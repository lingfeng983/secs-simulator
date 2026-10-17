package com.shimizukenta.secssim.core;

import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link System#nanoTime()} 的单调时钟实现。
 */
public final class SystemEngineClock implements EngineClock {

    private final long originNanos;

    /**
     * 创建以当前时刻为零点的单调时钟。
     */
    public SystemEngineClock() {
        this.originNanos = System.nanoTime();
    }

    @Override
    public long nowMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.originNanos);
    }
}
