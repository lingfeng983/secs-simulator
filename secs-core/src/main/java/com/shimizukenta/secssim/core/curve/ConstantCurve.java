package com.shimizukenta.secssim.core.curve;

/**
 * 固定值。
 *
 * @param value 值
 */
public record ConstantCurve(double value) implements Curve {

    @Override
    public double at(long tMs, Rng rng) {
        return this.value;
    }

    @Override
    public double lowerBound() {
        return this.value;
    }

    @Override
    public double upperBound() {
        return this.value;
    }

    @Override
    public String describe() {
        return "constant(" + this.value + ")";
    }
}
