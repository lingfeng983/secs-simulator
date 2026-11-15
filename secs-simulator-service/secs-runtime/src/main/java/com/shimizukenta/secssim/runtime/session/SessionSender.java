package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * 会话的发送出口：协作者只说"发什么"，真正把字节送出去、把失败记成会话错误都在这里。
 *
 * <p>发送失败说明链路已经断了：记下错误，让上层决定要不要断链。</p>
 */
final class SessionSender {

    private final HsmsSessionRuntime host;

    SessionSender(HsmsSessionRuntime host) {
        this.host = host;
    }

    /** 回一句 secondary（S1F2 / S2F14 / S10F4 ...）。 */
    void reply(SecsMessage primary, int stream, int function, Secs2 body) {
        try {
            this.host.communicator.send(primary, stream, function, false, body);
        } catch (SecsException | InterruptedException e) {
            this.record(e);
        }
    }

    /** 发一帧主动报文（S6F11 / S6F1 ...）。 */
    void send(int stream, int function, boolean wbit, Secs2 body) {
        try {
            this.host.communicator.send(stream, function, wbit, body);
        } catch (SecsException | InterruptedException e) {
            this.record(e);
        }
    }

    private void record(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        this.host.recordError(e);
    }
}