package com.shimizukenta.secssim.core.cycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 生命周期周期时钟（2026-11-16 设计 §5.2）：
 * 值相位锚 = 周期启动时刻（D9）、停止冻结（D10）、跑满冻结（审计 P1-1）、
 * ABSOLUTE 随周期重锚（D11）、无 cycle 段自动 RUNNING（D12）。
 */
class LifecycleCycleClockTest {

    private static final long PERIOD = 10_000L;

    /** 编译期时钟：10s/片、无抖动、3 片。 */
    private static LifecycleCycleClock clock(LifecycleCycleClock.State initial) {
        return new LifecycleCycleClock(CycleClock.of(PERIOD, 0L, 3, 7L), initial);
    }

    @Test
    void runningAnchorsPhaseAtStart() {
        LifecycleCycleClock clock = clock(LifecycleCycleClock.State.STOPPED);
        clock.start(1_000L);

        assertEquals(0, clock.indexAt(1_000L + 500L), "启动后 0.5s：第 0 片");
        assertEquals(500L, clock.phaseAt(1_000L + 500L), "片内相位从 0 起跑（D9）");
        assertEquals(1, clock.indexAt(1_000L + 10_500L), "10.5s：进入第 1 片");
        assertEquals(500L, clock.phaseAt(1_000L + 10_500L));
    }

    @Test
    void stopFreezesAtStopTime() {
        LifecycleCycleClock clock = clock(LifecycleCycleClock.State.STOPPED);
        clock.start(0L);
        clock.stop(12_500L);

        assertEquals(LifecycleCycleClock.State.STOPPED, clock.state());
        /* PHASE 冻结在停止时的读数（D10） */
        assertEquals(2_500L, clock.phaseAt(99_999L), "停止后时间继续走，相位不动");
        assertEquals(0, clock.indexAt(99_999L), "CYCLE 槽在停止后归 0（§5.2）");
        /* ABSOLUTE 冻结（D11 的停止面） */
        assertEquals(12_500L, clock.elapsedAt(99_999L));
    }

    @Test
    void finishedFreezesAtLastWaferEnd() {
        LifecycleCycleClock clock = clock(LifecycleCycleClock.State.STOPPED);
        clock.start(0L);
        clock.finish(99_999L);

        assertEquals(LifecycleCycleClock.State.FINISHED, clock.state());
        assertEquals(2, clock.indexAt(99_999L), "停在末槽");
        assertEquals(10_000L, clock.phaseAt(99_999L), "冻结在末相位 = 末片整长（修掉相位无界，P1-1）");
        assertEquals(30_000L, clock.elapsedAt(99_999L), "ABSOLUTE 冻结在整段结束时刻");
    }

    @Test
    void restartReplaysAbsoluteCurves() {
        LifecycleCycleClock clock = clock(LifecycleCycleClock.State.STOPPED);
        clock.start(1_000L);
        long firstRunAt = clock.elapsedAt(5_000L);
        assertEquals(4_000L, firstRunAt, "ABSOLUTE = t - startedAt");

        clock.stop(6_000L);
        clock.start(50_000L);
        assertEquals(1_000L, clock.elapsedAt(51_000L),
                "重启周期即重放：ABSOLUTE 重新从 0 起算（D11）");
    }

    @Test
    void passthroughRunsFromClockZeroAndFinishesAfter24h() {
        LifecycleCycleClock clock = new LifecycleCycleClock(
                CycleClock.passthrough(), LifecycleCycleClock.State.RUNNING);

        assertEquals(1_234L, clock.phaseAt(1_234L), "无 cycle 段：相位 = 绝对时刻（D12，3.1 行为不变）");
        clock.finish(99_999L);
        assertEquals(86_400_000L, clock.phaseAt(99_999L), "24h 后 FINISHED 冻结（修掉相位无界）");
    }
}
