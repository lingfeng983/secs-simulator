package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.util.Map;
import com.shimizukenta.secssim.app.log.Log;

import com.shimizukenta.jsonhub.JsonHub;
import com.sun.net.httpserver.HttpExchange;

/**
 * 会话域：会话列表、新建、批量新建，以及单个会话下的查询与命令。
 *
 * <p>原先这些路由散在 {@code HttpApiServer} 里两千多行的一坨；按资源域拆开后，
 * 一个域一个文件，读某个路由不用翻整台服务器。</p>
 */
final class SessionHandler extends HttpSupport {

    private static final Log.Facade LOG = Log.of(SessionHandler.class);

    /**
     * 查询压测状态的哨兵请求体。
     *
     * <p>{@link ApiContext#burst} 是压测域唯一的接口；GET 也用同一入口，
     * 传这个对象让运行时只回状态、不再起一轮压测。</p>
     */
    private static final String BURST_STATUS_REQUEST = "{\"status\":true}";

    /**
     * 废弃端点的下线日期（{@code Sunset} 头，RFC 8594）。
     *
     * <p>给一个明确日期而不是「以后」：客户端与脚本能据此排迁移计划。
     * 3.0 保留整个 3.x 周期，4.0 起移除。</p>
     */
    private static final String DEPRECATION_SUNSET = "Wed, 30 Jun 2027 00:00:00 GMT";

    /** 主机模拟路由段。 */
    private final SessionHostSimRoutes hostSimRoutes;

    SessionHandler(ApiContext context, AccessControl access, AuditSink audit) {
        super(context, access, audit);
        this.hostSimRoutes = new SessionHostSimRoutes(context, access, audit);
    }


    void handleSessions(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            respond(ex, 200, "{\"sessions\":" + this.context.sessionsJson() + "}");
            return;
        }

        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            this.createSession(ex);
            return;
        }

        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }


    /**
     * POST /api/v1/sessions：新建会话。
     *
     * <p>体是 {@code {"config":"demo"}} 或 {@code {"config":"设备.json"}}。
     * 配置只能给文件名：接口给的是<b>名字</b>，不是一条路径——
     * 否则一次 API 调用就能把服务器上任意 JSON 当配置读进来试。
     * </p>
     */
    void createSession(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }

        String body = readBody(ex);
        if (body == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }

        SessionCreateRequest request;
        try {
            request = sessionCreateRequestFrom(body);
        } catch (IllegalArgumentException e) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-request\",\"message\":" + json(e.getMessage()) + "}");
            return;
        }
        if (request == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"missing-config\"}");
            return;
        }

        SessionCreated created = this.context.createSession(request);
        audit(ex, "session-create", request.config(), "outcome=" + created.outcome()
                + " id=" + created.sessionId());
        switch (created.outcome()) {
            /* 会话 id 由服务端生成，必须回给调用方：配置名不再等于 id */
            case OK -> respond(ex, 201, "{\"ok\":true,\"id\":\"" + json(created.sessionId()) + "\"}");
            case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"session-exists\"}");
            default -> respond(ex, 400, "{\"ok\":false,\"error\":\"create-failed\"}");
        }
    }


    /**
     * 从请求体解析"新建会话"请求：config 必填，其余是可选的部署期覆盖。
     *
     * <p>覆盖字段（bindHost / port / deviceId）缺省就用配置原文里的——
     * 多台设备复用一份设备配置时只有这几项逐台不同。会话 id 不接受指定：
     * 服务端生成 {@code ses_} + ULID 并随 201 响应返回。</p>
     *
     * @return 请求；体不是对象或缺 config 时返回 {@code null}
     */
    static SessionCreateRequest sessionCreateRequestFrom(String body) {
        try {
            com.shimizukenta.jsonhub.JsonHub root =
                    com.shimizukenta.jsonhub.JsonHub.fromJson(body);
            if (!root.isObject()) {
                return null;
            }
            String config = optionalString(root, "config");
            if (config == null) {
                return null;
            }
            Integer port = null;
            if (root.containsKey("port")) {
                port = optionalInt(root, "port", n -> n > 0 && n <= 65535);
                if (port == null) {
                    /* 写了但非法：不能静默回落到配置端口，调用方会以为覆盖生效了 */
                    throw new IllegalArgumentException("port 必须是 1..65535 的整数");
                }
            }
            Integer deviceId = null;
            if (root.containsKey("deviceId")) {
                deviceId = optionalInt(root, "deviceId", n -> n >= 0 && n <= 32767);
                if (deviceId == null) {
                    throw new IllegalArgumentException("deviceId 必须是 0..32767 的整数");
                }
            }
            return new SessionCreateRequest(config,
                    optionalString(root, "bindHost"), port, deviceId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            return null;
        }
    }


    /**
     * POST /api/v1/sessions/bulk：从一份设备配置批量开多台设备。
     *
     * <p>体形如 {@code {"config":"tpl.json","count":5,
     * "portBase":5100,"deviceIdBase":100,"bindHost":"127.0.0.1"}}。
     * 第 i 台（从 1 起）的端口 {@code portBase+i-1}、device-id {@code deviceIdBase+i-1}；
     * 会话 id 由服务端生成（{@code ses_} + ULID），响应里逐台回真实 id。逐台独立创建：
     * 一台起不来不影响其余，全部成功回 201，部分失败回 207 并逐台给原因。</p>
     */
    void handleBulkSessions(HttpExchange ex) throws IOException {
        if (!ApiPaths.SESSIONS_BULK.equals(ex.getRequestURI().getPath())) {
            /* /api/v1/sessions/bulk-3 这种会话 id 按前缀撞进了本上下文，交回会话处理器 */
            this.handleSessionPath(ex);
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }

        String body = readBody(ex);
        if (body == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }

        com.shimizukenta.jsonhub.JsonHub root;
        try {
            root = com.shimizukenta.jsonhub.JsonHub.fromJson(body);
        } catch (RuntimeException e) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-json\"}");
            return;
        }
        if (!root.isObject()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-json\"}");
            return;
        }
        String config = optionalString(root, "config");
        int count = num(root, "count");
        int portBase = num(root, "portBase");
        int deviceIdBase = num(root, "deviceIdBase");
        String bindHost = optionalString(root, "bindHost");

        if (config == null || count < 1 || count > 100) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"count 必须在 1..100，且 config 必填\"}");
            return;
        }

        StringBuilder created = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        int okCount = 0;
        for (int i = 1; i <= count; i++) {
            Integer port = portBase > 0 ? portBase + i - 1 : null;
            Integer deviceId = deviceIdBase >= 0 ? deviceIdBase + i - 1 : null;
            SessionCreateRequest request = new SessionCreateRequest(config, bindHost, port, deviceId);
            SessionCreated result = this.context.createSession(request);
            audit(ex, "session-create", config + "#" + i, "outcome=" + result.outcome()
                    + " id=" + result.sessionId());
            if (result.outcome() == SessionOutcome.OK) {
                if (okCount > 0) {
                    created.append(',');
                }
                created.append("\"").append(json(result.sessionId())).append('"');
                okCount++;
            } else {
                if (failed.length() > 0) {
                    failed.append(',');
                }
                failed.append("{\"index\":").append(i)
                        .append(",\"error\":\"").append(result.outcome().name().toLowerCase()).append("\"}");
            }
        }

        /* 全部失败也不是 502：这是逐台创建的"多状态"结果，207 才能让客户端
           拿到逐台原因；502 会让人以为是网关/上游挂了。 */
        int status = okCount == count ? 201 : 207;
        String json = "{\"created\":[" + created + "],\"failed\":[" + failed + "]}";
        respond(ex, status, json);
    }


    /**
     * 会话子路径入口：GET 查询、POST 命令、DELETE 断链。
     *
     * <p>用 POST 是因为它们会改变设备状态（主机会看到一条 S6F11 / S5F1），
     * 不是幂等查询，因此最低角色是 {@link ApiRole#OPERATOR}。
     * </p>
     */
    void handleSessionPath(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            this.handleSessionQuery(ex);
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            this.handleSessionCommand(ex);
            return;
        }
        if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            /* DELETE /sessions/{id} 是关会话;/host-sim 是断开;其余子路径已随 v1 实体编辑下线 */
            String rest = ex.getRequestURI().getPath().substring(ApiPaths.SESSION_PREFIX.length());
            /* 末尾多一个斜杠不该改变语义：`DELETE /api/v1/sessions/s1/` 以前因为
               indexOf('/') >= 0 被当成"子资源"，回 404，而 `DELETE /sessions/s1` 是能断开的。 */
            if (rest.endsWith("/")) {
                rest = rest.substring(0, rest.length() - 1);
            }
            if (rest.indexOf('/') < 0 || rest.endsWith("/host-sim")) {
                this.closeSession(ex);
            } else {
                respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            }
            return;
        }

        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }


    /**
     * {@code DELETE /api/v1/sessions/{id}}：3.0 起语义是<b>断开链路</b>，不是移除会话。
     *
     * <p>设计 §2.5：这条 v1 端点保留一个版本做兼容别名，内部转成
     * {@code POST /api/v2/devices/{id}/link/disconnect}。行为上看得见的变化是
     * <b>设备不再从列表消失</b>——模型、钉值、计划启用态、目录编辑全部保留，
     * 只是端口释放、可随时再连（需求 1「关闭 = 断链不删实体」）。</p>
     *
     * <p>响应带 {@code Deprecation: true} 与 {@code Sunset}，客户端据此提示迁移；
     * body 里的 {@code hint} 直接给出替代端点，省得对着变更日志找。</p>
     */
    void closeSession(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/host-sim")) {
            String id = path.substring(ApiPaths.SESSION_PREFIX.length(), path.length() - "/host-sim".length());
            if (id.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-session-id\"}");
                return;
            }
            SessionOutcome outcome = this.context.closeHostSim(id);
            audit(ex, "host-sim-close", id, "outcome=" + outcome);
            respond(ex, outcome == SessionOutcome.OK ? 200 : 404,
                    outcome == SessionOutcome.OK ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"not-open\"}");
            return;
        }

        String id = sessionIdFromPath(ex);
        if (id == null || id.isBlank()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-session-id\"}");
            return;
        }

        SessionOutcome outcome = this.context.deviceDisconnectV2(id);
        audit(ex, "session-close", id, "outcome=" + outcome + " via=link/disconnect");
        ex.getResponseHeaders().set("Deprecation", "true");
        ex.getResponseHeaders().set("Sunset", DEPRECATION_SUNSET);
        switch (outcome) {
            case OK -> respond(ex, 200, "{\"ok\":true,\"phase\":\"DISCONNECTED\","
                    + "\"hint\":\"use POST /api/v2/devices/{id}/link/disconnect\"}");
            default -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
        }
    }


    /** 取路径里的会话标识；路径不合法或 id 为空白时返回 {@code null}。 */
    static String sessionIdFromPath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (path == null || !path.startsWith(ApiPaths.SESSION_PREFIX)) {
            return null;
        }
        String rest = path.substring(ApiPaths.SESSION_PREFIX.length());
        /* 末尾斜杠是路径噪音（`DELETE /sessions/s1/`），不是 id 的一部分——
           id 本身不允许含斜杠，先归一再校验，两个写法才会同语义 */
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        /* getPath() 已解码，不能再 decode：路径含 % 时会二次解码抛 IAE（500） */
        return rest.isBlank() ? null : rest;
    }


    /**
     * GET /api/v1/sessions/{id}/alarms、/terminal 或 /catalog。
     */
    void handleSessionQuery(HttpExchange ex) throws IOException {
        if (this.hostSimRoutes.handleQuery(ex)) {
            return;
        }

        String rest = ex.getRequestURI().getPath().substring(ApiPaths.SESSION_PREFIX.length());
        String[] parts = rest.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            /* 空 id（/sessions//...）不能当成某个会话去查，明确回 400 */
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-session-id\"}");
            return;
        }

        /* 压测进度/最近一次结果：轻量只读端点，走 GET 不占用压测执行器 */
        if (parts.length == 2 && "burst".equals(parts[1])) {
            String body = this.context.burst(parts[0], BURST_STATUS_REQUEST);
            if (body == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                return;
            }
            respond(ex, 200, body);
            return;
        }

        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            /* 命令路径用 GET 访问时仍按 405 处理，而不是误报 400 */
            if (parts.length == 3 && ("ceid".equals(parts[1]) || "alarm".equals(parts[1]))) {
                respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
                return;
            }
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        if ("message".equals(parts[1])) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String body = switch (parts[1]) {
            case "alarms" -> this.context.alarmsJson(parts[0]);
            case "terminal" -> this.context.terminalMessagesJson(parts[0]);
            case "catalog" -> this.context.catalogJson(parts[0]);
            case "events" -> this.context.eventsJson(parts[0]);
            default -> null;
        };

        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }
        respond(ex, 200, body);
    }


    void handleSessionCommand(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(ApiPaths.SESSION_PREFIX.length());
        String[] parts = rest.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-session-id\"}");
            return;
        }

        if (parts.length == 2 && "reload".equals(parts[1])) {
            SessionOutcome outcome = this.context.reloadSession(parts[0]);
            audit(ex, "session-reload", parts[0], "outcome=" + outcome);
            switch (outcome) {
                case OK -> respond(ex, 200, "{\"ok\":true}");
                case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                /* 演示会话没有配置来源，说"不可重载"比笼统的 500 有用得多 */
                case NOT_RELOADABLE -> respond(ex, 409, "{\"ok\":false,\"error\":\"not-reloadable\"}");
                default -> respond(ex, 400, "{\"ok\":false,\"error\":\"reload-failed\"}");
            }
            return;
        }

        /* 主机模拟器：打开连接 / 以主机角色发报文 / 采集器配置与启停 */
        if (parts.length >= 2 && "host-sim".equals(parts[1])
                && this.hostSimRoutes.handleCommand(ex, parts)) {
            return;
        }

        /* v6:服务端压测 */
        if (parts.length == 2 && "burst".equals(parts[1])) {
            String body = readBody(ex);
            if (body == null || body.isBlank()) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            String result = this.context.burst(parts[0], body);
            if (result == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                return;
            }
            audit(ex, "burst", parts[0], "accepted=" + burstFlag(result));
            /* 压测已改成后台任务：受理回 202，统计用 GET /sessions/{id}/burst 取。
               参数错误仍按 400；执行器/该会话已有任务在跑按 409（可重试）。
               状态码由解析字段决定，不用 contains 猜——错误消息里出现同名子串
               （比如回显了 "burst-busy"）就会把 400 报成 409。 */
            respond(ex, burstStatus(result), result);
            return;
        }

        if (parts.length == 2 && "message".equals(parts[1])) {
            this.sendSml(ex, parts[0]);
            return;
        }

        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        TriggerOutcome outcome;

        if ("ceid".equals(parts[1])) {
            int ceid;
            try {
                ceid = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-ceid\"}");
                return;
            }
            if (ceid < 1) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-ceid\"}");
                return;
            }
            outcome = this.context.triggerCeid(parts[0], ceid);
            audit(ex, "trigger-ceid", parts[0], "ceid=" + ceid + " outcome=" + outcome);
        } else if ("alarm".equals(parts[1])) {
            int alid;
            try {
                alid = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-alid\"}");
                return;
            }
            if (alid < 1) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-alid\"}");
                return;
            }

            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            boolean set = !"false".equalsIgnoreCase(query.getOrDefault("set", "true"));
            String text = query.getOrDefault("text", "");
            /* SECS-II 的 A 是 US-ASCII：在这里拒绝，别等异步发送时才在日志里失败 */
            if (!isAscii(text)) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-text\"}");
                return;
            }

            int category;
            try {
                category = Integer.parseInt(query.getOrDefault("category", "1"));
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-category\"}");
                return;
            }
            if (category < 1 || category > 7) {
                /* E30：ALCD 低 3 位是类别，0 无定义。放行 0 会在 AlarmReporter
                   入口抛 IOException，最终变成 500——在入口就按 400 拒绝。 */
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-category\"}");
                return;
            }

            outcome = this.context.triggerAlarm(parts[0], alid, set, text, category);
            audit(ex, "alarm", parts[0], "alid=" + alid + " set=" + set
                    + " category=" + category + " outcome=" + outcome);
        } else {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        switch (outcome) {
            case OK -> respond(ex, 202, "{\"ok\":true}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            case NOT_OPEN -> respond(ex, 409, "{\"ok\":false,\"error\":\"session-not-open\"}");
            /* 与其他发送路径对齐：链路级失败是 502，不是 500 */
            case FAILED -> respond(ex, 502, "{\"ok\":false,\"error\":\"send-failed\"}");
        }
    }


    /**
     * POST /api/v1/sessions/{id}/message：手动发送 SML。
     *
     * <p>请求体就是 SML 原文，不包 JSON——手写报文时多套一层转义只会更容易错。
     * 解析失败 400，没有对端 409，协议层发送失败 500。</p>
     */
    void sendSml(HttpExchange ex, String sessionId) throws IOException {
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
            String body = this.context.sendSml(sessionId, sml);
            if (body == null) {
                audit(ex, "send-sml", sessionId, "outcome=unknown-session bytes=" + sml.length());
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                return;
            }
            audit(ex, "send-sml", sessionId, "outcome=ok bytes=" + sml.length());
            respond(ex, 200, body);
        } catch (IllegalArgumentException e) {
            audit(ex, "send-sml", sessionId, "outcome=bad-sml bytes=" + sml.length());
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-sml\",\"message\":\""
                    + json(e.getMessage()) + "\"}");
        } catch (IllegalStateException e) {
            audit(ex, "send-sml", sessionId, "outcome=not-open bytes=" + sml.length());
            respond(ex, 409, "{\"ok\":false,\"error\":\"session-not-open\"}");
        } catch (IOException e) {
            audit(ex, "send-sml", sessionId, "outcome=failed bytes=" + sml.length());
            LOG.error("手动发送 SML 失败（会话 {}，SML: {}）", sessionId, head(sml), e);
            /* 与 v2 手动发送（POST /devices/{id}/runtime/message）统一：502 */
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

    /** 压测响应里的 accepted 标志（解析失败按 false 处理）。 */
    private static boolean burstFlag(String result) {
        try {
            JsonHub root = JsonHub.fromJson(result);
            JsonHub v = root.get("accepted");
            return v != null && v.optionalBoolean().orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 压测响应的状态码：受理 202；忙 409；ok=false 的其余情况 400；其他 200。
     *
     * <p>用字段而不是 {@code contains}：错误消息里出现 "burst-busy" 之类的
     * 字样时，子串判断会把 400 误报成 409。</p>
     */
    private static int burstStatus(String result) {
        JsonHub root;
        try {
            root = JsonHub.fromJson(result);
        } catch (RuntimeException e) {
            return 500;
        }
        JsonHub accepted = root.get("accepted");
        if (accepted != null && accepted.optionalBoolean().orElse(false)) {
            return 202;
        }
        JsonHub ok = root.get("ok");
        if (ok != null && !ok.optionalBoolean().orElse(true)) {
            JsonHub error = root.get("error");
            String code = error == null ? "" : error.optionalString().orElse("");
            return "burst-busy".equals(code) || "burst-running".equals(code) ? 409 : 400;
        }
        return 200;
    }


    /** SECS-II 的 A 类型只允许 US-ASCII（ALTX 含中文时编码会失败）。 */
    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
