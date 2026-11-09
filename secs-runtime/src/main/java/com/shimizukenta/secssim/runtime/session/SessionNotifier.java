package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.NoticeEvent;
import com.shimizukenta.secs.SecsMessage;

/**
 * 会话对外的播报：数据报文进事件总线，状态通知进通知总线。
 *
 * <p>两种总线用途不同——数据报文是"报文面板"要看的流水，
 * 状态通知是"SSE 面板"要看的类型化事件。放在一处是因为它们共享
 * "哪些消息值得发"的判断（控制帧不算数据）。</p>
 */
final class SessionNotifier {

    /** 会话 id。 */
    private final String sessionId;

    /** 数据报文总线。 */
    private volatile EventBus eventBus;

    /** 状态通知总线。 */
    private volatile NoticeBus noticeBus;

    /** 每秒报文速率统计。 */
    private final RateTracker msgRate;

    SessionNotifier(String sessionId, RateTracker msgRate) {
        this.sessionId = sessionId;
        this.msgRate = msgRate;
    }

    /** 挂上事件总线：会话收发的每条数据报文都会以事件形式发布。 */
    void eventBus(EventBus bus) {
        this.eventBus = bus;
    }

    /** 挂上状态通知总线：事件/报警/状态迁移会以 NoticeEvent 发布。 */
    void noticeBus(NoticeBus bus) {
        this.noticeBus = bus;
    }

    /** 每秒报文数。 */
    long msgRate() {
        return this.msgRate.countInWindow(System.currentTimeMillis());
    }

    /**
     * 把报文发布到事件总线。
     *
     * <p>只发布<b>数据报文</b>（stream ≥ 0）：HSMS 的 select/deselect 之类控制帧
     * 对使用者没有意义，混进报文面板只会造成噪音。
     * </p>
     *
     * @param direction "in" 或 "out"
     * @param message  报文
     */
    void publish(String direction, SecsMessage message) {
        EventBus bus = this.eventBus;
        if (bus == null || message.getStream() < 0) {
            return;
        }

        String body;
        try {
            body = com.shimizukenta.secssim.app.sml.SmlRenderer.render(message.secs2());
        } catch (RuntimeException e) {
            body = "<不可渲染>";
        }

        this.msgRate.record(System.currentTimeMillis());
        bus.publish(new MessageEvent(this.sessionId, direction, message.getStream(), message.getFunction(),
                message.wbit(), body, System.currentTimeMillis()));
    }

    /**
     * 发布一条通知（无总线时 no-op）。
     *
     * @param type    通知类型
     * @param payload 载荷（JSON 片段）
     */
    void notice(String type, String payload) {
        NoticeBus bus = this.noticeBus;
        if (bus != null) {
            bus.publish(new NoticeEvent(this.sessionId, type, payload, System.currentTimeMillis()));
        }
    }
}
