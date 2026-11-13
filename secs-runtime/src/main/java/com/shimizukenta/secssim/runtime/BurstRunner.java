package com.shimizukenta.secssim.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.SessionManager;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;

/**
 * 压测引擎：把"发 N 条、按间隔节流"排队到固定的后台线程上跑。
 *
 * <p>从 {@code RuntimeContext} 拆出来的第二块。压测有自己的线程模型、
 * 预算与取消语义（{@link #BURST_BUDGET_MS}、有界队列、会话关闭即取消），
 * 与会话查询/配置读写这些"读一下就回"的接口不是一类东西。</p>
 *
 * <p>Thread.sleep 节流绝不能占 HTTP 请求线程：请求线程只做参数校验并返回 202，
 * 统计从 {@code GET /sessions/{id}/burst?status=true} 取。</p>
 */
final class BurstRunner {

    /** 压测总时长预算：到点中止剩余条目，避免一次请求把后台线程占住几小时。 */
    private static final long BURST_BUDGET_MS = 5 * 60_000L;

    /** 压测执行器线程数。 */
    private static final int BURST_THREADS = 2;

    /** 等待执行的压测任务队列上限；满了直接拒绝，不无限排队。 */
    private static final int BURST_QUEUE = 4;

    private final SessionManager sessions;

    /** 主机实例注册表：主机级压测按实例 id 找模拟器发报文。 */
    private final HostSimRegistry hosts;

    /**
     * 压测执行器：线程数固定、队列有界——队列满时直接拒绝（回 burst-busy），
     * 而不是让请求排队等到统计已经失去意义。
     */
    private final ThreadPoolExecutor executor;

    /** 会话 → 最近一次压测结果/运行状态（GET /sessions/{id}/burst 用）。 */
    private final ConcurrentMap<String, BurstState> states = new ConcurrentHashMap<>();

    /** 主机实例 → 最近一次压测结果/运行状态（GET /host-sims/{id}/burst 用）。 */
    private final ConcurrentMap<String, BurstState> hostStates = new ConcurrentHashMap<>();

    BurstRunner(SessionManager sessions, HostSimRegistry hosts) {
        this.sessions = sessions;
        this.hosts = hosts;

        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                BURST_THREADS, BURST_THREADS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(BURST_QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "burst-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * 压测入口（同时是 {@code GET /sessions/{id}/burst?status=true} 的实现）。
     *
     * @param sessionId   会话
     * @param requestJson 请求体
     * @return 结果/受理 JSON；会话不存在返回 {@code null}（HTTP 404）
     */
    String burst(String sessionId, String requestJson) {
        SessionRuntime session = this.sessions.find(sessionId).orElse(null);
        if (session == null) {
            return null;
        }

        com.shimizukenta.jsonhub.JsonHub request;
        try {
            request = com.shimizukenta.jsonhub.JsonHub.fromJson(requestJson);
        } catch (RuntimeException e) {
            return "{\"ok\":false,\"error\":\"bad-request\"}";
        }
        if (!request.isObject()) {
            return "{\"ok\":false,\"error\":\"bad-request\"}";
        }

        /* GET /sessions/{id}/burst 复用同一个入口：只回最近一次结果/运行中状态，不起新压测。 */
        com.shimizukenta.jsonhub.JsonHub status = request.get("status");
        if (status != null && status.optionalBoolean().orElse(false)) {
            BurstState state = this.states.get(sessionId);
            return state == null ? "{\"ok\":true,\"running\":false,\"accepted\":false}" : state.statusJson();
        }

        if (!(session instanceof HsmsSessionRuntime runtime)) {
            return "{\"ok\":false,\"error\":\"unsupported-session\"}";
        }

        /* 可选字段缺失时 JsonHub.get 返回 null：直接 .optionalInt() 会 NPE 变成 500。
           压测请求里 intervalMs 之类本来就允许省略，这里逐个兜底。 */
        String kind = burstString(request, "kind", "");
        int count = burstInt(request, "count", 1);
        long intervalMs = burstLong(request, "intervalMs", 0L);
        if (count < 1 || count > 5000) {
            return "{\"ok\":false,\"error\":\"count-out-of-range\"}";
        }
        if (intervalMs < 0 || intervalMs > 60_000L) {
            return "{\"ok\":false,\"error\":\"interval-out-of-range\"}";
        }

        /* 参数校验必须在请求线程里做完：后台任务再报错，调用方已经拿到 202 了。 */
        String sml = "";
        int ceid = 0;
        int alid = 0;
        String text = "";
        int category = 4;
        switch (kind) {
            case "message" -> {
                sml = burstString(request, "sml", "");
                if (sml.isBlank()) {
                    return "{\"ok\":false,\"error\":\"missing-sml\"}";
                }
            }
            case "ceid" -> {
                ceid = burstInt(request, "ceid", 0);
                if (ceid < 1) {
                    return "{\"ok\":false,\"error\":\"missing-ceid\"}";
                }
            }
            case "alarm" -> {
                alid = burstInt(request, "alid", 0);
                if (alid < 1) {
                    return "{\"ok\":false,\"error\":\"missing-alid\"}";
                }
                text = burstString(request, "text", "");
                category = burstInt(request, "category", 4);
            }
            default -> {
                return "{\"ok\":false,\"error\":\"unknown-kind\"}";
            }
        }

        /* 离线时 emitAlarm 只是入队、真实发送在 ACK 线程里失败：
           不先挡一道，报警风暴会把"什么都没发出去"报成成功。
           与单发的 triggerAlarm 用同一条判据，两条路径才不会互相矛盾。 */
        boolean sendable = runtime.state() == SessionState.OPEN && runtime.hasPeer();

        BurstState state = this.states.computeIfAbsent(sessionId, key -> new BurstState());
        synchronized (state) {
            if (state.running) {
                return "{\"ok\":false,\"error\":\"burst-running\"}";
            }
            /* 先写好进度字段再置 running：GET 看到 running=true 时 startedAt/count 一定可见 */
            state.startedAt = System.currentTimeMillis();
            state.count = count;
            state.running = true;
        }

        /* lambda 只能捕获 effectively final 的局部变量，先抄一份。 */
        final String burstKind = kind;
        final String burstSml = sml;
        final int burstCeid = ceid;
        final int burstAlid = alid;
        final String burstText = text;
        final int burstCategory = category;

        try {
            this.executor.execute(() -> {
                long startedAt = System.currentTimeMillis();
                try {
                    BurstStats stats = runBurst(count, intervalMs, sendable,
                            () -> state.cancelRequested, index -> {
                        switch (burstKind) {
                            case "message" -> runtime.sendSml(burstSml);
                            case "ceid" -> runtime.emitCeid(burstCeid);
                            case "alarm" -> runtime.emitAlarm(burstAlid, index % 2 == 0, burstText, burstCategory);
                            default -> { }
                        }
                    });
                    state.finish(burstResultJson(stats, count, System.currentTimeMillis() - startedAt));
                } catch (RuntimeException e) {
                    Log.error("压测执行异常（会话 " + sessionId + "）", e);
                    state.finish("{\"ok\":false,\"error\":\"burst-failed\",\"sent\":0,\"failed\":0"
                            + ",\"durationMs\":" + (System.currentTimeMillis() - startedAt)
                            + ",\"latencyP50\":0,\"latencyP95\":0,\"latencyMax\":0}");
                } finally {
                    /* 就算抛出 Error 也不能让 running 永远为 true，否则这个会话再也压测不了 */
                    state.running = false;
                }
            });
        } catch (RejectedExecutionException e) {
            state.running = false;
            return "{\"ok\":false,\"error\":\"burst-busy\"}";
        }

        /* 202：后台已受理。字段不能少——前端仍按 BurstResult 解析，
           缺字段会显示 undefined；真实统计随后从 GET 取。 */
        return "{\"ok\":true,\"accepted\":true,\"running\":true"
                + ",\"count\":" + count
                + ",\"sent\":0,\"failed\":0,\"skipped\":0,\"aborted\":false"
                + ",\"durationMs\":0,\"latencyP50\":0,\"latencyP95\":0,\"latencyMax\":0}";
    }

    /**
     * 取消并遗忘一个会话的压测状态。
     *
     * <p>会话被关时调用：在跑的压测继续对着已关闭会话发，会把 2 个槽位占死；
     * 状态留着也会让 map 随会话增删无限增长。</p>
     */
    void cancel(String sessionId) {
        BurstState state = this.states.remove(sessionId);
        if (state != null) {
            state.cancelRequested = true;
        }
    }

    /**
     * 主机实例的报文压测（{@code kind=message} 且目标是一台主机实例）。
     *
     * <p>与会话级压测共用执行器与预算：主机模拟页的"压测发送"就是"以主机角色
     * 连发 N 条 SML"，长耗时同样不能挂在 HTTP 线程上。远程主机的对端不在本进程，
     * 只能走这条路。</p>
     *
     * @param hostId      主机实例 id
     * @param requestJson {@code {"kind":"message","sml":"...","count":100,"intervalMs":0}}
     *                    ；带 {@code "status":true} 时只回状态
     * @return 受理/状态 JSON；实例不存在返回 {@code null}（HTTP 404）
     */
    String burstHost(String hostId, String requestJson) {
        HostSimRegistry.HostHandle handle = this.hosts.handle(hostId);
        if (handle == null) {
            return null;
        }

        com.shimizukenta.jsonhub.JsonHub request;
        try {
            request = com.shimizukenta.jsonhub.JsonHub.fromJson(requestJson);
        } catch (RuntimeException e) {
            return "{\"ok\":false,\"error\":\"bad-request\"}";
        }
        if (!request.isObject()) {
            return "{\"ok\":false,\"error\":\"bad-request\"}";
        }

        com.shimizukenta.jsonhub.JsonHub status = request.get("status");
        if (status != null && status.optionalBoolean().orElse(false)) {
            BurstState state = this.hostStates.get(hostId);
            return state == null ? "{\"ok\":true,\"running\":false,\"accepted\":false}" : state.statusJson();
        }

        String kind = burstString(request, "kind", "message");
        if (!"message".equals(kind)) {
            /* 主机实例只发报文：CEID/报警是设备侧能力，主机没有对应语义 */
            return "{\"ok\":false,\"error\":\"unsupported-kind\"}";
        }
        String sml = burstString(request, "sml", "");
        if (sml.isBlank()) {
            return "{\"ok\":false,\"error\":\"missing-sml\"}";
        }
        int count = burstInt(request, "count", 1);
        long intervalMs = burstLong(request, "intervalMs", 0L);
        if (count < 1 || count > 5000) {
            return "{\"ok\":false,\"error\":\"count-out-of-range\"}";
        }
        if (intervalMs < 0 || intervalMs > 60_000L) {
            return "{\"ok\":false,\"error\":\"interval-out-of-range\"}";
        }
        /* 发送前先把 SML 解析一遍：错误要在请求线程里返回 400，
           后台任务再报错调用方已经拿到 202 了。 */
        try {
            com.shimizukenta.secssim.app.sml.SmlParser.parse(sml);
        } catch (com.shimizukenta.secssim.app.sml.SmlParseException e) {
            return "{\"ok\":false,\"error\":\"bad-sml\",\"message\":\"" + jsonEscape(e.getMessage()) + "\"}";
        }

        BurstState state = this.hostStates.computeIfAbsent(hostId, key -> new BurstState());
        synchronized (state) {
            if (state.running) {
                return "{\"ok\":false,\"error\":\"burst-running\"}";
            }
            state.startedAt = System.currentTimeMillis();
            state.count = count;
            state.running = true;
        }

        final String burstSml = sml;
        try {
            this.executor.execute(() -> {
                long startedAt = System.currentTimeMillis();
                try {
                    BurstStats stats = runBurst(count, intervalMs, handle.open(),
                            () -> state.cancelRequested, index -> handle.send(burstSml));
                    state.finish(burstResultJson(stats, count, System.currentTimeMillis() - startedAt));
                } catch (RuntimeException e) {
                    Log.error("主机压测执行异常（实例 " + hostId + "）", e);
                    state.finish("{\"ok\":false,\"error\":\"burst-failed\",\"sent\":0,\"failed\":0"
                            + ",\"durationMs\":" + (System.currentTimeMillis() - startedAt)
                            + ",\"latencyP50\":0,\"latencyP95\":0,\"latencyMax\":0}");
                } finally {
                    state.running = false;
                }
            });
        } catch (RejectedExecutionException e) {
            state.running = false;
            return "{\"ok\":false,\"error\":\"burst-busy\"}";
        }

        return "{\"ok\":true,\"accepted\":true,\"running\":true"
                + ",\"count\":" + count
                + ",\"sent\":0,\"failed\":0,\"skipped\":0,\"aborted\":false"
                + ",\"durationMs\":0,\"latencyP50\":0,\"latencyP95\":0,\"latencyMax\":0}";
    }

    /** 主机实例被关时取消它的压测（实例已不存在，状态留着只会让 map 增长）。 */
    void cancelHost(String hostId) {
        BurstState state = this.hostStates.remove(hostId);
        if (state != null) {
            state.cancelRequested = true;
        }
    }

    /** 最小 JSON 字符串转义（错误信息要带回 HTTP 响应）。 */
    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 停机：取消全部在跑的压测并关闭执行器。 */
    void close() {
        /* 两张表都要取消：只清会话表的话，正在跑的主机级压测会在执行器关掉后
           继续往一条已经断开的链路上发报文，直到 5 分钟预算耗尽 */
        for (BurstState state : this.states.values()) {
            state.cancelRequested = true;
        }
        this.states.clear();
        for (BurstState state : this.hostStates.values()) {
            state.cancelRequested = true;
        }
        this.hostStates.clear();
        this.executor.shutdownNow();
    }

    /** 单会话压测状态：最近一次完成的统计 + 是否运行中。 */
    private static final class BurstState {

        volatile boolean running;

        /** 取消请求：会话被关或整体停机时置位，压测在下一轮检查后退出。 */
        volatile boolean cancelRequested;

        volatile long startedAt;
        volatile int count;
        volatile String lastResult = "{\"ok\":true,\"running\":false,\"accepted\":false}";

        synchronized void finish(String result) {
            this.lastResult = result;
            this.running = false;
        }

        String statusJson() {
            if (this.running) {
                return "{\"ok\":true,\"accepted\":true,\"running\":true"
                        + ",\"count\":" + this.count
                        + ",\"startedAt\":" + this.startedAt + "}";
            }
            return this.lastResult;
        }
    }

    /** 压测动作：第 {@code index} 条（0 起）。 */
    @FunctionalInterface
    private interface BurstAction {

        void perform(int index) throws Exception;
    }

    /** 一次压测的统计中间量。 */
    private static final class BurstStats {

        final List<Long> latencies = new ArrayList<>();
        int failed;
        int skipped;
        boolean aborted;
    }

    /** 在后台线程里跑一条压测：总时长超预算就中止剩余条目。 */
    private static BurstStats runBurst(int count, long intervalMs, boolean sendable,
                                       BooleanSupplier cancelled, BurstAction action) {
        BurstStats stats = new BurstStats();
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + BURST_BUDGET_MS;
        for (int i = 0; i < count; i++) {
            if (cancelled.getAsBoolean()) {
                stats.skipped = count - i;
                stats.aborted = true;
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                stats.skipped = count - i;
                stats.aborted = true;
                break;
            }
            long t0 = System.currentTimeMillis();
            if (!sendable) {
                stats.failed++;
            } else {
                try {
                    action.perform(i);
                    stats.latencies.add(System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    stats.failed++;
                }
            }
            if (!pace(intervalMs, startedAt, i, deadline)) {
                stats.skipped = count - i - 1;
                stats.aborted = true;
                break;
            }
        }
        return stats;
    }

    /** 统计 JSON；sent/failed/durationMs/latencyP50/P95/Max 与前端 BurstResult 对齐。 */
    private static String burstResultJson(BurstStats stats, int count, long durationMs) {
        List<Long> latencies = stats.latencies;
        latencies.sort(Long::compare);
        IntFunction<Long> pick = rank -> latencies.isEmpty()
                ? 0L : latencies.get(Math.min(latencies.size() - 1, rank));
        return "{\"ok\":true,\"accepted\":true,\"running\":false"
                + ",\"count\":" + count
                + ",\"sent\":" + latencies.size()
                + ",\"failed\":" + stats.failed
                + ",\"skipped\":" + stats.skipped
                + ",\"aborted\":" + stats.aborted
                + ",\"durationMs\":" + durationMs
                + ",\"latencyP50\":" + pick.apply((int) (latencies.size() * 0.5))
                + ",\"latencyP95\":" + pick.apply((int) (latencies.size() * 0.95))
                + ",\"latencyMax\":" + pick.apply(latencies.size() - 1)
                + "}";
    }

    /** 压测节奏：按起始时刻对齐间隔，且不越过总时长预算。 */
    private static boolean pace(long intervalMs, long startedAt, int index, long deadline) {
        if (intervalMs <= 0L) {
            return System.currentTimeMillis() < deadline;
        }
        long nextAt = startedAt + (index + 1L) * intervalMs;
        if (nextAt >= deadline) {
            return false;
        }
        long wait = nextAt - System.currentTimeMillis();
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 可选整数：字段缺失时给默认值（JsonHub.get 对缺失键返回 null）。 */
    private static int burstInt(com.shimizukenta.jsonhub.JsonHub request, String key, int fallback) {
        com.shimizukenta.jsonhub.JsonHub value = request.get(key);
        return value == null ? fallback : value.optionalInt().orElse(fallback);
    }

    /** 可选长整数：字段缺失时给默认值。 */
    private static long burstLong(com.shimizukenta.jsonhub.JsonHub request, String key, long fallback) {
        com.shimizukenta.jsonhub.JsonHub value = request.get(key);
        return value == null ? fallback : value.optionalLong().orElse(fallback);
    }

    /** 可选字符串：字段缺失时给默认值。 */
    private static String burstString(com.shimizukenta.jsonhub.JsonHub request, String key, String fallback) {
        com.shimizukenta.jsonhub.JsonHub value = request.get(key);
        return value == null ? fallback : value.optionalString().orElse(fallback);
    }
}
