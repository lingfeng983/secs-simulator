package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;

import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.HttpApiServer;
import com.shimizukenta.secssim.api.http.SessionsJson;
import com.shimizukenta.secssim.api.http.TriggerOutcome;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.config.SessionConfig;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 程序入口。
 *
 * <p>三种形态共用同一条启动路径，只有绑定地址、探针端口与鉴权策略不同。
 * 这是刻意的：一体化与远程服务端如果走两条代码路径，必然会出现
 * "单机能用、连服务器就不对"的问题。
 * </p>
 */
public final class Main {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long DEMO_SEED = 20240601L;

    private Main() {
    }

    /**
     * 进程入口。
     *
     * @param args 命令行
     */
    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            /* 单行摘要给桌面壳看；完整栈只在需要时输出，避免刷屏 */
            Log.error("启动失败", e);
            if (System.getenv("SECS_SIM_DEBUG") != null) {
                e.printStackTrace();
            }
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        RuntimeOptions options;
        try {
            options = RuntimeOptions.parse(args);
        } catch (IllegalArgumentException e) {
            Log.error("参数错误", e);
            System.err.print(RuntimeOptions.usage());
            return 2;
        }

        if (options.help()) {
            System.out.print(RuntimeOptions.usage());
            return 0;
        }

        String token = options.token().isBlank() ? newToken() : options.token();
        boolean utf8Ok = utf8RoundTrip(options.workDir());
        CountDownLatch stopped = new CountDownLatch(1);

        SessionManager sessions = new SessionManager();
        EventBus bus = new EventBus();
        SecsProbe probe = null;

        if (options.configFile() != null) {
            int code = startConfiguredSession(options, sessions, bus);
            if (code != 0) {
                return code;
            }
        } else if (options.demoSession()) {
            int code = startDemoSession(options, sessions, bus);
            if (code != 0) {
                return code;
            }
        } else if (options.mode() == RuntimeMode.PROBE) {
            probe = SecsProbe.open(options.hsmsPort());
        }

        StringBuilder extra = new StringBuilder();
        extra.append(",\"vm\":\"").append(vmKind()).append('"')
                .append(",\"utf8\":").append(utf8Ok)
                .append(",\"sessions\":").append(sessions.list().size());
        if (probe != null) {
            extra.append(",\"hsmsPort\":").append(options.hsmsPort());
        }

        ApiContext context = new Context(options.mode().id(), BuildInfo.version(),
                extra.toString(), stopped::countDown, sessions);

        HttpApiServer api = HttpApiServer.start(
                new InetSocketAddress(options.bindHost(), options.httpPort()), token, context,
                options.webRoot(), bus);

        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "shutdown-hook"));

        System.out.println("READY mode=" + options.mode().id()
                + " http=" + api.port()
                + " token=" + token
                + " utf8=" + utf8Ok
                + " sessions=" + sessions.list().size()
                + ((probe == null && !options.demoSession()) ? "" : " hsms=" + options.hsmsPort())
                + " work=" + options.workDir());
        System.out.flush();

        try {
            stopped.await();
        } finally {
            sessions.closeAll();
            if (probe != null) {
                probe.close();
            }
            api.close();
        }
        return 0;
    }

    private static int startConfiguredSession(RuntimeOptions options, SessionManager sessions, EventBus bus)
            throws IOException {
        SessionConfig config;
        try {
            config = SessionConfigJson.read(options.configFile());
        } catch (ConfigException e) {
            Log.error("配置错误", e);
            return 3;
        }

        CompilationResult compiled = config.compile();
        if (!compiled.ok()) {
            compiled.errors().forEach(e -> Log.error("配置错误: " + e));
            return 3;
        }

        SessionSpec configured = config.spec();
        SessionSpec spec = new SessionSpec(configured.id(), configured.passive(),
                configured.bindHost(),
                configured.port() == 0 ? options.hsmsPort() : configured.port(),
                configured.deviceId(), configured.seed(), configured.equipment());

        HsmsSessionRuntime runtime = new HsmsSessionRuntime(spec, compiled.equipment());
        runtime.eventBus(bus);
        runtime.enableSchedule(config.schedule(), config.maxEventsPerSec());
        runtime.open();
        sessions.register(runtime);
        return 0;
    }

    private static int startDemoSession(RuntimeOptions options, SessionManager sessions, EventBus bus)
            throws IOException {
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", options.deviceId());
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        if (!compiled.ok()) {
            compiled.errors().forEach(e -> Log.error("配置错误: " + e));
            return 3;
        }

        SessionSpec spec = new SessionSpec("demo", true, "127.0.0.1", options.hsmsPort(),
                options.deviceId(), DEMO_SEED, demo);

        HsmsSessionRuntime runtime = new HsmsSessionRuntime(spec, compiled.equipment());
        runtime.eventBus(bus);
        runtime.enableSchedule(new com.shimizukenta.secssim.core.event.EventSchedule(java.util.List.of(
                new com.shimizukenta.secssim.core.event.EventPlan("demo-process-start", 2001,
                        new com.shimizukenta.secssim.core.event.Trigger.Periodic(5_000L, 200L)))), 20);
        runtime.open();
        sessions.register(runtime);
        return 0;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String vmKind() {
        return System.getProperty("org.graalvm.nativeimage.imagecode", "jvm");
    }

    /**
     * 检查 workDir 下的中文文件名能否 UTF-8 往返。
     *
     * <p>检查完就把测试文件和空目录删掉：这是启动自检，不该在用户的目录里留垃圾。
     * 目录只在确实为空时才删除，绝不会带走用户自己的文件。
     * </p>
     */
    private static boolean utf8RoundTrip(Path workDir) {
        Path dir = null;
        Path file = null;

        try {
            dir = Files.createDirectories(workDir.resolve("腔体配置"));
            file = dir.resolve("模板-温度.json");
            String content = "{\"单元\":\"CH1\",\"名称\":\"温度设置值\",\"值\":180.5}";
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return content.equals(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.error("UTF-8 往返检查失败", e);
            return false;
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    Log.error("清理 UTF-8 自检文件失败", e);
                }
            }
            if (dir != null) {
                try {
                    Files.delete(dir);
                } catch (IOException ignored) {
                    /* 目录非空说明用户在里面放了东西，保留 */
                }
            }
        }
    }

    /** 运行时的 HTTP 上下文：只做转发，不含业务逻辑。 */
    private static final class Context implements ApiContext {

        private final String mode;
        private final String version;
        private final String extraHealthJson;
        private final Runnable shutdown;
        private final SessionManager sessions;

        Context(String mode, String version, String extraHealthJson, Runnable shutdown, SessionManager sessions) {
            this.mode = mode;
            this.version = version;
            this.extraHealthJson = extraHealthJson;
            this.shutdown = shutdown;
            this.sessions = sessions;
        }

        @Override
        public String mode() {
            return this.mode;
        }

        @Override
        public String version() {
            return this.version;
        }

        @Override
        public String extraHealthJson() {
            return this.extraHealthJson;
        }

        @Override
        public void requestShutdown() {
            this.shutdown.run();
        }

        @Override
        public String sessionsJson() {
            return SessionsJson.render(this.sessions.list());
        }

        @Override
        public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
            return this.sessions.find(sessionId)
                    .map(session -> session.previewJson(fromMs, toMs, buckets))
                    .orElse(null);
        }

        @Override
        public TriggerOutcome triggerCeid(String sessionId, int ceid) {
            SessionRuntime session = this.sessions.find(sessionId).orElse(null);

            if (session == null) {
                return TriggerOutcome.UNKNOWN_SESSION;
            }
            if (session.state() != SessionState.OPEN || !session.hasPeer()) {
                return TriggerOutcome.NOT_OPEN;
            }

            try {
                session.emitCeid(ceid);
                return TriggerOutcome.OK;
            } catch (IOException e) {
                Log.error("手动触发 CEID " + ceid + " 失败", e);
                return TriggerOutcome.FAILED;
            }
        }
    }
}
