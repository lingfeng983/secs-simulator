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

    /**
     * 最近一次求值的快照。
     *
     * <p>{@code atMs} 与 {@code values} 必须<b>原子</b>一起发布：若拆成两个字段分别写，
     * 并发求值时会把一个时刻算出的值与另一个时刻的时间戳配在一起，导致某时刻返回错值。
     * 因此整体作为一个不可变记录、经单个 volatile 引用发布。
     * </p>
     */
    private volatile Snapshot snapshot = new Snapshot(Long.MIN_VALUE, Map.of());

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
     * <p>只保留<b>最近一个时刻</b>的缓存。并发调用是安全的：即便多个线程同时判为未命中，
     * 各自也会算出并发布一个自洽的 {@code (atMs, values)} 快照，绝不会返回与所请求时刻
     * 不符的值；曲线是纯函数，重复计算的代价远低于加锁的代价。
     * </p>
     *
     * @param tMs 时刻
     * @return SVID → 值（与 {@code tMs} 对应）
     */
    public Map<Integer, Double> snapshot(long tMs) {
        Snapshot current = this.snapshot;
        if (current.atMs() != tMs) {
            current = new Snapshot(tMs, this.graph.evaluate(tMs, this.rng));
            this.snapshot = current;
        }
        return current.values();
    }

    /**
     * 底层模板图。
     *
     * @return 图
     */
    public TemplateGraph graph() {
        return this.graph;
    }

    /**
     * 一次求值快照：时刻与对应取值原子绑定。
     *
     * @param atMs   快照时刻
     * @param values 该时刻的 SVID → 值（不可变）
     */
    private record Snapshot(long atMs, Map<Integer, Double> values) {
    }
}
