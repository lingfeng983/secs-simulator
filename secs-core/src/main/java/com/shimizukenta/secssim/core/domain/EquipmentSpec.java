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
                            IdSpacePolicy idSpace, List<EquipmentConstantDef> constants) {

    /**
     * 不带设备常量的构造（S2F13/F14 会回空列表、S2F15 一律拒绝）。
     *
     * @param name        设备名
     * @param deviceId    device-id
     * @param units       单元列表
     * @param globalSvids 共享 SVID
     * @param globalCeids 设备级 CEID
     * @param idSpace     ID 段策略
     */
    public EquipmentSpec(String name, int deviceId, List<UnitSpec> units,
                         List<GlobalSvidDef> globalSvids, List<GlobalCeidDef> globalCeids,
                         IdSpacePolicy idSpace) {
        this(name, deviceId, units, globalSvids, globalCeids, idSpace, List.of());
    }

    /**
     * 校验并创建。
     *
     * @param name        设备名
     * @param deviceId    device-id
     * @param units       单元列表
     * @param globalSvids 共享 SVID
     * @param globalCeids 设备级 CEID
     * @param idSpace     ID 段策略
     * @param constants   设备常量（ECV）
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
        constants = List.copyOf(constants == null ? List.of() : constants);

        /* ECID 重复会让 S2F13 返回两个同号值、S2F15 写哪个都说不清，
           在构造期就拦下来，别等到运行时 */
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (EquipmentConstantDef def : constants) {
            if (! seen.add(def.id())) {
                throw new IllegalArgumentException("duplicate equipment constant id: " + def.id());
            }
        }

        if (idSpace == null) {
            throw new IllegalArgumentException("idSpace must not be null");
        }
    }
}
