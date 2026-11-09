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
    private void applyPlanEffects(int ceid, String planId) {
        long now = this.clock.getAsLong();
        boolean matched = false;
        for (EventPlan plan : this.schedule.get().plans()) {
            if (plan.ceid() != ceid) {
                continue;
            }
            if (planId != null && !plan.id().equals(planId)) {
                continue;
            }
            matched = true;
            for (EventEffect effect : plan.effects()) {
                this.effects.apply(effect.svid(), effect, now);
            }
        }
        if (planId != null && !matched) {
            /* 触发方与效果源看到的不是同一张编排表：不报出来就是“事件发了、值不动” */
            Log.warn("会话 " + this.sessionId + " 触发计划 " + planId
                    + "（CEID " + ceid + "）时编排表中没有它，效果未施加");
        }
    }
}
