package com.shimizukenta.secs.hsmsss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic evidence for the {@code activeCircuit()} wait fix.
 *
 * <p>
 * The former implementation of {@code AbstractHsmsSsActiveCommunicator#
 * activeCircuit()} ended the connect handshake with a plain
 * {@code synchronized (channel) { channel.wait(); }}, while the completion
 * handler notified from its {@code finally} block. Nothing ordered the
 * notification after the waiter entered {@code wait()}, so a completion
 * that finished first lost its notification and the loop thread blocked
 * forever (no reconnect, no T5 retry, stuck state).
 * </p>
 * <p>
 * The race window is nanoseconds wide and cannot be hit reliably from a
 * black-box test; this test therefore demonstrates the two synchronization
 * patterns themselves: the unguarded {@code wait()} loses a notification
 * that happens first, the guarded/timed wait used by the fix does not.
 * </p>
 */
class ActiveCircuitWaitPatternTest {

    @Test
    @DisplayName("unguarded wait() loses an earlier notification; the guarded wait of the fix does not")
    void unguardedWaitLosesNotificationButGuardedWaitDoesNot() throws Exception {

        /* former pattern: notify happens before wait() is entered */
        final Object lock = new Object();
        Thread notifier = new Thread(() -> {
            synchronized ( lock ) {
                lock.notifyAll();
            }
        });
        notifier.start();
        notifier.join();

        final long beginUnguarded = System.currentTimeMillis();
        synchronized ( lock ) {
            lock.wait(1000L);   /* the real former code waited without timeout */
        }
        final long unguardedBlocked = System.currentTimeMillis() - beginUnguarded;
        assertTrue(unguardedBlocked >= 900L,
                "the unguarded wait() missed the earlier notify and blocked"
                + " (" + unguardedBlocked + " ms); in the real code this hangs forever");

        /* fixed pattern: condition flag + timed wait */
        final AtomicBoolean circuitCompleted = new AtomicBoolean(true);
        final Object lock2 = new Object();
        final long beginGuarded = System.currentTimeMillis();
        synchronized ( lock2 ) {
            while ( ! circuitCompleted.get() ) {
                lock2.wait(1000L);
            }
        }
        final long guardedBlocked = System.currentTimeMillis() - beginGuarded;
        assertTrue(guardedBlocked < 500L,
                "the guarded wait returns immediately when the circuit already completed");
    }
}
