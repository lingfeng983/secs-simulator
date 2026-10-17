package com.shimizukenta.secssim.core.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.curve.RampCurve;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.template.ParameterLayers;
import com.shimizukenta.secssim.core.template.SingleCurveTemplate;
import com.shimizukenta.secssim.core.template.TemplateGraph;
import com.shimizukenta.secssim.core.template.TemplateGraph.Instance;

/**
 * {@link GraphValueSource} 的缓存正确性回归。
 *
 * <p>重点是并发下的"所请求时刻 ↔ 返回值"必须自洽：历史上 (cached, cachedAtMs)
 * 两个 volatile 字段非原子更新，会把一个时刻算出的值与另一个时刻的时间戳配在一起。
 * </p>
 */
class GraphValueSourceTest {

    private static final long SEED = 1L;
    private static final int SVID = 1001;

    private static TemplateGraph rampGraph() {
        return TemplateGraph.builder()
                .add(new Instance("ramp",
                        new SingleCurveTemplate("ramp", RampCurve.of(0.0, 100.0, 10_000L)),
                        Map.of(SingleCurveTemplate.OUTPUT, SVID), ParameterLayers.empty()))
                .build();
    }

    private static double expected(TemplateGraph graph, long t) {
        return graph.evaluate(t, new Rng(SEED)).get(SVID);
    }

    @Test
    void snapshotMatchesRequestedTimeSequentially() {
        TemplateGraph graph = rampGraph();
        GraphValueSource source = new GraphValueSource(graph, new Rng(SEED));

        for (long t : new long[] {0L, 137L, 1_000L, 4_321L, 9_999L, 10_000L}) {
            assertEquals(expected(graph, t), source.snapshot(t).get(SVID), 1e-9, "t=" + t);
        }
        assertNotEquals(source.snapshot(0L).get(SVID), source.snapshot(10_000L).get(SVID), 1e-9,
                "不同时刻必须给出不同值，缓存不得张冠李戴");
    }

    @Test
    void concurrentSnapshotsNeverReturnAnotherTimesValue() throws Exception {
        TemplateGraph graph = rampGraph();
        GraphValueSource source = new GraphValueSource(graph, new Rng(SEED));

        long[] times = {0L, 137L, 1_000L, 4_321L, 7_777L, 10_000L};
        Map<Long, Double> truth = new HashMap<>();
        for (long t : times) {
            truth.put(t, expected(graph, t));
        }

        int threads = 8;
        int iterations = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    for (int j = 0; j < iterations; j++) {
                        long t = times[rnd.nextInt(times.length)];
                        double want = truth.get(t);
                        double got = source.snapshot(t).get(SVID);
                        assertEquals(want, got, 1e-9,
                                "snapshot(" + t + ") 返回了与请求时刻不符的值: " + got);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
