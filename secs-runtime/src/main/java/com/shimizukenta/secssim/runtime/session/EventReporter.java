package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secssim.core.event.EventEffect;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.app.log.Log;
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

    /** 编排表。 */
    private final java.util.function.Supplier<EventSchedule> schedule;

    /** 被禁用的计划 id。 */
    private final java.util.function.Supplier<java.util.Set<String>> disabledPlans;

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
                  com.shimizukenta.secssim.runtime.session.EventEffectStore effects,
                  java.util.function.Supplier<EventSchedule> schedule,
                  java.util.function.Supplier<java.util.Set<String>> disabledPlans,
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
        this.disabledPlans = disabledPlans;
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
     * @param ceid 采集事件号
     * @throws IOException 会话未打开或发送失败
     */
    void emit(int ceid) throws IOException {
        SecsCommunicator comm = this.communicator.get();
        if (comm == null) {
            throw new IOException("session not open: " + this.sessionId);
        }

        if (!this.ceidEnabled.test(ceid)) {
            /* 被 S2F37 禁用：直接丢弃，不缓存（见 ADR 0006） */
            this.recordSkipped.run();
            return;
        }

        this.applyPlanEffects(ceid);
        this.publishS6f11Notice(ceid);

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
     * 发布 s6f11 通知（载荷含首个匹配且启用的计划 id,手动触发可能为 null）。
     */
    private void publishS6f11Notice(int ceid) {
        java.util.Set<String> disabled = this.disabledPlans.get();
        String planId = this.schedule.get().plans().stream()
                .filter(plan -> plan.ceid() == ceid && !disabled.contains(plan.id()))
                .map(EventPlan::id)
                .findFirst()
                .orElse(null);
        String payload = "{\"ceid\":" + ceid
                + (planId == null ? "" : ",\"planId\":\"" + planId + "\"")
                + "}";
        this.notice.accept(com.shimizukenta.secssim.app.event.NoticeEvent.S6F11, payload);
    }

    /**
     * 施加某 CEID 对应计划的值效果（schema v6）。
     *
     * <p>emit 是全部触发路径（定时/条件/链式/手动）的公共入口，
     * 效果也只在这里施加一次:手动触发同样生效。计划被禁用时效果不施加。</p>
     */
    private void applyPlanEffects(int ceid) {
        long now = this.clock.getAsLong();
        java.util.Set<String> disabled = this.disabledPlans.get();
        for (EventPlan plan : this.schedule.get().plans()) {
            if (plan.ceid() != ceid || disabled.contains(plan.id())) {
                continue;
            }
            for (EventEffect effect : plan.effects()) {
                this.effects.apply(effect.svid(), effect, now);
            }
        }
    }
}
