package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.compile.Issue;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;

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
    public static final int SCHEMA_VERSION = 5;

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
        }

        return new CompilationResult(compiled.equipment(), issues);
    }
}
