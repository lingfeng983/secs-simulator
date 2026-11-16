package com.shimizukenta.secssim.runtime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.shimizukenta.secssim.api.http.SessionOutcome;
import com.shimizukenta.secssim.api.http.TriggerOutcome;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.PlanRuntimeStat;
import com.shimizukenta.secssim.store.ConfigRepository;
import com.shimizukenta.secssim.store.PlanExecutionRepository;
import com.shimizukenta.secssim.store.RuntimeStateRepository;

/**
 * 3.0 v2 设备域的委托：运行时编辑、计划台账、链路相位与单步试跑。
 *
 * <p>全部方法先按 {@link DeviceIdResolver} 把设备 id 归一，再落到会话或台账。</p>
 */
final class DeviceV2Delegates {

    /** 手工触发台账的序号：同一毫秒内连点两次也不能撞幂等键。 */
    private static final java.util.concurrent.atomic.AtomicLong MANUAL_TRIGGER_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private final RuntimeApi api;
    private final DeviceIdResolver ids;
    private final RuntimeStateRepository linkState;
    private final PlanExecutionRepository planExecutions;
    private final RuntimePersister persister;
    private final ConfigRepository configRepository;

    DeviceV2Delegates(RuntimeApi api, DeviceIdResolver ids, RuntimeStateRepository linkState,
            PlanExecutionRepository planExecutions, RuntimePersister persister,
            ConfigRepository configRepository) {
        this.api = api;
        this.ids = ids;
        this.linkState = linkState;
        this.planExecutions = planExecutions;
        this.persister = persister;
        this.configRepository = configRepository;
    }

    /* ---------- 执行台账 ---------- */

    String planExecutionsV2(String deviceId, int limit, String planId,
                            String status, String dryRun) {
        String canonical = this.ids.canonical(deviceId);
        boolean includeDryRun = dryRun != null && ("all".equalsIgnoreCase(dryRun)
                || "1".equals(dryRun) || "true".equalsIgnoreCase(dryRun));
        int capped = Math.max(1, Math.min(500, limit));
        String statusFilter = status == null || status.isBlank() ? null
                : status.trim().toUpperCase(java.util.Locale.ROOT);
        List<PlanExecutionRepository.ExecutionRow> rows = this.planExecutions.query(
                canonical, RuntimeJson.blankToNull(planId), statusFilter, includeDryRun, capped);

        StringBuilder sb = new StringBuilder(128 + rows.size() * 256);
        sb.append("{\"items\":[");
        for (int i = 0; i < rows.size(); i++) {
            PlanExecutionRepository.ExecutionRow row = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(row.id())
                    .append(",\"deviceId\":").append(RuntimeJson.quote(row.deviceId()))
                    .append(",\"planId\":").append(RuntimeJson.quote(row.planId()))
                    .append(",\"ceid\":").append(row.ceid())
                    .append(",\"dryRun\":").append(row.dryRun())
                    .append(",\"scheduledAt\":").append(row.scheduledAt())
                    .append(",\"firedAt\":").append(row.firedAt())
                    .append(",\"status\":").append(RuntimeJson.quote(row.status()))
                    .append(",\"messages\":").append(RuntimeJson.safeArray(row.messageJson()))
                    .append(",\"effects\":").append(RuntimeJson.safeArray(row.effectJson()))
                    .append(",\"error\":").append(RuntimeJson.quote(row.error()))
                    .append('}');
        }
        return sb.append("],\"count\":").append(rows.size()).append('}').toString();
    }

    SessionOutcome clearExecutionsV2(String deviceId) {
        String canonical = this.ids.canonical(deviceId);
        if (this.configRepository.findDevice(canonical).isEmpty()) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        try {
            this.planExecutions.clearForDevice(canonical);
            return SessionOutcome.OK;
        } catch (com.shimizukenta.secssim.store.StoreException e) {
            com.shimizukenta.secssim.app.log.Log.error("清理执行台账失败: " + canonical, e);
            return SessionOutcome.FAILED;
        }
    }

    TriggerOutcome stepPlanV2(String deviceId, String planId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
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

    SessionOutcome setPlanEnabledV2(String deviceId, String planId, boolean enabled) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        com.shimizukenta.secssim.app.session.PlanEnableOutcome outcome = session.setEventEnabled(planId, enabled);
        if (outcome == com.shimizukenta.secssim.app.session.PlanEnableOutcome.UNKNOWN_PLAN) {
            return SessionOutcome.FAILED;
        }
        if (outcome == com.shimizukenta.secssim.app.session.PlanEnableOutcome.NOT_ONLINE) {
            /* 2026-11-16 设计 §2.2：离线时拒绝启动（409 not-online），界面禁用按钮 */
            return SessionOutcome.NOT_ONLINE;
        }
        /* next_fire_at 开始写入（§2.1）：周期计划 = enabledAt + k×period 的下一次；
           启用态不再跨重启回放（D4），本行只承载统计与倒计时初值 */
        this.linkState.savePlanState(this.ids.canonical(deviceId), planId, enabled,
                session.nextFireAtOf(planId), "");
        return SessionOutcome.OK;
    }

    /** 工艺周期运行态 JSON（M3）；设备不在返回 {@code null}。 */
    String cycleJsonV2(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? null : session.cycleJson();
    }

    /** 启动工艺周期：ONLINE 门控同计划启停；没有 cycle 段返回 FAILED（409 no-cycle）。 */
    SessionOutcome cycleStartV2(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        com.shimizukenta.secssim.app.session.PlanEnableOutcome outcome = session.cycleStart();
        return switch (outcome) {
            case OK -> SessionOutcome.OK;
            case NOT_ONLINE -> SessionOutcome.NOT_ONLINE;
            case NO_CYCLE -> SessionOutcome.FAILED;
            case UNKNOWN_PLAN -> SessionOutcome.FAILED;
        };
    }

    /** 停止工艺周期（禁用方向不受 ONLINE 限制）。 */
    SessionOutcome cycleStopV2(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        session.cycleStop();
        return SessionOutcome.OK;
    }

    /* ---------- 计划与目录 ---------- */

    String plansV2Json(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return null;
        }
        /* 统计缺失的计划按「没跑过」处理：配置里有、runtime_plan_state 里没有很正常 */
        Map<String, PlanRuntimeStat> stats = new LinkedHashMap<>();
        for (RuntimeStateRepository.PlanStateRow row : this.linkState.planStates(this.ids.canonical(deviceId))) {
            stats.put(row.planId(), new PlanRuntimeStat(row.fireCount(), row.skippedCount(),
                    row.lastFireAt() == null ? 0L : row.lastFireAt(),
                    row.nextFireAt() == null ? 0L : row.nextFireAt(),
                    row.lastError() == null ? "" : row.lastError(), 0L));
        }
        return session.eventsJson(stats);
    }

    int addPlanV2(String deviceId, String planJson) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? 404 : this.api.addEventPlan(session.id(), planJson);
    }

    int updatePlanV2(String deviceId, String planId, String planJson) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? 404 : this.api.updateEventPlan(session.id(), planId, planJson);
    }

    int removePlanV2(String deviceId, String planId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? 404 : this.api.removeEventPlan(session.id(), planId);
    }

    int addCatalogV2(String deviceId, String kind, String bodyJson) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return 404;
        }
        return switch (kind) {
            case "ceid" -> this.api.addCeid(session.id(), bodyJson);
            case "report" -> this.api.addReport(session.id(), bodyJson);
            case "svid" -> this.api.addSvid(session.id(), bodyJson);
            default -> 400;
        };
    }

    int updateCatalogV2(String deviceId, String kind, int key, String bodyJson) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return 404;
        }
        return switch (kind) {
            case "ceid" -> this.api.updateCeid(session.id(), key, bodyJson);
            case "report" -> this.api.updateReport(session.id(), key, bodyJson);
            case "svid" -> this.api.updateSvid(session.id(), key, bodyJson);
            default -> 400;
        };
    }

    int removeCatalogV2(String deviceId, String kind, int key) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return 404;
        }
        return switch (kind) {
            case "ceid" -> this.api.removeCeid(session.id(), key);
            case "report" -> this.api.removeReport(session.id(), key);
            case "svid" -> this.api.removeSvid(session.id(), key);
            default -> 400;
        };
    }

    int pinSvidV2(String deviceId, int svid, String valueJson) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? 404 : this.api.pinSvidValue(session.id(), svid, valueJson);
    }

    int unpinSvidV2(String deviceId, int svid) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? 404 : this.api.unpinSvidValue(session.id(), svid);
    }

    String sendSmlV2(String deviceId, String sml) throws IOException {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? null : this.api.sendSml(session.id(), sml);
    }

    TriggerOutcome triggerCeidV2(String deviceId, int ceid) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return TriggerOutcome.UNKNOWN_SESSION;
        }
        TriggerOutcome outcome = this.api.triggerCeid(session.id(), ceid);
        /* 台账要留证据（设计 §2.5）：手工触发也记一行，plan_id 固定 manual，
           这样「谁在什么时候手动发过一次」能直接查出来，不用翻报文日志 */
        long now = System.currentTimeMillis();
        String canonical = this.ids.canonical(deviceId);
        String key = canonical + "|manual|" + ceid + "|" + now
                + "|" + MANUAL_TRIGGER_SEQ.incrementAndGet();
        this.planExecutions.append(new PlanExecutionRepository.Execution(
                canonical, "manual", ceid, key, false, now, now,
                outcome == TriggerOutcome.OK ? "SENT" : outcome.name(), "", "",
                outcome == TriggerOutcome.OK ? "" : "手动触发未发出: " + outcome));
        return outcome;
    }

    TriggerOutcome triggerAlarmV2(String deviceId, int alid, boolean set,
                                  String text, int category) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        return session == null ? TriggerOutcome.UNKNOWN_SESSION
                : this.api.triggerAlarm(session.id(), alid, set, text, category);
    }

    /* ---------- 链路 ---------- */

    String deviceLinkV2Json(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        String canonical = this.ids.canonical(deviceId);
        /* 内存相位是权威：device_link 行可能落后一拍（相位回调是异步的） */
        String phase = session == null ? null
                : RuntimeApi.phaseName(session.linkPhase());
        String lastError = session == null ? null : session.lastError();
        java.util.Optional<RuntimeStateRepository.LinkRow> row = this.linkState.deviceLink(canonical);
        if (session == null && row.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"deviceId\":").append(RuntimeJson.quote(canonical))
                .append(",\"phase\":").append(RuntimeJson.quote(phase != null ? phase
                        : row.map(RuntimeStateRepository.LinkRow::phase).orElse("STOPPED")))
                .append(",\"controlState\":")
                .append(RuntimeJson.quote(session == null ? "OFFLINE" : session.controlState().name()))
                .append(",\"lastError\":").append(RuntimeJson.quote(lastError == null ? "" : lastError))
                .append(",\"configRevision\":").append(row.map(RuntimeStateRepository.LinkRow::configRevision).orElse(0L))
                .append(",\"connectedAt\":").append(row.map(RuntimeStateRepository.LinkRow::connectedAt).orElse(null))
                .append(",\"disconnectedAt\":").append(row.map(RuntimeStateRepository.LinkRow::disconnectedAt).orElse(null))
                .append('}');
        return sb.toString();
    }

    SessionOutcome deviceConnectV2(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        if (session.linkPhase() != SessionRuntime.LinkPhase.STOPPED
                && session.linkPhase() != SessionRuntime.LinkPhase.DISCONNECTED
                && session.linkPhase() != SessionRuntime.LinkPhase.FAILED) {
            /* 幂等：已经在监听/已连上，重复点「连接」不该报错 */
            return SessionOutcome.OK;
        }
        try {
            session.reconnect();
            this.persister.devicePhase(deviceId, RuntimeApi.phaseName(session.linkPhase()),
                    session.lastError());
            return SessionOutcome.OK;
        } catch (java.io.IOException | RuntimeException e) {
            com.shimizukenta.secssim.app.log.Log.error("设备连接失败: " + deviceId + " " + e.getMessage(), e);
            this.persister.devicePhase(deviceId, "FAILED", e.getMessage());
            return SessionOutcome.FAILED;
        }
    }

    SessionOutcome deviceDisconnectV2(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        /* 只断链：配置、模型、钉值、计划启用态、运行时编辑都留着，再连原样恢复 */
        session.disconnect();
        this.persister.devicePhase(deviceId, "DISCONNECTED", session.lastError());
        return SessionOutcome.OK;
    }

    SessionOutcome deviceOnlineV2(String deviceId, boolean online) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        if (!session.setOnline(online)) {
            /* 没有主机时不允许上线：GEM 的 ONLINE 是"对某台主机"的状态 */
            return SessionOutcome.NOT_CONNECTED;
        }
        com.shimizukenta.secssim.app.log.Log.info("设备 " + deviceId
                + (online ? " 置为在线（等价 S1F17）" : " 置为离线（等价 S1F15）"));
        return SessionOutcome.OK;
    }

    SessionOutcome deviceReloadV2(String deviceId) {
        HsmsSessionRuntime session = this.ids.sessionFor(deviceId);
        if (session == null) {
            return SessionOutcome.UNKNOWN_SESSION;
        }
        return this.api.reloadSession(session.id());
    }
}