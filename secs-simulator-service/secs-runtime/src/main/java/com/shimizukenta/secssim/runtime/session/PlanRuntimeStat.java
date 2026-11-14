package com.shimizukenta.secssim.runtime.session;

/**
 * 单条计划的运行证据（设计 §2.5：{@code GET /devices/{id}/runtime/plans}）。
 *
 * <p>计划正文在配置/运行时覆盖里，而「跑过几次、上次什么时候、上次为什么失败」
 * 只存在于 {@code runtime_plan_state}——两者合到一处才是界面上倒计时与
 * 「启用后确实在跑」的证据。</p>
 *
 * <p>时刻用 {@code 0} 表示「没有记录」而不是 JSON null：项目里的 JSON 视图
 * 一律不带 null，前端按 {@code > 0} 判断即可，避免各处多一条空值分支。</p>
 *
 * @param fireCount    真实触发次数（不含单步试跑）
 * @param skippedCount 被跳过的次数（限流、条件未满足、执行失败）
 * @param lastFireAt   最近一次触发时刻（毫秒）；0 = 从未触发
 * @param nextFireAt   下一次计划触发时刻（毫秒）；0 = 未知（由调度器现场推算）
 * @param lastError    最近一次失败原因；无则空串
 */
public record PlanRuntimeStat(long fireCount, long skippedCount, long lastFireAt,
                              long nextFireAt, String lastError) {

    /** 还没跑过、也没有任何统计的计划。 */
    public static final PlanRuntimeStat EMPTY = new PlanRuntimeStat(0L, 0L, 0L, 0L, "");
}
