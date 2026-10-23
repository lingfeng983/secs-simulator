package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/** 手动 SML：解析 → 发送 → 捕获回复。 */
class SessionManualSendTest {

    @Test
    void sendsWBitMessageAndCapturesReply() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec("manual", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);
            /* 设备侧手动发 S1F1，主机侧要真的回 S1F2，才能验证"捕获回复" */
            client.addSecsMessageReceiveListener(message -> {
                try {
                    if (message.getStream() == 1 && message.getFunction() == 1 && message.wbit()) {
                        client.send(message, 1, 2, false, Secs2.getBuilder().list());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            JsonHub result = JsonHub.fromJson(session.sendSml("S1F1 W."));
            assertTrue(result.get("ok").booleanValue());
            assertEquals(1, result.get("stream").intValue());
            assertEquals(1, result.get("function").intValue());
            assertTrue(result.get("wbit").booleanValue());
            assertFalse(result.get("timedOut").booleanValue());
            assertEquals(2, result.get("replyFunction").intValue(), "S1F1 W 应等到 S1F2");
            assertNotNull(result.get("replySml").optionalString().orElse(null));
            assertTrue(result.get("replySml").optionalString().orElse("").startsWith("<L"), result.toString());
        } finally {
            session.close();
        }
    }

    @Test
    void noReplyRequestDoesNotWait() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec("manual2", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);
            JsonHub result = JsonHub.fromJson(session.sendSml("S1F1"));
            assertFalse(result.get("wbit").booleanValue());
            assertFalse(result.get("timedOut").booleanValue());
            assertTrue(result.get("replySml").isNull());
        } finally {
            session.close();
        }
    }

    @Test
    void rejectsBadSmlAndMissingPeer() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec("manual3", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.open();

        try {
            assertThrows(IllegalStateException.class, () -> session.sendSml("S1F1"));
        } finally {
            session.close();
        }

        int port2 = freePort();
        SessionSpec spec2 = new SessionSpec("manual4", true, "127.0.0.1", port2, 10, 1L, demo);
        HsmsSessionRuntime session2 = new HsmsSessionRuntime(spec2, compiled.equipment(), new ManualEngineClock(0L));
        session2.open();
        try (SecsCommunicator client = connect(port2)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session2);
            assertThrows(IllegalArgumentException.class, () -> session2.sendSml("S1F1\n<L <U4 1>"));
        } finally {
            session2.close();
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
