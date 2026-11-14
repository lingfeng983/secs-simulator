package com.shimizukenta.secssim.core.domain;

import java.util.List;

/**
 * 设备级 CEID（绝对 ID），不属于任何单元。
 *
 * <p>典型用途：控制状态变化、厂务报警。它的报告允许跨单元引用，
 * 但必须在配置里显式声明，避免"某个单元的事件悄悄挂到设备级"。
 * </p>
 *
 * @param id          绝对 CEID
 * @param name        名称
 * @param reportNames 报告名（可跨单元）
 */
public record GlobalCeidDef(int id, String name, List<String> reportNames) {

    /**
     * 校验并创建。
     *
     * @param id          绝对 CEID
     * @param name        名称
     * @param reportNames 报告名
     */
    public GlobalCeidDef {
        if (id < 1) {
            throw new IllegalArgumentException("global ceid must be >= 1 but was " + id);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("global ceid name must not be blank");
        }
        reportNames = List.copyOf(reportNames == null ? List.of() : reportNames);
    }
}
