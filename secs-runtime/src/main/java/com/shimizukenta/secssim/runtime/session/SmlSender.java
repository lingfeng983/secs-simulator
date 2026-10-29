package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.Optional;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secssim.runtime.sml.SmlParseException;
import com.shimizukenta.secssim.runtime.sml.SmlParser;
import com.shimizukenta.secssim.runtime.sml.SmlRenderer;

/**
 * 手动发送 SML 报文。
 *
 * <p>解析失败是调用方的问题（400），会话没有对端是设备状态（409），
 * 协议层发送失败才是 500。三者不能混成一个 "send failed"。</p>
 */
final class SmlSender {

    /** 通信器。 */
    private final java.util.function.Supplier<SecsCommunicator> communicator;

    /** 会话是否已打开。 */
    private final java.util.function.BooleanSupplier open;

    /** 是否已与主机建立通信。 */
    private final java.util.function.BooleanSupplier communicatable;

    SmlSender(java.util.function.Supplier<SecsCommunicator> communicator,
               java.util.function.BooleanSupplier open,
               java.util.function.BooleanSupplier communicatable) {
        this.communicator = communicator;
        this.open = open;
        this.communicatable = communicatable;
    }

    /**
     * 发送一条 SML 报文。
     *
     * @param sml SML 文本
     * @return 发送结果 JSON
     * @throws IOException 发送失败
     */
    String send(String sml) throws IOException {
        /* 先解析再判连接：SML 写错时应该先告诉用户"语法错"（400），
           而不是因为会话没上线先回一个 409，让人误以为是连接问题。 */
        SmlParser.Message message;
        try {
            message = SmlParser.parse(sml);
        } catch (SmlParseException e) {
            throw new IllegalArgumentException("SML 解析失败: " + e.getMessage(), e);
        }

        SecsCommunicator comm = this.communicator.get();
        if (!this.open.getAsBoolean() || comm == null || !this.communicatable.getAsBoolean()) {
            throw new IllegalStateException("会话未打开或尚未与主机建立通信");
        }

        long startedAt = System.currentTimeMillis();
        Optional<SecsMessage> reply;
        boolean waitTimedOut = false;
        try {
            reply = comm.send(message.stream(), message.function(), message.wbit(), message.body());
        } catch (SecsWaitReplyMessageException e) {
            /* T3 超时不是"没发出去"：报文已经发了，只是主机没回 */
            reply = Optional.empty();
            waitTimedOut = true;
        } catch (SecsException e) {
            throw new IOException("发送失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("发送被中断", e);
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;

        String replySml = reply.map(value -> SmlRenderer.render(value.secs2())).orElse(null);
        /* 原写法 size() < 0 恒为 false（死条件），空报文体照样去渲染。
           空体没有可渲染的内容，直接给空串，避免日志里出现 "{}" 之类的占位。 */
        String sentSml = message.body().isEmpty() ? "" : SmlRenderer.render(message.body());
        JsonHubBuilder b = JsonHub.getBuilder();
        return b.object(
                b.pair("ok", true),
                b.pair("stream", message.stream()),
                b.pair("function", message.function()),
                b.pair("wbit", message.wbit()),
                b.pair("sentSml", sentSml),
                b.pair("timedOut", waitTimedOut || (message.wbit() && reply.isEmpty())),
                b.pair("replyStream", reply.map(SecsMessage::getStream).orElse(0)),
                b.pair("replyFunction", reply.map(SecsMessage::getFunction).orElse(0)),
                b.pair("replyWbit", reply.map(SecsMessage::wbit).orElse(false)),
                b.pair("replySml", replySml),
                b.pair("elapsedMs", elapsedMs))
                .toJson();
    }
}
