package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secssim.core.domain.ValueType;

/**
 * 把核心层的 {@link ValueType} 与数值映射成 SECS-II 数据项。
 *
 * <p>类型必须与 SVID 定义一致：主机是按声明类型解析的，类型错了不会报错，
 * 只会解析出垃圾值——所以这里用穷尽 switch，新增类型时编译期就会提醒。
 * </p>
 */
public final class Secs2Mapper {

    private Secs2Mapper() {
    }

    /**
     * 映射成 SECS-II 数据项。
     *
     * @param type  类型
     * @param value 数值
     * @return SECS-II 数据项
     */
    public static Secs2 of(ValueType type, double value) {
        Secs2Builder b = Secs2.getBuilder();

        return switch (type) {
            case U1 -> b.uint1((long) value);
            case U2 -> b.uint2((long) value);
            case U4 -> b.uint4((long) value);
            case U8 -> b.uint8((long) value);
            case I1 -> b.int1((long) value);
            case I2 -> b.int2((long) value);
            case I4 -> b.int4((long) value);
            case I8 -> b.int8((long) value);
            case F4 -> b.float4((float) value);
            case F8 -> b.float8(value);
            case ASCII -> b.ascii(format(value));
            case BOOLEAN -> b.bool(value != 0.0);
            case BINARY -> b.binary((byte) ((int) value));
        };
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
