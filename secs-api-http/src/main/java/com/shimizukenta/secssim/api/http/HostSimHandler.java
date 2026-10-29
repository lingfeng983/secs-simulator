package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import com.shimizukenta.secssim.app.log.Log;
import java.util.Locale;

import com.shimizukenta.jsonhub.JsonHub;
import com.sun.net.httpserver.HttpExchange;

/**
 * 主机模拟域：主机实例集合与单个实例（发送、采集器配置与启停）。
 *
 * <p>多主机：实例按 id 定位，不同主机各连各的（本机设备或远程 SECS）。</p>
 */
final class HostSimHandler extends HttpSupport {

    HostSimHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }


    /**
     * 主机实例集合：GET 列表 / POST 新建。
     *
     * <p>多主机：每台设备可以开多个主机实例，实例之间互不影响
     * （各连各的：可以是本机设备，也可以是不同远程 SECS）。</p>
     */
    void handleHostSims(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if ("GET".equals(method)) {
            /* 实例列表含会话名、远程主机/端口与 lastError：与 collector 端点同级，至少 VIEWER */
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            String body = this.context.hostSimsJson();
            if (body == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"not-supported\"}");
                return;
            }
            respond(ex, 200, body);
            return;
        }
        if (!"POST".equals(method)) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        /* 请求体:{ "session":"line1", "host":"...", "port":5000, "deviceId":0, "hostId":"h1" }
           session 必填;host/port 给了就是远程目标,不给就连本机设备。 */
        String raw = readBody(ex, MAX_CONFIG_BYTES);
        if (raw == null || raw.isBlank()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }
        com.shimizukenta.jsonhub.JsonHub request;
        try {
            request = com.shimizukenta.jsonhub.JsonHub.fromJson(raw);
        } catch (RuntimeException e) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }
        String sessionId = str(request, "session");
        if (sessionId.isBlank()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"missing-session\"}");
            return;
        }
        String host = str(request, "host");
        String targetJson = null;
        if (!host.isBlank()) {
            int port = num(request, "port");
            int deviceId = num(request, "deviceId");
            if (port < 1 || port > 65535) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-port\"}");
                return;
            }
            /* host 来自请求体：按 JSON 字符串转义，否则引号能把 port/deviceId 覆盖掉 */
            targetJson = "{\"host\":\"" + json(host) + "\",\"port\":" + port
                    + ",\"deviceId\":" + deviceId + "}";
        }
        String hostId = str(request, "hostId").trim();
        if (!hostId.isEmpty() && (hostId.contains("/") || hostId.contains("\\"))) {
            /* 实例 id 会进 URL 路径，斜杠会让它变成不可寻址的资源 */
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-host-id\"}");
            return;
        }
        SessionOutcome outcome = this.context.openHostSim(sessionId, targetJson, hostId.isBlank() ? null : hostId);
        audit(ex, "host-sim-open", sessionId, "outcome=" + outcome + (targetJson == null ? "" : " remote"));
        switch (outcome) {
            case OK -> respond(ex, 201, "{\"ok\":true}");
            case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"host-busy-or-sim-open\"}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            default -> respond(ex, 502, "{\"ok\":false,\"error\":\"open-failed\"}");
        }
    }


    /**
     * 单个主机实例：GET 状态 / DELETE 断开 / POST 子动作。
     *
     * <p>子路径 {@code send} / {@code collector} / {@code collector/start} /
     * {@code collector/stop} 与会话级的旧路径同义，但按实例 id 定位，
     * 多主机时不会串台。</p>
     */
    void handleHostSimPath(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        String rest = ex.getRequestURI().getPath().substring(ApiPaths.HOST_SIM_PREFIX.length());
        String[] parts = rest.split("/");
        String hostId = parts[0];
        if (hostId.isBlank()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        if ("GET".equals(method) && parts.length == 1) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            String body = this.context.hostSimJsonById(hostId);
            if (body == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-host\"}");
                return;
            }
            respond(ex, 200, body);
            return;
        }

        if ("DELETE".equals(method) && parts.length == 1) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            SessionOutcome outcome = this.context.closeHostSimById(hostId);
            audit(ex, "host-sim-close", hostId, "outcome=" + outcome);
            switch (outcome) {
                case OK -> respond(ex, 200, "{\"ok\":true}");
                default -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-host\"}");
            }
            return;
        }

        /* 角色门槛放进各分支：以前放在这里会把 "GET .../collector"（只读轮询）
           也要求成 OPERATOR，恢复只读账号后前端会被静默 403。 */
        if ("POST".equals(method) && parts.length == 2 && "send".equals(parts[1])) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            /* 与会话级 /message 同义：SML 上限用 64 KiB，而不是默认的 4 KiB */
            String body = readBody(ex, MAX_SML_BYTES);
            if (body == null || body.trim().isEmpty()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            try {
                String result = this.context.sendHostSmlById(hostId, body);
                if (result == null) {
                    respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-host\"}");
                    return;
                }
                respond(ex, 200, result);
            } catch (IllegalArgumentException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"SML 解析失败: " + json(e.getMessage()) + "\"}");
            } catch (IllegalStateException e) {
                respond(ex, 409, "{\"ok\":false,\"error\":\"host-sim-not-open\"}");
            } catch (IOException e) {
                respond(ex, 502, "{\"ok\":false,\"error\":\"send-failed\"}");
            }
            return;
        }

        if (parts.length >= 2 && "collector".equals(parts[1])) {
            if ("GET".equals(method) && parts.length == 2) {
                if (!requireRole(ex, ApiRole.VIEWER)) {
                    return;
                }
                String body = this.context.hostSimCollectorJsonById(hostId);
                if (body == null) {
                    respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-host\"}");
                    return;
                }
                respond(ex, 200, body);
                return;
            }
            if ("POST".equals(method) && parts.length == 2) {
                if (!requireRole(ex, ApiRole.OPERATOR)) {
                    return;
                }
                /* 与会话级 /host-sim/collector 同义：配置正文上限 256 KiB */
                String body = readBody(ex, MAX_CONFIG_BYTES);
                if (body == null || body.trim().isEmpty()) {
                    respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                    return;
                }
                CollectorResult result = this.context.setHostSimCollectorById(hostId, body);
                respondCollector(ex, result, "host-sim-collector-config", hostId);
                return;
            }
            if ("POST".equals(method) && parts.length == 3 && ("start".equals(parts[2]) || "stop".equals(parts[2]))) {
                if (!requireRole(ex, ApiRole.OPERATOR)) {
                    return;
                }
                CollectorResult result = this.context.hostSimCollectorCommandById(hostId, "start".equals(parts[2]));
                respondCollector(ex, result, "host-sim-collector-" + parts[2], hostId);
                return;
            }
        }

        respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
    }
}
