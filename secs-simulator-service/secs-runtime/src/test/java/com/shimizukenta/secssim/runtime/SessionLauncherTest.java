package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionState;

/**
 * 会话生命周期：创建、关闭、热重载。
 *
 * <p>重点不在"能不能起得来"，而在两件容易出事的事：
 * 配置文件名<b>不能变成路径</b>，以及<b>配置坏了要保住旧会话</b>。
 * </p>
 */
class SessionLauncherTest {

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path workDir;

    private SessionManager sessions;
    private SessionLauncher launcher;
    private com.shimizukenta.secssim.store.ConfigRepository repo;

    @BeforeEach
    void setUp() {
        this.sessions = new SessionManager();
        this.launcher = new SessionLauncher(this.sessions, new EventBus(), this.workDir);
        /* 3.0：配置正文在 config.db，launcher 经门面读写 */
        var db = com.shimizukenta.secssim.store.Db.open(this.workDir.resolve("db").normalize());
        this.repo = new com.shimizukenta.secssim.store.ConfigRepository(db);
        this.launcher.configStore(new ConfigStore(this.repo,
                new com.shimizukenta.secssim.store.TemplateRepository(db), this.workDir));
    }

    @Test
    void createsSessionFromWorkDirConfig() throws Exception {
        write("line1.json", config("line1", "V1"));

        String id = this.launcher.create("line1.json");
        assertTrue(id.startsWith("ses_"), "会话 id 必须是 ses_ ULID: " + id);
        assertTrue(this.launcher.sourceOf(id).isPresent(), "建完必须记住配置来源，否则不能重载");
    }

    @Test
    void refusesPathTraversalAndNonJsonNames() {
        /* 接口给的是"名字"，不是一条路径；挡住 ../ 与分隔符就够了，
           比解析真实路径再比对更不容易出错 */
        assertConfigError("../evil.json");
        assertConfigError("sub/dir.json");
        assertConfigError("sub\\dir.json");
        assertConfigError("..\\evil.json");
        assertConfigError("C:evil.json");
        assertConfigError("line1.txt");
        assertConfigError("");
        assertConfigError("   ");
    }

    /**
     * 文件读不到要说"读不到文件"，不能报成"端口绑定失败"。
     *
     * <p>两者都是 IOException，不分开就会把排查方向直接带偏——
     * 现场会去查端口占用，而真正的原因是文件根本不在那儿。
     * </p>
     */
    @Test
    void missingConfigIsReportedAsConfigProblem() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> this.launcher.create("no-such.json"));

        assertTrue(error.getMessage().contains("不存在"), error.getMessage());
        assertTrue(this.sessions.list().isEmpty());
    }

    /**
     * 两个被动会话不能抢同一个端口。
     *
     * <p>Windows 上监听 socket 带 SO_REUSEADDR，后建的会话会"看起来启动成功"，
     * 但主机连到哪一个是不确定的。必须在启动路径上拒绝。</p>
     */
    @Test
    void rejectsSecondSessionOnSamePort() throws Exception {
        int port = freePort();
        write("a.json", configWithPort("a", "A", port));
        write("b.json", configWithPort("b", "B", port));
        String first = this.launcher.create("a.json");

        IOException error = assertThrows(IOException.class, () -> this.launcher.create("b.json"));
        assertTrue(error.getMessage().contains("占用"), error.getMessage());
        assertEquals(1, this.sessions.list().size(), "端口冲突时不能留下半个会话");
        assertTrue(this.sessions.find(first).isPresent(), "先建的会话要原样跑着");
    }

    /**
     * 端口被<b>外部进程</b>占用时，创建会话必须失败。
     *
     * <p>进程内的端口冲突由 requirePortFree 挡住；外部占用看不见。Windows 的
     * SO_REUSEADDR 还允许重复绑定——没有"预检 + 首次 bind 失败即失败"这两道，
     * 会话会显示 OPEN，端口上却没有监听。</p>
     */
    @Test
    void createFailsWhenPortIsHeldByAnotherProcess() throws Exception {
        int port = freePort();
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", port));

            write("ext.json", configWithPort("ext", "EXTERNAL", port));

            IOException error = assertThrows(IOException.class, () -> this.launcher.create("ext.json"));
            assertTrue(error.getMessage().contains("不可绑定"), error.getMessage());
            assertTrue(this.sessions.list().isEmpty(), "创建失败不能留下半个会话");
        }
    }

    /**
     * 同端口重建：改 deviceId（session 段变化）但端口不变。
     *
     * <p>「先开新再关旧」在这条路径上是不成立的：旧监听 socket 还占着端口。
     * 修复后应当「先关旧再开新」，且新会话真的在监听。</p>
     */
    @Test
    void reloadSamePortRebuildsAndKeepsListening() throws Exception {
        int port = freePort();
        write("same.json", configWithPort("same", "V1", port));
        String id = this.launcher.create("same.json");

        write("same.json", configWithPort("same", "V2", port)
                .replace("\"deviceId\": 61", "\"deviceId\": 62"));

        assertTrue(this.launcher.reload(id), "同端口重建必须成功");
        assertEquals(SessionState.OPEN, this.sessions.find(id).orElseThrow().state());

        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
            assertTrue(probe.isConnected(), "重建后端口上必须有监听");
        }
    }

    @Test
    void closeRemovesSessionAndItsSource() throws Exception {
        write("line1.json", config("line1", "V1"));
        String id = this.launcher.create("line1.json");

        assertTrue(this.launcher.close(id));
        assertFalse(this.sessions.find(id).isPresent());
        assertFalse(this.launcher.sourceOf(id).isPresent());
        assertFalse(this.launcher.close(id), "重复关闭应如实返回 false");
    }

    @Test
    void reloadPicksUpEditedConfig() throws Exception {
        write("line1.json", config("line1", "BEFORE"));
        String id = this.launcher.create("line1.json");

        assertTrue(this.sessions.find(id).orElseThrow().deviceName().contains("BEFORE"));

        write("line1.json", config("line1", "AFTER"));
        assertTrue(this.launcher.reload(id));

        assertTrue(this.sessions.find(id).orElseThrow().deviceName().contains("AFTER"));
    }

    /** 只动事件编排：热生效，会话实例不变、主机连接不断。 */
    @Test
    void reloadWithOnlyEventChangeKeepsSameSessionInstance() throws Exception {
        write("line1.json", config("line1", "V1"));
        String id = this.launcher.create("line1.json");

        com.shimizukenta.secssim.app.session.SessionRuntime before =
                this.sessions.find(id).orElseThrow();

        write("line1.json", configWithPlan("line1", "V1", "beat", 9000));
        assertTrue(this.launcher.reload(id));

        com.shimizukenta.secssim.app.session.SessionRuntime after =
                this.sessions.find(id).orElseThrow();
        assertSame(before, after, "结构与连接标识都没变：必须原地热生效而不是重建");
        String events = ((com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime) after).eventsJson();
        assertTrue(events.contains("9000"), "新编排要可见: " + events);
    }

    /** 设备结构变化也热生效：实例不变、连接不断，新模型立即可见。 */
    @Test
    void reloadWithEquipmentChangeKeepsSameSessionInstance() throws Exception {
        write("line1.json", config("line1", "BEFORE"));
        String id = this.launcher.create("line1.json");

        com.shimizukenta.secssim.app.session.SessionRuntime before =
                this.sessions.find(id).orElseThrow();
        assertTrue(before.deviceName().contains("BEFORE"));

        write("line1.json", config("line1", "AFTER"));
        assertTrue(this.launcher.reload(id));

        com.shimizukenta.secssim.app.session.SessionRuntime after =
                this.sessions.find(id).orElseThrow();
        assertSame(before, after, "只有连接标识变化才重建；设备结构变化热生效");
        assertTrue(after.deviceName().contains("AFTER"), "新设备模型要立即可见");
    }

    /** 连接标识（session 段）变化必须重建：实例换新。 */
    @Test
    void reloadWithSessionChangeReplacesSession() throws Exception {
        write("line1.json", configWithPort("line1", "V1", 4701));
        String id = this.launcher.create("line1.json");

        com.shimizukenta.secssim.app.session.SessionRuntime before =
                this.sessions.find(id).orElseThrow();

        write("line1.json", configWithPort("line1", "V1", 4702));
        assertTrue(this.launcher.reload(id));

        com.shimizukenta.secssim.app.session.SessionRuntime after =
                this.sessions.find(id).orElseThrow();
        assertNotSame(before, after, "连接标识变了：必须关旧开新");
    }

    /** 配置坏了要保住旧会话：一个手滑的逗号不该让设备凭空消失。 */
    @Test
    void brokenConfigLeavesOldSessionRunning() throws Exception {
        write("line1.json", config("line1", "V1"));
        String id = this.launcher.create("line1.json");

        /* 存一份 JSON 合法但配置非法的正文（仓库保证入库正文可解析，
           所以"写坏"的现代形态是语义非法：版本号越界） */
        this.repo.saveDocument("line1", "{\"schemaVersion\": 99}", null, "test", "");

        assertThrows(ConfigException.class, () -> this.launcher.reload(id));
        assertTrue(this.sessions.find(id).isPresent(), "配置坏了旧会话必须还在");
        assertEquals(SessionState.OPEN, this.sessions.find(id).orElseThrow().state());
    }

    /**
     * R1b 故障注入（UP-04）：新会话已经打开、旧会话已经关闭，注册这一步失败时
     * 必须用 lastGood 快照把旧配置拉回来，不能让设备凭空消失或停在新端口上。
     */
    @Test
    void reloadRestoresOldSessionWhenRegistrationFails() throws Exception {
        int oldPort = freePort();
        int newPort = freePort();
        while (newPort == oldPort) {
            newPort = freePort();
        }
        write("line1.json", configWithPort("line1", "V1", oldPort));
        String id = this.launcher.create("line1.json");
        assertEquals(oldPort, this.sessions.find(id).orElseThrow().hostTarget().port());

        write("line1.json", configWithPort("line1", "V2", newPort));
        this.launcher.registerFailureHook = runtime -> {
            throw new IllegalStateException("注入：注册失败");
        };
        try {
            assertThrows(IllegalStateException.class, () -> this.launcher.reload(id));
            com.shimizukenta.secssim.app.session.SessionRuntime restored =
                    this.sessions.find(id).orElseThrow(
                            () -> new AssertionError("重载失败后设备不能消失"));
            assertEquals(SessionState.OPEN, restored.state(), "回滚后的会话必须仍在运行");
            assertEquals(oldPort, restored.hostTarget().port(), "回滚后仍监听旧端口");
        } finally {
            this.launcher.registerFailureHook = null;
        }
    }

    /**
     * 部署期覆盖：端口/deviceId 只作用于运行中的会话，不写回配置文件。
     *
     * <p>覆盖只在启动期生效（不写回文件），重载时会原样再套一遍。</p>
     */
    @Test
    void overridesChangePortWithoutRewritingFile() throws Exception {
        int filePort = freePort();
        int overridePort = freePort();
        while (overridePort == filePort) {
            /* 两次 freePort 偶尔拿到同一个号：“覆盖生效”的前提就没了 */
            overridePort = freePort();
        }
        assertNotEquals(filePort, overridePort);
        /* startFromFile 是外部文件入口：这个用例的文件必须真实存在 */
        Path file = this.workDir.resolve("ov.json");
        Files.writeString(file, configWithPort("ov", "OV", filePort), StandardCharsets.UTF_8);
        String before = Files.readString(file, StandardCharsets.UTF_8);

        var session = this.launcher.startFromFile(file,
                new SessionOverrides(null, overridePort, 62));
        try {
            assertEquals(SessionState.OPEN, session.state());

            /* 监听可能晚于 start 返回一拍：用 deadline 轮询，而不是只看第一次 connect */
            long deadline = System.currentTimeMillis() + 5_000L;
            boolean listening = false;
            while (!listening && System.currentTimeMillis() < deadline) {
                try (java.net.Socket probe = new java.net.Socket()) {
                    probe.connect(new InetSocketAddress("127.0.0.1", overridePort), 1_000);
                    listening = true;
                } catch (IOException retry) {
                    Thread.sleep(20L);
                }
            }
            assertTrue(listening, "覆盖端口 " + overridePort + " 上必须有监听");

            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
                    "覆盖不能写回配置文件");
        } finally {
            session.close();
        }
    }

    private void assertConfigError(String name) {
        assertThrows(ConfigException.class, () -> this.launcher.create(name), "应拒绝: '" + name + "'");
    }

    private void write(String name, String content) throws IOException {
        String stem = name.substring(0, name.length() - 5);
        try {
            if (this.repo.findDevice(stem).isPresent()) {
                this.repo.saveDocument(stem, content, null, "test", "");
            } else {
                this.repo.createDevice(stem, null, content, true, "test");
            }
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** 同 config，但带一个周期事件计划（热生效测试用）。 */
    private String configWithPlan(String id, String marker, String planId, int periodMs) {
        return config(id, marker).replace(
                "\"plans\": []",
                "\"plans\": [{ \"id\": \"" + planId + "\", \"ceid\": 2001, "
                        + "\"trigger\": { \"type\": \"periodic\", \"periodMs\": " + periodMs + ", \"jitterMs\": 0 } }]");
    }

    /**
     * 一份最小可编译配置。
     *
     * <p>设备名带上 marker：重载有没有生效，要能从会话本身看出来，
     * 而不是只看接口回了 200。
     * </p>
     */
    /* 每份配置一个唯一端口:端口现在必填,多台设备不能共用一个口。
       按 id 记忆——热生效测试对同一 id 写两次配置,两次必须同口(连接标识不变才算热生效);
       放在实例上,@TempDir 每用例新建,跨用例互不残留。 */
    private final java.util.concurrent.ConcurrentMap<String, Integer> portById =
            new java.util.concurrent.ConcurrentHashMap<>();
    /* 类级递增:上个用例的监听器不一定显式关闭,端口在整个测试类里绝不能复用 */
    private static final java.util.concurrent.atomic.AtomicInteger portSeq =
            new java.util.concurrent.atomic.AtomicInteger(5020);

    private int nextPort() {
        return portSeq.getAndIncrement();
    }

    private String config(String id, String marker) {
        return configWithPort(id, marker, portById.computeIfAbsent(id, (key) -> this.nextPort()));
    }

    private String configWithPort(String id, String marker, int port) {
        return """
                {"schemaVersion": 7,
                 "session": {"id": "%s", "passive": true, "bindHost": "127.0.0.1",
                             "port": %d, "deviceId": 61, "seed": 3},
                 "equipment": {"name": "LINE-%s", "idSpace": {},
                   "units": [{ "unitId": 1, "name": "CH1", "injectUnitId": false,
                     "svids": [{ "offset": 1, "slot": "TEMP", "type": "F4", "unit": "C" }],
                     "reports": [],
                     "ceids": [{ "offset": 1, "slot": "BEAT", "reports": [] }] }]},
                 "events": {"maxPerSec": 5, "plans": []}}
                """.formatted(id, port, marker);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("找不到可用端口", e);
        }
    }
}
