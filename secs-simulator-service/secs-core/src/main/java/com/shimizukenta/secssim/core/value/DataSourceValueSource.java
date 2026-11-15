package com.shimizukenta.secssim.core.value;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.curve.Rng;

/**
 * 每个 SVID（或 ECID）各有一份 {@link DataSource} 的值来源（schema v7）。
 *
 * <p>与 v6 的模板图相比，这里没有图、没有拓扑排序、没有实例：一个编号对应一份
 * 定义，评估它不需要看别的编号。代价是跨变量关系只能写进同一个
 * {@link DataSource.ExprSource} 的局部变量里（而那正是"复制即展开"的产物）。</p>
 *
 * <p>噪声按编号派生（{@code svid:<id>}），所以新增/删除一个变量不会扰动其它变量；
 * {@code noisePerWafer} 打开时再叠加片号，让每片的抖动不同但仍可复现。</p>
 */
public final class DataSourceValueSource implements ValueSource {

    private final Map<Integer, DataSource> sources;
    private final CycleClock clock;
    private final Rng root;
    private final boolean noisePerWafer;

    /**
     * 创建。
     *
     * @param sources      编号 → 值定义
     * @param clock        周期时钟
     * @param seed         噪声种子
     * @param noisePerWafer 每片是否用不同噪声
     */
    public DataSourceValueSource(Map<Integer, DataSource> sources, CycleClock clock, long seed,
                                 boolean noisePerWafer) {
        this.sources = Map.copyOf(sources);
        this.clock = clock;
        this.root = new Rng(seed).derive("sources");
        this.noisePerWafer = noisePerWafer;
    }

    @Override
    public OptionalDouble valueAt(int svid, long tMs) {
        DataSource source = this.sources.get(svid);
        if (source == null) {
            return OptionalDouble.empty();
        }
        Rng rng = this.root.derive("svid:" + svid);
        if (this.noisePerWafer) {
            rng = rng.derive("cycle:" + this.clock.indexAt(tMs));
        }
        return OptionalDouble.of(SourceEvaluator.numeric(source, tMs, this.clock, rng));
    }

    @Override
    public Set<Integer> svids() {
        return this.sources.keySet();
    }

    /**
     * 周期时钟（预览与 UI 需要按片分桶）。
     *
     * @return 时钟
     */
    public CycleClock clock() {
        return this.clock;
    }
}
