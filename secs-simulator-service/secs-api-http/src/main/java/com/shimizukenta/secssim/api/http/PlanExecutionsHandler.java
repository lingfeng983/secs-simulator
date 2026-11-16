package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.sun.net.httpserver.HttpExchange;

/**
 * v2 设备运行时：计划（含单步试跑、启停持久化）与执行台账。
 */
final class PlanExecutionsHandler extends HttpSupport {

    PlanExecutionsHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }


    /**
     * {@code /runtime/plans[/{planId}]} 与 {@code .../{planId}/{step|enabled}}。
     *
     * <p>{@code step} 与 {@code enabled} 沿用已发布的处理器：它们的鉴权、
     * 请求体解析与状态码已经和界面联调过，不在这里重写一遍。</p>
     */
    void handlePlansV2(HttpExchange ex, String deviceId, String method,
                       String[] parts) throws IOException {
        if (parts.length == 5) {
            String planId = parts[3];
            if ("step".equals(parts[4])) {
                this.handleStepV2(ex, deviceId, planId);
                return;
            }
            if ("enabled".equals(parts[4])) {
                this.handleEnabledV2(ex, deviceId, planId);
                return;
            }
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (parts.length < 3 || parts.length > 4) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        boolean read = "GET".equals(method);
        if (!requireRole(ex, read ? ApiRole.VIEWER : ApiRole.OPERATOR)) {
            return;
        }

        if (parts.length == 3) {
            if (read) {
                String body = this.context.plansV2Json(deviceId);
                if (body == null) {
                    respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
                    return;
                }
                audit(ex, "plan-list", deviceId, "");
                respond(ex, 200, body);
                return;
            }
            if ("POST".equals(method)) {
                String body = readBody(ex, MAX_CONFIG_BYTES);
                if (body == null || body.isBlank()) {
                    respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                    return;
                }
                int status = this.context.addPlanV2(deviceId, body);
                audit(ex, "plan-add", deviceId, "status=" + status);
                ConfigHandler.respondEditStatus(ex, status);
                return;
            }
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String planId = parts[3];
        if ("PUT".equals(method)) {
            String body = readBody(ex, MAX_CONFIG_BYTES);
            if (body == null || body.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            int status = this.context.updatePlanV2(deviceId, planId, body);
            audit(ex, "plan-update", deviceId, "plan=" + planId + " status=" + status);
            ConfigHandler.respondEditStatus(ex, status);
            return;
        }
        if ("DELETE".equals(method)) {
            int status = this.context.removePlanV2(deviceId, planId);
            audit(ex, "plan-delete", deviceId, "plan=" + planId + " status=" + status);
            ConfigHandler.respondEditStatus(ex, status);
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /** GET /api/v2/devices/{id}/runtime/executions：执行台账（缺省不含试跑）。DELETE：清空台账。 */
    void handleExecutionsV2(HttpExchange ex, String deviceId) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            SessionOutcome outcome = this.context.clearExecutionsV2(deviceId);
            audit(ex, "plan-executions-clear", deviceId, "outcome=" + outcome);
            switch (outcome) {
                case OK -> respond(ex, 200, "{\"ok\":true}");
                case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
                default -> respond(ex, 502, "{\"ok\":false,\"error\":\"clear-failed\"}");
            }
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        int limit = 50;
        Integer requested = intParam(query, "limit");
        /* 写了 limit 但不是整数也算 400：以前静默用默认值，调用方以为
           "只取 10 条"，实际拿到 50 条，翻页错位还查不出原因 */
        if (query.containsKey("limit") && requested == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }
        if (requested != null) {
            limit = requested;
        }
        if (limit < 1 || limit > 500) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }
        String json = this.context.planExecutionsV2(deviceId, limit,
                query.get("planId"), query.get("status"), query.get("dryRun"));
        if (json == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            return;
        }
        audit(ex, "plan-executions", deviceId, "limit=" + limit);
        respond(ex, 200, json);
    }

    /** POST /api/v2/devices/{id}/runtime/plans/{planId}/step：单步试跑。 */
    void handleStepV2(HttpExchange ex, String deviceId, String planId) throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        TriggerOutcome outcome = this.context.stepPlanV2(deviceId, planId);
        audit(ex, "plan-step", deviceId, "plan=" + planId + " outcome=" + outcome);
        switch (outcome) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"planId\":\"" + json(planId) + "\"}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            case NOT_OPEN -> respond(ex, 409, "{\"ok\":false,\"error\":\"device-offline\"}");
            default -> respond(ex, 500, "{\"ok\":false,\"error\":\"plan-step-failed\"}");
        }
    }

    /** POST/PUT /api/v2/devices/{id}/runtime/plans/{planId}/enabled：持久化启停。 */
    void handleEnabledV2(HttpExchange ex, String deviceId, String planId) throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        boolean enabled = !"false".equalsIgnoreCase(query.get("enabled"));
        String body = readBody(ex);
        if (body == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }
        if (!body.isBlank()) {
            try {
                JsonHub root = JsonHub.fromJson(body);
                if (root.isObject() && root.containsKey("enabled")) {
                    enabled = root.get("enabled").optionalBoolean().orElse(enabled);
                } else if (!root.isObject()) {
                    throw new IllegalArgumentException("expected object");
                }
            } catch (RuntimeException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
        }
        SessionOutcome outcome = this.context.setPlanEnabledV2(deviceId, planId, enabled);
        audit(ex, "plan-enabled", deviceId, "plan=" + planId + " enabled=" + enabled
                + " outcome=" + outcome);
        switch (outcome) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"planId\":\"" + json(planId)
                    + "\",\"enabled\":" + enabled + "}");
            case NOT_ONLINE -> respond(ex, 409, "{\"ok\":false,\"error\":\"not-online\"}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            default -> respond(ex, 400, "{\"ok\":false,\"error\":\"plan-not-found\"}");
        }
    }
}