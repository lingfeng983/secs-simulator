package com.shimizukenta.secssim.core.value;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.template.TemplateGraph;

/**
 * 用模板 DAG 作为值来源。
 *
 * <p>求值会算出图里全部输出，因此对同一时刻的多个 SVID 只算一次
 * （一次 S1F3 往往问好几个变量）。缓存只保留<b>最近一个时刻</b>，
 * 不做无界增长：曲线是纯函数，重算的代价远低于内存泄漏的代价。
 * </p>
 */
public final class GraphValueSource implements ValueSource {

    private final TemplateGraph graph;
    private final Rng rng;

    private volatile long cachedAtMs = Long.MIN_VALUE;
    private volatile Map<Integer, Double> cached = Map.of();

    /**
     * 创建值来源。
     *
     * @param graph 模板图
     * @param rng   根噪声源
     */
    public GraphValueSource(TemplateGraph graph, Rng rng) {
        this.graph = graph;
        this.rng = rng;
    }

    @Override
    public OptionalDouble valueAt(int svid, long tMs) {
        Double value = this.snapshot(tMs).get(svid);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    @Override
    public Set<Integer> svids() {
        return this.graph.ordered().stream()
                .flatMap(instance -> instance.svidByOutput().values().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 取某一时刻的全部输出。
     *
     * @param tMs 时刻
     * @return SVID → 值
     */
    public Map<Integer, Double> snapshot(long tMs) {
        if (tMs != this.cachedAtMs) {
            this.cached = this.graph.evaluate(tMs, this.rng);
            this.cachedAtMs = tMs;
        }
        return this.cached;
    }

    /**
     * 底层模板图。
     *
     * @return 图
     */
    public TemplateGraph graph() {
        return this.graph;
    }
}
