package com.shimizukenta.secssim.core.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConditionDebouncerTest {

    @Test
    void firesOnceAfterConditionHoldsForDebounce() {
        ConditionDebouncer debouncer = new ConditionDebouncer(1_000L);

        assertFalse(debouncer.update(0L, true), "刚成立，还没到去抖时间");
        assertFalse(debouncer.update(500L, true));
        assertTrue(debouncer.update(1_000L, true), "连续成立满 1s，触发一次");
        assertFalse(debouncer.update(1_500L, true), "保持成立不能重复触发");
    }

    @Test
    void falseResetsTimerAndAllowsNextEpisode() {
        ConditionDebouncer debouncer = new ConditionDebouncer(1_000L);

        assertFalse(debouncer.update(0L, true));
        assertFalse(debouncer.update(500L, false), "中间变 false，计时归零");
        assertFalse(debouncer.update(600L, true));
        assertFalse(debouncer.update(1_500L, true), "从 600 起算，还差 100ms");
        assertTrue(debouncer.update(1_600L, true));
    }

    @Test
    void zeroDebounceFiresOnFirstTrue() {
        ConditionDebouncer debouncer = new ConditionDebouncer(0L);

        assertTrue(debouncer.update(123L, true));
        assertFalse(debouncer.update(124L, true));
    }
}
