package com.shimizukenta.secssim.app.session;

/**
 * 会话状态。
 */
public enum SessionState {

    /** 已创建，尚未打开端口。 */
    CREATED,

    /** 正在打开（绑定端口 / 建立连接）。 */
    OPENING,

    /** 端口已打开。 */
    OPEN,

    /** 正在关闭。 */
    CLOSING,

    /** 已关闭。 */
    CLOSED,

    /** 打开或运行失败。 */
    FAILED
}
