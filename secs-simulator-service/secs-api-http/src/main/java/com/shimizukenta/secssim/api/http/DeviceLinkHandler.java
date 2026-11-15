package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * v2 设备运行时：链路状态/动作与设备配置版本历史。
 */
final class DeviceLinkHandler extends HttpSupport {

    DeviceLinkHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }


    /** GET /api/v2/devices/{id}/link：链路相位（断开展示、重连按钮的状态来源）。 */
    void handleDeviceLinkStateV2(HttpExchange ex, String deviceId) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        String json = this.context.deviceLinkV2Json(deviceId);
        if (json == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            return;
        }
        respond(ex, 200, json);
    }

    /** POST /api/v2/devices/{id}/link/{connect|disconnect|reload|online|offline}。 */
    void handleDeviceLinkV2(HttpExchange ex, String deviceId, String action) throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        final SessionOutcome outcome;
        switch (action) {
            case "connect" -> outcome = this.context.deviceConnectV2(deviceId);
            case "disconnect" -> outcome = this.context.deviceDisconnectV2(deviceId);
            case "reload" -> outcome = this.context.deviceReloadV2(deviceId);
            /* GEM 的上线/下线：等价于主机发 S1F17 / S1F15，只动控制状态不动链路 */
            case "online" -> outcome = this.context.deviceOnlineV2(deviceId, true);
            case "offline" -> outcome = this.context.deviceOnlineV2(deviceId, false);
            default -> {
                respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
                return;
            }
        }
        audit(ex, "device-link", deviceId, "action=" + action + " outcome=" + outcome);
        switch (outcome) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"action\":\"" + action + "\"}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            case NOT_RELOADABLE -> respond(ex, 409, "{\"ok\":false,\"error\":\"not-reloadable\"}");
            case NOT_CONNECTED -> respond(ex, 409, "{\"ok\":false,\"error\":\"not-connected\"}");
            default -> respond(ex, 502, "{\"ok\":false,\"error\":\"link-" + action + "-failed\"}");
        }
    }

    /**
     * v2:设备配置版本历史(GET /api/v2/devices/{name}/config/revisions)。
     */
    void handleConfigRevisionsV2(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        String rest = ex.getRequestURI().getPath()
                .substring(ApiPaths.DEVICE_REVISIONS_V2.length());
        String[] parts = rest.split("/");
        /* 只认 {id}/config/revisions 这一种形状。以前这里是 handleDeviceV2 的兜底分支，
           任何没认出来的路径（`/devices/{id}/runtime`、`/devices/{id}/config/xyz`）
           都会静默返回该设备的版本历史——200 + 另一个资源，比 404 更难查。 */
        if (parts.length != 3 || parts[0].isBlank()
                || !"config".equals(parts[1]) || !"revisions".equals(parts[2])) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (!"GET".equals(ex.getRequestMethod().toUpperCase(Locale.ROOT))) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        String name = parts[0];
        int limit = 50;
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        Integer requested = intParam(query, "limit");
        /* 写了 limit 但不是整数 / 越界都算 400：以前 parse 直接抛 NumberFormatException
           变成 500，负数则被下游 Math.max 静默改成 1 条 */
        if (query.containsKey("limit")
                && (requested == null || requested < 1 || requested > 500)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }
        if (requested != null) {
            limit = requested;
        }
        String body = this.context.configRevisionsV2(name, limit);
        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"config-not-found\"}");
            return;
        }
        respond(ex, 200, body);
    }
}