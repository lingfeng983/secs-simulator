package com.shimizukenta.secssim.core.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.curve.RampCurve;
import com.shimizukenta.secssim.core.curve.RandomWalkCurve;
import com.shimizukenta.secssim.core.curve.BoundPolicy;

class ValueStoreTest {

    @Test
    void unboundSvidHasNoValue() {
        ValueStore store = new ValueStore(1L);
        assertFalse(store.valueAt(1001, 0L).isPresent());
        assertFalse(store.isBound(1001));
    }

    @Test
    void boundSvidEvaluatesCurve() {
        ValueStore store = new ValueStore(1L);
        store.bind(1001, RampCurve.of(0.0, 100.0, 10_000L));

        assertTrue(store.isBound(1001));
        assertEquals(50.0, store.valueAt(1001, 5_000L).orElseThrow(), 1e-9);
    }

    @Test
    void snapshotCoversAllBindings() {
        ValueStore store = new ValueStore(1L);
        store.bind(1001, RampCurve.of(0.0, 100.0, 10_000L));
        store.bind(1002, RampCurve.of(100.0, 0.0, 10_000L));

        Map<Integer, Double> snapshot = store.snapshot(5_000L);
        assertEquals(2, snapshot.size());
        assertEquals(50.0, snapshot.get(1001), 1e-9);
        assertEquals(50.0, snapshot.get(1002), 1e-9);
    }

    @Test
    void sameSeedProducesSameSeries() {
        ValueStore a = new ValueStore(20240601L);
        ValueStore b = new ValueStore(20240601L);
        RandomWalkCurve curve = new RandomWalkCurve(50.0, 1.0, 5.0, 2_000L, 3, 0.0, 100.0, BoundPolicy.CLAMP);

        a.bind(1001, curve);
        b.bind(1001, curve);

        for (long t = 0L; t < 60_000L; t += 500L) {
            assertEquals(a.valueAt(1001, t).orElseThrow(), b.valueAt(1001, t).orElseThrow(),
                    "同 seed 同 svid 必须逐点相等, t=" + t);
        }
    }

    @Test
    void addingAnotherSvidDoesNotPerturbExistingOne() {
        RandomWalkCurve curve = new RandomWalkCurve(50.0, 1.0, 5.0, 2_000L, 3, 0.0, 100.0, BoundPolicy.CLAMP);

        ValueStore solo = new ValueStore(9L);
        solo.bind(1001, curve);

        ValueStore crowded = new ValueStore(9L);
        crowded.bind(1001, curve);
        crowded.bind(1002, curve);
        crowded.bind(1003, curve);

        for (long t = 0L; t < 30_000L; t += 1_000L) {
            assertEquals(solo.valueAt(1001, t).orElseThrow(),
                    crowded.valueAt(1001, t).orElseThrow(),
                    "新增变量不得改变已有变量的曲线, t=" + t);
        }
    }

    @Test
    void unbindRemovesValue() {
        ValueStore store = new ValueStore(1L);
        store.bind(1001, RampCurve.of(0.0, 1.0, 1_000L));
        assertTrue(store.unbind(1001));
        assertFalse(store.valueAt(1001, 0L).isPresent());
        assertFalse(store.unbind(1001));
    }
}
