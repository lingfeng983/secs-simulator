package com.shimizukenta.secssim.core.compile;

import java.util.List;

/**
 * 已解析出绝对 ID 的报告。
 *
 * @param id       绝对 RPTID
 * @param owner    归属单元名
 * @param slot     符号名
 * @param svidIds  按报文顺序排列的绝对 SVID
 */
public record ResolvedReport(int id, String owner, String slot, List<Integer> svidIds) {

    /**
     * 校验并创建。
     *
     * @param id      绝对 RPTID
     * @param owner   归属
     * @param slot    符号名
     * @param svidIds SVID 列表
     */
    public ResolvedReport {
        svidIds = List.copyOf(svidIds);
    }
}
