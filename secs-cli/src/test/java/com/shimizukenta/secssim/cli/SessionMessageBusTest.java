package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
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
            SessionTestSupport.awaitPeer(session);
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

    /**
     * W 位请求的<b>应答</b>也必须进总线。
     *
     * <p>3.0 修的就是这个：应答由 reply manager 按 system bytes 直接消费掉，走不到
     * 普通接收监听，于是"手动发送"页面只看得到自己发的那条、看不到主机的回复——
     * 现场以为主机没应答。现在原始收帧另有 pass-through 通道，两个方向都进监控。</p>
     */
    @Test
    void secondaryReplyIsPublishedAsReceive() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);

        EventBus bus = new EventBus();
        List<MessageEvent> events = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(events::add);

        SessionSpec spec = new SessionSpec("bus2", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime session = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        session.eventBus(bus);
        session.open();

        try (SecsCommunicator client = connect(port)) {
            client.openAndWaitUntilCommunicating();
            SessionTestSupport.awaitPeer(session);
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
            assertTrue(result.get("ok").booleanValue(), result.toString());

            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && !hasIn(events, 1, 2)) {
                Thread.sleep(50L);
            }
        } finally {
            session.close();
        }

        assertTrue(events.stream().anyMatch(e -> "out".equals(e.direction())
                        && e.stream() == 1 && e.function() == 1),
                () -> "手动发出的 S1F1 应记一条发报: " + describe(events));
        assertTrue(hasIn(events, 1, 2),
                () -> "W 位应答（S1F2）必须也进监控，否则手动发送看不到响应: " + describe(events));
    }

    private static boolean hasIn(List<MessageEvent> events, int stream, int function) {
        return events.stream().anyMatch(e -> "in".equals(e.direction())
                && e.stream() == stream && e.function() == function);
    }

    private static SecsCommunicator connect(int port) throws Exception {
        HsmsSsCommunicatorConfig cfg = new HsmsSsCommunicatorConfig();
        cfg.protocol(HsmsSsProtocol.ACTIVE);
        cfg.socketAddress(new InetSocketAddress("127.0.0.1", port));
        cfg.deviceId(10);
        return HsmsSsCommunicator.open(cfg);
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
