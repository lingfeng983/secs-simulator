package com.shimizukenta.secssim.app.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.compile.CompilationResult;
import com.shimizukenta.secssim.core.compile.EquipmentCompiler;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.value.ValueStore;

/**
 * 默认曲线绑定：单元 SVID 与设备级 SVID 都必须有值。
 *
 * <p>回归点：{@code DefaultCurves} 曾经只遍历 units，导致
 * {@code globalSvids} 在 S1F3/S6F11 里永远是 0。</p>
 */
class DefaultCurvesTest {

    @Test
    void bindsConfiguredGlobalSvidRange() {
        EquipmentSpec spec = equipment(
                new GlobalSvidDef(9001, "FACILITY_TEMP", ValueType.F4, 18.0, 26.0),
                new GlobalSvidDef(9002, "POWER_OK", ValueType.BOOLEAN));

        CompilationResult compiled = EquipmentCompiler.compile(spec);
        assertTrue(compiled.ok(), () -> "配置不合法: " + compiled.issues());

        ValueStore store = new ValueStore(1234L);
        DefaultCurves.bind(store, spec, compiled.equipment());

        assertTrue(store.isBound(9001), "配置了值域的 global SVID 必须绑定曲线");
        assertTrue(store.isBound(9002), "BOOLEAN 型 global SVID 也必须绑定");

        for (long t : List.of(0L, 1_000L, 60_000L, 3_600_000L)) {
            double value = store.valueAt(9001, t).orElseThrow();
            assertTrue(value >= 18.0 && value <= 26.0,
                    () -> "global SVID 应落在配置值域内，实际 " + value + " @" + t);
        }

        double booleanValue = store.valueAt(9002, 0L).orElseThrow();
        assertTrue(booleanValue == 0.0 || booleanValue == 1.0,
                () -> "BOOLEAN 默认值只能是 0/1，实际 " + booleanValue);
    }

    @Test
    void globalNumericSvidWithoutRangeStillProducesData() {
        EquipmentSpec spec = equipment(new GlobalSvidDef(9001, "FACILITY_TEMP", ValueType.F4));

        CompilationResult compiled = EquipmentCompiler.compile(spec);
        assertTrue(compiled.ok(), () -> "配置不合法: " + compiled.issues());

        ValueStore store = new ValueStore(99L);
        DefaultCurves.bind(store, spec, compiled.equipment());

        assertTrue(store.isBound(9001), "没有值域的 global 数值变量也要有默认曲线");
        for (long t : List.of(0L, 5_000L, 300_000L)) {
            double value = store.valueAt(9001, t).orElseThrow();
            assertTrue(value >= 0.0 && value <= 100.0,
                    () -> "默认值域应为 0..100，实际 " + value + " @" + t);
        }
    }

    private static EquipmentSpec equipment(GlobalSvidDef... globals) {
        UnitSpec unit = new UnitSpec(UnitId.of(1), "CH1",
                List.of(new SvidDef(1, "TEMP_PV", ValueType.F4, "C", 2, 0.0, 400.0)),
                List.of(),
                List.of(),
                false);

        return new EquipmentSpec("E", 10, List.of(unit), List.of(globals), List.of(),
                IdSpacePolicy.defaults());
    }
}
