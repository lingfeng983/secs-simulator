package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>所有触发路径（定时、条件、链式、手动）都从这里走一遍：组装 S6F11、
 * 发出去。<b>事件不改任何值</b>（schema v7）——报文体里的数值来自各个数据项
 * 自己的 {@code source}，在组装那一刻求值。</p>
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

    /**
     * 编排表。
     *
     * <p>必须是<b>实时</b>表（{@code EventScheduler::previewSchedule}）：运行时增删改计划
     * 只改调度器内部那份，拿装载时的快照会出现“刚加的计划效果不施加、已删的计划还在改值”。
     * 预览表已排除被禁用的计划，所以这里不再单独过滤。</p>
     */
    private final java.util.function.Supplier<EventSchedule> schedule;

    /**
     * 按 id 在<b>完整</b>编排表查计划（含被禁用的），专供单步试跑。
     *
     * <p>与 {@link #schedule} 指向的预览表分开：试跑是调试能力，要能跑列表里
     * 任意一条计划（默认全是禁用态）；实际触发仍走排除禁用计划的预览表。</p>
     */
    private final java.util.function.Function<String, EventPlan> planLookup;

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

    EventReporter(java.util.function.Supplier<S6F11Assembler> assembler,
                  java.util.function.Supplier<SecsCommunicator> communicator,
                  java.util.function.Supplier<ExecutorService> ackSender,
                  java.util.function.Supplier<EventSchedule> schedule,
                  java.util.function.Function<String, EventPlan> planLookup,
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
        this.schedule = schedule;
        this.planLookup = planLookup;
        this.ceidEnabled = ceidEnabled;
        this.recordSkipped = recordSkipped;
        this.recordFire = recordFire;
        this.notice = notice;
        this.clock = clock;
        this.wbit = wbit;
        this.sessionId = sessionId;
        this.errorSink = errorSink;
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
        this.emit(ceid, planId, this.clock.getAsLong());
    }

    /**
     * 上报一次采集事件（带显式触发时刻）。
     *
     * <p>工艺周期用：台账 {@code scheduled_at} 记「排定的片内时刻」而不是
     * 当前时刻——同一 tick 里两条片内事件才不会撞同一个幂等键，追溯时也能
     * 看到它本该在什么时候发。</p>
     *
     * @param ceid   采集事件号
     * @param planId 触发它的计划 id（cycle 片内事件传 {@code "cycle"}）；手动 {@code null}
     * @param atMs   排定的触发时刻（幂等键成分）
     * @throws IOException 会话未打开或发送失败
     */
    void emit(int ceid, String planId, long atMs) throws IOException {
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
            /*
             * 台账/幂等键上的计划标识：手动触发（planId 为 null）必须落到"实际匹配到的那条计划"上。
             * 否则两次手动触发（不同 CEID）在同一毫秒会撞上同一个幂等键 deviceId|manual|atMs，
             * 后一条被静默丢弃 —— 现场就是"点了两个启动事件，只有第一个上报了"，
             * 两份采集计划里第二份永远等不到它的启动 CEID。没有匹配计划时按 CEID 兜底区分。
             */
            String ledgerPlanId = planId != null
                    ? planId
                    : (firedPlan != null ? firedPlan.id() : "manual-" + ceid);
            executor.execute(effective, ledgerPlanId, ceid, atMs, comm, this.onlineCheck.getAsBoolean(),
                    (ceidArg, atMsArg) -> this.assembler.get().build(ceidArg, atMsArg),
                    this.wbit.getAsBoolean());
            this.publishS6f11Notice(ceid, planId);
            return;
        }

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

        /* 非 W 位同样入队。它虽然不等回复，但写 socket 仍可能被对端零窗口堵到 T6；
           这段代码跑在事件心跳的 pumpLock 内，同步写会让锁被占数秒，
           把会话创建/关闭/重载一起拖住。入队后锁内只做投递，队列满则显式报错。 */
        try {
            this.ackSender.get().execute(() -> {
                try {
                    comm.send(6, 11, false, body);
                    this.emitted.incrementAndGet();
                    this.recordFire.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.errorSink.accept(new IllegalStateException("发送 S6F11 被中断: " + ceid));
                } catch (Exception e) {
                    this.errorSink.accept(e);
                    Log.error("会话 " + this.sessionId + " 发送 S6F11 失败: " + ceid, e);
                }
            });
        } catch (RejectedExecutionException e) {
            this.wbitDropped.incrementAndGet();
            throw new IOException("S6F11 发送队列已满: " + ceid, e);
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
        /* 查完整编排表（含禁用计划）：列表里显示的计划默认都是禁用态，
           若查预览表会一律 "plan not found"，试跑这个调试能力就形同虚设。 */
        EventPlan plan = this.planLookup.apply(planId);
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
                (ceidArg, atMsArg) -> this.assembler.get().build(ceidArg, atMsArg),
                this.wbit.getAsBoolean());
        this.publishS6f11Notice(target.ceid(), planId);
    }

    /* offlinePolicy=queue 的补跑链路已随 2026-11-16 设计（M2 去补跑）删除：
       不在线的触发在调度层就被清场丢弃（D8），发送失败也不重试不补发。 */
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

}
