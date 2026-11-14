package com.shimizukenta.secssim.core.curve;

/**
 * 带漂移的平滑随机（"持续随机上升 / 下降"）。
 *
 * <p>三部分组成：起始值、线性漂移、分形噪声。漂移决定趋势方向，
 * 噪声决定抖动。因为噪声是时间的纯函数，改变 tick 频率不会改变曲线形状——
 * 这一点与"每 tick 累加一个随机增量"的做法有本质区别（后者不幂等、不可回放）。
 * </p>
 *
 * @param start        起点值
 * @param driftPerSec  每秒漂移量（正=上升，负=下降）
 * @param sigma        噪声幅度
 * @param gridMs       噪声基频网格（毫秒），越大越平缓
 * @param octaves      噪声倍频层数
 * @param min          下界
 * @param max          上界
 * @param policy       越界策略
 */
public record RandomWalkCurve(double start, double driftPerSec, double sigma,
                              long gridMs, int octaves, double min, double max,
                              BoundPolicy policy) implements Curve {

    /**
     * 校验并创建。
     *
     * @param start       起点
     * @param driftPerSec 每秒漂移
     * @param sigma       噪声幅度
     * @param gridMs      噪声网格
     * @param octaves     倍频层数
     * @param min         下界
     * @param max         上界
     * @param policy      越界策略
     */
    public RandomWalkCurve {
        if (!(min < max)) {
            throw new IllegalArgumentException("min " + min + " must be < max " + max);
        }
        if (sigma < 0.0) {
            throw new IllegalArgumentException("sigma must be >= 0 but was " + sigma);
        }
        if (gridMs < 1L) {
            throw new IllegalArgumentException("gridMs must be >= 1 but was " + gridMs);
        }
        if (octaves < 1) {
            throw new IllegalArgumentException("octaves must be >= 1 but was " + octaves);
        }
    }

    @Override
    public double at(long tMs, Rng rng) {
        long t = Math.max(0L, tMs);
        double drift = this.driftPerSec * (t / 1000.0);
        double noise = this.sigma * rng.fbm(t, this.gridMs, this.octaves);
        return this.policy.apply(this.start + drift + noise, this.min, this.max);
    }

    @Override
    public double lowerBound() {
        return this.min;
    }

    @Override
    public double upperBound() {
        return this.max;
    }

    @Override
    public String describe() {
        return "walk(start=" + this.start + ", drift=" + this.driftPerSec + "/s, sigma=" + this.sigma
                + ", range=" + this.min + ".." + this.max + ", " + this.policy + ")";
    }
}
