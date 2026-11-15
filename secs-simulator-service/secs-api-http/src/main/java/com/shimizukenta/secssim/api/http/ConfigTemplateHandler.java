package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;

/**
 * 模板域：内置设备模板目录、自定义模板与参数模板的读写。
 */
final class ConfigTemplateHandler extends HttpSupport {

    ConfigTemplateHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
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
            case INVALID -> respond(ex, 400, ConfigHandler.problemsJson(false, result.problems()));
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
     * GET /api/v2/param-templates：全部参数模板（条目带模板体，设备绑定不用二次请求）；
     * POST 新建/更新（体是 {@code {"id"?,"name","description","body"}}，id 空 = 新建）。
     */
    void handleParamTemplatesV2(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            respond(ex, 200, "{\"templates\":" + this.context.paramTemplatesJson() + "}");
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
                String id = str(root, "id");
                String name = str(root, "name");
                String description = str(root, "description");
                String templateBody = str(root, "body");
                if (templateBody.isEmpty()) {
                    respond(ex, 400, ConfigHandler.problemsJson(false, List.of("body 不能为空")));
                    return;
                }
                ParamTemplateSaved saved =
                        this.context.saveParamTemplate(id, name, description, templateBody);
                audit(ex, "param-template-save", saved.id() == null ? name : saved.id(),
                        "outcome=" + saved.result().outcome());
                switch (saved.result().outcome()) {
                    case OK -> respond(ex, saved.id().equals(id) ? 200 : 201,
                            "{\"ok\":true,\"id\":\"" + json(saved.id()) + "\"}");
                    case CONFLICT -> respond(ex, 409,
                            "{\"ok\":false,\"error\":\"param-template-conflict\",\"problems\":[\""
                                    + json(String.join("; ", saved.result().problems())) + "\"]}");
                    case NOT_FOUND -> respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
                    case INVALID -> respond(ex, 400, ConfigHandler.problemsJson(false, saved.result().problems()));
                    default -> respond(ex, 500, "{\"ok\":false,\"error\":\"param-template-failed\"}");
                }
            } catch (RuntimeException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-json\"}");
            }
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /** DELETE /api/v2/param-templates/{id}；内置模板拒绝（409）。 */
    void handleParamTemplateV2Path(HttpExchange ex) throws IOException {
        String id = paramTemplateIdFromPath(ex);
        if (id == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }
        if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            ConfigResult result = this.context.deleteParamTemplate(id);
            audit(ex, "param-template-delete", id, "outcome=" + result.outcome());
            switch (result.outcome()) {
                case OK -> respond(ex, 200, "{\"ok\":true,\"id\":\"" + json(id) + "\"}");
                case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"param-template-readonly\"}");
                case NOT_FOUND -> respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
                default -> respond(ex, 500, "{\"ok\":false,\"error\":\"param-template-failed\"}");
            }
            return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            /* 单个条目暂无独立读端点：列表已带 body，前端不需要额外请求 */
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    private static String paramTemplateIdFromPath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        String prefix = ApiPaths.PARAM_TEMPLATE_V2_PREFIX;
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
}