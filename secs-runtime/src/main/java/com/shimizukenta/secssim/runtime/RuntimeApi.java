package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.EventOutcome;
import com.shimizukenta.secssim.api.http.SessionCreateRequest;
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
    private final MessageLog messages;
    private final AuditLog audit;
    private final HostSimRegistry hosts;
    private final BurstRunner bursts;

    /** 运行态改动写穿到 runtime.db；失败只记日志，不影响请求结果。 */
    private final RuntimePersister persister;

    RuntimeApi(SessionManager sessions, SessionLauncher launcher, ConfigStore configs,
            TemplateStore templates, MessageLog messages, AuditLog audit,
            HostSimRegistry hosts, BurstRunner bursts, RuntimePersister persister) {
        this.sessions = sessions;
        this.launcher = launcher;
        this.configs = configs;
        this.templates = templates;
        this.messages = messages;
        this.audit = audit;
        this.hosts = hosts;
        this.bursts = bursts;
        this.persister = persister;
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
                @Override public boolean setEventEnabled(String planId, boolean enabled) { return session.setEventEnabled(planId, enabled); }
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
        if (!session.setEventEnabled(planId, enabled)) {
            return EventOutcome.UNKNOWN_PLAN;
        }
        /* 启用态是「断开保留、重启恢复」的一部分：v1 与 v2 两条路径都在这里落库，
           next_fire_at 交给调度器推进，不写死一个可能与 OnCondition 冲突的时刻。 */
        this.persister.planState(sessionId, planId, enabled);
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

    SessionOutcome createSession(SessionCreateRequest request) {
        try {
            SessionOverrides overrides = new SessionOverrides(
                    request.sessionId(), request.bindHost(), request.port(), request.deviceId());
            String id = this.launcher.create(request.config(),
                    overrides.isEmpty() ? SessionOverrides.NONE : overrides);
            Log.info("新建会话 " + id + "（配置 " + request.config() + "）");
            /* 会话一注册就有 device_link 行（Bootstrap 的注册观察者写的），
               这里补一次相位：刚建好可能已经 bind 成功（LISTENING）或失败（FAILED）。 */
            this.sessions.find(id).ifPresent(session ->
                    this.persister.devicePhase(id, phaseName(session.linkPhase()), session.lastError()));
            return SessionOutcome.OK;
        } catch (ConfigException e) {
            /* 配置名非法、文件读不了、内容编译不过——都是"这台设备起不来" */
            Log.error("新建会话失败（配置）: " + e.getMessage(), null);
            return SessionOutcome.FAILED;
        } catch (IOException e) {
            /* 到这一步配置已经是好的，只剩端口没绑上 */
            Log.error("新建会话失败（端口绑定）: " + e.getMessage(), null);
            return SessionOutcome.FAILED;
        } catch (IllegalArgumentException e) {
            Log.error("新建会话失败（标识冲突）: " + e.getMessage(), null);
            return SessionOutcome.CONFLICT;
        }
    }

    SessionOutcome closeSession(String sessionId) {
        /* 会话没了，压测状态也没有查询入口；一并清掉，避免 map 随会话增删无限增长 */
        this.bursts.cancel(sessionId);
        /* 先收掉这台设备的主机模拟器：会话都删了，主机实例不该继续挂在列表里、
           占着一条已经死掉的连接。 */
        this.hosts.closeAllOf(sessionId);
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        String lastError = session == null ? null : session.lastError();
        boolean closed = this.launcher.close(sessionId);
        if (closed) {
            /* 关闭 = 销毁（v1 语义）：按设计 §2.4「destroy 才清」把运行态编辑一起清掉。
               行本身留着并标 STOPPED，界面据此显示「已断开」而不是凭空消失。 */
            this.persister.clearEdits(sessionId);
            this.persister.devicePhase(sessionId, phaseName(SessionRuntime.LinkPhase.STOPPED), lastError);
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
            RuntimePersister.Edits before = this.persister.snapshot(sessionId);
            this.persister.clearEdits(sessionId);
            try {
                this.launcher.reload(sessionId);
            } catch (ConfigException | IOException failure) {
                this.persister.restore(sessionId, before);
                this.sessions.find(sessionId).ifPresent(session -> {
                    if (session instanceof HsmsSessionRuntime hsms) {
                        this.persister.replay(hsms, sessionId);
                    }
                });
                throw failure;
            }
            Log.info("会话 " + sessionId + " 已按配置重载");
            this.sessions.find(sessionId).ifPresent(session ->
                    this.persister.devicePhase(sessionId, phaseName(session.linkPhase()), session.lastError()));
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
                if (withEvents && !config.schedule().plans().isEmpty()) {
                    events = com.shimizukenta.secssim.runtime.session.EventSimulator.simulate(
                                    config.schedule(), values, config.spec().seed(), fromMs, toMs,
                                    com.shimizukenta.secssim.runtime.session.EventSimulator.DEFAULT_MAX_EVENTS)
                            .stream()
                            .map(fire -> new com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewEvent(
                                    fire.atMs(), fire.ceid(), fire.planId()))
                            .toList();
                }
                com.shimizukenta.secssim.runtime.session.PreviewSupport.PreviewData data =
                        com.shimizukenta.secssim.runtime.session.PreviewSupport.compute(
                                values, compiled.equipment(), fromMs, toMs, buckets, svids, events);
                return com.shimizukenta.secssim.runtime.session.PreviewSupport.toJson(data);
            } finally {
                java.nio.file.Files.deleteIfExists(temp);
            }
        } catch (IllegalArgumentException e) {
            /* 参数类问题（分桶数、时间窗等）：界面按"草稿非法"展示（HTTP 422） */
            Log.error("配置草稿预览参数无效: " + e.getMessage(), e);
            return null;
        } catch (com.shimizukenta.secssim.app.config.ConfigException
                | com.shimizukenta.secssim.core.template.GraphException
                | com.shimizukenta.secssim.core.template.ExpressionException e) {
            /* 草稿的语法/字段/模板表达式错误都属于用户输入，继续映射为 null（422）。
               模板参数引用只在求值期才暴露（图构建期不校验 p.*），必须一起接住，
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
