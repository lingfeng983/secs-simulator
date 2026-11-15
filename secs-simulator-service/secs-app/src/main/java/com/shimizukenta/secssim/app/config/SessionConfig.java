package com.shimizukenta.secssim.app.config;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.compile.FieldRef;
import com.shimizukenta.secssim.core.compile.Issue;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.context.ContextTable;
import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.value.DataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一份完整配置（schema v7）：会话参数 + 设备 + 三类数据的值定义 + 周期 + 事件。
 *
 * <p>v7 的值模型只有一条路径：<b>一个数据项一份 {@link DataSource}</b>。</p>
 * <ul>
 *   <li>{@link #svidSources()}：变量（SVID）→ 值定义；</li>
 *   <li>{@link #ecidSources()}：设备常量（ECID）→ 值定义（可随时间变化）；</li>
 *   <li>{@link #context()}：晶圆属性（LOTID 等）→ 值定义（按片变化）。</li>
 * </ul>
 *
 * <p>没有模板图、没有实例绑定、没有单写者冲突——一个编号只可能出现一次，
 * 因为它是 map 的 key。事件只上报，不改任何值。</p>
 *
 * @param spec            会话参数
 * @param schedule        事件编排（周期外的异常路径）
 * @param maxEventsPerSec 设备级每秒最大上报数
 * @param s6f11Wbit        S6F11 是否带 W 位并等待 S6F12（配置缺省 true）
 * @param s6f1Wbit         S6F1 是否带 W 位并等待 S6F2（配置缺省 true）
 * @param cycle           wafer 周期定义（可空＝不按片重复）
 * @param context         晶圆属性定义
 * @param svidSources     SVID → 值定义
 * @param ecidSources     ECID → 值定义
 */
public record SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec,
                            boolean s6f11Wbit, boolean s6f1Wbit,
                            CycleConfig cycle, List<ContextTable.Def> context,
                            Map<Integer, DataSource> svidSources, Map<Integer, DataSource> ecidSources) {

    /** 当前 schema 版本。 */
    public static final int SCHEMA_VERSION = 7;

    /** 兼容读取的最小 schema 版本。 */
    public static final int MIN_SCHEMA_VERSION = 7;

    /**
     * 最小构造：没有周期、没有任何值定义。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     */
    public SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec) {
        this(spec, schedule, maxEventsPerSec, false, false, null, List.of(), Map.of(), Map.of());
    }

    /**
     * 只配置 S6F11 W 位的构造。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     * @param s6f11Wbit       S6F11 是否带 W 位
     */
    public SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit) {
        this(spec, schedule, maxEventsPerSec, s6f11Wbit, false, null, List.of(), Map.of(), Map.of());
    }

    /**
     * 只配置两个 W 位的构造。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     * @param s6f11Wbit       S6F11 是否带 W 位
     * @param s6f1Wbit        S6F1 是否带 W 位
     */
    public SessionConfig(SessionSpec spec, EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit, boolean s6f1Wbit) {
        this(spec, schedule, maxEventsPerSec, s6f11Wbit, s6f1Wbit, null, List.of(), Map.of(), Map.of());
    }

    /**
     * 校验并创建。
     *
     * @param spec            会话参数
     * @param schedule        编排
     * @param maxEventsPerSec 限流
     * @param s6f11Wbit       S6F11 是否带 W 位
     * @param s6f1Wbit        S6F1 是否带 W 位
     * @param cycle           周期定义
     * @param context         晶圆属性
     * @param svidSources     SVID 值定义
     * @param ecidSources     ECID 值定义
     */
    public SessionConfig {
        if (schedule == null) {
            schedule = EventSchedule.empty();
        }
        context = context == null ? List.of() : List.copyOf(context);
        svidSources = svidSources == null ? Map.of() : Map.copyOf(svidSources);
        ecidSources = ecidSources == null ? Map.of() : Map.copyOf(ecidSources);
        if (maxEventsPerSec < 1) {
            throw new IllegalArgumentException("maxEventsPerSec must be >= 1 but was " + maxEventsPerSec);
        }
    }

    /**
     * 周期时钟：没有 {@code cycle} 段时退化为直通（相位 = 绝对时刻）。
     *
     * @return 时钟
     */
    public CycleClock cycleClock() {
        return this.cycle == null ? CycleClock.passthrough() : this.cycle.clock(this.spec.seed());
    }

    /**
     * 晶圆属性表。
     *
     * @return 表
     */
    public ContextTable contextTable() {
        return new ContextTable(this.context, this.cycleClock());
    }

    /**
     * 运行时真正生效的编排：手写计划 + 周期事件按片展开。
     *
     * <p>展开在取用时算，<b>不回写配置</b>——否则 {@code toJson} 会把生成的计划
     * 也写进文档，存一次翻一倍。</p>
     *
     * @return 编排
     */
    public EventSchedule effectiveSchedule() {
        if (this.cycle == null || this.cycle.events().isEmpty()) {
            return this.schedule;
        }
        List<EventPlan> plans = new ArrayList<>(this.schedule.plans());
        plans.addAll(this.cycle.expandPlans(this.spec.seed()));
        return new EventSchedule(plans);
    }

    /**
     * 编译设备模型（解析绝对 ID 并校验），并校验所有跨段引用。
     *
     * <p>计划里的 CEID 是本地编排的一部分，必须在设备模型里真实存在：
     * 否则配置能存、能启动，但运行期每次触发都会在组装 S6F11 时抛
     * “未知 CEID”，只留下一条日志——表现为“设备在线、计划启用、却永远收不到事件”。
     * 把它挪到编译期，编辑器的「校验」与启动走同一道门。</p>
     *
     * <p>v7 检查项：值定义只落在已知的数值型数据项上；晶圆属性不重名、
     * 不是 BINARY；报告里的 {@code ctx.*} 引用真实存在；周期事件的 CEID 存在。</p>
     *
     * @return 编译结果（含设备模型问题与跨段引用问题）
     */
    public CompilationResult compile() {
        CompilationResult compiled = EquipmentCompiler.compile(this.spec.equipment());
        List<Issue> issues = new ArrayList<>(compiled.issues());
        ResolvedEquipment model = compiled.equipment();

        for (EventPlan plan : this.schedule.plans()) {
            if (!model.ceids().containsKey(plan.ceid())) {
                issues.add(Issue.error("UNKNOWN_CEID",
                        "事件计划 " + plan.id() + " 引用的 CEID " + plan.ceid()
                                + " 在设备模型里不存在（先在该单元的 ceids 里定义它）"));
            }
            checkTrigger(plan, this.schedule, model, issues);
        }

        /* 每个 SVID 一份 source，必须是数值型且编号存在 */
        for (Map.Entry<Integer, DataSource> entry : this.svidSources.entrySet()) {
            int svid = entry.getKey();
            ResolvedSvid resolved = model.svids().get(svid);
            if (resolved == null) {
                issues.add(Issue.error("UNKNOWN_SOURCE_SVID",
                        "值定义引用了不存在的 SVID " + svid));
                continue;
            }
            /* BOOLEAN 走同一条数值管道（0/1 → bool），ASCII/BINARY 没有数值语义 */
            if (resolved.type() == ValueType.ASCII || resolved.type() == ValueType.BINARY) {
                issues.add(Issue.error("SOURCE_TYPE_MISMATCH",
                        "SVID " + svid + "（" + resolved.type() + "）没有数值语义，不能配置曲线式值定义"));
            }
        }

        /* 设备常量也可以是时间函数，但编号必须存在 */
        for (Map.Entry<Integer, DataSource> entry : this.ecidSources.entrySet()) {
            if (this.spec.equipment().constants().stream()
                    .noneMatch(constant -> constant.id() == entry.getKey())) {
                issues.add(Issue.error("UNKNOWN_SOURCE_ECID",
                        "值定义引用了不存在的设备常量 ECID " + entry.getKey()));
            }
        }

        /* 晶圆属性的名字不能重复，类型不能是不支持编码的 */
        Set<String> contextIds = new HashSet<>();
        for (ContextTable.Def def : this.context) {
            if (!contextIds.add(def.id())) {
                issues.add(Issue.error("DUPLICATE_CONTEXT", "晶圆属性重名：" + def.id()));
            }
            if (def.type() == ValueType.BINARY) {
                issues.add(Issue.error("CONTEXT_TYPE_MISMATCH",
                        "晶圆属性 " + def.id() + " 不支持 BINARY（报文里没有可读语义）"));
            }
        }

        /* 报告里的 ctx.<名> 必须真有这个属性（写错了报文里就是空串，很难发现） */
        for (ResolvedReport report : model.reports().values()) {
            for (FieldRef field : report.fields()) {
                if (field instanceof FieldRef.Context ref && !contextIds.contains(ref.name())) {
                    issues.add(Issue.error("UNKNOWN_CONTEXT_REF",
                            "报告 " + report.slot() + " 引用了不存在的晶圆属性 ctx." + ref.name()));
                }
            }
        }

        /* 周期事件的 CEID 必须在设备模型里存在 */
        if (this.cycle != null) {
            for (CycleConfig.Event event : this.cycle.events()) {
                if (!model.ceids().containsKey(event.ceid())) {
                    issues.add(Issue.error("UNKNOWN_CEID",
                            "周期事件（偏移 " + event.atMs() + "ms）引用的 CEID " + event.ceid()
                                    + " 在设备模型里不存在"));
                }
            }
        }

        return new CompilationResult(model, issues);
    }

    /**
     * 校验触发条件里的引用。
     *
     * <p>{@code onCondition} 的表达式在运行期求值：变量写错时每个 tick 抛异常、
     * 只记首条日志，现场表现为"计划启用却永不触发"，极难定位。AfterEvent 的来源
     * 计划也是同一类引用。与 CEID/SVID 一样，必须在编译期拦下。</p>
     *
     * @param plan      事件计划
     * @param schedule  整份编排（AfterEvent 需要查来源计划）
     * @param equipment 已解析的设备模型
     * @param issues    问题清单（追加）
     */
    private static void checkTrigger(EventPlan plan, EventSchedule schedule,
                                     ResolvedEquipment equipment, List<Issue> issues) {
        Trigger trigger = plan.trigger();

        if (trigger instanceof Trigger.OnCondition onCondition) {
            for (String variable : onCondition.expression().variables()) {
                if ("t".equals(variable)) {
                    continue;
                }
                if (!variable.startsWith("svid.")) {
                    issues.add(Issue.error("UNKNOWN_CONDITION_VARIABLE",
                            "事件计划 " + plan.id() + " 的条件变量 " + variable
                                    + " 不受支持（只支持 t 与 svid.<id>）"));
                    continue;
                }
                final int svid;
                try {
                    svid = Integer.parseInt(variable.substring("svid.".length()).trim());
                } catch (NumberFormatException e) {
                    issues.add(Issue.error("UNKNOWN_CONDITION_VARIABLE",
                            "事件计划 " + plan.id() + " 的条件变量格式非法：" + variable));
                    continue;
                }
                if (!equipment.svids().containsKey(svid)) {
                    issues.add(Issue.error("UNKNOWN_CONDITION_SVID",
                            "事件计划 " + plan.id() + " 的条件引用了不存在的 SVID " + svid));
                }
            }
        } else if (trigger instanceof Trigger.AfterEvent afterEvent) {
            if (afterEvent.sourcePlan().equals(plan.id())) {
                issues.add(Issue.error("SELF_REFERENCING_TRIGGER",
                        "事件计划 " + plan.id() + " 的 afterEvent 来源指向自己（永远不会触发）"));
            } else if (schedule.plans().stream().noneMatch(p -> p.id().equals(afterEvent.sourcePlan()))) {
                issues.add(Issue.error("UNKNOWN_SOURCE_PLAN",
                        "事件计划 " + plan.id() + " 的 afterEvent 来源计划 "
                                + afterEvent.sourcePlan() + " 不存在"));
            }
        }
    }

    /**
     * 是否配置了任何值定义。
     *
     * <p>都没有时值引擎走 {@code DefaultCurves} 默认曲线；有任意一个就走
     * 「一项一份 source」——没配值定义的项出口为 0，不再回落默认曲线
     * （否则同一台设备里两种语义并存，很难解释「为什么这个变量没按我配的走」）。</p>
     *
     * @return 有值定义返回 {@code true}
     */
    public boolean hasSources() {
        return !this.svidSources.isEmpty();
    }
}
