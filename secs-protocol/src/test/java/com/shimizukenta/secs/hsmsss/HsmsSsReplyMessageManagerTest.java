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

    /**
     * 一条 S1F2（secondary）。应答判定要求偶数 function，原来的夹具用 S1F1（primary）
     * 冒充"应答"，恰好掩盖了 P1-5 那个碰撞风险：primary 和 secondary 被同等对待。
     */
    private static HsmsSsMessage dataMessage(int systemBytes) {
        return functionMessage(1, 2, systemBytes);
    }

    private static HsmsSsMessage functionMessage(int stream, int function, int systemBytes) {
        return new HsmsSsMessage(new byte[] {
                0, 1, (byte) stream, (byte) function, 0, 0,
                (byte)(systemBytes >> 24),
                (byte)(systemBytes >> 16),
                (byte)(systemBytes >> 8),
                (byte)(systemBytes)
        });
    }

    /**
     * 同 SystemBytes 的 primary 不能被当成应答吞掉。
     *
     * <p>等 S6F12 时主机若发来 SystemBytes 恰好相同的 S6F11，只比 key 的实现会把这条
     * primary 交给等待者：主机消息凭空消失，等待方还把 S6F11 当 S6F12 解析。</p>
     */
    @Test
    @DisplayName("a primary with colliding system-bytes is not consumed as a reply")
    void collidingPrimaryIsNotConsumedAsReply() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage primary = functionMessage(6, 11, 7);
        mgr.entry(primary);

        assertTrue(mgr.put(functionMessage(6, 11, 7)).isPresent(),
                "同 SystemBytes 的 primary 必须留在接收路径上");
        assertTrue(mgr.reply(primary, 100L, TimeUnit.MILLISECONDS).isEmpty(), "不应把 primary 当应答");

        assertTrue(mgr.put(functionMessage(6, 12, 7)).isEmpty(), "真正的 S6F12 仍要被消费");
        assertTrue(mgr.reply(primary, 100L, TimeUnit.MILLISECONDS).isPresent());
    }

    @Test
    @DisplayName("control frames are not routed into reply matching")
    void controlFrameIsNotTreatedAsReply() {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        /* Select.req 没有 stream/function（-1），不能被任何 Pack 消费 */
        HsmsSsMessage selectReq = new HsmsSsMessage(new byte[] {
                (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 1, 0, 0, 0, 1
        });
        assertTrue(mgr.put(selectReq).isPresent());
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
