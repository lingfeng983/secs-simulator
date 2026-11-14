package com.shimizukenta.secssim.core.compile;

import java.util.List;

/**
 * 已解析出绝对 ID 的采集事件。
 *
 * @param id        绝对 CEID
 * @param owner     归属单元名，设备级为 {@code GLOBAL}
 * @param slot      符号名
 * @param reportIds 该事件携带的绝对 RPTID
 */
public record ResolvedCeid(int id, String owner, String slot, List<Integer> reportIds) {

    /**
     * 校验并创建。
     *
     * @param id        绝对 CEID
     * @param owner     归属
     * @param slot      符号名
     * @param reportIds RPTID 列表
     */
    public ResolvedCeid {
        reportIds = List.copyOf(reportIds);
    }
}
