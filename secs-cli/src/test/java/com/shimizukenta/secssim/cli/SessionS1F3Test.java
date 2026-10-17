package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * 真协议链路：JVM 侧主动连接打会话的被动端口，验证 S1F3 → S1F4 取的是值引擎的数据。
 *
 * <p>用手动时钟把时间钉死在 0，断言才能精确到"就是引擎在 t=0 算出的那个值"，
 * 而不是"某个区间内的某个数"。
 * </p>
 */
class SessionS1F3Test {

    @Test
    void s1f1AndS1f3AreAnsweredFromValueEngine() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        assertTrue(compiled.ok(), () -> "配置不合法: " + compiled.issues());

        ManualEngineClock clock = new ManualEngineClock(0L);
        SessionSpec spec = new SessionSpec("t1", true, "127.0.0.1", port, 10, 424242L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock);
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();

            Optional<SecsMessage> linktest = client.send(1, 1, true);
            assertTrue(linktest.isPresent(), "S1F1 没有应答");
            assertEquals(2, linktest.get().getFunction());

            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = client.send(1, 3, true,
                    b.list(b.uint4(1001L), b.uint4(1090L), b.uint4(1101L)));
            assertTrue(reply.isPresent(), "S1F3 没有应答");
            assertEquals(1, reply.get().getStream());
            assertEquals(4, reply.get().getFunction());

            Secs2 body = reply.get().secs2();
            assertEquals(3, body.size(), "S1F4 的个数必须与请求一致");

            assertEquals(session.valueStore().valueAt(1001, 0L).orElseThrow(),
                    Secs2Read.asDouble(body.get(0)), 1e-3, "CH1 温度应等于引擎在 t=0 的值");
            assertEquals(1, Secs2Read.asInt(body.get(1)), "UNIT_ID 必须是 CH1 的单元号");
            assertEquals(session.valueStore().valueAt(1101, 0L).orElseThrow(),
                    Secs2Read.asDouble(body.get(2)), 1e-3, "CH2 温度应等于引擎在 t=0 的值");
        } finally {
            session.close();
        }
    }

    @Test
    void unknownSvidReturnsZeroAndKeepsLinkAlive() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec("t2", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), EngineClock.manual(0L));
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();

            Secs2Builder b = Secs2.getBuilder();
            Optional<SecsMessage> reply = client.send(1, 3, true, b.list(b.uint4(9999L)));
            assertTrue(reply.isPresent());
            assertEquals(0, Secs2Read.asInt(reply.get().secs2().get(0)),
                    "未知 SVID 返回 0，比断开连接更友好");

            Optional<SecsMessage> after = client.send(1, 1, true);
            assertTrue(after.isPresent(), "遇到未知 SVID 后链路必须仍然可用");
        } finally {
            session.close();
        }
    }

    @Test
    void valuesMoveAsTimeAdvances() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        ManualEngineClock clock = new ManualEngineClock(0L);
        SessionSpec spec = new SessionSpec("t3", true, "127.0.0.1", port, 10, 20240601L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock);
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            Secs2Builder b = Secs2.getBuilder();

            double first = Secs2Read.asDouble(client.send(1, 3, true, b.list(b.uint4(1001L)))
                    .orElseThrow().secs2().get(0));

            clock.advanceMs(120_000L);

            double later = Secs2Read.asDouble(client.send(1, 3, true, b.list(b.uint4(1001L)))
                    .orElseThrow().secs2().get(0));

            assertNotEquals(first, later, 1e-6, "两分钟后同一个 SVID 必须已经变化");
            assertTrue(later >= 0.0 && later <= 400.0, "取值必须落在声明值域内: " + later);
        } finally {
            session.close();
        }
    }

    @Test
    void emptyS1F3ReturnsAllSvids() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec("all", true, "127.0.0.1", port, 10, 424242L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), EngineClock.manual(0L));
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();

            Optional<SecsMessage> reply = client.send(1, 3, true, Secs2.getBuilder().list());
            assertTrue(reply.isPresent(), "S1F3 空列表没有应答");

            Secs2 body = reply.get().secs2();
            assertEquals(compiled.equipment().svids().size(), body.size(),
                    "E5：空 SVID 列表表示全部变量");
            assertEquals(session.valueStore().valueAt(1001, 0L).orElseThrow(),
                    Secs2Read.asDouble(body.get(0)), 1e-3, "首个值应是最小 SVID 的当前值");
            assertEquals(2, Secs2Read.asInt(body.get(body.size() - 1)),
                    "最后一个值应是 CH2 的 UNIT_ID");
        } finally {
            session.close();
        }
    }

    @Test
    void globalSvidIsServedWithConfiguredBound() throws Exception {
        int port = freePort();
        EquipmentSpec equipment = new EquipmentSpec("GLOBAL-01", 10,
                List.of(new UnitSpec(UnitId.of(1), "CH1",
                        List.of(new SvidDef(1, "TEMP_PV", ValueType.F4, "C", 2, 0.0, 400.0)),
                        List.of(), List.of(), false)),
                List.of(new GlobalSvidDef(9001, "FACILITY_TEMP", ValueType.F4, 18.0, 26.0)),
                List.of(), IdSpacePolicy.defaults());

        CompilationResult compiled = EquipmentCompiler.compile(equipment);
        assertTrue(compiled.ok(), () -> "配置不合法: " + compiled.issues());

        SessionSpec spec = new SessionSpec("g1", true, "127.0.0.1", port, 10, 7L, equipment);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), EngineClock.manual(0L));
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();

            Optional<SecsMessage> reply = client.send(1, 3, true,
                    Secs2.getBuilder().list(Secs2.getBuilder().uint4(9001L)));
            assertTrue(reply.isPresent(), "S1F3 没有应答");

            double value = Secs2Read.asDouble(reply.get().secs2().get(0));
            assertTrue(value >= 18.0 && value <= 26.0,
                    () -> "global SVID 必须使用配置值域，实际 " + value);
        } finally {
            session.close();
        }
    }

    private static SecsCommunicator connect(int port) throws Exception {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress("127.0.0.1", port));
        config.deviceId(10);
        return HsmsSsCommunicator.open(config);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
