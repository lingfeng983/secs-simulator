package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * 运维域：工作台总览、报文历史、审计留痕、日志级别。
 */
final class OpsHandler extends HttpSupport {

    /** 报文历史默认条数与上限；上限与 MessageLog 的内存容量一致。 */
    private static final int MESSAGES_LIMIT_DEFAULT = 500;
    private static final int MESSAGES_LIMIT_MAX = 5000;

    /** 审计历史默认条数与上限；审计是低频事件，不需要报文那么大。 */
    private static final int AUDIT_LIMIT_DEFAULT = 200;
    private static final int AUDIT_LIMIT_MAX = 1000;

    OpsHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }


    /**
     * GET /api/v1/overview：全部会话的工作台聚合（schema v6）。
     */
    void handleOverview(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        String body = this.context.overviewJson();
        respond(ex, 200, body == null ? "{\"sessions\":[]}" : body);
    }


    /**
     * 报文历史：{@code /api/v1/messages?session=&direction=&limit=&format=}。
     *
     * <p>它补的是 SSE 的“现在”之外的那一段：断线、刷新页面、或只是想看
     * 上一分钟到底发了什么。默认回 JSON；{@code format=jsonl} 直接下载
     * JSON Lines，方便丢给 jq / Select-String。</p>
     */
    void handleMessages(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        Long limitParam = longParam(query, "limit");
        if (query.containsKey("limit") && limitParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }
        long limit = limitParam == null ? MESSAGES_LIMIT_DEFAULT : limitParam;
        if (limit < 1 || limit > MESSAGES_LIMIT_MAX) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }

        String direction = query.getOrDefault("direction", "").trim().toLowerCase(Locale.ROOT);
        if (!direction.isEmpty() && !"in".equals(direction) && !"out".equals(direction)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-direction\"}");
            return;
        }

        String session = query.getOrDefault("session", "").trim();
        String format = query.getOrDefault("format", "json").trim().toLowerCase(Locale.ROOT);

        /* before 游标:只取时间早于该值的消息(翻旧账);stream/function 过滤报文类型 */
        Long beforeParam = longParam(query, "before");
        if (query.containsKey("before") && beforeParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }
        long before = beforeParam == null ? 0L : beforeParam;
        if (before < 0L) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }
        Integer stream = intParam(query, "stream");
        if (query.containsKey("stream") && stream == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-stream\"}");
            return;
        }
        Integer function = intParam(query, "function");
        if (query.containsKey("function") && function == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-function\"}");
            return;
        }

        if ("jsonl".equals(format)) {
            String body = this.context.messagesJsonl((int) limit, session, direction, before, stream, function);
            if (body == null) {
                respond(ex, 503, "{\"ok\":false,\"error\":\"message-log-unavailable\"}");
                return;
            }
            respondNdjson(ex, body);
            return;
        }

        if (!format.isEmpty() && !"json".equals(format)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-format\"}");
            return;
        }

        String body = this.context.messagesJson((int) limit, session, direction, before, stream, function);
        if (body == null) {
            respond(ex, 503, "{\"ok\":false,\"error\":\"message-log-unavailable\"}");
            return;
        }
        respond(ex, 200, body);
    }


    /**
     * 审计历史：{@code /api/v1/audit?limit=}。
     *
     * <p>只给 admin：审计里会有账号名、动作目标和结果，
     * 比报文历史更敏感；viewer/operator 不应看到别人的操作轨迹。</p>
     */
    void handleAudit(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.ADMIN)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        Long limitParam = longParam(query, "limit");
        if (query.containsKey("limit") && limitParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }
        long limit = limitParam == null ? AUDIT_LIMIT_DEFAULT : limitParam;
        if (limit < 1 || limit > AUDIT_LIMIT_MAX) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }

        /* 服务端过滤:action/actor 精确匹配,空串表示全部;before 游标翻更早的记录 */
        String action = query.getOrDefault("action", "").trim();
        String actor = query.getOrDefault("actor", "").trim();
        Long auditBeforeParam = longParam(query, "before");
        if (query.containsKey("before") && auditBeforeParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }
        long auditBefore = auditBeforeParam == null ? 0L : auditBeforeParam;
        if (auditBefore < 0L) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }

        String body = this.context.auditJson((int) limit, action, actor, auditBefore);
        if (body == null) {
            respond(ex, 503, "{\"ok\":false,\"error\":\"audit-log-unavailable\"}");
            return;
        }
        respond(ex, 200, body);
    }


    /**
     * GET /api/v1/log-level：当前级别；PUT 切换（body 是 error/info/debug，裸文本或 JSON 均可）。
     *
     * <p>排查现场问题的标准动作是"先开 debug 再复现"；能远程切就不用上机器改启动参数。
     * 切换记进审计。</p>
     */
    void handleLogLevel(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            respond(ex, 200, "{\"level\":\"" + json(this.context.logLevel()) + "\"}");
            return;
        }
        if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            String body = readBody(ex);
            if (body == null) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            String level = null;
            try {
                com.shimizukenta.jsonhub.JsonHub root = com.shimizukenta.jsonhub.JsonHub.fromJson(body);
                if (root.isObject() && root.containsKey("level")) {
                    level = str(root, "level").isEmpty() ? null : str(root, "level");
                } else if (root.isString()) {
                    level = root.optionalString().orElse(null);
                }
            } catch (RuntimeException ignore) {
                /* 不是 JSON 就当裸文本处理 */
            }
            if (level == null) {
                level = body.trim().replace("\"", "");
            }
            boolean changed = this.context.setLogLevel(level);
            audit(ex, "log-level", level, changed ? "outcome=OK" : "outcome=REJECTED");
            respond(ex, changed ? 200 : 400,
                    changed ? "{\"ok\":true,\"level\":\"" + json(this.context.logLevel()) + "\"}"
                            : "{\"ok\":false,\"error\":\"unknown-level\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }
}
