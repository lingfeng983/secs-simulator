package com.shimizukenta.secssim.core.value;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.shimizukenta.secssim.core.curve.Curve;
import com.shimizukenta.secssim.core.curve.Rng;

/**
 * SVID → 曲线的绑定表。
 *
 * <p>每个 SVID 用一个<b>独立派生</b>的噪声源（seed + SVID），因此：
 * 新增或删除一个变量，不会改变其它变量的曲线。这一点在做 A/B 对比和回归时很关键。
 * </p>
 *
 * <p>由于曲线是纯函数，这里不做缓存：{@link #valueAt(int, long)} 直接求值。
 * 需要"某一时刻的整份快照"时用 {@link #snapshot(long)}。
 * </p>
 */
public final class ValueStore {

    private final long seed;
    private final Map<Integer, Binding> bindings = new ConcurrentHashMap<>();

    /**
     * 创建值存储。
     *
     * @param seed 全局种子
     */
    public ValueStore(long seed) {
        this.seed = seed;
    }

    /**
     * 绑定（重复绑定会覆盖）。
     *
     * @param svid  绝对 SVID
     * @param curve 曲线
     */
    public void bind(int svid, Curve curve) {
        this.bindings.put(svid, new Binding(curve, new Rng(this.seed).derive("svid:" + svid)));
    }

    /**
     * 解除绑定。
     *
     * @param svid 绝对 SVID
     * @return 原来是否存在绑定
     */
    public boolean unbind(int svid) {
        return this.bindings.remove(svid) != null;
    }

    /**
     * 是否已绑定。
     *
     * @param svid 绝对 SVID
     * @return 已绑定返回 {@code true}
     */
    public boolean isBound(int svid) {
        return this.bindings.containsKey(svid);
    }

    /**
     * 已绑定的 SVID 集合。
     *
     * @return 不可变集合
     */
    public Set<Integer> boundSvids() {
        return Set.copyOf(this.bindings.keySet());
    }

    /**
     * 求某一时刻的值。
     *
     * @param svid 绝对 SVID
     * @param tMs  时刻（毫秒）
     * @return 有绑定则返回值
     */
    public OptionalDouble valueAt(int svid, long tMs) {
        Binding binding = this.bindings.get(svid);
        if (binding == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(binding.curve().at(tMs, binding.rng()));
    }

    /**
     * 求某一时刻全部绑定的值。
     *
     * @param tMs 时刻（毫秒）
     * @return 不可变快照
     */
    public Map<Integer, Double> snapshot(long tMs) {
        return Map.copyOf(this.bindings.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().curve().at(tMs, e.getValue().rng()))));
    }

    /**
     * 取某 SVID 绑定的曲线。
     *
     * @param svid 绝对 SVID
     * @return 曲线
     */
    public Curve curveOf(int svid) {
        Binding binding = this.bindings.get(svid);
        if (binding == null) {
            throw new IllegalArgumentException("svid not bound: " + svid);
        }
        return binding.curve();
    }

    /**
     * 清空全部绑定。
     */
    public void clear() {
        this.bindings.clear();
    }

    private record Binding(Curve curve, Rng rng) {
    }
}
