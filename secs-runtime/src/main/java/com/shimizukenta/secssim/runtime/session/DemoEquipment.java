package com.shimizukenta.secssim.runtime.session;

import java.util.List;

import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.ReportDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;

/**
 * 演示用设备：两腔体，各自的 SVID / CEID 号段不同。
 *
 * <p>它同时是 M2 的"活文档"：一份最小但结构完整的设备配置，
 * 能直接跑起来让主机问 S1F3。M4 会把它换成 12 个内置模板中的一个。
 * </p>
 */
public final class DemoEquipment {

    private DemoEquipment() {
    }

    /**
     * 两腔体设备。
     *
     * @param name     设备名
     * @param deviceId device-id
     * @return 设备配置
     */
    public static EquipmentSpec twoChambers(String name, int deviceId) {
        return new EquipmentSpec(name, deviceId,
                List.of(chamber("CH1", 1), chamber("CH2", 2)),
                List.of(),
                List.of(),
                IdSpacePolicy.defaults());
    }

    private static UnitSpec chamber(String name, int unitId) {
        return new UnitSpec(UnitId.of(unitId), name,
                List.of(
                        new SvidDef(1, "TEMP_PV", ValueType.F4, "C", 2, 0.0, 400.0),
                        new SvidDef(2, "TEMP_SV", ValueType.F4, "C", 2, 0.0, 400.0),
                        new SvidDef(3, "PRESS", ValueType.F4, "Torr", 3, 0.0, 10.0),
                        new SvidDef(4, "HEATER", ValueType.BOOLEAN, "", 0, 0.0, 1.0),
                        new SvidDef(90, "UNIT_ID", ValueType.U4, "", 0, 0.0, 64.0)),
                List.of(new ReportDef(1, "STATUS", List.of("UNIT_ID", "TEMP_PV", "PRESS")),
                        new ReportDef(2, "MEASURE", List.of("TEMP_PV", "PRESS"))),
                List.of(new CeidDef(1, "PROCESS_START", List.of("STATUS")),
                        CeidDef.of(2, "PROCESS_END"),
                        new CeidDef(3, "MEASURE_EVENT", List.of("MEASURE"))),
                true);
    }
}
