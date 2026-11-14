package com.shimizukenta.secssim.core.curve;

/**
 * 线性斜坡：从 {@code from} 匀速走到 {@code to}。
 *
 * <p>{@code cyclic=false} 时到顶后停住（"持续上升"的常见形态）；
 * {@code cyclic=true} 时循环回起点，形成锯齿。
 * </p>
 *
 * @param from       起点值
 * @param to         终点值
 * @param durationMs 走完所需时间（毫秒）
 * @param cyclic     到顶后是否回卷
 */
public record RampCurve(double from, double to, long durationMs, boolean cyclic) implements Curve {

    /**
     * 校验并创建。
     *
     * @param from       起点
     * @param to         终点
     * @param durationMs 时长
     * @param cyclic     是否循环
     */
    public RampCurve {
        if (durationMs < 1L) {
            throw new IllegalArgumentException("durationMs must be >= 1 but was " + durationMs);
        }
    }

    /**
     * 创建不循环的斜坡。
     *
     * @param from       起点
     * @param to         终点
     * @param durationMs 时长
     * @return 曲线
     */
    public static RampCurve of(double from, double to, long durationMs) {
        return new RampCurve(from, to, durationMs, false);
    }

    @Override
    public double at(long tMs, Rng rng) {
        long t = Math.max(0L, tMs);
        double progress = t / (double) this.durationMs;

        if (this.cyclic) {
            progress = progress - Math.floor(progress);
        } else if (progress >= 1.0) {
            progress = 1.0;
        }

        return this.from + (this.to - this.from) * progress;
    }

    @Override
    public double lowerBound() {
        return Math.min(this.from, this.to);
    }

    @Override
    public double upperBound() {
        return Math.max(this.from, this.to);
    }

    @Override
    public String describe() {
        return "ramp(" + this.from + "->" + this.to + " in " + this.durationMs + "ms"
                + (this.cyclic ? ", cyclic" : "") + ")";
    }
}
