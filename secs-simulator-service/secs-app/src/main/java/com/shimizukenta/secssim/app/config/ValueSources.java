package com.shimizukenta.secssim.app.config;

import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.value.BoundedValueSource;
import com.shimizukenta.secssim.core.value.CurveValueSource;
import com.shimizukenta.secssim.core.value.DataSourceValueSource;
import com.shimizukenta.secssim.core.value.UnitIdValueSource;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.core.value.ValueStore;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话值来源的统一构建入口（schema v7）。
 *
 * <p>只有两条路，全部集中在这里，{@code SessionLauncher} 与草稿预览都调它：</p>
 * <ol>
 *   <li>配了任意值定义（{@code SVID → source}）→ {@link DataSourceValueSource}，
 *       没配的项取 0；</li>
 *   <li>一个都没配 → {@link DefaultCurves} 默认曲线（新建设备的“能跑起来”状态）。</li>
 * </ol>
 *
 * <p>v7 之前还有第三条（模板图 / 实例绑定 / SVID 直配），已随值模型收窄删除。</p>
 */
public final class ValueSources {

    private ValueSources() {
    }

    /**
     * 按配置构建值来源。
     *
     * @param config    会话配置
     * @param equipment 已编译的设备模型
     * @return 值来源
     */
    public static ValueSource of(SessionConfig config, ResolvedEquipment equipment) {
        final ValueSource values;

        if (config.hasSources()) {
            /* 一项一份 source：没有模板图、没有实例绑定、没有单写者冲突。
               周期时钟决定曲线拿到的是片内相位还是绝对时刻。 */
            values = new DataSourceValueSource(
                    config.svidSources(),
                    config.cycleClock(),
                    config.spec().seed(),
                    config.cycle() != null && config.cycle().noisePerWafer());
        } else {
            ValueStore store = new ValueStore(config.spec().seed());
            DefaultCurves.bind(store, config.spec().equipment(), equipment);
            values = new CurveValueSource(store);
        }

        /* UNIT_ID 是设备语义，不是用户曲线：任何路径都必须返回单元号，
           否则 S1F3 查到 0、S6F11 注入的却是真值。 */
        Map<Integer, Double> unitIds = unitIdValues(config, equipment);
        final ValueSource withUnitIds = unitIds.isEmpty()
                ? values : new UnitIdValueSource(values, unitIds);

        /* 值域在取值处收紧：类型宽度是硬约束，越界/NaN 不能留到报文编码时才炸。
           启动、热生效、草稿预览都走这里，三种形态的语义因此一致。 */
        return new BoundedValueSource(withUnitIds, equipment.svids());
    }

    /** 收集「UNIT_ID SVID → 单元编号」；没有这类 SVID 时返回空表。 */
    private static Map<Integer, Double> unitIdValues(SessionConfig config, ResolvedEquipment equipment) {
        Map<String, Integer> unitIdByName = new HashMap<>();
        for (UnitSpec unit : config.spec().equipment().units()) {
            unitIdByName.put(unit.name(), unit.unitId().value());
        }

        Map<Integer, Double> result = new HashMap<>();
        for (ResolvedSvid svid : equipment.svids().values()) {
            if (!UnitSpec.UNIT_ID_SLOT.equals(svid.slot())) {
                continue;
            }
            Integer unitId = unitIdByName.get(svid.owner());
            if (unitId != null) {
                result.put(svid.id(), unitId.doubleValue());
            }
        }
        return result;
    }
}
