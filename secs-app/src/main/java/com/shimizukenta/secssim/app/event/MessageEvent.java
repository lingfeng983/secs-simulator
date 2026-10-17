package com.shimizukenta.secssim.app.event;

/**
 * 一条经过会话的 SECS 报文。
 *
 * @param sessionId 会话标识
 * @param direction {@code in} 收到 / {@code out} 发出
 * @param stream    流号
 * @param function  功能号
 * @param wbit      是否带 W 位
 * @param body      SECS-II 的 JSON 表示（截断到合理长度）
 * @param atMillis  发生时刻（墙钟）
 */
public record MessageEvent(String sessionId, String direction, int stream, int function,
                           boolean wbit, String body, long atMillis) {

    /** 报文体的最大保留长度，避免一条大报文把 SSE 通道撑爆。 */
    public static final int MAX_BODY_LENGTH = 2_000;

    /**
     * 创建事件并截断过长的报文体。
     *
     * @param sessionId 会话
     * @param direction 方向
     * @param stream    流
     * @param function  功能
     * @param wbit      W 位
     * @param body      报文体
     * @param atMillis  时刻
     */
    public MessageEvent {
        body = body == null ? "" : (body.length() > MAX_BODY_LENGTH
                ? body.substring(0, MAX_BODY_LENGTH) + "…" : body);
    }

    /**
     * 形如 {@code S1F3} 的短标识。
     *
     * @return 标识
     */
    public String sf() {
        return "S" + this.stream + "F" + this.function;
    }
}
