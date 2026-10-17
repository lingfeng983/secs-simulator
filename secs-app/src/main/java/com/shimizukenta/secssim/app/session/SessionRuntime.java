package com.shimizukenta.secssim.app.session;

import java.io.IOException;

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
}
