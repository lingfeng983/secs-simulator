package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.core.event.RateLimiter;

/**
 * 设备级上报限流闸：事件编排与工艺周期共用同一只阀门。
 *
 * <p>3.3 拆分后两个运行时（{@code EventScheduler} / {@code CycleRunner}）
 * 各自触发、走同一道限流——一台设备的 S6F11 总速率仍然受
 * {@code maxEventsPerSec} 约束，不会因为拆成两套运行时就翻倍。</p>
 */
final class SessionRateGate {

    /** 限流窗口（毫秒）。 */
    private static final long RATE_WINDOW_MS = 1_000L;

    private volatile RateLimiter limiter;

    /** 按新的每秒上限重建阀门（热重载 / 装载时调用）。 */
    void configure(int maxEventsPerSec) {
        this.limiter = new RateLimiter(maxEventsPerSec, RATE_WINDOW_MS);
    }

    /**
     * 尝试占一个上报名额。
     *
     * @param nowMs 当前毫秒
     * @return {@code true} = 放行；{@code false} = 本窗口已满，丢弃（不记台账，D8）
     */
    boolean tryAcquire(long nowMs) {
        RateLimiter current = this.limiter;
        return current != null && current.tryAcquire(nowMs);
    }
}
