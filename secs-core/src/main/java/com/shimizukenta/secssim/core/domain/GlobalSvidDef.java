package com.shimizukenta.secssim.core.domain;

/**
 * 设备级共享 SVID（绝对 ID）。
 *
 * <p>典型用途：厂务数据（环境温度、电源状态）、设备汇总值。
 * 它不属于任何单元，但可以被任意单元的报告引用。
 * </p>
 *
 * <p>{@code min}/{@code max} 是可选值域：不配时默认无界，
 * 由 {@code DefaultCurves} 退化为 0..100 的有界随机游走。
 * 设备级变量没有“槽位定义”这一步，值域只能在这里给。
 * </p>
 *
 * @param id     绝对 SVID
 * @param name   名称
 * @param type   类型
 * @param min    值域下界（默认负无穷）
 * @param max    值域上界（默认正无穷）
 * @param source 直配值来源（schema v6，可选；{@code null} 用默认曲线）
 */
public record GlobalSvidDef(int id, String name, ValueType type, double min, double max, DirectSource source) {

    /**
     * 兼容旧调用的构造：不配值来源。
     *
     * @param id   绝对 SVID
     * @param name 名称
     * @param type 类型
     * @param min  值域下界
     * @param max  值域上界
     */
    public GlobalSvidDef(int id, String name, ValueType type, double min, double max) {
        this(id, name, type, min, max, null);
    }

    /**
     * 简化构造：不带值域。
     *
     * @param id   绝对 SVID
     * @param name 名称
     * @param type 类型
     */
    public GlobalSvidDef(int id, String name, ValueType type) {
        this(id, name, type, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * 校验并创建。
     *
     * @param id   绝对 SVID
     * @param name 名称
     * @param type 类型
     * @param min  值域下界
     * @param max  值域上界
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
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " > max " + max + " for global svid " + name);
        }
    }
}
