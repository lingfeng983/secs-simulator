package com.shimizukenta.secssim.core.curve;

/**
 * 正弦波：温度摆动、压力脉动一类周期性过程。
 *
 * @param min      下界（波谷）
 * @param max      上界（波峰）
 * @param periodMs 周期（毫秒）
 * @param phaseMs  相位偏移（毫秒）
 */
public record SineCurve(double min, double max, long periodMs, long phaseMs) implements Curve {

    /**
     * 校验并创建。
     *
     * @param min      下界
     * @param max      上界
     * @param periodMs 周期
     * @param phaseMs  相位
     */
    public SineCurve {
        if (!(min <= max)) {
            throw new IllegalArgumentException("min " + min + " > max " + max);
        }
        if (periodMs < 1L) {
            throw new IllegalArgumentException("periodMs must be >= 1 but was " + periodMs);
        }
    }

    @Override
    public double at(long tMs, Rng rng) {
        double mid = (this.min + this.max) / 2.0;
        double amplitude = (this.max - this.min) / 2.0;
        double angle = 2.0 * Math.PI * ((tMs + this.phaseMs) % this.periodMs) / this.periodMs;
        return mid + amplitude * Math.sin(angle);
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
        return "sine(" + this.min + ".." + this.max + ", period=" + this.periodMs + "ms)";
    }
}
