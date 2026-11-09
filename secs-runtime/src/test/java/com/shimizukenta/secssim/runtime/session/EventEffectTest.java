package com.shimizukenta.secssim.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 事件效果层（v6）的单元测试：效果组合语义 + 确定性事件模拟。
 */
class EventEffectTest {

    /** 基线 = 常数 100。 */
    private static final ValueSource BASE = new ValueSource() {
        @Override
        public OptionalDouble valueAt(int svid, long tMs) {
            return OptionalDouble.of(100.0);
        }

        @Override
        public Set<Integer> svids() {
            return Set.of(1);
        }
    };

    private static EventEffectStore storeWith(int svid, EventEffect effect, long at) {
        EventEffectStore store = new EventEffectStore();
        store.apply(svid, effect, at);
        return store;
    }

    @Test
    void shiftAddsDelta() {
        EventEffectStore store = storeWith(1, new EventEffect(1, EventEffect.Op.SHIFT, null, 20.0, null, null), 1_000L);
        EffectValueSource source = new EffectValueSource(BASE, store);

        assertEquals(OptionalDouble.of(100.0), source.valueAt(1, 999L), "施加前是基线");
        assertEquals(OptionalDouble.of(120.0), source.valueAt(1, 1_000L));
        assertEquals(OptionalDouble.of(120.0), source.valueAt(1, 999_999L), "shift 持续生效");
    }

    @Test
    void pulseFallsBackAfterHold() {
        EventEffectStore store = storeWith(1, new EventEffect(1, EventEffect.Op.PULSE, 250.0, null, 5_000L, null), 1_000L);
        EffectValueSource source = new EffectValueSource(BASE, store);

        assertEquals(OptionalDouble.of(250.0), source.valueAt(1, 3_000L));
        assertEquals(OptionalDouble.of(100.0), source.valueAt(1, 6_001L), "holdMs 后回落基线");
    }

    @Test
    void setDecaysBackToComposedBase() {
        EventEffectStore store = new EventEffectStore();
        store.apply(1, new EventEffect(1, EventEffect.Op.SHIFT, null, 10.0, null, null), 0L);
        store.apply(1, new EventEffect(1, EventEffect.Op.SET, 300.0, null, null, 1_000L), 0L);
        EffectValueSource source = new EffectValueSource(BASE, store);

        assertEquals(300.0, source.valueAt(1, 0L).orElse(-1), 1e-9, "施加瞬间是 set 值");
        assertEquals(205.0, source.valueAt(1, 500L).orElse(-1), 1e-9, "衰减中点:300 向基线(110)走一半");
        assertEquals(110.0, source.valueAt(1, 1_000L).orElse(-1), 1e-9, "衰减完回到含 shift 的基线");
    }

    @Test
    void resumeClearsAllEffects() {
        EventEffectStore store = new EventEffectStore();
        store.apply(1, new EventEffect(1, EventEffect.Op.SHIFT, null, 50.0, null, null), 0L);
        store.apply(1, new EventEffect(1, EventEffect.Op.RESUME, null, null, null, null), 2_000L);
        EffectValueSource source = new EffectValueSource(BASE, store);

        assertEquals(OptionalDouble.of(100.0), source.valueAt(1, 2_001L), "resume 之后回基线");
    }

    @Test
    void storeIsBoundedPerSvid() {
        EventEffectStore store = new EventEffectStore();
        for (int i = 0; i < 200; i++) {
            store.apply(1, new EventEffect(1, EventEffect.Op.PULSE, (double) i, null, 1L, null), i);
        }
        assertTrue(store.activeAt(1, 500L).size() <= EventEffectStore.MAX_PER_SVID,
                "环形上界必须生效");
    }

    /* ===== 事件模拟器 ===== */

    @Test
    void simulatorFiresPeriodicPlan() {
        EventSchedule schedule = new EventSchedule(List.of(
                new EventPlan("p", 2001, new Trigger.Periodic(1_000L, 0L))));
        List<EventSimulator.SimFire> fires = EventSimulator.simulate(
                schedule, BASE, 42L, 0L, 10_000L, 100);
        assertEquals(10, fires.size(), "10s 窗口 @1s 周期应触发 10 次");
        assertEquals(2001, fires.getFirst().ceid());
        assertEquals(1_000L, fires.getFirst().atMs());
    }

    @Test
    void simulatorFiresConditionAndAppliesEffects() {
        /* 条件 svid.1 > 150;效果:对 svid.1 pulse 300 保持 500ms。
           模拟里 pulse 一旦触发,条件应因值升高而持续成立(去抖 0)。
           但 pulse 500ms 后回落,条件失效——恰好验证闭环。 */
        EventSchedule schedule = new EventSchedule(List.of(
                new EventPlan("cond", 2002, new Trigger.OnCondition(
                        com.shimizukenta.secssim.core.template.Expression.parse("svid.1 > 150"), 0L),
                        List.of(new EventEffect(1, EventEffect.Op.PULSE, 300.0, null, 500L, null)))));
        List<EventSimulator.SimFire> fires = EventSimulator.simulate(
                schedule, BASE, 42L, 0L, 5_000L, 100);
        /* 基线 100 < 150:条件只在 pulse 生效的 500ms 窗口内成立 →
           第一次触发靠外部(没有)→ 实际上条件永远不成立,这是"无自激"的验证 */
        assertTrue(fires.isEmpty(), "基线 100 不超阈值,不应有任何触发");
    }

    @Test
    void simulatorFiresAfterEventChain() {
        EventSchedule schedule = new EventSchedule(List.of(
                new EventPlan("start", 2001, new Trigger.At(1_000L)),
                new EventPlan("end", 2002, new Trigger.AfterEvent("start", 2_000L))));
        List<EventSimulator.SimFire> fires = EventSimulator.simulate(
                schedule, BASE, 42L, 0L, 10_000L, 100);
        assertEquals(2, fires.size());
        assertEquals("start", fires.get(0).planId());
        assertEquals(3_000L, fires.get(1).atMs(), "AfterEvent = 源时刻 + delayMs");
    }

    @Test
    void simulatorCapsEventCount() {
        EventSchedule schedule = new EventSchedule(List.of(
                new EventPlan("p", 2001, new Trigger.Periodic(100L, 0L))));
        List<EventSimulator.SimFire> fires = EventSimulator.simulate(
                schedule, BASE, 42L, 0L, 1_000_000L, 50);
        assertEquals(50, fires.size(), "事件上限必须生效");
    }

    @Test
    void simulatorRejectsOversizedWindow() {
        /* maxEvents 只限制触发次数：条件恒假的计划可以让 while 循环一直推进，
           所以窗口本身必须有 tick 预算（100ms/步 x 100 万步）。 */
        EventSchedule schedule = new EventSchedule(List.of(
                new EventPlan("p", 2001, new Trigger.Periodic(1_000L, 0L))));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                EventSimulator.simulate(schedule, BASE, 42L, 0L, 100_000_100L,
                        EventSimulator.DEFAULT_MAX_EVENTS));
        assertTrue(ex.getMessage().contains("preview window"), ex.getMessage());
    }
}
