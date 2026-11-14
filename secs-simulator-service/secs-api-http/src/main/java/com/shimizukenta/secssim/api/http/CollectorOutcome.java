package com.shimizukenta.secssim.api.http;

/**
 * 采集器命令（下发配置 / 手动启停）的结果。
 *
 * <p>以前这些接口回一串 JSON 文本，HTTP 层用
 * {@code result.contains("\"ok\":true")} 猜成败：错误消息里一旦出现这个子串
 * （比如 SML 回显、配置内容引用）就会把失败报成 200。语义必须是结构化的。</p>
 */
public enum CollectorOutcome {

    /** 成功。 */
    OK,

    /** 主机实例不存在。 */
    UNKNOWN_HOST,

    /** 采集器未配置（手动启停时没有配置可用）。 */
    NOT_CONFIGURED,

    /** 配置内容非法。 */
    INVALID,
}
