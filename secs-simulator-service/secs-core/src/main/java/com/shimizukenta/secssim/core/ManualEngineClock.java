package com.shimizukenta.secssim.core;

/**
 * 可手动推进的时钟。
 *
 * <p>预览沙盒以 1000 倍的虚拟速度跑完整条时间线时使用它；
 * 单元测试也用它把"过了 30 秒"变成一次赋值，而不是真的等 30 秒。
 * </p>
 */
public final class ManualEngineClock implements EngineClock {

    private volatile long nowMs;

    /**
     * 创建手动时钟。
     *
     * @param startMs 起始毫秒
     */
    public ManualEngineClock(long startMs) {
        this.nowMs = startMs;
    }

    @Override
    public long nowMs() {
        return this.nowMs;
    }

    /**
     * 前进指定毫秒。
     *
     * @param deltaMs 增量（必须非负）
     */
    public void advanceMs(long deltaMs) {
        if (deltaMs < 0L) {
            throw new IllegalArgumentException("deltaMs must not be negative: " + deltaMs);
        }
        this.nowMs += deltaMs;
    }

    /**
     * 直接设定当前毫秒（允许回退，仅用于测试布置）。
     *
     * @param nowMs 目标毫秒
     */
    public void setNowMs(long nowMs) {
        this.nowMs = nowMs;
    }
}
