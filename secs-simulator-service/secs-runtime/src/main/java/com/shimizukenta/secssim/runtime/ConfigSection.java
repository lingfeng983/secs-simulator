package com.shimizukenta.secssim.runtime;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * 配置里可以按页单独保存、单独生效的片段。
 *
 * <p>2026-11-18「配置按页保存、按段生效」：页面只提交自己拥有的片段，
 * 后端按段替换、按段应用，不再每次整份覆盖。片段是<b>白名单枚举</b>而不是
 * 自由 JSON path——接口不接受任意指针，杜绝越权改别的段。</p>
 *
 * <p>每段的 {@link Effect} 是它对运行态的影响级别，前端据此显示
 * 「保存会重置什么」。判定依据见 docs/design/config-section-apply-2026-11-18.md。</p>
 */
public enum ConfigSection {

    /** 连接参数：端口 / 绑定地址 / Device ID / seed —— 只有它需要断链重建。 */
    SESSION("session", Effect.RECONNECT, "连接参数"),

    /** 设备结构与号段：增删单元、unitId、名称。要求设备不在线。 */
    STRUCTURE("structure", Effect.HOT_RESET, "设备结构"),

    /** 单元/全局 SVID 定义（含值来源、曲线、模板引用）。 */
    SVIDS("svids", Effect.HOT_RESET, "变量定义"),

    /** 报告定义（RPT）。 */
    REPORTS("reports", Effect.HOT_RESET, "报告定义"),

    /** 事件定义（CEID）。 */
    CEIDS("ceids", Effect.HOT_RESET, "事件定义"),

    /** 设备常量（ECV）。 */
    CONSTANTS("constants", Effect.HOT, "设备常量"),

    /** 晶圆属性表（ctx.*）。 */
    CONTEXT("context", Effect.HOT, "晶圆属性"),

    /** 工艺周期（cycle 段）。改配置即停周期，需手动重新启动。 */
    CYCLE("cycle", Effect.HOT, "工艺周期"),

    /** 事件计划（events.plans）。未改动的计划保留启用态。 */
    PLANS("plans", Effect.HOT, "事件计划"),

    /** 事件发送口径：每秒上限与两个 W 位。 */
    THROTTLE("throttle", Effect.HOT, "事件发送");

    /** 一次保存对运行态的影响级别。 */
    public enum Effect {
        /** 重启端口：断链、运行状态全清。 */
        RECONNECT("reconnect"),
        /** 不断链，但相关运行时重置（模型/值来源/目录编辑/Trace）。 */
        HOT_RESET("hot-reset"),
        /** 不断链，运行态保留。 */
        HOT("hot");

        private final String wire;

        Effect(String wire) {
            this.wire = wire;
        }

        /** 接口与界面使用的字符串。 */
        public String wire() {
            return this.wire;
        }

        /** 取更重的那个：一段是 reconnect，整次就是 reconnect。 */
        public Effect max(Effect other) {
            return this.ordinal() <= other.ordinal() ? this : other;
        }
    }

    private final String wire;
    private final Effect effect;
    private final String label;

    ConfigSection(String wire, Effect effect, String label) {
        this.wire = wire;
        this.effect = effect;
        this.label = label;
    }

    /** 接口里用的段名。 */
    public String wire() {
        return this.wire;
    }

    /** 该段的影响级别。 */
    public Effect effect() {
        return this.effect;
    }

    /** 中文名（错误信息与提示用）。 */
    public String label() {
        return this.label;
    }

    /** 段名 → 枚举；未知段返回空。 */
    public static Optional<ConfigSection> of(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        String key = wire.trim().toLowerCase(Locale.ROOT);
        for (ConfigSection section : values()) {
            if (section.wire.equals(key)) {
                return Optional.of(section);
            }
        }
        return Optional.empty();
    }

    /** 一组段里最重的影响级别（空集合视为 hot）。 */
    public static Effect effectOf(Collection<ConfigSection> sections) {
        Effect effect = Effect.HOT;
        for (ConfigSection section : sections) {
            effect = effect.max(section.effect);
        }
        return effect;
    }
}
