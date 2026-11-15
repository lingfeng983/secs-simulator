package com.shimizukenta.secssim.runtime.session;

import java.util.List;
import java.util.Set;

import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 曲线预览：分桶 min/max 包络 + avg 线。
 *
 * <p>事件标记与曲线必须来自同一次模拟：否则图上是"没有事件的基线曲线"，
 * 而标记画着事件发生的时间点——两者对不上，事件引起的跳变就看不见了。
 * 所以这里先跑一遍带效果层的模拟，曲线与标记都用它的结果。</p>
 */
final class PreviewBuilder {

    /** 值来源（含效果层）。 */
    private final java.util.function.Supplier<ValueSource> values;

    /** 设备模型。 */
    private final java.util.function.Supplier<ResolvedEquipment> model;

    /** 编排表。 */
    private final java.util.function.Supplier<EventSchedule> schedule;

    /** 随机种子。 */
    private final long seed;

    /**
     * 回放历史的上限：效果可能是很久以前施加的（shift 会一直生效），
     * 但把从 0 到现在的每个 100ms tick 都跑一遍，长跑进程会越来越慢。
     * 取最近 2 小时足够覆盖常见编排，又不会让预览随 uptime 线性变慢。
     */
    private static final long MAX_HISTORY_MS = 2L * 60L * 60L * 1000L;

    PreviewBuilder(java.util.function.Supplier<ValueSource> values,
                   java.util.function.Supplier<ResolvedEquipment> model,
                   java.util.function.Supplier<EventSchedule> schedule,
                   long seed) {
        this.values = values;
        this.model = model;
        this.schedule = schedule;
        this.seed = seed;
    }

    /**
     * 曲线预览：分桶 min/max 包络 + avg 线。
     *
     * @param fromMs    起始时刻
     * @param toMs      结束时刻
     * @param buckets   桶数（1..500）
     * @param svids     只看这些 SVID；空集合表示全部
     * @param withEvents 是否叠加事件标记
     * @return JSON 文本
     */
    String toJson(long fromMs, long toMs, int buckets, Set<Integer> svids, boolean withEvents) {
        return this.toJson(fromMs, toMs, buckets, svids, withEvents, null);
    }

    /**
     * 曲线预览，可只看某一条计划的触发时刻。
     *
     * <p>事件只标时间点、不改值（schema v7）：曲线始终由数据项的 {@code source}
     * 求值。窗口从 {@code fromMs} 之前一点开始回放，是为了让周期 60s 的计划在
     * 短窗口里也能看到它在本窗口内的触发。</p>
     */
    String toJson(long fromMs, long toMs, int buckets, Set<Integer> svids,
                  boolean withEvents, String planId) {
        ValueSource source = this.values.get();
        List<PreviewSupport.PreviewEvent> events = List.of();
        if (withEvents) {
            EventSchedule scheduleToRun = this.schedule.get();
            if (planId != null && !planId.isBlank()) {
                List<com.shimizukenta.secssim.core.event.EventPlan> only = scheduleToRun.plans().stream()
                        .filter(plan -> plan.id().equals(planId))
                        .toList();
                scheduleToRun = new EventSchedule(only);
            }
            long simFrom = Math.max(0L, fromMs - MAX_HISTORY_MS);
            EventSimulator.SimResult sim = EventSimulator.simulateWithValues(
                    scheduleToRun, source, this.seed,
                    simFrom, toMs, EventSimulator.DEFAULT_MAX_EVENTS);
            events = sim.fires().stream()
                    .filter(fire -> fire.atMs() >= fromMs && fire.atMs() <= toMs)
                    .map(fire -> new PreviewSupport.PreviewEvent(fire.atMs(), fire.ceid(), fire.planId()))
                    .toList();
        }
        PreviewSupport.PreviewData data = PreviewSupport.compute(
                source, this.model.get(), fromMs, toMs, buckets, svids, events);
        return PreviewSupport.toJson(data);
    }

    /**
     * 预览的二进制格式（SCSP v1，小端）。
     *
     * @param fromMs  起始时刻
     * @param toMs    结束时刻
     * @param buckets 桶数（1..500）
     * @return 二进制数据
     */
    byte[] toBinary(long fromMs, long toMs, int buckets) {
        PreviewSupport.PreviewData data = PreviewSupport.compute(
                this.values.get(), this.model.get(), fromMs, toMs, buckets);
        return PreviewSupport.toBinary(data);
    }
}
