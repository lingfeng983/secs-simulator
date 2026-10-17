package com.shimizukenta.secssim.app.session;

import java.util.List;

import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.curve.BoundPolicy;
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.curve.RandomWalkCurve;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
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
    }

    private static Curve curveFor(UnitSpec unit, SvidDef def) {
        if (UnitSpec.UNIT_ID_SLOT.equals(def.slot())) {
            return new ConstantCurve(unit.unitId().value());
        }

        if (!def.type().isNumeric()) {
            return new ConstantCurve(def.type() == com.shimizukenta.secssim.core.domain.ValueType.BOOLEAN ? 1.0 : 0.0);
        }

        double lo = Double.isFinite(def.min()) ? def.min() : 0.0;
        double hi = Double.isFinite(def.max()) ? def.max() : lo + 100.0;
        if (!(lo < hi)) {
            hi = lo + 1.0;
        }

        double span = hi - lo;
        double mid = lo + span * 0.6;

        return new RandomWalkCurve(mid, span * 0.001, span * 0.02, 2_000L, 3, lo, hi, BoundPolicy.REFLECT);
    }
}
