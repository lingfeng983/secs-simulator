package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.app.sml.SmlParser;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.PlanAction;
import com.shimizukenta.secssim.store.PlanExecutionRepository;

/**
 * 编排执行器（3.0 需求 5，设计 §2.7.2）。
 *
 * <p>一次触发 = 一条台账：幂等预留 → 可发判定（离线策略）→ 施加效果 →
 * 逐条动作发送 → 台账提交。台账经 {@link PlanExecutionRepository} 异步批量
 * 落库（发送线程零同步 DB 写）。顺序契约：效果一定在组装之前，
 * 组装一定在发送之前——S6F11 报文体读到的是变化后的值。</p>
 *
 * <p>可选装配（由 {@code HsmsSessionRuntime} 注入）：{@link EffectController}
 * 负责效果施加/回滚与 before→after 证据，{@link ActionResolver} 负责把五种动作
 * 解析成可发送的报文，{@code ackSender} 让 W 位发送走会话的单线程 ACK 队列。
 * 三者缺省时执行器仍可独立工作（供单测与 2.x 语义使用）。</p>
 */
public final class PlanExecutor {

    /** 台账与状态计数落地。 */
    public interface Sink {
        /** @param execution 台账行 */
        void commit(PlanExecutionRepository.Execution execution);

        /** @param planId 计划 id  @param succeeded 是否真的发送  @param error 错误描述 */
        void recordFire(String planId, boolean succeeded, String error);

        /**
         * 发布一条 {@code plan-fired} 通知（由装配层做 200ms 合并节流）。
         *
         * @param payloadJson 单条执行摘要（JSON 对象文本）
         * @param immediate   {@code status=FAILED} 时为 true：不合并、立即推送
         */
        default void publish(String payloadJson, boolean immediate) {
            /* 未接 SSE 的实现保持静默 */
        }
    }

    /** 一次效果施加的证据（台账 effects 字段 + 回滚句柄）。 */
    public record EffectResult(String json, Object token, boolean rollbackable) {
    }

    /**
     * 效果控制：施加值效果，并在发送全部失败时按边界撤销。
     *
     * <p>{@code rollbackable} 只由「本次效果全是 set/add」决定；是否真的能撤，
     * 由 {@link #rollback} 再检查「此后没有其它 fire 触碰过这些 SVID」。</p>
     */
    public interface EffectController {
        /** @param ceid 采集事件号  @param planId 计划 id（手动触发为 null） @return 效果证据 */
        EffectResult apply(int ceid, String planId);

        /** @param token {@link EffectResult#token()} @return rolled_back / rollback_skipped */
        String rollback(Object token);
    }

    /** 动作解析器：把一条动作变成待发送报文（含 SML 占位符求值）。 */
    public interface ActionResolver {
        /**
         * @param action 动作
         * @param ceid   采集事件号
         * @param atMs   触发时刻
         * @return 报文
         * @throws Exception 解析/组装失败（记为该动作 FAILED）
         */
        SmlParser.Message resolve(PlanAction action, int ceid, long atMs) throws Exception;
    }

    /** 一次触发可用的会话依赖（重连补跑时整体换成当前连接）。 */
    public record Dependencies(SecsCommunicator comm, boolean online, Runnable applyEffects,
                               Secs2BodySupplier s6f11Body, boolean s6f11Wbit) {
    }

    /** 离线排队的一条触发。 */
    private record Pending(EventPlan plan, String planId, int ceid, long atMs) {
    }

    /** 幂等窗口：同键 60 秒内视为重复触发。 */
    private static final long IDEMPOTENT_WINDOW_MS = 60_000L;

    /** 离线有界队列上限（不是 Spooling，进程重启即丢）。 */
    public static final int QUEUE_CAPACITY = 256;

    private final String deviceId;
    private final Sink sink;
    private final ConcurrentMap<String, Long> reserved = new ConcurrentHashMap<>();
    private final AtomicLong stepSequence = new AtomicLong();
    private final AtomicLong replaySequence = new AtomicLong();
    private final Deque<Pending> pending = new ArrayDeque<>();

    private volatile EffectController effectController;
    private volatile ActionResolver actionResolver;
    private volatile Supplier<ExecutorService> ackSender;
    private volatile Consumer<Exception> errorSink = ignored -> { };

    /** @param deviceId 设备 id（台账归属） @param sink 台账落地 */
    public PlanExecutor(String deviceId, Sink sink) {
        this.deviceId = deviceId;
        this.sink = sink;
    }

    /** @param controller 效果控制（before→after / 回滚） */
    public void effectController(EffectController controller) {
        this.effectController = controller;
    }

    /** @param resolver 动作解析器 */
    public void actionResolver(ActionResolver resolver) {
        this.actionResolver = resolver;
    }

    /** @param sender W 位发送用的单线程 ACK 队列 */
    public void ackSender(Supplier<ExecutorService> sender) {
        this.ackSender = sender;
    }

    /** @param sink 异步发送异常的记录口 */
    public void errorSink(Consumer<Exception> sink) {
        this.errorSink = sink == null ? ignored -> { } : sink;
    }

    private enum Status {
        SENT, PARTIAL, FAILED, SKIPPED,
    }

    /**
     * 执行一次计划触发。
     *
     * @param plan          计划（动作列表为空 = 默认一条 S6F11）
     * @param planId        触发它的计划 id；手动触发传 {@code null}
     * @param ceid          采集事件号
     * @param atMs          触发时刻（调度锚点）
     * @param comm          连接；null = 离线
     * @param online        是否在线（控制状态 ONLINE 才算可发）
     * @param applyEffects  效果施加回调（先于组装）
     * @param s6f11Body     默认 S6F11 报文体组装
     * @param s6f11Wbit     默认 S6F11 是否带 W
     * @throws IOException 同步路径上的发送异常（W 位队列满等；台账已记）
     */
    public void execute(EventPlan plan, String planId, int ceid, long atMs,
                        SecsCommunicator comm, boolean online,
                        Runnable applyEffects,
                        Secs2BodySupplier s6f11Body,
                        boolean s6f11Wbit) throws IOException {
        executeInternal(plan, planId, ceid, atMs,
                new Dependencies(comm, online, applyEffects, s6f11Body, s6f11Wbit),
                false, null, atMs);
    }

    /**
     * 单步试跑：走完整执行器，台账 {@code dry_run=1}、幂等键追加
     * {@code |step|<seq>}；不计 fire_count、不推进调度倒计时（设计 §2.5）。
     */
    public void step(EventPlan plan, String planId, int ceid, long atMs,
                     SecsCommunicator comm, boolean online,
                     Runnable applyEffects,
                     Secs2BodySupplier s6f11Body,
                     boolean s6f11Wbit) throws IOException {
        executeInternal(plan, planId, ceid, atMs,
                new Dependencies(comm, online, applyEffects, s6f11Body, s6f11Wbit),
                true, "step|" + this.stepSequence.incrementAndGet(), atMs);
    }

    private void executeInternal(EventPlan plan, String planId, int ceid, long atMs,
                                 Dependencies deps,
                                 boolean dryRun, String keySuffix,
                                 long buildAtMs) throws IOException {
        String baseKey = this.deviceId + "|" + (planId == null ? "manual" : planId) + "|" + atMs;
        final String key = keySuffix == null ? baseKey : baseKey + "|" + keySuffix;
        final Long previous = this.reserved.put(key, System.currentTimeMillis());
        if (previous != null && System.currentTimeMillis() - previous < IDEMPOTENT_WINDOW_MS) {
            /* 幂等命中：直接跳过，不重复发、不重复记 */
            return;
        }
        this.reserved.entrySet().removeIf(entry ->
                System.currentTimeMillis() - entry.getValue() > IDEMPOTENT_WINDOW_MS * 2);

        final String ledgerPlanId = planId == null ? "manual" : planId;

        /* 1. 离线策略：不在线/未连接时按计划策略处理 */
        if (deps.comm() == null || !deps.online()) {
            String policy = planId == null ? EventPlan.OFFLINE_SKIP
                    : plan.offlinePolicy().toLowerCase(Locale.ROOT);
            if (EventPlan.OFFLINE_QUEUE.equals(policy)) {
                /* 有界内存队列（≤256），重连后 FIFO 补跑；
                   不是 Spooling，不落 S2F43–F48，进程重启即丢。 */
                enqueuePending(new Pending(plan, planId, ceid, atMs), key);
                return;
            }
            if (EventPlan.OFFLINE_FAIL.equals(policy)) {
                commit(key, ledgerPlanId, ceid, atMs, dryRun, Status.FAILED,
                        "[]", "[]", "设备离线（offlinePolicy=fail）", "device-offline");
                return;
            }
            commit(key, ledgerPlanId, ceid, atMs, dryRun, Status.SKIPPED,
                    "[]", "[]", "设备离线（skip）", "device-offline");
            return;
        }

        runOnline(plan, planId, ceid, atMs, key, ledgerPlanId, deps, dryRun, buildAtMs);
    }

    /** 在线执行：效果 → 逐条组装/发送 → 结果判定 → （可选）回滚 → 台账。 */
    private void runOnline(EventPlan plan, String planId, int ceid, long scheduledAt,
                           String key, String ledgerPlanId, Dependencies deps,
                           boolean dryRun, long buildAtMs) {
        /* 2. 施加效果（先于组装：报文体读到变化后的值） */
        EffectController controller = this.effectController;
        EffectResult effects;
        if (controller != null) {
            effects = controller.apply(ceid, planId);
        } else {
            deps.applyEffects().run();
            effects = new EffectResult("[]", null, false);
        }

        /* 3. 逐条动作发送 */
        List<PlanAction> actions = plan.hasCustomActions() ? plan.actions() : List.of(PlanAction.s6f11());
        List<String> messages = new ArrayList<>();
        int sent = 0;
        int failed = 0;
        String lastError = "";

        for (PlanAction action : actions) {
            long startedAt = System.currentTimeMillis();
            try {
                SmlParser.Message message = resolve(action, ceid, buildAtMs, deps);
                send(deps.comm(), message);
                messages.add(messageEntry(action.kind(), message.stream(), message.function(),
                        message.wbit(), startedAt, "", ""));
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed++;
                lastError = "发送被中断";
                messages.add(failedEntry(action.kind(), lastError));
            } catch (Exception e) {
                failed++;
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                messages.add(failedEntry(action.kind(), lastError));
            }
        }

        /* 4. 结果：全部成功 SENT / 部分 PARTIAL / 全失败 FAILED */
        Status status = failed == 0 ? Status.SENT
                : sent > 0 ? Status.PARTIAL : Status.FAILED;
        String effectsJson = effects.json() == null ? "[]" : effects.json();
        if (status == Status.FAILED && !dryRun
                && EventPlan.FAILURE_ROLLBACK.equals(plan.onSendFailure().toLowerCase(Locale.ROOT))) {
            /* PARTIAL 一律不回滚；含 pulse/shift/resume 的计划在解析期就不可回滚 */
            String mark = effects.rollbackable() && controller != null
                    ? controller.rollback(effects.token()) : "rollback_skipped";
            effectsJson = appendRollbackMark(effectsJson, mark);
            lastError = appendError(lastError, mark);
        }
        commit(key, ledgerPlanId, ceid, scheduledAt, dryRun, status,
                messages.toString(), effectsJson, lastError, lastError);
    }

    /** 动作 → 报文：注入解析器优先；动作级 wbit 覆盖解析结果与会话默认值。 */
    private SmlParser.Message resolve(PlanAction action, int ceid, long atMs, Dependencies deps)
            throws Exception {
        SmlParser.Message message;
        ActionResolver resolver = this.actionResolver;
        if (resolver != null) {
            message = resolver.resolve(action, ceid, atMs);
        } else {
            message = switch (action.kind()) {
                case PlanAction.KIND_S6F11 -> new SmlParser.Message(
                        6, 11, deps.s6f11Wbit(), deps.s6f11Body().get(ceid, atMs));
                case PlanAction.KIND_SML -> SmlParser.parse(action.sml());
                default -> throw new IllegalStateException(
                        "动作类型 " + action.kind() + " 未接发送器（请用 sml 动作）");
            };
        }
        if (action.wbit() != null) {
            message = new SmlParser.Message(message.stream(), message.function(),
                    action.wbit(), message.body());
        }
        return message;
    }

    /** W 位发送进单线程 ACK 队列，不阻塞事件心跳；队列满视为该动作失败。 */
    private void send(SecsCommunicator comm, SmlParser.Message message) throws Exception {
        if (message.wbit() && this.ackSender != null) {
            ExecutorService pool = this.ackSender.get();
            if (pool == null) {
                throw new IllegalStateException("W 位 ACK 队列不可用");
            }
            try {
                pool.execute(() -> {
                    try {
                        comm.send(message.stream(), message.function(), true, message.body());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        this.errorSink.accept(new IllegalStateException(
                                "等待 S" + message.stream() + "F" + message.function() + " 回复被中断", e));
                    } catch (Exception e) {
                        this.errorSink.accept(e);
                    }
                });
            } catch (RejectedExecutionException e) {
                throw new IOException("W 位 ACK 队列已满: S" + message.stream()
                        + "F" + message.function(), e);
            }
            return;
        }
        comm.send(message.stream(), message.function(), message.wbit(), message.body());
    }

    /** 离线队列入队；超过 256 丢最旧并给它记一条 SKIPPED 台账（不静默丢）。 */
    private void enqueuePending(Pending item, String key) {
        Pending dropped = null;
        synchronized (this.pending) {
            if (this.pending.size() >= QUEUE_CAPACITY) {
                dropped = this.pending.pollFirst();
            }
            this.pending.addLast(item);
        }
        if (dropped != null) {
            String dropKey = this.deviceId + "|" + dropped.planId() + "|" + dropped.atMs()
                    + "|overflow|" + this.replaySequence.incrementAndGet();
            commit(dropKey, dropped.planId(), dropped.ceid(), dropped.atMs(), false,
                    Status.SKIPPED, "[]", "[]",
                    "离线队列已满（256），丢弃最早触发；不是 Spooling，不实现 S2F43–F48",
                    "offline-queue-overflow");
        }
    }

    /**
     * 重连后按 FIFO 补跑离线队列（设计 §2.7.2）。
     *
     * <p>台账 {@code scheduled_at} 记原触发时刻便于追溯，但效果与报文体一律按
     * 补跑时刻 {@code now} 施加/求值（pulse/decay 是时间函数，用旧时刻会错乱）。
     * 队列是内存态，进程重启即丢。</p>
     *
     * @param deps 当前在线会话的依赖
     * @return 补跑条数
     */
    public int flushPending(Dependencies deps) {
        if (deps == null || deps.comm() == null || !deps.online()) {
            return 0;
        }
        int replayed = 0;
        while (true) {
            Pending item;
            synchronized (this.pending) {
                item = this.pending.pollFirst();
            }
            if (item == null) {
                break;
            }
            String key = this.deviceId + "|" + (item.planId() == null ? "manual" : item.planId())
                    + "|" + item.atMs() + "|queued|" + this.replaySequence.incrementAndGet();
            runOnline(item.plan(), item.planId(), item.ceid(), item.atMs(), key,
                    item.planId() == null ? "manual" : item.planId(), deps, false,
                    System.currentTimeMillis());
            replayed++;
        }
        return replayed;
    }

    /** 离线队列里等待补跑的条数（诊断用）。 */
    public int pendingCount() {
        synchronized (this.pending) {
            return this.pending.size();
        }
    }

    /** 记一条台账 + 触发计数 + SSE 摘要，三件事都只做内存/异步操作。 */
    private void commit(String key, String planId, int ceid, long atMs, boolean dryRun,
                        Status status, String messagesJson, String effectsJson,
                        String error, String fireError) {
        PlanExecutionRepository.Execution ledger = execution(key, planId, ceid, atMs, dryRun,
                status, messagesJson, effectsJson, error);
        this.sink.commit(ledger);
        if (!dryRun) {
            this.sink.recordFire(planId, status == Status.SENT, fireError);
        }
        /* FAILED 立即推，其余交给装配层的 200ms 合并窗口（设计 §2.4.3） */
        this.sink.publish(noticePayload(ledger), status == Status.FAILED);
    }

    private static String messageEntry(String kind, int stream, int function, boolean wbit,
                                       long startedAt, String note, String ack) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"kind\":\"").append(kind).append('"')
                .append(",\"stream\":").append(stream)
                .append(",\"function\":").append(function)
                .append(",\"wbit\":").append(wbit)
                .append(",\"sentAt\":").append(System.currentTimeMillis())
                .append(",\"elapsedMs\":").append(System.currentTimeMillis() - startedAt);
        if (!ack.isEmpty()) {
            sb.append(",\"ack\":\"").append(escape(ack)).append('"');
        }
        if (note != null && !note.isEmpty()) {
            sb.append(",\"error\":\"").append(escape(note)).append('"');
        }
        return sb.append("}").toString();
    }

    private static String failedEntry(String kind, String error) {
        return "{\"kind\":\"" + escape(kind) + "\",\"error\":\"" + escape(error) + "\"}";
    }

    private static String appendRollbackMark(String effectsJson, String mark) {
        String text = effectsJson == null || effectsJson.isBlank() ? "[]" : effectsJson.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            return "{\"items\":" + text + ",\"rollback\":\"" + escape(mark) + "\"}";
        }
        return text;
    }

    private static String appendError(String previous, String extra) {
        return previous == null || previous.isEmpty() ? extra : previous + "; " + extra;
    }

    /** 单条执行摘要（合并窗口里一帧携带多条这样的对象）。 */
    private static String noticePayload(PlanExecutionRepository.Execution e) {
        return "{\"deviceId\":" + quote(e.deviceId())
                + ",\"executionId\":" + quote(e.idempotencyKey())
                + ",\"planId\":" + quote(e.planId())
                + ",\"ceid\":" + e.ceid()
                + ",\"status\":" + quote(e.status())
                + ",\"dryRun\":" + e.dryRun()
                + ",\"messages\":" + (e.messageJson() == null ? "[]" : e.messageJson())
                + ",\"effects\":" + (e.effectJson() == null ? "[]" : e.effectJson())
                + ",\"at\":" + e.firedAt() + "}";
    }

    private static String quote(String text) {
        return "\"" + escape(text) + "\"";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private PlanExecutionRepository.Execution execution(String key, String planId, int ceid,
                                                        long atMs, boolean dryRun, Status status,
                                                        String messagesJson, String effectsJson,
                                                        String error) {
        long now = System.currentTimeMillis();
        return new PlanExecutionRepository.Execution(
                this.deviceId, planId, ceid, key,
                dryRun, atMs, now, status.name(),
                messagesJson, effectsJson, error == null ? "" : error);
    }

    /** 默认 S6F11 报文体组装回调（ceid + 求值时刻）。 */
    @FunctionalInterface
    public interface Secs2BodySupplier {
        /** @param ceid 采集事件号  @param atMs 求值时刻  @return 报文体  @throws Exception 组装失败 */
        Secs2 get(int ceid, long atMs) throws Exception;
    }

    /** 查一台设备是否已有执行记录（幂等查询用）。 */
    public Optional<Long> lastReserveAt(String planId, long atMs) {
        return Optional.ofNullable(this.reserved.get(this.deviceId + "|" + planId + "|" + atMs));
    }
}
