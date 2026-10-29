package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.event.ConditionDebouncer;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Fire;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.template.ExpressionException;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 编排的确定性事件模拟器（schema v6，预览用）。
 *
 * <p>periodic/at 的触发时刻是纯时间函数，onCondition 只依赖"当时各 SVID 的值"，
 * AfterEvent 只依赖"源事件何时发生"——给定 seed 与基线值来源，整条事件时间线
 * 是确定的。模拟器在时间上单趟推进：先条件、再时间触发、再延迟链，
 * 每次触发把计划效果施加到<b>独立的</b>效果记录上，后续条件求值即能看到
 * 效果引起的值变化——这就是"事件驱动曲线"在草稿预览里的复现。</p>
 *
 * <p>与运行时的刻意差异：全部计划按启用处理；手动触发（HTTP）不在模拟范围。
 * 因此预览是"配置的确定性影子"，不是运行时录像（重设计文档 §5.1）。</p>
 */
public final class EventSimulator {

    /** 条件求值与效果施加的推进步长（与运行时事件 tick 一致）。 */
    private static final long TICK_MS = 100L;

    /** 默认单次模拟的事件上限：保护编辑器不被高频计划拖死。 */
    public static final int DEFAULT_MAX_EVENTS = 1000;

    /** 一条模拟出的触发。 */
    public record SimFire(long atMs, String planId, int ceid) {
    }

    /**
     * 一次模拟的完整结果。
     *
     * <p>关键点：{@code values} 是**叠加了本次模拟的事件效果**的值来源，
     * 而不是基线。用它算曲线，事件引起的跳变才会出现在图上——
     * 这正是"把事件编排串起来的变化曲线"。</p>
     *
     * @param fires  触发列表（时间升序）
     * @param values 基线 + 事件效果层的值来源
     */
    public record SimResult(List<SimFire> fires, ValueSource values) {
    }

    private EventSimulator() {
    }

    /**
     * 模拟区间内的全部触发，按时间升序。
     *
     * @param schedule  编排表
     * @param base      基线值来源（不带效果层）
     * @param seed      数据种子（抖动与运行时同源）
     * @param fromMs    起始（模拟时钟）
     * @param toMs      结束
     * @param maxEvents 事件上限
     * @return 触发列表
     */
    public static List<SimFire> simulate(EventSchedule schedule, ValueSource base, long seed,
                                         long fromMs, long toMs, int maxEvents) {
        return simulateWithEffects(schedule, base, seed, fromMs, toMs, maxEvents).fires();
    }

    /**
     * 模拟区间内的全部触发，并返回叠加了效果层的值来源。
     *
     * <p>曲线要用 {@link SimResult#values()} 算：事件引起的值变化（set/pulse/shift）
     * 才会体现在图上；用基线算出来的只是"没有事件"的曲线。</p>
     *
     * @param schedule  编排表
     * @param base      基线值来源（不带效果层）
     * @param seed      数据种子（抖动与运行时同源）
     * @param fromMs    起始（模拟时钟）
     * @param toMs      结束
     * @param maxEvents 事件上限
     * @return 触发列表 + 叠加效果后的值来源
     */
    public static SimResult simulateWithEffects(EventSchedule schedule, ValueSource base, long seed,
                                                 long fromMs, long toMs, int maxEvents) {
        List<SimFire> fires = new ArrayList<>();
        if (schedule.plans().isEmpty() || toMs <= fromMs) {
            return new SimResult(fires, base);
        }

        EventEffectStore store = new EventEffectStore();
        EffectValueSource values = new EffectValueSource(base, store);
        Rng rng = new Rng(seed).derive("events");
        Map<String, ConditionDebouncer> debouncers = new HashMap<>();
        List<PendingFire> pending = new ArrayList<>();
        /* 条件表达式连续报错时不再刷屏:一次模拟最多记一条 */
        boolean expressionBroken = false;

        long windowFrom = fromMs;
        while (windowFrom < toMs && fires.size() < maxEvents) {
            long windowTo = Math.min(toMs, windowFrom + TICK_MS);

            /* 1) 条件触发:与运行时一致,每个 tick 求值 */
            for (EventPlan plan : schedule.plans()) {
                if (!(plan.trigger() instanceof Trigger.OnCondition condition) || fires.size() >= maxEvents) {
                    continue;
                }
                ConditionDebouncer debouncer = debouncers.computeIfAbsent(
                        plan.id(), id -> new ConditionDebouncer(condition.debounceMs()));
                boolean active;
                try {
                    active = condition.expression().evaluate(name -> variable(name, values, windowTo)) != 0.0;
                } catch (RuntimeException e) {
                    if (!expressionBroken) {
                        expressionBroken = true;
                    }
                    debouncer.reset();
                    continue;
                }
                if (debouncer.update(windowTo, active)) {
                    fires.add(new SimFire(windowTo, plan.id(), plan.ceid()));
                    applyEffects(plan, store, windowTo);
                    enqueueAfter(schedule, plan.id(), windowTo, pending);
                }
            }

            /* 2) 纯时间触发 */
            for (Fire fire : schedule.between(windowFrom, windowTo, rng)) {
                if (fires.size() >= maxEvents) {
                    break;
                }
                fires.add(new SimFire(fire.atMs(), fire.planId(), fire.ceid()));
                schedule.plans().stream()
                        .filter(plan -> plan.id().equals(fire.planId()))
                        .findFirst()
                        .ifPresent(plan -> {
                            applyEffects(plan, store, fire.atMs());
                            enqueueAfter(schedule, plan.id(), fire.atMs(), pending);
                        });
            }

            /* 3) AfterEvent 到期的(允许同 tick 级联,防环跳数与运行时一致) */
            int guard = 0;
            while (fires.size() < maxEvents && guard++ <= 64) {
                PendingFire next = pending.stream()
                        .filter(item -> item.atMs() <= windowTo)
                        .min(Comparator.comparingLong(PendingFire::atMs))
                        .orElse(null);
                if (next == null) {
                    break;
                }
                pending.remove(next);
                fires.add(new SimFire(Math.max(next.atMs(), windowFrom + 1), next.planId(), next.ceid()));
                schedule.plans().stream()
                        .filter(plan -> plan.id().equals(next.planId()))
                        .findFirst()
                        .ifPresent(plan -> {
                            applyEffects(plan, store, next.atMs());
                            enqueueAfter(schedule, plan.id(), next.atMs(), pending);
                        });
            }

            windowFrom = windowTo;
        }

        fires.sort(Comparator.comparingLong(SimFire::atMs).thenComparing(SimFire::planId));
        return new SimResult(List.copyOf(fires), values);
    }

    private static void applyEffects(EventPlan plan, EventEffectStore store, long atMs) {
        for (com.shimizukenta.secssim.core.event.EventEffect effect : plan.effects()) {
            store.apply(effect.svid(), effect, atMs);
        }
    }

    private static void enqueueAfter(EventSchedule schedule, String firedPlanId, long atMs,
                                     List<PendingFire> pending) {
        for (EventPlan plan : schedule.plans()) {
            if (plan.trigger() instanceof Trigger.AfterEvent after
                    && after.sourcePlan().equals(firedPlanId)) {
                pending.add(new PendingFire(atMs + after.delayMs(), plan.id(), plan.ceid()));
            }
        }
    }

    private static double variable(String name, ValueSource values, long nowMs) {
        if ("t".equals(name)) {
            return nowMs / 1000.0;
        }
        if (name.startsWith("svid.")) {
            int svid;
            try {
                svid = Integer.parseInt(name.substring("svid.".length()));
            } catch (NumberFormatException e) {
                throw new ExpressionException("条件表达式里的 SVID 非法: " + name, e);
            }
            return values.valueAt(svid, nowMs).orElse(0.0);
        }
        throw new ExpressionException("条件表达式只支持 t 和 svid.<id>: " + name);
    }

    private record PendingFire(long atMs, String planId, int ceid) {
    }
}
