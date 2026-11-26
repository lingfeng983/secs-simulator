package com.shimizukenta.secssim.core.cycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 编译期周期时钟的边界契约：相位恒在 {@code [0, duration)}。
 *
 * <p>P2 修复的回归锚点：t = 0、片切换瞬间归零、t ≥ endMs 钳到末片右开端点
 * duration - 1（而不是随时间无界增长）、负时刻钳到 0。</p>
 */
class CompiledCycleClockTest {

    private static final long PERIOD = 10_000L;

    /** 10s/片、无抖动、3 片：起点 0 / 10s / 20s，endMs = 30s。 */
    private static CompiledCycleClock threeWafers() {
        return new CompiledCycleClock(PERIOD, 0L, 3, 7L);
    }

    @Test
    void zeroTimeIsFirstWaferPhaseZero() {
        CompiledCycleClock clock = threeWafers();

        assertEquals(0, clock.indexAt(0L));
        assertEquals(0L, clock.phaseAt(0L), "t = 0：第 0 片相位 0");
    }

    @Test
    void waferSwitchInstantResetsPhaseToZero() {
        CompiledCycleClock clock = threeWafers();

        assertEquals(PERIOD - 1L, clock.phaseAt(PERIOD - 1L), "第 0 片末毫秒仍在片内");
        assertEquals(1, clock.indexAt(PERIOD), "片切换瞬间进入下一片");
        assertEquals(0L, clock.phaseAt(PERIOD), "切换瞬间相位归零（曲线按相位重放）");
        assertEquals(2, clock.indexAt(2L * PERIOD));
        assertEquals(0L, clock.phaseAt(2L * PERIOD));
    }

    @Test
    void phaseBeyondEndIsClampedToLastWaferRightOpenEndpoint() {
        CompiledCycleClock clock = threeWafers();
        long endMs = clock.endMs();
        assertEquals(3L * PERIOD, endMs);

        assertEquals(2, clock.indexAt(endMs), "超界时刻停在末片");
        assertEquals(PERIOD - 1L, clock.phaseAt(endMs), "t = endMs：钳到右开端点 duration - 1");
        assertEquals(PERIOD - 1L, clock.phaseAt(endMs + 999_999L), "t 远超 endMs：相位不再增长");
    }

    @Test
    void negativeTimeClampsToZero() {
        CompiledCycleClock clock = threeWafers();

        assertEquals(0L, clock.phaseAt(-5_000L), "负时刻钳到 0，保持 [0, duration) 契约");
    }

    @Test
    void singleWaferPassthroughClampsAt24h() {
        CompiledCycleClock clock = CompiledCycleClock.passthrough();

        assertEquals(1, clock.count());
        assertEquals(0L, clock.phaseAt(0L));
        assertEquals(1_234L, clock.phaseAt(1_234L), "片内：相位 = 绝对时刻");
        assertEquals(86_400_000L, clock.endMs());
        assertEquals(86_399_999L, clock.phaseAt(86_400_000L), "24h 边界钳到 duration - 1");
        assertEquals(86_399_999L, clock.phaseAt(Long.MAX_VALUE / 2), "任意大时刻均被钳制");
    }

    @Test
    void jitteredWafersKeepPhaseWithinDuration() {
        CompiledCycleClock clock = new CompiledCycleClock(PERIOD, 2_000L, 5, 42L);

        for (int i = 0; i < clock.count(); i++) {
            long start = clock.startOf(i);
            long duration = clock.durationOf(i);
            assertEquals(0L, clock.phaseAt(start), "每片起点相位为 0");
            assertEquals(duration - 1L, clock.phaseAt(start + duration - 1L), "每片末毫秒在片内");
            assertTrue(clock.phaseAt(start + duration - 1L) < duration);
        }
        long endMs = clock.endMs();
        assertEquals(clock.durationOf(4) - 1L, clock.phaseAt(endMs + 1L),
                "带抖动时超界同样钳到末片右开端点");
    }
}