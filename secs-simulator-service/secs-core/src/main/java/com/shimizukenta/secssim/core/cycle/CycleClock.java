package com.shimizukenta.secssim.core.cycle;

import com.shimizukenta.secssim.core.curve.Rng;

/**
 * wafer 周期时钟：把连续时间映射成「第几片 + 片内相位」。
 *
 * <p>一个周期就是一片 wafer 的处理过程。周期长度是 {@code periodMs} 加上
 * 每片独立的抖动 {@code ±jitterMs}——抖动由种子派生，因此<b>可复现</b>：
 * 同一份配置、同一个种子，第 7 片的起点永远是同一个时刻。</p>
 *
 * <p>三条边界语义：</p>
 * <ul>
 *   <li>抖动用 {@code rng.derive("cycle:" + i)}，与求值顺序无关；</li>
 *   <li>起点是逐片累加（{@code startOf(i+1) = startOf(i) + durationOf(i)}），
 *       所以抖动会累积成节拍漂移，这正是真实产线的样子；</li>
 *   <li>{@code phase} 落在 {@code [0, duration)}，周期边界处归零——
 *       曲线按相位重放，不需要任何"复位"逻辑。</li>
 * </ul>
 *
 * <p>片数必须是有限值：事件在编译期按片展开（见 {@code CycleConfig}），
 * 无限片会让展开无界。要长时间跑就配一个大一点的 {@code count}。</p>
 */
public final class CycleClock {

    /** 周期长度的下限：太短的周期会让事件挤在同一个 tick 上。 */
    public static final long MIN_PERIOD_MS = 1_000L;

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
    public CycleClock(long periodMs, long jitterMs, int count, long seed) {
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
     * <p>配置里没有 {@code cycle} 段时用它——{@link DataSource.Clock#PHASE}
     * 因此退化成"只跑一次"，旧配置的语义不必额外分支。</p>
     *
     * @return 直通时钟
     */
    public static CycleClock passthrough() {
        return new CycleClock(86_400_000L, 0L, 1, 0L);
    }

    /**
     * 标准周期长度。
     *
     * @return 毫秒
     */
    public long periodMs() {
        return this.periodMs;
    }

    /**
     * 每片长度误差。
     *
     * @return 毫秒
     */
    public long jitterMs() {
        return this.jitterMs;
    }

    /**
     * 片数。
     *
     * @return 片数
     */
    public int count() {
        return this.count;
    }

    /**
     * 第 {@code i} 片的实际长度。
     *
     * @param i 片号（从 0 开始）
     * @return 毫秒
     */
    public long durationOf(int i) {
        if (this.jitterMs == 0L) {
            return this.periodMs;
        }
        double u = this.rng.derive("cycle:" + i).uniform01(0L);
        long delta = Math.round((u * 2.0 - 1.0) * this.jitterMs);
        return Math.max(MIN_PERIOD_MS, this.periodMs + delta);
    }

    /**
     * 第 {@code i} 片的起点。
     *
     * @param i 片号（从 0 开始；{@code i == count} 返回整段结束时刻）
     * @return 毫秒
     */
    public long startOf(int i) {
        if (i <= 0) {
            return 0L;
        }
        long[] table = this.starts();
        return table[Math.min(i, this.count)];
    }

    /**
     * 当前时刻落在第几片。
     *
     * <p>超出最后一片时返回 {@code count - 1}——模拟结束后停在最后一片，
     * 与曲线「非循环停在末段」一致，不会因为时间继续走而报错。</p>
     *
     * @param tMs 时刻
     * @return 片号（从 0 开始）
     */
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

    /**
     * 片内相位：{@code t - startOf(indexAt(t))}。
     *
     * @param tMs 时刻
     * @return 相位（毫秒，从 0 开始）
     */
    public long phaseAt(long tMs) {
        return tMs - this.startOf(this.indexAt(tMs));
    }

    /**
     * 整段结束时刻。
     *
     * @return 毫秒
     */
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
