package com.shimizukenta.secssim.core.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.curve.RampCurve;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.template.ParameterLayers.Layer;
import com.shimizukenta.secssim.core.template.TemplateGraph.Instance;

class TemplateGraphTest {

    private static final Rng RNG = new Rng(7L);

    private static Instance source(String id, double value, int svid) {
        return new Instance(id, new SingleCurveTemplate(id, new ConstantCurve(value)),
                Map.of(SingleCurveTemplate.OUTPUT, svid), ParameterLayers.empty());
    }

    private static Instance derived(String id, Map<String, Expression> outputs,
                                    Map<String, Integer> svids, ParameterLayers params) {
        return new Instance(id, new CompositeTemplate(id, outputs), svids, params);
    }

    @Test
    void evaluationFollowsDependenciesRegardlessOfInsertionOrder() {
        ParameterLayers params = ParameterLayers.empty()
                .with(Layer.TEMPLATE_DEFAULT, Map.of("bias", 5.0, "target", 4.0));

        TemplateGraph graph = TemplateGraph.builder()
                .add(derived("derived",
                        Map.of("TOTAL", Expression.parse("in.src.V * 2 + p.bias"),
                                "DELTA", Expression.parse("in.src.V - p.target")),
                        Map.of("TOTAL", 1010, "DELTA", 1011),
                        params))
                .add(source("src", 10.0, 1001))
                .build();

        assertEquals(List.of("src", "derived"), graph.ordered().stream().map(Instance::id).toList(),
                "被依赖的实例必须先算");

        Map<Integer, Double> values = graph.evaluate(0L, RNG);
        assertEquals(10.0, values.get(1001), 1e-9);
        assertEquals(25.0, values.get(1010), 1e-9, "10*2+5");
        assertEquals(6.0, values.get(1011), 1e-9, "10-4");
    }

    @Test
    void parametersComeFromHighestLayer() {
        ParameterLayers params = ParameterLayers.empty()
                .with(Layer.TEMPLATE_DEFAULT, Map.of("gain", 1.0))
                .with(Layer.UNIT, Map.of("gain", 3.0))
                .with(Layer.PROFILE, Map.of("gain", 4.0));

        TemplateGraph graph = TemplateGraph.builder()
                .add(source("src", 2.0, 1001))
                .add(derived("scaled", Map.of("V", Expression.parse("in.src.V * p.gain")),
                        Map.of("V", 1002), params))
                .build();

        assertEquals(8.0, graph.evaluate(0L, RNG).get(1002), 1e-9, "2 * 4（profile 覆盖 unit）");
    }

    @Test
    void cycleIsRejected() {
        Map<String, Expression> a = Map.of("V", Expression.parse("in.b.V + 1"));
        Map<String, Expression> b = Map.of("V", Expression.parse("in.a.V + 1"));

        TemplateGraph.Builder builder = TemplateGraph.builder()
                .add(derived("a", a, Map.of("V", 1001), ParameterLayers.empty()))
                .add(derived("b", b, Map.of("V", 1002), ParameterLayers.empty()));

        GraphException e = assertThrows(GraphException.class, builder::build);
        assertEquals("CYCLE", e.code());
    }

    @Test
    void duplicateSvidBindingIsRejected() {
        TemplateGraph.Builder builder = TemplateGraph.builder()
                .add(source("a", 1.0, 1001))
                .add(source("b", 2.0, 1001));

        GraphException e = assertThrows(GraphException.class, builder::build);
        assertEquals("DUPLICATE_SVID", e.code());
    }

    @Test
    void duplicateInstanceIdIsRejected() {
        TemplateGraph.Builder builder = TemplateGraph.builder()
                .add(source("a", 1.0, 1001))
                .add(source("a", 2.0, 1002));

        assertEquals("DUPLICATE_INSTANCE", assertThrows(GraphException.class, builder::build).code());
    }

    @Test
    void unknownInstanceReferenceIsRejected() {
        TemplateGraph.Builder builder = TemplateGraph.builder()
                .add(derived("x", Map.of("V", Expression.parse("in.ghost.V")),
                        Map.of("V", 1001), ParameterLayers.empty()));

        assertEquals("UNKNOWN_INSTANCE", assertThrows(GraphException.class, builder::build).code());
    }

    @Test
    void unknownOutputReferenceIsRejected() {
        TemplateGraph.Builder builder = TemplateGraph.builder()
                .add(source("src", 1.0, 1001))
                .add(derived("x", Map.of("V", Expression.parse("in.src.NOPE")),
                        Map.of("V", 1002), ParameterLayers.empty()));

        assertEquals("UNKNOWN_OUTPUT", assertThrows(GraphException.class, builder::build).code());
    }

    @Test
    void bindingOutputThatTemplateDoesNotProduceIsRejected() {
        GraphException e = assertThrows(GraphException.class, () -> new Instance(
                "x", new SingleCurveTemplate("x", new ConstantCurve(1.0)),
                Map.of("NOPE", 1001), ParameterLayers.empty()));
        assertEquals("UNKNOWN_OUTPUT", e.code());
    }

    @Test
    void missingParameterFailsAtEvaluation() {
        TemplateGraph graph = TemplateGraph.builder()
                .add(derived("x", Map.of("V", Expression.parse("p.absent + 1")),
                        Map.of("V", 1001), ParameterLayers.empty()))
                .build();

        assertEquals("UNKNOWN_PARAM", assertThrows(GraphException.class,
                () -> graph.evaluate(0L, RNG)).code());
    }

    @Test
    void evaluateRangeProducesOneSnapshotPerStep() {
        TemplateGraph graph = TemplateGraph.builder()
                .add(new Instance("ramp", new SingleCurveTemplate("ramp", RampCurve.of(0.0, 10.0, 10_000L)),
                        Map.of("V", 1001), ParameterLayers.empty()))
                .build();

        List<Map<Integer, Double>> series = new ArrayList<>();
        for (Map<Integer, Double> point : graph.evaluateRange(0L, 10_000L, 1_000L, RNG)) {
            series.add(point);
        }
        assertEquals(11, series.size());
        assertEquals(0.0, series.getFirst().get(1001), 1e-9);
        assertEquals(5.0, series.get(5).get(1001), 1e-9);
        assertEquals(10.0, series.getLast().get(1001), 1e-9);
    }

    @Test
    void evaluateRangeIsLazyAndDoesNotMaterializeHugeWindow() {
        TemplateGraph graph = TemplateGraph.builder()
                .add(new Instance("ramp", new SingleCurveTemplate("ramp", RampCurve.of(0.0, 10.0, 10_000L)),
                        Map.of("V", 1001), ParameterLayers.empty()))
                .build();

        // 窗口 1000 亿 ms、步长 1ms：若整体物化将是约 1000 亿个点，必然 OOM。
        int taken = 0;
        for (Map<Integer, Double> ignored : graph.evaluateRange(0L, 100_000_000_000L, 1L, RNG)) {
            taken++;
            if (taken >= 100) {
                break;
            }
        }
        assertEquals(100, taken, "只取前 100 个点即应能中止，不应物化整个窗口");
    }

    @Test
    void sameTimeAndSeedAlwaysProduceSameValues() {
        TemplateGraph graph = TemplateGraph.builder()
                .add(new Instance("walk",
                        new SingleCurveTemplate("walk", new com.shimizukenta.secssim.core.curve.RandomWalkCurve(
                                50.0, 1.0, 5.0, 2_000L, 3, 0.0, 100.0,
                                com.shimizukenta.secssim.core.curve.BoundPolicy.REFLECT)),
                        Map.of("V", 1001), ParameterLayers.empty()))
                .build();

        for (long t = 0L; t < 30_000L; t += 500L) {
            assertEquals(graph.evaluate(t, new Rng(42L)).get(1001),
                    graph.evaluate(t, new Rng(42L)).get(1001), 0.0, "t=" + t);
        }
        assertTrue(graph.size() == 1);
    }
}
