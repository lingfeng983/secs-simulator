package com.shimizukenta.secssim.app.config;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.compile.Issue;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.domain.DirectSource;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.template.TemplateGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份完整配置：会话参数 + 事件编排 + 限流。
 *
 * @param spec            会话参数
 * @param schedule        事件编排
 * @param maxEventsPerSec 设备级每秒最大上报数
 * @param s6f11Wbit        S6F11 是否带 W 位并等待 S6F12（默认 false）
 * @param s6f1Wbit         S6F1 是否带 W 位并等待 S6F2（默认 false）
 * @param templates        模板/曲线配置（默认空）
 */
public record SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec,
                            boolean s6f11Wbit, boolean s6f1Wbit, TemplateConfig templates) {

    /** 当前 schema 版本。 */
    public static final int SCHEMA_VERSION = 6;

    /** 兼容读取的最小 schema 版本。 */
    public static final int MIN_SCHEMA_VERSION = 4;

    /**
     * 兼容旧调用的构造：默认 S6F11 不带 W 位。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     */
    public SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec) {
        this(spec, schedule, maxEventsPerSec, false, false, TemplateConfig.empty());
    }

    /**
     * 兼容只配置 S6F11 W 位的调用：S6F1 默认不带 W 位。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     * @param s6f11Wbit       S6F11 是否带 W 位
     */
    public SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit) {
        this(spec, schedule, maxEventsPerSec, s6f11Wbit, false, TemplateConfig.empty());
    }

    /**
     * 兼容只配置两个 W 位的调用：模板配置为空。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     * @param s6f11Wbit       S6F11 是否带 W 位
     * @param s6f1Wbit        S6F1 是否带 W 位
     */
    public SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit, boolean s6f1Wbit) {
        this(spec, schedule, maxEventsPerSec, s6f11Wbit, s6f1Wbit, TemplateConfig.empty());
    }

    /**
     * 校验并创建。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     * @param s6f11Wbit       S6F11 是否带 W 位
     * @param s6f1Wbit        S6F1 是否带 W 位
     * @param templates        模板/曲线配置
     */
    public SessionConfig {
        if (schedule == null) {
            schedule = EventSchedule.empty();
        }
        if (templates == null) {
            templates = TemplateConfig.empty();
        }
        if (maxEventsPerSec < 1) {
            throw new IllegalArgumentException("maxEventsPerSec must be >= 1 but was " + maxEventsPerSec);
        }
    }

    /**
     * 编译设备模型（解析绝对 ID 并校验），并校验事件计划引用的 CEID。
     *
     * <p>计划里的 CEID 是本地编排的一部分，必须在设备模型里真实存在：
     * 否则配置能存、能启动，但运行期每次触发都会在组装 S6F11 时抛
     * “未知 CEID”，只留下一条日志——表现为“设备在线、计划启用、却永远收不到事件”。
     * 把它挪到编译期，编辑器的「校验」与启动走同一道门。</p>
     *
     * <p>schema v6 追加两类检查：事件效果（effects）的 SVID 存在性与类型兼容性；
     * SVID 直配值来源（curve/template）的类型兼容性。直配与
     * {@code templates.instances} 的单写者冲突在 {@link #buildValues}（模板图）里报。</p>
     *
     * @return 编译结果（含设备模型问题与未知 CEID 问题）
     */
    public CompilationResult compile() {
        CompilationResult compiled = EquipmentCompiler.compile(this.spec.equipment());
        List<Issue> issues = new ArrayList<>(compiled.issues());

        for (EventPlan plan : this.schedule.plans()) {
            if (!compiled.equipment().ceids().containsKey(plan.ceid())) {
                issues.add(Issue.error("UNKNOWN_CEID",
                        "事件计划 " + plan.id() + " 引用的 CEID " + plan.ceid()
                                + " 在设备模型里不存在（先在该单元的 ceids 里定义它）"));
            }
            for (int i = 0; i < plan.effects().size(); i++) {
                EventEffect effect = plan.effects().get(i);
                ResolvedSvid svid = compiled.equipment().svids().get(effect.svid());
                if (svid == null) {
                    issues.add(Issue.error("UNKNOWN_EFFECT_SVID",
                            "事件计划 " + plan.id() + " 的效果[" + i + "] 引用了不存在的 SVID " + effect.svid()));
                    continue;
                }
                if (svid.type().isNumeric()) {
                    continue;
                }
                boolean ok = effect.op() == EventEffect.Op.RESUME
                        || (effect.op() == EventEffect.Op.SET && svid.type() == ValueType.BOOLEAN
                                && (effect.value() != null && (effect.value() == 0.0 || effect.value() == 1.0)));
                if (!ok) {
                    issues.add(Issue.error("EFFECT_TYPE_MISMATCH",
                            "事件计划 " + plan.id() + " 的效果[" + i + "] 作用于非数值 SVID " + effect.svid()
                                    + "（" + svid.type() + "），只支持 set(0/1) 与 resume"));
                }
            }
        }

        for (Map.Entry<Integer, DirectSource> entry : directSources().entrySet()) {
            ResolvedSvid svid = compiled.equipment().svids().get(entry.getKey());
            if (svid != null && !svid.type().isNumeric()) {
                issues.add(Issue.error("SOURCE_TYPE_MISMATCH",
                        "SVID " + entry.getKey() + "（" + svid.type() + "）是非数值类型，不能直配曲线/模板值来源"));
            }
        }

        return new CompilationResult(compiled.equipment(), issues);
    }

    /**
     * 是否配置了自定义值来源（templates 段或任意 SVID 直配）。
     *
     * <p>都没有时值引擎走 {@code DefaultCurves} 默认曲线；
     * 有任意一个就走模板图（未绑定到来源的 SVID 取 0，与 v5 模板语义一致）。</p>
     *
     * @return 有自定义值来源返回 {@code true}
     */
    public boolean hasCustomValues() {
        return !this.templates.isEmpty() || !directSources().isEmpty();
    }

    /**
     * 构建值来源的模板图：用户实例 + SVID 直配合成的实例。
     *
     * @param equipment 已编译的设备模型
     * @return 模板图
     * @throws ConfigException 未知模板/SVID、输出名不匹配、环或单写者冲突
     */
    public TemplateGraph buildValues(ResolvedEquipment equipment) {
        return this.templates.buildWithDirect(equipment, directSources());
    }

    /**
     * 收集设备配置里的全部直配值来源，解析成绝对 SVID。
     *
     * @return 绝对 SVID → 直配来源
     */
    private Map<Integer, DirectSource> directSources() {
        Map<Integer, DirectSource> map = new LinkedHashMap<>();
        EquipmentSpec equipment = this.spec.equipment();

        for (GlobalSvidDef def : equipment.globalSvids()) {
            if (def.source() != null) {
                map.putIfAbsent(def.id(), def.source());
            }
        }

        List<UnitSpec> units = equipment.units();
        for (int i = 0; i < units.size(); i++) {
            int base = equipment.idSpace().svidBaseFor(i + 1);
            for (SvidDef def : units.get(i).svids()) {
                if (def.source() != null) {
                    map.putIfAbsent(def.resolve(base), def.source());
                }
            }
        }
        return map;
    }
}
