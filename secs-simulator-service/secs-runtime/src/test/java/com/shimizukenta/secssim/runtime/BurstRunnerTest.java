package com.shimizukenta.secssim.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * 压测节奏专项：取消必须在亚秒内生效，不能被一个长 interval 的 sleep 卡住。
 */
class BurstRunnerTest {

    @Test
    void cancellationInterruptsLongWaitQuickly() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        long startedAt = System.currentTimeMillis();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        }, "test-cancel");
        canceller.start();
        try {
            boolean more = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> BurstRunner.pace(60_000L, startedAt, 0,
                            startedAt + 5 * 60_000L, cancelled::get));
            assertFalse(more, "取消后不应继续发后续条目");
        } finally {
            canceller.interrupt();
        }
    }

    @Test
    void deadlineStopsPacingWithoutCancellation() {
        long now = System.currentTimeMillis();
        assertFalse(BurstRunner.pace(60_000L, now - 120_000L, 2,
                now + 10_000L, () -> false), "下一拍越过总预算必须停");
        assertTrue(BurstRunner.pace(0L, now - 1L, 0,
                now + 10_000L, () -> false), "零间隔只看预算");
        assertFalse(BurstRunner.pace(0L, now, 0,
                now - 1_000L, () -> false), "已过预算则停");
    }
}
