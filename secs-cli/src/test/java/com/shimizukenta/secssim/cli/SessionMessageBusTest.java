package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 会话把收发的数据报文发布到事件总线，供 SSE / 日志 / 测试消费。
 */
class SessionMessageBusTest {

    @Test
    void receiveAndSendArePublished() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        EventBus bus = new EventBus();
        List<MessageEvent> events = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(events::add);

        SessionSpec spec = new SessionSpec("bus", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.eventBus(bus);
        session.open();

        HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
        cfg.protocol(HsmsSsProtocol.ACTIVE);
        cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
        cfg.deviceId(10);

        try (SecsCommunicator client = HsmsSsCommunicator.open(cfg)) {
            client.openAndWaitUntilCommunicating();
            client.send(1, 11, true, com.shimizukenta.secs.secs2.Secs2.getBuilder().list());

            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && events.size() < 2) {
                Thread.sleep(50L);
            }
        } finally {
            session.close();
        }

        boolean hasIn = events.stream().anyMatch(e -> "in".equals(e.direction()) && e.stream() == 1 && e.function() == 11);
        boolean hasOut = events.stream().anyMatch(e -> "out".equals(e.direction()) && e.stream() == 1 && e.function() == 12);

        assertTrue(hasIn, () -> "缺少 S1F11 的收报事件: " + describe(events));
        assertTrue(hasOut, () -> "缺少 S1F12 的发报事件: " + describe(events));
        assertTrue(events.stream().allMatch(e -> e.stream() >= 0), "控制帧（stream<0）不应出现在事件里");
    }

    private static String describe(List<MessageEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (MessageEvent event : events) {
            sb.append(event.sessionId()).append(' ').append(event.direction()).append(' ')
                    .append(event.sf()).append("; ");
        }
        return sb.toString();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
