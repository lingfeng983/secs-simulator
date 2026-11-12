package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.shimizukenta.secssim.api.http.CollectorOutcome;
import com.shimizukenta.secssim.api.http.CollectorResult;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.runtime.hostsim.HostSimulator;
import com.shimizukenta.secssim.store.HostRepository;
import com.shimizukenta.secssim.store.RuntimeStateRepository;

/**
 * 主机模拟器注册表（3.0：主机是与设备完全无关的独立实体，设计 §2.4）。
 *
 * <p>实例按持久 hostId 全局唯一寻址，数量不设上限，多台可指向同一地址；
 * 不再有 {@code hostsBySession} 反向索引、不再有「一台设备一个本机主机」检查、
 * 设备断开/删除不再触碰主机。实例的持久行在 {@code host} 表（HostRepository），
 * 链路相位在 {@code host_link} 表（RuntimeStateRepository）。</p>
 *
 * <p>并发约定：注册表是 ConcurrentMap；「同一 id 并发打开」由 putIfAbsent 兜底——
 * 失败的一方把自己的连接关掉再返回冲突。</p>
 */
final class HostSimRegistry {

    private final SessionManager sessions;
    private final com.shimizukenta.secssim.app.event.EventBus eventBus;
    private final HostRepository hosts;
    private final RuntimeStateRepository linkState;

    /** 主机实例注册表：hostId → 模拟器。 */
    private final ConcurrentMap<String, HostSimulator> hostSims = new ConcurrentHashMap<>();

    HostSimRegistry(SessionManager sessions, com.shimizukenta.secssim.app.event.EventBus eventBus,
                    HostRepository hosts, RuntimeStateRepository linkState) {
        this.sessions = sessions;
        this.eventBus = eventBus;
        this.hosts = hosts;
        this.linkState = linkState;
    }

    /**
     * 新建主机实例并连接（v2 主入口：目标任意，与设备无关）。
     *
     * @param name          显示名
     * @param target        连接目标
     * @param autoReconnect 失败后是否自动重连
     * @return 新实例 id；冲突/失败经 SessionOutcome 表达
     */
    synchronized CreateResult open(String name, HsmsConnectionTarget target, boolean autoReconnect) {
        String id = this.hosts.create(name, target.bindHost(), target.port(), target.deviceId(),
                "{}", autoReconnect, "");
        HostSimulator sim = attach(id);
        if (sim == null) {
            return new CreateResult(id, SessionOutcome.FAILED);
        }
        try {
            sim.connect();
        } catch (IOException e) {
            /* 连接失败：实例留在注册表（FAILED 终态可重连），如实报告 */
            return new CreateResult(id, SessionOutcome.FAILED);
        }
        return new CreateResult(id, SessionOutcome.OK);
    }

    /** 打开结果：实例 id + 结果码（FAILED 时实例已存在但停在 FAILED）。 */
    record CreateResult(String hostId, SessionOutcome outcome) {
    }

    /**
     * 装配一个已持久化的实例（内存 + 相位监听），失败返回 null。
     */
    private HostSimulator attach(String hostId) {
        Optional<HostRepository.HostView> view = this.hosts.find(hostId);
        if (view.isEmpty()) {
            return null;
        }
        HostSimulator sim = HostSimulator.fromView(view.get(), this.eventBus);
        sim.onPhase((phaseId, phase) ->
                this.linkState.upsertHostLink(phaseId, phase.name(),
                        view.get().targetHost() + ":" + view.get().targetPort(),
                        view.get().settingRevision()));
        HostSimulator previous = this.hostSims.putIfAbsent(hostId, sim);
        return previous == null ? sim : previous;
    }

    /** 实例是否在册。 */
    boolean exists(String hostId) {
        return this.hostSims.containsKey(hostId);
    }

    /**
     * v1 兼容入口：为会话打开主机实例。session 只在「没给目标」时一次性解析成
     * 目标地址（设备的监听地址），此后实例与那台设备再无关系（设计 §2.5）。
     *
     * @param sessionId  宿主会话（解析默认目标用）
     * @param targetJson 远程目标；空 = 用会话的监听地址
     * @param name       显示名；空 = 用 hostId
     * @return 打开结果
     */
    CreateResult openForSession(String sessionId, String targetJson, String name) {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        if (session == null) {
            return new CreateResult("", SessionOutcome.UNKNOWN_SESSION);
        }
        HsmsConnectionTarget target;
        if (targetJson != null && !targetJson.isBlank()) {
            try {
                com.shimizukenta.jsonhub.JsonHub parsed = com.shimizukenta.jsonhub.JsonHub.fromJson(targetJson);
                String host = parsed.get("host").optionalString().orElse("");
                int port = parsed.get("port").optionalInt().orElse(0);
                int deviceId = parsed.get("deviceId").optionalInt().orElse(0);
                if (host.isBlank() || port < 1 || port > 65535) {
                    Log.error("主机模拟远程目标非法（会话 " + sessionId + "）", null);
                    return new CreateResult("", SessionOutcome.FAILED);
                }
                target = new HsmsConnectionTarget(host, port, deviceId);
            } catch (RuntimeException e) {
                Log.error("主机模拟远程目标解析失败（会话 " + sessionId + "）: " + e.getMessage(), null);
                return new CreateResult("", SessionOutcome.FAILED);
            }
        } else {
            target = session.hostTarget();
            if (target == null) {
                return new CreateResult("", SessionOutcome.FAILED);
            }
        }
        return open(name == null || name.isBlank() ? sessionId : name, target, false);
    }

    /** 全部实例的 JSON 数组（内存态相位为准）。 */
    String allJson() {
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        List<com.shimizukenta.jsonhub.JsonHub> items = new java.util.ArrayList<>();
        for (HostSimulator sim : this.hostSims.values()) {
            items.add(com.shimizukenta.jsonhub.JsonHub.fromJson(sim.stateJson()));
        }
        return b.object(b.pair("hosts", b.array(items))).toJson();
    }

    /** 单实例状态 JSON；实例不存在返回 {@code null}（HTTP 404）。 */
    String jsonById(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            /* 实例不在内存 = 不存在；持久行由 v2 /hosts 列表给出断开态 */
            return null;
        }
        return sim.stateJson();
    }

    /** 断开一个实例（实例保留，可重连）。 */
    SessionOutcome disconnectById(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        sim.disconnect();
        return SessionOutcome.OK;
    }

    /** 重连一个实例。 */
    SessionOutcome connectById(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        try {
            if (sim.phase() == HostSimulator.HostLinkPhase.DISCONNECTED || !sim.isOpen()) {
                sim.disconnect();
            }
            sim.connect();
            return SessionOutcome.OK;
        } catch (IOException e) {
            Log.error("主机重连失败: " + hostId + ": " + e.getMessage(), null);
            return SessionOutcome.FAILED;
        }
    }

    /** 使设置生效（按最新设置重连）。 */
    SessionOutcome applyById(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        try {
            sim.apply();
            return SessionOutcome.OK;
        } catch (IOException e) {
            Log.error("主机使设置生效失败: " + hostId + ": " + e.getMessage(), null);
            return SessionOutcome.FAILED;
        }
    }

    /**
     * 删除实例（显式销毁 + 持久行软删）。断开态才允许的检查在 HTTP 层。
     */
    SessionOutcome destroyById(String hostId) {
        HostSimulator sim = this.hostSims.remove(hostId);
        if (sim != null) {
            sim.destroy();
        }
        try {
            this.hosts.delete(hostId);
        } catch (com.shimizukenta.secssim.store.StoreException e) {
            if (e.kind() == com.shimizukenta.secssim.store.StoreException.Kind.NOT_FOUND) {
                return SessionOutcome.UNKNOWN_SESSION;
            }
            throw e;
        }
        return SessionOutcome.OK;
    }

    /**
     * 实例级发送：按 id 找实例。
     *
     * @param hostId 实例 id
     * @param sml    SML 文本
     * @return 响应文本；实例不存在返回 {@code null}
     * @throws IOException 发送失败（会自动重连重试一次）
     */
    String sendById(String hostId, String sml) throws IOException {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return null;
        }
        return this.send(sim, sml, hostId);
    }

    /**
     * 发送并在连接已死时自动重连重试一次。
     */
    private String send(HostSimulator sim, String sml, String who) throws IOException {
        try {
            return sim.sendSml(sml);
        } catch (IOException e) {
            if (!sim.isOpen()) {
                Log.info("主机连接已断（" + who + "），自动重连后重试");
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

    /** 实例级：下发采集器配置（同步持久到 host.collector_json）。 */
    CollectorResult setCollectorById(String hostId, String requestJson) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        try {
            sim.setCollectorConfig(requestJson);
            /* 采集配置属于实例设置：持久化，重连/重启后恢复 */
            this.hosts.find(hostId).ifPresent(view ->
                    this.hosts.save(hostId, view.name(), view.targetHost(), view.targetPort(),
                            view.targetDeviceIdNum(), requestJson, view.autoReconnect(), view.note()));
            return CollectorResult.ok();
        } catch (IllegalArgumentException e) {
            return CollectorResult.invalid(e.getMessage());
        }
    }

    /** 实例级：启停采集。 */
    CollectorResult commandById(String hostId, boolean start) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        return this.command(sim, start);
    }

    /** 实例级：采集器状态 JSON；实例不存在返回 {@code null}（HTTP 404）。 */
    String collectorJsonById(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return null;
        }
        return sim.collectorJson();
    }

    /** 启停采集；"没有配置"是状态冲突（409），不是请求非法。 */
    private CollectorResult command(HostSimulator sim, boolean start) {
        try {
            if (start) {
                sim.collectorStartManual();
            } else {
                sim.collectorStopManual();
            }
            return CollectorResult.ok();
        } catch (IllegalStateException e) {
            return CollectorResult.notConfigured(e.getMessage());
        } catch (IllegalArgumentException e) {
            return CollectorResult.invalid(e.getMessage());
        }
    }

    /**
     * 从目标读取变量目录（S1F11 → S1F12）并持久化到实例行。
     *
     * @return {@code {"ok":true,"count":n}}；目标不支持时 {@code {"ok":false,...}}
     */
    String refreshVariables(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return "{\"ok\":false,\"error\":\"host-sim-not-open\"}";
        }
        try {
            int count = sim.refreshVariables();
            this.hosts.saveVariableCatalog(hostId, sim.catalogJson());
            return "{\"ok\":true,\"count\":" + count + "}";
        } catch (IOException e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /** 声明变量解码类型并持久化。 */
    boolean declareVariableType(String hostId, int svid, String type) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return false;
        }
        sim.declareType(svid, type);
        this.hosts.saveVariableCatalog(hostId, sim.catalogJson());
        return true;
    }

    /** 实例变量目录 JSON；实例不存在返回 null。 */
    String variablesJson(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        if (sim == null) {
            return null;
        }
        return sim.catalogJson();
    }

    /**
     * 主机实例句柄：给压测这类需要"反复发同一条"的调用方。
     */
    HostHandle handle(String hostId) {
        HostSimulator sim = this.hostSims.get(hostId);
        return sim == null ? null : new HostHandle(this, sim);
    }

    /** 主机实例句柄：{@link #open()} 判活，{@link #send(String)} 发一条 SML。 */
    record HostHandle(HostSimRegistry registry, HostSimulator sim) {

        /** 实例当前是否可用。 */
        boolean open() {
            return this.sim.isOpen();
        }

        /**
         * 发一条 SML（连接断了自动重连一次）。
         *
         * @param sml SML 文本
         * @throws IOException 发送失败
         */
        void send(String sml) throws IOException {
            this.registry.send(this.sim, sml, this.sim.hostId());
        }
    }

    /** 启动装配：把 host 表里的持久实例装回内存（全部断开态；不自动连接）。 */
    void loadPersisted() {
        for (HostRepository.HostView view : this.hosts.list()) {
            if (this.hostSims.containsKey(view.id())) {
                continue;
            }
            attach(view.id());
        }
        Log.info("主机实例已从配置库装回内存 " + this.hostSims.size() + " 台（全部断开态）");
    }

    /** 停机：断开所有实例（实例行保留，重启后仍是列表里的一员）。 */
    void close() {
        for (String hostId : List.copyOf(this.hostSims.keySet())) {
            HostSimulator sim = this.hostSims.get(hostId);
            if (sim != null) {
                try {
                    sim.disconnect();
                } catch (RuntimeException e) {
                    Log.error("关闭主机实例失败（停机继续）: " + hostId, e);
                }
            }
        }
    }

    /* ---------- v1 会话级兼容（设计 §2.5：session 只在创建瞬间解析目标） ---------- */

    /** 按显示名找实例（v1 的"会话的主机"≈ 以会话名建的主机）。 */
    private HostSimulator findByName(String name) {
        for (HostSimulator sim : this.hostSims.values()) {
            if (sim.name().equals(name)) {
                return sim;
            }
        }
        return null;
    }

    /** v1:会话的主机状态（找不到绑定实例时回 allJson 里的第一台，没有则 open:false）。 */
    String jsonOfSession(String sessionId) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
        HostSimulator sim = findByName(sessionId);
        if (sim == null) {
            return "{\"open\":false}";
        }
        return sim.stateJson();
    }

    /** v1:会话级发送。 */
    String sendBySession(String sessionId, String sml) throws IOException {
        if (this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
        HostSimulator sim = findByName(sessionId);
        if (sim == null) {
            return null;
        }
        return this.send(sim, sml, sessionId);
    }

    /** v1:会话级下发采集配置。 */
    CollectorResult setCollectorBySession(String sessionId, String requestJson) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        HostSimulator sim = findByName(sessionId);
        if (sim == null) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        try {
            sim.setCollectorConfig(requestJson);
            return CollectorResult.ok();
        } catch (IllegalArgumentException e) {
            return CollectorResult.invalid(e.getMessage());
        }
    }

    /** v1:会话级启停采集。 */
    CollectorResult commandBySession(String sessionId, boolean start) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        HostSimulator sim = findByName(sessionId);
        if (sim == null) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        return this.command(sim, start);
    }

    /** v1:会话级采集器状态。 */
    String collectorJsonBySession(String sessionId) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
        HostSimulator sim = findByName(sessionId);
        if (sim == null) {
            return "{\"configured\":false}";
        }
        return sim.collectorJson();
    }

    /** v1:按名称断开（closeHostSim(sessionId) 的兼容路径）。 */
    SessionOutcome disconnectByName(String sessionId) {
        HostSimulator sim = findByName(sessionId);
        if (sim == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        sim.disconnect();
        return SessionOutcome.OK;
    }

    /** 会话删除时不再触碰主机（3.0：无级联）——保留空实现给旧调用点。 */
    void closeAllOf(String sessionId) {
        /* 设计 §2.4.1 约束 2：设备断开/删除不调用主机注册表 */
    }

    /** v1 兼容：会话当前是否挂着已连接主机（总览页标记；v1 里按宿主关系不存在，恒 false）。 */
    boolean anyOpen(String sessionId) {
        return false;
    }
}
