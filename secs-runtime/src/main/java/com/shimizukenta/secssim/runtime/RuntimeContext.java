package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.AuditSink;
import com.shimizukenta.secssim.api.http.ConfigOutcome;
import com.shimizukenta.secssim.api.http.ConfigResult;
import com.shimizukenta.secssim.api.http.EventOutcome;
import com.shimizukenta.secssim.api.http.SessionCreateRequest;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.api.http.SessionsJson;
import com.shimizukenta.secssim.api.http.TriggerOutcome;
import com.shimizukenta.secssim.app.audit.AuditLog;
import com.shimizukenta.secssim.app.config.ConfigException;
import com.shimizukenta.secssim.runtime.ConfigStore;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.NoticeEvent;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.event.MessageLog;
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.runtime.session.HostSimulator;
import com.shimizukenta.secssim.runtime.session.RuntimeEdits;

/**
 * 运行时上下文：把会话、配置、日志与主机模拟器接到 HTTP 接口上。
 *
 * <p>原来这是 {@code Main} 里一个八百行的内部类——{@code Main} 因此超过
 * 1200 行。拆出来之后，{@code Main} 只管"按参数启动什么"，
 * 这里管"接口怎么落到实际对象上"。</p>
 */
final class RuntimeContext implements ApiContext, AutoCloseable {

    /** 压测总时长预算：到点中止剩余条目，避免一次请求把后台线程占住几小时。 */
    private static final long BURST_BUDGET_MS = 5 * 60_000L;

    /** 压测执行器线程数。 */
    private static final int BURST_THREADS = 2;

    /** 等待执行的压测任务队列上限；满了直接拒绝，不无限排队。 */
    private static final int BURST_QUEUE = 4;

    private final String mode;
    private final String version;
    private final String extraHealthJson;
    private final Runnable shutdown;
    private final SessionManager sessions;
    private final SessionLauncher launcher;
    private final ConfigStore configs;
    private final TemplateStore templates;
    private final MessageLog messages;
    private final AuditLog audit;

    /**
     * 压测执行器：压测的 Thread.sleep 节流绝不能占 HTTP 请求线程。
     *
     * <p>线程数固定、队列有界：队列满时直接拒绝（回 burst-busy），
     * 而不是让请求排队等到统计已经失去意义。</p>
     */
    private final java.util.concurrent.ThreadPoolExecutor burstExecutor;

    /** 会话 → 最近一次压测结果/运行状态（GET /sessions/{id}/burst 用）。 */
    private final java.util.concurrent.ConcurrentMap<String, BurstState> burstStates =
            new java.util.concurrent.ConcurrentHashMap<>();

    RuntimeContext(String mode, String version, String extraHealthJson, Runnable shutdown,
            SessionManager sessions, SessionLauncher launcher, ConfigStore configs,
            MessageLog messages, AuditLog audit) {
        this.mode = mode;
        this.version = version;
        this.extraHealthJson = extraHealthJson;
        this.shutdown = shutdown;
        this.sessions = sessions;
        this.launcher = launcher;
        this.configs = configs;
        this.templates = new TemplateStore(configs.workDir());
        this.messages = messages;
        this.audit = audit;

        java.util.concurrent.atomic.AtomicInteger burstSeq = new java.util.concurrent.atomic.AtomicInteger();
        this.burstExecutor = new java.util.concurrent.ThreadPoolExecutor(
                BURST_THREADS, BURST_THREADS, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(BURST_QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "burst-" + burstSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.burstExecutor.allowCoreThreadTimeOut(true);
    }

    /**
     * 关闭上下文持有的后台资源：主机模拟器连接、压测执行器与在跑的压测。
     *
     * <p>以前停机只关会话与 HTTP，host-sim 的连接与采集线程、以及最多 2 个
     * 压测线程（可能在 {@code Thread.sleep} 里等 5 分钟）都靠 JVM 退出兜底。
     * 嵌入/测试宿主里这就变成泄漏。</p>
     */
    @Override
    public void close() {
        for (String hostId : java.util.List.copyOf(this.hostSims.keySet())) {
            com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.remove(hostId);
            if (sim != null) {
                try {
                    sim.close();
                } catch (RuntimeException e) {
                    Log.error("关闭主机模拟器失败（停机继续）: " + hostId, e);
                }
            }
        }
        this.hostsBySession.clear();

        for (BurstState state : this.burstStates.values()) {
            state.cancelRequested = true;
        }
        this.burstStates.clear();

        this.burstExecutor.shutdownNow();
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

    @Override
    public String sessionsJson() {
        /* 包一层带配置名:配置页要用它自动打开本设备的配置文件 */
        List<SessionRuntime> decorated = new ArrayList<>();
        for (SessionRuntime session : this.sessions.list()) {
            final String config = this.launcher.sourceOf(session.id())
                    .map(path -> path.getFileName().toString())
                    .orElse("");
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

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewJson(fromMs, toMs, buckets))
                .orElse(null);
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewJson(fromMs, toMs, buckets, svids, withEvents))
                .orElse(null);
    }

    @Override
    public String previewJson(String sessionId, long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents, String planId) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewJson(fromMs, toMs, buckets, svids, withEvents, planId))
                .orElse(null);
    }

    @Override
    public byte[] previewBinary(String sessionId, long fromMs, long toMs, int buckets) {
        return this.sessions.find(sessionId)
                .map(session -> session.previewBinary(fromMs, toMs, buckets))
                .orElse(null);
    }

    @Override
    public TriggerOutcome triggerCeid(String sessionId, int ceid) {
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

    @Override
    public TriggerOutcome triggerAlarm(String sessionId, int alid, boolean set, String text, int category) {
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

    @Override
    public String alarmsJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::alarmsJson).orElse(null);
    }

    @Override
    public String terminalMessagesJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::terminalMessagesJson).orElse(null);
    }

    @Override
    public String catalogJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::catalogJson).orElse(null);
    }

    @Override
    public String eventsJson(String sessionId) {
        return this.sessions.find(sessionId).map(SessionRuntime::eventsJson).orElse(null);
    }

    @Override
    public EventOutcome setEventEnabled(String sessionId, String planId, boolean enabled) {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        if (session == null) {
            return EventOutcome.UNKNOWN_SESSION;
        }
        return session.setEventEnabled(planId, enabled) ? EventOutcome.OK : EventOutcome.UNKNOWN_PLAN;
    }

    @Override
    public String messagesJson(int limit, String sessionId, String direction) {
        return this.messagesJson(limit, sessionId, direction, 0L, null, null);
    }

    @Override
    public String messagesJson(int limit, String sessionId, String direction,
                               long beforeMs, Integer stream, Integer function) {
        return this.messages == null ? null : this.messages.recentJson(limit, sessionId, direction, beforeMs, stream, function);
    }

    @Override
    public String messagesJsonl(int limit, String sessionId, String direction) {
        return this.messagesJsonl(limit, sessionId, direction, 0L, null, null);
    }

    @Override
    public String messagesJsonl(int limit, String sessionId, String direction,
                                long beforeMs, Integer stream, Integer function) {
        return this.messages == null ? null : this.messages.recentJsonl(limit, sessionId, direction, beforeMs, stream, function);
    }

    @Override
    public String auditJson(int limit) {
        return this.auditJson(limit, "", "");
    }

    @Override
    public String auditJson(int limit, String action, String actor) {
        return this.auditJson(limit, action, actor, 0L);
    }

    @Override
    public String auditJson(int limit, String action, String actor, long beforeMs) {
        return this.audit == null ? null : this.audit.recentJson(limit, action, actor, beforeMs);
    }

    /* ---------- 运行时编辑(设备页·事件/报告/状态变量) ---------- */

    private SessionRuntime sessionOr404(String sessionId) {
        return this.sessions.find(sessionId).orElse(null);
    }

    @Override
    public int addEventPlan(String sessionId, String planJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.addEventPlan(RuntimeEdits.parseEventPlan(planJson)) ? 201 : 409;
    }

    @Override
    public int updateEventPlan(String sessionId, String planId, String planJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.updateEventPlan(planId, RuntimeEdits.parseEventPlan(planJson)) ? 200 : 404;
    }

    @Override
    public int removeEventPlan(String sessionId, String planId) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.removeEventPlan(planId) ? 200 : 404;
    }

    @Override
    public int addReport(String sessionId, String reportJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.addReport(RuntimeEdits.parseReport(reportJson)) ? 201 : 409;
    }

    @Override
    public int updateReport(String sessionId, int rptid, String reportJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.updateReport(RuntimeEdits.parseReport(reportJson)) ? 200 : 404;
    }

    @Override
    public int addCeid(String sessionId, String ceidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.addCeid(RuntimeEdits.parseCeid(ceidJson)) ? 201 : 409;
    }

    @Override
    public int updateCeid(String sessionId, int ceid, String ceidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.updateCeid(RuntimeEdits.parseCeid(ceidJson)) ? 200 : 404;
    }

    @Override
    public int removeCeid(String sessionId, int ceid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.removeCeid(ceid) ? 200 : 404;
    }

    @Override
    public int removeReport(String sessionId, int rptid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.removeReport(rptid) ? 200 : 404;
    }

    @Override
    public int addSvid(String sessionId, String svidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.addSvid(RuntimeEdits.parseSvid(svidJson)) ? 201 : 409;
    }

    @Override
    public int updateSvid(String sessionId, int svid, String svidJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.updateSvid(RuntimeEdits.parseSvid(svidJson)) ? 200 : 404;
    }

    @Override
    public int removeSvid(String sessionId, int svid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.removeSvid(svid) ? 200 : 404;
    }

    @Override
    public int pinSvidValue(String sessionId, int svid, String valueJson) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.pinSvidValue(svid, RuntimeEdits.parsePinnedValue(valueJson)) ? 200 : 404;
    }

    @Override
    public int unpinSvidValue(String sessionId, int svid) {
        SessionRuntime session = sessionOr404(sessionId);
        if (session == null) {
            return 404;
        }
        return session.unpinSvidValue(svid) ? 200 : 404;
    }

    @Override
    public String sendSml(String sessionId, String sml) throws IOException {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        return session == null ? null : session.sendSml(sml);
    }

    @Override
    public SessionOutcome createSession(com.shimizukenta.secssim.api.http.SessionCreateRequest request) {
        try {
            SessionOverrides overrides = new SessionOverrides(
                    request.sessionId(), request.bindHost(), request.port(), request.deviceId());
            String id = this.launcher.create(request.config(),
                    overrides.isEmpty() ? SessionOverrides.NONE : overrides);
            Log.info("新建会话 " + id + "（配置 " + request.config() + "）");
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

    @Override
    public SessionOutcome closeSession(String sessionId) {
        /* 会话没了，压测状态也没有查询入口；一并清掉，避免 map 随会话增删无限增长 */
        BurstState burstState = this.burstStates.remove(sessionId);
        if (burstState != null) {
            /* 在跑的压测继续对着已关闭会话发，会把 2 个槽位占死 */
            burstState.cancelRequested = true;
        }
        /* 先收掉这台设备的主机模拟器：会话都删了，主机实例不该继续挂在列表里、
           占着一条已经死掉的连接。 */
        java.util.List<String> hostIds = this.hostsBySession.remove(sessionId);
        if (hostIds != null) {
            for (String hostId : hostIds) {
                com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.remove(hostId);
                if (sim != null) {
                    sim.close();
                }
            }
        }
        return this.launcher.close(sessionId) ? SessionOutcome.OK : SessionOutcome.UNKNOWN_SESSION;
    }

    /** 主机实例注册表：hostId → 模拟器（多主机：同一台设备可以开多个主机）。 */
    private final java.util.concurrent.ConcurrentMap<String, com.shimizukenta.secssim.runtime.session.HostSimulator> hostSims =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 会话 → 它开的主机实例 id 列表。 */
    private final java.util.concurrent.ConcurrentMap<String, java.util.List<String>> hostsBySession =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 主机实例 id 计数器。 */
    private final java.util.concurrent.atomic.AtomicLong hostSeq =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public SessionOutcome openHostSim(String sessionId) {
        return openHostSim(sessionId, null);
    }

    @Override
    public SessionOutcome openHostSim(String sessionId, String targetJson) {
        return openHostSim(sessionId, targetJson, null);
    }

    @Override
    public SessionOutcome openHostSim(String sessionId, String targetJson, String hostId) {
        com.shimizukenta.secssim.app.session.SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        String id = hostId == null || hostId.isBlank()
                ? "host-" + this.hostSeq.incrementAndGet()
                : hostId.trim();
        if (this.hostSims.containsKey(id)) {
            return SessionOutcome.CONFLICT;
        }

        /* v6:target 为空连本机(保留"设备已有真主机→409"检查);
           远程目标连别的 SECS,占用情况不可知,冲突由连接行为自然暴露 */
        com.shimizukenta.secssim.app.session.HsmsConnectionTarget override = null;
        boolean remote = targetJson != null && !targetJson.isBlank();
        if (remote) {
            try {
                com.shimizukenta.jsonhub.JsonHub target = com.shimizukenta.jsonhub.JsonHub.fromJson(targetJson);
                String host = target.get("host").optionalString().orElse("");
                int port = target.get("port").optionalInt().orElse(0);
                int deviceId = target.get("deviceId").optionalInt().orElse(0);
                if (host.isBlank() || port < 1 || port > 65535) {
                    Log.error("主机模拟远程目标非法（会话 " + sessionId + "）", null);
                    return SessionOutcome.FAILED;
                }
                override = new com.shimizukenta.secssim.app.session.HsmsConnectionTarget(host, port, deviceId);
            } catch (RuntimeException e) {
                Log.error("主机模拟远程目标解析失败（会话 " + sessionId + "）: " + e.getMessage(), null);
                return SessionOutcome.FAILED;
            }
        }

        /* 一台设备只允许一个本机主机：E5 的一台设备只接受一个选中的主机，
           再开一个会让"当前主机"与报文归属变得不可解释。远程实例连的是别的设备，不受此限。 */
        if (!remote) {
            java.util.List<String> existing = this.hostsBySession.get(sessionId);
            if (existing != null) {
                for (String existingHostId : existing) {
                    com.shimizukenta.secssim.runtime.session.HostSimulator previous = this.hostSims.get(existingHostId);
                    if (previous != null && previous.isOpen()) {
                        Log.info("会话 " + sessionId + " 已有主机 " + existingHostId + "，拒绝再建一个本机主机");
                        return SessionOutcome.CONFLICT;
                    }
                }
            }
        }

        try {
            com.shimizukenta.secssim.runtime.session.HostSimulator created =
                    com.shimizukenta.secssim.runtime.session.HostSimulator.open(id, session, override);
            /* containsKey → put 之间有窗口：并发同名实例会互相覆盖并泄漏一个连接。
               putIfAbsent 失败时把这次新建的这个关掉。 */
            com.shimizukenta.secssim.runtime.session.HostSimulator previous = this.hostSims.putIfAbsent(id, created);
            if (previous != null) {
                created.close();
                return SessionOutcome.CONFLICT;
            }
            this.hostsBySession.computeIfAbsent(sessionId, key -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(id);
            Log.info("主机模拟器 " + id + " 已连接会话 " + sessionId + (remote ? "（远程）" : ""));
            return SessionOutcome.OK;
        } catch (IOException e) {
            Log.error("主机模拟器连接失败（会话 " + sessionId + "）: " + e.getMessage(), null);
            return SessionOutcome.FAILED;
        } catch (IllegalArgumentException e) {
            Log.error("主机模拟器连接失败（设备已有主机）: " + e.getMessage(), null);
            return SessionOutcome.CONFLICT;
        }
    }

    @Override
    public SessionOutcome closeHostSim(String sessionId) {
        return closeHostSimById(this.hostIdOf(sessionId));
    }

    @Override
    public SessionOutcome closeHostSimById(String hostId) {
        com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.remove(hostId);
        if (sim == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        sim.close();
        /* 同步清理会话 → 实例的索引：否则关掉最后一个实例后，
           会话级接口还会拿着一个已经不存在的 id 去查。 */
        for (java.util.List<String> ids : this.hostsBySession.values()) {
            ids.remove(hostId);
        }
        this.hostsBySession.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return SessionOutcome.OK;
    }

    @Override
    public String hostSimJson(String sessionId) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
        String hostId = this.hostIdOf(sessionId);
        if (hostId.isEmpty()) {
            return "{\"open\":false}";
        }
        return this.hostSimJsonById(hostId);
    }

    /** 会话当前的主机实例 id（没开过、或实例已被关掉就是空串）。 */
    private String hostIdOf(String sessionId) {
        java.util.List<String> ids = this.hostsBySession.get(sessionId);
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        for (int i = ids.size() - 1; i >= 0; i--) {
            String id = ids.get(i);
            if (this.hostSims.containsKey(id)) {
                return id;
            }
            ids.remove(i);
        }
        return "";
    }

    /** 全部主机实例（JSON 数组）。 */
    @Override
    public String hostSimsJson() {
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        java.util.List<com.shimizukenta.jsonhub.JsonHub> items = new java.util.ArrayList<>();
        for (com.shimizukenta.secssim.runtime.session.HostSimulator sim : this.hostSims.values()) {
            items.add(com.shimizukenta.jsonhub.JsonHub.fromJson(sim.stateJson()));
        }
        return b.object(b.pair("hosts", b.array(items))).toJson();
    }

    @Override
    public String hostSimJsonById(String hostId) {
        com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            /* 实例不存在 = 未知资源，交给 HTTP 层回 404；不要伪装成"存在但已断开" */
            return null;
        }
        return sim.stateJson();
    }

    @Override
    public String sendHostSmlById(String hostId, String sml) throws IOException {
        com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return null;
        }
        try {
            return sim.sendSml(sml);
        } catch (IOException e) {
            /* 设备重载后端口重绑会让连接死亡：自动重连一次再发，
               与会话级 sendHostSml 的行为保持一致。 */
            if (!sim.isOpen()) {
                Log.info("主机模拟器连接已断（" + hostId + "），自动重连后重试");
                try {
                    sim.reconnect();
                } catch (IOException reconnectError) {
                    throw e;
                }
                return sim.sendSml(sml);
            }
            throw e;
        }
    }

    @Override
    public String setHostSimCollectorById(String hostId, String requestJson) {
        com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return "{\"ok\":false,\"error\":\"host-sim-not-open\"}";
        }
        try {
            sim.setCollectorConfig(requestJson);
            return "{\"ok\":true}";
        } catch (IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public String hostSimCollectorCommandById(String hostId, boolean start) {
        com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return "{\"ok\":false,\"error\":\"host-sim-not-open\"}";
        }
        try {
            if (start) {
                sim.collectorStartManual();
            } else {
                sim.collectorStopManual();
            }
            return "{\"ok\":true}";
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public String hostSimCollectorJsonById(String hostId) {
        com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            /* 实例不存在 → HTTP 404；已存在的实例未配置才是 configured:false */
            return null;
        }
        return sim.collectorJson();
    }

    @Override
    public String sendHostSml(String sessionId, String sml) throws IOException {
        com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
        if (sim == null || this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
    try {
        return sim.sendSml(sml);
    } catch (IOException e) {
        /* 设备重载后端口重绑会让 host 连接死亡：自动重连一次再发，
           让"改配置→重载→继续用主机模拟"不需要手动断开重连。
           v6:用 reconnect() 保留原目标(远程模式同样适用)。 */
        if (!sim.isOpen()) {
            Log.info("主机模拟器连接已断（会话 " + sessionId + "），自动重连后重试");
            try {
                sim.reconnect();
            } catch (IOException reconnectError) {
                throw e;
            }
            return sim.sendSml(sml);
        }
        throw e;
    }
}

@Override
public String setHostSimCollector(String sessionId, String requestJson) {
    com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
    if (sim == null || this.sessions.find(sessionId).isEmpty()) {
        return "{\"ok\":false,\"error\":\"host-sim-not-open\"}";
    }
    try {
        sim.setCollectorConfig(requestJson);
        return "{\"ok\":true}";
    } catch (IllegalArgumentException e) {
        return "{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
    }
}

@Override
public String hostSimCollectorCommand(String sessionId, boolean start) {
    com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
    if (sim == null || this.sessions.find(sessionId).isEmpty()) {
        return "{\"ok\":false,\"error\":\"host-sim-not-open\"}";
    }
    try {
        if (start) {
            sim.collectorStartManual();
        } else {
            sim.collectorStopManual();
        }
        return "{\"ok\":true}";
    } catch (IllegalStateException | IllegalArgumentException e) {
        return "{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
    }
}

@Override
public String hostSimCollectorJson(String sessionId) {
    if (this.sessions.find(sessionId).isEmpty()) {
        return null;
    }
    com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
    if (sim == null) {
        return "{\"configured\":false}";
    }
    return sim.collectorJson();
}

@Override
public String overviewJson() {
    com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
    java.util.List<com.shimizukenta.jsonhub.JsonHub> items = new java.util.ArrayList<>();
    for (SessionRuntime session : this.sessions.list()) {
        String fragment = session.overviewJson();
        if (fragment == null) {
            continue;
        }
        /* 注入 hostSim 占用标记:效果层不知道 hostSims 的存在,在这里拼。
           hostSims 按实例 id 存,这里必须经 hostsBySession 反查该会话的实例。 */
        java.util.List<String> hostIds = this.hostsBySession.get(session.id());
        boolean hostSim = hostIds != null && hostIds.stream().anyMatch(id -> {
            com.shimizukenta.secssim.runtime.session.HostSimulator sim = this.hostSims.get(id);
            return sim != null && sim.isOpen();
        });
        String patched = fragment.substring(0, fragment.length() - 1)
                + ",\"hostSim\":" + hostSim + "}";
        items.add(com.shimizukenta.jsonhub.JsonHub.fromJson(patched));
    }
    return b.object(b.pair("sessions", b.array(items))).toJson();
}

@Override
public String burst(String sessionId, String requestJson) {
    SessionRuntime session = this.sessions.find(sessionId).orElse(null);
    if (session == null) {
        return null;
    }

    com.shimizukenta.jsonhub.JsonHub request;
    try {
        request = com.shimizukenta.jsonhub.JsonHub.fromJson(requestJson);
    } catch (RuntimeException e) {
        return "{\"ok\":false,\"error\":\"bad-request\"}";
    }
    if (!request.isObject()) {
        return "{\"ok\":false,\"error\":\"bad-request\"}";
    }

    /* GET /sessions/{id}/burst 复用同一个入口：只回最近一次结果/运行中状态，不起新压测。 */
    com.shimizukenta.jsonhub.JsonHub status = request.get("status");
    if (status != null && status.optionalBoolean().orElse(false)) {
        BurstState state = this.burstStates.get(sessionId);
        return state == null ? "{\"ok\":true,\"running\":false,\"accepted\":false}" : state.statusJson();
    }

    if (!(session instanceof com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime runtime)) {
        return "{\"ok\":false,\"error\":\"unsupported-session\"}";
    }

    /* 可选字段缺失时 JsonHub.get 返回 null：直接 .optionalInt() 会 NPE 变成 500。
       压测请求里 intervalMs 之类本来就允许省略，这里逐个兜底。 */
    String kind = burstString(request, "kind", "");
    int count = burstInt(request, "count", 1);
    long intervalMs = burstLong(request, "intervalMs", 0L);
    if (count < 1 || count > 5000) {
        return "{\"ok\":false,\"error\":\"count-out-of-range\"}";
    }
    if (intervalMs < 0 || intervalMs > 60_000L) {
        return "{\"ok\":false,\"error\":\"interval-out-of-range\"}";
    }

    /* 参数校验必须在请求线程里做完：后台任务再报错，调用方已经拿到 202 了。 */
    String sml = "";
    int ceid = 0;
    int alid = 0;
    String text = "";
    int category = 4;
    switch (kind) {
        case "message" -> {
            sml = burstString(request, "sml", "");
            if (sml.isBlank()) {
                return "{\"ok\":false,\"error\":\"missing-sml\"}";
            }
        }
        case "ceid" -> {
            ceid = burstInt(request, "ceid", 0);
            if (ceid < 1) {
                return "{\"ok\":false,\"error\":\"missing-ceid\"}";
            }
        }
        case "alarm" -> {
            alid = burstInt(request, "alid", 0);
            if (alid < 1) {
                return "{\"ok\":false,\"error\":\"missing-alid\"}";
            }
            text = burstString(request, "text", "");
            category = burstInt(request, "category", 4);
        }
        default -> {
            return "{\"ok\":false,\"error\":\"unknown-kind\"}";
        }
    }

    /* 离线时 emitAlarm 只是入队、真实发送在 ACK 线程里失败：
       不先挡一道，报警风暴会把"什么都没发出去"报成成功。
       与单发的 triggerAlarm 用同一条判据，两条路径才不会互相矛盾。 */
    boolean sendable = runtime.state() == SessionState.OPEN && runtime.hasPeer();

    BurstState state = this.burstStates.computeIfAbsent(sessionId, key -> new BurstState());
    synchronized (state) {
        if (state.running) {
            return "{\"ok\":false,\"error\":\"burst-running\"}";
        }
        /* 先写好进度字段再置 running：GET 看到 running=true 时 startedAt/count 一定可见 */
        state.startedAt = System.currentTimeMillis();
        state.count = count;
        state.running = true;
    }

    /* lambda 只能捕获 effectively final 的局部变量，先抄一份。 */
    final String burstKind = kind;
    final String burstSml = sml;
    final int burstCeid = ceid;
    final int burstAlid = alid;
    final String burstText = text;
    final int burstCategory = category;

    try {
        this.burstExecutor.execute(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                BurstStats stats = runBurst(count, intervalMs, sendable,
                        () -> state.cancelRequested, index -> {
                    switch (burstKind) {
                        case "message" -> runtime.sendSml(burstSml);
                        case "ceid" -> runtime.emitCeid(burstCeid);
                        case "alarm" -> runtime.emitAlarm(burstAlid, index % 2 == 0, burstText, burstCategory);
                        default -> { }
                    }
                });
                state.finish(burstResultJson(stats, count, System.currentTimeMillis() - startedAt));
            } catch (RuntimeException e) {
                Log.error("压测执行异常（会话 " + sessionId + "）", e);
                state.finish("{\"ok\":false,\"error\":\"burst-failed\",\"sent\":0,\"failed\":0"
                        + ",\"durationMs\":" + (System.currentTimeMillis() - startedAt)
                        + ",\"latencyP50\":0,\"latencyP95\":0,\"latencyMax\":0}");
            } finally {
                /* 就算抛出 Error 也不能让 running 永远为 true，否则这个会话再也压测不了 */
                state.running = false;
            }
        });
    } catch (java.util.concurrent.RejectedExecutionException e) {
        state.running = false;
        return "{\"ok\":false,\"error\":\"burst-busy\"}";
    }

    /* 202：后台已受理。字段不能少——前端仍按 BurstResult 解析，
       缺字段会显示 undefined；真实统计随后从 GET 取。 */
    return "{\"ok\":true,\"accepted\":true,\"running\":true"
            + ",\"count\":" + count
            + ",\"sent\":0,\"failed\":0,\"skipped\":0,\"aborted\":false"
            + ",\"durationMs\":0,\"latencyP50\":0,\"latencyP95\":0,\"latencyMax\":0}";
}

/** 单会话压测状态：最近一次完成的统计 + 是否运行中。 */
private static final class BurstState {

    volatile boolean running;
    /** 取消请求：会话被关或整体停机时置位，压测在下一轮检查后退出。 */
    volatile boolean cancelRequested;
    volatile long startedAt;
    volatile int count;
    volatile String lastResult = "{\"ok\":true,\"running\":false,\"accepted\":false}";

    synchronized void finish(String result) {
        this.lastResult = result;
        this.running = false;
    }

    String statusJson() {
        if (this.running) {
            return "{\"ok\":true,\"accepted\":true,\"running\":true"
                    + ",\"count\":" + this.count
                    + ",\"startedAt\":" + this.startedAt + "}";
        }
        return this.lastResult;
    }
}

/** 压测动作：第 {@code index} 条（0 起）。 */
@FunctionalInterface
private interface BurstAction {

    void perform(int index) throws Exception;
}

/** 一次压测的统计中间量。 */
private static final class BurstStats {

    final java.util.List<Long> latencies = new java.util.ArrayList<>();
    int failed;
    int skipped;
    boolean aborted;
}

/** 在后台线程里跑一条压测：总时长超预算就中止剩余条目。 */
private static BurstStats runBurst(int count, long intervalMs, boolean sendable,
                                   java.util.function.BooleanSupplier cancelled, BurstAction action) {
    BurstStats stats = new BurstStats();
    long startedAt = System.currentTimeMillis();
    long deadline = startedAt + BURST_BUDGET_MS;
    for (int i = 0; i < count; i++) {
        if (cancelled.getAsBoolean()) {
            stats.skipped = count - i;
            stats.aborted = true;
            break;
        }
        if (System.currentTimeMillis() >= deadline) {
            stats.skipped = count - i;
            stats.aborted = true;
            break;
        }
        long t0 = System.currentTimeMillis();
        if (!sendable) {
            stats.failed++;
        } else {
            try {
                action.perform(i);
                stats.latencies.add(System.currentTimeMillis() - t0);
            } catch (Exception e) {
                stats.failed++;
            }
        }
        if (!pace(intervalMs, startedAt, i, deadline)) {
            stats.skipped = count - i - 1;
            stats.aborted = true;
            break;
        }
    }
    return stats;
}

/** 统计 JSON；sent/failed/durationMs/latencyP50/P95/Max 与前端 BurstResult 对齐。 */
private static String burstResultJson(BurstStats stats, int count, long durationMs) {
    java.util.List<Long> latencies = stats.latencies;
    latencies.sort(Long::compare);
    java.util.function.IntFunction<Long> pick = rank -> latencies.isEmpty()
            ? 0L : latencies.get(Math.min(latencies.size() - 1, rank));
    return "{\"ok\":true,\"accepted\":true,\"running\":false"
            + ",\"count\":" + count
            + ",\"sent\":" + latencies.size()
            + ",\"failed\":" + stats.failed
            + ",\"skipped\":" + stats.skipped
            + ",\"aborted\":" + stats.aborted
            + ",\"durationMs\":" + durationMs
            + ",\"latencyP50\":" + pick.apply((int) (latencies.size() * 0.5))
            + ",\"latencyP95\":" + pick.apply((int) (latencies.size() * 0.95))
            + ",\"latencyMax\":" + pick.apply(latencies.size() - 1)
            + "}";
}

/** 压测节奏：按起始时刻对齐间隔，且不越过总时长预算。 */
private static boolean pace(long intervalMs, long startedAt, int index, long deadline) {
    if (intervalMs <= 0L) {
        return System.currentTimeMillis() < deadline;
    }
    long nextAt = startedAt + (index + 1L) * intervalMs;
    if (nextAt >= deadline) {
        return false;
    }
    long wait = nextAt - System.currentTimeMillis();
    if (wait > 0L) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    return true;
}

/** 可选整数：字段缺失时给默认值（JsonHub.get 对缺失键返回 null）。 */
private static int burstInt(com.shimizukenta.jsonhub.JsonHub request, String key, int fallback) {
    com.shimizukenta.jsonhub.JsonHub value = request.get(key);
    return value == null ? fallback : value.optionalInt().orElse(fallback);
}

/** 可选长整数：字段缺失时给默认值。 */
private static long burstLong(com.shimizukenta.jsonhub.JsonHub request, String key, long fallback) {
    com.shimizukenta.jsonhub.JsonHub value = request.get(key);
    return value == null ? fallback : value.optionalLong().orElse(fallback);
}

/** 可选字符串：字段缺失时给默认值。 */
private static String burstString(com.shimizukenta.jsonhub.JsonHub request, String key, String fallback) {
    com.shimizukenta.jsonhub.JsonHub value = request.get(key);
    return value == null ? fallback : value.optionalString().orElse(fallback);
}

    @Override
    public SessionOutcome reloadSession(String sessionId) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        if (this.launcher.sourceOf(sessionId).isEmpty()) {
            return SessionOutcome.NOT_RELOADABLE;
        }

        try {
            this.launcher.reload(sessionId);
            Log.info("会话 " + sessionId + " 已按配置重载");
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

    @Override
    public String configPreviewJson(String text, long fromMs, long toMs, int buckets) {
        return configPreviewJson(text, fromMs, toMs, buckets, java.util.Set.of(), true);
    }

    @Override
    public String configPreviewJson(String text, long fromMs, long toMs, int buckets,
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
        } catch (Exception e) {
            Log.error("配置草稿预览失败: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String configsJson() {
        return this.configs.listJson();
    }

    @Override
    public ApiContext.ConfigSnapshot configSnapshot(String name) {
        return this.configs.readSnapshot(name);
    }

    @Override
    public String configText(String name) {
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

    @Override
    public List<String> validateConfig(String text) {
        return ConfigStore.validate(text);
    }

    @Override
    public ConfigResult saveConfig(String name, String text) {
        return this.saveConfig(name, text, null);
    }

    @Override
    public ConfigResult saveConfig(String name, String text, Long expectedModifiedMs) {
        ConfigResult result = this.configs.save(name, text, expectedModifiedMs);
        if (result.outcome() == ConfigOutcome.OK) {
            this.warnStaleSessions(name);
        }
        return result;
    }

    @Override
    public Long configModifiedMs(String name) {
        return this.configs.modifiedMs(name);
    }

    @Override
    public ConfigResult createConfig(String name) {
        ConfigResult result = this.configs.create(name);
        if (result.outcome() == ConfigOutcome.OK) {
            Log.info("新建配置 " + name);
        }
        return result;
    }

    @Override
    public String configTemplatesJson() {
        return DeviceTemplates.listJson();
    }

    @Override
    public String templatesJson() {
        return this.templates.listJson();
    }

    @Override
    public java.util.Optional<String> templateText(String id) {
        return this.templates.text(id);
    }

    @Override
    public ConfigResult saveTemplate(String id, String text) {
        return this.templates.save(id, text);
    }

    @Override
    public ConfigResult deleteTemplate(String id) {
        return this.templates.delete(id);
    }

    @Override
    public ConfigResult createConfig(String name, String templateId) {
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
            Path source = this.launcher.sourceOf(session.id()).orElse(null);
            if (source != null && source.getFileName().toString().equals(name.trim())) {
                Log.info("配置已改，但会话 " + session.id() + " 仍在用旧配置，重载后才生效");
            }
        }
    }
}
