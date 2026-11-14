package com.shimizukenta.secssim.api.http;

/**
 * 会话生命周期操作的结果。
 *
 * <p>与 {@link TriggerOutcome} 分开：触发失败重试和"这个会话不能重载"不是一回事，
 * 混成一个枚举迟早会漏掉其中一种的映射。
 * </p>
 */
public enum SessionOutcome {

    /** 成功。 */
    OK,

    /** 没有这个会话。 */
    UNKNOWN_SESSION,

    /** 会话在，但没有配置来源（例如内置演示会话），因此不能重载。 */
    NOT_RELOADABLE,

    /** 会话在，但链路没建立（没有主机），不能上线/下线。 */
    NOT_CONNECTED,

    /** 标识已被占用。 */
    CONFLICT,

    /** 其它失败：端口被占、配置非法、编译不过。 */
    FAILED
}
