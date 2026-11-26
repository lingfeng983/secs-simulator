package com.shimizukenta.secssim.core.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.domain.ValueType;

/**
 * 值域必须收在取值处。
 *
 * <p>以前 SVID 声明的类型只影响默认曲线：用户写的 Pwl/Step 曲线输出 300 给一个 U1，
 * 会一路走到协议层编码时才抛异常——事件丢失，日志里只有一行不起眼的错误。
 * 这一层之后，越界值在<b>被读到的那一刻</b>就被钳制，下游永远拿不到非法值。</p>
 */
class BoundedValueSourceTest {

    @Test
    void clampsByDeclaredType() {
        ValueSource raw = new ValueSource() {
            @Override
            public OptionalDouble valueAt(int svid, long tMs) {
                return switch (svid) {
                    case 1 -> OptionalDouble.of(300.0);
                    case 2 -> OptionalDouble.of(-200.0);
                    case 3 -> OptionalDouble.of(Double.NaN);
                    case 4 -> OptionalDouble.of(Double.POSITIVE_INFINITY);
                    case 5 -> OptionalDouble.of(0.25);
                    default -> OptionalDouble.empty();
                };
            }

            @Override
            public java.util.Set<Integer> svids() {
                return java.util.Set.of(1, 2, 3, 4, 5);
            }
        };

        Map<Integer, ResolvedSvid> svids = Map.of(
                1, new ResolvedSvid(1, "CH1", "U1_OVER", ValueType.U1, ""),
                2, new ResolvedSvid(2, "CH1", "I1_UNDER", ValueType.I1, ""),
                3, new ResolvedSvid(3, "CH1", "NAN", ValueType.F8, ""),
                4, new ResolvedSvid(4, "CH1", "INF", ValueType.U2, ""),
                5, new ResolvedSvid(5, "CH1", "OK", ValueType.F4, ""));

        BoundedValueSource bounded = new BoundedValueSource(raw, svids);

        assertEquals(255.0, bounded.valueAt(1, 0L).orElseThrow(), 1e-9);
        assertEquals(-128.0, bounded.valueAt(2, 0L).orElseThrow(), 1e-9);
        assertEquals(0.0, bounded.valueAt(3, 0L).orElseThrow(), 1e-9);
        assertEquals(65_535.0, bounded.valueAt(4, 0L).orElseThrow(), 1e-9);
        assertEquals(0.25, bounded.valueAt(5, 0L).orElseThrow(), 1e-9);

        assertEquals(4L, bounded.clampedCount(), "四条越界/非有限值应被计数");
    }

    @Test
    void u8AndI8ClampToLongRange() {
        /* 值管线是 double、编码端是 long：(double) Long.MAX_VALUE 已经等于 2^63，
           直接用它当上界会让钳制后的值仍然无法编码；上界必须是它的前一个可表示值。 */
        double u8Max = Math.nextDown((double) Long.MAX_VALUE);
        double over = 1.0E20;

        ValueSource raw = new ValueSource() {
            @Override
            public OptionalDouble valueAt(int svid, long tMs) {
                return OptionalDouble.of(svid == 1 || svid == 3 ? over : -over);
            }

            @Override
            public java.util.Set<Integer> svids() {
                return java.util.Set.of(1, 2, 3, 4);
            }
        };
        Map<Integer, ResolvedSvid> svids = Map.of(
                1, new ResolvedSvid(1, "CH1", "U8_OVER", ValueType.U8, ""),
                2, new ResolvedSvid(2, "CH1", "U8_UNDER", ValueType.U8, ""),
                3, new ResolvedSvid(3, "CH1", "I8_OVER", ValueType.I8, ""),
                4, new ResolvedSvid(4, "CH1", "I8_UNDER", ValueType.I8, ""));

        BoundedValueSource bounded = new BoundedValueSource(raw, svids);

        assertEquals(u8Max, bounded.valueAt(1, 0L).orElseThrow(), 1e-9, "U8 上界必须是 Long 范围");
        assertEquals(0.0, bounded.valueAt(2, 0L).orElseThrow(), 1e-9, "U8 下界是 0");
        assertEquals(u8Max, bounded.valueAt(3, 0L).orElseThrow(), 1e-9, "I8 上界必须是 Long 范围");
        assertEquals((double) Long.MIN_VALUE, bounded.valueAt(4, 0L).orElseThrow(), 1e-9,
                "I8 下界是 Long.MIN_VALUE");
        assertEquals(4L, bounded.clampedCount());
    }

    @Test
    void f4ClampsToFloatRange() {
        /* F4 是 4 字节浮点：1e100 / ±Inf 必须钳到 ±Float.MAX_VALUE，NaN 归 0，
           不能放行到协议编码层才失败（C1 修复的回归锚点）。 */
        ValueSource raw = new ValueSource() {
            @Override
            public OptionalDouble valueAt(int svid, long tMs) {
                return switch (svid) {
                    case 1 -> OptionalDouble.of(1e100);
                    case 2 -> OptionalDouble.of(-1e100);
                    case 3 -> OptionalDouble.of(Double.NaN);
                    case 4 -> OptionalDouble.of(Double.POSITIVE_INFINITY);
                    case 5 -> OptionalDouble.of(Double.NEGATIVE_INFINITY);
                    case 6 -> OptionalDouble.of((double) Float.MAX_VALUE);
                    default -> OptionalDouble.empty();
                };
            }

            @Override
            public java.util.Set<Integer> svids() {
                return java.util.Set.of(1, 2, 3, 4, 5, 6);
            }
        };
        Map<Integer, ResolvedSvid> svids = Map.of(
                1, new ResolvedSvid(1, "CH1", "F4_OVER", ValueType.F4, ""),
                2, new ResolvedSvid(2, "CH1", "F4_UNDER", ValueType.F4, ""),
                3, new ResolvedSvid(3, "CH1", "F4_NAN", ValueType.F4, ""),
                4, new ResolvedSvid(4, "CH1", "F4_POS_INF", ValueType.F4, ""),
                5, new ResolvedSvid(5, "CH1", "F4_NEG_INF", ValueType.F4, ""),
                6, new ResolvedSvid(6, "CH1", "F4_EDGE", ValueType.F4, ""));

        BoundedValueSource bounded = new BoundedValueSource(raw, svids);

        double f4Max = (double) Float.MAX_VALUE;
        assertEquals(f4Max, bounded.valueAt(1, 0L).orElseThrow(), 0.0, "1e100 必须钳到 Float.MAX_VALUE");
        assertEquals(-f4Max, bounded.valueAt(2, 0L).orElseThrow(), 0.0, "-1e100 必须钳到 -Float.MAX_VALUE");
        assertEquals(0.0, bounded.valueAt(3, 0L).orElseThrow(), 0.0, "NaN 归 0");
        assertEquals(f4Max, bounded.valueAt(4, 0L).orElseThrow(), 0.0, "+Inf 钳到上界");
        assertEquals(-f4Max, bounded.valueAt(5, 0L).orElseThrow(), 0.0, "-Inf 钳到下界");
        assertEquals(f4Max, bounded.valueAt(6, 0L).orElseThrow(), 0.0, "恰在上界的值原样放行");
        assertEquals(5L, bounded.clampedCount(), "五次越界/非有限值应被计数，边界值不计");
    }

    @Test
    void passesThroughUnknownAndNonNumeric() {
        ValueSource raw = new ValueSource() {
            @Override
            public OptionalDouble valueAt(int svid, long tMs) {
                return OptionalDouble.of(1234.0);
            }

            @Override
            public java.util.Set<Integer> svids() {
                return java.util.Set.of(1);
            }
        };
        Map<Integer, ResolvedSvid> svids = Map.of(
                1, new ResolvedSvid(1, "CH1", "TEXT", ValueType.ASCII, ""));

        BoundedValueSource bounded = new BoundedValueSource(raw, svids);

        assertEquals(1234.0, bounded.valueAt(1, 0L).orElseThrow(), 1e-9,
                "非数值类型不做数值钳制");
        assertEquals(1234.0, bounded.valueAt(99, 0L).orElseThrow(), 1e-9,
                "未知 SVID 原样透传");
        assertTrue(bounded.valueAt(0, 0L).isPresent());
    }
}
