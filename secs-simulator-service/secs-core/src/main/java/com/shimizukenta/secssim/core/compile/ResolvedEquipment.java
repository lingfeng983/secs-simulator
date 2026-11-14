package com.shimizukenta.secssim.core.compile;

import java.util.Map;

/**
 * 可直接运行（或送入报文层）的设备模型。
 *
 * <p>SVID / RPTID / CEID 三个命名空间彼此独立，各自以绝对 ID 为键。
 * 主机侧报文里只有数字，所以这三张表就是"数字 → 语义"的唯一桥梁。
 * </p>
 *
 * @param name      设备名
 * @param deviceId  device-id
 * @param svids     SVID 表
 * @param reports   报告表
 * @param ceids     CEID 表
 * @param unitByCeid CEID → 归属单元名（含设备级 {@code GLOBAL}）
 */
public record ResolvedEquipment(String name, int deviceId,
                                Map<Integer, ResolvedSvid> svids,
                                Map<Integer, ResolvedReport> reports,
                                Map<Integer, ResolvedCeid> ceids,
                                Map<Integer, String> unitByCeid) {

    /**
     * 校验并创建。
     *
     * @param name       设备名
     * @param deviceId   device-id
     * @param svids      SVID 表
     * @param reports    报告表
     * @param ceids      CEID 表
     * @param unitByCeid CEID 归属
     */
    public ResolvedEquipment {
        svids = Map.copyOf(svids);
        reports = Map.copyOf(reports);
        ceids = Map.copyOf(ceids);
        unitByCeid = Map.copyOf(unitByCeid);
    }

    /**
     * 派生一份只换 SVID 表的模型（运行时增删改变量用）。
     *
     * @param svids 新的 SVID 表
     * @return 新模型
     */
    public ResolvedEquipment withSvids(Map<Integer, ResolvedSvid> svids) {
        return new ResolvedEquipment(this.name, this.deviceId, svids, this.reports, this.ceids, this.unitByCeid);
    }

    /**
     * 派生一份只换报告表的模型（运行时增删改报告用）。
     *
     * @param reports 新的报告表
     * @return 新模型
     */
    public ResolvedEquipment withReports(Map<Integer, ResolvedReport> reports) {
        return new ResolvedEquipment(this.name, this.deviceId, this.svids, reports, this.ceids, this.unitByCeid);
    }

    /**
     * 派生一份只换 CEID 表(及其归属)的模型（运行时增删改事件用）。
     *
     * @param ceids      新的 CEID 表
     * @param unitByCeid 新的 CEID 归属表
     * @return 新模型
     */
    public ResolvedEquipment withCeids(Map<Integer, ResolvedCeid> ceids, Map<Integer, String> unitByCeid) {
        return new ResolvedEquipment(this.name, this.deviceId, this.svids, this.reports, ceids, unitByCeid);
    }
}
