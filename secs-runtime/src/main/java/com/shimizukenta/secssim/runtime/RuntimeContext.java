package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.util.List;

import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.EventOutcome;
import com.shimizukenta.secssim.api.http.SessionCreateRequest;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.api.http.TriggerOutcome;
import com.shimizukenta.secssim.app.audit.AuditLog;
import com.shimizukenta.secssim.app.event.MessageLog;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.store.ConfigRepository;
import com.shimizukenta.secssim.store.HostRepository;
import com.shimizukenta.secssim.store.PlanExecutionRepository;
import com.shimizukenta.secssim.store.RuntimeStateRepository;

/**
 * 运行时上下文：{@code ApiContext} 的门面，也是停机时统一关闭后台资源的入口。
 *
 * <p>原来这个类一千二百多行，同时管着健康信息、会话查询、运行时编辑、主机模拟器、
 * 压测线程池和配置读写。现在按"谁拥有状态"拆成四个：</p>
 *
 * <ul>
 *   <li>{@link RuntimeApi}——会话/配置/报文/审计的接口落地（无状态，只查对象调方法）</li>
 *   <li>{@link HostSimRegistry}——主机模拟器的注册、查找、重连与关闭</li>
 *   <li>{@link BurstRunner}——压测线程池、预算与取消</li>
 *   <li>本类——健康信息、停机编排与 {@code ApiContext} 委托</li>
 * </ul>
 *
 * <p>启动流程不在这里：参数解析、播种配置、起 HTTP 服务都在 {@code Bootstrap}。</p>
 */
final class RuntimeContext implements ApiContext, AutoCloseable {

    private final String mode;
    private final String version;
    private final String extraHealthJson;
    private final Runnable shutdown;
    private final RuntimeApi api;
    private final HostSimRegistry hosts;
    private final com.shimizukenta.secssim.store.HostRepository hostRepository;
    private final MessageLog messageLog;
    private final ConfigStore configs;
    private final SessionManager sessions;
    private final ConfigRepository configRepository;
    private final PlanExecutionRepository planExecutions;
    private final RuntimeStateRepository linkState;
    private volatile String lastCreatedHostId = "";
    private final BurstRunner bursts;
    /** 运行态改动写穿到 runtime.db（钉值、计划启用态、目录编辑、链路相位）。 */
    private final RuntimePersister persister;
    /** 状态通知（SSE 的第二条数据源：设备/主机链路相位、配置变更）。 */
    private final com.shimizukenta.secssim.app.event.NoticeBus notices;

    RuntimeContext(String mode, String version, String extraHealthJson, Runnable shutdown,
            SessionManager sessions, SessionLauncher launcher, ConfigStore configs,
            TemplateStore templates, MessageLog messages, AuditLog audit,
            com.shimizukenta.secssim.app.event.EventBus eventBus,
            com.shimizukenta.secssim.app.event.NoticeBus notices,
            com.shimizukenta.secssim.store.HostRepository hostRepository,
            RuntimeStateRepository linkState,
            PlanExecutionRepository planExecutions, ConfigRepository configRepository) {
        this.mode = mode;
        this.version = version;
        this.extraHealthJson = extraHealthJson;
        this.shutdown = shutdown;
        this.sessions = sessions;
        this.configRepository = configRepository;
        this.planExecutions = planExecutions;
        this.linkState = linkState;
        this.notices = notices;
        this.hosts = new HostSimRegistry(sessions, eventBus, hostRepository, linkState, notices);
        /* 主机实例从配置库装回内存。这一步以前写在 Bootstrap 里，装进了一个随后就被
           丢掉的注册表——真正对外提供服务的这个注册表是空的，重启后主机详情 404、
           连接/发送全都找不到实例。放在这里才算真的装上。 */
        this.hosts.loadPersisted();
        this.hostRepository = hostRepository;
        this.messageLog = messages;
        this.configs = configs;
        this.bursts = new BurstRunner(sessions, this.hosts);
        this.persister = new RuntimePersister(linkState, this::canonicalDeviceId);
        this.api = new RuntimeApi(sessions, launcher, configs, templates, messages, audit,
                this.hosts, this.bursts, this.persister);
    }

    /**
     * 关闭上下文持有的后台资源：主机模拟器连接、在跑的压测与压测执行器。
     *
     * <p>以前停机只关会话与 HTTP，host-sim 的连接与采集线程、以及最多 2 个
     * 压测线程（可能在 {@code Thread.sleep} 里等 5 分钟）都靠 JVM 退出兜底。
     * 嵌入/测试宿主里这就变成泄漏。</p>
     */
    @Override
    public void close() {
        this.hosts.close();
        this.bursts.close();
    }

    @Override
    public String mode() {
        return this.mode;
    }

    @Override
    public String version() {
        return this.version;
    }

    @Override
    public String extraHealthJson() {
        return this.extraHealthJson;
    }

    @Override
    public void requestShutdown() {
        this.shutdown.run();
    }

    /* ---------- 会话查询 ---------- */

    @Override
    public String sessionsJson() {
        return this.api.sessionsJson();
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
        return this.api.previewJson(sessionId, fromMs, toMs, buckets);
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents) {
        return this.api.previewJson(sessionId, fromMs, toMs, buckets, svids, withEvents);
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents, String planId) {
        return this.api.previewJson(sessionId, fromMs, toMs, buckets, svids, withEvents, planId);
    }

    @Override
    public byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
        return this.api.previewBinary(sessionId, fromMs, toMs, buckets);
    }

    /* ---------- 手动触发 ---------- */

    @Override
    public TriggerOutcome triggerCeid(String sessionId, int ceid) {
        return this.api.triggerCeid(sessionId, ceid);
    }

    @Override
    public TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set, String text, int category) {
        return this.api.triggerAlarm(sessionId, alid, set, text, category);
    }

    @Override
    public String alarmsJson(String sessionId) {
        return this.api.alarmsJson(sessionId);
    }

    @Override
    public String terminalMessagesJson(String sessionId) {
        return this.api.terminalMessagesJson(sessionId);
    }

    @Override
    public String catalogJson(String sessionId) {
        return this.api.catalogJson(sessionId);
    }

    @Override
    public String eventsJson(String sessionId) {
        return this.api.eventsJson(sessionId);
    }

    @Override
    public EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
        return this.api.setEventEnabled(sessionId, planId, enabled);
    }

    /* ---------- 报文与审计 ---------- */

    @Override
    public String messagesJson(int limit, String sessionId, String direction) {
        return this.api.messagesJson(limit, sessionId, direction);
    }

    @Override
    public String messagesJson(int limit, String sessionId, String direction,
                               long beforeMs, Integer stream, Integer function) {
        return this.api.messagesJson(limit, sessionId, direction, beforeMs, stream, function);
    }

    @Override
    public String messagesJsonl(int limit, String sessionId, String direction) {
        return this.api.messagesJsonl(limit, sessionId, direction);
    }

    @Override
    public String messagesJsonl(int limit, String sessionId, String direction,
                                long beforeMs, Integer stream, Integer function) {
        return this.api.messagesJsonl(limit, sessionId, direction, beforeMs, stream, function);
    }

    @Override
    public String auditJson(int limit) {
        return this.api.auditJson(limit);
    }

    @Override
    public String auditJson(int limit, String action, String actor) {
        return this.api.auditJson(limit, action, actor);
    }

    @Override
    public String auditJson(int limit, String action, String actor, long beforeMs) {
        return this.api.auditJson(limit, action, actor, beforeMs);
    }

    /* ---------- 运行时编辑(设备页·事件/报告/状态变量) ---------- */

    @Override
    public int addEventPlan(String sessionId, String planJson) {
        return this.api.addEventPlan(sessionId, planJson);
    }

    @Override
    public int updateEventPlan(String sessionId, String planId, String planJson) {
        return this.api.updateEventPlan(sessionId, planId, planJson);
    }

    @Override
    public int removeEventPlan(String sessionId, String planId) {
        return this.api.removeEventPlan(sessionId, planId);
    }

    @Override
    public int addReport(String sessionId, String reportJson) {
        return this.api.addReport(sessionId, reportJson);
    }

    @Override
    public int updateReport(String sessionId, int rptid, String reportJson) {
        return this.api.updateReport(sessionId, rptid, reportJson);
    }

    @Override
    public int addCeid(String sessionId, String ceidJson) {
        return this.api.addCeid(sessionId, ceidJson);
    }

    @Override
    public int updateCeid(String sessionId, int ceid, String ceidJson) {
        return this.api.updateCeid(sessionId, ceid, ceidJson);
    }

    @Override
    public int removeCeid(String sessionId, int ceid) {
        return this.api.removeCeid(sessionId, ceid);
    }

    @Override
    public int removeReport(String sessionId, int rptid) {
        return this.api.removeReport(sessionId, rptid);
    }

    @Override
    public int addSvid(String sessionId, String svidJson) {
        return this.api.addSvid(sessionId, svidJson);
    }

    @Override
    public int updateSvid(String sessionId, int svid, String svidJson) {
        return this.api.updateSvid(sessionId, svid, svidJson);
    }

    @Override
    public int removeSvid(String sessionId, int svid) {
        return this.api.removeSvid(sessionId, svid);
    }

    @Override
    public int pinSvidValue(String sessionId, int svid, String valueJson) {
        return this.api.pinSvidValue(sessionId, svid, valueJson);
    }

    @Override
    public int unpinSvidValue(String sessionId, int svid) {
        return this.api.unpinSvidValue(sessionId, svid);
    }

    @Override
    public String sendSml(String sessionId, String sml) throws IOException {
        return this.api.sendSml(sessionId, sml);
    }

    /* ---------- 会话生命周期 ---------- */

    @Override
    public SessionOutcome createSession(SessionCreateRequest request) {
        return this.api.createSession(request);
    }

    @Override
    public SessionOutcome closeSession(String sessionId) {
        return this.api.closeSession(sessionId);
    }

    @Override
    public SessionOutcome reloadSession(String sessionId) {
        return this.api.reloadSession(sessionId);
    }

    /* ---------- 主机模拟器 ---------- */

    @Override
    public SessionOutcome openHostSim(String sessionId) {
        /* v1 兼容：session 只在创建瞬间解析成目标地址，此后主机与设备再无关系 */
        return this.hosts.openForSession(sessionId, null, null).outcome();
    }

    @Override
    public SessionOutcome openHostSim(String sessionId, String targetJson) {
        return this.hosts.openForSession(sessionId, targetJson, null).outcome();
    }

    @Override
    public SessionOutcome openHostSim(String sessionId, String targetJson, String hostId) {
        /* v1 的 hostId 参数被持久 ULID 取代：忽略并按新实例创建 */
        return this.hosts.openForSession(sessionId, targetJson, null).outcome();
    }

    @Override
    public SessionOutcome closeHostSim(String sessionId) {
        /* v1:断开以会话名建的主机实例 */
        return this.hosts.disconnectByName(sessionId);
    }

    @Override
    public SessionOutcome closeHostSimById(String hostId) {
        /* v1 的「断开实例」= 3.0 的 disconnect（实例保留，可重连） */
        SessionOutcome outcome = this.hosts.disconnectById(hostId);
        /* 在跑的压测也该停下（否则一直对着已断开的连接重试） */
        this.bursts.cancelHost(hostId);
        return outcome;
    }

    @Override
    public String hostSimJson(String sessionId) {
        return this.hosts.jsonOfSession(sessionId);
    }

    @Override
    public String hostSimsJson() {
        return this.hosts.allJson();
    }

    @Override
    public String hostSimJsonById(String hostId) {
        return this.hosts.jsonById(hostId);
    }

    @Override
    public String sendHostSmlById(String hostId, String sml) throws IOException {
        return this.hosts.sendById(hostId, sml);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult setHostSimCollectorById(
            String hostId, String requestJson) {
        return this.hosts.setCollectorById(hostId, requestJson);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult hostSimCollectorCommandById(
            String hostId, boolean start) {
        return this.hosts.commandById(hostId, start);
    }

    @Override
    public String hostSimCollectorJsonById(String hostId) {
        return this.hosts.collectorJsonById(hostId);
    }

    @Override
    public String sendHostSml(String sessionId, String sml) throws IOException {
        return this.hosts.sendBySession(sessionId, sml);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult setHostSimCollector(
            String sessionId, String requestJson) {
        return this.hosts.setCollectorBySession(sessionId, requestJson);
    }

    @Override
    public com.shimizukenta.secssim.api.http.CollectorResult hostSimCollectorCommand(
            String sessionId, boolean start) {
        return this.hosts.commandBySession(sessionId, start);
    }

    @Override
    public String hostSimCollectorJson(String sessionId) {
        return this.hosts.collectorJsonBySession(sessionId);
    }

    @Override
    public String configRevisionsV2(String name, int limit) {
        return this.configs.revisionsJson(name, limit);
    }

    /* ---------- 3.0 M4：事件编排执行台账 ---------- */

    @Override
    public String planExecutionsV2(String deviceId, int limit, String planId,
                                   String status, String dryRun) {
        String canonical = canonicalDeviceId(deviceId);
        boolean includeDryRun = dryRun != null && ("all".equalsIgnoreCase(dryRun)
                || "1".equals(dryRun) || "true".equalsIgnoreCase(dryRun));
        int capped = Math.max(1, Math.min(500, limit));
        String statusFilter = status == null || status.isBlank() ? null
                : status.trim().toUpperCase(java.util.Locale.ROOT);
        List<PlanExecutionRepository.ExecutionRow> rows = this.planExecutions.query(
                canonical, blankToNull(planId), statusFilter, includeDryRun, capped);

        StringBuilder sb = new StringBuilder(128 + rows.size() * 256);
        sb.append("{\"items\":[");
        for (int i = 0; i < rows.size(); i++) {
            PlanExecutionRepository.ExecutionRow row = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(row.id())
                    .append(",\"deviceId\":").append(quote(row.deviceId()))
                    .append(",\"planId\":").append(quote(row.planId()))
                    .append(",\"ceid\":").append(row.ceid())
                    .append(",\"dryRun\":").append(row.dryRun())
                    .append(",\"scheduledAt\":").append(row.scheduledAt())
                    .append(",\"firedAt\":").append(row.firedAt())
                    .append(",\"status\":").append(quote(row.status()))
                    .append(",\"messages\":").append(safeArray(row.messageJson()))
                    .append(",\"effects\":").append(safeArray(row.effectJson()))
                    .append(",\"error\":").append(quote(row.error()))
                    .append('}');
        }
        return sb.append("],\"count\":").append(rows.size()).append('}').toString();
    }

    @Override
    public TriggerOutcome stepPlanV2(String deviceId, String planId) {
        HsmsSessionRuntime session = sessionFor(deviceId);
        if (session == null) {
            return TriggerOutcome.UNKNOWN_SESSION;
        }
        try {
            session.stepPlan(planId);
            return TriggerOutcome.OK;
        } catch (java.io.IOException | RuntimeException e) {
            com.shimizukenta.secssim.app.log.Log.error("单步试跑计划失败: " + planId, e);
            return TriggerOutcome.FAILED;
        }
    }

    @Override
    public SessionOutcome setPlanEnabledV2(String deviceId, String planId, boolean enabled) {
        HsmsSessionRuntime session = sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        if (!session.setEventEnabled(planId, enabled)) {
            return SessionOutcome.FAILED;
        }
        /* next_fire_at 由调度器在下一次扫描时推进；这里只持久化启停态，
           不写死一个可能与 OnCondition / AfterEvent 冲突的时刻（设计 §2.7.3）。 */
        this.linkState.savePlanState(canonicalDeviceId(deviceId), planId, enabled, null, "");
        return SessionOutcome.OK;
    }

    /** v2 设备 id 与 v1 legacy stem 都归一到 config.db 的 ULID（台账按它归属）。 */
    private String canonicalDeviceId(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        try {
            return this.configRepository.findDevice(key)
                    .map(ConfigRepository.DeviceView::id).orElse(key);
        } catch (RuntimeException e) {
            /* 配置库临时不可读时按原 key 查，不让台账查询整条挂掉 */
            return key;
        }
    }

    /** 按归一后的设备 id 找会话：v1 会话 id 是 legacy stem，v2 设备 id 是 ULID。 */
    private HsmsSessionRuntime sessionFor(String deviceId) {
        String canonical = canonicalDeviceId(deviceId);
        for (SessionRuntime runtime : this.sessions.list()) {
            if (runtime instanceof HsmsSessionRuntime hsms
                    && canonical.equals(canonicalDeviceId(hsms.id()))) {
                return hsms;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeArray(String json) {
        return json == null || json.isBlank() ? "[]" : json;
    }

    /** JSON 字符串转义（设备 id / 计划 id / 错误文本可能带引号或换行）。 */
    private static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /* ---------- 3.0 v2：主机独立命名空间（设计 §2.5） ---------- */

    @Override
    public String hostsV2Json() {
        return this.hosts.allJson();
    }

    @Override
    public SessionOutcome openHostV2(String name, String host, int port, int deviceId, boolean autoReconnect) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return SessionOutcome.FAILED;
        }
        var result = this.hosts.open(name == null || name.isBlank() ? "host" : name,
                new com.shimizukenta.secssim.app.session.HsmsConnectionTarget(host, port, deviceId),
                autoReconnect);
        this.lastCreatedHostId = result.hostId();
        return result.outcome();
    }

    @Override
    public String lastCreatedHostId() {
        return this.lastCreatedHostId;
    }

    @Override
    public SessionOutcome saveHostV2(String hostId, String name, String host, int port, int deviceId) {
        var sim = this.hosts.findSim(hostId);
        if (sim == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        sim.saveSettings(name, new com.shimizukenta.secssim.app.session.HsmsConnectionTarget(host, port, deviceId));
        var view = this.hostRepository.find(hostId).orElse(null);
        if (view != null) {
            this.hostRepository.save(hostId, sim.name(), host, port, deviceId,
                    view.collectorJson(), view.autoReconnect(), view.note());
        }
        return SessionOutcome.OK;
    }

    @Override
    public String hostV2Json(String hostId) {
        return this.hosts.jsonById(hostId);
    }

    @Override
    public SessionOutcome destroyHostV2(String hostId) {
        SessionOutcome outcome = this.hosts.destroyById(hostId);
        this.bursts.cancelHost(hostId);
        return outcome;
    }

    @Override
    public SessionOutcome hostConnectV2(String hostId) {
        return this.hosts.connectById(hostId);
    }

    @Override
    public SessionOutcome hostDisconnectV2(String hostId) {
        SessionOutcome outcome = this.hosts.disconnectById(hostId);
        this.bursts.cancelHost(hostId);
        return outcome;
    }

    @Override
    public SessionOutcome hostApplyV2(String hostId) {
        return this.hosts.applyById(hostId);
    }

    @Override
    public String hostSendV2(String hostId, String sml) {
        try {
            return this.hosts.sendById(hostId, sml);
        } catch (java.io.IOException e) {
            return "{\"ok\":false,\"error\":\"" + escape(e.getMessage() == null ? "send-failed" : e.getMessage()) + "\"}";
        }
    }

    @Override
    public String hostVariablesRefreshV2(String hostId) {
        return this.hosts.refreshVariables(hostId);
    }

    @Override
    public boolean hostVariableDeclareV2(String hostId, int svid, String type) {
        return this.hosts.declareVariableType(hostId, svid, type);
    }

    @Override
    public String hostVariablesV2(String hostId) {
        return this.hosts.variablesJson(hostId);
    }

    @Override
    public String hostTemplatesJson() {
        return this.hostRepository.listTemplatesJson();
    }

    @Override
    public SessionOutcome createHostTemplateV2(String id, String name, String host, int port,
                                               int deviceId, String note) {
        try {
            if (id != null && !id.isBlank()) {
                this.hostRepository.saveTemplate(id, name, host, port, deviceId, "{}", note);
            } else {
                this.hostRepository.createTemplate(name, host, port, deviceId, "{}", note);
            }
            return SessionOutcome.OK;
        } catch (com.shimizukenta.secssim.store.StoreException e) {
            return SessionOutcome.FAILED;
        }
    }

    @Override
    public SessionOutcome deleteHostTemplateV2(String id) {
        try {
            this.hostRepository.deleteTemplate(id);
            return SessionOutcome.OK;
        } catch (com.shimizukenta.secssim.store.StoreException e) {
            return SessionOutcome.FAILED;
        }
    }

    @Override
    public String hostMessagesV2(String hostId, int limit) {
        var snapshot = this.messageLog.selectHostMessages(Math.max(1, limit), hostId, "host");
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        List<com.shimizukenta.jsonhub.JsonHub> items = new java.util.ArrayList<>();
        for (var event : snapshot.messages()) {
            items.add(com.shimizukenta.jsonhub.JsonHub.fromJson(toMessageJson(event)));
        }
        return b.object(
                b.pair("ok", true),
                b.pair("total", snapshot.total()),
                b.pair("messages", b.array(items))).toJson();
    }

    private String toMessageJson(com.shimizukenta.secssim.app.event.MessageEvent event) {
        return "{\"seq\":" + event.seq()
                + ",\"hostId\":\"" + escape(event.hostId()) + "\""
                + ",\"perspective\":\"" + event.perspective() + "\""
                + ",\"direction\":\"" + event.direction() + "\""
                + ",\"stream\":" + event.stream()
                + ",\"function\":" + event.function()
                + ",\"wbit\":" + event.wbit()
                + ",\"body\":\"" + escape(event.body()) + "\""
                + ",\"at\":" + event.atMillis() + "}";
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append('\\').append('n');
                case '\r' -> sb.append('\\').append('r');
                case '\t' -> sb.append('\\').append('t');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
    /* ---------- 总览与压测 ---------- */

    @Override
    public String overviewJson() {
        return this.api.overviewJson();
    }

    @Override
    public String burst(String sessionId, String requestJson) {
        return this.bursts.burst(sessionId, requestJson);
    }

    @Override
    public String burstHost(String hostId, String requestJson) {
        return this.bursts.burstHost(hostId, requestJson);
    }

    /* ---------- 配置与模板 ---------- */

    @Override
    public String configPreviewJson(String text, long fromMs, long toMs, int buckets) {
        return this.api.configPreviewJson(text, fromMs, toMs, buckets);
    }

    @Override
    public String configPreviewJson(String text, long fromMs, long toMs, int buckets,
                                    java.util.Set<Integer> svids, boolean withEvents) {
        return this.api.configPreviewJson(text, fromMs, toMs, buckets, svids, withEvents);
    }

    @Override
    public String configsJson() {
        return this.api.configsJson();
    }

    @Override
    public ApiContext.ConfigSnapshot configSnapshot(String name) {
        return this.api.configSnapshot(name);
    }

    @Override
    public String configText(String name) {
        return this.api.configText(name);
    }

    @Override
    public List<String> validateConfig(String text) {
        return this.api.validateConfig(text);
    }

    @Override
    public ConfigResult saveConfig(String name, String text) {
        ConfigResult result = this.api.saveConfig(name, text);
        this.announceConfig(name, "save", result);
        return result;
    }

    @Override
    public ConfigResult saveConfig(String name, String text, Long expectedModifiedMs) {
        ConfigResult result = this.api.saveConfig(name, text, expectedModifiedMs);
        this.announceConfig(name, "save", result);
        return result;
    }

    /**
     * 配置改了要让别的窗口知道（{@code event: config-changed}）。
     *
     * <p>3.0 有「同一份配置在设备页与配置页各开一个编辑器」的用法，
     * 没有这条通知就只能靠用户自己点「重新载入」发现内容变了。</p>
     */
    private void announceConfig(String name, String action, ConfigResult result) {
        if (result == null || result.outcome() != ConfigOutcome.OK || name == null) {
            return;
        }
        this.notices.publish(new com.shimizukenta.secssim.app.event.NoticeEvent(
                name, com.shimizukenta.secssim.app.event.NoticeEvent.CONFIG_CHANGED,
                "{\"name\":\"" + name.replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\",\"action\":\"" + action + "\"}",
                System.currentTimeMillis()));
    }

    @Override
    public Long configModifiedMs(String name) {
        return this.api.configModifiedMs(name);
    }

    @Override
    public ConfigResult createConfig(String name) {
        ConfigResult result = this.api.createConfig(name);
        this.announceConfig(name, "create", result);
        return result;
    }

    @Override
    public ConfigResult createConfig(String name, String templateId) {
        ConfigResult result = this.api.createConfig(name, templateId);
        this.announceConfig(name, "create", result);
        return result;
    }

    @Override
    public String configTemplatesJson() {
        return this.api.configTemplatesJson();
    }

    @Override
    public String templatesJson() {
        return this.api.templatesJson();
    }

    @Override
    public java.util.Optional<String> templateText(String id) {
        return this.api.templateText(id);
    }

    @Override
    public ConfigResult saveTemplate(String id, String text) {
        return this.api.saveTemplate(id, text);
    }

    @Override
    public ConfigResult deleteTemplate(String id) {
        return this.api.deleteTemplate(id);
    }
}
