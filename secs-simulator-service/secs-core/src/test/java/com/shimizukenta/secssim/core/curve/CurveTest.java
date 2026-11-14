package com.shimizukenta.secssim.core.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CurveTest {

    private static final Rng RNG = new Rng(11L);

    @Test
    void constantIgnoresTime() {
        Curve curve = new ConstantCurve(42.5);
        assertEquals(42.5, curve.at(0L, RNG));
        assertEquals(42.5, curve.at(999_999L, RNG));
    }

    @Test
    void rampHoldsAtEndWhenNotCyclic() {
        Curve curve = RampCurve.of(0.0, 100.0, 10_000L);
        assertEquals(0.0, curve.at(0L, RNG), 1e-9);
        assertEquals(50.0, curve.at(5_000L, RNG), 1e-9);
        assertEquals(100.0, curve.at(10_000L, RNG), 1e-9);
        assertEquals(100.0, curve.at(50_000L, RNG), 1e-9, "不循环时到顶必须停住");
    }

    @Test
    void rampWrapsWhenCyclic() {
        Curve curve = new RampCurve(0.0, 100.0, 10_000L, true);
        assertEquals(50.0, curve.at(5_000L, RNG), 1e-9);
        assertEquals(99.99, curve.at(9_999L, RNG), 1e-9);
        assertEquals(0.0, curve.at(10_000L, RNG), 1e-9, "锯齿在整周期处回到起点");
        assertEquals(50.0, curve.at(15_000L, RNG), 1e-9);
    }

    @Test
    void uniformStaysInRangeAndHoldsWithinBucket() {
        Curve curve = new UniformCurve(10.0, 20.0, 1_000L);
        for (long t = 0L; t < 60_000L; t += 100L) {
            double v = curve.at(t, RNG);
            assertTrue(v >= 10.0 && v <= 20.0, "out of range at t=" + t + ": " + v);
        }
        assertEquals(curve.at(100L, RNG), curve.at(999L, RNG), "同一重采样周期内必须保持");
    }

    @Test
    void uniformChangesAcrossBuckets() {
        Curve curve = new UniformCurve(0.0, 1.0, 1_000L);
        long distinct = java.util.stream.LongStream.range(0L, 20L)
                .mapToObj(b -> curve.at(b * 1_000L, RNG))
                .distinct()
                .count();
        assertTrue(distinct > 15, "跨周期应重新采样，实际只出现 " + distinct + " 个不同值");
    }

    @Test
    void randomWalkObeysBoundsForAllPolicies() {
        for (BoundPolicy policy : BoundPolicy.values()) {
            Curve curve = new RandomWalkCurve(50.0, 5.0, 40.0, 2_000L, 3, 0.0, 100.0, policy);
            for (long t = 0L; t < 300_000L; t += 250L) {
                double v = curve.at(t, RNG);
                assertTrue(v >= 0.0 && v <= 100.0,
                        policy + " 越界 at t=" + t + ": " + v);
            }
        }
    }

    @Test
    void randomWalkDriftsUpWhenDriftPositive() {
        Curve curve = new RandomWalkCurve(0.0, 1.0, 0.5, 2_000L, 2, 0.0, 1_000.0, BoundPolicy.CLAMP);
        double early = mean(curve, 0L, 10_000L);
        double late = mean(curve, 200_000L, 210_000L);
        assertTrue(late > early + 50.0, "正向漂移应有明显上升趋势: " + early + " -> " + late);
    }

    @Test
    void sineHasExpectedShape() {
        Curve curve = new SineCurve(0.0, 100.0, 4_000L, 0L);
        assertEquals(50.0, curve.at(0L, RNG), 1e-9, "起点在中位");
        assertEquals(100.0, curve.at(1_000L, RNG), 1e-9, "四分之一周期到峰");
        assertEquals(0.0, curve.at(3_000L, RNG), 1e-9, "四分之三周期到谷");
    }

    @Test
    void stepJumpsAtBoundaries() {
        Curve curve = new StepCurve(List.of(
                new StepCurve.Step(0L, 1.0),
                new StepCurve.Step(5_000L, 2.0),
                new StepCurve.Step(9_000L, 3.0)));

        assertEquals(1.0, curve.at(0L, RNG));
        assertEquals(1.0, curve.at(4_999L, RNG));
        assertEquals(2.0, curve.at(5_000L, RNG));
        assertEquals(3.0, curve.at(99_999L, RNG));
    }

    @Test
    void pwlInterpolatesLinearly() {
        Curve curve = new PwlCurve(List.of(
                new PwlCurve.Point(0L, 0.0),
                new PwlCurve.Point(10_000L, 100.0),
                new PwlCurve.Point(20_000L, 100.0),
                new PwlCurve.Point(30_000L, 0.0)));

        assertEquals(0.0, curve.at(0L, RNG), 1e-9);
        assertEquals(50.0, curve.at(5_000L, RNG), 1e-9);
        assertEquals(100.0, curve.at(15_000L, RNG), 1e-9);
        assertEquals(0.0, curve.at(99_999L, RNG), 1e-9, "序列末端外应保持末值");
    }

    @Test
    void boundPoliciesBehaveDifferently() {
        assertEquals(10.0, BoundPolicy.CLAMP.apply(25.0, 0.0, 10.0), 1e-9);
        assertEquals(5.0, BoundPolicy.REFLECT.apply(15.0, 0.0, 10.0), 1e-9);
        assertEquals(5.0, BoundPolicy.WRAP.apply(15.0, 0.0, 10.0), 1e-9);
        assertEquals(5.0, BoundPolicy.REFLECT.apply(-5.0, 0.0, 10.0), 1e-9);
        assertEquals(5.0, BoundPolicy.WRAP.apply(-5.0, 0.0, 10.0), 1e-9);
    }

    private static double mean(Curve curve, long fromMs, long toMs) {
        double sum = 0.0;
        long n = 0L;
        for (long t = fromMs; t <= toMs; t += 100L) {
            sum += curve.at(t, RNG);
            n++;
        }
        return sum / n;
    }
}
