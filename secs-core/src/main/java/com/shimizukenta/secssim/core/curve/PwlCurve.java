package com.shimizukenta.secssim.core.curve;

import java.util.List;

/**
 * 关键帧折线：任意"先升后稳再降"都能用几个点表达。
 *
 * <p>这是最通用的底座——工艺配方、验收曲线、客户给的实测数据，
 * 基本都能拆成一组 (时间, 值) 关键帧。
 * </p>
 *
 * @param points 按时间升序排列的关键帧
 */
public record PwlCurve(List<Point> points) implements Curve {

    /**
     * 一个关键帧。
     *
     * @param tMs   时刻（毫秒）
     * @param value 值
     */
    public record Point(long tMs, double value) {
    }

    /**
     * 校验并创建。
     *
     * @param points 关键帧
     */
    public PwlCurve {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("pwl curve needs at least two points");
        }
        points = List.copyOf(points);
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1).tMs() >= points.get(i).tMs()) {
                throw new IllegalArgumentException("points must be strictly increasing in tMs");
            }
        }
    }

    @Override
    public double at(long tMs, Rng rng) {
        Point first = this.points.getFirst();
        Point last = this.points.getLast();

        if (tMs <= first.tMs()) {
            return first.value();
        }
        if (tMs >= last.tMs()) {
            return last.value();
        }

        for (int i = 1; i < this.points.size(); i++) {
            Point right = this.points.get(i);
            if (tMs <= right.tMs()) {
                Point left = this.points.get(i - 1);
                double span = right.tMs() - left.tMs();
                double k = (tMs - left.tMs()) / span;
                return left.value() + (right.value() - left.value()) * k;
            }
        }

        return last.value();
    }

    @Override
    public double lowerBound() {
        return this.points.stream().mapToDouble(Point::value).min().orElse(0.0);
    }

    @Override
    public double upperBound() {
        return this.points.stream().mapToDouble(Point::value).max().orElse(0.0);
    }

    @Override
    public String describe() {
        return "pwl(" + this.points.size() + " points)";
    }
}
