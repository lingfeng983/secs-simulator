package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;

/**
 * 终端消息（S10F3）的对外视图：收件箱可能还没建（未连接），这里给出空列表。
 */
final class SessionTailView {

    private final HsmsSessionRuntime host;

    SessionTailView(HsmsSessionRuntime host) {
        this.host = host;
    }

    /** 最近收到的终端消息（新→旧）。 */
    List<HsmsSessionRuntime.TerminalMessage> messages() {
        TerminalInbox inbox = this.host.terminalInbox;
        return inbox == null
                ? List.of()
                : inbox.messages().stream()
                        .map(m -> new HsmsSessionRuntime.TerminalMessage(m.tid(), m.text(), m.atMillis()))
                        .toList();
    }

    String json() {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> items = new ArrayList<>();
        for (HsmsSessionRuntime.TerminalMessage message : this.messages()) {
            items.add(b.object(
                    b.pair("tid", message.tid()),
                    b.pair("text", message.text()),
                    b.pair("atMillis", message.atMillis())));
        }
        return b.array(items).toJson();
    }
}