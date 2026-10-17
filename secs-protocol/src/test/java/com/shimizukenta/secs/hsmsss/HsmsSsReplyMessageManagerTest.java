package com.shimizukenta.secs.hsmsss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HsmsSsReplyMessageManager}.
 *
 * <p>
 * These tests pin down the entry/exit lifecycle: a Pack lives only between
 * {@code entry(primary)} and {@code exit(primary)} (the send-transaction
 * try/finally), so a late reply of a finished transaction -- including a
 * reply whose system-bytes key has wrapped around -- can no longer be
 * routed into a later transaction.
 * </p>
 */
class HsmsSsReplyMessageManagerTest {

    private static HsmsSsMessage dataMessage(int systemBytes) {
        return new HsmsSsMessage(new byte[] {
                0, 0, 1, 1, 0, 0,
                (byte)(systemBytes >> 24),
                (byte)(systemBytes >> 16),
                (byte)(systemBytes >> 8),
                (byte)(systemBytes)
        });
    }

    @Test
    @DisplayName("reply is delivered to the waiting transaction")
    void replyIsDeliveredToMatchingTransaction() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage primary = dataMessage(1);
        mgr.entry(primary);

        assertTrue(mgr.put(dataMessage(1)).isEmpty(), "reply must be consumed");

        Optional<HsmsSsMessage> r = mgr.reply(primary, 1L, TimeUnit.SECONDS);
        assertTrue(r.isPresent());
        assertEquals(1, r.get().systemBytesKey().intValue());
    }

    @Test
    @DisplayName("late reply of a finished transaction is not delivered")
    void lateReplyAfterExitIsNotDelivered() {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage primary = dataMessage(1);
        mgr.entry(primary);
        mgr.exit(primary);

        /* a late reply (e.g. after T3 timeout, or after the 16-bit
         * system-bytes counter wrapped around) must not be routed
         * into any transaction */
        Optional<HsmsSsMessage> r = mgr.put(dataMessage(1));
        assertTrue(r.isPresent(), "late reply must be returned, not delivered");
    }

    @Test
    @DisplayName("reply without a waiting transaction is returned")
    void replyWithoutEntryIsReturned() {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();
        assertTrue(mgr.put(dataMessage(7)).isPresent());
    }

    @Test
    @DisplayName("reply with a different system-bytes key is returned")
    void replyWithDifferentKeyIsReturned() {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        mgr.entry(dataMessage(1));
        assertTrue(mgr.put(dataMessage(2)).isPresent());
    }

    @Test
    @DisplayName("reply returns empty when the transaction times out")
    void replyTimesOut() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage primary = dataMessage(1);
        mgr.entry(primary);

        Optional<HsmsSsMessage> r = mgr.reply(primary, 100L, TimeUnit.MILLISECONDS);
        assertFalse(r.isPresent());
    }
}
