package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import com.shimizukenta.secssim.app.log.Log;
import java.util.List;

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
