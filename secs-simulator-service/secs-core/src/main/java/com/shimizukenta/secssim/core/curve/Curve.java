package com.shimizukenta.secssim.core.curve;

/**
 * 一条曲线：给定时刻返回一个数值。
 *
 * <p>实现必须是纯函数（见包注释）。{@link #lowerBound()} 与 {@link #upperBound()}
 * 用于 UI 定坐标轴、以及运行期越界告警，不参与求值。
 * </p>
 */
public sealed interface Curve
        permits ConstantCurve, RampCurve, UniformCurve, RandomWalkCurve, SineCurve, StepCurve, PwlCurve {

    /**
     * 求值。
     *
     * @param tMs 时刻（毫秒，自仿真起点）
     * @param rng 该变量的确定性噪声源
     * @return 数值
     */
    double at(long tMs, Rng rng);

    /**
     * 值域下界。
     *
     * @return 下界
     */
    double lowerBound();

    /**
     * 值域上界。
     *
     * @return 上界
     */
    double upperBound();

    /**
     * 人类可读描述，用于日志与 UI。
     *
     * @return 描述
     */
    String describe();
}
