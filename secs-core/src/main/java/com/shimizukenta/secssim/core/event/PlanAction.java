package com.shimizukenta.secssim.core.event;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 计划的一条动作（3.0 需求 5）：一次触发可发多条消息。
 *
 * @param kind     动作类型：s6f11（事件报告，默认）/ sml（自定义报文）/
 *                 alarm / terminal / trace
 * @param stream   流号（sml 时可空——从 sml 原文解析）
 * @param function 功能号
 * @param wbit     是否带 W 位
 * @param sml      kind=sml 时的报文原文模板
 * @param params   预留参数（终端文本、报警号等）
 */
public record PlanAction(String kind, Integer stream, Integer function, Boolean wbit,
                         String sml, Map<String, String> params) {

    /** 动作类型常量（与 config.db 的 plan_action.kind 同一拼法）。 */
    public static final String KIND_S6F11 = "s6f11";
    public static final String KIND_SML = "sml";
    public static final String KIND_ALARM = "alarm";
    public static final String KIND_TERMINAL = "terminal";
    public static final String KIND_TRACE = "trace";

    /**
     * 校验并创建。
     *
     * @param kind     类型
     * @param stream   流号
     * @param function 功能号
     * @param wbit     W 位
     * @param sml      SML 原文
     * @param params   参数
     */
    public PlanAction {
        kind = kind == null ? KIND_S6F11 : kind.trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case KIND_S6F11, KIND_SML, KIND_ALARM, KIND_TERMINAL, KIND_TRACE -> {
                /* 合法 */
            }
            default -> throw new IllegalArgumentException("未知动作类型: " + kind
                    + "（可选 s6f11 / sml / alarm / terminal / trace）");
        }
        if (KIND_SML.equals(kind) && (sml == null || sml.isBlank())) {
            throw new IllegalArgumentException("kind=sml 时 sml 原文不能为空");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 便捷工厂：默认事件报告。 */
    public static PlanAction s6f11() {
        return new PlanAction(KIND_S6F11, null, null, null, null, Map.of());
    }

    /** 便捷工厂：自定义 SML。 */
    public static PlanAction sml(String sml) {
        return new PlanAction(KIND_SML, null, null, null, sml, Map.of());
    }
}
