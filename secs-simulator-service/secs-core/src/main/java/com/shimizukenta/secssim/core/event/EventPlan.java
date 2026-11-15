package com.shimizukenta.secssim.core.event;

import java.util.List;
import java.util.Objects;

/**
 * 一条编排：某个 CEID 按某个规则触发。
 *
 * <p>计划只声明「什么时候、发什么」——<b>不改任何值</b>。报文体里的数值由
 * 数据项自己的 {@code source} 决定（schema v7 起取消了事件效果这一层）。</p>
 *
 * @param id             计划标识
 * @param ceid           采集事件 ID
 * @param trigger        触发规则
 * @param actions        触发时发送的消息（3.0 需求 5；空 = 默认一条 S6F11）
 * @param offlinePolicy  离线策略：skip（跳过，默认）/ queue（有界内存队列，不是 Spooling）/ fail（记 FAILED）
 * @param onSendFailure  发送失败策略：keep（保持效果，默认）/ rollback（仅 set/add 且未被触碰时撤销）
 */
public record EventPlan(String id, int ceid, Trigger trigger,
                        List<PlanAction> actions, String offlinePolicy, String onSendFailure) {

    /** 离线策略：跳过（默认，ADR 0005）。 */
    public static final String OFFLINE_SKIP = "skip";
    /** 离线策略：有界内存队列。 */
    public static final String OFFLINE_QUEUE = "queue";
    /** 离线策略：失败。 */
    public static final String OFFLINE_FAIL = "fail";
    /** 发送失败策略：保持效果（默认）。 */
    public static final String FAILURE_KEEP = "keep";
    /** 发送失败策略：回滚（边界见设计 §2.7.2）。 */
    public static final String FAILURE_ROLLBACK = "rollback";

    /**
     * 最简构造：只发默认 S6F11。
     *
     * @param id      计划标识
     * @param ceid    CEID
     * @param trigger 触发规则
     */
    public EventPlan(String id, int ceid, Trigger trigger) {
        this(id, ceid, trigger, List.of(), OFFLINE_SKIP, FAILURE_KEEP);
    }

    /**
     * 校验并创建。
     *
     * @param id             计划标识
     * @param ceid           CEID
     * @param trigger        规则
     * @param actions        动作
     * @param offlinePolicy  离线策略
     * @param onSendFailure  发送失败策略
     */
    public EventPlan {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("plan id must not be blank");
        }
        if (ceid < 1) {
            throw new IllegalArgumentException("ceid must be >= 1 but was " + ceid);
        }
        Objects.requireNonNull(trigger, "trigger");
        actions = List.copyOf(actions == null ? List.of() : actions);
        offlinePolicy = offlinePolicy == null || offlinePolicy.isBlank() ? OFFLINE_SKIP : offlinePolicy;
        onSendFailure = onSendFailure == null || onSendFailure.isBlank() ? FAILURE_KEEP : onSendFailure;
    }

    /** 动作列表是否为空（空 = 默认一条 S6F11）。 */
    public boolean hasCustomActions() {
        return !this.actions.isEmpty();
    }
}

