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
