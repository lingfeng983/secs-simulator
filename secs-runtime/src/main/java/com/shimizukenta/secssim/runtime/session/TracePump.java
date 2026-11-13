package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.core.trace.TracePlan;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * Trace（周期采样）的采集与上报。
 *
 * <p>S2F23 让设备按周期采样并分条推 S6F1：设备这边只管
 * "什么时候采、采多少、怎么发"，协议细节收在这里，
 * 会话层只提供时钟、连接与"现在能不能主动发"的判断。</p>
 */
final class TracePump {

    /**
     * 采样与分条（热重载换模型时要重建）。
     *
     * <p>{@code volatile}：{@link #reset} 在 HTTP 线程（热生效/目录编辑）替换，
     * {@code pump} 在心跳线程读。非 volatile 时心跳可能一直用着旧实例——
     * 更糟的是 {@code handleInitialize} 把新 TRID 装进即将被丢弃的旧管理器，
     * S2F24 回成功但永远不推 S6F1。</p>
     */
    private volatile TraceManager traces;

    /**
     * W 位 ACK 专用队列：阻塞等 S6F2 不能占住采样线程。
     *
     * <p>必须是 supplier 而不是固定引用：断开时这套池会被 {@code shutdownNow()}，
     * 重连时 {@code openLocked} 会新建一个。抓着一个已关闭的池，重连后
     * 每次 {@code execute} 都抛 {@link RejectedExecutionException}，
     * 结果是"能收 S2F23、S2F24 也回成功，但 S6F1 再也发不出来"。</p>
     */
    private final java.util.function.Supplier<ExecutorService> ackSenders;

    /** 当前时钟（毫秒）。 */
    private final java.util.function.LongSupplier clock;

    /** 回话出口。 */
    private ProtocolReply replier;


    /**
     * 主动发送出口：S6F1 没有主报文可回，只能直接发。
     *
     * <p>带 W 位时这个调用会阻塞到收到 S6F2（或 T3 超时），
     * 所以它必须落到真正会等待应答的 {@code SecsCommunicator.send} 上——
     * 走"回话"出口会因为不等待而提前返回。</p>
     *
     * @param stream   流号
     * @param function 功能号
     * @param wbit     是否等待应答
     * @param body     内容
     * @throws Exception 发送失败
     */
    @FunctionalInterface
    interface Sender {

        /**
         * 发一帧。
         *
         * @param stream   流号
         * @param function 功能号
         * @param wbit     是否等待应答
         * @param body     内容
         * @throws Exception 发送失败
         */
        void send(int stream, int function, boolean wbit, Secs2 body) throws Exception;
    }

    /** 主动上报是否允许（W 位等待会阻塞，链路不通就别试）。 */
    private java.util.function.BooleanSupplier canSendSupplier;

    /** 已发帧数（诊断用）。 */
    private final AtomicLong framesSent = new AtomicLong();

    /** 因 ACK 队列满而丢弃的帧数。 */
    private final AtomicLong wbitDropped = new AtomicLong();

    /** 主动发送出口（S6F1 是 unsolicited，没有主报文可回）。 */
    private Sender sender;

    /** 出错回调。 */
    private java.util.function.Consumer<Exception> errorSink;

    /** 会话 id（错误日志用）。 */
    private String sessionId = "";

    /** S6F1 是否带 W 位。 */
    private boolean s6f1Wbit;

    TracePump(TraceManager traces, java.util.function.Supplier<ExecutorService> ackSenders,
              java.util.function.LongSupplier clock) {
        this.traces = traces;
        this.ackSenders = ackSenders;
        this.clock = clock;
    }

    /** 容量限制（条数）。 */
    private int maxTraces = TraceManager.MAX_TRACES;

    /** 容量限制（单条 SVID 数）。 */
    private int maxSvidCount = TraceManager.MAX_SVIDS;

    /**
     * 设置容量限制（下次 reset 生效；当前管理器立即生效）。
     *
     * @param maxTraces    同时允许的 Trace 条数（≥1）
     * @param maxSvidCount 单条 Trace 允许的 SVID 数（≥1）
     */
    void traceLimits(int maxTraces, int maxSvidCount) {
        if (maxTraces < 1 || maxSvidCount < 1) {
            throw new IllegalArgumentException("trace limits must be >= 1");
        }
        this.maxTraces = maxTraces;
        this.maxSvidCount = maxSvidCount;
        this.traces.setLimits(maxTraces, maxSvidCount);
    }

    void replyer(ProtocolReply replyer) {
        this.replier = replyer;
    }

    void canSend(java.util.function.BooleanSupplier supplier) {
        this.canSendSupplier = supplier;
    }

    void sender(Sender sender) {
        this.sender = sender;
    }

    void errorSink(java.util.function.Consumer<Exception> sink) {
        this.errorSink = sink;
    }

    void sessionId(String id) {
        this.sessionId = id;
    }

    void s6f1Wbit(boolean enabled) {
        this.s6f1Wbit = enabled;
    }

    /** 已发帧数。 */
    long framesSent() {
        return this.framesSent.get();
    }

    /** 因 ACK 队列满而丢弃的帧数。 */
    long wbitDropped() {
        return this.wbitDropped.get();
    }

    /** 当前活跃的 Trace 数。 */
    int activeCount() {
        return this.traces.activeCount();
    }

    /**
     * 换模型后重建采样器。
     *
     * <p>热重载会换掉设备模型，旧的 Trace 参数（TRID/SVID 列表）不再成立，
     * 必须停掉重建——否则会拿着旧模型的 SVID 去采样。</p>
     *
     * @param model 新模型
     * @param values 值来源
     */
    synchronized void reset(com.shimizukenta.secssim.core.compile.ResolvedEquipment model,
                            com.shimizukenta.secssim.core.value.ValueSource values) {
        this.traces.stopAll();
        /* 容量限制跟随会话配置：热重载换模型时原样带到新管理器 */
        this.traces = new TraceManager(model, values, java.time.Instant.now(),
                this.maxTraces, this.maxSvidCount);
    }

    /** 以主机期望的 stream/function 回话。 */
    private void reply(SecsMessage primary, int stream, int function, Secs2 body) {
        ProtocolReply sink = this.replier;
        if (sink == null) {
            return;
        }
        sink.reply(primary, stream, function, body);
    }

    /** 现在能不能主动发。 */
    private boolean canSend() {
        java.util.function.BooleanSupplier supplier = this.canSendSupplier;
        return supplier == null || supplier.getAsBoolean();
    }

    /** 记录一次错误。 */
    private void recordError(Exception e) {
        java.util.function.Consumer<Exception> sink = this.errorSink;
        if (sink != null) {
            sink.accept(e);
        }
    }


    /**
     * S2F23：主机点播一条周期采样。
     *
     * <p>回答 S2F24 的 TIAACK 码；{@code TOTSMP=0} 表示终止该 TRID。
     * </p>
     */
    void handleInitialize(SecsMessage primary) throws Exception {
        int ack;

        try {
            ack = this.initializeTrace(primary.secs2());
        } catch (Exception e) {
            recordError(e);
            ack = TraceManager.TIAACK_INVALID_PERIOD;
        }

        this.reply(primary, 2, 24, Secs2.getBuilder().binary((byte) ack));
    }


    private int initializeTrace(Secs2 body) throws Exception {
        if (body.size() < 5) {
            throw new IllegalArgumentException("S2F23 需要 5 个元素: TRID / DSPER / TOTSMP / REPGSZ / SVID 列表");
        }

        int trid = Secs2Read.asInt(body.get(0));
        int totsmp = Secs2Read.asInt(body.get(2));
        int repgsz = Secs2Read.asInt(body.get(3));

        List<Integer> svids = new ArrayList<>();
        Secs2 svidList = body.get(4);
        for (int i = 0; i < svidList.size(); i++) {
            svids.add(Secs2Read.asInt(svidList.get(i)));
        }

        /* TOTSMP=0 是"终止"，此时 DSPER 可能无意义，不要因为解析它而误判为周期非法 */
        long dsperMs = totsmp == 0 ? 1_000L : TracePlan.parseDsperMs(body.get(1).getAscii());

        return this.traces.initialize(trid, svids, dsperMs, totsmp, repgsz, this.clock.getAsLong());
    }


    void pump() {
        long now = this.clock.getAsLong();
        List<TraceManager.Frame> frames = this.traces.poll(now);

        if (!this.canSend()) {
            return;
        }

        for (TraceManager.Frame frame : frames) {
            try {
                Secs2 body = Secs2.getBuilder().list(
                        Secs2.getBuilder().uint4(frame.trid()),
                        Secs2.getBuilder().uint4(frame.smpln()),
                        Secs2.getBuilder().ascii(frame.stime()),
                        Secs2.getBuilder().list(frame.values()));

                if (this.s6f1Wbit) {
                    /* W 位会阻塞到 S6F2 或 T3，放到独立队列，Trace 采样照常推进。
                       队列每次现取：断开时旧池已被 shutdownNow，抓着旧引用就永久发不出去了。 */
                    ExecutorService pool = this.ackSenders.get();
                    if (pool == null || pool.isShutdown()) {
                        this.wbitDropped.incrementAndGet();
                        Log.warn("会话 " + this.sessionId + " S6F1 ACK 队列不可用（链路已断），丢弃一帧 W 位抓取");
                        continue;
                    }
                    pool.execute(() -> {
                        try {
                            this.sendTraceFrame(body, true);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            recordError(new IllegalStateException("等待 S6F2 被中断: TRID " + frame.trid()));
                        } catch (Exception e) {
                            recordError(e);
                            Log.error("会话 " + this.sessionId + " 等待 S6F2 失败", e);
                        }
                    });
                } else {
                    this.sendTraceFrame(body, false);
                }
            } catch (RejectedExecutionException e) {
                this.wbitDropped.incrementAndGet();
                Log.error("会话 " + this.sessionId + " S6F1 ACK 队列已满", e);
            } catch (Exception e) {
                recordError(e);
                Log.error("会话 " + this.sessionId + " 发送 S6F1 失败", e);
            }
        }
    }


    private void sendTraceFrame(Secs2 body, boolean wbit) throws Exception {
        this.sender.send(6, 1, wbit, body);
        this.framesSent.incrementAndGet();
    }
}
