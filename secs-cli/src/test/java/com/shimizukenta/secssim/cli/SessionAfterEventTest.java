package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * AfterEvent：A 在 t=1000 触发，B 在 t=1500，C 在 t=1700。
 */
class SessionAfterEventTest {

    @Test
    void delayedEventsFireInOrder() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        ManualEngineClock clock = new ManualEngineClock(0L);
        SessionSpec spec = new SessionSpec("chain", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock);
        session.enableSchedule(new EventSchedule(List.of(
                new EventPlan("a", 2001, new Trigger.At(1_000L)),
                new EventPlan("b", 2003, new Trigger.AfterEvent("a", 500L)),
                new EventPlan("c", 2002, new Trigger.AfterEvent("b", 200L)))), 20);
        /* 3.0 起计划默认禁用：本用例验证 AfterEvent 链的时序，先把三条都显式启动，
           否则整条链不会触发，断言会退化成"什么都没发生" */
        session.setEventEnabled("a", true);
        session.setEventEnabled("b", true);
        session.setEventEnabled("c", true);
        session.open();

        try (SecsCommunicator client = connect(port)) {
            BlockingQueue<SecsMessage> events = new LinkedBlockingQueue<>();
            client.addSecsMessageReceiveListener(message -> {
                if (message.getStream() == 6 && message.getFunction() == 11) {
                    events.add(message);
                }
            });
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);
            /* 主机上线流程：E30 要求 S1F13 建立通信、S1F17 请求 ONLINE；
               主动上报（S6F11/S6F1）只允许在 ONLINE 之后 */
            client.send(1, 13, true, Secs2.getBuilder().list());
            client.send(1, 17, true, Secs2.getBuilder().list());
            awaitPeer(session);

            clock.advanceMs(1_000L);
            session.pumpNow();
            assertEquals(2001, nextCeid(events), "t=1000 先发 A");

            clock.advanceMs(400L);
            session.pumpNow();
            assertTrue(events.isEmpty(), "t=1400 还不到 B 的 1500");

            clock.advanceMs(100L);
            session.pumpNow();
            assertEquals(2003, nextCeid(events), "t=1500 发 B");

            clock.advanceMs(200L);
            session.pumpNow();
            assertEquals(2002, nextCeid(events), "t=1700 发 C");
            assertEquals(3L, session.emittedCount());
        } finally {
            session.close();
        }
    }

    private static int nextCeid(BlockingQueue<SecsMessage> events) throws Exception {
        SecsMessage message = events.poll(3L, TimeUnit.SECONDS);
        assertNotNull(message, "应收到 S6F11");
        return Secs2Read.asInt(message.secs2().get(1));
    }

    private static void awaitPeer(HsmsSessionRuntime session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!session.hasPeer() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(session.hasPeer(), "设备侧应看到主机上线");
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
