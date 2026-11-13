package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.List;

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
     * 当前日志级别。
     *
     * @return 级别名（小写）
     */
    default String logLevel() {
        return com.shimizukenta.secssim.app.log.Log.level().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 运行时切换日志级别。
     *
     * @param level 级别名（error/info/debug，不分大小写）
     * @return 切换是否成功
     */
    default boolean setLogLevel(String level) {
        try {
            com.shimizukenta.secssim.app.log.Log.setLevel(
                    com.shimizukenta.secssim.app.log.Log.Level.parse(level));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

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
     * 置位/清除一个报警（S5F1）。
     *
     * @param sessionId 会话标识
     * @param alid      ALID
     * @param set       {@code true} 置位，{@code false} 清除
     * @param text      报警文本，空串用默认文本
     * @param category  类别 0..7
     * @return 结果
     */
    default TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set, String text, int category) {
        return TriggerOutcome.UNKNOWN_SESSION;
    }

    /**
     * 会话的活动报警 JSON；会话不存在时返回 {@code null}。
     *
     * @param sessionId 会话标识
     * @return JSON 数组文本或 {@code null}
     */
    default String alarmsJson(String sessionId) {
        return null;
    }

    /**
     * 会话的终端消息 JSON；会话不存在时返回 {@code null}。
     *
     * @param sessionId 会话标识
     * @return JSON 数组文本或 {@code null}
     */
    default String terminalMessagesJson(String sessionId) {
        return null;
    }

    /**
     * 会话的 SVID / RPTID / CEID 目录 JSON；会话不存在时返回 {@code null}。
     *
     * <p>界面靠它把"2001 到底是哪个事件"讲清楚，不用让人猜。
     * </p>
     *
     * @param sessionId 会话标识
     * @return JSON 文本或 {@code null}
     */
    default String catalogJson(String sessionId) {
        return null;
    }

    /**
     * 事件编排列表。
     *
     * @param sessionId 会话标识
     * @return JSON 文本；会话不存在时返回 {@code null}
     */
    default String eventsJson(String sessionId) {
        return null;
    }

    /**
     * 运行时启用/禁用一个编排计划。
     *
     * @param sessionId 会话标识
     * @param planId    计划标识
     * @param enabled   是否启用
     * @return 结果
     */
    default EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
        return EventOutcome.UNKNOWN_SESSION;
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

    /* ==================================================================
     * 运行时编辑(设备页·事件/报告/状态变量)。返回 HTTP 状态码:
     * 200/201 成功、400 请求不合法(解析失败)、404 会话或目标不存在、409 冲突。
     * ================================================================== */

    /** 新增编排计划;planJson 形如 {"id","ceid","trigger":{...},"effects":[...]}。 */
    default int addEventPlan(String sessionId, String planJson) {
        return 404;
    }

    /** 更新编排计划。 */
    default int updateEventPlan(String sessionId, String planId, String planJson) {
        return 404;
    }

    /** 删除编排计划。 */
    default int removeEventPlan(String sessionId, String planId) {
        return 404;
    }

    /** 新增报告;reportJson 形如 {"id","slot","svids":[...]}。 */
    default int addReport(String sessionId, String reportJson) {
        return 404;
    }

    /** 更新报告。 */
    default int updateReport(String sessionId, int rptid, String reportJson) {
        return 404;
    }

    /** 删除报告。 */
    default int removeReport(String sessionId, int rptid) {
        return 404;
    }

    /** 新增采集事件;ceidJson 形如 {"id","slot","reports":[...]}。 */
    default int addCeid(String sessionId, String ceidJson) {
        return 404;
    }

    /** 更新采集事件(符号名/绑定报告)。 */
    default int updateCeid(String sessionId, int ceid, String ceidJson) {
        return 404;
    }

    /** 删除采集事件。 */
    default int removeCeid(String sessionId, int ceid) {
        return 404;
    }

    /** 新增状态变量;svidJson 形如 {"id","slot","type","owner"?,"unit"?}。 */
    default int addSvid(String sessionId, String svidJson) {
        return 404;
    }

    /** 更新状态变量。 */
    default int updateSvid(String sessionId, int svid, String svidJson) {
        return 404;
    }

    /** 删除状态变量。 */
    default int removeSvid(String sessionId, int svid) {
        return 404;
    }

    /** 「设定」状态变量的值(valueJson 形如 {"value":1.25})。 */
    default int pinSvidValue(String sessionId, int svid, String valueJson) {
        return 404;
    }

    /** 取消「设定」。 */
    default int unpinSvidValue(String sessionId, int svid) {
        return 404;
    }

    /**
     * 手动发送一条 SML 报文。
     *
     * @param sessionId 会话标识
     * @param sml       SML 文本
     * @return 发送结果 JSON；会话不存在时返回 {@code null}
     * @throws IllegalArgumentException SML 语法错误
     * @throws IllegalStateException    会话未打开或没有对端
     * @throws IOException              发送失败
     */
    default String sendSml(String sessionId, String sml) throws IOException {
        return null;
    }

    /**
     * 新建一个会话。
     *
     * @param configName {@code demo} 或配置目录下的文件名
     * @return 结果
     */
    default SessionOutcome createSession(String configName) {
        return createSession(SessionCreateRequest.of(configName));
    }

    /**
     * 新建一个会话，允许用部署期覆盖替换配置里的连接标识。
     *
     * <p>默认实现忽略覆盖、按配置原文建——不支持覆盖的形态（如 CLI 演示）自然退化。</p>
     *
     * @param request 配置名 + 部署期覆盖
     * @return 结果
     */
    default SessionOutcome createSession(SessionCreateRequest request) {
        /* 不带覆盖时委托给旧的 String 重载：已有实现（含测试桩）不用跟着改 */
        if (request.sessionId() == null && request.bindHost() == null
                && request.port() == null && request.deviceId() == null) {
            return createSession(request.config());
        }
        return SessionOutcome.FAILED;
    }

    /**
     * 关闭并移除一个会话。
     *
     * @param sessionId 会话标识
     * @return 结果
     */
    default SessionOutcome closeSession(String sessionId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * 按配置重新装载一个会话（热重载）。
     *
     * @param sessionId 会话标识
     * @return 结果
     */
    default SessionOutcome reloadSession(String sessionId) {
        return SessionOutcome.UNKNOWN_SESSION;
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

    /** v2：设备配置版本历史（3.0 config_revision 快照）。 */
    default String configRevisionsV2(String name, int limit) {
        return null;
    }

    /* ---------- 3.0 M4：事件编排执行台账（设计 §2.5 / §2.7） ---------- */

    /**
     * v2：执行台账查询。
     *
     * @param deviceId 设备 id（ULID 或 legacy stem）
     * @param limit    条数上限
     * @param planId   计划过滤；null = 全部
     * @param status   状态过滤；null = 全部
     * @param dryRun   {@code all} / {@code 1} / {@code true} 才含单步试跑记录
     * @return JSON 文本；设备不存在返回 null
     */
    default String planExecutionsV2(String deviceId, int limit, String planId,
                                    String status, String dryRun) {
        return null;
    }

    /**
     * v2：单步试跑一条计划（台账 dry_run=1，不计 fire_count、不推进倒计时）。
     *
     * @param deviceId 设备 id
     * @param planId   计划 id
     * @return 结果
     */
    default TriggerOutcome stepPlanV2(String deviceId, String planId) {
        return TriggerOutcome.UNKNOWN_SESSION;
    }

    /**
     * v2：运行时启停计划，并持久化到 runtime.db。
     *
     * @param deviceId 设备 id
     * @param planId   计划 id
     * @param enabled  是否启用
     * @return 结果
     */
    default SessionOutcome setPlanEnabledV2(String deviceId, String planId, boolean enabled) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /* ---------- 3.0 v2：设备链路（设计 §2.5） ---------- */

    /**
     * v2：设备链路相位（device_link 行 + 内存相位）。
     *
     * @param deviceId 设备 id（ULID 或 v1 legacy stem，两者等价）
     * @return JSON；设备不存在返回 {@code null}
     */
    default String deviceLinkV2Json(String deviceId) {
        return null;
    }

    /**
     * v2：打开设备链路（被动 = 监听，主动 = 建链）。
     *
     * <p>与 {@link #createSession} 的区别：会话必须已经存在，这里只动链路，
     * 配置、模型、钉值、计划启用态全部保留。</p>
     *
     * @param deviceId 设备 id
     * @return 结果
     */
    default SessionOutcome deviceConnectV2(String deviceId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * v2：只断链不销毁设备（断开后设备仍留在列表里，可再连）。
     *
     * @param deviceId 设备 id
     * @return 结果
     */
    default SessionOutcome deviceDisconnectV2(String deviceId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * v2：按配置重载设备（等价于 v1 的会话重载）。
     *
     * @param deviceId 设备 id
     * @return 结果
     */
    default SessionOutcome deviceReloadV2(String deviceId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /* ---------- 3.0 v2：主机独立命名空间（设计 §2.5） ---------- */

    /** v2：主机实例列表（含持久行与内存相位）。 */
    default String hostsV2Json() {
        return "{\"ok\":false,\"error\":\"not-supported\"}";
    }

    /** v2：新建主机。 */
    default SessionOutcome openHostV2(String name, String host, int port, int deviceId, boolean autoReconnect) {
        return SessionOutcome.FAILED;
    }

    /** v2：新主机 id（openHostV2 成功后读取；进程内最新一次创建）。 */
    default String lastCreatedHostId() {
        return "";
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

    /**
     * 配置目录里的配置文件名列表 JSON。
     *
     * @return JSON 数组文本
     */
    default String configsJson() {
        return "[]";
    }

    /**
     * 读一份配置的原始文本。
     *
     * <p>给的是<b>原文</b>而不是模型反序列化结果：编辑器必须看到用户写的每一个键。
     * </p>
     *
     * @param name 文件名
     * @return 原文；不存在时返回 {@code null}
     */
    default String configText(String name) {
        return null;
    }

    /**
     * 配置正文 + 修改时间的一次性快照。
     *
     * @param text       原文
     * @param modifiedMs 修改时间（epoch 毫秒）；{@code null} 表示取不到
     */
    record ConfigSnapshot(String text, Long modifiedMs) {
    }

    /**
     * 一次读取配置正文与修改时间。
     *
     * <p>编辑器保存时把 {@code modifiedMs} 作为 If-Match 带回。分两次取会出现
     * 「旧正文 + 新 mtime」：保存时被判为"没有人改过"，于是<b>绕过</b>并发保护、
     * 覆盖掉别人的修改。默认实现是兼容旧调用方的退化版本，运行时实现应一次性读。</p>
     *
     * @param name 文件名
     * @return 快照；文件不存在返回 {@code null}
     */
    default ConfigSnapshot configSnapshot(String name) {
        String text = configText(name);
        if (text == null) {
            return null;
        }
        return new ConfigSnapshot(text, configModifiedMs(name));
    }

    /**
     * 校验一段配置文本（不落盘）。
     *
     * @param text JSON 文本
     * @return 问题清单；空表示合法
     */
    default List<String> validateConfig(String text) {
        return List.of("当前形态不支持配置校验");
    }

    /**
     * 新建一份配置（内容是能直接编译的骨架）。
     *
     * @param name 文件名
     * @return 结果
     */
    default ConfigResult createConfig(String name) {
        return ConfigResult.notFound("当前形态不支持新建配置");
    }

    /**
     * 内置设备模板目录的 JSON 数组。
     *
     * @return JSON 文本（{@code [{id,label,description},…]}）
     */
    default String configTemplatesJson() {
        return "[]";
    }

    /**
     * 按内置设备模板新建一份配置。
     *
     * <p>默认实现忽略模板退回骨架：旧实现只认骨架，语义仍成立。</p>
     *
     * @param name       文件名
     * @param templateId 模板标识；未知模板按非法请求返回问题清单
     * @return 结果
     */
    default ConfigResult createConfig(String name, String templateId) {
        return this.createConfig(name);
    }

    /** 全部模板（内置 + 自定义）的 JSON 数组。 */
    default String templatesJson() {
        return this.configTemplatesJson();
    }

    /** 模板原文；不存在返回空。 */
    default java.util.Optional<String> templateText(String id) {
        return java.util.Optional.empty();
    }

    /** 新建/更新自定义模板。 */
    default ConfigResult saveTemplate(String id, String text) {
        return ConfigResult.notFound("当前形态不支持模板管理");
    }

    /** 删除自定义模板。 */
    default ConfigResult deleteTemplate(String id) {
        return ConfigResult.notFound("当前形态不支持模板管理");
    }

    /**
     * 保存一份配置。
     *
     * <p>实现必须<b>先校验再落盘</b>：存一份编译不过的配置等于给下一次启动埋雷。
     * </p>
     *
     * @param name 文件名
     * @param text JSON 文本
     * @return 结果（带问题清单）
     */
    default ConfigResult saveConfig(String name, String text) {
        return ConfigResult.notFound("当前形态不支持保存配置");
    }

    /**
     * 取一份配置的最后修改时间（epoch 毫秒）。
     *
     * <p>与 {@link #saveConfig(String, String, Long)} 配合做保存时的并发保护；
     * 取不到（不支持或文件不存在）返回 {@code null}。</p>
     *
     * @param name 文件名
     * @return 修改时间；未知时 {@code null}
     */
    default Long configModifiedMs(String name) {
        return null;
    }

    /**
     * 保存一份配置（带并发保护）。
     *
     * <p>{@code expectedModifiedMs} 非空且与当前文件不一致时必须拒绝覆盖，
     * 返回 CONFLICT——否则两个编辑窗口会互相静默覆盖。</p>
     *
     * @param name               文件名
     * @param text               JSON 文本
     * @param expectedModifiedMs 期望的最后修改时间；null 表示不检查
     * @return 结果（带问题清单）
     */
    default ConfigResult saveConfig(String name, String text, Long expectedModifiedMs) {
        return this.saveConfig(name, text);
    }

    /** 按配置<b>草稿文本</b>算曲线预览（不落盘、不需要运行中的会话）。返回 null 表示编译失败。 */
    default String configPreviewJson(String text, long fromMs, long toMs, int buckets) {
        return null;
    }

    /**
     * 草稿预览（schema v6:SVID 过滤 + 模拟事件时间线,效果叠加进曲线）。
     *
     * @param text       配置草稿原文
     * @param fromMs     起始（模拟时钟）
     * @param toMs       结束
     * @param buckets    桶数
     * @param svids      只画这些 SVID;空/null 不过滤
     * @param withEvents 是否模拟事件时间线并叠加效果
     * @return JSON 文本；编译失败返回 {@code null}
     */
    default String configPreviewJson(String text, long fromMs, long toMs, int buckets,
                                     java.util.Set<Integer> svids, boolean withEvents) {
        return configPreviewJson(text, fromMs, toMs, buckets);
    }
}
