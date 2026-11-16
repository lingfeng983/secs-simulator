package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.core.context.ContextTable;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.value.CurveValueSource;
import com.shimizukenta.secssim.core.value.ValueStore;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 不断连热生效：整体替换事件编排、限流、W 位与值来源。
 *
 * <p>只允许在"会话结构与连接标识都没变"的重载场景调用（见
 * {@link com.shimizukenta.secssim.runtime.SessionLauncher} 的指纹比较）；
 * 设备模型变了必须走关旧开新。运行中的 ticker 不动——编排表是
 * volatile 引用，下一个 pump 周期自动看到新计划。</p>
 */
final class HotReloader {

    /** 换设备模型后重建引用它的视图（S6F11 组装器与 Trace）。 */
    private final java.util.function.Consumer<ResolvedEquipment> refreshModelViews;

    /** 设置 S6F11 的 W 位。 */
    private final java.util.function.Consumer<Boolean> collectionEventWbit;

    /** 设置 S6F1 的 W 位。 */
    private final java.util.function.Consumer<Boolean> traceWbit;

    /** 装载编排表并设限流。 */
    private final java.util.function.BiConsumer<EventSchedule, Integer> enableSchedule;

    /** 当前值来源（可变句柄）。 */
    private final ValueSourceHandle values;

    /** 值存储：默认曲线要绑进它。 */
    private final ValueStore store;

    /** 当前设备定义（规格）。 */
    private final java.util.function.Supplier<com.shimizukenta.secssim.core.domain.EquipmentSpec> equipment;

    /**
     * 换晶圆属性表 + 设备常量取值器（P0 修复：热路径此前丢弃新编译结果，
     * Context / EcidValues 只在 open 时挂一次，热重载后 S6F11 / S2F13 仍是旧值）。
     */
    private final java.util.function.BiConsumer<ContextTable, EcidValues> dataModel;

    HotReloader(java.util.function.Consumer<ResolvedEquipment> refreshModelViews,
                java.util.function.Consumer<Boolean> collectionEventWbit,
                java.util.function.Consumer<Boolean> traceWbit,
                java.util.function.BiConsumer<EventSchedule, Integer> enableSchedule,
                ValueSourceHandle values,
                ValueStore store,
                java.util.function.Supplier<com.shimizukenta.secssim.core.domain.EquipmentSpec> equipment) {
        this(refreshModelViews, collectionEventWbit, traceWbit, enableSchedule,
                values, store, equipment, null);
    }

    HotReloader(java.util.function.Consumer<ResolvedEquipment> refreshModelViews,
                java.util.function.Consumer<Boolean> collectionEventWbit,
                java.util.function.Consumer<Boolean> traceWbit,
                java.util.function.BiConsumer<EventSchedule, Integer> enableSchedule,
                ValueSourceHandle values,
                ValueStore store,
                java.util.function.Supplier<com.shimizukenta.secssim.core.domain.EquipmentSpec> equipment,
                java.util.function.BiConsumer<ContextTable, EcidValues> dataModel) {
        this.refreshModelViews = refreshModelViews;
        this.collectionEventWbit = collectionEventWbit;
        this.traceWbit = traceWbit;
        this.enableSchedule = enableSchedule;
        this.values = values;
        this.store = store;
        this.equipment = equipment;
        this.dataModel = dataModel == null ? (c, e) -> { } : dataModel;
    }

    /**
     * 不断连热生效（全量）：连设备模型一起替换。
     *
     * <p>连接标识（id/端口/绑定地址/deviceId/超时）在 spec 里，不在替换范围——
     * 那些变了仍然要关旧开新。设备模型变了要同步换组装器与 Trace：
     * Trace 绑定旧模型的 SVID 集合，留着会采到已删除的变量。</p>
     *
     * @param schedule        新编排表
     * @param maxEventsPerSec 新限流
     * @param s6f11Wbit       S6F11 W 位
     * @param s6f1Wbit        S6F1 W 位
     * @param model           新设备模型（单元/变量/报告/事件定义）
     * @param values          新值来源；{@code null} 表示回落设备默认曲线
     */
    ValueSource apply(EventSchedule schedule, int maxEventsPerSec,
                      boolean s6f11Wbit, boolean s6f1Wbit,
                      ResolvedEquipment model, ValueSource values,
                      ContextTable contextTable, EcidValues ecidValues) {
        this.collectionEventWbit.accept(s6f11Wbit);
        this.traceWbit.accept(s6f1Wbit);
        this.refreshModelViews.accept(model);
        /* 晶圆属性表 / 设备常量取值器跟着换：热路径此前漏了这两份（审计 P0） */
        if (contextTable != null && ecidValues != null) {
            this.dataModel.accept(contextTable, ecidValues);
        }
        this.enableSchedule.accept(schedule, maxEventsPerSec);
        if (values != null) {
            this.values.set(values);
            return values;
        }
        /* 没有自定义来源：默认曲线要先重新绑进值存储 */
        DefaultCurves.bind(this.store, this.equipment.get(), model);
        final ValueSource source = new CurveValueSource(this.store);
        this.values.set(source);
        return source;
    }
}
