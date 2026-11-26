package com.shimizukenta.secssim.core.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.template.Expression;
import com.shimizukenta.secssim.core.template.ExpressionException;

/**
 * 唯一求值函数的行为锚点：同一 {@code (source, 时刻)} 永远得到同一结果；
 * 文本型来源无数值语义（0），数值型来源无文本语义（现状为空串，见 P2 说明）。
 */
class SourceEvaluatorTest {

    private static final CycleClock CLOCK = CycleClock.of(10_000L, 0L, 3, 7L);
    private static final Rng RNG = new Rng(1L);

    @Test
    void constAndCurveEvaluateDeterministically() {
        assertEquals(42.5, SourceEvaluator.numeric(new DataSource.Const(42.5), 0L, CLOCK, RNG), 1e-9);
        assertEquals(42.5, SourceEvaluator.numeric(new DataSource.Const(42.5), 99_999L, CLOCK, RNG), 1e-9);

        DataSource.CurveSource phase = new DataSource.CurveSource(
                new ConstantCurve(3.0), DataSource.Clock.PHASE, null);
        assertEquals(3.0, SourceEvaluator.numeric(phase, 12_345L, CLOCK, RNG), 1e-9);

        /* transform 用 V 引用曲线输出，t 是局部秒 */
        DataSource.CurveSource scaled = new DataSource.CurveSource(
                new ConstantCurve(3.0), DataSource.Clock.PHASE, Expression.parse("V * 2 + t"));
        assertEquals(6.0 + 2.345, SourceEvaluator.numeric(scaled, 12_345L, CLOCK, RNG), 1e-9,
                "PHASE 12.345s → 片 1 相位 2.345s");
    }

    @Test
    void exprSourceUsesLocalVarsAndRejectsUnknown() {
        Map<String, Curve> vars = Map.of("base", new ConstantCurve(10.0));
        DataSource.ExprSource expr = new DataSource.ExprSource(
                vars, Expression.parse("base + t"), DataSource.Clock.ABSOLUTE);

        assertEquals(12.0, SourceEvaluator.numeric(expr, 2_000L, CLOCK, RNG), 1e-9);

        DataSource.ExprSource bad = new DataSource.ExprSource(
                Map.of(), Expression.parse("missing + 1"), DataSource.Clock.PHASE);
        assertThrows(ExpressionException.class,
                () -> SourceEvaluator.numeric(bad, 0L, CLOCK, RNG),
                "引用未定义的局部曲线必须显式抛错");

        /* P3：vars 可空 → 空 Map；null 元素拷贝时拒绝 */
        assertEquals(Map.of(), new DataSource.ExprSource(null, Expression.parse("t"),
                DataSource.Clock.PHASE).vars());
    }

    @Test
    void nanAndInfinityPassThroughNumericUnbounded() {
        /* core 求值层不做值域归一（那是 BoundedValueSource 的职责）：
           NaN / Inf 按现状原样穿过，此处锁定分层契约。 */
        assertTrue(Double.isNaN(SourceEvaluator.numeric(new DataSource.Const(Double.NaN), 0L, CLOCK, RNG)));
        assertEquals(Double.POSITIVE_INFINITY,
                SourceEvaluator.numeric(new DataSource.Const(Double.POSITIVE_INFINITY), 0L, CLOCK, RNG));
        assertTrue(Double.isNaN(SourceEvaluator.numeric(
                new DataSource.CurveSource(new ConstantCurve(Double.NaN), DataSource.Clock.PHASE, null),
                0L, CLOCK, RNG)));
    }

    @Test
    void textSourcesHaveNoNumericSemanticsAndViceVersa() {
        DataSource.Text lot = new DataSource.Text("LOT-A");
        assertEquals(0.0, SourceEvaluator.numeric(lot, 0L, CLOCK, RNG), 1e-9, "文本来源数值 = 0");
        assertEquals("LOT-A", SourceEvaluator.text(lot, 0L, CLOCK));

        DataSource.CycleText pattern = new DataSource.CycleText(null, "W{03}", null, 1L, 1L);
        assertEquals("W001", SourceEvaluator.text(pattern, 0L, CLOCK));
        assertEquals("W002", SourceEvaluator.text(pattern, 10_000L, CLOCK));
        assertEquals(0.0, SourceEvaluator.numeric(pattern, 0L, CLOCK, RNG), 1e-9);

        /* P2 现状（不改行为）：数值型 source 走 text() 得空串——
           Context 误配数值 source 会静默编码成空 ASCII，留待 config 层校验拦截。 */
        assertEquals("", SourceEvaluator.text(new DataSource.CurveSource(
                new ConstantCurve(3.0), DataSource.Clock.PHASE, null), 0L, CLOCK));
        assertEquals("", SourceEvaluator.text(new DataSource.CycleNumber(1L, 1L, 1L), 0L, CLOCK));
        assertEquals("7", SourceEvaluator.text(new DataSource.Const(7.0), 0L, CLOCK));
    }

    @Test
    void cycleNumberStepsByWaferIndex() {
        DataSource.CycleNumber step = new DataSource.CycleNumber(100L, 5L, 2L);

        assertEquals(100.0, SourceEvaluator.numeric(step, 0L, CLOCK, RNG), 1e-9);
        assertEquals(100.0, SourceEvaluator.numeric(step, 19_999L, CLOCK, RNG), 1e-9, "每 2 片进一档");
        assertEquals(105.0, SourceEvaluator.numeric(step, 20_000L, CLOCK, RNG), 1e-9);
        /* 超界时刻停在末片（index = count - 1 = 2），仍为第 1 档 */
        assertEquals(105.0, SourceEvaluator.numeric(step, 99_999L, CLOCK, RNG), 1e-9);
    }

    @Test
    void localTimeMapsEachClockKind() {
        assertEquals(2_345L, SourceEvaluator.localTime(DataSource.Clock.PHASE, 12_345L, CLOCK));
        assertEquals(12_345L, SourceEvaluator.localTime(DataSource.Clock.ABSOLUTE, 12_345L, CLOCK));
        assertEquals(1L, SourceEvaluator.localTime(DataSource.Clock.CYCLE, 12_345L, CLOCK));
        /* P2 钳制后：超界 PHASE 不再无界 */
        assertEquals(9_999L, SourceEvaluator.localTime(DataSource.Clock.PHASE, 99_999L, CLOCK));
    }
}