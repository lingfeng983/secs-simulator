package com.shimizukenta.secssim.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.shimizukenta.secssim.app.event.NoticeEvent;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.sun.net.httpserver.HttpExchange;

/**
 * 流式推送域：一次性票据、SSE 连接、订阅者管理与看门狗。
 *
 * <p>票据与 SSE 是一对——{@code EventSource} 不能带请求头，只能用 URL 上的
 * 一次性票据换取连接，所以两者放在一起，状态不外泄。</p>
 */
final class StreamHandler extends HttpSupport {

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
     * 测试可用 {@code -Dsecssim.sse.stallTimeoutMs=...} 调小。</p>
     */
    private static final long SSE_STALL_TIMEOUT_DEFAULT_MS = 60_000L;

    /** 看门狗巡检间隔。 */
    private static final long SSE_WATCHDOG_TICK_MS = 5_000L;

    /** 每个 SSE 订阅者的待发帧上限；满了丢最旧的一帧。 */
    private static final int SSE_QUEUE_FRAMES = 256;

    /** SSE 线程数上限：每个连接一根线程，超出直接 503，而不是无限堆积。 */
    private static final int SSE_THREADS_MAX = 64;

    /** 票据随机源。 */
    private static final SecureRandom TICKET_RANDOM = new SecureRandom();

    /** 一次性票据：ticket -> 票据。 */
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    /** 活跃订阅者。 */
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** SSE 专用线程池。 */
    private final ExecutorService sseExecutor;

    /** 看门狗：巡检写停滞的连接。 */
    private final ScheduledExecutorService watchdog;

    /** 停滞判定阈值。 */
    private final long stallTimeoutMs;

    StreamHandler(ApiContext context, AccessControl access, AuditSink audit,
                  ExecutorService sseExecutor, ScheduledExecutorService watchdog, long stallTimeoutMs) {
        super(context, access, audit);
        this.sseExecutor = sseExecutor;
        this.watchdog = watchdog;
        this.stallTimeoutMs = stallTimeoutMs;
    }


    /**
     * 签发一次性 SSE 票据。
     *
     * <p>{@code EventSource} 不能设置请求头，只能把凭据放进 URL。直接放口令会进
     * 访问日志和浏览器历史，而且长期有效；改成 30 秒、只能用一次的票据，
     * 即使被记下来也基本无法利用。
     * </p>
     */
    void handleTicket(HttpExchange ex) throws IOException {
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
     * SSE 入口：用一次性票据鉴权（{@code EventSource} 无法带请求头），
     * 然后把长连接交给 SSE 专用池。
     *
     * <p>票据只能由通过鉴权的账号换取，因此持有票据即至少 {@link ApiRole#VIEWER}，
     * 这里不再重复判角色——报文流是只读的。
     * </p>
     */
    void handleEvents(HttpExchange ex) throws IOException {
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


    /** 看门狗：把长时间写不动的连接踢掉，避免占着线程和队列。 */
    void sweepStalledStreams() {
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


    /**
     * 把一条报文事件推给所有 SSE 订阅者。
     *
     * <p>订阅者队列满了就丢最旧的一帧：界面宁可缺几条，也不能把内存拖爆或阻塞会话。
     * </p>
     */
    void broadcast(MessageEvent event) {
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


    /** 把一条状态通知推给所有订阅者;队列满同样丢最旧。 */
    void broadcastNotice(com.shimizukenta.secssim.app.event.NoticeEvent event) {
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

    static final class Subscriber {

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

    private record Ticket(long expiresAtMs, AccessControl.Principal principal) {
    }

    /** 启动看门狗：巡检写停滞的连接。 */
    void startWatchdog(long tickMs) {
        this.watchdog.scheduleWithFixedDelay(this::sweepStalledStreams, tickMs, tickMs, TimeUnit.MILLISECONDS);
    }

    /** 当前活跃的 SSE 连接数（测试与诊断用）。 */
    int activeStreams() {
        return this.subscribers.size();
    }

    /** 关闭 SSE 相关资源。 */
    void shutdown() {
        this.watchdog.shutdownNow();
        this.sseExecutor.shutdownNow();
    }
}
