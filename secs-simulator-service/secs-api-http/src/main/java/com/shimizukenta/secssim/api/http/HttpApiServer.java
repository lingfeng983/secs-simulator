package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.NoticeEvent;
import com.shimizukenta.secssim.app.log.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * HTTP 服务器：生命周期、路由表与线程池。
 *
 * <p>这个类只做三件事——起停、挂路由、管线程。各资源域的请求处理都在
 * 各自的 Handler 里（会话 / 配置 / 预览 / 主机模拟 / 运维 / 流式推送），
 * 共享的鉴权、审计与请求解析在 {@link HttpSupport} 里。
 * 原来两千多行的一坨拆开后，改一个路由不用翻整台服务器。</p>
 *
 * <p>路由表见 {@link #registerRoutes}——新增端点只改那一处。</p>
 */
public final class HttpApiServer {

    /** 访问日志的来源标识：所有 handler 的请求都从这里出，便于 grep 出一次会话的全貌。 */
    private static final com.shimizukenta.secssim.app.log.Log.Facade ACCESS =
            com.shimizukenta.secssim.app.log.Log.of(HttpApiServer.class);

    /** 超过这个耗时就升 WARN。与"慢请求专用池"的判定口径保持一致。 */
    private static final long SLOW_REQUEST_MS = 1_000L;

    /**
     * 写入停滞判定：连续这么久没有任何一帧写成功，就认为客户端已经不读了。
     *
     * <p>正常连接每 15 秒至少有一次心跳，所以 60 秒无进展必然异常。
     * 测试可用 {@code -Dsecssim.sse.stallTimeoutMs=...} 调小。</p>
     */
    private static final long SSE_STALL_TIMEOUT_DEFAULT_MS = 60_000L;

    /** 看门狗巡检间隔。 */
    private static final long SSE_WATCHDOG_TICK_MS = 5_000L;

    /** 慢请求线程数。 */
    private static final int SLOW_THREADS = Math.max(2, Integer.getInteger("secssim.http.slowThreads", 4));

    /** 慢请求排队上限；满了直接 503，不无限堆。 */
    private static final int SLOW_QUEUE = Math.max(SLOW_THREADS, Integer.getInteger("secssim.http.slowQueue", 16));

    /**
     * 是否用虚拟线程处理 HTTP 请求。
     *
     * <p>JDK 的 HttpServer 是<b>每连接一个任务</b>：连接在 keep-alive 空闲期仍然占着
     * 执行线程。浏览器一个域名就是 6 条连接，几条连接一开，固定线程池就全被"空转"占住——
     * 这比慢请求更隐蔽。虚拟线程在等 socket 时卸载载体线程，正好对路；
     */
    private static final boolean VIRTUAL_THREADS =
            !"false".equalsIgnoreCase(System.getProperty("secssim.http.virtual"));

    /**
     * 平台线程回退模式下的请求线程数（虚拟线程模式下不生效）。
     *
     * <p>以前固定 8 个：手动 SML 带 W 会同步等 T3、host-sim 建连最长 6 秒、
     * 配置草稿要编译、报文历史要序列化 5000 条，4 个线程时几个这样的请求
     * 就能让 health/轮询/SSE 取票全部排队。这里保留这个旋钮只为可选回退。</p>
     */
    private static final int REQUEST_THREADS = Math.max(4, Integer.getInteger("secssim.http.threads", 8));

    /**
     * 单请求最长执行时间。
     *
     * <p>慢速正文（声明很大的 Content-Length 后每秒发 1 字节）会让处理线程永久阻塞；
     * 到点直接关闭交换，至少能把线程拿回来。默认 120 秒，需覆盖 T3=45 秒的等回复场景。</p>
     */
    private static final long REQUEST_TIMEOUT_MS =
            Long.getLong("secssim.http.requestTimeoutMs", 120_000L);

    /** SSE 线程数上限：每个连接一根线程，超出直接 503，而不是无限堆积。 */
    private static final int SSE_THREADS_MAX = 64;

    private final HttpServer server;
    private final ExecutorService executor;

    /**
     * 同时在处理的请求数上限。
     *
     * <p>虚拟线程模式下"每请求一个任务"没有天然上限：海量 keep-alive 连接
     * 或慢速正文各自占一个请求上下文，堆会跟着涨。超限直接 503，
     * 让客户端看到明确的"忙"而不是把服务拖死。</p>
     */
    private static final int MAX_IN_FLIGHT = 512;

    private final java.util.concurrent.Semaphore inFlight =
            new java.util.concurrent.Semaphore(MAX_IN_FLIGHT);
    private final ApiContext context;
    private final AccessControl access;
    private final AuditSink audit;
    private final Path webRoot;

    /**
     * 慢请求专用池。
     *
     * <p>手动发送（等 T3，最长 45 秒）、host-sim 建连、配置草稿编译、5000 条报文历史
     * 都不是短任务；放在通用池里，几个并发就能把 health/轮询/取票全部堵住。</p>
     */
    private final java.util.concurrent.ThreadPoolExecutor slowExecutor;

    /** 流式推送域（票据 + SSE + 看门狗）。 */
    private final StreamHandler streams;

    /** 会话域。 */
    private final SessionHandler sessions;

    /** 配置域。 */
    private final ConfigHandler configs;

    /** 曲线预览域。 */
    private final PreviewHandler previews;

    /** 主机模拟域。 */
    private final HostSimHandler hostSims;

    /** 运维域（总览/报文/审计/日志级别）。 */
    private final OpsHandler ops;

    private final Instant startedAt = Instant.now();

    private AutoCloseable eventSubscription;
    private AutoCloseable noticeSubscription;

    /** 请求截止时间：到点关闭交换，避免慢正文/慢读端长期占用请求线程。 */
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "http-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private HttpApiServer(HttpServer server, ExecutorService executor, ApiContext context,
                          AccessControl access, AuditSink audit, Path webRoot, long stallTimeoutMs) {
        this.server = server;
        this.executor = executor;

        java.util.concurrent.atomic.AtomicInteger slowSeq = new java.util.concurrent.atomic.AtomicInteger();
        /* 慢路由保留"并发上限 + 排队上限"：挡的是 CPU/内存型重活（编译草稿、压测、
           等 T3），不是线程饥饿；超过上限仍回 503。线程本身用虚拟线程，
           阻塞在 T3 上不占载体。 */
        this.slowExecutor = new java.util.concurrent.ThreadPoolExecutor(
                SLOW_THREADS, SLOW_THREADS, 30L, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(SLOW_QUEUE),
                VIRTUAL_THREADS
                        ? Thread.ofVirtual().name("http-slow-", 1).factory()
                        : runnable -> {
                            Thread thread = new Thread(runnable, "http-slow-" + slowSeq.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });
        this.slowExecutor.allowCoreThreadTimeOut(true);
        this.context = context;
        this.access = access;
        this.audit = audit;
        this.webRoot = webRoot;

        AtomicInteger sseSeq = new AtomicInteger();
        /* 0 核心线程 + 同步队列 = 来一个连接开一根线程，空闲 60 秒回收；上限之外的连接直接 503。
           每条流大部分时间都在等下一帧，虚拟线程版不占载体线程。 */
        ExecutorService ssePool = new ThreadPoolExecutor(0, SSE_THREADS_MAX, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                VIRTUAL_THREADS
                        ? Thread.ofVirtual().name("sse-", 1).factory()
                        : runnable -> {
                            Thread thread = new Thread(runnable, "sse-" + sseSeq.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-watchdog");
            thread.setDaemon(true);
            return thread;
        });

        this.streams = new StreamHandler(context, access, audit, ssePool, watchdog, stallTimeoutMs);
        this.sessions = new SessionHandler(context, access, audit);
        this.configs = new ConfigHandler(context, access, audit);
        this.previews = new PreviewHandler(context, access, audit);
        this.hostSims = new HostSimHandler(context, access, audit);
        this.ops = new OpsHandler(context, access, audit);
    }

    /**
     * 启动服务器（直连访问）。
     *
     * @param bind    绑定地址，端口可传 0 由系统分配
     * @param access  访问控制（内部工具恒为开放）
     * @param context 运行时信息
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, AccessControl access,
                                      ApiContext context) throws IOException {
        return start(bind, access, context, null, null, null);
    }

    /**
     * 启动服务器，可选托管静态前端。
     *
     * @param bind    绑定地址
     * @param access  访问控制（内部工具恒为开放）
     * @param context 运行时信息
     * @param webRoot 静态资源根目录；{@code null} 表示不托管
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, AccessControl access,
                                      ApiContext context, Path webRoot) throws IOException {
        return start(bind, access, context, webRoot, null, null);
    }

    /**
     * 启动服务器，可选托管静态前端并把报文事件推给 SSE 订阅者。
     *
     * @param bind    绑定地址
     * @param access  访问控制（内部工具恒为开放）
     * @param context 运行时信息
     * @param webRoot 静态资源根目录；{@code null} 表示不托管
     * @param bus     事件总线；{@code null} 表示不推报文
     * @return 已启动的服务器
     * @throws IOException 绑定失败
     */
    public static HttpApiServer start(InetSocketAddress bind, AccessControl access,
                                      ApiContext context, Path webRoot, EventBus bus) throws IOException {
        return start(bind, access, context, webRoot, bus, null);
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
        /* 每连接一个任务：虚拟线程模式下线程数不再受 keep-alive 空闲连接影响 */
        ExecutorService pool = VIRTUAL_THREADS
                ? Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("http-", 1).factory())
                : Executors.newFixedThreadPool(REQUEST_THREADS, runnable -> {
                    Thread thread = new Thread(runnable, "http-" + reqSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });

        HttpApiServer api = new HttpApiServer(http, pool, context, access, audit,
                normalizeRoot(webRoot),
                Long.getLong("secssim.sse.stallTimeoutMs", SSE_STALL_TIMEOUT_DEFAULT_MS));
        http.setExecutor(pool);

        if (bus != null) {
            api.eventSubscription = bus.subscribe(api::broadcast);
        }

        api.registerRoutes(http);
        api.startWatchdog();

        http.start();
        return api;
    }

    /** 路由表：每个端点挂到对应资源域的处理器上。 */
    private void registerRoutes(HttpServer http) {
        http.createContext(ApiPaths.HEALTH, withCors(this::handleHealth));
        http.createContext(ApiPaths.EVENTS, withCors(this.streams::handleEvents));
        http.createContext(ApiPaths.TICKET, withCors(this.streams::handleTicket));
        http.createContext(ApiPaths.SHUTDOWN, withCors(this::handleShutdown));
        http.createContext(ApiPaths.SESSIONS, withCors(this.sessions::handleSessions));
        http.createContext(ApiPaths.SESSIONS_BULK, withCors(this.sessions::handleBulkSessions));
        http.createContext(ApiPaths.SESSION_PREFIX, withCors(this.sessions::handleSessionPath));
        http.createContext(ApiPaths.CONFIG_PREVIEW, withCors(this.configs::handleConfigPreview));
        http.createContext(ApiPaths.CONFIGS, withCors(this.configs::handleConfigs));
        /* 批量新建设备必须注册在 CONFIG_PREFIX 之前：HttpServer 按最长前缀匹配，
           但 /api/v1/devices/bulk 与 /api/v1/configs 不共享前缀，顺序只为可读 */
        http.createContext(ApiPaths.DEVICES_BULK, withCors(this.configs::createDevicesBulk));
        http.createContext(ApiPaths.CONFIG_PREFIX, withCors(this.configs::handleConfigPath));
        http.createContext(ApiPaths.CONFIG_TEMPLATES, withCors(this.configs::handleConfigTemplates));
        http.createContext(ApiPaths.TEMPLATES, withCors(this.configs::handleTemplates));
        http.createContext(ApiPaths.TEMPLATE_PREFIX, withCors(this.configs::handleTemplatePath));
        http.createContext(ApiPaths.PREVIEW, withCors(this.previews::handlePreview));
        http.createContext(ApiPaths.OVERVIEW, withCors(this.ops::handleOverview));
        http.createContext(ApiPaths.PREVIEW_BINARY, withCors(this.previews::handlePreviewBinary));
        http.createContext(ApiPaths.MESSAGES, withCors(this.ops::handleMessages));
        http.createContext(ApiPaths.AUDIT, withCors(this.ops::handleAudit));
        http.createContext(ApiPaths.LOG_LEVEL, withCors(this.ops::handleLogLevel));
        /* 集合路径要注册在实例前缀之前：HttpServer 按注册顺序匹配上下文，
           "/api/v1/host-sims"（无尾斜杠）先撞上前缀处理器，
           处理器按 "/host-sims/" 切分得到空数组，实例 id 取不到就直接崩。 */
        http.createContext(ApiPaths.HOST_SIMS, withCors(this.hostSims::handleHostSims));
        http.createContext(ApiPaths.HOST_SIM_PREFIX, withCors(this.hostSims::handleHostSimPath));
        /* 3.0 v2：主机独立命名空间（设计 §2.5） */
        http.createContext(ApiPaths.HOSTS_V2, withCors(this.hostSims::handleHostsV2));
        http.createContext(ApiPaths.HOST_V2_PREFIX, withCors(this.hostSims::handleHostV2Path));
        http.createContext(ApiPaths.HOST_TEMPLATES_V2, withCors(this.hostSims::handleHostTemplatesV2));
        http.createContext(ApiPaths.DEVICE_V2_PREFIX, withCors(this.configs::handleDeviceV2));
        http.createContext(ApiPaths.HOST_TEMPLATE_V2_PREFIX, withCors(this.hostSims::handleHostTemplateV2Path));
        /* 参数模板库（所有设备共享）：集合在前、单个在前缀之后（同 host-templates） */
        http.createContext(ApiPaths.PARAM_TEMPLATES_V2, withCors(this.configs::handleParamTemplatesV2));
        http.createContext(ApiPaths.PARAM_TEMPLATE_V2_PREFIX, withCors(this.configs::handleParamTemplateV2Path));

        /* 根上下文兜底；HttpServer 按最长前缀匹配，因此不会抢走 /api/v1/*。
           没有前端目录（纯 API 模式）时也要注册：否则拼错的路径会落到 JDK
           自带的 HTML 404 页面，调用方拿到一坨 HTML 还得自己猜状态。 */
        http.createContext("/", withCors(this::handleStatic));
    }

    /** 启动 SSE 看门狗：巡检写停滞的连接。 */
    private void startWatchdog() {
        this.streams.startWatchdog(SSE_WATCHDOG_TICK_MS);
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
        return this.streams.activeStreams();
    }

    /**
     * 订阅状态通知总线（schema v6:SSE 类型化事件）。
     *
     * <p>与报文总线并行:通知以 {@code event: <type>} 帧推送,
     * 报文事件保持 {@code event: message} 不变。</p>
     *
     * @param bus 通知总线;{@code null} 忽略
     */
    public void subscribeNotices(NoticeBus bus) {
        if (bus != null) {
            this.noticeSubscription = bus.subscribe(this::broadcastNotice);
        }
    }

    /** 把一条状态通知推给所有订阅者;队列满同样丢最旧。 */
    private void broadcastNotice(NoticeEvent event) {
        this.streams.broadcastNotice(event);
    }

    /** 把一条报文事件推给所有 SSE 订阅者。 */
    private void broadcast(MessageEvent event) {
        this.streams.broadcast(event);
    }

    /** 停止服务并释放线程。 */
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
        this.streams.shutdown();
        this.slowExecutor.shutdownNow();
        this.deadlines.shutdownNow();
        this.executor.shutdownNow();
        try {
            this.executor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 健康检查：直连访问，直接给出完整运行信息（探针、启动脚本与界面都靠它握手）。
     */
    private void handleHealth(HttpExchange ex) throws IOException {
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !head) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        long uptime = Duration.between(this.startedAt, Instant.now()).toMillis();
        AccessControl.Principal principal = this.access.authenticate();

        /* 这段是拼进 JSON 对象的片段，必须以逗号开头。实现方写错（或塞了裸换行、
           引号）时整份 health 就不是合法 JSON 了，调用方连版本号都读不到——
           宁可丢掉这段附加字段，也不能把健康检查弄坏。 */
        String extra = this.context.extraHealthJson();
        if (extra == null || extra.isEmpty()) {
            extra = "";
        } else if (!extra.startsWith(",")) {
            Log.error("extraHealthJson 必须以逗号开头，已忽略: " + extra);
            extra = "";
        }

        respond(ex, 200, "{\"ok\":true"
                + ",\"version\":\"" + HttpSupport.json(this.context.version()) + "\""
                + ",\"mode\":\"" + HttpSupport.json(this.context.mode()) + "\""
                + ",\"user\":\"" + HttpSupport.json(principal.name()) + "\""
                + ",\"role\":\"" + HttpSupport.json(principal.role().name().toLowerCase(Locale.ROOT)) + "\""
                + ",\"uptimeMs\":" + uptime
                + extra
                + "}");
    }

    /** 关机：仅管理员。 */
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
        /* 读类资源接受 HEAD：探测存在性/大小是标准用法，之前一律 405。
           405 必须带 Allow 头，否则客户端不知道这个路径支持什么方法。 */
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !head) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            respond(ex, 405, "{\"ok\":false,\"error\":\"method-not-allowed\"}");
            return;
        }

        String path = ex.getRequestURI().getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/index.html";
        }

        if (this.webRoot == null) {
            /* 纯 API 模式（没有 --web-root）：任何非 API 路径都按 JSON 404 回 */
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }

        /* /api 下没有注册的路径不能回落到 index.html：客户端把端点拼错了
           应该看到 JSON 404，而不是 200 + 一页 HTML（那会让调用方以为请求成功）。 */
        if (path.equals("/api") || path.startsWith("/api/")) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"not-found\"}");
            return;
        }

        Path target;
        try {
            target = this.webRoot.resolve(path.substring(1)).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            /* 百分号解码后可能留下 NUL 等非法字符（%00）：resolve 直接抛
               RuntimeException，落到最外层就是 500。这是路径非法，不是服务端故障。 */
            respond(ex, 400, "{\"ok\":false,\"error\":\"bad-path\"}");
            return;
        }
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

    /** 静态资源根目录：转成绝对的真实路径，前缀校验才有意义。 */
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

    /** 最后一段是否带扩展名（{@code /a/b.js} 是，{@code /a/b} 不是）。 */
    private static boolean hasExtension(String path) {
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return last.lastIndexOf('.') > 0;
    }

    /** 静态资源的 Content-Type：按扩展名猜，未知类型按二进制流。 */
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

    /**
     * 给处理器包一层跨域放行。
     *
     * <p>内网工具定位：不做鉴权，跨域全放行——任意来源的请求都照常处理，
     * 响应统一带 {@code Access-Control-Allow-Origin: *}。预检（OPTIONS）一律 204，
     * 预检放行的请求头包含保存配置用的 {@code If-Match}。</p>
     */
    private HttpHandler withCors(HttpHandler handler) {
        return ex -> {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            /* If-Match 是保存配置时的并发保护头：预检不放行它保存必失败 */
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization, Content-Type, If-Match");

            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }

            /* 浏览器侧的跨站写拦截。服务端不设凭据是既定定位，但「打开任意恶意网页
               就能 POST 到 localhost 关停服务或改配置」是另一回事：没有凭据可校验、
               预检也放行，浏览器不会替我们挡。这里按 Origin / Sec-Fetch-Site 拒绝
               跨站写请求；curl、脚本、SDK 这类没有 Origin 的客户端不受影响。 */
            if (isCrossSiteWrite(ex)) {
                respond(ex, 403, "{\"ok\":false,\"error\":\"cross-origin-write-blocked\"}");
                return;
            }

            if (isSlowRequest(ex)) {
                /* 慢路由交给专用池：请求线程立刻返回，health/轮询/取票不再排在它们后面。 */
                try {
                    this.slowExecutor.execute(() -> invokeLogged(handler, ex));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    respond(ex, 503, "{\"ok\":false,\"error\":\"busy\"}");
                }
                return;
            }

            invokeLogged(handler, ex);
        };
    }

    /**
     * 执行处理器并写一行访问日志。
     *
     * <p>挂在 {@link #withCors} 这个唯一入口上，所有 handler 自动覆盖，不需要
     * 每个 handler 自己记。慢请求（≥1s）升 WARN，与"慢请求专用池"的判定口径一致。</p>
     *
     * <p>这一条同时解决两件事：正常请求第一次有了记录（出安全事件能取证），
     * 以及慢请求第一次可见。</p>
     */
    private void invokeLogged(HttpHandler handler, HttpExchange ex) {
        /* 全局并发门：超过上限立刻 503，不排队占内存 */
        if (!this.inFlight.tryAcquire()) {
            try {
                respond(ex, 503, "{\"ok\":false,\"error\":\"busy\"}");
            } catch (IOException e) {
                ex.close();
            }
            return;
        }
        long startNanos = System.nanoTime();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        try {
            HttpSupport.clearLastStatus();
            invokeHandler(handler, ex);
        } finally {
            this.inFlight.release();
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = HttpSupport.lastStatus();
            if (millis >= SLOW_REQUEST_MS) {
                ACCESS.warn("{} {} {} {}ms", method, path, status, millis);
            } else {
                ACCESS.info("{} {} {} {}ms", method, path, status, millis);
            }
        }
    }

    /**
     * 执行处理器：挂截止时间、兜底 500、结束后取消定时。
     *
     * @param handler 处理器
     * @param ex      交换
     */
    private void invokeHandler(HttpHandler handler, HttpExchange ex) {
            java.util.concurrent.ScheduledFuture<?> deadline = scheduleDeadline(ex);
            try {
                handler.handle(ex);
            } catch (Exception e) {
                /* 处理器抛异常时至少回一个 500：直接吞掉的话连接会被无解释地断开，
                   客户端只看到"连接被意外关闭"，连是哪个端点都无从判断。 */
                Log.error("HTTP 处理失败（" + ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + "）", e);
                try {
                    byte[] body = "{\"ok\":false,\"error\":\"internal-error\"}".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    ex.sendResponseHeaders(500, body.length);
                    ex.getResponseBody().write(body);
                } catch (IOException ignored) {
                    /* 响应已经发过或连接已断：没什么可做的 */
                } finally {
                    ex.close();
                }
            } finally {
                if (deadline != null) {
                    deadline.cancel(false);
                }
            }
    }

    /**
     * 是否是浏览器发起的跨站写请求。
     *
     * <p>服务端是内部工具、不设凭据，但浏览器端的跨站请求仍会被发出去：
     * 恶意页面可以对 {@code http://localhost:PORT} 发简单请求（预检也被放行），
     * 无凭据可校验就无从拒绝。这里只挡写操作——读是公开状态，挡了反而影响排障。</p>
     *
     * @param ex 交换
     * @return 跨站写返回 true
     */
    private static boolean isCrossSiteWrite(HttpExchange ex) {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            /* curl / 脚本 / SDK 没有 Origin：本工具的常规用法，放行 */
            return false;
        }
        if (isTrustedOrigin(origin, ex)) {
            return false;
        }
        String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        /* Sec-Fetch-Site 明确不是跨站时（same-origin / same-site）给一次豁免，
           应对个别环境里 Origin 与 Host 端口写法不一致的情况 */
        return site == null || !"same-origin".equalsIgnoreCase(site);
    }

    /**
     * 判断 Origin 是否可信：同主机，或桌面壳的 webview 来源。
     *
     * @param origin Origin 头
     * @param ex     交换（取 Host）
     * @return 可信返回 true
     */
    private static boolean isTrustedOrigin(String origin, HttpExchange ex) {
        String lower = origin.trim().toLowerCase(Locale.ROOT);
        if ("null".equals(lower)) {
            /* file:// 页面或 sandbox iframe：不能当同源 */
            return false;
        }
        /* Tauri 的 webview 来源（Windows 用 http(s)://tauri.localhost，其余 tauri://localhost） */
        if (lower.equals("tauri://localhost")
                || lower.startsWith("http://tauri.localhost")
                || lower.startsWith("https://tauri.localhost")) {
            return true;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(origin.trim());
            String originHost = uri.getHost();
            if (originHost == null) {
                return false;
            }
            /* 只比主机名：本机工具里端口可能被代理/端口转发改写，
               比 host:port 会把正常用法误判成跨站 */
            String requestHost = host.trim().toLowerCase(Locale.ROOT);
            int colon = requestHost.lastIndexOf(':');
            if (colon > 0 && requestHost.indexOf(']') < colon) {
                requestHost = requestHost.substring(0, colon);
            }
            return originHost.equalsIgnoreCase(requestHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 慢路由：等 T3、建连、编译、大历史序列化。 */
    private static boolean isSlowRequest(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (path == null) {
            return false;
        }
        if (ApiPaths.EVENTS.equals(path)) {
            return false;
        }
        if (ApiPaths.HOST_SIMS.equals(path) || path.startsWith(ApiPaths.HOST_SIM_PREFIX)) {
            return true;
        }
        if (path.equals(ApiPaths.CONFIG_PREVIEW) || path.equals(ApiPaths.MESSAGES)) {
            return true;
        }
        return path.endsWith("/message")
                || path.endsWith("/reload")
                || path.endsWith("/burst")
                || path.endsWith("/validate")
                || path.contains("/host-sim");
    }

    /**
     * 给请求挂一个截止时间；SSE 是长连接，由看门狗负责，这里跳过。
     *
     * @param ex 请求
     * @return 定时任务；{@code null} 表示不设截止
     */
    private java.util.concurrent.ScheduledFuture<?> scheduleDeadline(HttpExchange ex) {
        if (REQUEST_TIMEOUT_MS <= 0L) {
            return null;
        }
        final String path = ex.getRequestURI().getPath();
        if (ApiPaths.EVENTS.equals(path)) {
            return null;
        }
        return this.deadlines.schedule(() -> {
            try {
                Log.warn("请求超时，主动关闭连接: " + ex.getRequestMethod() + " " + path);
                ex.close();
            } catch (RuntimeException ignored) {
                /* 交换已经结束：关闭失败无需处理 */
            }
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /* ===== 下面几个薄封装:让本类不必继承 HttpSupport 也能用共享设施 ===== */

    /** 要求调用方至少具备某角色（直连访问下恒为管理员）。 */
    private boolean requireRole(HttpExchange ex, ApiRole required) throws IOException {
        AccessControl.Principal principal = this.access.authenticate();
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

    /** 记录一条审计；直连访问下主体恒为 {@code local}。 */
    private void audit(HttpExchange ex, String action, String target, String detail) {
        AuditSink sink = this.audit;
        if (sink == null) {
            return;
        }
        AccessControl.Principal principal = this.access.authenticate();
        if (principal == null) {
            return;
        }
        sink.record(principal.name(),
                principal.role().name().toLowerCase(Locale.ROOT),
                action, target, detail);
    }

    /** 回 JSON 正文。委托 {@link HttpSupport#respond}，让访问日志能读到 LAST_STATUS。 */
    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        /* 以前这里自己 sendResponseHeaders，不写 HttpSupport.LAST_STATUS——
           health/shutdown/静态资源/慢池 503/兜底 500 的访问日志状态码恒为 0，
           取证时"他拿到了什么"对这些路径失效。统一走 HttpSupport（评审 P3-1）。 */
        HttpSupport.respond(ex, status, body);
    }
}
