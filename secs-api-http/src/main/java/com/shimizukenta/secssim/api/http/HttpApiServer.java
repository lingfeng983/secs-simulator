package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final Duration SSE_HEARTBEAT = Duration.ofSeconds(1L);
    private static final Duration SSE_MAX_LIFETIME = Duration.ofMinutes(30L);

    private final HttpServer server;
    private final ExecutorService executor;
    private final String token;
    private final ApiContext context;
    private final Instant startedAt = Instant.now();

    private HttpApiServer(HttpServer server, ExecutorService executor, String token, ApiContext context) {
        this.server = server;
        this.executor = executor;
        this.token = token;
        this.context = context;
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

        HttpApiServer api = new HttpApiServer(http, pool, token, context);
        http.setExecutor(pool);

        http.createContext(PATH_HEALTH, api::handleHealth);
        http.createContext(PATH_EVENTS, api::handleEvents);
        http.createContext(PATH_SHUTDOWN, api::handleShutdown);

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

    @Override
    public void close() {
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

    private void handleEvents(HttpExchange ex) throws IOException {
        if (!this.authorized(ex)) {
            respond(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }

        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);

        Instant deadline = Instant.now().plus(SSE_MAX_LIFETIME);
        try (OutputStream out = ex.getResponseBody()) {
            while (Instant.now().isBefore(deadline)) {
                out.write(("event: heartbeat\ndata: " + System.currentTimeMillis() + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    Thread.sleep(SSE_HEARTBEAT.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException clientGone) {
            /* 客户端断开是常态，静默退出 */
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
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
