package com.shimizukenta.secssim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EngineClockTest {

    @Test
    void manualClockStartsAtGivenValue() {
        EngineClock clock = EngineClock.manual(100L);
        assertEquals(100L, clock.nowMs());
    }

    @Test
    void manualClockAdvances() {
        ManualEngineClock clock = EngineClock.manual(0L);
        clock.advanceMs(50L);
        clock.advanceMs(50L);
        assertEquals(100L, clock.nowMs());
    }

    @Test
    void manualClockRejectsNegativeAdvance() {
        ManualEngineClock clock = EngineClock.manual(0L);
        assertThrows(IllegalArgumentException.class, () -> clock.advanceMs(-1L));
        assertEquals(0L, clock.nowMs());
    }

    @Test
    void systemClockIsMonotonic() {
        EngineClock clock = EngineClock.system();
        long first = clock.nowMs();
        long second = clock.nowMs();
        assertTrue(second >= first, "monotonic clock went backwards: " + first + " -> " + second);
        assertTrue(first >= 0L);
    }
}
