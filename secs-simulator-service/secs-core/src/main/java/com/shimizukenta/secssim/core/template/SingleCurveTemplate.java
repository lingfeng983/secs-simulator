package com.shimizukenta.secssim.core.template;

import java.util.List;
import java.util.Set;

import com.shimizukenta.secssim.core.curve.Curve;

/**
 * 单输出模板：把一条曲线包装成模板，输出名固定为 {@code V}。
 *
 * <p>有了它，"一个 SVID 一条曲线"就退化成了 DAG 里的一个特例，
 * 上层不需要为两种形态写两套代码。
 * </p>
 *
 * @param id    模板标识
 * @param curve 曲线
 */
public record SingleCurveTemplate(String id, Curve curve) implements Template {

    /**
     * 输出名。
     */
    public static final String OUTPUT = "V";

    @Override
    public List<String> outputs() {
        return List.of(OUTPUT);
    }

    @Override
    public Set<String> inputRefs() {
        return Set.of();
    }

    @Override
    public void evaluate(long tMs, EvalContext ctx, OutputSink out) {
        out.put(OUTPUT, this.curve.at(tMs, ctx.rng()));
    }
}
