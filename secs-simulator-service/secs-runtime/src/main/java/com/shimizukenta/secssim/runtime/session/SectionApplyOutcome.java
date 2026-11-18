package com.shimizukenta.secssim.runtime.session;

/**
 * 一次分段生效对运行态做了什么（2026-11-18 设计 §4）。
 *
 * <p>接口把它原样回给前端，界面才能说清「哪些东西被重置了」，而不是笼统
 * 一句"已生效"。字段全部是"这一步是否真的动了"，没动的段不会出现在提示里。</p>
 *
 * @param modelChanged   设备结构是否替换（单元 / 号段；只有这一档会全停）
 * @param catalogChanged 变量 / 报告 / 事件目录是否替换（换视图与编排，运行不断）
 * @param valuesChanged  值来源是否重建（含钉值迁移）
 * @param scheduleChanged 编排表是否替换
 * @param throttleChanged 限流与 W 位是否更新
 * @param dataChanged     晶圆属性表 / 设备常量取值器是否替换
 * @param cycleChanged    工艺周期配置是否替换（周期回停止）
 * @param pinsDropped     因 SVID 被删而丢弃的钉值条数
 * @param plansKept       换表时保留下来的启用计划数
 */
public record SectionApplyOutcome(
        boolean modelChanged,
        boolean catalogChanged,
        boolean valuesChanged,
        boolean scheduleChanged,
        boolean throttleChanged,
        boolean dataChanged,
        boolean cycleChanged,
        int pinsDropped,
        int plansKept) {

    /** 什么都没动（幂等保存或只有 UI 层变化）。 */
    public static SectionApplyOutcome nothing() {
        return new SectionApplyOutcome(false, false, false, false, false, false, false, 0, 0);
    }
}
