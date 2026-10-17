package com.shimizukenta.secssim.core.event;

/**
 * 条件触发的去抖状态机。
 *
 * <p>语义：条件从 false 变 true 后，必须<b>连续</b>成立 {@code debounceMs} 才触发一次；
 * 期间只要变 false，计时归零。触发后保持成立不会重复触发，直到再次变 false 再变 true。</p>
 *
 * <p>它单独成类而不是塞进 {@link Trigger}：Trigger 是纯区间查询，
 * 而“条件持续了多久”必然带状态。状态属于运行时，不属于计划表。</p>
 */
public final class ConditionDebouncer {

    private final long debounceMs;
    private long trueSinceMs = -1L;
    private boolean fired;

    /**
     * 创建去抖器。
     *
     * @param debounceMs 条件必须连续成立的毫秒数（0 表示立即触发）
     */
    public ConditionDebouncer(long debounceMs) {
        if (debounceMs < 0L) {
            throw new IllegalArgumentException("debounceMs must be >= 0 but was " + debounceMs);
        }
        this.debounceMs = debounceMs;
    }

    /**
     * 推进一次采样。
     *
     * @param nowMs  当前仿真时刻
     * @param active 条件是否成立
     * @return 本次是否应触发
     */
    public synchronized boolean update(long nowMs, boolean active) {
        if (!active) {
            this.trueSinceMs = -1L;
            this.fired = false;
            return false;
        }

        if (this.trueSinceMs < 0L) {
            this.trueSinceMs = nowMs;
        }

        if (!this.fired && nowMs - this.trueSinceMs >= this.debounceMs) {
            this.fired = true;
            return true;
        }
        return false;
    }

    /**
     * 丢掉当前状态（例如主机掉线、事件被禁用）。
     */
    public synchronized void reset() {
        this.trueSinceMs = -1L;
        this.fired = false;
    }
}
