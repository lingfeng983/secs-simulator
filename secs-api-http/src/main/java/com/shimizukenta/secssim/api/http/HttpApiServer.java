package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 极简 HTTP API：健康检查、SSE 心跳、优雅停机。
 *
 * <p>只依赖 JDK 自带 {@code com.sun.net.httpserver}，不引入 Web 框架：
 * 这个后端的 API 面积很小，而框架会把原生镜像从 13MB 撑到 100MB 以上。
 * </p>
 *
 * <p>鉴权采用每次启动生成的一次性 token（{@code Authorization: Bearer}），
 * 并额外接受查询参数 {@code ?token=}，因为浏览器的 {@code EventSource} 无法设置请求头。
 * </p>
 */
public final class HttpApiServer implements AutoCloseable {

    /** 健康检查路径（不需要 token）。 */
    public static final String PATH_HEALTH = "/api/v1/health";

    /** SSE 事件流路径。 */
    public static final String PATH_EVENTS = "/api/v1/events";

    /** 停机路径。 */
    public static final String PATH_SHUTDOWN = "/api/v1/shutdown";

    /** 会话列表路径。 */
    public static final String PATH_SESSIONS = "/api/v1/sessions";

    /** 会话命令前缀（{@code /api/v1/sessions/{id}/ceid/{ceid}}）。 */
    public static final String PATH_SESSION_PREFIX = "/api/v1/sessions/";

    /** 曲线预览路径。 */
    public static final String PATH_PREVIEW = "/api/v1/preview";

    private static final Duration SSE_HEARTBEAT = Duration.ofSeconds(1L);
    private static final Duration SSE_MAX_LIFETIME = Duration.ofMinutes(30L);

    private final HttpServer server;
    private final ExecutorService executor;
    private final String token;
    private final ApiContext context;
    private final Path webRoot;
    private final CopyOnWriteArrayList<BlockingQueue<String>> subscribers = new CopyOnWriteArrayList<>();
    private final Instant startedAt = Instant.now();

    private AutoCloseable eventSubscription;

    private HttpApiServer(HttpServer server, ExecutorService executor, String token, ApiContext context,
                          Path webRoot) {
        this.server = server;
        this.executor = executor;
        this.token = token;
        this.context = context;
        this.webRoot = webRoot;
    }

    /**
     * 启动服务器。
     *
     * @param bind    绑定地址，端口可传 0 由系统分配
     * @param token   访问令牌，不得为空
     * @param context 运行时信息
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, String token, ApiContext context) throws IOException {
        return start(bind, token, context, null);
    }

    /**
     * 启动服务器，并可选地托管一个静态前端目录。
     *
     * <p>同时托管前端与 API 是刻意的：两者同源，浏览器端不需要任何 CORS 配置，
     * 也不需要 Tauri / Electron 之类的壳就能先把界面跑起来。
     * </p>
     *
     * @param bind    绑定地址
     * @param token   访问令牌
     * @param context 运行时信息
     * @param webRoot 静态资源根目录；{@code null} 表示不托管
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, String token, ApiContext context,
                                      Path webRoot) throws IOException {
        return start(bind, token, context, webRoot, null);
    }

    /**
     * 启动服务器，并把报文事件推给 SSE 订阅者。
     *
     * @param bind    绑定地址
     * @param token   访问令牌
     * @param context 运行时信息
     * @param webRoot 静态资源根目录，{@code null} 表示不托管
     * @param bus     事件总线，{@code null} 表示不推报文
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, String token, ApiContext context,
                                      Path webRoot, EventBus bus) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }

        HttpServer http = HttpServer.create(bind, 64);
        AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "http-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        HttpApiServer api = new HttpApiServer(http, pool, token, context, normalizeRoot(webRoot));
        http.setExecutor(pool);

        if (bus != null) {
            api.eventSubscription = bus.subscribe(api::broadcast);
        }

        http.createContext(PATH_HEALTH, api::handleHealth);
        http.createContext(PATH_EVENTS, api::handleEvents);
        http.createContext(PATH_SHUTDOWN, api::handleShutdown);
        http.createContext(PATH_SESSIONS, api::handleSessions);
        http.createContext(PATH_SESSION_PREFIX, api::handleSessionCommand);
        http.createContext(PATH_PREVIEW, api::handlePreview);

        if (api.webRoot != null) {
            /* 根上下文兜底；HttpServer 按最长前缀匹配，因此不会抢走 /api/v1/* */
            http.createContext("/", api::handleStatic);
        }

        http.start();
        return api;
    }

    /**
     * 实际监听端口（端口传 0 时用于回读）。
     *
     * @return 端口
     */
    public int port() {
        return this.server.getAddress().getPort();
    }

    /**
     * 把一条报文事件推给所有 SSE 订阅者。
     *
     * <p>订阅者队列满了就丢最旧的一帧：界面宁可缺几条，也不能把内存拖爆或阻塞会话。</p>
     */
    private void broadcast(MessageEvent event) {
        if (this.subscribers.isEmpty()) {
            return;
        }

        String frame = "event: message\ndata: "
                + "{\"sessionId\":\"" + json(event.sessionId()) + "\""
                + ",\"direction\":\"" + json(event.direction()) + "\""
                + ",\"stream\":" + event.stream()
                + ",\"function\":" + event.function()
                + ",\"wbit\":" + event.wbit()
                + ",\"body\":\"" + json(event.body()) + "\""
                + ",\"at\":" + event.atMillis()
                + "}\n\n";

        for (BlockingQueue<String> queue : this.subscribers) {
            if (!queue.offer(frame)) {
                queue.poll();
                queue.offer(frame);
            }
        }
    }

    @Override
    public void close() {
        AutoCloseable subscription = this.eventSubscription;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignore) {
                /* 关闭阶段的异常无需处理 */
            }
            this.eventSubscription = null;
        }

        this.server.stop(0);
        this.executor.shutdownNow();
        try {
            this.executor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        long uptime = Duration.between(this.startedAt, Instant.now()).toMillis();
        respond(ex, 200, "{\"ok\":true"
                + ",\"version\":\"" + json(this.context.version()) + "\""
                + ",\"mode\":\"" + json(this.context.mode()) + "\""
                + ",\"uptimeMs\":" + uptime
                + this.context.extraHealthJson()
                + "}");
    }

    private void handleShutdown(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        respond(ex, 200, "{\"ok\":true,\"shuttingDown\":true}");
        this.context.requestShutdown();
    }

    /**
     * 静态资源：托管前端目录。
     *
     * <p>找不到文件时回落到 {@code index.html}（给将来的 SPA 用）；
     * 路径先 {@code normalize} 再校验前缀，挡住 {@code ../} 穿越。
     * </p>
     */
    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String path = ex.getRequestURI().getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/index.html";
        }

        Path target = this.webRoot.resolve(path.substring(1)).normalize();
        if (!target.startsWith(this.webRoot)) {
            respond(ex, 403, "{\"ok\":false,\"error\":\"forbidden\"}");
            return;
        }

        if (!Files.isRegularFile(target)) {
            target = this.webRoot.resolve("index.html");
        }
        if (!Files.isRegularFile(target)) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }

        byte[] body = Files.readAllBytes(target);
        ex.getResponseHeaders().add("Content-Type", contentType(target));
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static Path normalizeRoot(Path webRoot) throws IOException {
        if (webRoot == null) {
            return null;
        }

        Path root = webRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("web root is not a directory: " + root);
        }
        return root;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);

        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        respond(ex, 200, "{\"sessions\":" + this.context.sessionsJson() + "}");
    }

    /**
     * 会话命令：目前只有"手动触发一个采集事件"。
     *
     * <p>用 POST 是因为它会改变设备状态（主机会看到一条 S6F11），不是幂等查询。
     * </p>
     */
    private void handleSessionCommand(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String rest = ex.getRequestURI().getPath().substring(PATH_SESSION_PREFIX.length());
        String[] parts = rest.split("/");

        if (parts.length != 3 || !"ceid".equals(parts[1])) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        int ceid;
        try {
            ceid = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-ceid\"}");
            return;
        }

        TriggerOutcome outcome = this.context.triggerCeid(parts[0], ceid);

        switch (outcome) {
            case OK -> respond(ex, 202, "{\"ok\":true}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            case NOT_OPEN -> respond(ex, 409, "{\"ok\":false,\"error\":\"session-not-open\"}");
            case FAILED -> respond(ex, 500, "{\"ok\":false,\"error\":\"send-failed\"}");
        }
    }

    /**
     * 曲线预览：{@code /api/v1/preview?session=demo&from=0&to=60000&buckets=120}
     */
    private void handlePreview(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        java.util.Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
        String sessionId = query.getOrDefault("session", "");
        long fromMs = longParam(query, "from", 0L);
        long toMs = longParam(query, "to", 60_000L);
        int buckets = (int) longParam(query, "buckets", 120L);

        if (toMs <= fromMs) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-range\"}");
            return;
        }

        String body = this.context.previewJson(sessionId, fromMs, toMs, buckets);
        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            return;
        }

        respond(ex, 200, body);
    }

    private static java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        if (query == null || query.isEmpty()) {
            return values;
        }

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(pair.substring(0, eq),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static long longParam(java.util.Map<String, String> query, String key, long fallback) {
        try {
            return query.containsKey(key) ? Long.parseLong(query.get(key)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void handleEvents(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }

        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);

        Instant deadline = Instant.now().plus(SSE_MAX_LIFETIME);
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(256);
        this.subscribers.add(queue);

        try (OutputStream out = ex.getResponseBody()) {
            while (Instant.now().isBefore(deadline)) {
                String frame;
                try {
                    frame = queue.poll(SSE_HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (frame == null) {
                    frame = "event: heartbeat\ndata: " + System.currentTimeMillis() + "\n\n";
                }

                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException clientGone) {
            /* 客户端断开是常态，静默退出 */
        } finally {
            this.subscribers.remove(queue);
        }
    }

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String presented = null;

        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = header.substring(7).trim();
        } else {
            String query = ex.getRequestURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                        presented = pair.substring(eq + 1);
                        break;
                    }
                }
            }
        }

        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                this.token.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }
}
