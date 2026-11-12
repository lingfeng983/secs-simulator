package com.shimizukenta.secssim.app.event;

/**
 * 一条经过会话的 SECS 报文。
 *
 * @param seq       全局单调序号（用于 SSE 与历史补拉的去重；旧日志行没有，读回为 0）
 * @param sessionId 会话标识
 * @param hostId    主机模拟实例 id；空串表示这条报文属于设备会话本身（无主机标记）
 * @param direction {@code in} 收到 / {@code out} 发出
 * @param stream    流号
 * @param function  功能号
 * @param wbit      是否带 W 位
 * @param body      SECS-II 的 JSON 表示（截断到合理长度）
 * @param atMillis  发生时刻（墙钟）
 */
public record MessageEvent(long seq, String sessionId, String hostId, String direction,
                           int stream, int function, boolean wbit, String body, long atMillis) {

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
        this(SEQUENCE.incrementAndGet(), sessionId, "", direction, stream, function, wbit, body, atMillis);
    }

    /**
     * 兼容旧调用方：保留序号、不带主机标记。
     *
     * @param seq       序号
     * @param sessionId 会话
     * @param direction 方向
     * @param stream    流
     * @param function  功能
     * @param wbit      W 位
     * @param body      报文体
     * @param atMillis  时刻
     */
    public MessageEvent(long seq, String sessionId, String direction, int stream, int function,
                        boolean wbit, String body, long atMillis) {
        this(seq, sessionId, "", direction, stream, function, wbit, body, atMillis);
    }

    /** 报文体的最大保留长度，避免一条大报文把 SSE 通道撑爆。 */
    public static final int MAX_BODY_LENGTH = 2_000;

    /**
     * 创建事件并截断过长的报文体。
     *
     * @param seq       序号
     * @param sessionId 会话
     * @param hostId    主机实例 id（空串表示设备会话报文）
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
        sessionId = sessionId == null ? "" : sessionId;
        hostId = hostId == null ? "" : hostId;
    }

    /** 是否来自某个主机模拟实例（远程主机只有这一条通道）。 */
    public boolean fromHost() {
        return !this.hostId.isEmpty();
    }

    /**
     * 主机模拟实例发布的报文（自动分配序号）。
     *
     * <p>单独做成工厂而不是再加一个构造器：8 参数的构造器已经有一个
     * "带 seq + 不带 hostId" 的兼容重载，再加同形状的会变成歧义调用。</p>
     *
     * @param sessionId 宿主会话
     * @param hostId    主机实例 id
     * @param direction 方向（统一按设备视角）
     * @param stream    流号
     * @param function  功能号
     * @param wbit      W 位
     * @param body      报文体
     * @param atMillis  时刻
     * @return 事件
     */
    public static MessageEvent ofHost(String sessionId, String hostId, String direction,
                                      int stream, int function, boolean wbit, String body, long atMillis) {
        return new MessageEvent(SEQUENCE.incrementAndGet(), sessionId, hostId, direction,
                stream, function, wbit, body, atMillis);
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
