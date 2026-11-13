package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import com.shimizukenta.secssim.app.log.Log;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.sun.net.httpserver.HttpExchange;

/**
 * 配置域：配置目录、配置本体的读写、校验与保存、内置模板目录、草稿曲线预览。
 */
final class ConfigHandler extends HttpSupport {

    ConfigHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
    }


    /**
     * POST /api/v1/config-preview：按草稿文本算曲线预览。
     *
     * <p>编辑器里改模板/SVID 时不用先保存再开会话——直接看"这份配置会画出什么曲线"。
     * 时间窗相对模拟时钟的 0..N 毫秒（草稿没有运行时态，从种子确定性重放）。</p>
     */
    void handleConfigPreview(HttpExchange ex) throws IOException {
        /* 草稿预览会触发一次完整编译与临时文件写入：和同文件其它写路径同属 OPERATOR。
           鉴权放在方法检查之前：没凭据的请求不该先知道"这里有这条路"（405） */
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        long fromMs = 0L;
        long toMs = 60_000L;
        int buckets = 120;
        boolean withEvents = true;
        java.util.Set<Integer> svids = new java.util.LinkedHashSet<>();
        String query = ex.getRequestURI().getRawQuery();
        if (query != null && !query.isEmpty()) {
            try {
                for (String pair : query.split("&")) {
                    int eq2 = pair.indexOf('=');
                    if (eq2 <= 0) {
                        continue;
                    }
                    String key = pair.substring(0, eq2);
                    String value = pair.substring(eq2 + 1);
                    switch (key) {
                        case "from" -> fromMs = Long.parseLong(value);
                        case "to" -> toMs = Long.parseLong(value);
                        case "buckets" -> buckets = Integer.parseInt(value);
                        case "withEvents" -> withEvents = !"false".equalsIgnoreCase(value) && !"0".equals(value);
                        default -> { }
                    }
                }
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-query\"}");
                return;
            }
        }
        /* 与 /preview 同一套边界：不校验就会把 0 桶送进 compute 里做除法 */
        if (toMs <= fromMs) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-range\"}");
            return;
        }
        /* 草稿预览同样会跑 EventSimulator：窗口上限必须与实时预览一致，
           否则超大窗口会被下层异常吞成 422 config-invalid，把 CPU 预算问题误报成配置错误。
           windowMs < 0 捕获 to-from 溢出。 */
        long windowMs = toMs - fromMs;
        if (windowMs < 0 || windowMs > MAX_PREVIEW_WINDOW_MS) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"range-too-large\"}");
            return;
        }
        if (buckets < 1 || buckets > 500) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-buckets\"}");
            return;
        }

        String text = readBody(ex, MAX_CONFIG_BYTES);
        if (text == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }
        String rawSvids = ex.getRequestURI().getRawQuery();
        if (rawSvids != null) {
            for (String pair : rawSvids.split("&")) {
                if (pair.startsWith("svids=")) {
                    try {
                        for (String piece : pair.substring("svids=".length()).split(",")) {
                            if (!piece.isBlank()) {
                                svids.add(Integer.parseInt(piece.trim()));
                            }
                        }
                    } catch (NumberFormatException e) {
                        respond(ex, 400, "{\"ok\":false,\"error\":\"bad-svids\"}");
                        return;
                    }
                }
            }
        }

        String json = this.context.configPreviewJson(text, fromMs, toMs, buckets, svids, withEvents);
        if (json == null) {
            respond(ex, 422, "{\"ok\":false,\"error\":\"config-invalid\"}");
            return;
        }
        respond(ex, 200, json);
    }


    /**
     * GET /api/v1/configs：配置目录列表。
     *
     * <p>POST 同一个路径是"新建"：给个文件名，后端生成一份能直接编译的骨架。
     * 新建与保存分开，是因为两者的语义完全不同——
     * 新建是"给我一个起点"，保存是"把这段内容写成这样"。
     * </p>
     */
    void handleConfigs(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            respond(ex, 200, "{\"configs\":" + this.context.configsJson() + "}");
            return;
        }

        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            this.createConfig(ex);
            return;
        }

        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }


    /**
     * POST /api/v1/configs：新建一份配置。
     *
     * <p>体是 {@code {"name":"新配置.json"}}；可选 {@code "template"} 选内置
     * 设备模板（目录见 GET /api/v1/config-templates），缺省是空骨架。
     * </p>
     */
    void createConfig(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        if (body == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }

        NewConfigRequest request = newConfigRequestFrom(body);
        if (request == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"missing-name\"}");
            return;
        }

        ConfigResult result = this.context.createConfig(request.name(), request.templateId());
        audit(ex, "config-create", request.name(),
                "template=" + request.templateId() + " outcome=" + result.outcome());
        switch (result.outcome()) {
            case OK -> respond(ex, 201, "{\"ok\":true,\"name\":\"" + json(request.name()) + "\"}");
            case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"config-exists\"}");
            /* 名字非法要说清是哪个名字、为什么不行，不能笼统回一句"创建失败" */
            case NOT_FOUND, INVALID -> respond(ex, 400, problemsJson(false, result.problems()));
            default -> respond(ex, 500, "{\"ok\":false,\"error\":\"create-failed\"}");
        }
    }


    /** 从请求体里取 name 与可选 template 字段；体不是对象或缺 name 时返回 {@code null}。 */
    static NewConfigRequest newConfigRequestFrom(String body) {
        try {
            com.shimizukenta.jsonhub.JsonHub root =
                    com.shimizukenta.jsonhub.JsonHub.fromJson(body);
            if (!root.isObject()) {
                return null;
            }
            String name = str(root, "name").trim();
            if (name == null) {
                return null;
            }
            /* 模板缺省给空骨架（DeviceTemplates 在 runtime 层，这里只认同一字符串约定）：
               模板字段写错值走"未知模板"问题清单，不在这里拦。JsonHub.get 缺键会抛，先 containsKey */
            String templateId = str(root, "template").trim();
            if (templateId.isEmpty()) {
                templateId = "bare";
            }
            return new NewConfigRequest(name, templateId);
        } catch (RuntimeException e) {
            return null;
        }
    }


    /**
     * GET /api/v1/config-templates：内置设备模板目录。
     *
     * <p>新建配置的选型列表：id 是 POST /api/v1/configs 里 {@code template}
     * 字段的取值，label/description 给界面显示。</p>
     */
    void handleConfigTemplates(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 200, "{\"templates\":" + this.context.configTemplatesJson() + "}");
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /** GET /api/v1/templates：内置 + 自定义模板；POST 新建自定义模板。 */
    void handleTemplates(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            respond(ex, 200, "{\"templates\":" + this.context.templatesJson() + "}");
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            String body = readBody(ex, MAX_CONFIG_BYTES);
            if (body == null) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            try {
                com.shimizukenta.jsonhub.JsonHub root = com.shimizukenta.jsonhub.JsonHub.fromJson(body);
                String id = root.get("id").optionalString().orElse("").trim();
                String text = root.get("text").optionalString().orElse("");
                ConfigResult result = this.context.saveTemplate(id, text);
                this.templateResult(ex, id, result);
            } catch (RuntimeException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-json\"}");
            }
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /** GET/PUT/DELETE /api/v1/templates/{id}。 */
    void handleTemplatePath(HttpExchange ex) throws IOException {
        String id = templateIdFromPath(ex);
        if (id == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            String text = this.context.templateText(id).orElse(null);
            if (text == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
                return;
            }
            respond(ex, 200, "{\"id\":\"" + json(id) + "\",\"text\":\"" + json(text) + "\"}");
            return;
        }
        if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            String text = readBody(ex, MAX_CONFIG_BYTES);
            if (text == null) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            this.templateResult(ex, id, this.context.saveTemplate(id, text));
            return;
        }
        if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            this.templateResult(ex, id, this.context.deleteTemplate(id));
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    private void templateResult(HttpExchange ex, String id, ConfigResult result) throws IOException {
        switch (result.outcome()) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"id\":\"" + json(id) + "\"}");
            case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"template-conflict\"}");
            case NOT_FOUND -> respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            case INVALID -> respond(ex, 400, problemsJson(false, result.problems()));
            default -> respond(ex, 500, "{\"ok\":false,\"error\":\"template-failed\"}");
        }
    }

    private static String templateIdFromPath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        String prefix = ApiPaths.TEMPLATE_PREFIX;
        if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
            return null;
        }
        String rest = path.substring(prefix.length());
        if (rest.contains("/")) {
            return null;
        }
        /* getPath() 已经解过一次码；再 decode 会把含 % 的 id 解坏并抛 IAE（500） */
        return rest;
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
                this.handleDeviceLinkStateV2(ex, parts[0]);
                return;
            }
            if (parts.length == 3) {
                this.handleDeviceLinkV2(ex, parts[0], parts[2]);
                return;
            }
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (parts.length >= 3 && !parts[0].isBlank() && "runtime".equals(parts[1])) {
            this.handleDeviceRuntimeV2(ex, parts);
            return;
        }
        this.handleConfigRevisionsV2(ex);
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
                this.handleExecutionsV2(ex, deviceId);
            }
            case "plans" -> this.handlePlansV2(ex, deviceId, method, parts);
            case "catalog" -> this.handleCatalogEditV2(ex, deviceId, method, parts);
            case "svids" -> this.handleSvidPinV2(ex, deviceId, method, parts);
            case "message" -> this.handleDeviceMessageV2(ex, deviceId, method);
            case "alarm" -> this.handleDeviceAlarmV2(ex, deviceId, method, parts);
            case "ceid" -> this.handleDeviceCeidV2(ex, deviceId, method, parts);
            default -> respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
        }
    }

    /**
     * {@code /runtime/plans[/{planId}]} 与 {@code .../{planId}/{step|enabled}}。
     *
     * <p>{@code step} 与 {@code enabled} 沿用已发布的处理器：它们的鉴权、
     * 请求体解析与状态码已经和界面联调过，不在这里重写一遍。</p>
     */
    private void handlePlansV2(HttpExchange ex, String deviceId, String method,
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
                respondEditStatus(ex, status);
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
            respondEditStatus(ex, status);
            return;
        }
        if ("DELETE".equals(method)) {
            int status = this.context.removePlanV2(deviceId, planId);
            audit(ex, "plan-delete", deviceId, "plan=" + planId + " status=" + status);
            respondEditStatus(ex, status);
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /** {@code /runtime/catalog/{kind}[/{key}]}，{@code kind = ceid | report | svid}。 */
    private void handleCatalogEditV2(HttpExchange ex, String deviceId, String method,
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
            respondEditStatus(ex, status);
            return;
        }

        Integer key = intOrNull(parts[4]);
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
            respondEditStatus(ex, status);
            return;
        }
        if ("DELETE".equals(method)) {
            int status = this.context.removeCatalogV2(deviceId, kind, key);
            audit(ex, kind + "-delete", deviceId, "key=" + key + " status=" + status);
            respondEditStatus(ex, status);
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /** {@code PUT|DELETE /runtime/svids/{svid}/pin}：钉值 / 取消钉值。 */
    private void handleSvidPinV2(HttpExchange ex, String deviceId, String method,
                                 String[] parts) throws IOException {
        if (parts.length != 5 || !"pin".equals(parts[4])) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }
        Integer svid = intOrNull(parts[3]);
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
            respondEditStatus(ex, status);
            return;
        }
        if ("DELETE".equals(method)) {
            int status = this.context.unpinSvidV2(deviceId, svid);
            audit(ex, "svid-unpin", deviceId, "svid=" + svid + " status=" + status);
            respondEditStatus(ex, status);
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
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
            Log.error("手动发送 SML 失败（v2 设备 " + deviceId + "）", e);
            respond(ex, 502, "{\"ok\":false,\"error\":\"send-failed\"}");
        }
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
        Integer alid = intOrNull(parts[3]);
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

        Integer category = intOrNull(categoryRaw);
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
        Integer ceid = intOrNull(parts[3]);
        if (ceid == null || ceid < 1) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-ceid\"}");
            return;
        }
        TriggerOutcome outcome = this.context.triggerCeidV2(deviceId, ceid);
        audit(ex, "trigger-ceid", deviceId, "ceid=" + ceid + " outcome=" + outcome);
        respondTrigger(ex, outcome);
    }

    /**
     * 编辑类状态码的响应映射。
     *
     * <p>v2 把资源拆到了不同路径，但语义与 v1 编辑路径一致：
     * 201 新建、200 改/删、404 设备或条目不存在、409 冲突、400 请求体不合法。</p>
     */
    private static void respondEditStatus(HttpExchange ex, int status) throws IOException {
        switch (status) {
            case 200, 201 -> respond(ex, status, "{\"ok\":true}");
            case 400 -> respond(ex, 400, "{\"ok\":false,\"error\":\"bad-request\"}");
            case 404 -> respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            case 409 -> respond(ex, 409, "{\"ok\":false,\"error\":\"conflict\"}");
            default -> respond(ex, 500, "{\"ok\":false,\"error\":\"edit-failed\"}");
        }
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

    /** 路径段转整数；非数字返回 {@code null}（调用方按 400 处理）。 */
    private static Integer intOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
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

    /** GET /api/v2/devices/{id}/runtime/executions：执行台账（缺省不含试跑）。 */
    private void handleExecutionsV2(HttpExchange ex, String deviceId) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
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
    private void handleStepV2(HttpExchange ex, String deviceId, String planId) throws IOException {
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
    private void handleEnabledV2(HttpExchange ex, String deviceId, String planId) throws IOException {
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
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"device-not-found\"}");
            default -> respond(ex, 400, "{\"ok\":false,\"error\":\"plan-not-found\"}");
        }
    }

    /** GET /api/v2/devices/{id}/link：链路相位（断开展示、重连按钮的状态来源）。 */
    private void handleDeviceLinkStateV2(HttpExchange ex, String deviceId) throws IOException {
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

    /** POST /api/v2/devices/{id}/link/{connect|disconnect|reload}。 */
    private void handleDeviceLinkV2(HttpExchange ex, String deviceId, String action) throws IOException {
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

    /**
     * 单份配置的读取、校验与保存。
     *
     * <p>读是 {@link ApiRole#VIEWER}；保存是 {@link ApiRole#OPERATOR}：
     * 配置决定了下一台设备长什么样，改它属于"会改变设备状态"的那一类，
     * 和触发 CEID、增删会话同属一级。
     * </p>
     */
    void handleConfigPath(HttpExchange ex) throws IOException {
        if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            this.saveConfig(ex);
            return;
        }

        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }

        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            this.readConfig(ex);
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            this.validateConfig(ex);
            return;
        }

        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }


    /**
     * GET /api/v1/configs/{name}：回原文。
     *
     * <p>回的是<b>原文</b>而不是模型反序列化结果：编辑器必须看到用户写的每一个键。
     * </p>
     */
    void readConfig(HttpExchange ex) throws IOException {
        String name = configNameFromPath(ex);
        if (name == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        /* 正文与 mtime 必须一次取：分两次读到「旧正文 + 新 mtime」时，
           编辑器带着"正确的" If-Match 保存，反而绕过并发保护覆盖别人的修改。 */
        ApiContext.ConfigSnapshot snapshot = this.context.configSnapshot(name);
        if (snapshot == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }

        String meta = snapshot.modifiedMs() == null ? "" : ",\"modifiedMs\":" + snapshot.modifiedMs();
        respond(ex, 200, "{\"name\":\"" + json(name) + "\",\"text\":\"" + json(snapshot.text()) + "\"" + meta + "}");
    }


    /**
     * POST /api/v1/configs/{name}/validate：只校验不落盘。
     *
     * <p>和"保存"分开，是因为保存一步就写盘了。用户想先确认自己这份改动能不能编译，
     * 不该被迫先覆盖一份能用的配置。
     * </p>
     */
    void validateConfig(HttpExchange ex) throws IOException {
        String name = configNameFromPath(ex);
        if (name == null || !isValidatePath(ex)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        String text = readBody(ex, MAX_CONFIG_BYTES);
        if (text == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }

        List<String> problems = this.context.validateConfig(text);
        respond(ex, 200, problemsJson(problems.isEmpty(), problems));
    }


    /**
     * PUT /api/v1/configs/{name}：校验通过后原子落盘。
     *
     * <p>请求体就是配置原文，不包一层 JSON 包装：
     * 编辑器里那份文本应当能原样发出去、原样存回来，中间任何一层转义都只会制造差异。
     * </p>
     */
    void saveConfig(HttpExchange ex) throws IOException {
        String name = configNameFromPath(ex);
        if (name == null || isValidatePath(ex)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        String text = readBody(ex, MAX_CONFIG_BYTES);
        if (text == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }

        /* If-Match：编辑器把 GET 时拿到的 modifiedMs 原样带回来；编辑期间文件被别人改过就拒绝覆盖 */
        Long expectedModifiedMs = null;
        String ifMatch = ex.getRequestHeaders().getFirst("If-Match");
        if (ifMatch != null && !ifMatch.isBlank()) {
            try {
                expectedModifiedMs = Long.parseLong(ifMatch.trim().replace("\"", ""));
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-if-match\"}");
                return;
            }
        }

        ConfigResult result = this.context.saveConfig(name, text, expectedModifiedMs);
        audit(ex, "config-save", name, "outcome=" + result.outcome() + " bytes=" + text.length());
        switch (result.outcome()) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"name\":\"" + json(name) + "\"}");
            /* 内容不合法用 422：请求本身没毛病，是内容过不了关，和 400 的"body 坏了"不是一回事 */
            case INVALID -> respond(ex, 422, problemsJson(false, result.problems()));
            /* 编辑期间文件被改过：用 409 Conflict，让编辑器提示重新载入 */
            case CONFLICT -> respond(ex, 409, problemsJson(false, result.problems()));
            /* 名字非法：这是请求字段的问题，不是"文件没找到" */
            case NOT_FOUND -> respond(ex, 400, problemsJson(false, result.problems()));
            case FAILED -> respond(ex, 500, problemsJson(false, result.problems()));
        }
    }


    /** 问题清单的 JSON。 */
    static String problemsJson(boolean valid, List<String> problems) {
        StringBuilder sb = new StringBuilder("{\"ok\":").append(valid)
                .append(",\"valid\":").append(valid)
                .append(",\"problems\":[");
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(json(problems.get(i))).append('"');
        }
        return sb.append("]}").toString();
    }


    /** 取路径里的配置名；形状不对时返回 {@code null}。 */
    static String configNameFromPath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (path == null || !path.startsWith(ApiPaths.CONFIG_PREFIX)) {
            return null;
        }

        String rest = path.substring(ApiPaths.CONFIG_PREFIX.length());
        if (rest.isEmpty()) {
            return null;
        }

        String[] parts = rest.split("/");
        if (parts.length == 1 && !parts[0].isEmpty()) {
            /* getPath() 已解码：不要再 decode，含 % 的名字会被解坏 */
            return parts[0];
        }
        if (parts.length == 2 && !parts[0].isEmpty() && ApiPaths.VALIDATE_SUFFIX.equals(parts[1])) {
            return parts[0];
        }
        return null;
    }


    /** 请求是否打在校验子路径上（必须恰好是 {@code /configs/<name>/validate} 两段）。 */
    static boolean isValidatePath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (path == null || !path.startsWith(ApiPaths.CONFIG_PREFIX)) {
            return false;
        }
        String[] parts = path.substring(ApiPaths.CONFIG_PREFIX.length()).split("/");
        return parts.length == 2 && ApiPaths.VALIDATE_SUFFIX.equals(parts[1]);
    }
    /**
     * 新建配置请求：文件名 + 设备模板 id。
     *
     * @param name       新配置文件名（须带 {@code .json}）
     * @param templateId 内置设备模板 id
     */
    private record NewConfigRequest(String name, String templateId) {
    }
}
