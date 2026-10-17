package com.shimizukenta.secssim.core.domain;

import java.util.List;

/**
 * 报告定义（RPTID → VID 列表），槽位相对。
 *
 * @param offset    段内偏移，≥ 1
 * @param slot      符号名（如 {@code PROCESS_STATUS}）
 * @param svidNames 该报告包含的 SVID 符号名（顺序即报文中的顺序）
 */
public record ReportDef(int offset, String slot, List<String> svidNames) {

    /**
     * 校验并创建。
     *
     * @param offset    段内偏移
     * @param slot      符号名
     * @param svidNames SVID 名
     */
    public ReportDef {
        if (offset < 1) {
            throw new IllegalArgumentException("report offset must be >= 1 but was " + offset);
        }
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("report slot must not be blank");
        }
        svidNames = List.copyOf(svidNames == null ? List.of() : svidNames);
    }

    /**
     * 按段起点解析出绝对 ID。
     *
     * @param base 段起点
     * @return 绝对 RPTID
     */
    public int resolve(int base) {
        return base + this.offset;
    }
}
