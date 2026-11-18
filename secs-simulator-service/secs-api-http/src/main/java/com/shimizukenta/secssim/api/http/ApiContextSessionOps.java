package com.shimizukenta.secssim.api.http;

import java.io.IOException;

/**
 * {@link ApiContext} 的会话域：会话生命周期、事件/报警触发与设备运行时（v2）操作。
 *
 * <p>从 {@code ApiContext} 按职责拆出，方法集合与默认语义保持不变。</p>
 */
public interface ApiContextSessionOps {

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
        return createSession(SessionCreateRequest.of(configName)).outcome();
    }

    /**
     * 新建一个会话，允许用部署期覆盖替换配置里的连接标识。
     *
     * <p>默认实现忽略覆盖、按配置原文建——不支持覆盖的形态（如 CLI 演示）自然退化。
     * 会话 id 由运行时生成，随结果一起返回。</p>
     *
     * @param request 配置名 + 部署期覆盖
     * @return 结果（含新会话 id）
     */
    default SessionCreated createSession(SessionCreateRequest request) {
        /* 不带覆盖时委托给旧的 String 重载：已有实现（含测试桩）不用跟着改 */
        if (request.bindHost() == null && request.port() == null && request.deviceId() == null) {
            return new SessionCreated(createSession(request.config()), "");
        }
        return SessionCreated.failed(SessionOutcome.FAILED);
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

    /** v2：设备配置版本历史（3.0 config_revision 快照）。 */
    default String configRevisionsV2(String name, int limit) {
        return null;
    }

    /* ---------- 3.0 M4：事件编排执行台账（设计 §2.5 / §2.7） ---------- */

    /**
     * v2：执行台账查询。
     *
     * @param deviceId 设备 id（{@code dev_} ULID）或配置名 stem
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
     * v2：清空某台设备的执行台账（界面「清理历史」按钮）。
     *
     * @param deviceId 设备 id
     * @return 结果（设备不存在回 UNKNOWN_SESSION 语义）
     */
    default SessionOutcome clearExecutionsV2(String deviceId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /**
     * v2：运行时启停计划，并持久化到 runtime.db。
     *
     * <p>启用方向有 ONLINE 门控（2026-11-16 设计 §2.2）：设备离线回
     * {@code NOT_ONLINE}（HTTP 409 not-online）。</p>
     *
     * @param deviceId 设备 id
     * @param planId   计划 id
     * @param enabled  是否启用
     * @return 结果
     */
    default SessionOutcome setPlanEnabledV2(String deviceId, String planId, boolean enabled) {
        return SessionOutcome.UNKNOWN_SESSION;
    }

    /** v2：工艺周期运行态 JSON（M3）；设备不在返回 {@code null}。 */
    default String cycleJsonV2(String deviceId) {
        return null;
    }

    /** v2：启动工艺周期——ONLINE 门控同计划启停；没有 cycle 段回 FAILED。 */
    default SessionOutcome cycleStartV2(String deviceId) {
        return SessionOutcome.FAILED;
    }

    /** v2：停止工艺周期（禁用方向不受 ONLINE 限制）。 */
    default SessionOutcome cycleStopV2(String deviceId) {
        return SessionOutcome.OK;
    }

    /**
     * 分段生效的结果：HTTP 状态码 + 已拼好的 JSON 正文。
     *
     * @param status HTTP 状态
     * @param json   响应体
     */
    record SectionsApplyResult(int status, String json) {
    }

    /**
     * v2：把刚保存的配置段应用到正在跑的这台设备（2026-11-18 设计 §4.4）。
     *
     * @param deviceId 设备（会话）标识
     * @param sections 变化段名
     * @return 状态码 + JSON
     */
    default SectionsApplyResult applySectionsV2(String deviceId, java.util.List<String> sections) {
        return new SectionsApplyResult(405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /* ---------- 3.0 v2：设备运行时（设计 §2.5） ---------- */

    /**
     * v2：计划列表 + 运行统计（启用态、触发次数、上次触发/跳过原因）。
     *
     * @param deviceId 设备 id
     * @return JSON 文本；设备不存在返回 {@code null}
     */
    default String plansV2Json(String deviceId) {
        return null;
    }

    /**
     * v2：运行时新增计划（写 {@code runtime_plan_override}，不动配置）。
     *
     * @param deviceId 设备 id
     * @param planJson 计划正文
     * @return HTTP 状态码：201 / 404 / 409 / 400
     */
    default int addPlanV2(String deviceId, String planJson) {
        return 404;
    }

    /**
     * v2：运行时改写计划正文。
     *
     * @param deviceId 设备 id
     * @param planId   计划 id
     * @param planJson 计划正文
     * @return HTTP 状态码：200 / 404 / 400
     */
    default int updatePlanV2(String deviceId, String planId, String planJson) {
        return 404;
    }

    /**
     * v2：运行时删除计划。
     *
     * @param deviceId 设备 id
     * @param planId   计划 id
     * @return HTTP 状态码：200 / 404
     */
    default int removePlanV2(String deviceId, String planId) {
        return 404;
    }

    /**
     * v2：目录新增（{@code kind = ceid | report | svid}）。
     *
     * @param deviceId 设备 id
     * @param kind     目录种类
     * @param bodyJson 条目正文
     * @return HTTP 状态码：201 / 400 / 404 / 409
     */
    default int addCatalogV2(String deviceId, String kind, String bodyJson) {
        return 404;
    }

    /**
     * v2：目录改写。
     *
     * @param deviceId 设备 id
     * @param kind     目录种类
     * @param key      CEID / RPTID / SVID
     * @param bodyJson 条目正文
     * @return HTTP 状态码：200 / 400 / 404
     */
    default int updateCatalogV2(String deviceId, String kind, int key, String bodyJson) {
        return 404;
    }

    /**
     * v2：目录删除。
     *
     * @param deviceId 设备 id
     * @param kind     目录种类
     * @param key      CEID / RPTID / SVID
     * @return HTTP 状态码：200 / 404
     */
    default int removeCatalogV2(String deviceId, String kind, int key) {
        return 404;
    }

    /**
     * v2：钉住一个 SVID 的值。
     *
     * @param deviceId  设备 id
     * @param svid      状态变量号
     * @param valueJson 值正文
     * @return HTTP 状态码：200 / 400 / 404
     */
    default int pinSvidV2(String deviceId, int svid, String valueJson) {
        return 404;
    }

    /**
     * v2：取消钉值。
     *
     * @param deviceId 设备 id
     * @param svid     状态变量号
     * @return HTTP 状态码：200 / 404
     */
    default int unpinSvidV2(String deviceId, int svid) {
        return 404;
    }

    /**
     * v2：手动发送 SML。
     *
     * @param deviceId 设备 id
     * @param sml      SML 原文
     * @return 发送结果 JSON；设备不存在返回 {@code null}
     * @throws IllegalArgumentException SML 解析失败
     * @throws IllegalStateException    链路未建立
     * @throws java.io.IOException      协议层发送失败
     */
    default String sendSmlV2(String deviceId, String sml)
            throws java.io.IOException {
        return null;
    }

    /**
     * v2：手动触发采集事件（台账记 {@code plan_id='manual'}、{@code dry_run=0}）。
     *
     * @param deviceId 设备 id
     * @param ceid     采集事件号
     * @return 结果
     */
    default TriggerOutcome triggerCeidV2(String deviceId, int ceid) {
        return TriggerOutcome.UNKNOWN_SESSION;
    }

    /**
     * v2：报警置位 / 清除。
     *
     * @param deviceId 设备 id
     * @param alid     报警号
     * @param set      true = 置位，false = 清除
     * @param text     报警文本（US-ASCII）
     * @param category 报警类别 1..7
     * @return 结果
     */
    default TriggerOutcome triggerAlarmV2(String deviceId, int alid, boolean set,
                                          String text, int category) {
        return TriggerOutcome.UNKNOWN_SESSION;
    }

    /* ---------- 3.0 v2：设备链路（设计 §2.5） ---------- */

    /**
     * v2：设备链路相位（device_link 行 + 内存相位）。
     *
     * @param deviceId 设备 id（{@code dev_} ULID）或配置名 stem
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
     * v2：手动把设备置为在线 / 离线（GEM 里对应 S1F17 / S1F15）。
     *
     * <p>只改控制状态，不动链路：离线后 S6F11 / S6F1 立刻停发，在线后按计划
     * 规则恢复。链路没建立时返回 {@link SessionOutcome#NOT_CONNECTED}——
     * ONLINE 是"对某台主机"而言的，没有主机就没有可上的线。</p>
     *
     * @param deviceId 设备 id
     * @param online   是否在线
     * @return 结果
     */
    default SessionOutcome deviceOnlineV2(String deviceId, boolean online) {
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

    /**
     * v2：销毁设备会话（{@code DELETE /api/v2/devices/{id}}）。
     *
     * <p>与 {@link #deviceDisconnectV2} 的区别：断开只放链路，销毁要释放端口、
     * 把会话从注册表移除，并清掉钉值 / 计划覆盖 / 目录编辑等运行态。
     * 设备实体与配置正文保留（那是 {@code DELETE /api/v1/configs/{name}} 的事）。</p>
     *
     * @param deviceId 设备 id
     * @return 结果
     */
    default SessionOutcome destroyDeviceV2(String deviceId) {
        return SessionOutcome.UNKNOWN_SESSION;
    }
}