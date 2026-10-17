package com.shimizukenta.secssim.core.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.template.ParameterLayers.Layer;

class ParameterLayersTest {

    @Test
    void higherLayerOverridesLower() {
        ParameterLayers layers = ParameterLayers.empty()
                .with(Layer.TEMPLATE_DEFAULT, Map.of("gain", 1.0, "tau", 3.0))
                .with(Layer.UNIT_TEMPLATE, Map.of("gain", 1.4))
                .with(Layer.UNIT, Map.of("gain", 1.5))
                .with(Layer.PROFILE, Map.of("gain", 2.2))
                .with(Layer.RUNTIME, Map.of("gain", 9.9));

        assertEquals(9.9, layers.resolve("gain").orElseThrow(), 1e-9, "运行时覆盖优先级最高");
        assertEquals(3.0, layers.resolve("tau").orElseThrow(), 1e-9, "未被覆盖的取默认值");
        assertTrue(layers.resolve("missing").isEmpty());
    }

    @Test
    void profileBeatsUnitButLosesToRuntime() {
        ParameterLayers withProfile = ParameterLayers.empty()
                .with(Layer.UNIT, Map.of("gain", 1.5))
                .with(Layer.PROFILE, Map.of("gain", 2.2));
        assertEquals(2.2, withProfile.resolve("gain").orElseThrow(), 1e-9);

        ParameterLayers withRuntime = withProfile.with(Layer.RUNTIME, Map.of("gain", 3.3));
        assertEquals(3.3, withRuntime.resolve("gain").orElseThrow(), 1e-9);
    }

    @Test
    void effectiveMergesAllLayers() {
        ParameterLayers layers = ParameterLayers.empty()
                .with(Layer.TEMPLATE_DEFAULT, Map.of("a", 1.0, "b", 2.0))
                .with(Layer.UNIT, Map.of("b", 20.0, "c", 30.0));

        Map<String, Double> effective = layers.effective();
        assertEquals(3, effective.size());
        assertEquals(1.0, effective.get("a"), 1e-9);
        assertEquals(20.0, effective.get("b"), 1e-9);
        assertEquals(30.0, effective.get("c"), 1e-9);
    }

    @Test
    void definesReportsLayerOrigin() {
        ParameterLayers layers = ParameterLayers.empty()
                .with(Layer.TEMPLATE_DEFAULT, Map.of("a", 1.0))
                .with(Layer.UNIT, Map.of("b", 2.0));

        assertTrue(layers.defines(Layer.TEMPLATE_DEFAULT, "a"));
        assertFalse(layers.defines(Layer.UNIT, "a"));
        assertTrue(layers.defines(Layer.UNIT, "b"));
    }

    @Test
    void withDoesNotMutateReceiver() {
        ParameterLayers base = ParameterLayers.empty().with(Layer.TEMPLATE_DEFAULT, Map.of("a", 1.0));
        ParameterLayers extended = base.with(Layer.UNIT, Map.of("a", 5.0));

        assertEquals(1.0, base.resolve("a").orElseThrow(), 1e-9, "原对象必须保持不变");
        assertEquals(5.0, extended.resolve("a").orElseThrow(), 1e-9);
    }
}
