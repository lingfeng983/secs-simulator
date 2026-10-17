package com.shimizukenta.secssim.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenRejects() {
        RateLimiter limiter = new RateLimiter(3, 1_000L);

        assertTrue(limiter.tryAcquire(0L));
        assertTrue(limiter.tryAcquire(100L));
        assertTrue(limiter.tryAcquire(200L));
        assertFalse(limiter.tryAcquire(300L), "超过上限必须拒绝");
        assertEquals(1L, limiter.rejected());
    }

    @Test
    void windowSlides() {
        RateLimiter limiter = new RateLimiter(2, 1_000L);

        assertTrue(limiter.tryAcquire(0L));
        assertTrue(limiter.tryAcquire(100L));
        assertFalse(limiter.tryAcquire(200L));

        assertTrue(limiter.tryAcquire(1_001L), "旧记录滑出窗口后应重新可用");
        assertEquals(1, limiter.used(1_100L));
    }

    @Test
    void usedCountsOnlyCurrentWindow() {
        RateLimiter limiter = new RateLimiter(10, 500L);

        limiter.tryAcquire(0L);
        limiter.tryAcquire(100L);
        assertEquals(2, limiter.used(200L));
        assertEquals(0, limiter.used(600L));
    }
}
