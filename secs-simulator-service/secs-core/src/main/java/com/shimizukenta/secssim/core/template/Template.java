package com.shimizukenta.secssim.core.template;

import java.util.List;
import java.util.Set;

/**
 * 模板：一次求值产生一组输出。
 *
 * <p>这是"多个 SVID 由一个模板统一生产"的载体。模板可以引用其它模板的输出，
 * 因此它们天然构成一张有向图，由 {@link TemplateGraph} 负责排序与校验。
 * </p>
 */
public interface Template {

    /**
     * 模板标识（用于日志与错误定位）。
     *
     * @return 标识
     */
    String id();

    /**
     * 输出名列表。
     *
     * @return 输出名
     */
    List<String> outputs();

    /**
     * 它依赖的外部输出，形如 {@code 实例.输出}（已去掉 {@code in.} 前缀）。
     *
     * @return 依赖引用
     */
    Set<String> inputRefs();

    /**
     * 求值。
     *
     * @param tMs 时刻
     * @param ctx 上下文
     * @param out 输出
     */
    void evaluate(long tMs, EvalContext ctx, OutputSink out);
}
