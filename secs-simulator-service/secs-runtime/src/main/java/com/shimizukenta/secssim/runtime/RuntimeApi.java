package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.EventOutcome;
import com.shimizukenta.secssim.api.http.ParamTemplateSaved;
import com.shimizukenta.secssim.api.http.SessionCreateRequest;
import com.shimizukenta.secssim.api.http.SessionCreated;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.api.http.SessionsJson;
import com.shimizukenta.secssim.api.http.TriggerOutcome;
import com.shimizukenta.secssim.app.audit.AuditLog;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.app.event.MessageLog;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.RuntimeEdits;

/**
 * 接口落地：把 {@code ApiContext} 的每个方法落到会话、配置、报文日志这些实际对象上。
 *
 * <p>从 {@code RuntimeContext} 拆出来的第三块，也是原来最臃肿的一块。
 * 它只做"查对象 → 调方法 → 归一化返回值"，不含健康信息、线程池或生命周期；
 * 主机模拟器与压测各自由 {@link HostSimRegistry}、{@link BurstRunner} 负责。</p>
 */
final class RuntimeApi {

    private final SessionManager sessions;
    private final SessionLauncher launcher;
    private final ConfigStore configs;
    private final TemplateStore templates;
    private final ParamTemplateStore paramTemplates;
    private final MessageLog messages;
    private final AuditLog audit;
    private final HostSimRegistry hosts;
    private final BurstRunner bursts;

    /** 运行态改动写穿到 runtime.db；失败只记日志，不影响请求结果。 */
    private final RuntimePersister persister;

    /** 设备实体 ↔ 会话实例的映射（运行态按设备 id 归属）。 */
    private final DeviceSessions devices;

    RuntimeApi(SessionManager sessions, SessionLauncher launcher, ConfigStore configs,
            TemplateStore templates, ParamTemplateStore paramTemplates, MessageLog messages,
            AuditLog audit, HostSimRegistry hosts, BurstRunner bursts, RuntimePersister persister,
            DeviceSessions devices) {
        this.sessions = sessions;
        this.launcher = launcher;
        this.configs = configs;
        this.templates = templates;
        this.paramTemplates = paramTemplates;
        this.messages = messages;
        this.audit = audit;
        this.hosts = hosts;
        this.bursts = bursts;
        this.persister = persister;
        this.devices = devices;
    }

    /* ---------- 会话查询 ---------- */

    String sessionsJson() {
        /* 包一层带配置名:配置页要用它自动打开本设备的配置文件 */
        List<SessionRuntime> decorated = new ArrayList<>();
        for (SessionRuntime session : this.sessions.list()) {
            final String config = this.launcher.sourceOf(session.id()).orElse("");
            decorated.add(new SessionRuntime() {
                @Override public String id() { return session.id(); }
                @Override public void open() throws IOException { session.open(); }
                @Override public void close() { session.close(); }
                @Override public SessionState state() { return session.state(); }
                @Override public String lastError() { return session.lastError(); }
                @Override public String deviceName() { return session.deviceName(); }
                @Override public String configName() { return config; }
                @Override public int unitCount() { return session.unitCount(); }
                @Override public boolean hasPeer() { return session.hasPeer(); }
                @Override public java.util.Map<String, Long> counters() { return session.counters(); }
                @Override public com.shimizukenta.secssim.app.session.EquipmentControlState controlState() { return session.controlState(); }
                @Override public String eventsJson() { return session.eventsJson(); }
                @Override public String overviewJson() { return session.overviewJson(); }
                @Override public com.shimizukenta.secssim.app.session.PlanEnableOutcome setEventEnabled(String planId, boolean enabled) { return session.setEventEnabled(planId, enabled); }
                @Override public String previewJson(long fromMs, long toMs, int buckets) { return session.previewJson(fromMs, toMs, buckets); }
                @Override public String previewJson(long fromMs, long toMs, int buckets, java.util.Set<Integer> svids, boolean withEvents) { return session.previewJson(fromMs, toMs, buckets, svids, withEvents); }
                @Override public byte[] previewBinary(long fromMs, long toMs, int buckets) { return session.previewBinary(fromMs, toMs, buckets); }
                @Override public void emitCeid(int ceid) throws IOException { session.emitCeid(ceid); }
                @Override public void emitAlarm(int alid, boolean set, String text, int category) throws IOException { session.emitAlarm(alid, set, text, category); }
                @Override public String sendSml(String sml) throws IOException { return session.sendSml(sml); }
                @Override public com.shimizukenta.secssim.app.session.HsmsConnectionTarget hostTarget() { return session.hostTarget(); }
                @Override public String alarmsJson() { return session.alarmsJson(); }
                @Override public String terminalMessagesJson() { return session.terminalMessagesJson(); }
                @Override public String catalogJson() { return session.catalogJson(); }
            });
        }
        return SessionsJson.render(decorated);
    }

    String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewJson(fromMs, toMs, buckets))
                .orElse(null);
    }

    String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                       java.util.Set<Integer> svids, boolean withEvents) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewJson(fromMs, toMs, buckets, svids, withEvents))
                .orElse(null);
    }

    String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                       java.util.Set<Integer> svids, boolean withEvents, String planId) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewJson(fromMs, toMs, buckets, svids, withEvents, planId))
                .orElse(null);
    }

    byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewBinary(fromMs, toMs, buckets))
                .orElse(null);
    }

    /* ---------- 手动触发 ---------- */

    TriggerOutcome triggerCeid(String sessionId, int ceid) {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);

        if (session == null) {
            return TriggerOutcome.UNKNOWN_SESSION;
        }
        if (session.state() != SessionState.OPEN || !session.hasPeer()) {
            return TriggerOutcome.NOT_OPEN;
        }

        try {
            session.emitCeid(ceid);
            return TriggerOutcome.OK;
        } catch (IOException e) {
            Log.error("手动触发 CEID " + ceid + " 失败", e);
            return TriggerOutcome.FAILED;
        }
    }

    TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set, String text, int category) {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);

        if (session == null) {
            return TriggerOutcome.UNKNOWN_SESSION;
        }
        if (session.state() != SessionState.OPEN || !session.hasPeer()) {
            return TriggerOutcome.NOT_OPEN;
        }

        try {
            session.emitAlarm(alid, set, text, category);
            return TriggerOutcome.OK;
        } catch (IOException e) {
            Log.error("手动触发 ALID " + alid + " 失败", e);
            return TriggerOutcome.FAILED;
        }
    }

    String alarmsJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::alarmsJson).orElse(null);
    }

    String terminalMessagesJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::terminalMessagesJson).orElse(null);
    }

    String catalogJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::catalogJson).orElse(null);
    }

    String eventsJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::eventsJson).orElse(null);
    }

    EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        if (session == null) {
            return EventOutcome.UNKNOWN_SESSION;
        }
        com.shimizukenta.secssim.app.session.PlanEnableOutcome outcome =
                session.setEventEnabled(planId, enabled);
        if (outcome == com.shimizukenta.secssim.app.session.PlanEnableOutcome.UNKNOWN_PLAN) {
            return EventOutcome.UNKNOWN_PLAN;
        }
        if (outcome == com.shimizukenta.secssim.app.session.PlanEnableOutcome.NOT_ONLINE) {
            /* 2026-11-16 设计 §2.2：离线时拒绝启动（409 not-online），界面禁用按钮 */
            return EventOutcome.NOT_ONLINE;
        }
        /* next_fire_at 开始写入：周期计划 = enabledAt + k×period 的下一次（§2.1）；
           启用态不再跨重启回放（D4），本行只承载统计与倒计时初值 */
        this.persister.planState(sessionId, planId, enabled, session.nextFireAtOf(planId));
        return EventOutcome.OK;
    }

    /* ---------- 报文与审计 ---------- */

    String messagesJson(int limit, String sessionId, String direction) {
        return this.messagesJson(limit, sessionId, direction, 0L, null, null);
    }

    String messagesJson(int limit, String sessionId, String direction,
                        long beforeMs, Integer stream, Integer function) {
        return this.messages == null ? null : this.messages.recentJson(limit, sessionId, direction, beforeMs, stream, function);
    }

    String messagesJsonl(int limit, String sessionId, String direction) {
        return this.messagesJsonl(limit, sessionId, direction, 0L, null, null);
    }

    String messagesJsonl(int limit, String sessionId, String direction,
                         long beforeMs, Integer stream, Integer function) {
        return this.messages == null ? null : this.messages.recentJsonl(limit, sessionId, direction, beforeMs, stream, function);
    }

    String auditJson(int limit) {
        return this.auditJson(limit, "", "");
    }

    String auditJson(int limit, String action, String actor) {
        return this.auditJson(limit, action, actor, 0L);
    }

    String auditJson(int limit, String action, String actor, long beforeMs) {
        return this.audit == null ? null : this.audit.recentJson(limit, action, actor, beforeMs);
    }

    /* ---------- 运行时编辑(设备页·事件/报告/状态变量) ---------- */

    private SessionRuntime sessionOr404(String sessionId) {
        return this.sessions.find(sessionId).orElse(null);
    }

    int addEventPlan(String sessionId, String planJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        com.shimizukenta.secssim.core.event.EventPlan plan = RuntimeEdits.parseEventPlan(planJson);
        if (!session.addEventPlan(plan)) {
            return 409;
        }
        this.persister.planOverride(sessionId, plan.id(), "add", planJson);
        return 201;
    }

    int updateEventPlan(String sessionId, String planId, String planJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        if (!session.updateEventPlan(planId, RuntimeEdits.parseEventPlan(planJson))) {
            return 404;
        }
        this.persister.planOverride(sessionId, planId, "update", planJson);
        return 200;
    }

    int removeEventPlan(String sessionId, String planId) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        if (!session.removeEventPlan(planId)) {
            return 404;
        }
        this.persister.planOverride(sessionId, planId, "remove", "{}");
        return 200;
    }

    int addReport(String sessionId, String reportJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        com.shimizukenta.secssim.core.compile.ResolvedReport report = RuntimeEdits.parseReport(reportJson);
        if (!session.addReport(report)) {
            return 409;
        }
        this.persister.catalogEdit(sessionId, "report", report.id(), "add", reportJson);
        return 201;
    }

    int updateReport(String sessionId, int rptid, String reportJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        com.shimizukenta.secssim.core.compile.ResolvedReport report = RuntimeEdits.parseReport(reportJson);
        if (!session.updateReport(report)) {
            return 404;
        }
        this.persister.catalogEdit(sessionId, "report", rptid, "update", reportJson);
        return 200;
    }

    int addCeid(String sessionId, String ceidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        com.shimizukenta.secssim.core.compile.ResolvedCeid def = RuntimeEdits.parseCeid(ceidJson);
        if (!session.addCeid(def)) {
            return 409;
        }
        this.persister.catalogEdit(sessionId, "ceid", def.id(), "add", ceidJson);
        return 201;
    }

    int updateCeid(String sessionId, int ceid, String ceidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        com.shimizukenta.secssim.core.compile.ResolvedCeid def = RuntimeEdits.parseCeid(ceidJson);
        if (!session.updateCeid(def)) {
            return 404;
        }
        this.persister.catalogEdit(sessionId, "ceid", ceid, "update", ceidJson);
        return 200;
    }

    int removeCeid(String sessionId, int ceid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        if (!session.removeCeid(ceid)) {
            return 404;
        }
        this.persister.catalogEdit(sessionId, "ceid", ceid, "remove", "{}");
        return 200;
    }

    int removeReport(String sessionId, int rptid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        if (!session.removeReport(rptid)) {
            return 404;
        }
        this.persister.catalogEdit(sessionId, "report", rptid, "remove", "{}");
        return 200;
    }

    int addSvid(String sessionId, String svidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        com.shimizukenta.secssim.core.compile.ResolvedSvid def = RuntimeEdits.parseSvid(svidJson);
        if (!session.addSvid(def)) {
            return 409;
        }
        this.persister.catalogEdit(sessionId, "svid", def.id(), "add", svidJson);
        return 201;
    }

    int updateSvid(String sessionId, int svid, String svidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        com.shimizukenta.secssim.core.compile.ResolvedSvid def = RuntimeEdits.parseSvid(svidJson);
        if (!session.updateSvid(def)) {
            return 404;
        }
        this.persister.catalogEdit(sessionId, "svid", svid, "update", svidJson);
        return 200;
    }

    int removeSvid(String sessionId, int svid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        if (!session.removeSvid(svid)) {
            return 404;
        }
        this.persister.catalogEdit(sessionId, "svid", svid, "remove", "{}");
        return 200;
    }

    int pinSvidValue(String sessionId, int svid, String valueJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        double value = RuntimeEdits.parsePinnedValue(valueJson);
        if (!session.pinSvidValue(svid, value)) {
            return 404;
        }
        this.persister.pin(sessionId, svid, value);
        return 200;
    }

    int unpinSvidValue(String sessionId, int svid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        if (!session.unpinSvidValue(svid)) {
            return 404;
        }
        this.persister.unpin(sessionId, svid);
        return 200;
    }

    String sendSml(String sessionId, String sml) throws IOException {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        return session == null ? null : session.sendSml(sml);
    }

    /* ---------- 会话生命周期 ---------- */

    SessionCreated createSession(SessionCreateRequest request) {
        try {
            SessionOverrides overrides = new SessionOverrides(
                    request.bindHost(), request.port(), request.deviceId());
            String id = this.launcher.create(request.config(),
                    overrides.isEmpty() ? SessionOverrides.NONE : overrides);
            Log.info("新建会话 " + id + "（配置 " + request.config() + "）");
            /* 会话一注册就有 device_link 行（Bootstrap 的注册观察者写的），
               这里补一次相位：刚建好可能已经 bind 成功（LISTENING）或失败（FAILED）。
               运行态按设备 id 归属，不能用会话 id。 */
            String deviceId = this.devices.deviceIdOf(id);
            this.sessions.find(id).ifPresent(session ->
                    this.persister.devicePhase(deviceId, phaseName(session.linkPhase()), session.lastError()));
            return new SessionCreated(SessionOutcome.OK, id);
        } catch (ConfigException e) {
            /* 配置名非法、文件读不了、内容编译不过——都是"这台设备起不来" */
            Log.error("新建会话失败（配置）: " + e.getMessage(), null);
            return SessionCreated.failed(SessionOutcome.FAILED);
        } catch (IOException e) {
            /* 到这一步配置已经是好的，只剩端口没绑上 */
            Log.error("新建会话失败（端口绑定）: " + e.getMessage(), null);
            return SessionCreated.failed(SessionOutcome.FAILED);
        } catch (IllegalArgumentException e) {
            Log.error("新建会话失败（标识冲突）: " + e.getMessage(), null);
            return SessionCreated.failed(SessionOutcome.CONFLICT);
        }
    }

    SessionOutcome closeSession(String sessionId) {
        /* 会话没了，压测状态也没有查询入口；一并清掉，避免 map 随会话增删无限增长 */
        this.bursts.cancel(sessionId);
        /* 运行态按设备 id 归属：会话 id 只用于注册表与压测表 */
        String deviceId = this.devices.deviceIdOf(sessionId);
        /* 先收掉这台设备的主机模拟器：会话都删了，主机实例不该继续挂在列表里、
           占着一条已经死掉的连接。 */
        this.hosts.closeAllOf(sessionId);
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        String lastError = session == null ? null : session.lastError();
        boolean closed = this.launcher.close(sessionId);
        if (closed) {
            /* 关闭 = 销毁（v1 语义）：按设计 §2.4「destroy 才清」把运行态编辑一起清掉。
               行本身留着并标 STOPPED，界面据此显示「已断开」而不是凭空消失。 */
            this.persister.clearEdits(deviceId);
            this.persister.devicePhase(deviceId, phaseName(SessionRuntime.LinkPhase.STOPPED), lastError);
        }
        return closed ? SessionOutcome.OK : SessionOutcome.UNKNOWN_SESSION;
    }

    SessionOutcome reloadSession(String sessionId) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        if (this.launcher.sourceOf(sessionId).isEmpty()) {
            return SessionOutcome.NOT_RELOADABLE;
        }

        try {
            /* 重载 = 按配置恢复（设计 §2.4）：内存里的运行时编辑先清库再重建会话，
               否则注册观察者会把刚清掉的钉值/计划覆盖又装回来。
               清之前先抓快照：重建可能因配置非法/端口被占失败，失败时旧会话会被
               launcher 拉回来，但编辑已经删了——不回滚等于"重载失败一次，钉值全丢"。 */
            String deviceId = this.devices.deviceIdOf(sessionId);
            RuntimePersister.Edits before = this.persister.snapshot(deviceId);
            this.persister.clearEdits(deviceId);
            try {
                this.launcher.reload(sessionId);
            } catch (ConfigException | IOException failure) {
                this.persister.restore(deviceId, before);
                this.sessions.find(sessionId).ifPresent(session -> {
                    if (session instanceof HsmsSessionRuntime hsms) {
                        this.persister.replay(hsms, deviceId);
                    }
                });
                throw failure;
            }
            Log.info("会话 " + sessionId + " 已按配置重载");
            this.sessions.find(sessionId).ifPresent(session ->
                    this.persister.devicePhase(deviceId, phaseName(session.linkPhase()), session.lastError()));
            return SessionOutcome.OK;
        } catch (ConfigException e) {
            /* 配置有问题时旧会话原样跑着，这里只是如实报告失败 */
            Log.error("重载失败（配置，旧会话未动）: " + e.getMessage(), null);
            return SessionOutcome.FAILED;
        } catch (IOException e) {
            Log.error("重载失败（端口绑定）: " + e.getMessage(), null);
            return SessionOutcome.FAILED;
        }
    }

    /** 链路相位落库用的字符串（与 device_link.phase 的约定一致）。 */
    static String phaseName(SessionRuntime.LinkPhase phase) {
        return phase == null ? SessionRuntime.LinkPhase.STOPPED.name() : phase.name();
    }

    /* ---------- 总览 ---------- */

    String overviewJson() {
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        List<com.shimizukenta.jsonhub.JsonHub> items = new ArrayList<>();
        for (SessionRuntime session : this.sessions.list()) {
            /* 主机连接标记由注册表判定：hostSims 按实例 id 存,
               必须经 hostsBySession 反查该会话的实例。 */
            String fragment = session.overviewJson(this.hosts.anyOpen(session.id()));
            if (fragment == null) {
                continue;
            }
            items.add(com.shimizukenta.jsonhub.JsonHub.fromJson(fragment));
        }
        return b.object(b.pair("sessions", b.array(items))).toJson();
    }

    /* ---------- 配置与模板 ---------- */

    String configPreviewJson(String text, long fromMs, long toMs, int buckets) {
        return configPreviewJson(text, fromMs, toMs, buckets, java.util.Set.of(), true);
    }

    String configPreviewJson(String text, long fromMs, long toMs, int buckets,
                             java.util.Set<Integer> svids, boolean withEvents) {
        /* 与 validate 同一条编译路径:失败返回 null,界面显示校验问题而不是半张图 */
        try {
            java.nio.file.Path temp = java.nio.file.Files.createTempFile("secs-preview-", ".json");
            try {
                java.nio.file.Files.writeString(temp, text, java.nio.charset.StandardCharsets.UTF_8);
                com.shimizukenta.secssim.app.config.SessionConfig config =
                        com.shimizukenta.secssim.app.config.SessionConfigJson.read(temp);
                com.shimizukenta.secssim.core.compile.CompilationResult compiled = config.compile();
                if (!compiled.ok()) {
                    return null;
                }
                /* 值来源统一走 ValueSources:草稿预览与真实启动必须是同一套语义 */
                com.shimizukenta.secssim.core.value.ValueSource values =
                        com.shimizukenta.secssim.app.config.ValueSources.of(config, compiled.equipment());
                java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> events =
                java.util.List.of();
                java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> cycleEvents =
                java.util.List.of();
                /* cycle 已从编排表拆出（M3）：草稿预览的事件时间线 = 手写计划模拟
                   ∪ CycleRunner.simulateFires（锚 = 窗口起点，"如果现在启动"）；
                   值曲线的草稿时间线也从窗口起点起算，预览与实跑同锚（M3 追加 3） */
                com.shimizukenta.secssim.core.event.EventSchedule schedule = config.schedule();
                if (withEvents) {
                    /* 草稿没有真实锚点：计划统一按「窗口起点启动」求值（设计 §2.4） */
                    java.util.Map<String, Long> previewAnchors = schedule.plans().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    com.shimizukenta.secssim.core.event.EventPlan::id,
                                    plan -> fromMs,
                                    (a, b) -> a));
                    java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> planMarks =
                            com.shimizukenta.secssim.runtime.session.EventSimulator.simulate(
                                            schedule, values, config.spec().seed(), fromMs, toMs,
                                            com.shimizukenta.secssim.runtime.session.EventSimulator.DEFAULT_MAX_EVENTS,
                                            previewAnchors)
                                    .stream()
                                    .map(fire -> new com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent(
                                            fire.atMs(), fire.ceid(), fire.planId()))
                                    .toList();
                    /* 工艺周期标记独立一袋：周期预览与事件计划预览各算各的（2026-11-17 拆分） */
                    java.util.List<com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent> cycleMarks =
                            new java.util.ArrayList<>();
                    if (config.cycle() != null) {
                        for (long[] mark : com.shimizukenta.secssim.runtime.session.CycleRunner.simulateFires(
                                config.cycle(), config.spec().seed(), fromMs, fromMs, toMs)) {
                            cycleMarks.add(new com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent(
                                    mark[0], (int) mark[1], "cycle"));
                        }
                    }
                    events = java.util.List.copyOf(planMarks);
                    cycleEvents = java.util.List.copyOf(cycleMarks);
                }
                com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewData computed =
                        com.shimizukenta.secssim.runtime.session.PreviewSupport.compute(
                                values, compiled.equipment(), fromMs, toMs, buckets, svids, events);
                /* 曲线之外把两袋标记原样带回：周期预览用 cycleEvents、事件计划预览用 events */
                com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewData data =
                        new com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewData(
                                computed.fromMs(), computed.toMs(), computed.buckets(), computed.bucketMs(),
                                computed.svidTotal(), computed.series(), events, cycleEvents);
                return com.shimizukenta.secssim.runtime.session.PreviewSupport.toJson(data);
            } finally {
                java.nio.file.Files.deleteIfExists(temp);
            }
        } catch (IllegalArgumentException e) {
            /* 参数类问题（分桶数、时间窗等）：界面按"草稿非法"展示（HTTP 422） */
            Log.error("配置草稿预览参数无效: " + e.getMessage(), e);
            return null;
        } catch (com.shimizukenta.secssim.app.config.ConfigException
                | com.shimizukenta.secssim.core.template.ExpressionException e) {
            /* 草稿的语法/字段/表达式错误都属于用户输入，继续映射为 null（422）。
               表达式里的 p.* 引用只在求值期才暴露（解析期不校验），必须一起接住，
               否则用户写错一个参数会看到 500 而不是"配置非法" */
            Log.error("配置草稿预览失败: " + e.getMessage(), e);
            return null;
        } catch (java.io.IOException e) {
            /* 临时文件读写失败是环境问题：上抛成 500，不再伪装成"草稿非法" */
            throw new IllegalStateException("配置草稿预览失败", e);
        }
    }

    String configsJson() {
        return this.configs.listJson();
    }

    ApiContext.ConfigSnapshot configSnapshot(String name) {
        return this.configs.readSnapshot(name);
    }

    String configText(String name) {
        try {
            return this.configs.read(name);
        } catch (ConfigException e) {
            /* 名字非法与文件不存在都归到"取不到"，界面只需知道这份打不开 */
            return null;
        } catch (IOException e) {
            Log.error("读取配置失败: " + name, e);
            return null;
        }
    }

    List<String> validateConfig(String text) {
        return ConfigStore.validate(text);
    }

    ConfigResult saveConfig(String name, String text) {
        return this.saveConfig(name, text, null);
    }

    ConfigResult saveConfig(String name, String text, Long expectedModifiedMs) {
        ConfigResult result = this.configs.save(name, text, expectedModifiedMs);
        if (result.outcome() == ConfigOutcome.OK) {
            this.warnStaleSessions(name);
        }
        return result;
    }

    /** 分段保存的响应：HTTP 状态 + JSON 正文。 */
    record SectionsSave(int status, String json) {
    }

    /**
     * 分段保存（2026-11-18 设计 §4.3）：
     * 按段替换 → 整份编译 → 三道守卫 → 落盘，返回实际变化的段与影响级别。
     *
     * <p>守卫（§3.1）：A 结构变更要求下线；B 运行中周期引用的 SVID / 晶圆属性禁改；
     * C 常量覆盖需要确认。前两道拒绝保存，第三道返回 409 等前端确认后带
     * {@code confirmOverwrite=1} 重试。</p>
     *
     * @param name              文件名
     * @param body              {@code {"sections":{...}}}
     * @param expectedModifiedMs If-Match 带回的修改时间
     * @param confirmOverwrite  已确认覆盖运行时常量
     * @return 状态码 + JSON
     */
    SectionsSave saveSections(String name, String body, Long expectedModifiedMs, boolean confirmOverwrite) {
        Map<ConfigSection, JsonHub> payload;
        try {
            payload = ConfigSectionPatcher.parsePayload(body);
        } catch (IllegalArgumentException e) {
            return new SectionsSave(400, sectionsError("bad-sections", e.getMessage()));
        }

        String old;
        try {
            old = this.configs.read(name);
        } catch (ConfigException | IOException e) {
            return new SectionsSave(404, sectionsError("not-found", "配置不存在: " + name));
        }

        String next;
        Map<ConfigSection, String> before;
        Map<ConfigSection, String> after;
        List<ConfigSection> changed;
        try {
            next = ConfigSectionPatcher.apply(old, payload);
            before = ConfigSectionPatcher.fingerprints(old);
            after = ConfigSectionPatcher.fingerprints(next);
            changed = ConfigSectionPatcher.changed(before, after);
        } catch (RuntimeException e) {
            return new SectionsSave(422, problemsJson(List.of(String.valueOf(e.getMessage()))));
        }

        Long revision = this.configs.revision(name);
        if (changed.isEmpty()) {
            /* 提交了但内容没变：幂等返回，不 +1 版本、不触发应用 */
            return new SectionsSave(200, "{\"ok\":true,\"revision\":" + (revision == null ? 0 : revision)
                    + ",\"modifiedMs\":" + number(this.configs.modifiedMs(name))
                    + ",\"changed\":[],\"effect\":\"hot\",\"warnings\":[]}");
        }

        List<SessionRuntime> using = this.sessionsUsing(name);

        /* 守卫 A：结构变更必须「下线」。
           口径按设备自身的启停算：只要会话端口开着（state = OPEN/OPENING），
           模型变化就会整会话重建——对操作员来说等于静默重启设备，所以一律拦下。
           只看 GEM 控制状态是不够的：主机连上但没发 S1F17 时也是 OFFLINE，
           那会儿放行等于让设备在跑的时候被换掉模型。 */
        if (changed.contains(ConfigSection.STRUCTURE)) {
            for (SessionRuntime session : using) {
                if (sessionOn(session)) {
                    return new SectionsSave(409, sectionsError("not-offline",
                            "设备已启动（" + session.id() + "）：改单元结构前先停止设备"));
                }
            }
        }

        /* 守卫 B：被运行中周期引用的 SVID / 晶圆属性禁改 */
        for (SessionRuntime session : using) {
            if (session instanceof HsmsSessionRuntime hsms && hsms.cycleRunning()) {
                List<ConfigSectionGuards.Conflict> conflicts =
                        ConfigSectionGuards.cycleRefConflicts(old, payload, changed);
                if (!conflicts.isEmpty()) {
                    return new SectionsSave(409, conflictsJson(conflicts));
                }
            }
        }

        /* 守卫 C：常量覆盖提示（需要二次确认） */
        String warnings = "[]";
        if (changed.contains(ConfigSection.CONSTANTS)) {
            List<ConfigSectionGuards.ConstantOverwrite> overwrites = this.constantOverwrites(using, payload);
            if (!overwrites.isEmpty()) {
                warnings = overwritesJson(overwrites);
                if (!confirmOverwrite) {
                    return new SectionsSave(409, "{\"ok\":false,\"error\":\"ecv-overwrite\",\"warnings\":"
                            + warnings + "}");
                }
            }
        }

        String note = "section:" + changed.stream().map(ConfigSection::wire).reduce((a, b2) -> a + "," + b2).orElse("");
        ConfigResult result = this.configs.saveSections(name, next, expectedModifiedMs, note);
        Long newRevision = this.configs.revision(name);
        switch (result.outcome()) {
            case OK:
                this.announceSectionSave(name, changed);
                return new SectionsSave(200, "{\"ok\":true,\"revision\":" + (newRevision == null ? 0 : newRevision)
                        + ",\"modifiedMs\":" + number(this.configs.modifiedMs(name))
                        + ",\"changed\":" + sectionsJson(changed)
                        + ",\"effect\":\"" + ConfigSection.effectOf(changed).wire() + "\""
                        + ",\"warnings\":" + warnings + "}");
            case INVALID:
                return new SectionsSave(422, problemsJson(result.problems()));
            case CONFLICT:
                return new SectionsSave(409, sectionsError("revision-conflict",
                        "配置在编辑期间被其他窗口修改过，请重新载入"));
            case NOT_FOUND:
                return new SectionsSave(400, sectionsError("not-found", "配置不存在: " + name));
            default:
                return new SectionsSave(500, sectionsError("save-failed",
                        result.problems().isEmpty() ? "保存失败" : String.join("; ", result.problems())));
        }
    }

    /** 分段生效的响应：HTTP 状态 + JSON 正文。 */
    record SectionsApply(int status, String json) {
    }

    /**
     * 分段生效（2026-11-18 设计 §4.4）：把刚保存的段应用到正在跑的这台设备。
     *
     * <p>只动变化的段：只改事件计划 → 未改动的计划保留启用态；只改工艺周期 →
     * 周期回停止、计划与钉值不动。设备模型段（结构/变量/报告/事件）变化才做
     * 全量热重置；连接段变化直接转重载（断链重建，运行态全清）。</p>
     *
     * @param deviceId 设备（会话）标识
     * @param sections 变化段名（来自分段保存的响应）
     * @return 状态码 + JSON
     */
    SectionsApply applySections(String deviceId, List<String> sections) {
        if (this.sessions.find(deviceId).isEmpty()) {
            return new SectionsApply(404, sectionsError("unknown-session", "设备不存在: " + deviceId));
        }
        if (this.launcher.sourceOf(deviceId).isEmpty()) {
            return new SectionsApply(409, sectionsError("not-reloadable", "这台设备没有配置来源（演示会话）"));
        }

        List<ConfigSection> changed = new ArrayList<>();
        for (String wire : sections) {
            java.util.Optional<ConfigSection> section = ConfigSection.of(wire);
            if (section.isEmpty()) {
                return new SectionsApply(400, sectionsError("bad-section", "未知配置段: " + wire));
            }
            if (!changed.contains(section.get())) {
                changed.add(section.get());
            }
        }
        if (changed.isEmpty()) {
            /* 保存时没有段变化：没什么可应用的，也不要无端触发一次重置 */
            return new SectionsApply(200, "{\"ok\":true,\"effect\":\"hot\",\"changed\":[],"
                    + "\"reset\":[],\"preserved\":[],\"plansKept\":0,\"pinsDropped\":0}");
        }

        /* 连接段：端口/Device ID 变了只能断链重建（运行态全清，设计 §2.2） */
        if (changed.contains(ConfigSection.SESSION)) {
            SessionOutcome outcome = this.reloadSession(deviceId);
            if (outcome != SessionOutcome.OK) {
                return new SectionsApply(409, sectionsError("reconnect-failed",
                        "连接参数已保存，但重建会话失败（端口被占或配置非法）：" + outcome));
            }
            return new SectionsApply(200, "{\"ok\":true,\"effect\":\"reconnect\",\"changed\":"
                    + sectionsJson(changed)
                    + ",\"reset\":[\"link\",\"plans\",\"cycle\",\"pins\",\"edits\",\"counters\"],"
                    + "\"preserved\":[],\"plansKept\":0,\"pinsDropped\":0}");
        }

        try {
            com.shimizukenta.secssim.runtime.session.SectionApplyOutcome outcome =
                    this.launcher.applySections(deviceId, changed);
            if (outcome == null) {
                return new SectionsApply(409, sectionsError("not-running",
                        "这台设备当前没有可应用的运行实例（未启动或正在重启）"));
            }
            return new SectionsApply(200, "{\"ok\":true,\"effect\":\""
                    + ConfigSection.effectOf(changed).wire() + "\",\"changed\":" + sectionsJson(changed)
                    + ",\"reset\":" + resetJson(outcome) + ",\"preserved\":" + preservedJson(outcome)
                    + ",\"plansKept\":" + outcome.plansKept()
                    + ",\"pinsDropped\":" + outcome.pinsDropped() + "}");
        } catch (ConfigException e) {
            return new SectionsApply(422, problemsJson(List.of(String.valueOf(e.getMessage()))));
        } catch (IOException e) {
            return new SectionsApply(500, sectionsError("apply-failed", String.valueOf(e.getMessage())));
        }
    }

    /** 这次被重置的运行态项（界面提示用）。 */
    private static String resetJson(com.shimizukenta.secssim.runtime.session.SectionApplyOutcome outcome) {
        List<String> items = new ArrayList<>();
        /* 只有结构变了才会丢视图与目录编辑；变量/报告/事件的改动是换视图、不动运行态 */
        if (outcome.modelChanged()) {
            items.add("model");
            items.add("trace");
            items.add("edits");
        }
        if (outcome.valuesChanged()) {
            items.add("values");
        }
        /* 计划只有在"结构变更 → 全停装载"时才真被重置；
           目录变更走保留态换表，哪怕当前 0 条启用也不能说成"被重置" */
        if (outcome.scheduleChanged() && outcome.modelChanged()) {
            items.add("plans");
        }
        if (outcome.cycleChanged()) {
            items.add("cycle");
        }
        return jsonStrings(items);
    }

    /** 这次被保留的运行态项。 */
    private static String preservedJson(com.shimizukenta.secssim.runtime.session.SectionApplyOutcome outcome) {
        List<String> items = new ArrayList<>();
        /* 计划只在结构变更时全停；其余情况换表都保留启用态与锚点 */
        if (!outcome.modelChanged()) {
            items.add("plans");
        }
        if (!outcome.valuesChanged()) {
            items.add("pins");
        }
        if (!outcome.cycleChanged()) {
            items.add("cycle");
        }
        items.add("counters");
        return jsonStrings(items);
    }

    /** 使用这份配置的会话（按 launcher 的记录反查）。 */
    private List<SessionRuntime> sessionsUsing(String configName) {
        List<SessionRuntime> out = new ArrayList<>();
        for (SessionRuntime session : this.sessions.list()) {
            if (this.launcher.sourceOf(session.id()).filter(configName::equals).isPresent()) {
                out.add(session);
            }
        }
        return out;
    }

    /**
     * 会话是不是「开着」：端口在监听或在建链都算。
     *
     * <p>结构段（模型）改动会让整会话重建，所以拦的是「设备启动着」，
     * 而不是 GEM 的 ONLINE——主机没上线时设备照样在跑。</p>
     */
    private static boolean sessionOn(SessionRuntime session) {
        return session.state() == SessionState.OPEN || session.state() == SessionState.OPENING;
    }

    /** 运行时常量 vs 提交值：有差异的列出来（守卫 C）。 */
    private List<ConfigSectionGuards.ConstantOverwrite> constantOverwrites(
            List<SessionRuntime> using, Map<ConfigSection, JsonHub> payload) {
        Map<Integer, Double> runtime = new LinkedHashMap<>();
        for (SessionRuntime session : using) {
            if (session instanceof HsmsSessionRuntime hsms) {
                for (EquipmentConstantDef def : hsms.constants()) {
                    runtime.putIfAbsent(def.id(), def.value());
                }
            }
        }
        if (runtime.isEmpty()) {
            return List.of();
        }
        return ConfigSectionGuards.constantOverwrites("", payload, runtime);
    }

    /** 分段保存的审计（HTTP 层另有 config-save-section 记录，这里补段名与级别）。 */
    private void announceSectionSave(String name, List<ConfigSection> changed) {
        try {
            String sections = changed.stream().map(ConfigSection::wire)
                    .reduce((a, b2) -> a + "," + b2).orElse("");
            this.audit.record("local", "system", "config-section-save", name,
                    "sections=" + sections + " effect=" + ConfigSection.effectOf(changed).wire());
        } catch (RuntimeException e) {
            Log.error("记录分段保存审计失败: " + name, e);
        }
    }

    private static String problemsJson(List<String> problems) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"error\":\"config-invalid\",\"problems\":[");
        for (int i = 0; i < problems.size(); i += 1) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(problems.get(i))).append('"');
        }
        return sb.append("]}").toString();
    }

    private static String sectionsError(String code, String detail) {
        return "{\"ok\":false,\"error\":\"" + code + "\",\"detail\":\"" + escape(detail) + "\"}";
    }

    private static String conflictsJson(List<ConfigSectionGuards.Conflict> conflicts) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"error\":\"cycle-running-guard\",\"detail\":[");
        boolean first = true;
        for (ConfigSectionGuards.Conflict conflict : conflicts) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"kind\":\"").append(conflict.kind())
                    .append("\",\"name\":\"").append(escape(conflict.name()))
                    .append("\",\"ceids\":[");
            for (int i = 0; i < conflict.ceids().size(); i += 1) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(conflict.ceids().get(i));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    private static String overwritesJson(List<ConfigSectionGuards.ConstantOverwrite> overwrites) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ConfigSectionGuards.ConstantOverwrite item : overwrites) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"ecid\":").append(item.ecid())
                    .append(",\"runtime\":").append(item.runtime())
                    .append(",\"config\":").append(item.config()).append('}');
        }
        return sb.append(']').toString();
    }

    /** 一组字符串 → JSON 数组（reset / preserved 用）。 */
    private static String jsonStrings(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i += 1) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String sectionsJson(List<ConfigSection> sections) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ConfigSection section : sections) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(section.wire()).append('"');
        }
        return sb.append(']').toString();
    }

    private static String number(Long value) {
        return value == null ? "0" : String.valueOf(value);
    }

    /** JSON 字符串转义（本类只拼小对象，不引第三方）。 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i += 1) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }


    Long configModifiedMs(String name) {
        return this.configs.modifiedMs(name);
    }

    ConfigResult createConfig(String name) {
        ConfigResult result = this.configs.create(name);
        if (result.outcome() == ConfigOutcome.OK) {
            Log.info("新建配置 " + name);
        }
        return result;
    }

    /**
     * 删除配置（= 从设备清单里删掉一台设备）。
     *
     * <p>先把这份配置对应的会话停掉再删：配置没了端口却还在监听的话，
     * 界面上这台设备已经消失、端口仍被占着，下一次新建设备会莫名其妙撞端口。</p>
     *
     * @param name 文件名
     * @return 结果
     */
    ConfigResult deleteConfig(String name) {
        for (SessionRuntime session : this.sessions.list()) {
            if (this.launcher.sourceOf(session.id()).filter(name::equals).isPresent()) {
                this.closeSession(session.id());
            }
        }
        ConfigResult result = this.configs.delete(name);
        if (result.outcome() == ConfigOutcome.OK) {
            Log.info("删除配置 " + name);
        }
        return result;
    }

    String configTemplatesJson() {
        return DeviceTemplates.listJson();
    }

    String templatesJson() {
        return this.templates.listJson();
    }

    java.util.Optional<String> templateText(String id) {
        return this.templates.text(id);
    }

    ConfigResult saveTemplate(String id, String text) {
        return this.templates.save(id, text);
    }

    ConfigResult deleteTemplate(String id) {
        return this.templates.delete(id);
    }

    String paramTemplatesJson() {
        return this.paramTemplates.listJson();
    }

    ParamTemplateSaved saveParamTemplate(String id, String name, String description, String body) {
        return this.paramTemplates.save(id, name, description, body);
    }

    ConfigResult deleteParamTemplate(String id) {
        return this.paramTemplates.delete(id);
    }

    ConfigResult createConfig(String name, String templateId) {
        String custom = this.templates.customText(templateId).orElse(null);
        ConfigResult result = custom == null
                ? this.configs.create(name, templateId)
                : this.configs.createFromTemplateText(name, custom, templateId);
        if (result.outcome() == ConfigOutcome.OK) {
            Log.info("新建配置 " + name + "（模板: " + templateId + "）");
        }
        return result;
    }

    /**
     * 提示"存了但没生效"。
     *
     * <p>会话是在启动那一刻读配置读进内存的，之后再改文件它不会自己变。
     * 用户改完配置发现设备行为没变，第一反应是"编辑器根本没存"。
     * 这里直接把还要重载哪个会话说清楚，比让人自己猜要省事。
     * </p>
     */
    private void warnStaleSessions(String name) {
        for (SessionRuntime session : this.sessions.list()) {
            String source = this.launcher.sourceOf(session.id()).orElse(null);
            if (source != null && source.equals(name.trim())) {
                Log.info("配置已改，但会话 " + session.id() + " 仍在用旧配置，重载后才生效");
            }
        }
    }
}
