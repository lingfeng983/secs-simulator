package com.shimizukenta.secssim.core.curve;

/**
 * 越界策略：值超出声明范围时怎么处理。
 *
 * <p>这是必须显式配置的语义，而不是随便截断：真实设备到顶之后是停住、
 * 反弹还是回卷，直接决定主机侧看到的曲线形状。
 * </p>
 */
public enum BoundPolicy {

    /** 停在边界（最常见于物理量饱和）。 */
    CLAMP,

    /** 反弹（像撞到墙）。 */
    REFLECT,

    /** 回卷（锯齿，像计数器或角度）。 */
    WRAP;

    /**
     * 把值约束到区间。
     *
     * @param value 原始值
     * @param min   下界
     * @param max   上界
     * @return 约束后的值
     */
    public double apply(double value, double min, double max) {
        if (!(min < max)) {
            return min;
        }
        return switch (this) {
            case CLAMP -> Math.min(max, Math.max(min, value));
            case WRAP -> wrap(value, min, max);
            case REFLECT -> reflect(value, min, max);
        };
    }

    private static double wrap(double value, double min, double max) {
        double range = max - min;
        double x = (value - min) % range;
        if (x < 0.0) {
            x += range;
        }
        return min + x;
    }

    private static double reflect(double value, double min, double max) {
        double range = max - min;
        double period = range * 2.0;
        double x = (value - min) % period;
        if (x < 0.0) {
            x += period;
        }
        return x <= range ? min + x : min + (period - x);
    }
}
