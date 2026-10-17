package com.shimizukenta.secssim.core.domain;

/**
 * 单元内的一个 SVID 定义（<b>槽位相对</b>）。
 *
 * <p>{@code offset} 是相对本单元 ID 段起点的偏移，绝对 ID = 段起点 + offset。
 * 这样同一份单元模板可以被多个单元复用，各自解析到不同号段。
 * </p>
 *
 * @param offset     段内偏移，≥ 1
 * @param slot       符号名（如 {@code TEMP_PV}），在单元内唯一
 * @param type       数据类型
 * @param unitSymbol 工程单位（如 {@code C}、{@code Torr}），可为空串
 * @param decimals   显示小数位，≥ 0
 * @param min        值域下界
 * @param max        值域上界
 */
public record SvidDef(int offset, String slot, ValueType type, String unitSymbol, int decimals, double min, double max) {

    /**
     * 校验并创建。
     *
     * @param offset     段内偏移
     * @param slot       符号名
     * @param type       类型
     * @param unitSymbol 单位
     * @param decimals   小数位
     * @param min        下界
     * @param max        上界
     */
    public SvidDef {
        if (offset < 1) {
            throw new IllegalArgumentException("svid offset must be >= 1 but was " + offset);
        }
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("svid slot must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("svid type must not be null: " + slot);
        }
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be >= 0 but was " + decimals);
        }
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " > max " + max + " for svid " + slot);
        }
        unitSymbol = unitSymbol == null ? "" : unitSymbol;
    }

    /**
     * 简化构造：只有编号、名字与类型。
     *
     * @param offset 段内偏移
     * @param slot   符号名
     * @param type   类型
     * @return 定义
     */
    public static SvidDef of(int offset, String slot, ValueType type) {
        return new SvidDef(offset, slot, type, "", 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * 按段起点解析出绝对 ID。
     *
     * @param base 段起点
     * @return 绝对 SVID
     */
    public int resolve(int base) {
        return base + this.offset;
    }
}
