package com.shimizukenta.secssim.core.curve;

import java.util.List;

/**
 * 阶跃序列：到点直接跳变（工序步进、状态码）。
 *
 * @param steps 按时间升序排列的台阶
 */
public record StepCurve(List<Step> steps) implements Curve {

    /**
     * 一个台阶。
     *
     * @param fromMs 生效时刻（毫秒，含）
     * @param value  值
     */
    public record Step(long fromMs, double value) {
    }

    /**
     * 校验并创建。
     *
     * @param steps 台阶
     */
    public StepCurve {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("step curve needs at least one step");
        }
        steps = List.copyOf(steps);
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i - 1).fromMs() > steps.get(i).fromMs()) {
                throw new IllegalArgumentException("steps must be sorted by fromMs");
            }
        }
    }

    @Override
    public double at(long tMs, Rng rng) {
        double value = this.steps.getFirst().value();
        for (Step step : this.steps) {
            if (tMs >= step.fromMs()) {
                value = step.value();
            } else {
                break;
            }
        }
        return value;
    }

    @Override
    public double lowerBound() {
        return this.steps.stream().mapToDouble(Step::value).min().orElse(0.0);
    }

    @Override
    public double upperBound() {
        return this.steps.stream().mapToDouble(Step::value).max().orElse(0.0);
    }

    @Override
    public String describe() {
        return "step(" + this.steps.size() + " steps)";
    }
}
