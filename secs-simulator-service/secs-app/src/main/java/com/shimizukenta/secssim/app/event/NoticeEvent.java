package com.shimizukenta.secssim.app.event;

/**
 * 报文之外的状态通知（schema v6：SSE 类型化事件）。
 *
 * <p>SSE 此前只推报文（{@code event: message}），事件流/报警/设备状态
 * 全靠前端轮询。工作台要实时滚动事件、点亮的设备卡需要状态变化推送，
 * 所以增加这一类通知；报文事件的形状与语义不变（增量演进）。</p>
 *
 * <p>身份契约（wire 定稿）：SSE 通知帧的顶层 {@code sessionId} 只出现在
 * 会话域事件（s6f11 / alarm / session / device-link）帧里；
 * plan-fired / host-link / config-changed 的身份本来就在 payload 里
 * （deviceId / hostId / name），帧顶层不带 sessionId、也不新增别的身份字段。
 * 组件名从 {@code sessionId} 改成 {@code sourceId} 就是为此——旧名把
 * 发布方（如把 hostId 塞进"会话标识"参数位）往坑里带（审计 P2-1）。</p>
 *
 * @param sourceId  来源标识，含义随 type：会话域装会话 id；plan-fired 装设备 id；
 *                  host-link 装 host id；config-changed 装配置名
 * @param type      通知类型：{@code s6f11} / {@code alarm} / {@code session} /
 *                  {@code device-link} / {@code host-link} / {@code config-changed} /
 *                  {@code plan-fired}（常量在 {@code PlanFiredThrottle}）
 * @param payload   载荷 JSON 文本（不含顶层身份/at，由 SSE 帧统一补）
 * @param atMillis  发生时刻（墙钟）
 */
public record NoticeEvent(String sourceId, String type, String payload, long atMillis) {

    /** 采集事件上报（载荷：ceid, planId?）。 */
    public static final String S6F11 = "s6f11";

    /** 报警置位/清除（载荷：alid, set, text, category）。 */
    public static final String ALARM = "alarm";

    /** 会话状态迁移（载荷：state?, controlState?）。 */
    public static final String SESSION = "session";

    /**
     * 设备链路相位（载荷：deviceId, phase）。
     *
     * <p>3.0 的侧栏与设备卡要"连上/断开立刻变"，靠 2s 轮询会看起来卡顿；
     * phase 取值同 {@code SessionRuntime.LinkPhase}。</p>
     */
    public static final String DEVICE_LINK = "device-link";

    /** 主机链路相位（载荷：hostId, phase）。 */
    public static final String HOST_LINK = "host-link";

    /** 配置变更（载荷：name, action=save|save-section|create|delete）。 */
    public static final String CONFIG_CHANGED = "config-changed";
}
