package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.ArrayList;
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
 * <p>一次触发 = 一条台账：幂等预留 → 可发判定（离线策略）→ 逐条动作发送
 * → 台账提交。台账经 {@link PlanExecutionRepository} 异步批量落库
 * （发送线程零同步 DB 写）。</p>
 *
 * <p>2026-11-16 设计（M2 去补跑）：{@code offlinePolicy=queue} 与整条补跑链路
 * （离线队列 / flush / replay / 溢出台账）已删除——调度层在不在线时直接清场，
 * 触发静默丢弃（D8）；发送失败记 FAILED，不重试不补发。W 位异步失败
 * （T3 超时 / 中断）补一条 {@code FAILED} 台账（新幂等键后缀 {@code |ack|seq}，
 * D7）并更新计划 last_error。</p>
 *
 * <p><b>事件不改值</b>（schema v7）：触发只上报，报文体里的值由数据项自己的
 * {@code source} 决定。台账的 effects 字段保留为空数组——DB 列不动，
 * 界面也不再展示这一列。</p>
 *
 * <p>可选装配（由 {@code HsmsSessionRuntime} 注入）：{@link ActionResolver}
 * 负责把动作解析成可发送的报文，{@code ackSender} 让 W 位发送走会话的
 * 单线程 ACK 队列。缺省时执行器仍可独立工作（供单测使用）。</p>
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

    /**
     * 一次触发可用的会话依赖。
     *
     * <p>补跑链路删除后，{@code online=false} 只剩 manual / step 两条入口可达：
     * 调度路径在不在线时由 pumpEvents 清场，根本不会走到这里（D8）。</p>
     */
    public record Dependencies(SecsCommunicator comm, boolean online,
                               Secs2BodySupplier s6f11Body, boolean s6f11Wbit) {
    }

    /** 幂等窗口：同键 60 秒内视为重复触发。 */
    private static final long IDEMPOTENT_WINDOW_MS = 60_000L;

    private final String deviceId;
    private final Sink sink;
    private final ConcurrentMap<String, Long> reserved = new ConcurrentHashMap<>();
    /** 幂等表清理节拍（每 256 次执行扫一次，见 {@code executeInternal}）。 */
    private final AtomicLong pruneTick = new AtomicLong();
    private final AtomicLong stepSequence = new AtomicLong();
    /** W 位异步失败的台账序号（幂等键后缀 |ack|seq，D7）。 */
    private final AtomicLong ackSequence = new AtomicLong();

    private volatile ActionResolver actionResolver;
    private volatile Supplier<ExecutorService> ackSender;
    private volatile Consumer<Exception> errorSink = ignored -> { };

    /** @param deviceId 设备 id（台账归属） @param sink 台账落地 */
    public PlanExecutor(String deviceId, Sink sink) {
        this.deviceId = deviceId;
        this.sink = sink;
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
     * @param s6f11Body     默认 S6F11 报文体组装
     * @param s6f11Wbit     默认 S6F11 是否带 W
     * @throws IOException 同步路径上的发送异常（W 位队列满等；台账已记）
     */
    public void execute(EventPlan plan, String planId, int ceid, long atMs,
                        SecsCommunicator comm, boolean online,
                        Secs2BodySupplier s6f11Body,
                        boolean s6f11Wbit) throws IOException {
        executeInternal(plan, planId, ceid, atMs,
                new Dependencies(comm, online, s6f11Body, s6f11Wbit),
                false, null, atMs);
    }

    /**
     * 单步试跑：走完整执行器，台账 {@code dry_run=1}、幂等键追加
     * {@code |step|<seq>}；不计 fire_count、不推进调度倒计时（设计 §2.5）。
     */
    public void step(EventPlan plan, String planId, int ceid, long atMs,
                     SecsCommunicator comm, boolean online,
                     Secs2BodySupplier s6f11Body,
                     boolean s6f11Wbit) throws IOException {
        executeInternal(plan, planId, ceid, atMs,
                new Dependencies(comm, online, s6f11Body, s6f11Wbit),
                true, "step|" + this.stepSequence.incrementAndGet(), atMs);
    }

    private void executeInternal(EventPlan plan, String planId, int ceid, long atMs,
                                 Dependencies deps,
                                 boolean dryRun, String keySuffix,
                                 long buildAtMs) throws IOException {
        String baseKey = this.deviceId + "|" + (planId == null ? "manual" : planId) + "|" + atMs;
        final String key = keySuffix == null ? baseKey : baseKey + "|" + keySuffix;
        final long now = System.currentTimeMillis();
        /* 幂等判定必须是原子的：先 put 再回读会出现"两个线程都看到窗口外的时间戳、
           都往下执行"的重复发送。compute 里一次决定，并把时间戳的推进也限定在
           真正执行的那一次上。 */
        final boolean[] reservedNow = new boolean[1];
        this.reserved.compute(key, (ignored, previous) -> {
            if (previous != null && now - previous < IDEMPOTENT_WINDOW_MS) {
                reservedNow[0] = false;
                return previous;
            }
            reservedNow[0] = true;
            return now;
        });
        if (!reservedNow[0]) {
            /* 幂等命中：直接跳过，不重复发、不重复记 */
            return;
        }
        /* 清理改成批量做：原先每次执行都 removeIf 全表扫，而计划周期最小 1ms，
           条目数随运行时长线性增长，摊到每次执行就是 O(n²)，且 removeIf 期间
           整张表被锁住。 */
        long ticks = this.pruneTick.incrementAndGet();
        if ((ticks & 0xFF) == 0 || this.reserved.size() > 8192) {
            this.reserved.entrySet().removeIf(entry -> now - entry.getValue() > IDEMPOTENT_WINDOW_MS * 2);
        }

        final String ledgerPlanId = planId == null ? "manual" : planId;

        /* 1. 离线策略：不在线/未连接时按计划策略处理。
           queue 已废弃（M2 去补跑）：解析层保留该值但按 skip 处理并给迁移 Issue。 */
        if (deps.comm() == null || !deps.online()) {
            String policy = planId == null ? EventPlan.OFFLINE_SKIP
                    : plan.offlinePolicy().toLowerCase(Locale.ROOT);
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

    /** 在线执行：逐条组装/发送 → 结果判定 → 台账。 */
    private void runOnline(EventPlan plan, String planId, int ceid, long scheduledAt,
                           String key, String ledgerPlanId, Dependencies deps,
                           boolean dryRun, long buildAtMs) {
        /* 2. 逐条动作发送（事件不改值：报文体直接按触发时刻求值） */
        List<PlanAction> actions = plan.hasCustomActions() ? plan.actions() : List.of(PlanAction.s6f11());
        List<String> messages = new ArrayList<>();
        int sent = 0;
        int failed = 0;
        String lastError = "";

        for (PlanAction action : actions) {
            long startedAt = System.currentTimeMillis();
            try {
                SmlParser.Message message = resolve(action, ceid, buildAtMs, deps);
                send(deps.comm(), message, ledgerPlanId, ceid, scheduledAt, dryRun);
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

        /* 3. 结果：全部成功 SENT / 部分 PARTIAL / 全失败 FAILED */
        Status status = failed == 0 ? Status.SENT
                : sent > 0 ? Status.PARTIAL : Status.FAILED;
        commit(key, ledgerPlanId, ceid, scheduledAt, dryRun, status,
                messages.toString(), "[]", lastError, lastError);
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

    /**
     * 发送：W 位进单线程 ACK 队列（不阻塞事件心跳）；队列满视为该动作失败。
     *
     * <p>异步发送失败（T3 超时 / 被中断）走 {@link #recordAckFailure}：
     * 台账补一条 FAILED（新幂等键 {@code |ack|seq}）+ 计划 last_error，
     * 不重试不补发（D7）。原行在入队时刻已按 SENT 提交——异步失败不翻旧行，
     * 只追加一条排障证据。</p>
     */
    private void send(SecsCommunicator comm, SmlParser.Message message,
                      String ledgerPlanId, int ceid, long atMs, boolean dryRun) throws Exception {
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
                        this.recordAckFailure(ledgerPlanId, ceid, atMs, dryRun,
                                "等待 S" + message.stream() + "F" + message.function() + " 回复被中断");
                    } catch (Exception e) {
                        String error = e.getMessage() == null ? e.toString() : e.getMessage();
                        this.recordAckFailure(ledgerPlanId, ceid, atMs, dryRun, error);
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

    /** W 位异步失败：补一条 FAILED 台账（幂等键 {@code |ack|seq}）+ fire 计数 / last_error。 */
    private void recordAckFailure(String ledgerPlanId, int ceid, long atMs, boolean dryRun, String error) {
        String key = this.deviceId + "|" + ledgerPlanId + "|" + atMs
                + "|ack|" + this.ackSequence.incrementAndGet();
        /* 先落台账再报错：errorSink 的订阅方（测试 / 通知）看到失败时，证据已经在 */
        commit(key, ledgerPlanId, ceid, atMs, dryRun, Status.FAILED,
                "[]", "[]", error, error);
        this.errorSink.accept(new IllegalStateException(error));
    }

    /* 离线补跑（enqueuePending / flushPending / pendingCount）已删除——M2 去补跑。
       设备不在线时调度层清场（D8），发送失败不重试。 */
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
