package com.shimizukenta.secssim.core.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExpressionTest {

    private static final Expression.Env EMPTY = name -> {
        throw new ExpressionException("未定义变量: " + name);
    };

    private static double eval(String source) {
        return Expression.parse(source).evaluate(EMPTY);
    }

    @Test
    void arithmeticFollowsPrecedence() {
        assertEquals(7.0, eval("1 + 2 * 3"), 1e-9);
        assertEquals(9.0, eval("(1 + 2) * 3"), 1e-9);
        assertEquals(2.0, eval("-3 + 5"), 1e-9);
        assertEquals(2.5, eval("5 / 2"), 1e-9);
        assertEquals(1.0, eval("1 - -1 + -1"), 1e-9);
    }

    @Test
    void builtinFunctionsWork() {
        assertEquals(10.0, eval("clamp(15, 0, 10)"), 1e-9);
        assertEquals(0.0, eval("clamp(-5, 0, 10)"), 1e-9);
        assertEquals(25.0, eval("lerp(0, 100, 0.25)"), 1e-9);
        assertEquals(2.0, eval("min(3, 2, 8)"), 1e-9);
        assertEquals(8.0, eval("max(3, 2, 8)"), 1e-9);
        assertEquals(6.0, eval("sum(1, 2, 3)"), 1e-9);
        assertEquals(2.0, eval("avg(1, 2, 3)"), 1e-9);
        assertEquals(5.0, eval("select(1, 5, 9)"), 1e-9);
        assertEquals(9.0, eval("select(0, 5, 9)"), 1e-9);
        assertEquals(3.0, eval("abs(-3)"), 1e-9);
        assertEquals(1.25, eval("roundTo(1.24, 0.25)"), 1e-9);
        assertEquals(0.0, eval("floor(0.9)"), 1e-9);
        assertEquals(1.0, eval("ceil(0.1)"), 1e-9);
    }

    @Test
    void variablesAreResolvedFromEnv() {
        Expression.Env env = name -> switch (name) {
            case "t" -> 2.0;
            case "p.gain" -> 3.0;
            case "in.ch1.PV" -> 10.0;
            default -> throw new ExpressionException(name);
        };

        assertEquals(30.0, Expression.parse("in.ch1.PV * p.gain").evaluate(env), 1e-9);
        assertEquals(20.0, Expression.parse("in.ch1.PV * t").evaluate(env), 1e-9);
    }

    @Test
    void variablesAreCollected() {
        var vars = Expression.parse("in.ch1.PV * p.gain + t").variables();
        assertTrue(vars.contains("in.ch1.PV"));
        assertTrue(vars.contains("p.gain"));
        assertTrue(vars.contains("t"));
        assertEquals(3, vars.size());
    }

    @Test
    void malformedExpressionsAreRejected() {
        assertThrows(ExpressionException.class, () -> Expression.parse(""));
        assertThrows(ExpressionException.class, () -> Expression.parse("1 +"));
        assertThrows(ExpressionException.class, () -> Expression.parse("(1 + 2"));
        assertThrows(ExpressionException.class, () -> Expression.parse("1 2"));
        assertThrows(ExpressionException.class, () -> Expression.parse("clamp(1, 2)"));
        assertThrows(ExpressionException.class, () -> Expression.parse("nope(1)"));
        assertThrows(ExpressionException.class, () -> Expression.parse("1 & 2"));
    }

    @Test
    void unknownVariableFailsAtEvaluation() {
        Expression expression = Expression.parse("p.missing + 1");
        assertThrows(ExpressionException.class, () -> expression.evaluate(EMPTY));
    }
}
