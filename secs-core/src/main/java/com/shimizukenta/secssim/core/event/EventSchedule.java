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
     * @param fromMs 起始（不含）
     * @param toMs   结束（含）
     * @param rng    噪声源
     * @return 事件列表
     */
    public List<Fire> between(long fromMs, long toMs, Rng rng) {
        List<Fire> fires = new ArrayList<>();

        for (EventPlan plan : this.plans) {
            for (long at : plan.trigger().fireTimes(fromMs, toMs, rng, plan.id())) {
                fires.add(new Fire(at, plan.id(), plan.ceid()));
            }
        }

        fires.sort(Comparator.comparingLong(Fire::atMs).thenComparing(Fire::planId));
        return List.copyOf(fires);
    }
}
