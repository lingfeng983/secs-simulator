package com.shimizukenta.secssim.app.session;

/**
 * 计划启停操作的结果（2026-11-16 计划生命周期重设计 §2.2）。
 *
 * <p>启用方向有 ONLINE 门控：链路 SELECTED 且控制状态 ONLINE 才允许启动——
 * 离线时启动会被拒绝（HTTP 409 {@code not-online}），界面同步禁用按钮；
 * 禁用方向不受限（离线时也允许把计划停掉）。</p>
 */
public enum PlanEnableOutcome {

    /** 成功（含周期计划"启动即触发一次"已完成）。 */
    OK,

    /** 计划不存在。 */
    UNKNOWN_PLAN,

    /** 设备不在线（链路未 SELECTED 或控制状态非 ONLINE）：拒绝启动。 */
    NOT_ONLINE,

    /** 配置里没有 cycle 段：工艺周期无从启动。 */
    NO_CYCLE,
}
