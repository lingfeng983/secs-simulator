package com.shimizukenta.secssim.api.http;

/**
 * 手动触发采集事件的结果。
 *
 * <p>HTTP 层据此映射状态码，而不是把 HTTP 码渗进上下文接口里。
 * </p>
 */
public enum TriggerOutcome {

    /** 已发送。 */
    OK,

    /** 找不到会话。 */
    UNKNOWN_SESSION,

    /** 会话未打开（或没有主机在线）。 */
    NOT_OPEN,

    /** 组装或发送失败。 */
    FAILED
}
