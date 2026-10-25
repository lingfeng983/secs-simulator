package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * 极简 HTTP/HTTPS API：健康检查、会话、配置、曲线预览、SSE 报文流、静态前端、优雅停机。
 *
 * <p>只依赖 JDK 自带 {@code com.sun.net.httpserver}，不引入 Web 框架：
 * 这个后端的 API 面积很小，而框架会把原生镜像从 13MB 撑到 100MB 以上。
 * </p>
 *
 * <p>鉴权采用 {@code Authorization: Bearer}，凭据由 {@link AccessControl} 判定。
 * 默认是单 token 模式（等价于一个 {@link ApiRole#ADMIN} 账号）；
 * 传账号文件则按 {@link ApiRole} 分级：只读 / 可下发 / 可停机。
 * </p>
 *
 * <p>URL 里不接受长期 token：浏览器的 {@code EventSource} 无法设置请求头，
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

    /** 批量新建会话路径（POST /api/v1/sessions/bulk）。 */
    public static final String PATH_SESSIONS_BULK = "/api/v1/sessions/bulk";

    /** 曲线预览路径。 */
    public static final String PATH_PREVIEW = "/api/v1/preview";

    /** 工作台聚合（schema v6）。 */
    public static final String PATH_OVERVIEW = "/api/v1/overview";

    /** 曲线预览的二进制路径。 */
    public static final String PATH_PREVIEW_BINARY = "/api/v1/preview.bin";

    /** 报文历史路径。 */
    public static final String PATH_MESSAGES = "/api/v1/messages";

    /** 审计历史路径。 */
    public static final String PATH_AUDIT = "/api/v1/audit";

    /** 日志级别路径（GET 读取 / PUT 切换）。 */
    public static final String PATH_LOG_LEVEL = "/api/v1/log-level";

    /** 配置目录列表路径。 */
    public static final String PATH_CONFIGS = "/api/v1/configs";

    /** 单份配置路径前缀（{@code /api/v1/configs/{name}}）。 */
    public static final String PATH_CONFIG_PREFIX = "/api/v1/configs/";

    /** 内置设备模板目录路径。 */
    public static final String PATH_CONFIG_TEMPLATES = "/api/v1/config-templates";

    /** 只校验不落盘的子路径。 */
    private static final String PATH_VALIDATE_SUFFIX = "validate";

    /** 配置草稿曲线预览路径（POST，body 为配置文本）。 */
    public static final String PATH_CONFIG_PREVIEW = "/api/v1/config-preview";

    /** 一次性 SSE 票据路径（POST，需 Bearer）。 */
    public static final String PATH_TICKET = "/api/v1/ticket";

    /** 票据有效期：够浏览器建连即可，短到即使泄漏也基本无用。 */
    private static final Duration TICKET_TTL = Duration.ofSeconds(30L);

    /**
     * 单账号未消费票据上限。
     *
     * <p>过期清理只在签发时触发；若客户端反复申请却从不建 SSE，map 会在 TTL 窗口内
     * 无限增长。限制单账号未用票据数，超了就淘汰该账号最旧的一张——正常前端
     * （申请一张就立刻建连）永远不会碰到这个上限。</p>
     */
    private static final int MAX_TICKETS_PER_USER = 8;

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

    /** 报文历史默认条数与上限；上限与 MessageLog 的内存容量一致。 */
    private static final int MESSAGES_LIMIT_DEFAULT = 500;
    private static final int MESSAGES_LIMIT_MAX = 5000;

    /** 审计历史默认条数与上限；审计是低频事件，不需要报文那么大。 */
    private static final int AUDIT_LIMIT_DEFAULT = 200;
    private static final int AUDIT_LIMIT_MAX = 1000;

    /** 票据随机源。 */
    private static final SecureRandom TICKET_RANDOM = new SecureRandom();

    /** 请求体上限：创建会话只需要一个配置名，给 4 KiB 已经很宽裕。 */
    private static final int MAX_BODY_BYTES = 4096;

    /**
     * 配置正文上限。
     *
     * <p>和 {@link #MAX_BODY_BYTES} 分开：配置正文是一整份设备定义，
     * 几百字节的设备还能编，真实的多腔体配置轻松几十 KB。
     * 给到 256 KiB 已经远超任何真实配置，又能挡住"上传一个 1 GiB 文件"的客户端。
     * </p>
     */
    private static final int MAX_CONFIG_BYTES = 256 * 1024;

    /** 手动发送 SML 的正文上限。 */
    private static final int MAX_SML_BYTES = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final ExecutorService sseExecutor;
    private final ScheduledExecutorService watchdog;
    private final AccessControl access;
    private final ApiContext context;
    private final AuditSink audit;
    private final Path webRoot;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    /** 一次性票据：ticket -> 票据。 */
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final long stallTimeoutMs;
    private final Instant startedAt = Instant.now();

    private AutoCloseable eventSubscription;

    private HttpApiServer(HttpServer server, ExecutorService executor, ExecutorService sseExecutor,
                          ScheduledExecutorService watchdog, AccessControl access, ApiContext context,
                          AuditSink audit, Path webRoot, long stallTimeoutMs) {
        this.server = server;
        this.executor = executor;
        this.sseExecutor = sseExecutor;
        this.watchdog = watchdog;
        this.access = access;
        this.context = context;
        this.audit = audit;
        this.webRoot = webRoot;
        this.stallTimeoutMs = stallTimeoutMs;
    }

    /**
     * 启动服务器（单 token 模式）。
     *
     * <p>口令持有者即 {@link ApiRole#ADMIN}：整个 API 都开放。
     * 需要分级时改用 {@link AccessControl} 版本。
     * </p>
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
        return start(bind, AccessControl.singleToken(token), context, webRoot, bus, sslContext);
    }

    /**
     * 启动服务器，鉴权由账号集合决定。
     *
     * @param bind    绑定地址
     * @param access  访问控制
     * @param context 运行时信息
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, AccessControl access,
                                      ApiContext context) throws IOException {
        return start(bind, access, context, null, null, null);
    }

    /**
     * 启动服务器，可选用 TLS，可选用多账号鉴权。
     *
     * @param bind       绑定地址
     * @param access     访问控制，不得为 {@code null}
     * @param context    运行时信息
     * @param webRoot    静态资源根目录，{@code null} 表示不托管
     * @param bus        事件总线，{@code null} 表示不推报文
     * @param sslContext TLS 上下文；{@code null} 表示明文 HTTP
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, AccessControl access, ApiContext context,
                                      Path webRoot, EventBus bus, SSLContext sslContext) throws IOException {
        return start(bind, access, context, webRoot, bus, sslContext, null);
    }

    /**
     * 启动服务器，并挂上审计旁路。
     *
     * @param bind       绑定地址
     * @param access     访问控制，不得为 {@code null}
     * @param context    运行时信息
     * @param webRoot    静态资源根目录，{@code null} 表示不托管
     * @param bus        事件总线，{@code null} 表示不推报文
     * @param sslContext TLS 上下文；{@code null} 表示明文 HTTP
     * @param audit      审计旁路；{@code null} 表示不审计
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, AccessControl access, ApiContext context,
                                      Path webRoot, EventBus bus, SSLContext sslContext,
                                      AuditSink audit) throws IOException {
        Objects.requireNonNull(access, "access");

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

        HttpApiServer api = new HttpApiServer(http, pool, ssePool, watchdog, access, context, audit,
                normalizeRoot(webRoot),
                Long.getLong("secssim.sse.stallTimeoutMs", SSE_STALL_TIMEOUT_DEFAULT_MS));
        http.setExecutor(pool);

        if (bus != null) {
            api.eventSubscription = bus.subscribe(api::broadcast);
        }

        /* 远程运行时后端：界面从另一台机器来（完整安装包指向远程地址），
           跨域请求必须放行；开放访问形态下来源不作限制 */
        http.createContext(PATH_HEALTH, withCors(api::handleHealth));
        http.createContext(PATH_EVENTS, withCors(api::handleEvents));
        http.createContext(PATH_TICKET, withCors(api::handleTicket));
        http.createContext(PATH_SHUTDOWN, withCors(api::handleShutdown));
        http.createContext(PATH_SESSIONS, withCors(api::handleSessions));
        http.createContext(PATH_SESSIONS_BULK, withCors(api::handleBulkSessions));
        http.createContext(PATH_SESSION_PREFIX, withCors(api::handleSessionPath));
        http.createContext(PATH_CONFIG_PREVIEW, withCors(api::handleConfigPreview));
        http.createContext(PATH_CONFIGS, withCors(api::handleConfigs));
        http.createContext(PATH_CONFIG_PREFIX, withCors(api::handleConfigPath));
        http.createContext(PATH_CONFIG_TEMPLATES, withCors(api::handleConfigTemplates));
        http.createContext(PATH_PREVIEW, withCors(api::handlePreview));
        http.createContext(PATH_OVERVIEW, withCors(api::handleOverview));
        http.createContext(PATH_PREVIEW_BINARY, withCors(api::handlePreviewBinary));
        http.createContext(PATH_MESSAGES, withCors(api::handleMessages));
        http.createContext(PATH_AUDIT, withCors(api::handleAudit));
        http.createContext(PATH_LOG_LEVEL, withCors(api::handleLogLevel));

        if (api.webRoot != null) {
            /* 根上下文兜底；HttpServer 按最长前缀匹配，因此不会抢走 /api/v1/* */
            http.createContext("/", withCors(api::handleStatic));
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
     * 订阅状态通知总线（schema v6:SSE 类型化事件）。
     *
     * <p>与报文总线并行:通知以 {@code event: <type>} 帧推送,
     * 报文事件保持 {@code event: message} 不变。</p>
     *
     * @param bus 通知总线;{@code null} 忽略
     */
    public void subscribeNotices(com.shimizukenta.secssim.app.event.NoticeBus bus) {
        if (bus != null) {
            this.noticeSubscription = bus.subscribe(this::broadcastNotice);
        }
    }

    private AutoCloseable noticeSubscription;

    /** 把一条状态通知推给所有订阅者;队列满同样丢最旧。 */
    private void broadcastNotice(com.shimizukenta.secssim.app.event.NoticeEvent event) {
        if (this.subscribers.isEmpty()) {
            return;
        }

        /* payload 是 JSON 对象文本,拆掉外层花括号与 sessionId/at 合并成一帧 */
        String payload = event.payload() == null ? "" : event.payload().trim();
        String merged;
        if (payload.length() <= 2) {
            merged = "{\"sessionId\":\"" + json(event.sessionId()) + "\",\"at\":" + event.atMillis() + "}";
        } else {
            merged = "{\"sessionId\":\"" + json(event.sessionId()) + "\",\"at\":" + event.atMillis()
                    + "," + payload.substring(1);
        }

        String frame = "event: " + event.type() + "\ndata: " + merged + "\n\n";
        for (Subscriber subscriber : this.subscribers) {
            subscriber.enqueue(frame);
        }
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
        for (AutoCloseable handle : new AutoCloseable[] {this.eventSubscription, this.noticeSubscription}) {
            if (handle != null) {
                try {
                    handle.close();
                } catch (Exception e) {
                    Log.error("取消事件订阅失败（已忽略）", e);
                }
            }
        }
        this.eventSubscription = null;
        this.noticeSubscription = null;

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
         * 版本、形态、会话数、HSMS 端口要带凭据才给，避免白名单外的扫描者拿到部署信息。
         */
        AccessControl.Principal principal = this.authenticate(ex);
        if (principal == null) {
            respond(ex, 200, "{\"ok\":true,\"uptimeMs\":" + uptime + "}");
            return;
        }

        respond(ex, 200, "{\"ok\":true"
                + ",\"version\":\"" + json(this.context.version()) + "\""
                + ",\"mode\":\"" + json(this.context.mode()) + "\""
                + ",\"user\":\"" + json(principal.name()) + "\""
                + ",\"role\":\"" + json(principal.role().name().toLowerCase(Locale.ROOT)) + "\""
                + ",\"uptimeMs\":" + uptime
                + this.context.extraHealthJson()
                + "}");
    }

    /**
     * 签发一次性 SSE 票据。
     *
     * <p>{@code EventSource} 不能设置请求头，只能把凭据放进 URL。直接放口令会进
     * 访问日志和浏览器历史，而且长期有效；改成 30 秒、只能用一次的票据，
     * 即使被记下来也基本无法利用。
     * </p>
     */
    private void handleTicket(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        long now = System.currentTimeMillis();
        this.tickets.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() < now);

        String ticket = newTicket();
        AccessControl.Principal principal = this.authenticate(ex);
        this.evictOldestTicketOf(principal, MAX_TICKETS_PER_USER - 1);
        this.tickets.put(ticket, new Ticket(now + TICKET_TTL.toMillis(), principal));

        respond(ex, 200, "{\"ok\":true,\"ticket\":\"" + ticket + "\""
                + ",\"user\":\"" + json(principal.name()) + "\""
                + ",\"role\":\"" + json(principal.role().name().toLowerCase(Locale.ROOT)) + "\""
                + ",\"expiresInMs\":" + TICKET_TTL.toMillis() + "}");
    }

    /**
     * 把某个账号的未消费票据压到 {@code keepAtMost} 张以内（淘汰最旧的）。
     *
     * @param principal  账号
     * @param keepAtMost 保留张数
     */
    private void evictOldestTicketOf(AccessControl.Principal principal, int keepAtMost) {
        List<Map.Entry<String, Ticket>> mine = new ArrayList<>();
        for (Map.Entry<String, Ticket> entry : this.tickets.entrySet()) {
            if (entry.getValue().principal().name().equals(principal.name())) {
                mine.add(entry);
            }
        }

        int excess = mine.size() - keepAtMost;
        if (excess <= 0) {
            return;
        }

        mine.sort(Comparator.comparingLong(entry -> entry.getValue().expiresAtMs()));
        for (int i = 0; i < excess; i++) {
            this.tickets.remove(mine.get(i).getKey());
        }
    }

    /**
     * 一次性票据：记录过期时刻与签发身份。
     *
     * <p>身份留在票据上，是为了在 SSE 建连那一刻还能说出"是谁在看报文流"——
     * 票据本身是匿名的 URL 参数，用完即弃。
     * </p>
     *
     * @param expiresAtMs 过期时刻（毫秒）
     * @param principal   签发者
     */
    private record Ticket(long expiresAtMs, AccessControl.Principal principal) {
    }

    /** 生成票据：32 字节随机数的十六进制。 */
    private static String newTicket() {
        byte[] bytes = new byte[32];
        TICKET_RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** 消费票据：原子移除 + 过期校验，保证只能用一次。 */
    private Ticket consumeTicket(String ticket) {
        if (ticket == null || ticket.isEmpty()) {
            return null;
        }
        Ticket value = this.tickets.remove(ticket);
        return value != null && value.expiresAtMs() >= System.currentTimeMillis() ? value : null;
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
        if (!requireRole(ex, ApiRole.ADMIN)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        audit(ex, "shutdown", "server", "ok");
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
    private void createSession(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.OPERATOR)) {
            return;
        }

        String body = readBody(ex);
        if (body == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
            return;
        }

        SessionCreateRequest request = sessionCreateRequestFrom(body);
        if (request == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"missing-config\"}");
            return;
        }

        SessionOutcome outcome = this.context.createSession(request);
        audit(ex, "session-create", request.config(), "outcome=" + outcome);
        switch (outcome) {
            case OK -> respond(ex, 201, "{\"ok\":true}");
            case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"session-exists\"}");
            default -> respond(ex, 400, "{\"ok\":false,\"error\":\"create-failed\"}");
        }
    }

    /**
     * 从请求体解析"新建会话"请求：config 必填，其余是可选的部署期覆盖。
     *
     * <p>覆盖字段（sessionId / bindHost / port / deviceId）缺省就用配置原文里的——
     * 多台设备复用一份设备配置时只有这几项逐台不同。</p>
     *
     * @return 请求；体不是对象或缺 config 时返回 {@code null}
     */
    private static SessionCreateRequest sessionCreateRequestFrom(String body) {
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
            Integer port = optionalInt(root, "port", n -> n > 0 && n <= 65535);
            Integer deviceId = optionalInt(root, "deviceId", n -> n >= 0);
            return new SessionCreateRequest(config, optionalString(root, "sessionId"),
                    optionalString(root, "bindHost"), port, deviceId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * POST /api/v1/sessions/bulk：从一份设备配置批量开多台设备。
     *
     * <p>体形如 {@code {"config":"tpl.json","count":5,"sessionIdPrefix":"dev-",
     * "portBase":5100,"deviceIdBase":100,"bindHost":"127.0.0.1"}}。
     * 第 i 台（从 1 起）的会话 id 是 {@code prefix+i}、端口 {@code portBase+i-1}、
     * device-id {@code deviceIdBase+i-1}。逐台独立创建：一台起不来不影响其余，
     * 全部成功回 201，部分失败回 207 并逐台给原因。</p>
     */
    private void handleBulkSessions(HttpExchange ex) throws IOException {
        if (!PATH_SESSIONS_BULK.equals(ex.getRequestURI().getPath())) {
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
        int count = root.containsKey("count")
                ? root.get("count").optionalInt().orElse(0)
                : 0;
        String prefix = optionalString(root, "sessionIdPrefix");
        int portBase = root.containsKey("portBase")
                ? root.get("portBase").optionalInt().orElse(0)
                : 0;
        int deviceIdBase = root.containsKey("deviceIdBase")
                ? root.get("deviceIdBase").optionalInt().orElse(0)
                : 0;
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
            SessionCreateRequest request = new SessionCreateRequest(config, prefix + i, bindHost, port, deviceId);
            SessionOutcome outcome = this.context.createSession(request);
            audit(ex, "session-create", config + "#" + i, "outcome=" + outcome);
            if (outcome == SessionOutcome.OK) {
                if (okCount > 0) {
                    created.append(',');
                }
                created.append("\"").append(prefix + i).append('"');
                okCount++;
            } else {
                if (failed.length() > 0) {
                    failed.append(',');
                }
                failed.append("{\"index\":").append(i)
                        .append(",\"error\":\"").append(outcome.name().toLowerCase()).append("\"}");
            }
        }

        int status = okCount == count ? 201 : okCount > 0 ? 207 : 502;
        String json = "{\"created\":[" + created + "],\"failed\":[" + failed + "]}";
        respond(ex, status, json);
    }

    /** 缺键返回 {@code null} 的字符串字段；{@code JsonHub.get} 对缺失键会抛，不能直接用。 */
    private static String optionalString(com.shimizukenta.jsonhub.JsonHub root, String key) {
        if (!root.containsKey(key)) {
            return null;
        }
        return root.get(key).optionalString().map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    }

    /** 缺键或非法返回 {@code null} 的整数字段。 */
    private static Integer optionalInt(com.shimizukenta.jsonhub.JsonHub root, String key,
            java.util.function.IntPredicate valid) {
        if (!root.containsKey(key)) {
            return null;
        }
        return root.get(key).optionalInt().stream().filter(valid).boxed().findFirst().orElse(null);
    }

    /**
     * 读请求体，带上限。
     *
     * <p>不给上限的话，一个失控的客户端可以让内存先于服务挂掉；
     * 创建会话这个端点只需要几十字节的配置名。
     * </p>
     *
     * @return 正文；超过上限返回 {@code null}
     */
    private static String readBody(HttpExchange ex) throws IOException {
        return readBody(ex, MAX_BODY_BYTES);
    }

    /**
     * 读请求体，带上限。
     *
     * @param ex    交换
     * @param limit 字节上限
     * @return 正文；超过上限返回 {@code null}
     * @throws IOException 读失败
     */
    private static String readBody(HttpExchange ex, int limit) throws IOException {
        try (var in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * POST /api/v1/config-preview：按草稿文本算曲线预览。
     *
     * <p>编辑器里改模板/SVID 时不用先保存再开会话——直接看"这份配置会画出什么曲线"。
     * 时间窗相对模拟时钟的 0..N 毫秒（草稿没有运行时态，从种子确定性重放）。</p>
     */
    private void handleConfigPreview(HttpExchange ex) throws IOException {
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
    private void handleConfigs(HttpExchange ex) throws IOException {
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
    private void createConfig(HttpExchange ex) throws IOException {
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

    /** 新建配置请求：文件名 + 可选模板。 */
    private record NewConfigRequest(String name, String templateId) {
    }

    /** 从请求体里取 name 与可选 template 字段；体不是对象或缺 name 时返回 {@code null}。 */
    private static NewConfigRequest newConfigRequestFrom(String body) {
        try {
            com.shimizukenta.jsonhub.JsonHub root =
                    com.shimizukenta.jsonhub.JsonHub.fromJson(body);
            if (!root.isObject()) {
                return null;
            }
            String name = root.get("name").optionalString().map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
            if (name == null) {
                return null;
            }
            /* 模板缺省给空骨架（DeviceTemplates 在 runtime 层，这里只认同一字符串约定）：
               模板字段写错值走"未知模板"问题清单，不在这里拦。JsonHub.get 缺键会抛，先 containsKey */
            String templateId = root.containsKey("template")
                    ? root.get("template").optionalString().map(String::trim).filter(s -> !s.isEmpty()).orElse("bare")
                    : "bare";
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
    private void handleConfigTemplates(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 200, "{\"templates\":" + this.context.configTemplatesJson() + "}");
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /**
     * 单份配置的读取、校验与保存。
     *
     * <p>读是 {@link ApiRole#VIEWER}；保存是 {@link ApiRole#OPERATOR}：
     * 配置决定了下一台设备长什么样，改它属于"会改变设备状态"的那一类，
     * 和触发 CEID、增删会话同属一级。
     * </p>
     */
    private void handleConfigPath(HttpExchange ex) throws IOException {
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
    private void readConfig(HttpExchange ex) throws IOException {
        String name = configNameFromPath(ex);
        if (name == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        String text = this.context.configText(name);
        if (text == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }

        /* modifiedMs 随原文一起回：编辑器存盘时经 If-Match 带回，做并发保护 */
        Long modifiedMs = this.context.configModifiedMs(name);
        String meta = modifiedMs == null ? "" : ",\"modifiedMs\":" + modifiedMs;
        respond(ex, 200, "{\"name\":\"" + json(name) + "\",\"text\":\"" + json(text) + "\"" + meta + "}");
    }

    /**
     * POST /api/v1/configs/{name}/validate：只校验不落盘。
     *
     * <p>和"保存"分开，是因为保存一步就写盘了。用户想先确认自己这份改动能不能编译，
     * 不该被迫先覆盖一份能用的配置。
     * </p>
     */
    private void validateConfig(HttpExchange ex) throws IOException {
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
    private void saveConfig(HttpExchange ex) throws IOException {
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
    private static String problemsJson(boolean valid, List<String> problems) {
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
    private static String configNameFromPath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (path == null || !path.startsWith(PATH_CONFIG_PREFIX)) {
            return null;
        }

        String rest = path.substring(PATH_CONFIG_PREFIX.length());
        if (rest.isEmpty()) {
            return null;
        }

        String[] parts = rest.split("/");
        if (parts.length == 1 && !parts[0].isEmpty()) {
            return decode(parts[0]);
        }
        if (parts.length == 2 && !parts[0].isEmpty() && PATH_VALIDATE_SUFFIX.equals(parts[1])) {
            return decode(parts[0]);
        }
        return null;
    }

    /** 请求是否打在校验子路径上。 */
    private static boolean isValidatePath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        return path != null && path.endsWith("/" + PATH_VALIDATE_SUFFIX);
    }

    /**
     * 会话命令：目前有"手动触发采集事件"、"置位/清除报警"、"按配置重载"。
     *
     * <p>用 POST 是因为它们会改变设备状态（主机会看到一条 S6F11 / S5F1），
     * 不是幂等查询，因此最低角色是 {@link ApiRole#OPERATOR}。
     * </p>
     */
    private void handleSessionPath(HttpExchange ex) throws IOException {
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
            this.closeSession(ex);
            return;
        }

        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /**
     * DELETE /api/v1/sessions/{id}：关闭并移除会话。
     *
     * <p>响应等真正关完才发：客户端拿到 200 就应当认为端口已经解绑了。
     * </p>
     */
    private void closeSession(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/host-sim")) {
            String id = path.substring(PATH_SESSION_PREFIX.length(), path.length() - "/host-sim".length());
            SessionOutcome outcome = this.context.closeHostSim(id);
            audit(ex, "host-sim-close", id, "outcome=" + outcome);
            respond(ex, outcome == SessionOutcome.OK ? 200 : 404,
                    outcome == SessionOutcome.OK ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"not-open\"}");
            return;
        }

        String id = sessionIdFromPath(ex);
        if (id == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }

        SessionOutcome outcome = this.context.closeSession(id);
        audit(ex, "session-close", id, "outcome=" + outcome);
        switch (outcome) {
            case OK -> respond(ex, 200, "{\"ok\":true}");
            default -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
        }
    }

    /** 取路径里的会话标识；路径不合法时返回 {@code null}。 */
    private static String sessionIdFromPath(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (path == null || !path.startsWith(PATH_SESSION_PREFIX)) {
            return null;
        }
        String rest = path.substring(PATH_SESSION_PREFIX.length());
        if (rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        return decode(rest);
    }

    /**
     * GET /api/v1/sessions/{id}/alarms、/terminal 或 /catalog。
     */
    private void handleSessionQuery(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/host-sim/collector")) {
            /* v6:采集器状态与数据 */
            String id = path.substring(PATH_SESSION_PREFIX.length(),
                    path.length() - "/host-sim/collector".length());
            String json = this.context.hostSimCollectorJson(id);
            if (json == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                return;
            }
            respond(ex, 200, json);
            return;
        }
        if (path.endsWith("/host-sim")) {
            String id = path.substring(PATH_SESSION_PREFIX.length(), path.length() - "/host-sim".length());
            String json = this.context.hostSimJson(id);
            if (json == null) {
                respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                return;
            }
            respond(ex, 200, json);
            return;
        }

        String rest = ex.getRequestURI().getPath().substring(PATH_SESSION_PREFIX.length());
        String[] parts = rest.split("/");

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

    private void handleSessionCommand(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String rest = ex.getRequestURI().getPath().substring(PATH_SESSION_PREFIX.length());
        String[] parts = rest.split("/");

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
        if (parts.length >= 2 && "host-sim".equals(parts[1])) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            if (parts.length == 2) {
                /* v6:请求体可选携带远程目标 {"host":..,"port":..,"deviceId":..};空体连本机 */
                String targetBody = readBody(ex, MAX_CONFIG_BYTES);
                if (targetBody != null && targetBody.isBlank()) {
                    targetBody = null;
                }
                SessionOutcome outcome = this.context.openHostSim(parts[0], targetBody);
                audit(ex, "host-sim-open", parts[0], "outcome=" + outcome
                        + (targetBody == null ? "" : " remote"));
                switch (outcome) {
                    case OK -> respond(ex, 201, "{\"ok\":true}");
                    case CONFLICT -> respond(ex, 409, "{\"ok\":false,\"error\":\"host-busy-or-sim-open\"}");
                    case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                    default -> respond(ex, 502, "{\"ok\":false,\"error\":\"open-failed\"}");
                }
                return;
            }
            if (parts.length == 3 && "send".equals(parts[2])) {
                String body = readBody(ex);
                if (body == null || body.trim().isEmpty()) {
                    respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                    return;
                }
                try {
                    String result = this.context.sendHostSml(parts[0], body);
                    if (result == null) {
                        respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                        return;
                    }
                    respond(ex, 200, result);
                } catch (IllegalArgumentException e) {
                    respond(ex, 400, "{\"ok\":false,\"error\":\"SML 解析失败: " + json(e.getMessage()) + "\"}");
                } catch (IllegalStateException e) {
                    respond(ex, 409, "{\"ok\":false,\"error\":\"host-sim-not-open\"}");
                } catch (IOException e) {
                    respond(ex, 502, "{\"ok\":false,\"error\":\"send-failed: " + json(e.getMessage()) + "\"}");
                }
                return;
            }
            if (parts.length == 3 && "collector".equals(parts[2])) {
                String body = readBody(ex, MAX_CONFIG_BYTES);
                if (body == null || body.isBlank()) {
                    respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                    return;
                }
                String result = this.context.setHostSimCollector(parts[0], body);
                audit(ex, "host-sim-collector-config", parts[0], "ok=" + result.contains("\"ok\":true"));
                respond(ex, result.contains("\"ok\":true") ? 200 : 400, result);
                return;
            }
            if (parts.length == 4 && "collector".equals(parts[2])
                    && ("start".equals(parts[3]) || "stop".equals(parts[3]))) {
                String result = this.context.hostSimCollectorCommand(parts[0], "start".equals(parts[3]));
                audit(ex, "host-sim-collector-" + parts[3], parts[0], "ok=" + result.contains("\"ok\":true"));
                respond(ex, result.contains("\"ok\":true") ? 200 : 409, result);
                return;
            }
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
            audit(ex, "burst", parts[0], "ok=" + !result.contains("error"));
            respond(ex, 200, result);
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
            audit(ex, "alarm", parts[0], "alid=" + alid + " set=" + set
                    + " category=" + category + " outcome=" + outcome);
        } else if ("events".equals(parts[1])) {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            String enabledParam = query.getOrDefault("enabled", "true");
            boolean enabled;
            if ("true".equalsIgnoreCase(enabledParam)) {
                enabled = true;
            } else if ("false".equalsIgnoreCase(enabledParam)) {
                enabled = false;
            } else {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-enabled\"}");
                return;
            }

            EventOutcome eventOutcome = this.context.setEventEnabled(
                    parts[0], decode(parts[2]), enabled);
            audit(ex, "event-toggle", parts[0],
                    "plan=" + decode(parts[2]) + " enabled=" + enabled + " outcome=" + eventOutcome);
            switch (eventOutcome) {
                case OK -> respond(ex, 200, "{\"ok\":true}");
                case UNKNOWN_SESSION -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
                case UNKNOWN_PLAN -> respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-plan\"}");
            }
            return;
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
     * POST /api/v1/sessions/{id}/message：手动发送 SML。
     *
     * <p>请求体就是 SML 原文，不包 JSON——手写报文时多套一层转义只会更容易错。
     * 解析失败 400，没有对端 409，协议层发送失败 500。</p>
     */
    private void sendSml(HttpExchange ex, String sessionId) throws IOException {
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
            Log.error("手动发送 SML 失败", e);
            respond(ex, 500, "{\"ok\":false,\"error\":\"send-failed\"}");
        }
    }

    /**
     * GET /api/v1/overview：全部会话的工作台聚合（schema v6）。
     */
    private void handleOverview(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }
        String body = this.context.overviewJson();
        respond(ex, 200, body == null ? "{\"sessions\":[]}" : body);
    }

    /**
     * 曲线预览：{@code /api/v1/preview?session=demo&from=0&to=60000&buckets=120}。
     *
     * <p>参数非法一律 400：静默回落默认值会让调用方以为自己的参数生效了。
     * schema v6 增加可选 {@code svids=401,402} 过滤与 {@code withEvents=false} 关闭事件标记。
     * </p>
     */
    private void handlePreview(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        PreviewRequest request = readPreviewRequest(ex);
        if (request == null) {
            return;
        }

        String body = this.context.previewJson(request.sessionId(), request.fromMs(),
                request.toMs(), request.buckets(), request.svids(), request.withEvents());
        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            return;
        }

        respond(ex, 200, body);
    }

    /**
     * 二进制曲线预览：{@code /api/v1/preview.bin?...}。
     *
     * <p>格式见 {@code SessionRuntime.previewBinary}：固定头 + 每条序列的
     * svid 与 min/max/avg 双精度数组，小端。给大曲线或不走 JSON 的客户端用。</p>
     */
    private void handlePreviewBinary(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        PreviewRequest request = readPreviewRequest(ex);
        if (request == null) {
            return;
        }

        byte[] body = this.context.previewBinary(request.sessionId(), request.fromMs(),
                request.toMs(), request.buckets());
        if (body == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"unknown-session\"}");
            return;
        }
        respondBinary(ex, "application/octet-stream", "preview.bin", body);
    }

    private PreviewRequest readPreviewRequest(HttpExchange ex) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        Long fromMs = longParam(query, "from");
        Long toMs = longParam(query, "to");
        Long buckets = longParam(query, "buckets");

        /* 缺省用默认值；写了但不是数字才报错 */
        if ((query.containsKey("from") && fromMs == null)
                || (query.containsKey("to") && toMs == null)
                || (query.containsKey("buckets") && buckets == null)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-number\"}");
            return null;
        }

        long from = fromMs == null ? 0L : fromMs;
        long to = toMs == null ? 60_000L : toMs;
        long count = buckets == null ? 120L : buckets;

        if (to <= from) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-range\"}");
            return null;
        }
        if (count < PREVIEW_BUCKETS_MIN || count > PREVIEW_BUCKETS_MAX) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-buckets\"}");
            return null;
        }

        String sessionId = query.getOrDefault("session", "");
        if (sessionId.isEmpty()) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"missing-session\"}");
            return null;
        }

        java.util.Set<Integer> svids = new java.util.LinkedHashSet<>();
        String rawSvids = query.get("svids");
        if (rawSvids != null && !rawSvids.isBlank()) {
            try {
                for (String piece : rawSvids.split(",")) {
                    if (!piece.isBlank()) {
                        svids.add(Integer.parseInt(piece.trim()));
                    }
                }
            } catch (NumberFormatException e) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-svids\"}");
                return null;
            }
        }

        boolean withEvents = true;
        String rawEvents = query.get("withEvents");
        if (rawEvents != null) {
            withEvents = !"false".equalsIgnoreCase(rawEvents) && !"0".equals(rawEvents);
        }

        return new PreviewRequest(sessionId, from, to, (int) count, svids, withEvents);
    }

    private record PreviewRequest(String sessionId, long fromMs, long toMs, int buckets,
                                  java.util.Set<Integer> svids, boolean withEvents) {
    }

    /**
     * 报文历史：{@code /api/v1/messages?session=&direction=&limit=&format=}。
     *
     * <p>它补的是 SSE 的“现在”之外的那一段：断线、刷新页面、或只是想看
     * 上一分钟到底发了什么。默认回 JSON；{@code format=jsonl} 直接下载
     * JSON Lines，方便丢给 jq / Select-String。</p>
     */
    private void handleMessages(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        Long limitParam = longParam(query, "limit");
        if (query.containsKey("limit") && limitParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }
        long limit = limitParam == null ? MESSAGES_LIMIT_DEFAULT : limitParam;
        if (limit < 1 || limit > MESSAGES_LIMIT_MAX) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }

        String direction = query.getOrDefault("direction", "").trim().toLowerCase(Locale.ROOT);
        if (!direction.isEmpty() && !"in".equals(direction) && !"out".equals(direction)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-direction\"}");
            return;
        }

        String session = query.getOrDefault("session", "").trim();
        String format = query.getOrDefault("format", "json").trim().toLowerCase(Locale.ROOT);

        /* before 游标:只取时间早于该值的消息(翻旧账);stream/function 过滤报文类型 */
        Long beforeParam = longParam(query, "before");
        if (query.containsKey("before") && beforeParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }
        long before = beforeParam == null ? 0L : beforeParam;
        if (before < 0L) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }
        Integer stream = intParam(query, "stream");
        if (query.containsKey("stream") && stream == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-stream\"}");
            return;
        }
        Integer function = intParam(query, "function");
        if (query.containsKey("function") && function == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-function\"}");
            return;
        }

        if ("jsonl".equals(format)) {
            String body = this.context.messagesJsonl((int) limit, session, direction, before, stream, function);
            if (body == null) {
                respond(ex, 503, "{\"ok\":false,\"error\":\"message-log-unavailable\"}");
                return;
            }
            respondNdjson(ex, body);
            return;
        }

        if (!format.isEmpty() && !"json".equals(format)) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-format\"}");
            return;
        }

        String body = this.context.messagesJson((int) limit, session, direction, before, stream, function);
        if (body == null) {
            respond(ex, 503, "{\"ok\":false,\"error\":\"message-log-unavailable\"}");
            return;
        }
        respond(ex, 200, body);
    }

    /**
     * GET /api/v1/log-level：当前级别；PUT 切换（body 是 error/info/debug，裸文本或 JSON 均可）。
     *
     * <p>排查现场问题的标准动作是"先开 debug 再复现"；能远程切就不用上机器改启动参数。
     * 切换记进审计。</p>
     */
    private void handleLogLevel(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.VIEWER)) {
                return;
            }
            respond(ex, 200, "{\"level\":\"" + json(this.context.logLevel()) + "\"}");
            return;
        }
        if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!requireRole(ex, ApiRole.OPERATOR)) {
                return;
            }
            String body = readBody(ex);
            if (body == null) {
                respond(ex, 400, "{\"ok\":false,\"error\":\"bad-body\"}");
                return;
            }
            String level = null;
            try {
                com.shimizukenta.jsonhub.JsonHub root = com.shimizukenta.jsonhub.JsonHub.fromJson(body);
                if (root.isObject() && root.containsKey("level")) {
                    level = root.get("level").optionalString().orElse(null);
                } else if (root.isString()) {
                    level = root.optionalString().orElse(null);
                }
            } catch (RuntimeException ignore) {
                /* 不是 JSON 就当裸文本处理 */
            }
            if (level == null) {
                level = body.trim().replace("\"", "");
            }
            boolean changed = this.context.setLogLevel(level);
            audit(ex, "log-level", level, changed ? "outcome=OK" : "outcome=REJECTED");
            respond(ex, changed ? 200 : 400,
                    changed ? "{\"ok\":true,\"level\":\"" + json(this.context.logLevel()) + "\"}"
                            : "{\"ok\":false,\"error\":\"unknown-level\"}");
            return;
        }
        if (!requireRole(ex, ApiRole.VIEWER)) {
            return;
        }
        respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
    }

    /**
     * 审计历史：{@code /api/v1/audit?limit=}。
     *
     * <p>只给 admin：审计里会有账号名、动作目标和结果，
     * 比报文历史更敏感；viewer/operator 不应看到别人的操作轨迹。</p>
     */
    private void handleAudit(HttpExchange ex) throws IOException {
        if (!requireRole(ex, ApiRole.ADMIN)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        Long limitParam = longParam(query, "limit");
        if (query.containsKey("limit") && limitParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }
        long limit = limitParam == null ? AUDIT_LIMIT_DEFAULT : limitParam;
        if (limit < 1 || limit > AUDIT_LIMIT_MAX) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-limit\"}");
            return;
        }

        /* 服务端过滤:action/actor 精确匹配,空串表示全部;before 游标翻更早的记录 */
        String action = query.getOrDefault("action", "").trim();
        String actor = query.getOrDefault("actor", "").trim();
        Long auditBeforeParam = longParam(query, "before");
        if (query.containsKey("before") && auditBeforeParam == null) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }
        long auditBefore = auditBeforeParam == null ? 0L : auditBeforeParam;
        if (auditBefore < 0L) {
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-before\"}");
            return;
        }

        String body = this.context.auditJson((int) limit, action, actor, auditBefore);
        if (body == null) {
            respond(ex, 503, "{\"ok\":false,\"error\":\"audit-log-unavailable\"}");
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

    /** 整数查询参数;缺省回 null,形状不对也回 null(调用方区分 containsKey 判 400)。 */
    private static Integer intParam(Map<String, String> query, String key) {
        String raw = query.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * SSE 入口：用一次性票据鉴权（{@code EventSource} 无法带请求头），
     * 然后把长连接交给 SSE 专用池。
     *
     * <p>票据只能由通过鉴权的账号换取，因此持有票据即至少 {@link ApiRole#VIEWER}，
     * 这里不再重复判角色——报文流是只读的。
     * </p>
     */
    private void handleEvents(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String ticket = parseQuery(ex.getRequestURI().getRawQuery()).get("ticket");
        Ticket redeemed = consumeTicket(ticket);
        if (redeemed == null) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        /* 报文流能看到全部会话的收发，谁在看要留痕：这是排障时最常问的一句话 */
        Log.info("SSE 订阅者 " + redeemed.principal().name()
                + "（" + redeemed.principal().role().name().toLowerCase(Locale.ROOT) + "）已连接");

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
     * 普通端点鉴权：只认 {@code Authorization: Bearer}，并判定角色。
     *
     * <p>不再接受 {@code ?token=}：URL 里的长期凭据会进访问日志、浏览器历史和 Referer。
     * SSE 走一次性 ticket（见 {@link #handleTicket}）。
     * </p>
     *
     * <p>未认证回 401，认证通过但角色不够回 403：
     * 两者的区别要如实告诉调用方，否则"我的口令是不是打错了"永远查不出来。
     * </p>
     *
     * @param ex       交换
     * @param required 需要的最低角色
     * @return {@code true} 表示放行；否则已写出错误响应，调用方必须立即返回
     */
    private boolean requireRole(HttpExchange ex, ApiRole required) throws IOException {
        AccessControl.Principal principal = this.authenticate(ex);
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

    /**
     * 记录一条审计；没有审计器或没带凭据时静默跳过。
     *
     * <p>身份从请求头重新解析，而不是让每个 handler 都多传一个 Principal：
     * 审计是旁路，不值得把鉴权对象穿透整条调用链。</p>
     */
    private void audit(HttpExchange ex, String action, String target, String detail) {
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

    /**
     * 给处理器包一层跨域放行。
     *
     * <p>预检（OPTIONS）直接回 204；实际响应统一带
     * {@code Access-Control-Allow-Origin: *}。服务端形态若配置了凭据，
     * 鉴权仍在业务处理器里照常执行——CORS 只解决"浏览器让不让发"。</p>
     */
    private static com.sun.net.httpserver.HttpHandler withCors(com.sun.net.httpserver.HttpHandler handler) {
        return ex -> {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization, Content-Type");
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            handler.handle(ex);
        };
    }

    /** 只做认证、不判角色（health 需要区分"没带凭据"和"带了凭据"）。 */
    private AccessControl.Principal authenticate(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String secret = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim()
                : null;
        /* 没带凭据也要问一次 AccessControl：开放访问形态下匿名请求就是合法管理员 */
        return this.access.authenticate(secret);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 回二进制正文；用于曲线预览等不适合 JSON 的负载。 */
    private static void respondBinary(HttpExchange ex, String contentType,
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
    private static void respondNdjson(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"messages.jsonl\"");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
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
