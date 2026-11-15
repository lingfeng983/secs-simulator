package com.shimizukenta.secssim.api.http;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

/**
 * v2 设备运行时：目录编辑（CEID / Report / SVID）与 SVID 钉值。
 */
final class CatalogEditHandler extends HttpSupport {

    CatalogEditHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }


    /** {@code /runtime/catalog/{kind}[/{key}]}，{@code kind = ceid | report | svid}。 */
    void handleCatalogEditV2(HttpExchange ex, String deviceId, String method,
                             String[] parts) throws IOException {
        String kind = parts.length > 3 ? parts[3] : "";
        if (!"ceid".equals(kind) && !"report".equals(kind) && !"svid".equals(kind)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-kind\"}");
            return;
        }
        if (parts.length < 4 || parts.length > 5) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }

        if (parts.length == 4) {
            if (!"POST".equals(method)) {
                respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
                return;
            }
            String body = readBody(ex, MAX_CONFIG_BYTES);
            if (body == null || body.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            int status = this.context.addCatalogV2(deviceId, kind, body);
            audit(ex, kind + "-add", deviceId, "status=" + status);
            ConfigHandler.respondEditStatus(ex, status);
            return;
        }

        Integer key = ConfigHandler.intOrNull(parts[4]);
        if (key == null || key < 1) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-key\"}");
            return;
        }
        if ("PUT".equals(method)) {
            String body = readBody(ex, MAX_CONFIG_BYTES);
            if (body == null || body.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            int status = this.context.updateCatalogV2(deviceId, kind, key, body);
            audit(ex, kind + "-update", deviceId, "key=" + key + " status=" + status);
            ConfigHandler.respondEditStatus(ex, status);
            return;
        }
        if ("DELETE".equals(method)) {
            int status = this.context.removeCatalogV2(deviceId, kind, key);
            audit(ex, kind + "-delete", deviceId, "key=" + key + " status=" + status);
            ConfigHandler.respondEditStatus(ex, status);
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /** {@code PUT|DELETE /runtime/svids/{svid}/pin}：钉值 / 取消钉值。 */
    void handleSvidPinV2(HttpExchange ex, String deviceId, String method,
                         String[] parts) throws IOException {
        if (parts.length != 5 || !"pin".equals(parts[4])) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        Integer svid = ConfigHandler.intOrNull(parts[3]);
        if (svid == null || svid < 1) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-svid\"}");
            return;
        }
        if ("PUT".equals(method)) {
            String body = readBody(ex, MAX_CONFIG_BYTES);
            if (body == null || body.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            int status = this.context.pinSvidV2(deviceId, svid, body);
            audit(ex, "svid-pin", deviceId, "svid=" + svid + " status=" + status);
            ConfigHandler.respondEditStatus(ex, status);
            return;
        }
        if ("DELETE".equals(method)) {
            int status = this.context.unpinSvidV2(deviceId, svid);
            audit(ex, "svid-unpin", deviceId, "svid=" + svid + " status=" + status);
            ConfigHandler.respondEditStatus(ex, status);
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }
}