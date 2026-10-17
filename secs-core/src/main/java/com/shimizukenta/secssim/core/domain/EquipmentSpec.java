package com.shimizukenta.secssim.core.domain;

import java.util.List;

/**
 * 一台设备（对应一个 SECS 端口）的完整配置。
 *
 * @param name        设备名（如 {@code PRI-01}）
 * @param deviceId    SECS device-id
 * @param units       单元列表，顺序决定 ID 段
 * @param globalSvids 设备级共享 SVID
 * @param globalCeids 设备级 CEID
 * @param idSpace     ID 段策略
 */
public record EquipmentSpec(String name, int deviceId, List<UnitSpec> units,
                            List<GlobalSvidDef> globalSvids, List<GlobalCeidDef> globalCeids,
                            IdSpacePolicy idSpace) {

    /**
     * 校验并创建。
     *
     * @param name        设备名
     * @param deviceId    device-id
     * @param units       单元列表
     * @param globalSvids 共享 SVID
     * @param globalCeids 设备级 CEID
     * @param idSpace     ID 段策略
     */
    public EquipmentSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("equipment name must not be blank");
        }
        if (deviceId < 0 || deviceId > 32767) {
            throw new IllegalArgumentException("deviceId out of range: " + deviceId);
        }
        units = List.copyOf(units == null ? List.of() : units);
        globalSvids = List.copyOf(globalSvids == null ? List.of() : globalSvids);
        globalCeids = List.copyOf(globalCeids == null ? List.of() : globalCeids);
        if (idSpace == null) {
            throw new IllegalArgumentException("idSpace must not be null");
        }
    }
}
