package com.shimizukenta.secssim.api.http;

import java.io.IOException;

/**
 * {@link ApiContext} 的主机域：主机实例（v2）生命周期与收发、主机模板与配置区方案、
 * 主机模拟器（v1 会话维度）与 S1F3 采集器。
 *
 * <p>从 {@code ApiContext} 按职责拆出，方法集合与默认语义保持不变；
 * 嵌套类型 {@link ApiContext.HostOpenResult} 仍留在 {@code ApiContext} 上。</p>
 */
public interface ApiContextHostOps {

    /* ---------- 3.0 v2：主机独立命名空间（设计 §2.5） ---------- */

    /** v2：主机实例列表（含持久行与内存相位）。 */
    default String hostsV2Json() {
        return "{\"ok\":false,\"error\":\"not-supported\"}";
    }

    /** v2：新建主机（默认立即连接）。 */
    default ApiContext.HostOpenResult openHostV2(String name, String host, int port, int deviceId,
                                                boolean autoReconnect) {
        return ApiContext.HostOpenResult.failed(SessionOutcome.FAILED);
    }

    /**
     * v2：新建主机，可选是否立即建链（界面「立即连接」开关）。
     *
     * <p>默认实现退回旧重载：不支持开关的形态（如测试桩）保持原行为。</p>
     *
     * @param connect {@code false} 时只建实例不连接，停在断开态
     */
    default ApiContext.HostOpenResult openHostV2(String name, String host, int port, int deviceId,
                                                boolean autoReconnect, boolean connect) {
        return openHostV2(name, host, port, deviceId, autoReconnect);
    }

    /** v2：单主机状态。 */
    default String hostV2Json(String hostId) {
        return null;
    }

    /** v2：保存主机设置（名称/目标；settingRevision +1，需「使设置生效」）。 */
    default SessionOutcome saveHostV2(String hostId, String name, String host, int port, int deviceId) {
        return SessionOutcome.FAILED;
    }

    /** v2：删除主机。 */
    default SessionOutcome destroyHostV2(String hostId) {
        return SessionOutcome.FAILED;
    }

    /** v2：连接 / 断开 / 使设置生效。 */
    default SessionOutcome hostConnectV2(String hostId) {
        return SessionOutcome.FAILED;
    }

    default SessionOutcome hostDisconnectV2(String hostId) {
        return SessionOutcome.FAILED;
    }

    default SessionOutcome hostApplyV2(String hostId) {
        return SessionOutcome.FAILED;
    }

    /** v2：以主机角色发送 SML。 */
    default String hostSendV2(String hostId, String sml) {
        return null;
    }

    /** v2：从目标读取变量目录。 */
    default String hostVariablesRefreshV2(String hostId) {
        return "{\"ok\":false,\"error\":\"not-supported\"}";
    }

    /** v2：声明变量解码类型。 */
    default boolean hostVariableDeclareV2(String hostId, int svid, String type) {
        return false;
    }

    /** v2：主机变量目录。 */
    default String hostVariablesV2(String hostId) {
        return null;
    }

    /** v2：主机模板列表 / 新建 / 更新 / 删除。 */
    default String hostTemplatesJson() {
        return "{\"ok\":false,\"error\":\"not-supported\"}";
    }

    /* ---------- v2：主机配置区方案库（Trace / S1F3 采集，落 config.db） ---------- */

    /**
     * v2：列出某台主机某一类配置区方案。
     *
     * @param hostId 主机实例 id
     * @param kind   {@code trace} / {@code collect}
     * @return JSON 数组文本；主机不存在返回 {@code null}
     */
    default String hostPlansV2Json(String hostId, String kind) {
        return null;
    }

    /**
     * v2：保存（upsert）一条配置区方案。
     *
     * @param hostId 主机实例 id
     * @param kind   {@code trace} / {@code collect}
     * @param planId 方案 id（客户端生成）
     * @param body   方案正文 JSON（前端结构原样）
     * @return 结果
     */
    default SessionOutcome saveHostPlanV2(String hostId, String kind, String planId, String body) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * v2：删除一条配置区方案。
     *
     * @param hostId 主机实例 id
     * @param kind   {@code trace} / {@code collect}
     * @param planId 方案 id
     * @return 结果（不存在回 NOT_FOUND 语义）
     */
    default SessionOutcome deleteHostPlanV2(String hostId, String kind, String planId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    default SessionOutcome createHostTemplateV2(String id, String name, String host, int port,
                                                int deviceId, String note) {
        return SessionOutcome.FAILED;
    }

    default SessionOutcome deleteHostTemplateV2(String id) {
        return SessionOutcome.FAILED;
    }

    /** v2：每主机收发记录（perspective=host）。 */
    default String hostMessagesV2(String hostId, int limit) {
        return "{\"ok\":false,\"error\":\"not-supported\"}";
    }

    /**
     * 列出全部主机实例。
     *
     * @return JSON；未实现返回 {@code null}
     */
    default String hostSimsJson() {
        return null;
    }

    /**
     * 按实例 id 读主机状态。
     *
     * @param hostId 实例 id
     * @return JSON；实例不存在返回 {@code null}
     */
    default String hostSimJsonById(String hostId) {
        return null;
    }

    /**
     * 按实例 id 关闭主机。
     *
     * @param hostId 实例 id
     * @return 结果
     */
    default SessionOutcome closeHostSimById(String hostId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * 按实例 id 以主机角色发送 SML。
     *
     * @param hostId 实例 id
     * @param sml    SML 文本
     * @return 结果 JSON；实例不存在返回 {@code null}
     */
    default String sendHostSmlById(String hostId, String sml) throws java.io.IOException {
        return null;
    }

    /**
     * 按实例 id 下发采集器配置。
     *
     * @param hostId     实例 id
     * @param requestJson 采集器配置 JSON
     * @return 结构化结果（HTTP 层据此定状态码，不再猜 JSON 文本）
     */
    default CollectorResult setHostSimCollectorById(String hostId, String requestJson) {
        return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
    }

    /**
     * 按实例 id 手动启停采集器。
     *
     * @param hostId 实例 id
     * @param start  启动/停止
     * @return 结构化结果
     */
    default CollectorResult hostSimCollectorCommandById(String hostId, boolean start) {
        return hostSimCollectorCommandById(hostId, start, null);
    }

    /**
     * 按实例 id 手动启停某一份采集计划的采集器。
     *
     * <p>一台主机可以挂多份采集器（按采集计划分开）：{@code planId} 指定要动哪一份，
     * 空/null 表示"全部已配置的计划"（老客户端的单份语义）。</p>
     *
     * @param hostId 实例 id
     * @param start  启动/停止
     * @param planId 采集计划 id；空/null = 全部
     * @return 结构化结果
     */
    default CollectorResult hostSimCollectorCommandById(String hostId, boolean start, String planId) {
        return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
    }

    /**
     * 按实例 id 读采集器状态。
     *
     * @param hostId 实例 id
     * @return JSON；实例不存在返回 {@code null}
     */
    default String hostSimCollectorJsonById(String hostId) {
        return null;
    }

    /**
     * 下发主机模拟 S1F3 采集器配置（schema v6）。
     *
     * @param sessionId   会话标识
     * @param requestJson {@code {"startCeid":..,"stopCeid":..,"intervalMs":..,"svids":[{"id":..}]}}
     * @return 结构化结果
     */
    default CollectorResult setHostSimCollector(String sessionId, String requestJson) {
        return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
    }

    /**
     * 手动启停采集器（正常流程由 CEID 的 S6F11 驱动）。
     *
     * @param sessionId 会话标识
     * @param start     {@code true} 启动 / {@code false} 停止
     * @return 结构化结果
     */
    default CollectorResult hostSimCollectorCommand(String sessionId, boolean start) {
        return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
    }

    /**
     * 采集器状态与数据。
     *
     * @param sessionId 会话标识
     * @return JSON；会话不存在返回 {@code null}
     */
    default String hostSimCollectorJson(String sessionId) {
        return null;
    }

    /**
     * 打开会话的主机模拟器连接（host 角色，用于发 S2F33/35/37 等主机方向报文）。
     *
     * @param sessionId 会话标识
     * @return 结果
     */
    default SessionOutcome openHostSim(String sessionId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * 打开主机模拟器连接，可指定远程 SECS 目标（schema v6）。
     *
     * @param sessionId  会话标识（工具实例宿主）
     * @param targetJson {@code null}/空 连本机;否则 {@code {"host":"...","port":5000,"deviceId":0}}
     * @return 结果
     */
    default SessionOutcome openHostSim(String sessionId, String targetJson) {
        return openHostSim(sessionId);
    }

    /**
     * 打开主机模拟器连接，可指定实例 id（多主机场景）。
     *
     * @param sessionId  会话标识
     * @param targetJson 远程目标 JSON；{@code null}/空 连本机
     * @param hostId     实例 id；{@code null}/空 由实现生成
     * @return 结果
     */
    default SessionOutcome openHostSim(String sessionId, String targetJson, String hostId) {
        return openHostSim(sessionId, targetJson);
    }

    /**
     * 关闭会话的主机模拟器连接。
     *
     * @param sessionId 会话标识
     * @return 结果
     */
    default SessionOutcome closeHostSim(String sessionId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * 主机模拟器状态 JSON。
     *
     * @param sessionId 会话标识
     * @return {@code {"open":...}}；会话不存在返回 {@code null}
     */
    default String hostSimJson(String sessionId) {
        return null;
    }

    /**
     * 以主机角色发送一条 SML。
     *
     * @param sessionId 会话标识
     * @param sml       SML 文本
     * @return 结果 JSON；会话不存在返回 {@code null}
     * @throws IOException 发送失败
     */
    default String sendHostSml(String sessionId, String sml) throws IOException {
        return null;
    }
}