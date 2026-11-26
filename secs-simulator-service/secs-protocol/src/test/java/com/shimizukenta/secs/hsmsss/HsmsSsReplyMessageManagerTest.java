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

    /** S1F1：真正的 primary（odd function），它的应答是 S1F2。 */
    private static HsmsSsMessage primaryMessage(int systemBytes) {
        return functionMessage(1, 1, systemBytes);
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
    @DisplayName("control frame reply (SELECT.rsp) with matching system-bytes is delivered")
    void controlReplyIsDelivered() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage selectReq = new HsmsSsMessage(new byte[] {
                (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 1, 0, 0, 0, 1
        });
        mgr.entry(selectReq);

        HsmsSsMessage selectRsp = new HsmsSsMessage(new byte[] {
                (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 2, 0, 0, 0, 1
        });
        assertTrue(mgr.put(selectRsp).isEmpty(), "control reply must be routed by system-bytes");
        assertTrue(mgr.reply(selectReq, 100L, TimeUnit.MILLISECONDS).isPresent());
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

    /** 控制帧（pType=0，sType 由参数给定），SystemBytes 撞号场景用。 */
    private static HsmsSsMessage controlMessage(int sType, int systemBytes) {
        return new HsmsSsMessage(new byte[] {
                (byte) 0xFF, (byte) 0xFF, 0, 0, 0, (byte) sType,
                (byte)(systemBytes >> 24),
                (byte)(systemBytes >> 16),
                (byte)(systemBytes >> 8),
                (byte)(systemBytes)
        });
    }

    /**
     * P2-2 回归：等 Select.rsp 期间收到同 SystemBytes 的偶数 function 数据帧，
     * 不得被控制事务当成应答吞掉。
     *
     * <p>原来控制 Pack 的 matches 恒 true：16 位 SystemBytes 回绕撞号时，
     * 一条 S1F2 会被当成 Select.rsp 交给等待方，解析成 NOT_SELECT_RSP 导致
     * 建链误判失败，而真正的数据应答凭空消失。</p>
     */
    @Test
    @DisplayName("a data secondary colliding with a waiting SELECT transaction is not consumed")
    void collidingDataSecondaryIsNotConsumedByControlPack() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage selectReq = controlMessage(1, 7);
        mgr.entry(selectReq);

        assertTrue(mgr.put(functionMessage(1, 2, 7)).isPresent(),
                "同 SystemBytes 的数据 secondary 必须留在接收路径上，不得当 Select.rsp");
        assertTrue(mgr.reply(selectReq, 100L, TimeUnit.MILLISECONDS).isEmpty(),
                "数据帧不得满足控制事务的应答等待");

        assertTrue(mgr.put(controlMessage(2, 7)).isEmpty(), "真正的 Select.rsp 仍要被消费");
        assertTrue(mgr.reply(selectReq, 100L, TimeUnit.MILLISECONDS).isPresent());
    }

    /**
     * P2-2 回归：控制事务之间也要校验 SType——等 Linktest.rsp 时收到同
     * SystemBytes 的 Select.rsp（或其他控制应答）不得被消费。
     */
    @Test
    @DisplayName("a control reply of a different S-Type is not consumed by a control pack")
    void mismatchedControlSTypeIsNotConsumed() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage linktestReq = controlMessage(5, 9);
        mgr.entry(linktestReq);

        assertTrue(mgr.put(controlMessage(2, 9)).isPresent(), "Select.rsp 不是 Linktest 的应答");
        assertTrue(mgr.put(controlMessage(4, 9)).isPresent(), "Deselect.rsp 不是 Linktest 的应答");
        assertTrue(mgr.reply(linktestReq, 100L, TimeUnit.MILLISECONDS).isEmpty());

        assertTrue(mgr.put(controlMessage(6, 9)).isEmpty(), "真正的 Linktest.rsp 仍要被消费");
        assertTrue(mgr.reply(linktestReq, 100L, TimeUnit.MILLISECONDS).isPresent());
    }

    /** P2-1/P2-2 配套：Deselect 事务按 SystemBytes + DESELECT_RSP 匹配。 */
    @Test
    @DisplayName("DESELECT transaction consumes only DESELECT.rsp with the same system-bytes")
    void deselectReplyMatchesBySType() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage deselectReq = controlMessage(3, 11);
        mgr.entry(deselectReq);

        assertTrue(mgr.put(functionMessage(1, 2, 11)).isPresent(), "数据帧不是 Deselect.rsp");
        assertTrue(mgr.put(controlMessage(2, 11)).isPresent(), "Select.rsp 不是 Deselect.rsp");
        assertTrue(mgr.reply(deselectReq, 100L, TimeUnit.MILLISECONDS).isEmpty());

        assertTrue(mgr.put(controlMessage(4, 11)).isEmpty(), "真正的 Deselect.rsp 仍要被消费");
        assertTrue(mgr.reply(deselectReq, 100L, TimeUnit.MILLISECONDS).isPresent());
    }

    /**
     * 同 SystemBytes 的两条事务：先结束的那条不能把后一条的 Pack 删掉。
     *
     * <p>SystemBytes 只有 16 位，回绕后 key 必然重合；exit 按 key 直删会让另一条
     * 在途事务失去等待对象，只能干等一个 T3。</p>
     */
    @Test
    @DisplayName("exit only removes the pack registered by that very primary")
    void exitDoesNotRemoveAnotherTransactionsPack() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage first = primaryMessage(5);
        HsmsSsMessage second = primaryMessage(5);

        mgr.entry(first);
        mgr.entry(second);
        mgr.exit(first);

        assertTrue(mgr.put(dataMessage(5)).isEmpty(),
                "第二条事务的应答仍必须被消费：exit(first) 不得删掉它的 Pack");
        assertTrue(mgr.reply(second, 100L, TimeUnit.MILLISECONDS).isPresent());
    }

    @Test
    @DisplayName("reply is delivered to the waiting transaction")
    void replyIsDeliveredToMatchingTransaction() throws InterruptedException {
        HsmsSsReplyMessageManager mgr = new HsmsSsReplyMessageManager();

        HsmsSsMessage primary = primaryMessage(1);
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

        HsmsSsMessage primary = primaryMessage(1);
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

        HsmsSsMessage primary = primaryMessage(1);
        mgr.entry(primary);

        Optional<HsmsSsMessage> r = mgr.reply(primary, 100L, TimeUnit.MILLISECONDS);
        assertFalse(r.isPresent());
    }
}
