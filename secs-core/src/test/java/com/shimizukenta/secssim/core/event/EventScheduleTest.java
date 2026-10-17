package com.shimizukenta.secssim.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.curve.Rng;

class EventScheduleTest {

    private static final Rng RNG = new Rng(2024L);

    @Test
    void atTriggerFiresOnce() {
        Trigger.At trigger = new Trigger.At(5_000L);

        assertEquals(List.of(5_000L), trigger.fireTimes(0L, 5_000L, RNG, "p"));
        assertEquals(List.of(), trigger.fireTimes(5_000L, 10_000L, RNG, "p"), "左开右闭，不重复");
        assertEquals(List.of(), trigger.fireTimes(0L, 4_999L, RNG, "p"));
    }

    @Test
    void periodicTriggerFollowsMultiples() {
        Trigger.Periodic trigger = new Trigger.Periodic(1_000L, 0L);

        assertEquals(List.of(1_000L, 2_000L, 3_000L), trigger.fireTimes(0L, 3_000L, RNG, "p"));
        assertEquals(List.of(4_000L, 5_000L), trigger.fireTimes(3_000L, 5_000L, RNG, "p"),
                "跨区间调用不重不漏");
        assertEquals(List.of(), trigger.fireTimes(0L, 999L, RNG, "p"));
    }

    @Test
    void periodicJitterStaysWithinBoundsAndIsDeterministic() {
        Trigger.Periodic trigger = new Trigger.Periodic(1_000L, 200L);

        List<Long> first = trigger.fireTimes(0L, 10_500L, RNG, "plan-a");
        List<Long> second = trigger.fireTimes(0L, 10_500L, RNG, "plan-a");

        assertEquals(first, second, "抖动必须是确定性的，否则无法回放");
        assertTrue(first.size() >= 9, "十次周期至少应有九次落在窗口内，实际 " + first.size());

        for (long k = 1L; k <= 10L; k++) {
            long base = k * 1_000L;
            List<Long> around = trigger.fireTimes(base - 300L, base + 300L, RNG, "plan-a");

            assertEquals(1, around.size(), "第 " + k + " 次应恰好触发一次");
            assertTrue(Math.abs(around.get(0) - base) <= 200L,
                    "抖动越界: " + around.get(0) + " vs base " + base);
        }
    }

    @Test
    void differentPlansGetDifferentJitter() {
        Trigger.Periodic trigger = new Trigger.Periodic(1_000L, 300L);

        List<Long> a = trigger.fireTimes(0L, 5_000L, RNG, "plan-a");
        List<Long> b = trigger.fireTimes(0L, 5_000L, RNG, "plan-b");

        assertTrue(!a.equals(b), "不同计划的抖动应当独立");
    }

    @Test
    void scheduleMergesPlansInTimeOrder() {
        EventSchedule schedule = new EventSchedule(List.of(
                new EventPlan("periodic", 2001, new Trigger.Periodic(1_000L, 0L)),
                new EventPlan("once", 2002, new Trigger.At(1_500L))));

        List<Fire> fires = schedule.between(0L, 3_000L, RNG);

        assertEquals(List.of(1_000L, 1_500L, 2_000L, 3_000L),
                fires.stream().map(Fire::atMs).toList());
        assertEquals(2002, fires.get(1).ceid());
        assertEquals("once", fires.get(1).planId());
    }

    @Test
    void emptyScheduleProducesNothing() {
        assertTrue(EventSchedule.empty().between(0L, 60_000L, RNG).isEmpty());
        assertEquals(0, EventSchedule.empty().size());
    }

    @Test
    void periodicJitterDoesNotDropEventsAcrossTickWindows() {
        // 复现历史缺陷：跨 tick 累加时，抖动把 at 推过窗口边界而 base 仍在窗外，事件被相邻两窗同时漏掉。
        long period = 200L;
        long jitter = 120L; // > period/2，同时压测"重排后仍须保序"的路径
        String planId = "jit-tick";
        long span = period * 5_000L; // 约 5000 个周期
        long tick = 137L; // 与周期不对齐，模拟真实 ticker

        Trigger.Periodic trigger = new Trigger.Periodic(period, jitter);

        // 独立基准：直接按 (planId, k) 派生抖动算出 (0, span] 内所有真实触发时刻。
        Rng stream = RNG.derive("plan:" + planId);
        List<Long> truth = new ArrayList<>();
        for (long k = 1L; k * period <= span + jitter; k++) {
            long at = k * period + Math.round((stream.uniform01(k) * 2.0 - 1.0) * jitter);
            if (0L < at && at <= span) {
                truth.add(at);
            }
        }
        truth.sort(Long::compare);

        // 像运行期那样按 tick 逐窗累加。
        List<Long> got = new ArrayList<>();
        for (long prev = 0L; prev < span; prev += tick) {
            long now = Math.min(prev + tick, span);
            got.addAll(trigger.fireTimes(prev, now, RNG, planId));
        }
        got.sort(Long::compare);

        assertEquals(truth, got, "跨 tick 累加必须与基准完全一致：不丢、不重、保序");

        long approx = span / period;
        assertTrue(got.size() >= approx / 2 && got.size() <= approx * 3 / 2,
                "事件数应约等于 t/p，实际 " + got.size());
    }
}
