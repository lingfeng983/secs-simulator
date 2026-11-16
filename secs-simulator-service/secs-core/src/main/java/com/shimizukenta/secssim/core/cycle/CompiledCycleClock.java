package com.shimizukenta.secssim.core.cycle;

import com.shimizukenta.secssim.core.curve.Rng;

/**
 * 编译期周期时钟：片表由种子确定，时间轴从 0 起算。
 *
 * <p>原来的 {@code CycleClock} 实现整体搬到这里；3.3 起对外只暴露
 * {@link CycleClock} 接口，运行态由 {@link LifecycleCycleClock} 装饰。</p>
 */
public final class CompiledCycleClock implements CycleClock {

    private final long periodMs;
    private final long jitterMs;
    private final int count;
    private final Rng rng;

    /** {@code starts[i]} = 第 i 片起点；{@code starts[count]} = 整段结束时刻。惰性构建。 */
    private volatile long[] starts;

    /**
     * 创建。
     *
     * @param periodMs 标准周期长度（毫秒）
     * @param jitterMs 每片长度误差（±，毫秒），0 表示不抖
     * @param count    片数（≥1）
     * @param seed     种子：抖动的确定性来源
     */
    public CompiledCycleClock(long periodMs, long jitterMs, int count, long seed) {
        if (periodMs < MIN_PERIOD_MS) {
            throw new IllegalArgumentException("periodMs must be >= " + MIN_PERIOD_MS + " but was " + periodMs);
        }
        if (jitterMs < 0L) {
            throw new IllegalArgumentException("jitterMs must be >= 0 but was " + jitterMs);
        }
        if (jitterMs >= periodMs) {
            throw new IllegalArgumentException("jitterMs must be < periodMs but was " + jitterMs);
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1 but was " + count);
        }
        this.periodMs = periodMs;
        this.jitterMs = jitterMs;
        this.count = count;
        this.rng = new Rng(seed);
    }

    /**
     * 单周期直通时钟：相位恒等于绝对时刻。
     *
     * @return 直通时钟
     */
    public static CompiledCycleClock passthrough() {
        return new CompiledCycleClock(86_400_000L, 0L, 1, 0L);
    }

    @Override
    public long periodMs() {
        return this.periodMs;
    }

    @Override
    public long jitterMs() {
        return this.jitterMs;
    }

    @Override
    public int count() {
        return this.count;
    }

    @Override
    public long durationOf(int i) {
        if (this.jitterMs == 0L) {
            return this.periodMs;
        }
        double u = this.rng.derive("cycle:" + i).uniform01(0L);
        long delta = Math.round((u * 2.0 - 1.0) * this.jitterMs);
        return Math.max(MIN_PERIOD_MS, this.periodMs + delta);
    }

    @Override
    public long startOf(int i) {
        if (i <= 0) {
            return 0L;
        }
        long[] table = this.starts();
        return table[Math.min(i, this.count)];
    }

    @Override
    public int indexAt(long tMs) {
        long[] table = this.starts();
        int lo = 0;
        int hi = this.count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (table[mid] <= tMs) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    @Override
    public long phaseAt(long tMs) {
        return tMs - this.startOf(this.indexAt(tMs));
    }

    /** ABSOLUTE 曲线无生命周期锚：就是绝对时刻本身。 */
    @Override
    public long elapsedAt(long tMs) {
        return tMs;
    }

    @Override
    public long endMs() {
        return this.starts()[this.count];
    }

    /** 惰性构建前缀表；并发下允许重复构建（结果相同），不额外加锁。 */
    private long[] starts() {
        long[] table = this.starts;
        if (table != null) {
            return table;
        }
        table = new long[this.count + 1];
        for (int i = 0; i < this.count; i++) {
            table[i + 1] = table[i] + this.durationOf(i);
        }
        this.starts = table;
        return table;
    }
}
