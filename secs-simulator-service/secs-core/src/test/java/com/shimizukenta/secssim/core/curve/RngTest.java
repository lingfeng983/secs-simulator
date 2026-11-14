package com.shimizukenta.secssim.core.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RngTest {

    @Test
    void uniform01IsStableAndInRange() {
        Rng rng = new Rng(42L);
        for (long bucket = 0; bucket < 1_000; bucket++) {
            double v = rng.uniform01(bucket);
            assertTrue(v >= 0.0 && v < 1.0, "out of range: " + v);
            assertEquals(v, rng.uniform01(bucket), "not deterministic at bucket " + bucket);
        }
    }

    @Test
    void derivedStreamsAreIndependent() {
        Rng root = new Rng(7L);
        Rng a = root.derive("svid:1001");
        Rng b = root.derive("svid:1002");

        assertNotEquals(a.uniform01(0L), b.uniform01(0L), "derived streams must differ");

        Rng a2 = new Rng(7L).derive("svid:1001");
        assertEquals(a.uniform01(0L), a2.uniform01(0L), "same key must reproduce");
    }

    @Test
    void smoothNoiseIsContinuous() {
        Rng rng = new Rng(1L);
        double previous = rng.smooth01(0L, 1_000L);
        for (long t = 10L; t <= 5_000L; t += 10L) {
            double current = rng.smooth01(t, 1_000L);
            assertTrue(Math.abs(current - previous) < 0.1,
                    "noise jumped at t=" + t + ": " + previous + " -> " + current);
            previous = current;
        }
    }

    @Test
    void fbmStaysWithinUnitRange() {
        Rng rng = new Rng(3L);
        for (long t = 0L; t < 120_000L; t += 500L) {
            double v = rng.fbm(t, 2_000L, 3);
            assertTrue(v >= -1.0 && v <= 1.0, "fbm out of range at t=" + t + ": " + v);
        }
    }

    @Test
    void stringHashIsStable() {
        assertEquals(Rng.hash("TEMP_PV"), Rng.hash("TEMP_PV"));
        assertNotEquals(Rng.hash("TEMP_PV"), Rng.hash("TEMP_SV"));
    }
}
