package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.shimizukenta.jsonhub.JsonHub;
import com.sun.net.httpserver.HttpExchange;

/**
 * HTTP 处理器的共享底座：响应、鉴权、审计、请求体与查询参数解析。
 *
 * <p>原先这些都塞在 {@link HttpApiServer} 里，一个类两千多行、六个资源域的
 * 处理器混在一起。抽出这层之后，各资源域的处理器只关心自己的路由，
 * 共同的基础设施在这里改一处即可。</p>
 *
 * <p>鉴权与审计刻意从请求头重新解析身份，而不是把 Principal 穿透整条调用链：
 * 审计是旁路，不值得让每个 handler 多带一个参数。</p>
 */
abstract class HttpSupport {

    /** 请求体上限：创建会话只需要一个配置名，给 4 KiB 已经很宽裕。 */
    static final int MAX_BODY_BYTES = 4096;

    /**
     * 配置正文上限。
     *
     * <p>和 {@link #MAX_BODY_BYTES} 分开：配置正文是一整份设备定义，
     * 几百字节的设备还能编，真实的多腔体配置轻松几十 KB。</p>
     */
    static final int MAX_CONFIG_BYTES = 256 * 1024;

    /** 手动发送 SML 的正文上限。 */
    static final int MAX_SML_BYTES = 64 * 1024;

    /**
     * 单次曲线预览窗口的最大跨度（24 小时）。
     *
     * <p>{@code EventSimulator} 按 100ms 步长推进，窗口越大推进次数越多；
     * 预览 GET 不做 Origin 校验，任意网页都能发起请求，没有上限等于给了一个
     * 无需读取响应就能耗尽本机 CPU 的入口。实时预览与草稿预览共用同一条边界。</p>
     */
    static final long MAX_PREVIEW_WINDOW_MS = 24L * 60L * 60L * 1000L;

    /** 运行时上下文。 */
    protected final ApiContext context;

    /** 访问控制（认证与角色）。 */
    protected final AccessControl access;

    /** 审计出口；{@code null} 表示不审计。 */
    private final AuditSink audit;

    HttpSupport(ApiContext context, AccessControl access, AuditSink audit) {
        this.context = context;
        this.access = access;
        this.audit = audit;
    }

    /* ===== 鉴权与审计 ===== */

    /**
     * 要求调用方至少具备某角色。
     *
     * <p>内部工具直连访问：身份恒为本机管理员，这里不会拒绝；保留角色判断是为了
     * 将来接入鉴权时只改 {@link AccessControl}，不用回头改每个 handler。</p>
     *
     * @return {@code true} 表示放行；否则已写出错误响应，调用方必须立即返回
     */
    protected boolean requireRole(HttpExchange ex, ApiRole required) throws IOException {
        AccessControl.Principal principal = authenticate(ex);
        if (principal == null) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return false;
        }
        if (!principal.role().atLeast(required)) {
            audit(ex, "denied", ex.getRequestURI().getPath(),
                    "required=" + required + " role=" + principal.role().name().toLowerCase(Locale.ROOT));
            respond(ex, 403, "{\"ok\":false,\"error\":\"forbidden\",\"required\":\"" + required + "\"}");
            return false;
        }
        return true;
    }

    /** 直连访问下恒为本机管理员（请求头不参与身份判定）。 */
    protected AccessControl.Principal authenticate(HttpExchange ex) {
        return this.access.authenticate(null);
    }

    /**
     * 记录一条审计；没有审计器时静默跳过。
     *
     * <p>直连访问下主体恒为 {@code local}，仍记录角色，便于日志格式保持稳定。</p>
     *
     * @param ex     当前请求
     * @param action 操作名
     * @param target 操作对象
     * @param detail 细节
     */
    protected void audit(HttpExchange ex, String action, String target, String detail) {
        AuditSink sink = this.audit;
        if (sink == null) {
            return;
        }
        AccessControl.Principal principal = authenticate(ex);
        if (principal == null) {
            return;
        }
        sink.record(principal.name(),
                principal.role().name().toLowerCase(Locale.ROOT),
                action, target, detail);
    }

    /* ===== 采集器命令的统一出口 ===== */

    /**
     * 把结构化的 {@link CollectorResult} 映射成状态码 + 响应体，并记审计。
     *
     * <p>以前这里靠 {@code result.contains("\"ok\":true")} 猜成败：配置错误消息里
     * 一旦带上这个子串（或任何回显内容含它），失败会被报成 200。状态码必须由枚举决定。</p>
     *
     * @param ex     交换
     * @param result 结构化结果
     * @param action 审计动作名
     * @param target 审计对象
     * @throws IOException 写响应失败
     */
    protected void respondCollector(HttpExchange ex, CollectorResult result,
                                    String action, String target) throws IOException {
        String body;
        int status;
        switch (result.outcome()) {
            case OK -> {
                status = 200;
                body = "{\"ok\":true}";
            }
            case UNKNOWN_HOST -> {
                status = 404;
                body = "{\"ok\":false,\"error\":\"host-sim-not-open\"}";
            }
            case NOT_CONFIGURED -> {
                status = 409;
                body = "{\"ok\":false,\"error\":\"collector-not-configured\",\"message\":"
                        + json(result.message()) + "}";
            }
            default -> {
                status = 400;
                body = "{\"ok\":false,\"error\":\"invalid-collector-config\",\"message\":"
                        + json(result.message()) + "}";
            }
        }
        audit(ex, action, target, "outcome=" + result.outcome());
        respond(ex, status, body);
    }

    /* ===== JSON 取值的空安全 ===== */

    /**
     * 取对象里的字符串字段，缺失时给空串。
     *
     * <p>{@link com.shimizukenta.jsonhub.JsonHub#get(CharSequence)} 在键不存在时
     * 返回 {@code null}，直接链 {@code .optionalString()} 会 NPE——
     * 请求体少一个字段就把整条连接打断，客户端只看到"连接被意外关闭"。
     * 这里统一兜住。</p>
     *
     * @param root JSON 对象
     * @param name 字段名
     * @return 字段值；缺失或非字符串时是空串
     */
    protected static String str(com.shimizukenta.jsonhub.JsonHub root, String name) {
        if (root == null) {
            return "";
        }
        com.shimizukenta.jsonhub.JsonHub value = root.get(name);
        return value == null ? "" : value.optionalString().orElse("");
    }

    /**
     * 取对象里的整数字段，缺失或非法时给 0。
     *
     * @param root JSON 对象
     * @param name 字段名
     * @return 字段值；缺失或非法时是 0
     */
    protected static int num(com.shimizukenta.jsonhub.JsonHub root, String name) {
        if (root == null) {
            return 0;
        }
        com.shimizukenta.jsonhub.JsonHub value = root.get(name);
        return value == null ? 0 : value.optionalInt().orElse(0);
    }

    /* ===== 请求体与查询参数 ===== */

    /** 读请求体（默认上限）。 */
    protected static String readBody(HttpExchange ex) throws IOException {
        return readBody(ex, MAX_BODY_BYTES);
    }

    /**
     * 读请求体并限制长度。
     *
     * @param ex    交换
     * @param limit 字节上限
     * @return 正文；超过上限返回 {@code null}
     * @throws IOException 读失败
     */
    protected static String readBody(HttpExchange ex, int limit) throws IOException {
        /* 先看声明的长度：Content-Length 巨大 + 每秒 1 字节是打满 4/8 个请求线程的标准手法，
           不预检就会一直阻塞在 readNBytes 上。 */
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && !declared.isBlank()) {
            try {
                if (Long.parseLong(declared.trim()) > limit) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                /* 非法 Content-Length 交给下面的读取与 JDK 处理 */
            }
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** 读请求体，空正文视为非法。 */
    protected static String requireBody(HttpExchange ex) throws IOException {
        return requireBody(ex, MAX_BODY_BYTES);
    }

    /**
     * 读请求体，空正文视为非法；上限由调用方指定。
     *
     * <p>「超长」与「空」必须给不同的错误：把超长报成"请求体为空"，用户会去检查自己
     * 到底发了什么，而不是去查大小。运行时编辑（报告/事件/SVID）的正文可以很大，
     * 这些端点要用 {@link #MAX_CONFIG_BYTES}。</p>
     *
     * @param ex    交换
     * @param limit 字节上限
     * @return 正文
     * @throws IllegalArgumentException 正文为空或超过上限
     */
    protected static String requireBody(HttpExchange ex, int limit) throws IOException {
        String body = readBody(ex, limit);
        if (body == null) {
            throw new IllegalArgumentException("请求体超过上限 " + limit + " 字节");
        }
        if (body.trim().isEmpty()) {
            throw new IllegalArgumentException("请求体为空");
        }
        return body;
    }

    /**
     * 读请求体并解析成 JSON 对象；失败时已写出 400，调用方直接返回。
     *
     * <p>这里把三件以前各写各的事收到一处：</p>
     * <ul>
     *   <li>空正文 / 超过上限 → 400（而不是抛出去变 500）</li>
     *   <li>语法非法 → 400</li>
     *   <li>合法 JSON 但不是对象（{@code [1,2]} / {@code "x"} / {@code null}）→ 400</li>
     * </ul>
     *
     * <p>第三条尤其容易漏：{@code JsonHub.fromJson("[]")} 是成功的，紧接着
     * {@code root.get("name")} 抛 {@code JsonHubUnsupportedOperationException}，
     * 如果取值不在 {@code try} 内，客户端看到的就是 500「服务器出错」，
     * 而实际问题只是它发了个数组。</p>
     *
     * @param ex 交换
     * @return 解析出的对象；失败返回 {@code null}（响应已写出）
     * @throws IOException 写响应失败
     */
    protected JsonHub readJsonObject(HttpExchange ex) throws IOException {
        return readJsonObject(ex, MAX_BODY_BYTES);
    }

    /**
     * 读请求体并解析成 JSON 对象，正文上限由调用方指定。
     *
     * @param ex    交换
     * @param limit 字节上限
     * @return 解析出的对象；失败返回 {@code null}（响应已写出）
     * @throws IOException 写响应失败
     */
    protected JsonHub readJsonObject(HttpExchange ex, int limit) throws IOException {
        String raw = readBody(ex, limit);
        if (raw == null || raw.isBlank()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return null;
        }
        JsonHub request;
        try {
            request = JsonHub.fromJson(raw);
        } catch (RuntimeException e) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return null;
        }
        if (request == null || !request.isObject()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return null;
        }
        return request;
    }

    /**
     * 解析 {@code limit} 查询参数，缺省给 {@code dflt}，越界或非法返回 {@code -1}。
     *
     * <p>以前是手写 {@code Integer.parseInt(pair.substring(6))}：{@code ?limit=abc}
     * 直接抛 {@code NumberFormatException} 变 500；不设上限则一次查询就能把整张
     * 报文表拉进内存。非法值返回哨兵而不是悄悄用默认值——静默兜底会让调用方
     * 以为自己传对了。</p>
     *
     * @param ex   交换
     * @param dflt 缺省值
     * @param max  允许的最大值
     * @return 解析结果；非法或越界返回 {@code -1}
     */
    protected static int limitOrBad(HttpExchange ex, int dflt, int max) {
        Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
        String raw = query.get("limit");
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 1 || value > max ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 解析查询串为键值表。 */
    protected static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.put(decode(pair), "");
            } else {
                result.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return result;
    }

    /** URL 解码。 */
    protected static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** 取长整型查询参数；缺省或非法返回 {@code null}。 */
    protected static Long longParam(Map<String, String> query, String key) {
        String raw = query.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取整型查询参数；缺省或非法返回 {@code null}。 */
    protected static Integer intParam(Map<String, String> query, String key) {
        String raw = query.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取 JSON 对象的可选字符串字段。
     *
     * @param root JSON 对象
     * @param key  字段名
     * @return 字段值；缺失或非字符串返回 {@code null}
     */
    protected static String optionalString(com.shimizukenta.jsonhub.JsonHub root, String key) {
        if (!root.containsKey(key)) {
            return null;
        }
        return root.get(key).optionalString().orElse(null);
    }

    /**
     * 取 JSON 对象的可选整数字段，并按范围校验。
     *
     * @param root  JSON 对象
     * @param key   字段名
     * @param valid 范围校验
     * @return 字段值；缺失或非法返回 {@code null}
     */
    protected static Integer optionalInt(com.shimizukenta.jsonhub.JsonHub root, String key,
                                        java.util.function.IntPredicate valid) {
        if (!root.containsKey(key)) {
            return null;
        }
        java.util.OptionalInt value = root.get(key).optionalInt();
        if (value.isEmpty() || !valid.test(value.getAsInt())) {
            return null;
        }
        return Integer.valueOf(value.getAsInt());
    }

    /** 从路径片段里取整数；非法返回 {@code -1}。 */
    protected static int pathInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /* ===== 响应 ===== */

    /** 回 JSON 正文。 */
    protected static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 只回状态码，但正文仍是合法 JSON。
     *
     * <p>以前这里发 0 字节正文却带 {@code application/json} 头，客户端
     * {@code JSON.parse} 必抛。现在成功类回 {@code {"ok":true}}；
     * 204 按规范不能有正文，客户端需要自己判状态码。</p>
     *
     * @param ex     交换
     * @param status 状态码
     * @throws IOException 写失败
     */
    protected static void respondStatus(HttpExchange ex, int status) throws IOException {
        respondStatus(ex, status, null);
    }

    /**
     * 只回状态码，并显式指定错误码。
     *
     * @param ex        交换
     * @param status    状态码
     * @param errorCode 错误码；只在错误类状态（≥400）使用，{@code null} 按状态码推导
     * @throws IOException 写失败
     */
    protected static void respondStatus(HttpExchange ex, int status, String errorCode) throws IOException {
        if (status == 204) {
            /* 204 不允许正文：不能为了统一包络硬塞一个 {} */
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (status >= 200 && status < 300) {
            respond(ex, status, "{\"ok\":true}");
            return;
        }
        String code = errorCode != null ? errorCode : statusErrorCode(status);
        respond(ex, status, "{\"ok\":false,\"error\":\"" + json(code) + "\"}");
    }

    /** 状态码对应的默认错误码。 */
    private static String statusErrorCode(int status) {
        return switch (status) {
            case 400 -> "bad-request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 409 -> "conflict";
            case 412 -> "precondition-failed";
            case 422 -> "invalid";
            default -> status >= 500 ? "internal-error" : "request-failed";
        };
    }

    /** 回二进制正文；用于曲线预览等不适合 JSON 的负载。 */
    protected static void respondBinary(HttpExchange ex, String contentType,
                                       String filename, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** 回 JSON Lines；浏览器会按附件下载，curl 拿到的是逐行 JSON。 */
    protected static void respondNdjson(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"messages.jsonl\"");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** JSON 字符串转义；控制字符一律转成 \\u00XX，避免拼出非法帧。 */
    protected static String json(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
