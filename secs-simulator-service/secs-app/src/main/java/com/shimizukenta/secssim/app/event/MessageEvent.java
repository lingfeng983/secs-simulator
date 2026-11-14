package com.shimizukenta.secssim.app.event;

/**
 * 一条经过会话的 SECS 报文。
 *
 * @param seq         全局单调序号（用于 SSE 与历史补拉的去重；旧日志行没有，读回为 0）
 * @param sessionId   会话标识
 * @param hostId      主机模拟实例 id；空串表示这条报文属于设备会话本身（无主机标记）
 * @param direction   {@code in} 收到 / {@code out} 发出
 * @param stream      流号
 * @param function    功能号
 * @param wbit        是否带 W 位
 * @param body        SECS-II 的 JSON 表示（截断到合理长度）
 * @param atMillis    发生时刻（墙钟）
 * @param perspective 视角：{@code device} 设备侧（默认）/{@code host} 主机侧。
 *                    同一帧在进程内目标会被两侧各记一条，用视角区分后设备页只读
 *                    device、主机页只读 host，不做跨域去重（3.0 设计 §2.4.2）
 */
public record MessageEvent(long seq, String sessionId, String hostId, String direction,
                           int stream, int function, boolean wbit, String body, long atMillis,
                           String perspective) {

    /** 设备视角（默认）。 */
    public static final String PERSPECTIVE_DEVICE = "device";
    /** 主机视角。 */
    public static final String PERSPECTIVE_HOST = "host";

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
        this(SEQUENCE.incrementAndGet(), sessionId, "", direction, stream, function, wbit, body, atMillis, null);
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
        this(seq, sessionId, "", direction, stream, function, wbit, body, atMillis, null);
    }

    /**
     * 兼容旧日志行：9 参读回（hostId 有值即主机视角；旧行没有 perspective 列）。
     *
     * @param seq       序号
     * @param sessionId 会话
     * @param hostId    主机实例 id
     * @param direction 方向
     * @param stream    流
     * @param function  功能
     * @param wbit      W 位
     * @param body      报文体
     * @param atMillis  时刻
     */
    public MessageEvent(long seq, String sessionId, String hostId, String direction, int stream,
                        int function, boolean wbit, String body, long atMillis) {
        this(seq, sessionId, hostId, direction, stream, function, wbit, body, atMillis, null);
    }

    /** 报文体的最大保留长度，避免一条大报文把 SSE 通道撑爆。 */
    public static final int MAX_BODY_LENGTH = 2_000;

    /**
     * 创建事件并截断过长的报文体；视角缺省按 hostId 推导。
     *
     * @param seq         序号
     * @param sessionId   会话
     * @param hostId      主机实例 id（空串表示设备会话报文）
     * @param direction   方向
     * @param stream      流
     * @param function    功能
     * @param wbit        W 位
     * @param body        报文体
     * @param atMillis    时刻
     * @param perspective 视角；null/空按 hostId 推导
     */
    public MessageEvent {
        body = body == null ? "" : (body.length() > MAX_BODY_LENGTH
                ? body.substring(0, MAX_BODY_LENGTH) + "…" : body);
        sessionId = sessionId == null ? "" : sessionId;
        hostId = hostId == null ? "" : hostId;
        if (perspective == null || perspective.isBlank()) {
            perspective = hostId.isEmpty() ? PERSPECTIVE_DEVICE : PERSPECTIVE_HOST;
        }
    }

    /**
     * 主机模拟实例发布的报文（自动分配序号，主机视角）。
     *
     * <p>3.0 起<b>所有</b>主机（不再分本机/远程）都从这里发布主机侧报文，
     * 带 {@code perspective=host}；设备侧同一帧仍是设备视角，二者不去重。</p>
     *
     * @param hostId    主机实例 id
     * @param direction 方向（统一按设备视角）
     * @param stream    流号
     * @param function  功能号
     * @param wbit      W 位
     * @param body      报文体
     * @param atMillis  时刻
     * @return 事件
     */
    public static MessageEvent ofHost(String hostId, String direction,
                                      int stream, int function, boolean wbit, String body, long atMillis) {
        return new MessageEvent(SEQUENCE.incrementAndGet(), "", hostId, direction,
                stream, function, wbit, body, atMillis, PERSPECTIVE_HOST);
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
