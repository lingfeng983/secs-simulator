package com.shimizukenta.secssim.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.value.DataSource;

/**
 * 参数模板体（v7）：只有一段 source，没有曲线/组合/实例/result 这一层结构。
 */
class ParamTemplateBodyTest {

    private static final String SINE =
            "{\"type\":\"sine\",\"min\":0,\"max\":10,\"periodMs\":1000}";

    @Test
    void curveBodyParsesToCurveSource() {
        DataSource source = ParamTemplateBody.parse(
                "{\"source\":{\"mode\":\"curve\",\"curve\":" + SINE + "}}");

        DataSource.CurveSource curve = assertInstanceOf(DataSource.CurveSource.class, source);
        assertEquals(DataSource.Clock.PHASE, curve.clock(), "缺省按片内相位重放");
    }

    @Test
    void constBodyParsesToConst() {
        DataSource source = ParamTemplateBody.parse("{\"source\":{\"mode\":\"const\",\"value\":180}}");
        assertEquals(180.0, assertInstanceOf(DataSource.Const.class, source).value());
    }

    @Test
    void exprBodyKeepsPrivateVars() {
        String text = """
                {"source": {"mode": "expr", "clock": "phase",
                  "vars": {"a": {"type": "uniform", "min": 0, "max": 10, "resampleMs": 1000},
                           "b": {"type": "constant", "value": 2}},
                  "expr": "a + b"}}""";

        DataSource.ExprSource source = assertInstanceOf(DataSource.ExprSource.class,
                ParamTemplateBody.parse(text));
        assertEquals(java.util.Set.of("a", "b"), source.vars().keySet());
        assertEquals("a + b", source.expr().source());
    }

    @Test
    void cycleBodyIsPerWafer() {
        DataSource source = ParamTemplateBody.parse(
                "{\"source\":{\"mode\":\"cycle\",\"start\":1,\"step\":1,\"every\":25}}");
        DataSource.CycleNumber step = assertInstanceOf(DataSource.CycleNumber.class, source);
        assertEquals(1.0, step.valueAt(0));
        assertEquals(2.0, step.valueAt(25), "每 25 片进一档");
    }

    @Test
    void normalizeRewritesOnlySource() {
        String normalized = ParamTemplateBody.normalize(
                "{\"source\":{\"mode\":\"curve\",\"curve\":" + SINE + "}}");

        assertTrue(normalized.contains("\"source\""), normalized);
        assertTrue(normalized.contains("\"sine\""), normalized);
        assertFalse(normalized.contains("\"instances\""), "v7 没有实例这个概念: " + normalized);
        assertFalse(normalized.contains("\"result\""), "v7 没有 result: " + normalized);
    }

    @Test
    void missingSourceIsRejected() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ParamTemplateBody.normalize("{\"curve\":" + SINE + "}"));
        assertTrue(e.getMessage().contains("source"), e.getMessage());
    }

    @Test
    void badJsonIsRejected() {
        assertThrows(ConfigException.class, () -> ParamTemplateBody.normalize("not json"));
    }
}
