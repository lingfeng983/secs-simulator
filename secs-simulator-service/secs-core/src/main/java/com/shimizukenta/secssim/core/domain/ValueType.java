package com.shimizukenta.secssim.core.domain;

/**
 * SVID / 报告值的数据类型。
 *
 * <p>这里刻意不复用协议层的 {@code Secs2Item}：核心层不认识 SECS-II 编码，
 * 由上层适配器负责把 {@link ValueType} 映射成具体的 SECS-II 数据项。
 * </p>
 */
public enum ValueType {

    /** 1 字节无符号整型。 */
    U1,
    /** 2 字节无符号整型。 */
    U2,
    /** 4 字节无符号整型。 */
    U4,
    /** 8 字节无符号整型。 */
    U8,
    /** 1 字节有符号整型。 */
    I1,
    /** 2 字节有符号整型。 */
    I2,
    /** 4 字节有符号整型。 */
    I4,
    /** 8 字节有符号整型。 */
    I8,
    /** 4 字节浮点。 */
    F4,
    /** 8 字节浮点。 */
    F8,
    /** ASCII 字符串。 */
    ASCII,
    /** 布尔。 */
    BOOLEAN,
    /** 二进制块。 */
    BINARY;

    /**
     * 是否为数值类型。
     *
     * @return 数值类型返回 {@code true}
     */
    public boolean isNumeric() {
        return switch (this) {
            case U1, U2, U4, U8, I1, I2, I4, I8, F4, F8 -> true;
            case ASCII, BOOLEAN, BINARY -> false;
        };
    }
}
