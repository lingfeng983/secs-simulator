package com.shimizukenta.secssim.app.event;

/**
 * 一条经过会话的 SECS 报文。
 *
 * @param seq       全局单调序号（用于 SSE 与历史补拉的去重；旧日志行没有，读回为 0）
 * @param sessionId 会话标识
 * @param direction {@code in} 收到 / {@code out} 发出
 * @param stream    流号
 * @param function  功能号
 * @param wbit      是否带 W 位
 * @param body      SECS-II 的 JSON 表示（截断到合理长度）
 * @param atMillis  发生时刻（墙钟）
 */
public record MessageEvent(long seq, String sessionId, String direction, int stream, int function,
                           boolean wbit, String body, long atMillis) {

    /** 序号源：进程内单调递增。 */
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 兼容旧调用方：自动分配序号。
     *
     * @param sessionId 会话
     * @param direction 方向
     * @param stream    流
     * @param function  功能
     * @param wbit      W 位
     * @param body      报文体
     * @param atMillis  时刻
     */
    public MessageEvent(String sessionId, String direction, int stream, int function,
                        boolean wbit, String body, long atMillis) {
        this(SEQUENCE.incrementAndGet(), sessionId, direction, stream, function, wbit, body, atMillis);
    }

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
