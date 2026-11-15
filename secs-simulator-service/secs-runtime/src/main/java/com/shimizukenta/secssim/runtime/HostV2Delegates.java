package com.shimizukenta.secssim.runtime;

import java.util.List;

import com.shimizukenta.secssim.api.http.ApiContext;
import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.app.event.MessageLog;
import com.shimizukenta.secssim.store.HostRepository;

/**
 * v2 主机命名空间的委托：主机实例、配置区方案库与报文。
 *
 * <p>主机与设备是两套独立命名空间（设计 §2.5）：这里只查
 * {@link HostSimRegistry} 与 config.db，不碰会话。</p>
 */
final class HostV2Delegates {

    private final HostSimRegistry hosts;
    private final HostRepository hostRepository;
    private final BurstRunner bursts;
    private final MessageLog messageLog;

    HostV2Delegates(HostSimRegistry hosts, HostRepository hostRepository, BurstRunner bursts,
            MessageLog messageLog) {
        this.hosts = hosts;
        this.hostRepository = hostRepository;
        this.bursts = bursts;
        this.messageLog = messageLog;
    }

    String hostsV2Json() {
        return this.hosts.allJson();
    }

    ApiContext.HostOpenResult openHostV2(String name, String host, int port, int deviceId,
            boolean autoReconnect) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return ApiContext.HostOpenResult.failed(SessionOutcome.FAILED);
        }
        var result = this.hosts.open(name == null || name.isBlank() ? "host" : name,
                new com.shimizukenta.secssim.app.session.HsmsConnectionTarget(host, port, deviceId),
                autoReconnect);
        /* id 随结果返回，不再落到共享字段上（并发创建会串号） */
        return new ApiContext.HostOpenResult(result.outcome(),
                result.hostId() == null ? "" : result.hostId());
    }

    SessionOutcome saveHostV2(String hostId, String name, String host, int port, int deviceId) {
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

    String hostV2Json(String hostId) {
        return this.hosts.jsonById(hostId);
    }

    SessionOutcome destroyHostV2(String hostId) {
        SessionOutcome outcome = this.hosts.destroyById(hostId);
        this.bursts.cancelHost(hostId);
        return outcome;
    }

    SessionOutcome hostConnectV2(String hostId) {
        return this.hosts.connectById(hostId);
    }

    SessionOutcome hostDisconnectV2(String hostId) {
        SessionOutcome outcome = this.hosts.disconnectById(hostId);
        this.bursts.cancelHost(hostId);
        return outcome;
    }

    SessionOutcome hostApplyV2(String hostId) {
        return this.hosts.applyById(hostId);
    }

    String hostSendV2(String hostId, String sml) {
        try {
            return this.hosts.sendById(hostId, sml);
        } catch (java.io.IOException e) {
            return "{\"ok\":false,\"error\":\"" + RuntimeJson.escape(e.getMessage() == null ? "send-failed" : e.getMessage()) + "\"}";
        }
    }

    String hostVariablesRefreshV2(String hostId) {
        return this.hosts.refreshVariables(hostId);
    }

    boolean hostVariableDeclareV2(String hostId, int svid, String type) {
        return this.hosts.declareVariableType(hostId, svid, type);
    }

    String hostVariablesV2(String hostId) {
        return this.hosts.variablesJson(hostId);
    }

    String hostTemplatesJson() {
        return this.hostRepository.listTemplatesJson();
    }

    String hostPlansV2Json(String hostId, String kind) {
        if (!this.hostRepository.hostExists(hostId)) {
            return null;
        }
        List<String> bodies = this.hostRepository.listPlans(hostId, kind);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < bodies.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(bodies.get(i));
        }
        return out.append(']').toString();
    }

    SessionOutcome saveHostPlanV2(String hostId, String kind, String planId, String body) {
        try {
            if (!this.hostRepository.hostExists(hostId)) {
                return SessionOutcome.UNKNOWN_SESSION;
            }
            this.hostRepository.savePlan(hostId, kind, planId, body);
            return SessionOutcome.OK;
        } catch (com.shimizukenta.secssim.store.StoreException e) {
            com.shimizukenta.secssim.app.log.Log.error("保存主机方案失败: " + hostId + "/" + kind + "/" + planId, e);
            return SessionOutcome.FAILED;
        }
    }

    SessionOutcome deleteHostPlanV2(String hostId, String kind, String planId) {
        try {
            if (!this.hostRepository.hostExists(hostId)) {
                return SessionOutcome.UNKNOWN_SESSION;
            }
            if (!this.hostRepository.deletePlan(hostId, kind, planId)) {
                return SessionOutcome.UNKNOWN_SESSION;
            }
            /* 采集方案删了，就把对应的那份采集器一起收掉：
               不这么做，它还在后台采、还会随配置复活，界面上却已经没有那一行。 */
            if ("collect".equals(kind)) {
                this.hosts.removeCollectorPlan(hostId, planId);
            }
            return SessionOutcome.OK;
        } catch (com.shimizukenta.secssim.store.StoreException e) {
            com.shimizukenta.secssim.app.log.Log.error("删除主机方案失败: " + hostId + "/" + kind + "/" + planId, e);
            return SessionOutcome.FAILED;
        }
    }

    SessionOutcome createHostTemplateV2(String id, String name, String host, int port,
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

    SessionOutcome deleteHostTemplateV2(String id) {
        try {
            this.hostRepository.deleteTemplate(id);
            return SessionOutcome.OK;
        } catch (com.shimizukenta.secssim.store.StoreException e) {
            return SessionOutcome.FAILED;
        }
    }

    String hostMessagesV2(String hostId, int limit) {
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
                + ",\"hostId\":\"" + RuntimeJson.escape(event.hostId()) + "\""
                + ",\"perspective\":\"" + event.perspective() + "\""
                + ",\"direction\":\"" + event.direction() + "\""
                + ",\"stream\":" + event.stream()
                + ",\"function\":" + event.function()
                + ",\"wbit\":" + event.wbit()
                + ",\"body\":\"" + RuntimeJson.escape(event.body()) + "\""
                + ",\"at\":" + event.atMillis() + "}";
    }
}