package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.log.Log;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * 极简 HTTP/HTTPS API：健康检查、会话、曲线预览、SSE 报文流、静态前端、优雅停机。
 *
 * <p>只依赖 JDK 自带 {@code com.sun.net.httpserver}，不引入 Web 框架：
 * 这个后端的 API 面积很小，而框架会把原生镜像从 13MB 撑到 100MB 以上。
 * </p>
 *
 * <p>鉴权采用每次启动生成的一次性 token（{@code Authorization: Bearer}）。
 * URL 里不接受长期 token：浏览器的 {@code EventSource} 无法设置请求头，
 * 因此改由 {@code POST /api/v1/ticket} 换取 30 秒、只能使用一次的票据。
 * </p>
 *
 * <p><b>线程模型</b>：请求处理器与 SSE 长连接分属两个线程池。SSE 连接会长时间占住线程，
 * 如果与普通请求共用固定大小的池，几个标签页就能把 health/sessions 全部饿死。
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

    /** 一次性 SSE 票据路径（POST，需 Bearer）。 */
    public static final String PATH_TICKET = "/api/v1/ticket";

    /** 票据有效期：够浏览器建连即可，短到即使泄漏也基本无用。 */
    private static final Duration TICKET_TTL = Duration.ofSeconds(30L);

    /** 心跳间隔：15 秒足够穿过常见代理的空闲超时，又不至于把界面刷爆。 */
    private static final Duration SSE_HEARTBEAT = Duration.ofSeconds(15L);

    /** 单个 SSE 连接的最长寿命；到点主动断开，浏览器会自动重连。 */
    private static final Duration SSE_MAX_LIFETIME = Duration.ofMinutes(10L);

    /**
     * 写入停滞判定：连续这么久没有任何一帧写成功，就认为客户端已经不读了。
     *
     * <p>正常连接每 15 秒至少有一次心跳，所以 60 秒无进展必然异常。
     * 测试可用 {@code -Dsecssim.sse.stallTimeoutMs=...} 调小。
     * </p>
     */
    private static final long SSE_STALL_TIMEOUT_DEFAULT_MS = 60_000L;

    /** 看门狗巡检间隔。 */
    private static final long SSE_WATCHDOG_TICK_MS = 5_000L;

    /** 每个 SSE 订阅者的待发帧上限；满了丢最旧的一帧。 */
    private static final int SSE_QUEUE_FRAMES = 256;

    /** 普通请求线程数：所有处理器都是短任务（SSE 已移走），4 个足够。 */
    private static final int REQUEST_THREADS = 4;

    /** SSE 线程数上限：每个连接一根线程，超出直接 503，而不是无限堆积。 */
    private static final int SSE_THREADS_MAX = 64;

    /** 预览桶数上下限，与运行时实现保持一致。 */
    private static final int PREVIEW_BUCKETS_MIN = 1;
    private static final int PREVIEW_BUCKETS_MAX = 500;

    /** 票据随机源。 */
    private static final SecureRandom TICKET_RANDOM = new SecureRandom();

    private final HttpServer server;
    private final ExecutorService executor;
    private final ExecutorService sseExecutor;
    private final ScheduledExecutorService watchdog;
    private final String token;
    private final ApiContext context;
    private final Path webRoot;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    /** 一次性票据：ticket -> 过期时刻（毫秒）。 */
    private final ConcurrentHashMap<String, Long> tickets = new ConcurrentHashMap<>();
    private final long stallTimeoutMs;
    private final Instant startedAt = Instant.now();

    private AutoCloseable eventSubscription;

    private HttpApiServer(HttpServer server, ExecutorService executor, ExecutorService sseExecutor,
                          ScheduledExecutorService watchdog, String token, ApiContext context, Path webRoot,
                          long stallTimeoutMs) {
        this.server = server;
        this.executor = executor;
        this.sseExecutor = sseExecutor;
        this.watchdog = watchdog;
        this.token = token;
        this.context = context;
        this.webRoot = webRoot;
        this.stallTimeoutMs = stallTimeoutMs;
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
        return start(bind, token, context, webRoot, bus, null);
    }

    /**
     * 启动服务器，可选用 TLS。
     *
     * @param bind       绑定地址
     * @param token      访问令牌
     * @param context    运行时信息
     * @param webRoot    静态资源根目录，{@code null} 表示不托管
     * @param bus        事件总线，{@code null} 表示不推报文
     * @param sslContext TLS 上下文；{@code null} 表示明文 HTTP
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, String token, ApiContext context,
                                      Path webRoot, EventBus bus, SSLContext sslContext) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }

        HttpServer http;
        if (sslContext == null) {
            http = HttpServer.create(bind, 64);
        } else {
            HttpsServer https = HttpsServer.create(bind, 64);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            http = https;
        }

        AtomicInteger reqSeq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(REQUEST_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "http-" + reqSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        AtomicInteger sseSeq = new AtomicInteger();
        /* 0 核心线程 + 同步队列 = 来一个连接开一根线程，空闲 60 秒回收；上限之外的连接直接 503 */
        ExecutorService ssePool = new ThreadPoolExecutor(0, SSE_THREADS_MAX, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "sse-" + sseSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });

        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-watchdog");
            thread.setDaemon(true);
            return thread;
        });

        HttpApiServer api = new HttpApiServer(http, pool, ssePool, watchdog, token, context,
                normalizeRoot(webRoot),
                Long.getLong("secssim.sse.stallTimeoutMs", SSE_STALL_TIMEOUT_DEFAULT_MS));
        http.setExecutor(pool);

        if (bus != null) {
            api.eventSubscription = bus.subscribe(api::broadcast);
        }

        http.createContext(PATH_HEALTH, api::handleHealth);
        http.createContext(PATH_EVENTS, api::handleEvents);
        http.createContext(PATH_TICKET, api::handleTicket);
        http.createContext(PATH_SHUTDOWN, api::handleShutdown);
        http.createContext(PATH_SESSIONS, api::handleSessions);
        http.createContext(PATH_SESSION_PREFIX, api::handleSessionCommand);
        http.createContext(PATH_PREVIEW, api::handlePreview);

        if (api.webRoot != null) {
            /* 根上下文兜底；HttpServer 按最长前缀匹配，因此不会抢走 /api/v1/* */
            http.createContext("/", api::handleStatic);
        }

        watchdog.scheduleWithFixedDelay(api::sweepStalledStreams,
                SSE_WATCHDOG_TICK_MS, SSE_WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS);

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
     * 当前活跃的 SSE 连接数（测试与诊断用）。
     *
     * @return 连接数
     */
    public int activeStreams() {
        return this.subscribers.size();
    }

    /**
     * 把一条报文事件推给所有 SSE 订阅者。
     *
     * <p>订阅者队列满了就丢最旧的一帧：界面宁可缺几条，也不能把内存拖爆或阻塞会话。
     * </p>
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

        for (Subscriber subscriber : this.subscribers) {
            subscriber.enqueue(frame);
        }
    }

    @Override
    public void close() {
        AutoCloseable subscription = this.eventSubscription;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                Log.error("取消事件订阅失败（已忽略）", e);
            }
            this.eventSubscription = null;
        }

        this.server.stop(0);
        this.watchdog.shutdownNow();
        this.sseExecutor.shutdownNow();
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

        /*
         * 免鉴权只回答"活着吗"：探针和启动脚本只需要这个。
         * 版本、形态、会话数、HSMS 端口要带 token 才给，避免白名单外的扫描者拿到部署信息。
         */
        if (!this.authorized(ex)) {
            respond(ex, 200, "{\"ok\":true,\"uptimeMs\":" + uptime + "}");
            return;
        }

        respond(ex, 200, "{\"ok\":true"
                + ",\"version\":\"" + json(this.context.version()) + "\""
                + ",\"mode\":\"" + json(this.context.mode()) + "\""
                + ",\"uptimeMs\":" + uptime
                + this.context.extraHealthJson()
                + "}");
    }

    /**
     * 签发一次性 SSE 票据。
     *
     * <p>{@code EventSource} 不能设置请求头，只能把凭据放进 URL。直接放 token 会进
     * 访问日志和浏览器历史，而且长期有效；改成 30 秒、只能用一次的票据，
     * 即使被记下来也基本无法利用。
     * </p>
     */
    private void handleTicket(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        long now = System.currentTimeMillis();
        this.tickets.entrySet().removeIf(entry -> entry.getValue() < now);

        String ticket = newTicket();
        this.tickets.put(ticket, now + TICKET_TTL.toMillis());

        respond(ex, 200, "{\"ok\":true,\"ticket\":\"" + ticket + "\""
                + ",\"expiresInMs\":" + TICKET_TTL.toMillis() + "}");
    }

    /** 生成票据：32 字节随机数的十六进制。 */
    private static String newTicket() {
        byte[] bytes = new byte[32];
        TICKET_RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** 消费票据：原子移除 + 过期校验，保证只能用一次。 */
    private boolean consumeTicket(String ticket) {
        if (ticket == null || ticket.isEmpty()) {
            return false;
        }
        Long expiry = this.tickets.remove(ticket);
        return expiry != null && expiry >= System.currentTimeMillis();
    }

    /** 看门狗：把长时间写不动的连接踢掉，避免占着线程和队列。 */
    private void sweepStalledStreams() {
        long now = System.currentTimeMillis();

        for (Subscriber subscriber : this.subscribers) {
            if (!subscriber.isStalled(now, this.stallTimeoutMs)) {
                continue;
            }
            if (this.subscribers.remove(subscriber)) {
                Log.error("SSE 连接 " + this.stallTimeoutMs / 1000 + " 秒无写入进展，主动断开", null);
                subscriber.abort();
            }
        }
    }

    private void handleShutdown(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        respond(ex, 200, "{\"ok\":true,\"shuttingDown\":true}");
        this.context.requestShutdown();
    }

    /**
     * 静态资源：托管前端目录。
     *
     * <p>找不到文件时回落到 {@code index.html}（给 SPA 用），但只对"没有扩展名"的路径回落：
     * 打错的 {@code /app.js} 应该老实返回 404，而不是拿一页 HTML 冒充脚本。
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
            if (hasExtension(path)) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
                return;
            }
            target = this.webRoot.resolve("index.html");
        }
        if (!Files.isRegularFile(target)) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }

        FileTime modified = Files.getLastModifiedTime(target);
        long size = Files.size(target);
        String etag = "\"" + size + "-" + modified.toMillis() + "\"";

        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            ex.getResponseHeaders().add("ETag", etag);
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
        }

        byte[] body = Files.readAllBytes(target);
        ex.getResponseHeaders().add("Content-Type", contentType(target));
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("ETag", etag);
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** 最后一段是否带扩展名（{@code /a/b.js} 是，{@code /a/b} 不是）。 */
    private static boolean hasExtension(String path) {
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return last.lastIndexOf('.') > 0;
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
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";

        return switch (ext) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "ico" -> "image/x-icon";
            case "webp" -> "image/webp";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "txt" -> "text/plain; charset=utf-8";
            case "wasm" -> "application/wasm";
            default -> "application/octet-stream";
        };
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
            outcome = this.context.triggerCeid(parts[0], ceid);
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

            int category;
            try {
                category = Integer.parseInt(query.getOrDefault("category", "1"));
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-category\"}");
                return;
            }
            if (category < 0 || category > 7) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-category\"}");
                return;
            }

            outcome = this.context.triggerAlarm(parts[0], alid, set, text, category);
        } else {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        switch (outcome) {
            case OK -> respond(ex, 202, "{\"ok\":true}");
            case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            case NOT_OPEN -> respond(ex, 409, "{\"ok\":false,\"error\":\"session-not-open\"}");
            case FAILED -> respond(ex, 500, "{\"ok\":false,\"error\":\"send-failed\"}");
        }
    }

    /**
     * 曲线预览：{@code /api/v1/preview?session=demo&from=0&to=60000&buckets=120}。
     *
     * <p>参数非法一律 400：静默回落默认值会让调用方以为自己的参数生效了。
     * </p>
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

        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        Long fromMs = longParam(query, "from");
        Long toMs = longParam(query, "to");
        Long buckets = longParam(query, "buckets");

        /* 缺省用默认值；写了但不是数字才报错 */
        if ((query.containsKey("from") && fromMs == null)
                || (query.containsKey("to") && toMs == null)
                || (query.containsKey("buckets") && buckets == null)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-number\"}");
            return;
        }

        long from = fromMs == null ? 0L : fromMs;
        long to = toMs == null ? 60_000L : toMs;
        long count = buckets == null ? 120L : buckets;

        if (to <= from) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-range\"}");
            return;
        }
        if (count < PREVIEW_BUCKETS_MIN || count > PREVIEW_BUCKETS_MAX) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-buckets\"}");
            return;
        }

        String sessionId = query.getOrDefault("session", "");
        if (sessionId.isEmpty()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"missing-session\"}");
            return;
        }

        String body = this.context.previewJson(sessionId, from, to, (int) count);
        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            return;
        }

        respond(ex, 200, body);
    }

    /**
     * 解析查询串，值做 URL 解码。
     *
     * <p>用 {@link LinkedHashMap} 保留首次出现的键；重复键忽略后者。
     * </p>
     */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return values;
        }

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = decode(pair.substring(0, eq));
            values.putIfAbsent(key, decode(pair.substring(eq + 1)));
        }
        return values;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            /* 非法百分号编码按原样使用，交给后续校验解释 */
            return value;
        }
    }

    /** 解析 long 参数；缺省或非法都返回 {@code null}，由调用方决定 400/默认值。 */
    private static Long longParam(Map<String, String> query, String key) {
        String raw = query.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * SSE 入口：用一次性票据鉴权（{@code EventSource} 无法带请求头），
     * 然后把长连接交给 SSE 专用池。
     */
    private void handleEvents(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String ticket = parseQuery(ex.getRequestURI().getRawQuery()).get("ticket");
        if (!this.consumeTicket(ticket)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }

        try {
            this.sseExecutor.execute(() -> this.streamEvents(ex));
        } catch (RejectedExecutionException e) {
            Log.error("SSE 连接数已达上限 " + SSE_THREADS_MAX + "，拒绝新连接", null);
            respond(ex, 503, "{\"ok\":false,\"error\":\"too-many-streams\"}");
        }
    }

    private void streamEvents(HttpExchange ex) {
        OutputStream out;
        try {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.getResponseHeaders().add("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);

            /*
             * 头发出去不等于客户端能收到：com.sun.net.httpserver 会缓冲，
             * 不主动 flush 的话要等到第一帧（心跳/报文）才会真正上线。
             * 先发一个 SSE 注释帧把通道打开。
             */
            out = ex.getResponseBody();
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            ex.close();
            return;
        }

        Instant deadline = Instant.now().plus(SSE_MAX_LIFETIME);
        Subscriber subscriber = new Subscriber(ex);
        this.subscribers.add(subscriber);

        try (out) {
            while (Instant.now().isBefore(deadline) && !subscriber.isClosed()) {
                String frame;
                try {
                    frame = subscriber.poll(SSE_HEARTBEAT.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (frame == null) {
                    frame = "event: heartbeat\ndata: " + System.currentTimeMillis() + "\n\n";
                }

                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                subscriber.touch();
            }
        } catch (IOException clientGone) {
            /* 客户端断开是常态，静默退出 */
        } finally {
            subscriber.markClosed();
            this.subscribers.remove(subscriber);
        }
    }

    /**
     * 普通端点鉴权：只认 {@code Authorization: Bearer}。
     *
     * <p>不再接受 {@code ?token=}：URL 里的长期凭据会进访问日志、浏览器历史和 Referer。
     * SSE 走一次性 ticket（见 {@link #handleTicket}）。
     * </p>
     */
    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }

        String presented = header.substring(7).trim();
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

    /**
     * 一个 SSE 订阅者。
     *
     * <p>"满了丢最旧"是"poll 再 offer"两步，必须与写端互斥：否则两个发布线程
     * 交错执行会各丢一帧，白白多丢数据。
     * </p>
     *
     * <p>同时记录"最后一次写成功"的时间：客户端不读时 TCP 会卡住写，看门狗据此断开。
     * </p>
     */
    private static final class Subscriber {

        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(SSE_QUEUE_FRAMES);
        private final HttpExchange exchange;
        private volatile long lastProgressAt = System.currentTimeMillis();
        private volatile boolean closed;

        Subscriber(HttpExchange exchange) {
            this.exchange = exchange;
        }

        synchronized void enqueue(String frame) {
            if (this.closed) {
                return;
            }
            if (!this.queue.offer(frame)) {
                this.queue.poll();
                this.queue.offer(frame);
            }
        }

        String poll(long timeoutMs) throws InterruptedException {
            return this.queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void touch() {
            this.lastProgressAt = System.currentTimeMillis();
        }

        boolean isStalled(long now, long timeoutMs) {
            return !this.closed && now - this.lastProgressAt > timeoutMs;
        }

        boolean isClosed() {
            return this.closed;
        }

        void markClosed() {
            this.closed = true;
        }

        /**
         * 强制断开。
         *
         * <p>{@code exchange.close()} 在写线程被 TCP 卡住时可能也会阻塞，
         * 所以放到独立线程里做，不能拖住看门狗。
         * </p>
         */
        void abort() {
            if (this.closed) {
                return;
            }
            this.closed = true;

            Thread closer = new Thread(() -> {
                try {
                    this.exchange.close();
                } catch (RuntimeException ignored) {
                    /* 关闭失败不影响清理 */
                }
            }, "sse-abort");
            closer.setDaemon(true);
            closer.start();
        }
    }

    /** JSON 字符串转义；控制字符一律转成 \\u00XX，避免拼出非法帧。 */
    private static String json(String value) {
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
