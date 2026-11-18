package com.shimizukenta.secssim.api.http;

/**
 * 新建会话的结果：结果码 + 服务端生成的会话 id。
 *
 * <p>会话 id 是 {@code ses_} + ULID，与配置名解耦；HTTP 层把它回给调用方，
 * 批量创建时也用它逐台报告真正建成的实例。</p>
 *
 * @param outcome   结果码
 * @param sessionId 新会话 id；失败时为空串
 */
public record SessionCreated(SessionOutcome outcome, String sessionId) {

    /** 失败结果（没有 id）。 */
    public static SessionCreated failed(SessionOutcome outcome) {
        return new SessionCreated(outcome, "");
    }
}
