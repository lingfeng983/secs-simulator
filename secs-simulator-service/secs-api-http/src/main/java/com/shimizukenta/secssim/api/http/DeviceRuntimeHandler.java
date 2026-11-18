package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.app.log.Log;
import com.sun.net.httpserver.HttpExchange;

/**
 * v2 设备域（{@code /api/v2/devices/} 前缀）的路由分派与运行时动作。
 *
 * <p>计划、目录编辑、执行台账与链路各自有独立处理器；本类只负责把路径第二段
 * 分派过去，并处理手动消息、报警与 CEID 触发。</p>
 */
final class DeviceRuntimeHandler extends HttpSupport {

    private static final Log.Facade LOG = Log.of(DeviceRuntimeHandler.class);

    private final PlanExecutionsHandler plans;
    private final CatalogEditHandler catalogs;
    private final DeviceLinkHandler links;

    DeviceRuntimeHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
        this.plans = new PlanExecutionsHandler(context, access, audit);
        this.catalogs = new CatalogEditHandler(context, access, audit);
        this.links = new DeviceLinkHandler(context, access, audit);
    }


    /**
     * v2 设备域总入口（{@code /api/v2/devices/} 前缀）。
     *
     * <p>版本历史与运行态台账共用一个 HttpServer 上下文，按路径第二段分派：
     * {@code config/**} 走原有版本历史，{@code runtime/**} 走 M4 的执行台账、
     * 单步试跑与启用态。注册两个同前缀的上下文会被 HttpServer 按注册顺序吞掉，
     * 所以分发只能放在同一个 handler 里。</p>
     */
    void handleDeviceV2(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(ApiPaths.DEVICE_V2_PREFIX)) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        String rest = path.substring(ApiPaths.DEVICE_V2_PREFIX.length());
        String[] parts = rest.split("/");
        if (parts.length >= 2 && "link".equals(parts[1]) && !parts[0].isBlank()) {
            if (parts.length == 2) {
                this.links.handleDeviceLinkStateV2(ex, parts[0]);
                return;
            }
            if (parts.length == 3) {
                this.links.handleDeviceLinkV2(ex, parts[0], parts[2]);
                return;
            }
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (parts.length >= 2 && "cycle".equals(parts[1]) && !parts[0].isBlank()) {
            /* 工艺周期（2026-11-16 设计 §2.4）：/api/v2/devices/{id}/cycle[/{start|stop}]，
               与契约同形——不在 runtime 段下 */
            this.handleDeviceCycleV2(ex, parts[0], ex.getRequestMethod().toUpperCase(Locale.ROOT), parts);
            return;
        }
        if (parts.length == 2 && "apply-sections".equals(parts[1]) && !parts[0].isBlank()) {
            this.handleApplySectionsV2(ex, parts[0]);
            return;
        }
        if (parts.length >= 3 && !parts[0].isBlank() && "runtime".equals(parts[1])) {
            this.handleDeviceRuntimeV2(ex, parts);
            return;
        }
        if (parts.length == 1 && !parts[0].isBlank()
                && "DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            /* DELETE /api/v2/devices/{id}：销毁会话（释放端口、清运行态）。
               只断链走 /link/disconnect；删除设备实体走 DELETE /api/v1/configs/{name}。 */
            this.handleDeviceDestroyV2(ex, parts[0]);
            return;
        }
        this.links.handleConfigRevisionsV2(ex);
    }

    /**
     * DELETE /api/v2/devices/{id}：销毁会话。
     *
     * <p>端口释放、会话从注册表移除、运行态编辑清理；设备实体与配置正文保留，
     * 之后再「启动设备」会按配置重建一个新的会话。</p>
     *
     * @param ex       交换
     * @param deviceId 设备 id（或配置名 stem）
     * @throws IOException 写响应失败
     */
    private void handleDeviceDestroyV2(HttpExchange ex, String deviceId) throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        SessionOutcome outcome = this.context.destroyDeviceV2(deviceId);
        audit(ex, "device-destroy", deviceId, "outcome=" + outcome);
        switch (outcome) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"destroyed\":true}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-running\"}");
            default -> respond(ex, 502, "{\"ok\":false,\"error\":\"destroy-failed\"}");
        }
    }

    /**
     * v2:设备配置版本历史(GET /api/v2/devices/{name}/config/revisions)。
     */
    void handleConfigRevisionsV2(HttpExchange ex) throws IOException {
        this.links.handleConfigRevisionsV2(ex);
    }

    /**
     * v2 设备运行时：{@code /api/v2/devices/{id}/runtime/**}（设计 §2.5）。
     *
     * <p>读写分权：GET 只要求 VIEWER，任何改动都要 OPERATOR。这里的所有增删改都
     * 只落运行态（{@code runtime_plan_override} / {@code runtime_catalog_edit} /
     * {@code runtime_svid_pin}），不回写配置——配置仍是「设备真实长什么样」的唯一来源，
     * 运行态覆盖只在重载/重启时按顺序重放。</p>
     *
     * @param ex    交换
     * @param parts 路径段（已去掉前缀：{@code [deviceId, "runtime", 资源, ...]}）
     * @throws IOException 写响应失败
     */
    private void handleDeviceRuntimeV2(HttpExchange ex, String[] parts) throws IOException {
        try {
            this.dispatchDeviceRuntimeV2(ex, parts);
        } catch (com.shimizukenta.jsonhub.JsonHubParseException
                | com.shimizukenta.jsonhub.JsonHubUnsupportedOperationException
                | com.shimizukenta.jsonhub.JsonHubBuildException
                | com.shimizukenta.jsonhub.JsonHubIndexOutOfBoundsException e) {
            /* 请求体不是合法 JSON、或缺字段/类型不对（`root.get("ceid").longValue()`
               在缺键时 NPE、在字符串值时抛 UnsupportedOperation）：这是调用方的错。
               v1 的编辑路径早就这么兜了，v2 之前漏了——一个截断的 body 就能打出 500。 */
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\",\"message\":\""
                    + json(e.getMessage()) + "\"}");
        } catch (IllegalArgumentException e) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-request\",\"message\":\""
                    + json(e.getMessage()) + "\"}");
        }
    }

    /** 运行时资源的实际分派（异常由 {@link #handleDeviceRuntimeV2} 统一收敛成 4xx）。 */
    private void dispatchDeviceRuntimeV2(HttpExchange ex, String[] parts) throws IOException {
        String deviceId = parts[0];
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        String resource = parts.length > 2 ? parts[2] : "";

        switch (resource) {
            case "executions" -> {
                if (parts.length != 3) {
                    respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
                    return;
                }
                this.plans.handleExecutionsV2(ex, deviceId);
            }
            case "plans" -> this.plans.handlePlansV2(ex, deviceId, method, parts);
            case "cycle" -> this.handleDeviceCycleV2(ex, deviceId, method, parts);
            case "catalog" -> this.catalogs.handleCatalogEditV2(ex, deviceId, method, parts);
            case "svids" -> this.catalogs.handleSvidPinV2(ex, deviceId, method, parts);
            case "message" -> this.handleDeviceMessageV2(ex, deviceId, method);
            case "alarm" -> this.handleDeviceAlarmV2(ex, deviceId, method, parts);
            case "ceid" -> this.handleDeviceCeidV2(ex, deviceId, method, parts);
            default -> respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
        }
    }

    /**
     * POST /api/v2/devices/{id}/apply-sections：把刚落盘的配置段应用到这台设备。
     *
     * <p>请求体 {@code {"sections":["plans","throttle"]}}，段名与分段保存的
     * {@code changed} 一一对应。服务端以磁盘内容为准重新编译，再按段最小应用；
     * 前端不需要传新的配置正文，避免"UI 里的草稿"与"实际生效"出现两份真相。</p>
     */
    private void handleApplySectionsV2(HttpExchange ex, String deviceId) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        String body = readBody(ex, MAX_CONFIG_BYTES);
        if (body == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }
        java.util.List<String> sections = new java.util.ArrayList<>();
        try {
            com.shimizukenta.jsonhub.JsonHub root = com.shimizukenta.jsonhub.JsonHub.fromJson(body);
            com.shimizukenta.jsonhub.JsonHub list = root.isObject() ? root.get("sections") : null;
            if (list == null || !list.isArray()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            for (int i = 0; i < list.length(); i += 1) {
                com.shimizukenta.jsonhub.JsonHub item = list.get(i);
                if (item != null && item.isString()) {
                    item.optionalString().ifPresent(sections::add);
                }
            }
        } catch (RuntimeException e) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }
        ApiContextSessionOps.SectionsApplyResult result = this.context.applySectionsV2(deviceId, sections);
        audit(ex, "apply-sections", deviceId, "status=" + result.status());
        respond(ex, result.status(), result.json());
    }

    /**
     * v2 工艺周期（2026-11-16 设计 §2.4/M3）——注意路径在 {@code /api/v2/devices/{id}/cycle}
     * （无 runtime 段，与契约同形）；{@code GET} 运行态、{@code POST /cycle/start|stop} 启停。
     *
     * <p>启动有 ONLINE 门控（409 {@code not-online}，同计划启停）；
     * 配置里没有 cycle 段时启动回 409 {@code no-cycle}。</p>
     */
    private void handleDeviceCycleV2(HttpExchange ex, String deviceId, String method, String[] parts)
            throws IOException {
        if (parts.length == 2) {
            if (!"GET".equals(method)) {
                respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
                return;
            }
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            String json = this.context.cycleJsonV2(deviceId);
            if (json == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
                return;
            }
            respond(ex, 200, json);
            return;
        }
        String action = parts.length > 2 ? parts[2] : "";
        if (!"start".equals(action) && !"stop".equals(action)) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (!"POST".equals(method)) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        SessionOutcome outcome = "start".equals(action)
                ? this.context.cycleStartV2(deviceId)
                : this.context.cycleStopV2(deviceId);
        audit(ex, "cycle-" + action, deviceId, "outcome=" + outcome);
        switch (outcome) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"state\":\""
                    + ("start".equals(action) ? "RUNNING" : "STOPPED") + "\"}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            case NOT_ONLINE -> respond(ex, 409, "{\"ok\":false,\"error\":\"not-online\"}");
            default -> respond(ex, 409, "{\"ok\":false,\"error\":\"no-cycle\"}");
        }
    }

    /** {@code POST /runtime/message}：手动 SML 原文，不套 JSON 外壳。 */
    private void handleDeviceMessageV2(HttpExchange ex, String deviceId, String method)
            throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        if (!"POST".equals(method)) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        String sml = readBody(ex, MAX_SML_BYTES);
        if (sml == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"body-too-large\"}");
            return;
        }
        if (sml.isBlank()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"empty-sml\"}");
            return;
        }
        try {
            String result = this.context.sendSmlV2(deviceId, sml);
            if (result == null) {
                audit(ex, "send-sml", deviceId, "outcome=unknown-device bytes=" + sml.length());
                respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
                return;
            }
            audit(ex, "send-sml", deviceId, "outcome=ok bytes=" + sml.length());
            respond(ex, 200, result);
        } catch (IllegalArgumentException e) {
            audit(ex, "send-sml", deviceId, "outcome=bad-sml bytes=" + sml.length());
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-sml\",\"message\":\""
                    + json(e.getMessage()) + "\"}");
        } catch (IllegalStateException e) {
            audit(ex, "send-sml", deviceId, "outcome=not-open bytes=" + sml.length());
            respond(ex, 409, "{\"ok\":false,\"error\":\"device-offline\"}");
        } catch (IOException e) {
            audit(ex, "send-sml", deviceId, "outcome=failed bytes=" + sml.length());
            LOG.error("手动发送 SML 失败（v2 设备 {}，SML: {}）", deviceId, head(sml), e);
            respond(ex, 502, "{\"ok\":false,\"error\":\"send-failed\"}");
        }
    }

    /** 日志里只放 SML 首行：整段 SML 可能有几十行，进了日志就没人看得懂。 */
    private static String head(String sml) {
        if (sml == null) {
            return "";
        }
        int newline = sml.indexOf('\n');
        String first = (newline >= 0 ? sml.substring(0, newline) : sml).strip();
        return first.length() <= 120 ? first : first.substring(0, 120) + "…";
    }

    /** {@code POST|DELETE /runtime/alarm/{alid}}：置位 / 清除。 */
    private void handleDeviceAlarmV2(HttpExchange ex, String deviceId, String method,
                                     String[] parts) throws IOException {
        if (parts.length != 4) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        boolean set;
        if ("POST".equals(method)) {
            set = true;
        } else if ("DELETE".equals(method)) {
            set = false;
        } else {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        Integer alid = ConfigHandler.intOrNull(parts[3]);
        if (alid == null || alid < 1) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-alid\"}");
            return;
        }

        /* 文本与类别既可以放 JSON body，也可以放 query：v1 用的是 query，
           这里两种都认，免得调用方为一元值再包一层 JSON */
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        String text = query.getOrDefault("text", "");
        String categoryRaw = query.getOrDefault("category", "1");
        String body = readBody(ex);
        if (body == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"body-too-large\"}");
            return;
        }
        if (!body.isBlank()) {
            try {
                JsonHub root = JsonHub.fromJson(body);
                if (!root.isObject()) {
                    throw new IllegalArgumentException("expected object");
                }
                if (root.containsKey("text")) {
                    text = root.get("text").optionalString().orElse(text);
                }
                if (root.containsKey("category")) {
                    /* 只认数字：字符串形式的类别交给 query 传，省掉一层隐式转换 */
                    categoryRaw = String.valueOf(root.get("category").optionalInt()
                            .orElseThrow(() -> new IllegalArgumentException("category")));
                }
            } catch (RuntimeException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
        }

        Integer category = ConfigHandler.intOrNull(categoryRaw);
        if (category == null || category < 1 || category > 7) {
            /* ALCD 低 3 位是类别，0 无定义：入口就拒，别等 AlarmReporter 抛 IOException 变 500 */
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-category\"}");
            return;
        }
        if (!isAscii(text)) {
            /* SECS-II 的 A 是 US-ASCII（ALTX 含中文编码会失败） */
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-text\"}");
            return;
        }

        TriggerOutcome outcome = this.context.triggerAlarmV2(deviceId, alid, set, text, category);
        audit(ex, "alarm", deviceId, "alid=" + alid + " set=" + set
                + " category=" + category + " outcome=" + outcome);
        respondTrigger(ex, outcome);
    }

    /** {@code POST /runtime/ceid/{ceid}}：手动触发一次采集事件上报。 */
    private void handleDeviceCeidV2(HttpExchange ex, String deviceId, String method,
                                    String[] parts) throws IOException {
        if (parts.length != 4) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        if (!"POST".equals(method)) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        Integer ceid = ConfigHandler.intOrNull(parts[3]);
        if (ceid == null || ceid < 1) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-ceid\"}");
            return;
        }
        TriggerOutcome outcome = this.context.triggerCeidV2(deviceId, ceid);
        audit(ex, "trigger-ceid", deviceId, "ceid=" + ceid + " outcome=" + outcome);
        respondTrigger(ex, outcome);
    }

    /** 触发类结果的状态码映射（与 v1 手动触发保持一致：202 受理、409 未在线）。 */
    private static void respondTrigger(HttpExchange ex, TriggerOutcome outcome) throws IOException {
        switch (outcome) {
            case OK -> respond(ex, 202, "{\"ok\":true}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            case NOT_OPEN -> respond(ex, 409, "{\"ok\":false,\"error\":\"device-offline\"}");
            /* 发送失败是链路/对端的问题，不是本进程的 bug：与 v2 手动发送、
               主机模拟发送统一用 502，客户端据此提示"重试/检查链路"而不是"报 bug" */
            case FAILED -> respond(ex, 502, "{\"ok\":false,\"error\":\"send-failed\"}");
        }
    }

    /** SECS-II 的 A 类型只允许 US-ASCII。 */
    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}