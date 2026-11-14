package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.jsonhub.JsonObjectPair;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * 采集事件上报（S6F11）。
 *
 * <p>所有触发路径（定时、条件、链式、手动）都从这里走一遍：施加计划效果、
 * 组装 S6F11、发出去。三件事放在一处，是为了让"效果只施加一次"这条语义
 * 不被各条路径搞乱。</p>
 */
final class EventReporter {

    /** 编排执行器(3.0 需求 5);null = 走 2.x 语义(不发台账)。 */
    private volatile PlanExecutor planExecutor;
    /** 是否在线(控制状态 ONLINE);由会话在装配时接入。 */
    private volatile java.util.function.BooleanSupplier onlineCheck = () -> true;

    /**
     * 组装器：按 CEID 拼出 S6F11。
     *
     * <p>用 Supplier 而不是实例：设备模型热生效时 {@code HsmsSessionRuntime} 会整个换掉
     * 组装器，注入实例的话这里会永远拿着旧模型——改完单元/报告后 S6F11 的结构与界面不一致。</p>
     */
    private final java.util.function.Supplier<S6F11Assembler> assembler;

    /** 通信器。 */
    private final java.util.function.Supplier<SecsCommunicator> communicator;

    /** S6F11 的 ACK 队列：W 位发送会阻塞到 S6F12 或 T3。 */
    private final java.util.function.Supplier<ExecutorService> ackSender;

    /** 效果记录：计划里的值变化在这里落地。 */
    private final com.shimizukenta.secssim.runtime.session.EventEffectStore effects;

    /**
     * 编排表。
     *
     * <p>必须是<b>实时</b>表（{@code EventScheduler::previewSchedule}）：运行时增删改计划
     * 只改调度器内部那份，拿装载时的快照会出现“刚加的计划效果不施加、已删的计划还在改值”。
     * 预览表已排除被禁用的计划，所以这里不再单独过滤。</p>
     */
    private final java.util.function.Supplier<EventSchedule> schedule;

    /** 该 CEID 是否被主机侧禁用（S2F37）。 */
    private final java.util.function.IntPredicate ceidEnabled;

    /** 记一次"被禁用而丢弃"。 */
    private final Runnable recordSkipped;

    /** 记一次事件触发（速率统计）。 */
    private final Runnable recordFire;

    /** 发布 S6F11 通知。 */
    private final java.util.function.BiConsumer<String, String> notice;

    /** 当前时钟（毫秒）。 */
    private final java.util.function.LongSupplier clock;

    /** S6F11 是否带 W 位。 */
    private final java.util.function.BooleanSupplier wbit;

    /** 已发条数。 */
    private final AtomicLong emitted = new AtomicLong();

    /** 因 ACK 队列满而丢弃的条数。 */
    private final AtomicLong wbitDropped = new AtomicLong();

    /** 会话 id（错误日志用）。 */
    private final String sessionId;

    /** 出错回调。 */
    private final java.util.function.Consumer<Exception> errorSink;

    /** 读 SVID 当前值（台账的 before→after 证据；NaN = 取不到值）。 */
    private volatile java.util.function.IntToDoubleFunction valueReader = svid -> Double.NaN;

    EventReporter(java.util.function.Supplier<S6F11Assembler> assembler,
                  java.util.function.Supplier<SecsCommunicator> communicator,
                  java.util.function.Supplier<ExecutorService> ackSender,
                  com.shimizukenta.secssim.runtime.session.EventEffectStore effects,
                  java.util.function.Supplier<EventSchedule> schedule,
                  java.util.function.IntPredicate ceidEnabled,
                  Runnable recordSkipped,
                  Runnable recordFire,
                  java.util.function.BiConsumer<String, String> notice,
                  java.util.function.LongSupplier clock,
                  java.util.function.BooleanSupplier wbit,
                  String sessionId,
                  java.util.function.Consumer<Exception> errorSink) {
        this.assembler = assembler;
        this.communicator = communicator;
        this.ackSender = ackSender;
        this.effects = effects;
        this.schedule = schedule;
        this.ceidEnabled = ceidEnabled;
        this.recordSkipped = recordSkipped;
        this.recordFire = recordFire;
        this.notice = notice;
        this.clock = clock;
        this.wbit = wbit;
        this.sessionId = sessionId;
        this.errorSink = errorSink;
    }

    /**
     * 挂 SVID 实时读值（台账的 before→after 证据）。
     *
     * <p>用 setter 而不是构造参数：构造签名是 2.x 起就被同包测试与其它
     * 装配点引用的契约，扩参数会让只改了调用方的增量编译现场炸成
     * {@code NoSuchMethodError}。</p>
     *
     * @param reader 读值回调；NaN 表示该 SVID 取不到值
     */
    void valueReader(java.util.function.IntToDoubleFunction reader) {
        this.valueReader = reader == null ? svid -> Double.NaN : reader;
    }

    /** 已发条数。 */
    long emitted() {
        return this.emitted.get();
    }

    /** 因 ACK 队列满而丢弃的条数。 */
    long wbitDropped() {
        return this.wbitDropped.get();
    }

    /**
     * 上报一次采集事件（S6F11）。
     *
     * @param ceid   采集事件号
     * @param planId 触发它的计划 id；手动触发传入 {@code null}
     * @throws IOException 会话未打开或发送失败
     */
    void emit(int ceid, String planId) throws IOException {
        SecsCommunicator comm = this.communicator.get();
        if (comm == null) {
            throw new IOException("session not open: " + this.sessionId);
        }

        if (!this.ceidEnabled.test(ceid)) {
            /* 被 S2F37 禁用：直接丢弃，不缓存（见 ADR 0006） */
            this.recordSkipped.run();
            return;
        }

        /* 3.0:每次触发走编排执行器(效果 → 动作发送 → 台账一条);
           计划动作列表为空时执行器发送默认 S6F11,语义与 2.x 一致 */
        PlanExecutor executor = this.planExecutor;
        if (executor != null) {
            EventPlan firedPlan = null;
            for (EventPlan plan : this.schedule.get().plans()) {
                if (plan.ceid() != ceid) {
                    continue;
                }
                if (planId != null && !plan.id().equals(planId)) {
                    continue;
                }
                firedPlan = plan;
                break;
            }
            /* 找不到计划(手动触发/编排表漂移)时按无动作 = 默认 S6F11 处理 */
            EventPlan effective = firedPlan != null ? firedPlan
                    : new EventPlan(planId == null ? "manual" : planId, ceid,
                            new com.shimizukenta.secssim.core.event.Trigger.Periodic(60_000L, 0L));
            long atMs = this.clock.getAsLong();
            executor.execute(effective, planId, ceid, atMs, comm, this.onlineCheck.getAsBoolean(),
                    () -> this.applyPlanEffects(ceid, planId),
                    (ceidArg, atMsArg) -> this.assembler.get().build(ceidArg, atMsArg),
                    this.wbit.getAsBoolean());
            this.publishS6f11Notice(ceid, planId);
            return;
        }

        this.applyPlanEffects(ceid, planId);
        this.publishS6f11Notice(ceid, planId);

        Secs2 body;
        try {
            body = this.assembler.get().build(ceid, this.clock.getAsLong());
        } catch (Exception e) {
            throw new IOException("组装 S6F11 失败: " + ceid, e);
        }

        if (this.wbit.getAsBoolean()) {
            /* W 位发送会阻塞到 S6F12 或 T3；放到单线程队列，事件心跳照常跑 */
            try {
                this.ackSender.get().execute(() -> {
                    try {
                        comm.send(6, 11, true, body);
                        this.emitted.incrementAndGet();
                        this.recordFire.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        this.errorSink.accept(new IllegalStateException("等待 S6F12 被中断: " + ceid));
                    } catch (Exception e) {
                        this.errorSink.accept(e);
                        Log.error("会话 " + this.sessionId + " 等待 S6F12 失败: " + ceid, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                this.wbitDropped.incrementAndGet();
                throw new IOException("S6F11 ACK 队列已满: " + ceid, e);
            }
            return;
        }

        try {
            comm.send(6, 11, false, body);
            this.emitted.incrementAndGet();
            this.recordFire.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("发送 S6F11 被中断: " + ceid, e);
        } catch (Exception e) {
            throw new IOException("发送 S6F11 失败: " + ceid, e);
        }
    }

    /**
     * 单步试跑一条计划（3.0 v2 {@code runtime/plans/{planId}/step}）。
     *
     * <p>不经过 {@code EventScheduler}，因此不推进倒计时、不占限流额度，
     * 台账记 {@code dry_run=1}、不计 fire_count（设计 §2.5）。会话未连接也允许：
     * 执行器会按离线策略记一条 SKIPPED/FAILED 台账，界面能看到"为什么没发"。</p>
     *
     * @param planId 计划 id
     * @throws IOException 计划不存在、执行器未接线或 S2F37 已禁用该 CEID
     */
    void step(String planId) throws IOException {
        EventPlan plan = null;
        for (EventPlan candidate : this.schedule.get().plans()) {
            if (candidate.id().equals(planId)) {
                plan = candidate;
                break;
            }
        }
        if (plan == null) {
            throw new IOException("plan not found: " + planId);
        }
        if (!this.ceidEnabled.test(plan.ceid())) {
            throw new IOException("ceid disabled by S2F37: " + plan.ceid());
        }
        PlanExecutor executor = this.planExecutor;
        if (executor == null) {
            throw new IOException("plan executor not attached: " + this.sessionId);
        }
        final EventPlan target = plan;
        long atMs = this.clock.getAsLong();
        executor.step(target, planId, target.ceid(), atMs, this.communicator.get(),
                this.onlineCheck.getAsBoolean(),
                () -> this.applyPlanEffects(target.ceid(), planId),
                (ceidArg, atMsArg) -> this.assembler.get().build(ceidArg, atMsArg),
                this.wbit.getAsBoolean());
        this.publishS6f11Notice(target.ceid(), planId);
    }

    /**
     * 重连后按 FIFO 补跑离线队列（{@code offlinePolicy=queue}）。
     *
     * <p>效果与报文体都按补跑时刻求值：这里给执行器的组装回调直接用
     * {@code clock.nowMs()}，而不是排队时的 {@code atMs}。</p>
     */
    void replayPending() {
        PlanExecutor executor = this.planExecutor;
        if (executor == null) {
            return;
        }
        SecsCommunicator comm = this.communicator.get();
        if (comm == null || !this.onlineCheck.getAsBoolean()) {
            return;
        }
        long now = this.clock.getAsLong();
        executor.flushPending(new PlanExecutor.Dependencies(
                comm, true,
                () -> { /* 效果由 EffectController 按实时计划施加 */ },
                (ceidArg, atMsArg) -> this.assembler.get().build(ceidArg, now),
                this.wbit.getAsBoolean()));
    }

    /**
     * 挂编排执行器(3.0):触发路径全部改走它并落台账。
     *
     * @param executor 执行器
     */
    void planExecutor(PlanExecutor executor) {
        this.planExecutor = executor;
    }

    /** 接入在线判定(控制状态 ONLINE;离线时按计划 offlinePolicy 记台账)。 */
    void onlineCheck(java.util.function.BooleanSupplier check) {
        this.onlineCheck = check;
    }

    /**
     * 发布 s6f11 通知（载荷带触发它的计划 id；手动触发时为 null）。
     *
     * <p>不能用 {@code findFirst()} 猜：同一 CEID 可能对应多条计划，
     * 猜出来的 id 会让界面高亮错误的计划。</p>
     */
    private void publishS6f11Notice(int ceid, String planId) {
        /* planId 来自运行时编辑（不限于字母数字）：裸拼会把引号/反斜杠/换行带进帧，
           轻则非法 JSON，重则提前终止 SSE 帧。统一交给 JSON 写出器转义。 */
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonObjectPair> pairs = new ArrayList<>(2);
        pairs.add(b.pair("ceid", ceid));
        if (planId != null) {
            pairs.add(b.pair("planId", planId));
        }
        this.notice.accept(com.shimizukenta.secssim.app.event.NoticeEvent.S6F11, b.object(pairs).toJson());
    }

    /**
     * 施加计划的值效果（schema v6）。
     *
     * <p>emit 是全部触发路径（定时/条件/链式/手动）的公共入口，
     * 效果也只在这里施加一次:手动触发同样生效。计划被禁用时效果不施加
     * （编排表来自 {@code previewSchedule}，已排除禁用项）。</p>
     *
     * <p>计划身份已知时只施加该计划的效果——同一 CEID 下允许多条计划
     * （周期 + 条件、多台单元），按 CEID 取并集会把另一条计划的效果也带进来。
     * 手动触发（{@code planId == null}）没有计划上下文，保持该 CEID 全部
     * 启用计划取并集。</p>
     */
    private EffectBatch applyPlanEffects(int ceid, String planId) {
        long now = this.clock.getAsLong();
        boolean matched = false;
        List<String> evidence = new ArrayList<>();
        List<EffectBatch.Touch> touches = new ArrayList<>();
        java.util.Set<Integer> seenSvids = new java.util.HashSet<>();
        boolean rollbackable = true;
        for (EventPlan plan : this.schedule.get().plans()) {
            if (plan.ceid() != ceid) {
                continue;
            }
            if (planId != null && !plan.id().equals(planId)) {
                continue;
            }
            matched = true;
            for (EventEffect effect : plan.effects()) {
                int svid = effect.svid();
                double before = this.valueReader.applyAsDouble(svid);
                EventEffectStore.Applied handle = this.effects.applyTracked(svid, effect, now);
                double after = this.valueReader.applyAsDouble(svid);
                long versionAfter = this.effects.version(svid);
                /* 只有 set/add 可逆；同一 SVID 被本次多条效果触碰也无法精确摘除 */
                if ((effect.op() != EventEffect.Op.SET && effect.op() != EventEffect.Op.ADD)
                        || !seenSvids.add(svid)) {
                    rollbackable = false;
                }
                touches.add(new EffectBatch.Touch(svid, handle, versionAfter));
                evidence.add("{\"svid\":" + svid
                        + ",\"op\":\"" + effect.op().name().toLowerCase(java.util.Locale.ROOT) + '"'
                        + ",\"before\":" + number(before)
                        + ",\"after\":" + number(after)
                        + ",\"value\":" + number(effect.value()) + "}");
            }
        }
        if (planId != null && !matched) {
            /* 触发方与效果源看到的不是同一张编排表：不报出来就是“事件发了、值不动” */
            Log.warn("会话 " + this.sessionId + " 触发计划 " + planId
                    + "（CEID " + ceid + "）时编排表中没有它，效果未施加");
        }
        return new EffectBatch("[" + String.join(",", evidence) + "]",
                touches, rollbackable && !touches.isEmpty());
    }

    /** 供执行器的 EffectController 调用：施加并返回台账证据。 */
    PlanExecutor.EffectResult applyTracked(int ceid, String planId) {
        EffectBatch batch = this.applyPlanEffects(ceid, planId);
        return new PlanExecutor.EffectResult(batch.json, batch, batch.rollbackable);
    }

    /** 仅当此后没有别的 fire 触碰过这些 SVID 才撤销本次 set/add 效果。 */
    String rollback(Object token) {
        if (!(token instanceof EffectBatch batch) || !batch.rollbackable) {
            return "rollback_skipped";
        }
        for (EffectBatch.Touch touch : batch.touches) {
            if (this.effects.version(touch.svid) != touch.versionAfter) {
                return "rollback_skipped";
            }
        }
        for (EffectBatch.Touch touch : batch.touches) {
            if (!this.effects.removeIfUntouched(touch.svid, touch.handle, touch.versionAfter)) {
                return "rollback_skipped";
            }
        }
        return "rolled_back";
    }

    private static String number(Double value) {
        return value == null || !Double.isFinite(value) ? "null" : value.toString();
    }

    /** 一次效果施加的回滚凭据（同包可见，供执行器 token 使用）。 */
    static final class EffectBatch {
        /** 单个 SVID 的施加句柄与代际快照。 */
        record Touch(int svid, EventEffectStore.Applied handle, long versionAfter) {
        }

        private final String json;
        private final List<Touch> touches;
        private final boolean rollbackable;

        private EffectBatch(String json, List<Touch> touches, boolean rollbackable) {
            this.json = json;
            this.touches = List.copyOf(touches);
            this.rollbackable = rollbackable;
        }
    }
}
