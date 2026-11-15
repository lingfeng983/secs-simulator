package com.shimizukenta.secssim.api.http;

/**
 * {@link ApiContext} 的观测域：曲线预览、报文历史、审计、工作台聚合与压测。
 *
 * <p>从 {@code ApiContext} 按职责拆出，方法集合与默认语义保持不变。</p>
 */
public interface ApiContextObservationOps {

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

    /**
     * 曲线预览（schema v6:SVID 过滤 + 事件标记）。
     *
     * @param sessionId  会话标识
     * @param fromMs     起始
     * @param toMs       结束
     * @param buckets    桶数
     * @param svids      只画这些 SVID;空/null 不过滤
     * @param withEvents 是否附带编排重放的事件标记
     * @return JSON 文本；会话不存在时返回 {@code null}
     */
    default String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                               java.util.Set<Integer> svids, boolean withEvents) {
        return previewJson(sessionId, fromMs, toMs, buckets);
    }

    /**
     * 曲线预览，可只回放某一条编排计划。
     *
     * @param sessionId  会话标识
     * @param fromMs     起始
     * @param toMs       结束
     * @param buckets    桶数
     * @param svids      只画这些 SVID;空/null 不过滤
     * @param withEvents 是否附带事件标记
     * @param planId     只回放这个计划；空/null 表示全部
     * @return JSON 文本；会话不存在时返回 {@code null}
     */
    default String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                               java.util.Set<Integer> svids, boolean withEvents, String planId) {
        return previewJson(sessionId, fromMs, toMs, buckets, svids, withEvents);
    }

    /**
     * 曲线预览的二进制正文。
     *
     * @param sessionId 会话标识
     * @param fromMs    起始
     * @param toMs      结束
     * @param buckets   桶数
     * @return 二进制正文；会话不存在或不支持时返回 {@code null}
     */
    default byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
        return null;
    }

    /**
     * 最近报文历史（JSON）。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @return JSON 文本；报文日志不可用时返回 {@code null}
     */
    default String messagesJson(int limit, String sessionId, String direction) {
        return null;
    }

    /**
     * 最近报文历史（JSON,带游标与报文类型过滤）。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @param beforeMs  只取时间早于该值的消息；0 表示不限制
     * @param stream    只取这个 Stream；null 表示全部
     * @param function  只取这个 Function；null 表示全部
     * @return JSON 文本；报文日志不可用时返回 {@code null}
     */
    default String messagesJson(int limit, String sessionId, String direction,
                                long beforeMs, Integer stream, Integer function) {
        return this.messagesJson(limit, sessionId, direction);
    }

    /**
     * 最近报文历史（JSON Lines），供导出。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @return 每行一个 JSON 对象；报文日志不可用时返回 {@code null}
     */
    default String messagesJsonl(int limit, String sessionId, String direction) {
        return null;
    }

    /**
     * 最近报文历史（JSON Lines,带游标与报文类型过滤）。
     *
     * @param limit     最多返回条数
     * @param sessionId 只取这个会话；空串表示全部
     * @param direction {@code in} / {@code out}；空串表示全部
     * @param beforeMs  只取时间早于该值的消息；0 表示不限制
     * @param stream    只取这个 Stream；null 表示全部
     * @param function  只取这个 Function；null 表示全部
     * @return 每行一个 JSON 对象；报文日志不可用时返回 {@code null}
     */
    default String messagesJsonl(int limit, String sessionId, String direction,
                                 long beforeMs, Integer stream, Integer function) {
        return this.messagesJsonl(limit, sessionId, direction);
    }

    /**
     * 最近审计记录。
     *
     * @param limit 最多返回条数
     * @return JSON 文本；审计不可用时返回 {@code null}
     */
    default String auditJson(int limit) {
        return null;
    }

    /**
     * 最近审计记录（带服务端过滤）。
     *
     * @param limit  最多返回条数
     * @param action 只取这个动作;空串表示全部
     * @param actor  只取这个操作者;空串表示全部
     * @return JSON 文本；审计不可用时返回 {@code null}
     */
    default String auditJson(int limit, String action, String actor) {
        return this.auditJson(limit);
    }

    /**
     * 最近审计记录（带服务端过滤与 before 游标）。
     *
     * @param limit    最多返回条数
     * @param action   只取这个动作;空串表示全部
     * @param actor    只取这个操作者;空串表示全部
     * @param beforeMs 只取时间早于该值的记录;0 表示不限制
     * @return JSON 文本；审计不可用时返回 {@code null}
     */
    default String auditJson(int limit, String action, String actor, long beforeMs) {
        return this.auditJson(limit, action, actor);
    }

    /**
     * 工作台聚合（schema v6）：全部会话的状态/速率/计数片段。
     *
     * @return {@code {"sessions":[...]}}
     */
    default String overviewJson() {
        return null;
    }

    /**
     * 服务端压测（schema v6）：同一条报文/事件在服务端循环发送。
     *
     * @param sessionId   会话标识
     * @param requestJson {@code {"kind":"message"|"ceid"|"alarm",...}}
     * @return 统计 JSON
     */
    default String burst(String sessionId, String requestJson) {
        return null;
    }

    /**
     * 主机实例级压测：以某台主机的身份连发同一条 SML。
     *
     * <p>与 {@link #burst(String, String)} 共用执行器与预算，但目标是主机实例而不是
     * 设备会话——远程主机（对端不在本进程）只有这一条路能压测。</p>
     *
     * @param hostId      主机实例 id
     * @param requestJson {@code {"kind":"message","sml":"...","count":100,"intervalMs":0}}；
     *                    带 {@code "status":true} 时只回状态
     * @return 受理/状态 JSON；实例不存在返回 {@code null}（HTTP 404）
     */
    default String burstHost(String hostId, String requestJson) {
        return null;
    }
}