package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.gem.ACKC10;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * 终端消息收件箱：主机用 S10F3 推一段文本给设备，设备回 S10F4。
 *
 * <p>这条路径与会话主流程无关——它只是"收一小段字、回一个确认"，
 * 所以单独放在这里，会话层只留一行调用。</p>
 */
final class TerminalInbox {

    /** 保留最近多少条终端消息。 */
    private static final int MAX_TERMINAL_MESSAGES = 20;

    /** 收到的终端消息（新→旧）。 */
    private final Deque<TerminalMessage> messages = new ArrayDeque<>();

    /** 收到的条数。 */
    private final AtomicLong received = new AtomicLong();

    /** 通信器。 */
    private final SecsCommunicator communicator;

    /** 会话 id（错误日志用）。 */
    private final String sessionId;

    /** 出错回调。 */
    private final java.util.function.Consumer<Exception> errorSink;

    TerminalInbox(SecsCommunicator communicator, String sessionId,
                  java.util.function.Consumer<Exception> errorSink) {
        this.communicator = communicator;
        this.sessionId = sessionId;
        this.errorSink = errorSink;
    }

    /** 收到条数。 */
    long received() {
        return this.received.get();
    }

    /** 最近收到的终端消息（新→旧）。 */
    List<TerminalMessage> messages() {
        synchronized (this.messages) {
            return List.copyOf(this.messages);
        }
    }

    /**
     * 处理 S10F3：收下终端文本，回 S10F4。
     *
     * @param primary 主机发来的 S10F3
     */
    void handle(SecsMessage primary) {
        ACKC10 ack = ACKC10.AcceptedForDisplay;

        try {
            Secs2 body = primary.secs2();
            if (body.size() < 2) {
                throw new IllegalArgumentException("S10F3 需要 TID + TEXT");
            }

            int tid = body.get(0).getByte(0) & 0xFF;
            String text = body.get(1).getAscii();

            synchronized (this.messages) {
                this.messages.addLast(new TerminalMessage(tid, text, System.currentTimeMillis()));
                while (this.messages.size() > MAX_TERMINAL_MESSAGES) {
                    this.messages.removeFirst();
                }
            }
            this.received.incrementAndGet();
        } catch (Exception e) {
            this.errorSink.accept(e);
            ack = ACKC10.MessageWillNotBeDisplayed;
            Log.error("会话 " + this.sessionId + " 处理 S10F3 失败", e);
        }

        try {
            /*
             * 不用 gem().s10f4：协议层 s10fx 用的是 comm.send(10, 4, ...)，
             * 发的是新的 Primary，而不是对 S10F3 的 Reply，主机会一直等 T3。
             * 这里显式用 primary 构造 Secondary。
             */
            this.communicator.send(primary, 10, 4, false, ack.secs2());
        } catch (Exception e) {
            this.errorSink.accept(e);
            Log.error("会话 " + this.sessionId + " 回复 S10F4 失败", e);
        }
    }

    /**
     * 一条终端消息。
     *
     * @param tid       终端号
     * @param text      文本
     * @param atMillis  到达时刻（墙钟）
     */
    record TerminalMessage(int tid, String text, long atMillis) {
    }
}
