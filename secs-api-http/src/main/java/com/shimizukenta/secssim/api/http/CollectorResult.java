package com.shimizukenta.secssim.api.http;

/**
 * 采集器命令的结构化结果。
 *
 * <p>{@link CollectorOutcome} 决定 HTTP 状态码，{@code message} 在失败时
 * 带给界面看具体原因（经 JSON 转义后放在 {@code error} 字段）。</p>
 *
 * @param outcome 结果类别
 * @param message 失败原因；成功时为 {@code null}
 */
public record CollectorResult(CollectorOutcome outcome, String message) {

    /**
     * 校验并创建：非成功结果必须带原因。
     *
     * @param outcome 结果类别
     * @param message 失败原因
     */
    public CollectorResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome required");
        }
        if (outcome != CollectorOutcome.OK && (message == null || message.isBlank())) {
            throw new IllegalArgumentException("failed collector result requires a message");
        }
    }

    /** 成功。 */
    public static CollectorResult ok() {
        return new CollectorResult(CollectorOutcome.OK, null);
    }

    /** 非法配置。 */
    public static CollectorResult invalid(String message) {
        return new CollectorResult(CollectorOutcome.INVALID, message);
    }

    /** 未配置。 */
    public static CollectorResult notConfigured(String message) {
        return new CollectorResult(CollectorOutcome.NOT_CONFIGURED, message);
    }
}
