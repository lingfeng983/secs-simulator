package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.NoticeEvent;

/**
 * {@code plan-fired} 节流的回归网（设计 §2.4.3）。
 *
 * <p>周期最小 1ms、events.maxPerSec 默认 50 且无上限，逐条推会打爆 SSE；
 * 同一设备 200ms 窗口内的摘要必须合并成一帧，{@code status=FAILED} 不合并、立即推，
 * 不同设备的窗口互不干扰，停机时把未满窗口冲出去。</p>
 */
class PlanFiredThrottleTest {

    private static final String SENT = "{\"status\":\"SENT\",\"ceid\":2001}";
    private static final String FAILED = "{\"status\":\"FAILED\",\"ceid\":2001}";

    /** 窗口内多条 → 一帧，merged=true、count=N、items 保留全部原始摘要。 */
    @Test
    void mergesSameDeviceWithinWindowIntoOneFrame() throws Exception {
        NoticeBus bus = new NoticeBus();
        List<NoticeEvent> frames = new CopyOnWriteArrayList<>();
        bus.subscribe(frames::add);
        try (PlanFiredThrottle throttle = new PlanFiredThrottle(bus)) {
            throttle.publish("devA", SENT, false);
            throttle.publish("devA", SENT, false);
            throttle.publish("devA", SENT, false);

            assertEquals(3, throttle.pendingCount(), "窗口未满时只攒着");
            assertEquals(0, frames.size(), "200ms 内不得逐条推");

            awaitFrames(frames, 1);

            assertEquals(1, frames.size(), "同设备合并成一帧");
            NoticeEvent frame = frames.get(0);
            assertEquals(PlanFiredThrottle.EVENT_TYPE, frame.type());
            assertEquals("devA", frame.sessionId());
            assertTrue(frame.payload().contains("\"merged\":true"));
            assertTrue(frame.payload().contains("\"count\":3"));
            assertEquals(3, count(frame.payload(), "\"status\":\"SENT\""));
            assertEquals(0, throttle.pendingCount());
        }
    }

    /** FAILED 不进合并窗口：立即单独一帧，且不影响其余窗口继续攒。 */
    @Test
    void failedIsPublishedImmediatelyWithoutMerging() throws Exception {
        NoticeBus bus = new NoticeBus();
        List<NoticeEvent> frames = new CopyOnWriteArrayList<>();
        bus.subscribe(frames::add);
        try (PlanFiredThrottle throttle = new PlanFiredThrottle(bus)) {
            throttle.publish("devA", SENT, false);
            throttle.publish("devA", FAILED, true);

            assertEquals(1, frames.size(), "FAILED 必须已经推出去了");
            assertTrue(frames.get(0).payload().contains("\"merged\":false"));
            assertTrue(frames.get(0).payload().contains("\"count\":1"));
            assertEquals(1, throttle.pendingCount(), "SENT 仍留在自己的窗口里");
        }

        /* close() 会把未满窗口冲出：停机不丢通知 */
        assertEquals(2, frames.size());
        assertTrue(frames.get(1).payload().contains("\"status\":\"SENT\""));
    }

    /** 窗口按设备隔离：devA 两条、devB 一条 → 两帧而不是一帧。 */
    @Test
    void windowsAreIsolatedPerDevice() throws Exception {
        NoticeBus bus = new NoticeBus();
        List<NoticeEvent> frames = new CopyOnWriteArrayList<>();
        bus.subscribe(frames::add);
        try (PlanFiredThrottle throttle = new PlanFiredThrottle(bus)) {
            throttle.publish("devA", SENT, false);
            throttle.publish("devA", SENT, false);
            throttle.publish("devB", SENT, false);

            awaitFrames(frames, 2);

            assertEquals(2, frames.size());
            assertTrue(frames.stream().anyMatch(f -> f.sessionId().equals("devA")
                    && f.payload().contains("\"count\":2") && f.payload().contains("\"merged\":true")));
            assertTrue(frames.stream().anyMatch(f -> f.sessionId().equals("devB")
                    && f.payload().contains("\"count\":1") && f.payload().contains("\"merged\":false")));
        }
    }

    /**
     * 停机之后再 publish 也要发出去。
     *
     * <p>窗口巡检线程已经停了，此时把摘要塞进新窗口就等于永久攒着：进程退出的
     * 最后几秒发生的执行会在时间轴上凭空消失。</p>
     */
    @Test
    void publishAfterCloseIsEmittedImmediately() {
        NoticeBus bus = new NoticeBus();
        List<NoticeEvent> frames = new CopyOnWriteArrayList<>();
        bus.subscribe(frames::add);
        PlanFiredThrottle throttle = new PlanFiredThrottle(bus);
        throttle.close();

        throttle.publish("devA", SENT, false);

        assertEquals(1, frames.size(), "停机后的摘要不能静默丢弃");
        assertTrue(frames.get(0).payload().contains("\"status\":\"SENT\""));
    }

    /**
     * 并发 publish 与窗口冲出交错时不丢摘要。
     *
     * <p>曾经的实现是"先清空、再摘表"：publish 正好落在两步之间时，会把新摘要
     * 写进一个马上要被丢弃的窗口，那一条执行就从时间轴上消失了。丢一条摘要
     * 不报错、不打日志，只能靠对账发现。</p>
     */
    @Test
    void concurrentPublishNeverLosesItems() throws Exception {
        NoticeBus bus = new NoticeBus();
        List<NoticeEvent> frames = new CopyOnWriteArrayList<>();
        bus.subscribe(frames::add);
        final int threads = 4;
        final int perThread = 400;
        try (PlanFiredThrottle throttle = new PlanFiredThrottle(bus)) {
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                workers[t] = new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        throttle.publish("devA", SENT, false);
                    }
                }, "pub-" + t);
                workers[t].start();
            }
            for (Thread worker : workers) {
                worker.join();
            }
        }

        int total = 0;
        for (NoticeEvent frame : frames) {
            total += count(frame.payload(), "\"status\":\"SENT\"");
        }
        assertEquals(threads * perThread, total, "窗口冲出与 publish 交错时丢了摘要");
    }

    /** 设备 id 里的引号/反斜杠不能破坏 SSE 载荷 JSON。 */
    @Test
    void deviceIdIsEscaped() throws Exception {
        NoticeBus bus = new NoticeBus();
        List<NoticeEvent> frames = new CopyOnWriteArrayList<>();
        bus.subscribe(frames::add);
        try (PlanFiredThrottle throttle = new PlanFiredThrottle(bus)) {
            throttle.publish("dev\"A\\B", FAILED, true);

            assertEquals(1, frames.size());
            assertTrue(frames.get(0).payload().contains("\"deviceId\":\"dev\\\"A\\\\B\""),
                    frames.get(0).payload());
        }
    }

    private static void awaitFrames(List<NoticeEvent> frames, int expected) throws InterruptedException {
        for (int i = 0; i < 80 && frames.size() < expected; i++) {
            Thread.sleep(25);
        }
    }

    private static int count(String text, String token) {
        int total = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            total++;
        }
        return total;
    }
}
