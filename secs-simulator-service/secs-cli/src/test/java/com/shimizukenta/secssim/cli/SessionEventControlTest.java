package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
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
import com.shimizukenta.secssim.app.session.PlanEnableOutcome;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/** 运行时事件编排控制：列表、禁用期间不触发、重新启用恢复。 */
class SessionEventControlTest {

    @Test
    void disablesAndReenablesAPlanAtRuntime() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        ManualEngineClock clock = new ManualEngineClock(0L);
        SessionSpec spec = new SessionSpec("evt", true, "127.0.0.1", port, 10, 7L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), clock);
        session.enableSchedule(new EventSchedule(List.of(
                new EventPlan("beat", 2001, new Trigger.Periodic(1_000L, 0L)))), 10);
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

            JsonHub before = JsonHub.fromJson(session.eventsJson());
            assertEquals(1, before.get("events").length());
            assertFalse(before.get("events").get(0).get("enabled").booleanValue(),
                    "加载后默认禁用，等操作员显式启动");
            assertEquals("periodic", before.get("events").get(0).get("triggerType").optionalString().orElse(""));

            assertEquals(PlanEnableOutcome.OK, session.setEventEnabled("beat", true));
            JsonHub started = JsonHub.fromJson(session.eventsJson());
            assertTrue(started.get("events").get(0).get("enabled").booleanValue(), "启动后应显示运行中");

            /* D2：周期计划启动即触发一条（过限流走台账）。
               发送是入队的，计数在队列线程里自增——先等收到报文，再断言计数，
               否则会读到「已入队但还没发出去」的 0。 */
            assertNotNull(events.poll(5L, TimeUnit.SECONDS), "启动即发一条");
            assertEquals(1L, session.emittedCount());

            clock.advanceMs(1_000L);
            session.pumpNow();
            assertNotNull(events.poll(5L, TimeUnit.SECONDS), "enabledAt + period 触发下一条");
            assertEquals(2L, session.emittedCount());

            assertEquals(PlanEnableOutcome.OK, session.setEventEnabled("beat", false));
            JsonHub disabled = JsonHub.fromJson(session.eventsJson());
            assertFalse(disabled.get("events").get(0).get("enabled").booleanValue());

            clock.advanceMs(1_000L);
            session.pumpNow();
            assertNull(events.poll(300L, TimeUnit.MILLISECONDS), "禁用期间不应再上报");
            assertEquals(2L, session.emittedCount());

            assertEquals(PlanEnableOutcome.UNKNOWN_PLAN, session.setEventEnabled("missing", true),
                    "未知计划应返回 UNKNOWN_PLAN");

            /* 重新启用：锚点重置为现在，又立即触发一条 */
            assertEquals(PlanEnableOutcome.OK, session.setEventEnabled("beat", true));
            clock.advanceMs(1_000L);
            session.pumpNow();
            assertNotNull(events.poll(5L, TimeUnit.SECONDS), "重启周期后 enabledAt + period 再触发");
            /* 这一次会同时产生两条（启动即触发 + period）：发送入队后计数异步，
               等到 4 再断言，避免读到队列还没消费完的中间值 */
            long deadline = System.currentTimeMillis() + 5_000L;
            while (session.emittedCount() < 4L && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(4L, session.emittedCount());
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
