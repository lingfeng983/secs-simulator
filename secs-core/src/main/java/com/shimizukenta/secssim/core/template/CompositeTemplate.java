package com.shimizukenta.secssim.core.template;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组合模板：每个输出由一个表达式定义，可引用参数与其它模板的输出。
 *
 * <p>典型用法：</p>
 * <pre>
 *   new CompositeTemplate("thermal", Map.of(
 *       "TOTAL", Expression.parse("in.ch1.PV + in.ch1.PRESS"),
 *       "DELTA", Expression.parse("in.ch1.PV - p.target")));
 * </pre>
 *
 * <p>它是纯函数：同一时刻、同一输入，永远得到同一个结果，因此可以乱序求值、
 * 可以回放、可以在预览里加速跑完整条时间线。
 * </p>
 */
public final class CompositeTemplate implements Template {

    private final String id;
    private final Map<String, Expression> outputs;
    private final Set<String> inputRefs;

    /**
     * 创建组合模板。
     *
     * @param id      标识
     * @param outputs 输出名 → 表达式
     */
    public CompositeTemplate(String id, Map<String, Expression> outputs) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("template id must not be blank");
        }
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("composite template needs at least one output: " + id);
        }

        this.id = id;
        this.outputs = Map.copyOf(new LinkedHashMap<>(outputs));

        Set<String> refs = new LinkedHashSet<>();
        for (Expression expression : this.outputs.values()) {
            for (String variable : expression.variables()) {
                if (variable.startsWith("in.")) {
                    refs.add(variable.substring("in.".length()));
                }
            }
        }
        this.inputRefs = Set.copyOf(refs);
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public List<String> outputs() {
        return List.copyOf(this.outputs.keySet());
    }

    @Override
    public Set<String> inputRefs() {
        return this.inputRefs;
    }

    @Override
    public void evaluate(long tMs, EvalContext ctx, OutputSink out) {
        Expression.Env env = name -> switch (variableKind(name)) {
            case "t" -> ctx.timeSec();
            case "param" -> ctx.param(name.substring("p.".length()));
            case "input" -> ctx.input(name.substring("in.".length()));
            default -> throw new ExpressionException("未知变量: " + name + "（模板 " + this.id + "）");
        };

        for (Map.Entry<String, Expression> entry : this.outputs.entrySet()) {
            out.put(entry.getKey(), entry.getValue().evaluate(env));
        }
    }

    private static String variableKind(String name) {
        if ("t".equals(name)) {
            return "t";
        }
        if (name.startsWith("p.")) {
            return "param";
        }
        if (name.startsWith("in.")) {
            return "input";
        }
        return "unknown";
    }
}
