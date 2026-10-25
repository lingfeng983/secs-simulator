package com.shimizukenta.secssim.app.event;

/**
 * 报文之外的状态通知（schema v6：SSE 类型化事件）。
 *
 * <p>SSE 此前只推报文（{@code event: message}），事件流/报警/设备状态
 * 全靠前端轮询。工作台要实时滚动事件、点亮的设备卡需要状态变化推送，
 * 所以增加这一类通知；报文事件的形状与语义不变（增量演进）。</p>
 *
 * @param sessionId  会话标识
 * @param type       通知类型：{@code s6f11} / {@code alarm} / {@code session}
 * @param payload    载荷 JSON 文本（不含 sessionId/at，由 SSE 帧统一补）
 * @param atMillis   发生时刻（墙钟）
 */
public record NoticeEvent(String sessionId, String type, String payload, long atMillis) {

    /** 采集事件上报（载荷：ceid, planId?）。 */
    public static final String S6F11 = "s6f11";

    /** 报警置位/清除（载荷：alid, set, text, category）。 */
    public static final String ALARM = "alarm";

    /** 会话状态迁移（载荷：state?, controlState?）。 */
    public static final String SESSION = "session";
}
