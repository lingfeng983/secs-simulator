package com.shimizukenta.secssim.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.NoticeEvent;

/**
 * {@code plan-fired} 通知节流（设计 §2.4.3）。
 *
 * <p>计划周期最小可配到 1ms、{@code events.maxPerSec} 默认 50 且无上限，
 * 逐条推送会把 SSE 与前端时间轴打爆。同一设备 200ms 窗口内的执行摘要
 * 合并成一帧（{@code items} 数组）；{@code status=FAILED} 不合并、立即推。
 * 明细始终逐条落台账，由 {@code GET /runtime/executions} 按需拉取。</p>
 *
 * <p>内存态、不落盘：停机时把未满窗口的摘要冲出去，进程崩溃最多丢一个窗口的
 * 通知，台账与已发出的报文不受影响。</p>
 */
public final class PlanFiredThrottle implements AutoCloseable {

    /** SSE 事件类型（新增帧，现有帧不动）。 */
    public static final String EVENT_TYPE = "plan-fired";

    /** 合并窗口。 */
    public static final long WINDOW_MS = 200L;

    /** 巡检间隔：窗口到期后最多晚这么多毫秒推出。 */
    private static final long TICK_MS = 50L;

    private final NoticeBus bus;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Thread flusher;
    private volatile boolean closed;

    /** @param bus 状态通知总线（与现有 s6f11 / alarm / session 帧同一条流） */
    public PlanFiredThrottle(NoticeBus bus) {
        this.bus = bus;
        this.flusher = Thread.ofPlatform().name("plan-fired-throttle").daemon(true)
                .start(this::tickLoop);
    }

    /**
     * 收一条执行摘要。
     *
     * @param deviceId 设备 id（ULID）
     * @param payload  单条摘要的 JSON 对象文本
     * @param immediate FAILED 时立即推、不进合并窗口
     */
    public void publish(String deviceId, String payload, boolean immediate) {
        if (this.bus == null || deviceId == null || payload == null || payload.isBlank()) {
            return;
        }
        if (immediate) {
            emit(deviceId, List.of(payload), false);
            return;
        }
        Window window = this.windows.computeIfAbsent(deviceId, ignored -> new Window());
        synchronized (window) {
            if (window.items.isEmpty()) {
                window.firstAt = System.currentTimeMillis();
            }
            window.items.add(payload);
        }
    }

    private void tickLoop() {
        while (!this.closed) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            flushExpired();
        }
    }

    /** 冲出所有到期窗口；停机时把未到期的也冲出去。 */
    private void flushExpired() {
        flush(false);
    }

    private void flush(boolean force) {
        long now = System.currentTimeMillis();
        for (var entry : this.windows.entrySet()) {
            Window window = entry.getValue();
            List<String> snapshot;
            synchronized (window) {
                if (window.items.isEmpty()
                        || (!force && now - window.firstAt < WINDOW_MS)) {
                    continue;
                }
                snapshot = List.copyOf(window.items);
                window.items.clear();
            }
            this.windows.remove(entry.getKey(), window);
            emit(entry.getKey(), snapshot, snapshot.size() > 1);
        }
    }

    private void emit(String deviceId, List<String> items, boolean merged) {
        if (items.isEmpty()) {
            return;
        }
        StringBuilder payload = new StringBuilder(64 + items.size() * 128);
        payload.append("{\"deviceId\":\"").append(escape(deviceId)).append('"')
                .append(",\"merged\":").append(merged)
                .append(",\"count\":").append(items.size())
                .append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                payload.append(',');
            }
            payload.append(items.get(i));
        }
        payload.append("],\"at\":").append(System.currentTimeMillis()).append('}');
        this.bus.publish(new NoticeEvent(deviceId, EVENT_TYPE, payload.toString(),
                System.currentTimeMillis()));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 当前待合并的摘要条数（测试与诊断用）。 */
    public int pendingCount() {
        int total = 0;
        for (Window window : this.windows.values()) {
            synchronized (window) {
                total += window.items.size();
            }
        }
        return total;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.flusher.interrupt();
        flush(true);
    }

    /** 单设备的合并窗口。 */
    private static final class Window {
        private final List<String> items = new ArrayList<>();
        private long firstAt;
    }
}
