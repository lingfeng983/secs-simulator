package com.shimizukenta.secssim.core.curve;

/**
 * 区间内随机：每隔 {@code resampleMs} 取一个新的随机值，期间保持不变。
 *
 * <p>"范围内随机"若每个 tick 都重采样，看起来是一团毛刺；
 * 真实设备的随机抖动通常有一个采样周期，所以这里必须显式给周期。
 * </p>
 *
 * @param min        下界
 * @param max        上界
 * @param resampleMs 重采样周期（毫秒）
 */
public record UniformCurve(double min, double max, long resampleMs) implements Curve {

    /**
     * 校验并创建。
     *
     * @param min        下界
     * @param max        上界
     * @param resampleMs 重采样周期
     */
    public UniformCurve {
        if (!(min <= max)) {
            throw new IllegalArgumentException("min " + min + " > max " + max);
        }
        if (resampleMs < 1L) {
            throw new IllegalArgumentException("resampleMs must be >= 1 but was " + resampleMs);
        }
    }

    @Override
    public double at(long tMs, Rng rng) {
        long bucket = Math.floorDiv(Math.max(0L, tMs), this.resampleMs);
        return this.min + (this.max - this.min) * rng.uniform01(bucket);
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
        return "uniform(" + this.min + ".." + this.max + " every " + this.resampleMs + "ms)";
    }
}
