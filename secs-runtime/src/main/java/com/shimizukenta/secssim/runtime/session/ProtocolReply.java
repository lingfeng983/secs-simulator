package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * 回话出口：把"应答什么"交给通信层。
 *
 * <p>协作者不认识 {@code SecsCommunicator}，只说"该回 S2F14，内容是这个"，
 * 真正把字节送出去由会话层做。</p>
 */
@FunctionalInterface
interface ProtocolReply {

    /**
     * 回一句。
     *
     * @param primary  主报文
     * @param stream   应答的流号
     * @param function 应答的功能号
     * @param body     应答内容
     */
    void reply(SecsMessage primary, int stream, int function, Secs2 body);
}
