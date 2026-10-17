package com.shimizukenta.secssim.app.session;

import java.io.IOException;
import java.util.Map;

/**
 * 会话的可操作句柄。
 *
 * <p>应用层只认这个接口；协议细节（HSMS-SS 主动/被动、SECS-I、分块、重连）
 * 全部留在实现侧。
 * </p>
 */
public interface SessionRuntime {

    /**
     * 会话标识。
     *
     * @return 标识
     */
    String id();

    /**
     * 打开端口。重复打开应抛异常，避免"打开了两次"这种静默错误。
     *
     * @throws IOException 绑定失败
     */
    void open() throws IOException;

    /**
     * 关闭端口（幂等）。
     */
    void close();

    /**
     * 当前状态。
     *
     * @return 状态
     */
    SessionState state();

    /**
     * 最近一次失败原因。
     *
     * @return 原因，无则为 {@code null}
     */
    String lastError();

    /**
     * 设备名（用于 API 展示）。
     *
     * @return 设备名，未知时为空串
     */
    default String deviceName() {
        return "";
    }

    /**
     * 单元数量。
     *
     * @return 数量，未知时为 0
     */
    default int unitCount() {
        return 0;
    }

    /**
     * 当前是否有对端在线（主机已连接并完成 select）。
     *
     * <p>没有对端时"上报事件"是发不出去的，这不是服务器故障而是设备状态，
     * 调用方应据此给出"无主机会话"而不是"内部错误"。
     * </p>
     *
     * @return 有对端返回 {@code true}
     */
    default boolean hasPeer() {
        return false;
    }

    /**
     * 运行计数（已上报事件数、被限流数、已发 S6F1 等）。
     *
     * @return 计数
     */
    default Map<String, Long> counters() {
        return Map.of();
    }

    /**
     * 手动上报一个采集事件。
     *
     * <p>并非所有会话都支持（例如只做被动应答的场景），默认实现明确失败，
     * 而不是静默忽略——静默忽略会让调用方以为成功了。
     * </p>
     *
     * @param ceid CEID
     * @throws IOException 不支持、会话未打开或发送失败
     */
    default void emitCeid(int ceid) throws IOException {
        throw new IOException("session does not support collection events: " + this.id());
    }
}
