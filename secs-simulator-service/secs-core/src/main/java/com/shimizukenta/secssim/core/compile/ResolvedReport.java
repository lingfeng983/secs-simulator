package com.shimizukenta.secssim.core.compile;

import java.util.List;

/**
 * 已解析出绝对 ID 的报告。
 *
 * @param id       绝对 RPTID
 * @param owner    归属单元名
 * @param slot     符号名
 * @param svidIds  报告里的 SVID（只含 SVID，供 S1F3/预览等只认变量的路径使用）
 * @param fields   报告字段全序列（SVID / 设备常量 / 晶圆属性，顺序即报文顺序）
 */
public record ResolvedReport(int id, String owner, String slot, List<Integer> svidIds, List<FieldRef> fields) {

    /**
     * 校验并创建。
     *
     * @param id      绝对 RPTID
     * @param owner   归属
     * @param slot    符号名
     * @param svidIds SVID 列表
     * @param fields  字段全序列
     */
    public ResolvedReport {
        svidIds = List.copyOf(svidIds);
        fields = List.copyOf(fields);
    }

    /**
     * 兼容构造：只有 SVID 的报告。
     *
     * @param id      绝对 RPTID
     * @param owner   归属
     * @param slot    符号名
     * @param svidIds SVID 列表
     */
    public ResolvedReport(int id, String owner, String slot, List<Integer> svidIds) {
        this(id, owner, slot, svidIds,
                svidIds.stream().map(FieldRef.Svid::new).map(FieldRef.class::cast).toList());
    }
}
