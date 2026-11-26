package com.shimizukenta.secssim.runtime.session;

import java.util.Map;
import java.util.Set;

import com.shimizukenta.secssim.app.config.CycleConfig;
import com.shimizukenta.secssim.core.context.ContextTable;
import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.cycle.LifecycleCycleClock;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.core.value.CurveValueSource;
import com.shimizukenta.secssim.core.value.ValueStore;
import com.shimizukenta.secssim.runtime.ConfigSection;

/**
 * 分段保存的最小生效落地（从 HsmsSessionRuntime 抽出，评审 2026-11-24 A1 千行拆分）。
 *
 * <p>与全量热替换（applyHot）的区别：那个是「重建会话 / 模型换了」的一律全停；
 * 这里只动真正变化的段——只改事件计划时，没改过的计划保留启用态与锚点、周期照跑；
 * 只改工艺周期时，周期回停止但计划、钉值、计数不动。</p>
 *
 * <p>本类只做编排，不持状态：所有可变部件经 {@link Parts} 访问器拿会话的同一实例，
 * 语义与拆分前逐行等价。</p>
 */
final class SectionApplySupport {

    private SectionApplySupport() {
        /* 纯静态工具 */
    }

    /** 会话可变部件的访问器：避免把十几个字段搬进新类造成两份状态。 */
    interface Parts {
        ResolvedEquipment model();

        void model(ResolvedEquipment next);

        SessionModelViews modelViews();

        ValueStore store();

        SessionSpec spec();

        PinnedValueSource pinnedValues();

        void pinnedValues(PinnedValueSource next);

        ValueSourceHandle values();

        EventScheduler scheduler();

        CycleRunner cycleRunner();

        void stopAllPlans();

        void collectionEventWbit(boolean enabled);

        void traceWbit(boolean enabled);
    }

    /**
     * 这次分段保存要动哪些运行态部件。
     *
     * <p>关键区分（2026-11-18 真机验守卫 B 后收紧）：</p>
     * <ul>
     *   <li>{@code structure}——单元/号段变了，旧编排、旧视图、旧引用全部不再成立，
     *       只有这一档要求"全停"；</li>
     *   <li>{@code catalog}——变量 / 报告 / 事件目录变了，要换模型视图、重建值来源与编排，
     *       但计划与周期可以照跑（未被引用的项按口径「可热改」）。</li>
     * </ul>
     *
     * <p>引用完整性不靠这里兜：删掉/改名被报告、周期、计划引用的对象，
     * 整份编译会在写盘前就 422 拦下，走不到 apply。</p>
     */
    record SectionEffects(
            boolean structure,
            boolean catalog,
            boolean values,
            boolean schedule,
            boolean throttle,
            boolean data,
            boolean cycle) {

        static SectionEffects of(Set<ConfigSection> changed) {
            boolean structure = changed.contains(ConfigSection.STRUCTURE);
            boolean catalog = changed.contains(ConfigSection.SVIDS)
                    || changed.contains(ConfigSection.REPORTS)
                    || changed.contains(ConfigSection.CEIDS);
            boolean views = structure || catalog;
            return new SectionEffects(
                    structure,
                    catalog,
                    /* 值来源与工艺周期共享同一个生命周期时钟：只改周期配置也必须重建值来源，
                       否则周期停了、曲线还在走（设计 §5.2）。钉值按 SVID 迁移，删掉的丢弃。 */
                    views || changed.contains(ConfigSection.CYCLE),
                    views || changed.contains(ConfigSection.PLANS),
                    views || changed.contains(ConfigSection.THROTTLE),
                    views
                            || changed.contains(ConfigSection.CONTEXT)
                            || changed.contains(ConfigSection.CONSTANTS),
                    structure || changed.contains(ConfigSection.CYCLE));
        }
    }

    /**
     * 按配置段最小生效（2026-11-18 设计 §4、§8）。
     *
     * <p>设备结构段变化才做全量热重置：单元/号段换了，旧视图、旧编排、旧引用都不再有效。
     * 变量 / 报告 / 事件目录变化只换模型视图、重建值来源与编排，计划与周期照跑
     * （口径「未被引用的项可热改」）。</p>
     *
     * @param host           会话可变部件访问器
     * @param model          新设备模型
     * @param values         新值来源；{@code null} = 回落设备默认曲线
     * @param schedule       新编排表
     * @param maxPerSec      新限流
     * @param s6f11Wbit      S6F11 W 位
     * @param s6f1Wbit       S6F1 W 位
     * @param contextTable   新晶圆属性表；{@code null} = 保持现状
     * @param ecidValues     新设备常量取值器；{@code null} = 保持现状
     * @param cycle          新 cycle 配置；{@code null} = 配置里没有 cycle 段
     * @param lifecycleClock 新生命周期时钟（与 values 同实例）
     * @param changed        实际变化的段（由保存结果给出）
     * @return 这次动了哪些东西
     */
    static SectionApplyOutcome applySections(
            Parts host,
            ResolvedEquipment model, ValueSource values,
            EventSchedule schedule, int maxPerSec, boolean s6f11Wbit, boolean s6f1Wbit,
            ContextTable contextTable,
            EcidValues ecidValues, CycleConfig cycle,
            LifecycleCycleClock lifecycleClock,
            Set<ConfigSection> changed) {

        SectionEffects fx = SectionEffects.of(changed);
        boolean modelChanged = fx.structure();
        boolean catalogChanged = fx.catalog();
        boolean valuesChanged = fx.values();
        boolean scheduleChanged = fx.schedule();
        boolean throttleChanged = fx.throttle();
        boolean dataChanged = fx.data();
        boolean cycleChanged = fx.cycle();

        /* --- 1. 模型与视图：结构变了先全停；只是目录（变量/报告/事件）变则换视图、运行不动 --- */
        if (modelChanged || catalogChanged) {
            host.model(model);
            host.modelViews().refresh(model);
        }
        if (modelChanged) {
            host.stopAllPlans();
        }

        /* --- 2. 值来源：重建后把钉值搬过去（只搬新模型里还存在的 SVID） --- */
        int pinsDropped = 0;
        if (valuesChanged) {
            ValueSource base = values;
            if (base == null) {
                DefaultCurves.bind(host.store(), host.spec().equipment(), model);
                base = new CurveValueSource(host.store());
            }
            PinnedValueSource current = host.pinnedValues();
            Map<Integer, Double> pins = current == null ? Map.of() : current.pins();
            PinnedValueSource next = new PinnedValueSource(base);
            for (Map.Entry<Integer, Double> entry : pins.entrySet()) {
                if (base.svids().contains(entry.getKey())) {
                    next.pin(entry.getKey(), entry.getValue());
                } else {
                    pinsDropped += 1;
                }
            }
            host.pinnedValues(next);
            host.values().set(next);
        }

        /* --- 3. 编排：模型换了走全停装载；只改计划则保留未改动计划的启用态 --- */
        int plansKept = 0;
        if (scheduleChanged) {
            if (modelChanged) {
                host.scheduler().enableSchedule(schedule, maxPerSec);
            } else {
                host.scheduler().replaceSchedulePreservingState(schedule, maxPerSec);
                plansKept = host.scheduler().enabledCount();
            }
        } else if (throttleChanged) {
            host.scheduler().maxPerSec(maxPerSec);
        }

        /* --- 4. W 位 --- */
        if (throttleChanged) {
            host.collectionEventWbit(s6f11Wbit);
            host.traceWbit(s6f1Wbit);
        }

        /* --- 5. 晶圆属性 / 设备常量：装配器持有引用，换掉下一次 S6F11 就生效 --- */
        if (dataChanged && contextTable != null && ecidValues != null) {
            host.modelViews().dataModel(contextTable, ecidValues);
        }

        /* --- 6. 工艺周期：配置换了就回 STOPPED，等操作员手动重新启动 --- */
        if (cycleChanged) {
            host.cycleRunner().reconfigure(cycle, lifecycleClock != null
                    ? lifecycleClock
                    : new LifecycleCycleClock(CycleClock.passthrough(), LifecycleCycleClock.State.RUNNING));
        }

        return new SectionApplyOutcome(modelChanged, catalogChanged, valuesChanged, scheduleChanged,
                throttleChanged, dataChanged, cycleChanged, pinsDropped, plansKept);
    }
}