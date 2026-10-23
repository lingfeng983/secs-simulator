package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secssim.app.config.SessionConfigJson;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.runtime.ConfigStore;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * S2F13/F14 查设备常量、S2F15/F16 写设备常量（ECV）。
 *
 * <p>主机调机时这两步是刚需：查出当前超时门限、改一个批量大小。
 * 落 S9F5 会让主机认为设备不支持参数化。</p>
 */
class SessionEquipmentConstantTest {

    /** S2F13 空列表 = 全部常量，顺序按 ECID 升序。 */
    @Test
    void queryAllReturnsEveryConstantInOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();

            SecsMessage reply = f.send(2, 13, Secs2.getBuilder().list());
            assertEquals(14, reply.getFunction(), "S2F13 应回 S2F14");

            /* S2F14 只回值（不带头信息），顺序即 ECID 升序 */
            Secs2 body = reply.secs2();
            assertEquals(3, body.size(), "配置了 3 个常量");
            assertEquals(5.0, body.get(0).getDouble(0), 1e-9, "501 TEMP_LIMIT");
            assertEquals(100.0, body.get(1).getDouble(0), 1e-9, "502 BATCH_SIZE");
            assertEquals(20.0, body.get(2).getDouble(0), 1e-9, "503 RETRY_COUNT");
        }
    }

    /**
     * 指定 ECID 查询：应答必须与请求<b>一一对应</b>。
     *
     * <p>主机按自己请求的顺序解释返回值，顺序错了会把超时门限当成温度用。</p>
     */
    @Test
    void queryKeepsRequestOrderAndFillsUnknownWithZero() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();

            Secs2Builder b = Secs2.getBuilder();
            SecsMessage reply = f.send(2, 13, b.list(b.uint4(503), b.uint4(9999), b.uint4(501)));
            Secs2 body = reply.secs2();

            assertEquals(3, body.size(), "四个请求三个有效，长度按请求数");
            assertEquals(20.0, body.get(0).getDouble(0), 1e-9, "503 = 请求的第一个");
            assertEquals(0, Secs2Read.asInt(body.get(1)), "未知 ECID 回 0，不能漏项");
            assertEquals(5.0, body.get(2).getDouble(0), 1e-9, "501 = 请求的第三个");
        }
    }

    /** 写入后立刻能查到新值。 */
    @Test
    void setThenQueryReturnsNewValue() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();

            assertEquals(0, f.setConstant(501, 42.5), "EAC=0 接受");

            Secs2Builder b = Secs2.getBuilder();
            Secs2 body = f.send(2, 13, b.list(b.uint4(501))).secs2();
            assertEquals(42.5, body.get(0).getDouble(0), 1e-9);
        }
    }

    /**
     * 未 ONLINE 时不得写：EAC=3。
     *
     * <p>假装写入比拒绝更糟——主机以为参数生效了，后续动作全按新参数走。</p>
     */
    @Test
    void setBeforeOnlineIsRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(3, f.setConstant(501, 1.0), "未 ONLINE 回 EAC=3");

            Secs2Builder b = Secs2.getBuilder();
            Secs2 body = f.send(2, 13, b.list(b.uint4(501))).secs2();
            assertEquals(5.0, body.get(0).getDouble(0), 1e-9, "值不应被改动");
        }
    }

    /** 未知 ECID：整批拒绝（EAC=1），不做部分写入。 */
    @Test
    void unknownConstantRejectsWholeBatch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();

            Secs2Builder b = Secs2.getBuilder();
            Secs2 pairs = b.list(
                    b.list(b.uint4(501), b.uint4(11)),
                    b.list(b.uint4(9999), b.uint4(22)));
            assertEquals(1, f.setRaw(pairs), "含未知 ECID → EAC=1");

            Secs2Builder q = Secs2.getBuilder();
            Secs2 body = f.send(2, 13, q.list(q.uint4(501))).secs2();
            assertEquals(5.0, body.get(0).getDouble(0), 1e-9,
                    "整批不生效：501 不能被写成 11");
        }
    }

    /** 值越界或不合法：EAC=2。 */
    @Test
    void outOfRangeValueIsRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.goOnline();

            /* 502 是 U2，上界 65535 */
            assertEquals(2, f.setConstant(502, 70000), "U2 越界 → EAC=2");
            assertEquals(2, f.setConstant(502, 1.5), "U2 非整数 → EAC=2");
            assertEquals(0, f.setConstant(502, 65535), "上界本身合法");
        }
    }

    /**
     * 落盘后重载保留——这是"可写、落盘保留"的核心承诺。
     *
     * <p>从配置文件装载会话 → 主机 S2F15 改值 → 重新从<b>同一个文件</b>解析，
     * 新值必须在。只留在内存里的话，重启就回到出厂值，现场会认为设备"忘了设置"。</p>
     */
    @Test
    void writtenValueSurvivesConfigReload(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("ecv.json");
        Files.writeString(config, CONFIG, StandardCharsets.UTF_8);

        int port = freePort();
        int deviceId = 10;
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", deviceId);

        /* 用真实文件驱动：常量初值来自文件 */
        HsmsSessionRuntime session = openFrom(config, port, demo);

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);

            client.send(1, 13, true, Secs2.getBuilder().list());
            client.send(1, 17, true, Secs2.getBuilder().list());

            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = client.send(2, 15, true,
                    b.list(b.uint4(0), b.list(b.list(b.uint4(501), b.float4(77.25f)))));
            assertTrue(reply.isPresent(), "S2F15 应回 S2F16");
            assertEquals(0, Secs2Read.asByte(reply.get().secs2()), "EAC=0");
        } finally {
            session.close();
        }

        /* 重新解析同一个文件：新值必须写进去了 */
        String reloaded = Files.readString(config, StandardCharsets.UTF_8);
        double stored = SessionConfigJson.parse(reloaded).spec().equipment().constants().stream()
                .filter(c -> c.id() == 501)
                .findFirst()
                .orElseThrow()
                .value();
        assertEquals(77.25, stored, 1e-6, "S2F15 的值必须落盘");
    }

    /** 配置文件里的其它段在这次写入后必须原样保留。 */
    @Test
    void writingConstantsKeepsOtherConfigSections(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("ecv.json");
        Files.writeString(config, CONFIG, StandardCharsets.UTF_8);

        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        HsmsSessionRuntime session = openFrom(config, port, demo);

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);
            client.send(1, 13, true, Secs2.getBuilder().list());
            client.send(1, 17, true, Secs2.getBuilder().list());

            Secs2Builder b = Secs2.getBuilder();
            client.send(2, 15, true,
                    b.list(b.uint4(0), b.list(b.list(b.uint4(502), b.uint2(1234)))));
        } finally {
            session.close();
        }

        String reloaded = Files.readString(config, StandardCharsets.UTF_8);
        var parsed = SessionConfigJson.parse(reloaded);
        assertEquals("ECV-EQ", parsed.spec().equipment().name(), "设备名不能被冲掉");
        assertEquals("ecv-1", parsed.spec().id(), "会话 id 不能被冲掉");
        assertEquals(2, parsed.spec().equipment().units().size(), "单元定义不能被冲掉");
    }

    /** 从配置文件建会话，并把常量落盘接回该文件（复刻 SessionLauncher 的接线）。 */
    private static HsmsSessionRuntime openFrom(Path config, int port, EquipmentSpec demo) throws Exception {
        var parsed = SessionConfigJson.parse(Files.readString(config, StandardCharsets.UTF_8));
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec(parsed.spec().id(), true, "127.0.0.1", port,
                parsed.spec().deviceId(), parsed.spec().seed(), parsed.spec().equipment());

        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.constantSaver(constants -> ConfigStore.writeConstants(config, constants));
        session.open();
        return session;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static SecsCommunicator connect(int port) throws Exception {
        HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
        cfg.protocol(HsmsSsProtocol.ACTIVE);
        cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
        cfg.deviceId(10);
        return HsmsSsCommunicator.open(cfg);
    }

    private static final String CONFIG = """
            {
              "schemaVersion": 5,
              "session": { "id": "ecv-1", "passive": true, "bindHost": "127.0.0.1",
                           "port": 5000, "deviceId": 10, "seed": 7 },
              "equipment": {
                "name": "ECV-EQ",
                "idSpace": {},
                "constants": [
                  { "id": 501, "name": "TEMP_LIMIT", "type": "F4", "value": 5.0, "unit": "C" },
                  { "id": 502, "name": "BATCH_SIZE", "type": "U2", "value": 100 },
                  { "id": 503, "name": "RETRY_COUNT", "type": "U4", "value": 20 }
                ],
                "units": [
                  { "unitId": 1, "name": "CH1", "injectUnitId": true,
                    "svids": [
                      { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C" },
                      { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                    ],
                    "reports": [ { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV"] } ],
                    "ceids": [ { "offset": 1, "slot": "PROCESS_START", "reports": ["STATUS"] } ] },
                  { "unitId": 2, "name": "CH2", "injectUnitId": true,
                    "svids": [
                      { "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C" },
                      { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
                    ],
                    "reports": [ { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV"] } ],
                    "ceids": [ { "offset": 1, "slot": "PROCESS_START", "reports": ["STATUS"] } ] }
                ]
              }
            }
            """;

    /** 会话 + 客户端夹具（常量为内存态，用于协议行为用例）。 */
    private static final class Fixture implements AutoCloseable {

        private final HsmsSessionRuntime session;
        private final SecsCommunicator client;

        Fixture() throws Exception {
            int port = freePort();
            EquipmentSpec base = DemoEquipment.twoChambers("DEMO-01", 10);

            EquipmentSpec withConstants = new EquipmentSpec(base.name(), base.deviceId(),
                    base.units(), base.globalSvids(), base.globalCeids(), IdSpacePolicy.defaults(),
                    parseConstants());

            CompilationResult compiled = EquipmentCompiler.compile(withConstants);
            SessionSpec spec = new SessionSpec("ecv", true, "127.0.0.1", port, 10, 1L, withConstants);

            this.session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
            this.session.open();

            HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
            cfg.protocol(HsmsSsProtocol.ACTIVE);
            cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
            cfg.deviceId(10);
            this.client = HsmsSsCommunicator.open(cfg);
            this.client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(this.session);
        }

        private static java.util.List<com.shimizukenta.secssim.core.domain.EquipmentConstantDef> parseConstants() {
            return java.util.List.of(
                    new com.shimizukenta.secssim.core.domain.EquipmentConstantDef(501, "TEMP_LIMIT",
                            com.shimizukenta.secssim.core.domain.ValueType.F4, 5.0, "C"),
                    new com.shimizukenta.secssim.core.domain.EquipmentConstantDef(502, "BATCH_SIZE",
                            com.shimizukenta.secssim.core.domain.ValueType.U2, 100),
                    new com.shimizukenta.secssim.core.domain.EquipmentConstantDef(503, "RETRY_COUNT",
                            com.shimizukenta.secssim.core.domain.ValueType.U4, 20));
        }

        void goOnline() throws Exception {
            this.send(1, 13, Secs2.getBuilder().list());
            this.send(1, 17, Secs2.getBuilder().list());
        }

        SecsMessage send(int stream, int function, Secs2 body) throws Exception {
            Optional<SecsMessage> reply = this.client.send(stream, function, true, body);
            assertNotNull(reply, "S" + stream + "F" + function + " 应答应存在");
            assertTrue(reply.isPresent(), "S" + stream + "F" + function + " 应答应存在");
            return reply.get();
        }

        int setConstant(int ecid, double value) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            Secs2 pairs = b.list(b.list(b.uint4(ecid),
                    b.float8(value)));
            return this.setRaw(pairs);
        }

        int setRaw(Secs2 pairs) throws Exception {
            Secs2Builder b = Secs2.getBuilder();
            SecsMessage reply = this.send(2, 15, b.list(b.uint4(0), pairs));
            assertEquals(16, reply.getFunction(), "S2F15 应回 S2F16");
            return Secs2Read.asByte(reply.secs2());
        }

        @Override
        public void close() {
            try {
                this.client.close();
            } catch (Exception ignore) {
                /* 测试收尾 */
            }
            this.session.close();
        }
    }
}
