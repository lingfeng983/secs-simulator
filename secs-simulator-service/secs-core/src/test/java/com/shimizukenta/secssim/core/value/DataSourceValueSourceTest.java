package com.shimizukenta.secssim.core.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.curve.ConstantCurve;

/**
 * 编号 → 值定义 的直通来源：未知编号返回 empty、按编号确定性求值、
 * 每片噪声派生不串扰其它编号。
 */
class DataSourceValueSourceTest {

    private static final CycleClock CLOCK = CycleClock.of(10_000L, 0L, 3, 7L);

    @Test
    void knownIdsEvaluateAndUnknownIdsAreEmpty() {
        DataSourceValueSource values = new DataSourceValueSource(
                Map.of(1, new DataSource.Const(42.5),
                        2, new DataSource.CurveSource(new ConstantCurve(3.0), DataSource.Clock.PHASE, null)),
                CLOCK, 11L, false);

        assertEquals(Set.of(1, 2), values.svids());
        assertEquals(42.5, values.valueAt(1, 0L).orElseThrow(), 1e-9);
        assertEquals(3.0, values.valueAt(2, 12_345L).orElseThrow(), 1e-9);
        assertTrue(values.valueAt(99, 0L).isEmpty(), "未定义编号必须返回 empty");
    }

    @Test
    void sameInputGivesSameOutput() {
        DataSourceValueSource values = new DataSourceValueSource(
                Map.of(1, new DataSource.CurveSource(new ConstantCurve(8.0), DataSource.Clock.PHASE, null)),
                CLOCK, 11L, true);

        double first = values.valueAt(1, 5_000L).orElseThrow();
        for (int i = 0; i < 5; i++) {
            assertEquals(first, values.valueAt(1, 5_000L).orElseThrow(), 0.0,
                    "同一 (svid, t) 重复读取必须完全一致");
        }
        /* 另一实例、同种子：结果可复现 */
        DataSourceValueSource twin = new DataSourceValueSource(
                Map.of(1, new DataSource.CurveSource(new ConstantCurve(8.0), DataSource.Clock.PHASE, null)),
                CLOCK, 11L, true);
        assertEquals(first, twin.valueAt(1, 5_000L).orElseThrow(), 0.0);
    }

    @Test
    void cycleNumberFollowsClockAndClampsBeyondEnd() {
        DataSourceValueSource values = new DataSourceValueSource(
                Map.of(7, new DataSource.CycleNumber(0L, 1L, 1L)), CLOCK, 0L, false);

        assertEquals(0.0, values.valueAt(7, 0L).orElseThrow(), 1e-9);
        assertEquals(1.0, values.valueAt(7, 10_000L).orElseThrow(), 1e-9);
        assertEquals(2.0, values.valueAt(7, 20_000L).orElseThrow(), 1e-9);
        assertEquals(2.0, values.valueAt(7, 99_999L).orElseThrow(), 1e-9, "超界停在末片");
        assertEquals(CLOCK, values.clock());
    }
}