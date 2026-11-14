package com.shimizukenta.secssim.app.session;

import java.util.List;

import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.curve.BoundPolicy;
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.curve.RandomWalkCurve;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.value.ValueStore;

/**
 * 在没有显式曲线配置时，按 SVID 定义生成"看起来合理"的默认曲线。
 *
 * <p>M2 的阶段目标不是配出完美工艺曲线，而是让"S1F3 一问就有数"成立。
 * 因此默认策略是：在 SVID 声明的值域内做有界随机游走，{@code UNIT_ID} 绑定单元号。
 * M3 起这里的地位会被模板 DAG 取代。
 * </p>
 */
public final class DefaultCurves {

    private DefaultCurves() {
    }

    /**
     * 把默认曲线绑定到值存储。
     *
     * @param store     值存储
     * @param spec      设备配置
     * @param equipment 已解析的设备模型
     */
    public static void bind(ValueStore store, EquipmentSpec spec, ResolvedEquipment equipment) {
        List<UnitSpec> units = spec.units();

        for (int i = 0; i < units.size(); i++) {
            UnitSpec unit = units.get(i);
            int svidBase = spec.idSpace().svidBaseFor(i + 1);

            for (SvidDef def : unit.svids()) {
                int svid = def.resolve(svidBase);
                if (!equipment.svids().containsKey(svid)) {
                    continue;
                }
                store.bind(svid, curveFor(unit, def));
            }
        }

        /*
         * 设备级 SVID 不属于任何单元，必须单独绑定。
         * 漏掉这一段的后果是：配置里声明了 FACILITY_TEMP，S1F3/S6F11 却永远返回 0。
         */
        for (GlobalSvidDef def : spec.globalSvids()) {
            if (!equipment.svids().containsKey(def.id())) {
                continue;
            }
            store.bind(def.id(), curveFor(def));
        }
    }

    private static Curve curveFor(UnitSpec unit, SvidDef def) {
        if (UnitSpec.UNIT_ID_SLOT.equals(def.slot())) {
            return new ConstantCurve(unit.unitId().value());
        }

        if (!def.type().isNumeric()) {
            return new ConstantCurve(def.type() == com.shimizukenta.secssim.core.domain.ValueType.BOOLEAN ? 1.0 : 0.0);
        }

        return numericCurve(def.min(), def.max());
    }

    private static Curve curveFor(GlobalSvidDef def) {
        if (!def.type().isNumeric()) {
            return new ConstantCurve(def.type() == com.shimizukenta.secssim.core.domain.ValueType.BOOLEAN ? 1.0 : 0.0);
        }

        return numericCurve(def.min(), def.max());
    }

    /**
     * 数值型默认曲线：在值域内做有界随机游走。
     *
     * <p>设备级 SVID 的 schema 里没有槽位定义，值域可选；
     * 不配时退化为 0..100，避免“声明了变量却永远是 0”。</p>
     */
    private static Curve numericCurve(double configuredMin, double configuredMax) {
        double lo = Double.isFinite(configuredMin) ? configuredMin
                : (Double.isFinite(configuredMax) ? configuredMax - 100.0 : 0.0);
        double hi = Double.isFinite(configuredMax) ? configuredMax : lo + 100.0;

        if (!(lo < hi)) {
            hi = lo + 1.0;
        }

        double span = hi - lo;
        double mid = lo + span * 0.6;

        return new RandomWalkCurve(mid, span * 0.001, span * 0.02, 2_000L, 3, lo, hi, BoundPolicy.REFLECT);
    }
}
