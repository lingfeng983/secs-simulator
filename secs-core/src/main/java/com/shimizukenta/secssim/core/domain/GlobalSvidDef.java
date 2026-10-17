package com.shimizukenta.secssim.core.domain;

/**
 * 设备级共享 SVID（绝对 ID）。
 *
 * <p>典型用途：厂务数据（环境温度、电源状态）、设备汇总值。
 * 它不属于任何单元，但可以被任意单元的报告引用。
 * </p>
 *
 * @param id   绝对 SVID
 * @param name 名称
 * @param type 类型
 */
public record GlobalSvidDef(int id, String name, ValueType type) {

    /**
     * 校验并创建。
     *
     * @param id   绝对 SVID
     * @param name 名称
     * @param type 类型
     */
    public GlobalSvidDef {
        if (id < 1) {
            throw new IllegalArgumentException("global svid must be >= 1 but was " + id);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("global svid name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("global svid type must not be null: " + name);
        }
    }
}
