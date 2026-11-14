package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

/**
 * S6F11 真链路：设备侧主动上报，主机侧接收。
 *
 * <p>验证 E30 的报文体结构（CEID + 报告列表 + 值列表），
 * 以及多单元设备最关键的一点：<b>报告里必须能区分是哪个单元</b>。
 * </p>
 */
class SessionS6F11Test {

    @Test
    void s6f11CarriesCeidReportAndValues() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        ManualEngineClock clock = new ManualEngineClock(0L);
        SessionSpec spec = new SessionSpec("e1", true, "127.0.0.1", port, 10, 424242L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock);
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);
            CompletableFuture<SecsMessage> received = captureS6F11(client);

            session.emitCeid(2001);
            Secs2 body = awaitBody(received);

            assertEquals(3, body.size(), "S6F11 报文体是 L[3]{DATAID, CEID, L{报告}}");
            assertEquals(0, Secs2Read.asInt(body.get(0)), "DATAID 固定为 0");
            assertEquals(2001, Secs2Read.asInt(body.get(1)), "第二个元素是 CEID");

            Secs2 reports = body.get(2);
            assertEquals(1, reports.size(), "该 CEID 挂了一个报告");

            Secs2 report = reports.get(0);
            assertEquals(3001, Secs2Read.asInt(report.get(0)), "报告里先写 RPTID");

            Secs2 values = report.get(1);
            assertEquals(3, values.size(), "STATUS 报告含 UNIT_ID / TEMP_PV / PRESS");
            assertEquals(1, Secs2Read.asInt(values.get(0)), "单元号在首位");
            assertEquals(session.valueStore().valueAt(1001, 0L).orElseThrow(),
                    Secs2Read.asDouble(values.get(1)), 1e-3, "温度取自值引擎");
            assertEquals(1L, session.emittedCount());
        } finally {
            session.close();
        }
    }

    @Test
    void s6f11WbitWaitsForS6f12() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec("w1", true, "127.0.0.1", port, 10, 424242L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.collectionEventWbit(true);
        session.open();

        try (SecsCommunicator client = connect(port)) {
            CompletableFuture<SecsMessage> received = new CompletableFuture<>();
            client.addSecsMessageReceiveListener(message -> {
                if (message.getStream() == 6 && message.getFunction() == 11) {
                    received.complete(message);
                    try {
                        client.send(message, 6, 12, false, Secs2.getBuilder().binary((byte) 0));
                    } catch (Exception e) {
                        received.completeExceptionally(e);
                    }
                }
            });
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);

            session.emitCeid(2001);

            SecsMessage primary = received.get(10L, TimeUnit.SECONDS);
            assertTrue(primary.wbit(), "开启后 S6F11 必须带 W 位");
            awaitEmitted(session, 1L, 5_000L);
            assertEquals(0L, session.counters().get("wbitDropped"), "队列不应丢帧");
        } finally {
            session.close();
        }
    }

    @Test
    void unitIdIsInjectedWhenReportOmitsIt() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec("e2", true, "127.0.0.1", port, 10, 7L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);
            CompletableFuture<SecsMessage> received = captureS6F11(client);

            session.emitCeid(2003);
            Secs2 report = awaitBody(received).get(2).get(0);

            assertEquals(3002, Secs2Read.asInt(report.get(0)));
            assertEquals(3, report.get(1).size(), "MEASURE 原本只有两个值，注入 UNIT_ID 后为三个");
            assertEquals(1, Secs2Read.asInt(report.get(1).get(0)), "注入的单元号在首位");
        } finally {
            session.close();
        }
    }

    @Test
    void unknownCeidIsRejected() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        SessionSpec spec = new SessionSpec("e3", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.open();

        try {
            assertThrows(IOException.class, () -> session.emitCeid(9999));
            assertEquals(0L, session.emittedCount());
        } finally {
            session.close();
        }
    }

    private static CompletableFuture<SecsMessage> captureS6F11(SecsCommunicator client) {
        CompletableFuture<SecsMessage> future = new CompletableFuture<>();
        client.addSecsMessageReceiveListener(message -> {
            if (message.getStream() == 6 && message.getFunction() == 11) {
                future.complete(message);
            }
        });
        return future;
    }

    private static Secs2 awaitBody(CompletableFuture<SecsMessage> future) throws Exception {
        SecsMessage message = future.get(10L, TimeUnit.SECONDS);
        assertEquals(6, message.getStream());
        assertEquals(11, message.getFunction());
        assertTrue(!message.wbit(), "本实现默认不带 W 位");
        return message.secs2();
    }

    private static void awaitEmitted(HsmsSessionRuntime session, long expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && session.emittedCount() < expected) {
            Thread.sleep(20L);
        }
        assertEquals(expected, session.emittedCount(), "收到 S6F12 后才算上报成功");
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
