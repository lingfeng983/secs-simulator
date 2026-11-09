package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secssim.api.http.CollectorOutcome;
import com.shimizukenta.secssim.api.http.CollectorResult;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.runtime.hostsim.HostSimulator;

/**
 * 主机模拟器注册表：会话 ↔ 主机实例的双向索引，以及每个实例的报文/采集器入口。
 *
 * <p>从 {@code RuntimeContext} 拆出来的第一块：主机实例是独立于会话生命周期的
 * 连接（多主机：同一台设备可以开多个，远程实例连的是别的设备），
 * 它的注册、查找、重连与关闭自成一套，不需要和会话查询/配置读写混在一个文件里。</p>
 *
 * <p>并发约定：注册表本身是两把 ConcurrentMap，"同一 id 并发打开"由
 * {@code putIfAbsent} 兜底——失败的那一方把自己的连接关掉再返回冲突。</p>
 */
final class HostSimRegistry {

    private final SessionManager sessions;

    /** 主机实例注册表：hostId → 模拟器（多主机：同一台设备可以开多个主机）。 */
    private final ConcurrentMap<String, HostSimulator> hostSims = new ConcurrentHashMap<>();

    /** 会话 → 它开的主机实例 id 列表。 */
    private final ConcurrentMap<String, List<String>> hostsBySession = new ConcurrentHashMap<>();

    /** 主机实例 id 计数器。 */
    private final AtomicLong hostSeq = new AtomicLong();

    HostSimRegistry(SessionManager sessions) {
        this.sessions = sessions;
    }

    /**
     * 为会话打开一个主机模拟器。
     *
     * @param sessionId  所属会话
     * @param targetJson 远程目标（{@code {"host","port","deviceId"}}）；空 = 连本机
     * @param hostId     指定实例 id；空则自动生成
     * @return 打开结果
     */
    SessionOutcome open(String sessionId, String targetJson, String hostId) {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
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
        HsmsConnectionTarget override = null;
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
                override = new HsmsConnectionTarget(host, port, deviceId);
            } catch (RuntimeException e) {
                Log.error("主机模拟远程目标解析失败（会话 " + sessionId + "）: " + e.getMessage(), null);
                return SessionOutcome.FAILED;
            }
        }

        /* 一台设备只允许一个本机主机：E5 的一台设备只接受一个选中的主机，
           再开一个会让"当前主机"与报文归属变得不可解释。远程实例连的是别的设备，不受此限。 */
        if (!remote) {
            List<String> existing = this.hostsBySession.get(sessionId);
            if (existing != null) {
                for (String existingHostId : existing) {
                    HostSimulator previous = this.hostSims.get(existingHostId);
                    if (previous != null && previous.isOpen()) {
                        Log.info("会话 " + sessionId + " 已有主机 " + existingHostId + "，拒绝再建一个本机主机");
                        return SessionOutcome.CONFLICT;
                    }
                }
            }
        }

        try {
            HostSimulator created = HostSimulator.open(id, session, override);
            /* containsKey → put 之间有窗口：并发同名实例会互相覆盖并泄漏一个连接。
               putIfAbsent 失败时把这次新建的这个关掉。 */
            HostSimulator previous = this.hostSims.putIfAbsent(id, created);
            if (previous != null) {
                created.close();
                return SessionOutcome.CONFLICT;
            }
            this.hostsBySession.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>())
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

    /** 关掉会话当前的主机实例（没有就是未知会话）。 */
    SessionOutcome closeBySession(String sessionId) {
        return this.closeById(this.hostIdOf(sessionId));
    }

    /** 按实例 id 关闭；同步清理会话索引，避免残留已不存在的 id。 */
    SessionOutcome closeById(String hostId) {
        HostSimulator sim = this.hostSims.remove(hostId);
        if (sim == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        sim.close();
        /* 同步清理会话 → 实例的索引：否则关掉最后一个实例后，
           会话级接口还会拿着一个已经不存在的 id 去查。 */
        for (List<String> ids : this.hostsBySession.values()) {
            ids.remove(hostId);
        }
        this.hostsBySession.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return SessionOutcome.OK;
    }

    /**
     * 会话的主机状态 JSON。
     *
     * @param sessionId 会话
     * @return 会话不存在返回 {@code null}（HTTP 404），没开过主机返回 {@code {"open":false}}
     */
    String jsonOfSession(String sessionId) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
        String hostId = this.hostIdOf(sessionId);
        if (hostId.isEmpty()) {
            return "{\"open\":false}";
        }
        return this.jsonById(hostId);
    }

    /** 会话当前的主机实例 id（没开过、或实例已被关掉就是空串）。 */
    private String hostIdOf(String sessionId) {
        List<String> ids = this.hostsBySession.get(sessionId);
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
            /* 实例不存在 = 未知资源，交给 HTTP 层回 404；不要伪装成"存在但已断开" */
            return null;
        }
        return sim.stateJson();
    }

    /**
     * 会话级发送：找会话当前的主机实例发一条 SML。
     *
     * @param sessionId 会话
     * @param sml       SML 文本
     * @return 响应文本；没有实例/会话不存在返回 {@code null}
     * @throws IOException 发送失败（会自动重连重试一次）
     */
    String sendBySession(String sessionId, String sml) throws IOException {
        HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
        if (sim == null || this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
        return this.send(sim, sml, "会话 " + sessionId);
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
     *
     * <p>设备重载后端口重绑会让连接死亡：自动重连让"改配置→重载→继续用主机模拟"
     * 不需要手动断开重连。v6 用 {@code reconnect()} 保留原目标（远程模式同样适用）。</p>
     */
    private String send(HostSimulator sim, String sml, String who) throws IOException {
        try {
            return sim.sendSml(sml);
        } catch (IOException e) {
            if (!sim.isOpen()) {
                Log.info("主机模拟器连接已断（" + who + "），自动重连后重试");
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

    /** 实例级：下发采集器配置。 */
    CollectorResult setCollectorById(String hostId, String requestJson) {
        HostSimulator sim = this.hostSims.get(hostId);
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
            /* 实例不存在 → HTTP 404；已存在的实例未配置才是 configured:false */
            return null;
        }
        return sim.collectorJson();
    }

    /** 会话级：下发采集器配置。 */
    CollectorResult setCollectorBySession(String sessionId, String requestJson) {
        HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
        if (sim == null || this.sessions.find(sessionId).isEmpty()) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        try {
            sim.setCollectorConfig(requestJson);
            return CollectorResult.ok();
        } catch (IllegalArgumentException e) {
            return CollectorResult.invalid(e.getMessage());
        }
    }

    /** 会话级：启停采集。 */
    CollectorResult commandBySession(String sessionId, boolean start) {
        HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
        if (sim == null || this.sessions.find(sessionId).isEmpty()) {
            return new CollectorResult(CollectorOutcome.UNKNOWN_HOST, "host-sim-not-open");
        }
        return this.command(sim, start);
    }

    /** 会话级：采集器状态；会话不存在返回 {@code null}，没开主机返回 {@code {"configured":false}}。 */
    String collectorJsonBySession(String sessionId) {
        if (this.sessions.find(sessionId).isEmpty()) {
            return null;
        }
        HostSimulator sim = this.hostSims.get(this.hostIdOf(sessionId));
        if (sim == null) {
            return "{\"configured\":false}";
        }
        return sim.collectorJson();
    }

    /** 启停采集；"没有配置"是状态冲突（409），不是请求非法，界面据此提示先下发配置。 */
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

    /** 会话是否挂着至少一个已连接的主机实例（总览页打标记用）。 */
    boolean anyOpen(String sessionId) {
        List<String> hostIds = this.hostsBySession.get(sessionId);
        if (hostIds == null) {
            return false;
        }
        for (String hostId : hostIds) {
            HostSimulator sim = this.hostSims.get(hostId);
            if (sim != null && sim.isOpen()) {
                return true;
            }
        }
        return false;
    }

    /** 会话被删时收掉它的全部主机实例：会话都没了，主机不该继续挂着一条死连接。 */
    void closeAllOf(String sessionId) {
        List<String> hostIds = this.hostsBySession.remove(sessionId);
        if (hostIds == null) {
            return;
        }
        for (String hostId : hostIds) {
            HostSimulator sim = this.hostSims.remove(hostId);
            if (sim != null) {
                sim.close();
            }
        }
    }

    /** 停机：关掉所有实例；单个失败只记录，停机继续。 */
    void close() {
        for (String hostId : List.copyOf(this.hostSims.keySet())) {
            HostSimulator sim = this.hostSims.remove(hostId);
            if (sim != null) {
                try {
                    sim.close();
                } catch (RuntimeException e) {
                    Log.error("关闭主机模拟器失败（停机继续）: " + hostId, e);
                }
            }
        }
        this.hostsBySession.clear();
    }
}
