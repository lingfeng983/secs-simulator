package com.shimizukenta.secssim.core.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.shimizukenta.secssim.core.curve.Rng;

/**
 * 编排表：多条计划合并后的时间线。
 */
public final class EventSchedule {

    private final List<EventPlan> plans;

    /**
     * 创建编排表。
     *
     * @param plans 计划
     */
    public EventSchedule(List<EventPlan> plans) {
        this.plans = List.copyOf(plans);
    }

    /**
     * 空编排表。
     *
     * @return 编排表
     */
    public static EventSchedule empty() {
        return new EventSchedule(List.of());
    }

    /**
     * 计划数量。
     *
     * @return 数量
     */
    public int size() {
        return this.plans.size();
    }

    /**
     * 全部计划。
     *
     * @return 计划列表
     */
    public List<EventPlan> plans() {
        return this.plans;
    }

    /**
     * 计算区间内应发生的全部事件，按时间升序。
     *
     * <p>只覆盖纯时间触发的计划（{@code At} / {@code Periodic}）。
     * {@code OnCondition} 与 {@code AfterEvent} 的 {@code fireTimes} 返回空，
     * 由运行时按 tick / 延迟队列处理。</p>
     *
     * @param fromMs 起始（不含）
     * @param toMs   结束（含）
     * @param rng    噪声源
     * @return 事件列表
     */
    public List<Fire> between(long fromMs, long toMs, Rng rng) {
        return this.between(fromMs, toMs, rng, java.util.Map.of());
    }

    /**
     * 计算区间内应发生的全部事件（带每计划启动锚点），按时间升序。
     *
     * <p>锚点来自 {@code EventScheduler} 的 per-plan {@code enabledAt}：
     * 手动启动一条计划时写入，全停/禁用时移除。没有锚点的计划按
     * 仿真时钟零点求值（纯区间语义，预览与旧测试用）。</p>
     *
     * @param fromMs  起始（不含）
     * @param toMs    结束（含）
     * @param rng     噪声源
     * @param anchors planId → 启动时刻（毫秒，时钟系）
     * @return 事件列表
     */
    public List<Fire> between(long fromMs, long toMs, Rng rng, java.util.Map<String, Long> anchors) {
        List<Fire> fires = new ArrayList<>();

        for (EventPlan plan : this.plans) {
            long anchor = anchors.getOrDefault(plan.id(), 0L);
            for (long at : plan.trigger().fireTimes(fromMs, toMs, rng, plan.id(), anchor)) {
                fires.add(new Fire(at, plan.id(), plan.ceid()));
            }
        }

        fires.sort(Comparator.comparingLong(Fire::atMs).thenComparing(Fire::planId));
        return List.copyOf(fires);
    }
}
