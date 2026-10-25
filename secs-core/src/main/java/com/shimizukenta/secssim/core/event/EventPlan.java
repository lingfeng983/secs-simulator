package com.shimizukenta.secssim.core.event;

import java.util.List;
import java.util.Objects;

/**
 * 一条编排：某个 CEID 按某个规则触发。
 *
 * @param id      计划标识
 * @param ceid    采集事件 ID
 * @param trigger 触发规则
 * @param effects 触发时施加的值效果（schema v6，可为空）
 */
public record EventPlan(String id, int ceid, Trigger trigger, List<EventEffect> effects) {

    /**
     * 兼容旧调用的构造：不带效果。
     *
     * @param id      计划标识
     * @param ceid    CEID
     * @param trigger 触发规则
     */
    public EventPlan(String id, int ceid, Trigger trigger) {
        this(id, ceid, trigger, List.of());
    }

    /**
     * 校验并创建。
     *
     * @param id      计划标识
     * @param ceid    CEID
     * @param trigger 规则
     * @param effects 效果
     */
    public EventPlan {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("plan id must not be blank");
        }
        if (ceid < 1) {
            throw new IllegalArgumentException("ceid must be >= 1 but was " + ceid);
        }
        Objects.requireNonNull(trigger, "trigger");
        effects = List.copyOf(effects == null ? List.of() : effects);
    }
}

