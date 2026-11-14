package com.shimizukenta.secssim.core;

/**
 * 仿真时间源。
 *
 * <p>曲线求值一律使用<b>单调时钟</b>：墙钟会因 NTP 校正、夏令时、手动改时间而跳变，
 * 一旦跳变，曲线会出现瞬间阶跃，回放与预览也无法复现。墙钟只用于 S2F17 与 S6F1 的
 * {@code STIME} 字段。
 * </p>
 */
public interface EngineClock {

    /**
     * 返回自时钟原点起的毫秒数（单调递增）。
     *
     * @return 仿真毫秒
     */
    long nowMs();

    /**
     * 返回基于 {@link System#nanoTime()} 的单调时钟。
     *
     * @return 系统单调时钟
     */
    static EngineClock system() {
        return new SystemEngineClock();
    }

    /**
     * 返回可手动推进的时钟，供预览沙盒与单元测试使用。
     *
     * @param startMs 起始毫秒
     * @return 手动时钟
     */
    static ManualEngineClock manual(long startMs) {
        return new ManualEngineClock(startMs);
    }
}
