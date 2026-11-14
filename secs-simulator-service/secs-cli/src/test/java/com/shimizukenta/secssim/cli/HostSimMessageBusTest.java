package com.shimizukenta.secssim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.runtime.hostsim.HostSimulator;
import com.shimizukenta.secssim.runtime.session.DemoEquipment;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 主机实例的报文可见性。
 *
 * <p>消息监控页要"所有主机（含远程）的收发都看得到"。两条通道的分工必须清楚：</p>
 * <ul>
 *   <li>本机主机：报文走设备会话，设备侧发布的那一份<b>不带 hostId</b>；
 *       主机实例不能再发一遍，否则监控页每条都出现两次；</li>
 *   <li>远程主机：对端不在本进程，设备会话看不见它，
 *       必须由主机实例自己发布，并带 {@code hostId} 归属到实例上。</li>
 * </ul>
 */
class HostSimMessageBusTest {

    @Test
    @Timeout(60)
    void remoteHostPublishesItsTrafficWithHostId() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec("remote-dst", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime device = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));
        device.open();

        EventBus bus = new EventBus();
        List<MessageEvent> events = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(events::add);

        HostSimulator remote = null;
        try {
            /* 远程主机 = 以另一条 ACTIVE 连接连同一台设备；
               对端在这个进程里只是测试的便利，语义与"连别的机器"一致：
               它不经过 device 会话的事件总线。 */
            remote = HostSimulator.open("host-r", "remote",
                    new HsmsConnectionTarget("127.0.0.1", port, 10), null);
            remote.eventBus(bus);
            SessionTestSupport.awaitPeer(device);

            JsonHub reply = JsonHub.fromJson(remote.sendSml("S1F1 W."));
            assertTrue(reply.get("ok").booleanValue(), "远程主机发送失败");
            assertEquals(2, reply.get("replyFunction").intValue(), "远程主机应收到 S1F2");

            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && hostEvents(events, "host-r").size() < 2) {
                Thread.sleep(50L);
            }

            List<MessageEvent> hostEvents = hostEvents(events, "host-r");
            boolean hasSent = hostEvents.stream()
                    .anyMatch(e -> "in".equals(e.direction()) && e.stream() == 1 && e.function() == 1);
            boolean hasReceived = hostEvents.stream()
                    .anyMatch(e -> "out".equals(e.direction()) && e.stream() == 1 && e.function() == 2);
            assertTrue(hasSent, () -> "远程主机发出的 S1F1 没有进事件流: " + describe(events));
            assertTrue(hasReceived, () -> "远程主机收到的 S1F2 没有进事件流: " + describe(events));
            assertTrue(hostEvents.stream().allMatch(e -> e.stream() >= 0), "控制帧不应出现在事件里");
            /* 3.0：主机侧报文不再挂宿主会话——perspective=host、sessionId 为空，
               前端按 hostId + perspective 归属 */
            assertTrue(hostEvents.stream().allMatch(e ->
                            "".equals(e.sessionId()) && "host".equals(e.perspective())),
                    "主机侧报文应为空 sessionId + host 视角: " + describe(events));
        } finally {
            if (remote != null) {
                remote.close();
            }
            device.close();
        }
    }

    @Test
    @Timeout(60)
    void localHostDoesNotPublishTwice() throws Exception {
        int port = freePort();
        EquipmentSpec demo = DemoEquipment.twoChambers("DEMO-01", 10);
        CompilationResult compiled = EquipmentCompiler.compile(demo);
        SessionSpec spec = new SessionSpec("local", true, "127.0.0.1", port, 10, 1L, demo);
        HsmsSessionRuntime device = new HsmsSessionRuntime(spec, compiled.equipment(), new ManualEngineClock(0L));

        EventBus bus = new EventBus();
        List<MessageEvent> events = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(events::add);
        device.eventBus(bus);
        device.open();

        HostSimulator local = null;
        try {
            local = HostSimulator.open("host-l", "local", device.hostTarget(), null);
            local.eventBus(bus);
            SessionTestSupport.awaitPeer(device);
            JsonHub reply = JsonHub.fromJson(local.sendSml("S1F1 W."));
            assertEquals(2, reply.get("replyFunction").intValue());

            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline
                    && events.stream().noneMatch(e -> e.stream() == 1 && e.function() == 2)) {
                Thread.sleep(50L);
            }
            Thread.sleep(300L);

            /* 3.0 双视角：设备侧一份（device 视角，无 hostId）+ 主机侧一份
               （host 视角，带 hostId），各自恰好一次——同一帧不串视角重复 */
            long deviceS1f1 = events.stream()
                    .filter(e -> e.stream() == 1 && e.function() == 1 && "device".equals(e.perspective()))
                    .count();
            long hostS1f1 = events.stream()
                    .filter(e -> e.stream() == 1 && e.function() == 1 && "host".equals(e.perspective()))
                    .count();
            assertEquals(1L, deviceS1f1, () -> "设备侧 S1F1 应恰好一份: " + describe(events));
            assertEquals(1L, hostS1f1, () -> "主机侧 S1F1 应恰好一份: " + describe(events));
            assertEquals(1L, events.stream()
                            .filter(e -> e.stream() == 1 && e.function() == 1 && e.fromHost())
                            .count(),
                    "主机视角的报文都带 hostId");
        } finally {
            if (local != null) {
                local.close();
            }
            device.close();
        }
    }

    private static List<MessageEvent> hostEvents(List<MessageEvent> events, String hostId) {
        List<MessageEvent> matched = new ArrayList<>();
        synchronized (events) {
            for (MessageEvent event : events) {
                if (hostId.equals(event.hostId())) {
                    matched.add(event);
                }
            }
        }
        return matched;
    }

    private static String describe(List<MessageEvent> events) {
        StringBuilder sb = new StringBuilder();
        synchronized (events) {
            for (MessageEvent event : events) {
                sb.append('[').append(event.hostId()).append('|').append(event.sessionId()).append(']')
                        .append(event.direction()).append(' ').append(event.sf()).append("; ");
            }
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
