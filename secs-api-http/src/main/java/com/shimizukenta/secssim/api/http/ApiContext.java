package com.shimizukenta.secssim.api.http;

/**
 * HTTP 层需要的运行时信息。
 *
 * <p>把"我是什么形态、版本多少、有哪些附加字段"抽成接口，
 * 使 HTTP 层不认识运行时的具体实现，运行时不认识 HTTP 的具体细节。
 * </p>
 */
public interface ApiContext {

    /**
     * 运行形态标识。
     *
     * @return 如 {@code integrated}
     */
    String mode();

    /**
     * 版本号。
     *
     * @return 版本
     */
    String version();

    /**
     * 追加到 health JSON 里的字段，形如 {@code ,"hsmsPort":15000}。
     *
     * @return 附加字段，无则为空串
     */
    String extraHealthJson();

    /**
     * 请求优雅停机。
     */
    void requestShutdown();

    /**
     * 会话列表的 JSON 数组（由运行时组装，HTTP 层只做转发）。
     *
     * @return JSON 数组文本
     */
    default String sessionsJson() {
        return "[]";
    }

    /**
     * 手动触发一个采集事件。
     *
     * @param sessionId 会话标识
     * @param ceid      CEID
     * @return 结果
     */
    default TriggerOutcome triggerCeid(String sessionId, int ceid) {
        return TriggerOutcome.UNKNOWN_SESSION;
    }

    /**
     * 曲线预览（分桶 min/max/avg）。
     *
     * @param sessionId 会话标识
     * @param fromMs    起始
     * @param toMs      结束
     * @param buckets   桶数
     * @return JSON 文本；会话不存在时返回 {@code null}
     */
    default String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
        return null;
    }
}
