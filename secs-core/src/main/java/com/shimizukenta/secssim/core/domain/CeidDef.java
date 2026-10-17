package com.shimizukenta.secssim.core.domain;

import java.util.List;

/**
 * 单元内的一个 CEID 定义（<b>槽位相对</b>）。
 *
 * <p>每个单元有自己独立的 CEID 号段，因此两个单元的"开工事件"可以是不同的 CEID，
 * 这正是多单元设备在主机侧被区分开的方式。
 * </p>
 *
 * @param offset      段内偏移，≥ 1
 * @param slot        符号名（如 {@code PROCESS_START}）
 * @param reportNames 该事件携带的报告名（同单元内）
 */
public record CeidDef(int offset, String slot, List<String> reportNames) {

    /**
     * 校验并创建。
     *
     * @param offset      段内偏移
     * @param slot        符号名
     * @param reportNames 报告名
     */
    public CeidDef {
        if (offset < 1) {
            throw new IllegalArgumentException("ceid offset must be >= 1 but was " + offset);
        }
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("ceid slot must not be blank");
        }
        reportNames = List.copyOf(reportNames == null ? List.of() : reportNames);
    }

    /**
     * 简化构造：不带报告的事件（仅通知）。
     *
     * @param offset 段内偏移
     * @param slot   符号名
     * @return 定义
     */
    public static CeidDef of(int offset, String slot) {
        return new CeidDef(offset, slot, List.of());
    }

    /**
     * 按段起点解析出绝对 ID。
     *
     * @param base 段起点
     * @return 绝对 CEID
     */
    public int resolve(int base) {
        return base + this.offset;
    }
}
