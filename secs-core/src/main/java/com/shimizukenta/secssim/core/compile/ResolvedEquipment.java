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
}
