package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.gem.COMMACK;
import com.shimizukenta.secs.gem.OFLACK;
import com.shimizukenta.secs.gem.ONLACK;
import com.shimizukenta.secs.gem.TIACK;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.secs.secs2.Secs2Exception;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.app.session.HostReportTable;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Fire;
import com.shimizukenta.secssim.core.event.RateLimiter;
import com.shimizukenta.secssim.core.value.CurveValueSource;
import com.shimizukenta.secssim.core.trace.TracePlan;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.core.value.ValueStore;

/**
 * 用 HSMS-SS 实现一个设备会话。
 *
 * <p>能力边界：</p>
 * <ul>
 *   <li>S1F1 → S1F2（链路自检）</li>
 *   <li>S1F3 → S1F4（按 SVID 列表取当前值，值来自 {@link ValueSource}）</li>
 *   <li>S6F11（主动上报采集事件，可手动触发，也可按编排表自动触发）</li>
 * </ul>
 *
 * <p>设备级限流与"单飞"都在这里：某个单元剧本配错时，不能把主机打爆。
 * </p>
 */
public final class HsmsSessionRuntime implements SessionRuntime {

    private static final long EVENT_TICK_MS = 100L;
    private static final long RATE_WINDOW_MS = 1_000L;
    private static final int DEFAULT_MAX_EVENTS_PER_SEC = 50;
    /** S6F11 等 ACK 的队列上限：主机不答 S6F12 时不能无限堆积。 */
    private static final int S6F11_ACK_QUEUE = 256;

    private final SessionSpec spec;
    private final ResolvedEquipment model;
    private final ValueStore store;
    private final ValueSource values;
    private final EngineClock clock;
    private final HostReportTable hostReports = new HostReportTable();
    private final S6F11Assembler assembler;
    private final TraceManager traces;
    private final Rng eventRng;

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong traceFrames = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong wbitDropped = new AtomicLong();
    private final AtomicLong traceWbitDropped = new AtomicLong();

    private volatile SecsCommunicator communicator;
    private volatile String lastError;
    private volatile EventBus eventBus;
    private volatile boolean communicatable;
    private volatile EventSchedule schedule = EventSchedule.empty();
    private volatile RateLimiter limiter = new RateLimiter(DEFAULT_MAX_EVENTS_PER_SEC, RATE_WINDOW_MS);
    private volatile ScheduledExecutorService ticker;
    private volatile boolean s6f11Wbit;
    private volatile boolean s6f1Wbit;
    /** 单线程串行等 S6F12：W 位发送会阻塞到 T3，不能占用 100ms 事件心跳。 */
    private volatile ExecutorService ackSender;
    /** 单线程串行等 S6F2：Trace 采样不能被 ACK 阻塞。 */
    private volatile ExecutorService traceAckSender;
    /**
     * 上次触发事件的时间点。
     *
     * <p>目前只由 ticker 线程写，但语义上跨"调度/手动触发"两条路径，
     * 加 {@code volatile} 是为了后续演进时不引入可见性问题。
     * </p>
     */
    private volatile long lastFireMs;

    /**
     * 用系统时钟创建会话。
     *
     * @param spec  会话参数
     * @param model 已解析的设备模型
     */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model) {
        this(spec, model, EngineClock.system(), null);
    }

    /**
     * 创建会话。
     *
     * @param spec  会话参数
     * @param model 已解析的设备模型
     * @param clock 仿真时钟（测试可注入手动时钟以获得确定性）
     */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model, EngineClock clock) {
        this(spec, model, clock, null);
    }

    /**
     * 创建会话，并指定值来源（例如用模板 DAG 驱动）。
     *
     * @param spec   会话参数
     * @param model  已解析的设备模型
     * @param clock  仿真时钟
     * @param values 值来源；传 {@code null} 表示用默认曲线
     */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model, EngineClock clock, ValueSource values) {
        this.spec = spec;
        this.model = model;
        this.clock = clock;
        this.store = new ValueStore(spec.seed());

        if (values == null) {
            DefaultCurves.bind(this.store, spec.equipment(), model);
            this.values = new CurveValueSource(this.store);
        } else {
            this.values = values;
        }

        this.assembler = new S6F11Assembler(model, this.values, this.hostReports);
        this.traces = new TraceManager(model, this.values, Instant.now());
        this.eventRng = new Rng(spec.seed()).derive("events");
        this.ackSender = newAckSender("s6f11-ack-" + spec.id());
        this.traceAckSender = newAckSender("s6f1-ack-" + spec.id());
    }

    private static ExecutorService newAckSender(String threadName) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(S6F11_ACK_QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * 设置 S6F11 是否带 W 位并等待 S6F12。
     *
     * <p>默认 {@code false}：多数设备不要求主机逐条 ACK，带 W 位会让每次上报
     * 阻塞到 S6F12 或 T3。开启后发送放到单线程队列，不占用事件心跳。</p>
     *
     * @param enabled 是否启用
     */
    public void collectionEventWbit(boolean enabled) {
        this.s6f11Wbit = enabled;
    }

    /**
     * 设置 S6F1 是否带 W 位并等待 S6F2。
     *
     * <p>与 S6F11 相同的取舍：默认 {@code false}，开启后发送放到独立单线程队列，
     * 不阻塞 Trace 采样与 100ms 心跳。</p>
     *
     * @param enabled 是否启用
     */
    public void traceWbit(boolean enabled) {
        this.s6f1Wbit = enabled;
    }

    /**
     * 值存储（诊断与测试用）。
     *
     * @return 值存储
     */
    public ValueStore valueStore() {
        return this.store;
    }

    /**
     * 已解析的设备模型。
     *
     * @return 模型
     */
    public ResolvedEquipment model() {
        return this.model;
    }

    /**
     * 值来源（诊断与预览用）。
     *
     * @return 值来源
     */
    public ValueSource valueSource() {
        return this.values;
    }

    /**
     * 预览某一时间段的曲线（分桶 min/max/avg）。
     *
     * <p>分桶而不是逐点返回：400 个 SVID × 几十万点会把浏览器打爆，
     * 而 min/max 双值能保留尖峰（故障注入要看得见），avg 用于看趋势。
     * </p>
     *
     * @param fromMs  起始时刻
     * @param toMs    结束时刻
     * @param buckets 桶数（1..500）
     * @return JSON 文本
     */
    @Override
    public String previewJson(long fromMs, long toMs, int buckets) {
        int count = Math.max(1, Math.min(500, buckets));
        long span = Math.max(1L, toMs - fromMs);
        long bucketMs = Math.max(1L, span / count);
        long sampleMs = Math.max(1L, bucketMs / 4L);

        java.util.List<Integer> svids = this.values.svids().stream().sorted().limit(32).toList();
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        java.util.List<com.shimizukenta.jsonhub.JsonHub> series = new java.util.ArrayList<>();

        for (int svid : svids) {
            double[] min = new double[count];
            double[] max = new double[count];
            double[] avg = new double[count];
            java.util.Arrays.fill(min, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(max, Double.NEGATIVE_INFINITY);

            for (int i = 0; i < count; i++) {
                long bucketFrom = fromMs + i * bucketMs;
                long bucketTo = Math.min(toMs, bucketFrom + bucketMs);
                double sum = 0.0;
                int n = 0;

                for (long t = bucketFrom; t <= bucketTo; t += sampleMs) {
                    java.util.OptionalDouble value = this.values.valueAt(svid, t);
                    if (value.isPresent()) {
                        double v = value.getAsDouble();
                        min[i] = Math.min(min[i], v);
                        max[i] = Math.max(max[i], v);
                        sum += v;
                        n++;
                    }
                }

                if (n == 0) {
                    min[i] = 0.0;
                    max[i] = 0.0;
                }
                avg[i] = n == 0 ? 0.0 : sum / n;
            }

            series.add(b.object(
                    b.pair("svid", svid),
                    b.pair("min", b.array(numbers(min, b))),
                    b.pair("max", b.array(numbers(max, b))),
                    b.pair("avg", b.array(numbers(avg, b)))));
        }

        return b.object(
                b.pair("from", fromMs),
                b.pair("to", toMs),
                b.pair("buckets", count),
                b.pair("bucketMs", bucketMs),
                b.pair("series", b.array(series))).toJson();
    }

    private static java.util.List<com.shimizukenta.jsonhub.JsonHub> numbers(double[] values,
                                                                            com.shimizukenta.jsonhub.JsonHubBuilder b) {
        java.util.List<com.shimizukenta.jsonhub.JsonHub> list = new java.util.ArrayList<>(values.length);
        for (double value : values) {
            list.add(b.number(Math.round(value * 1000.0) / 1000.0));
        }
        return list;
    }

    /**
     * 挂上事件总线：会话收发的每条数据报文都会以事件形式发布。
     *
     * @param bus 事件总线；{@code null} 表示不发布
     */
    public void eventBus(EventBus bus) {
        this.eventBus = bus;
    }

    /**
     * 启用事件编排。必须在 {@link #open()} 之前调用。
     *
     * @param schedule        编排表
     * @param maxEventsPerSec 设备级每秒最大上报数
     */
    public void enableSchedule(EventSchedule schedule, int maxEventsPerSec) {
        this.schedule = schedule == null ? EventSchedule.empty() : schedule;
        this.limiter = new RateLimiter(maxEventsPerSec, RATE_WINDOW_MS);
    }

    /**
     * 已上报的 S6F11 条数。
     *
     * @return 条数
     */
    public long emittedCount() {
        return this.emitted.get();
    }

    /**
     * 因限流被丢弃的条数。
     *
     * @return 条数
     */
    public long rejectedCount() {
        return this.rejected.get();
    }

    /**
     * 已发送的 S6F1 报文数。
     *
     * @return 条数
     */
    public long traceFramesSent() {
        return this.traceFrames.get();
    }

    /**
     * 活跃的 Trace 数量。
     *
     * @return 数量
     */
    public int activeTraces() {
        return this.traces.activeCount();
    }

    /**
     * 立即处理一次到期的工作（事件上报 + Trace 采样）。
     *
     * <p>正常情况下由 100ms 心跳驱动；暴露出来是为了让测试与"确定性步进"场景
     * 能精确控制推进节奏，而不是靠 sleep 赌时序。
     * </p>
     */
    public void pumpNow() {
        /* 采样照常推进（值来源是纯函数，不需要链路），只是链路不通时不发 */
        this.pumpTraces();

        if (!this.communicatable) {
            this.lastFireMs = this.clock.nowMs();
            return;
        }

        this.pumpEvents();
    }

    /**
     * 手动上报一个采集事件（不受限流约束：这是操作员的显式动作）。
     *
     * @param ceid CEID
     * @throws IOException 会话未打开或发送失败
     */
    @Override
    public void emitCeid(int ceid) throws IOException {
        SecsCommunicator comm = this.communicator;
        if (comm == null) {
            throw new IOException("session not open: " + this.spec.id());
        }

        if (!this.hostReports.isEnabled(ceid)) {
            /* 被 S2F37 禁用：直接丢弃，不缓存（见 ADR 0006） */
            this.skipped.incrementAndGet();
            return;
        }

        Secs2 body;
        try {
            body = this.assembler.build(ceid, this.clock.nowMs());
        } catch (Exception e) {
            throw new IOException("组装 S6F11 失败: " + ceid, e);
        }

        if (this.s6f11Wbit) {
            /* W 位发送会阻塞到 S6F12 或 T3；放到单线程队列，事件心跳照常跑 */
            try {
                this.ackSender.execute(() -> {
                    try {
                        comm.send(6, 11, true, body);
                        this.emitted.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        this.lastError = "等待 S6F12 被中断: " + ceid;
                    } catch (Exception e) {
                        this.lastError = e.toString();
                        Log.error("会话 " + this.spec.id() + " 等待 S6F12 失败: " + ceid, e);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("发送 S6F11 被中断: " + ceid, e);
        } catch (Exception e) {
            throw new IOException("发送 S6F11 失败: " + ceid, e);
        }
    }

    @Override
    public String id() {
        return this.spec.id();
    }

    @Override
    public void open() throws IOException {
        if (this.communicator != null) {
            throw new IOException("session already opened: " + this.spec.id());
        }

        this.state.set(SessionState.OPENING);
        if (this.ackSender == null || this.ackSender.isShutdown()) {
            this.ackSender = newAckSender("s6f11-ack-" + this.spec.id());
        }
        if (this.traceAckSender == null || this.traceAckSender.isShutdown()) {
            this.traceAckSender = newAckSender("s6f1-ack-" + this.spec.id());
        }

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(this.spec.passive() ? HsmsSsProtocol.PASSIVE : HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress(this.spec.bindHost(), this.spec.port()));
        config.deviceId(this.spec.deviceId());
        config.isEquip(true);

        try {
            SecsCommunicator comm = HsmsSsCommunicator.open(config);
            comm.addSecsMessageReceiveListener(message -> {
                this.publish("in", message);
                this.onPrimary(message);
            });
            comm.addSendedMessagePassThroughListener(message -> this.publish("out", message));
            comm.addSecsCommunicatableStateChangeListener(value -> this.communicatable = value);
            this.communicator = comm;
            this.state.set(SessionState.OPEN);
            this.startTicker();
        } catch (IOException | RuntimeException e) {
            this.lastError = e.toString();
            this.state.set(SessionState.FAILED);
            throw e;
        }
    }

    @Override
    public void close() {
        this.stopTicker();
        ExecutorService sender = this.ackSender;
        if (sender != null) {
            sender.shutdownNow();
        }
        ExecutorService traceSender = this.traceAckSender;
        if (traceSender != null) {
            traceSender.shutdownNow();
        }

        SecsCommunicator comm = this.communicator;
        if (comm == null) {
            this.state.set(SessionState.CLOSED);
            return;
        }

        this.state.set(SessionState.CLOSING);
        try {
            comm.close();
        } catch (IOException e) {
            this.lastError = e.toString();
        } finally {
            this.communicator = null;
            this.state.set(SessionState.CLOSED);
        }
    }

    @Override
    public String deviceName() {
        return this.model.name();
    }

    @Override
    public int unitCount() {
        return this.spec.equipment().units().size();
    }

    @Override
    public boolean hasPeer() {
        return this.communicatable;
    }

    @Override
    public Map<String, Long> counters() {
        return Map.of(
                "emitted", this.emitted.get(),
                "rejected", this.rejected.get(),
                "skipped", this.skipped.get(),
                "wbitDropped", this.wbitDropped.get(),
                "traceWbitDropped", this.traceWbitDropped.get(),
                "traceFrames", this.traceFrames.get(),
                "activeTraces", (long) this.traces.activeCount(),
                "hostReports", (long) this.hostReports.reportCount(),
                "hostLinks", (long) this.hostReports.linkCount());
    }

    @Override
    public SessionState state() {
        return this.state.get();
    }

    @Override
    public String lastError() {
        return this.lastError;
    }

    private void startTicker() {
        this.lastFireMs = this.clock.nowMs();

        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "events-" + this.spec.id());
            thread.setDaemon(true);
            return thread;
        });
        service.scheduleAtFixedRate(this::pumpNow, EVENT_TICK_MS, EVENT_TICK_MS, TimeUnit.MILLISECONDS);
        this.ticker = service;
    }

    private void stopTicker() {
        ScheduledExecutorService service = this.ticker;
        if (service != null) {
            service.shutdownNow();
            this.ticker = null;
        }
    }

    private void pumpEvents() {
        try {
            if (!this.communicatable) {
                /* 没有主机在线时不要积压事件：上线后从当前时刻重新开始，避免一次涌出几百条 */
                this.lastFireMs = this.clock.nowMs();
                return;
            }

            long now = this.clock.nowMs();
            long from = this.lastFireMs;

            if (now <= from) {
                return;
            }
            this.lastFireMs = now;

            for (Fire fire : this.schedule.between(from, now, this.eventRng)) {
                if (this.limiter.tryAcquire(now)) {
                    this.emitCeid(fire.ceid());
                } else {
                    this.rejected.incrementAndGet();
                }
            }
        } catch (Exception e) {
            this.lastError = e.toString();
            Log.error("会话 " + this.spec.id() + " 事件上报失败", e);
        }
    }

    /**
     * 把报文发布到事件总线。
     *
     * <p>只发布<b>数据报文</b>（stream ≥ 0）：HSMS 的 select/deselect 之类控制帧
     * 对使用者没有意义，混进报文面板只会造成噪音。
     * </p>
     */
    private void publish(String direction, SecsMessage message) {
        EventBus bus = this.eventBus;
        if (bus == null || message.getStream() < 0) {
            return;
        }

        String body;
        try {
            body = com.shimizukenta.secssim.runtime.sml.SmlRenderer.render(message.secs2());
        } catch (RuntimeException e) {
            body = "<不可渲染>";
        }

        bus.publish(new MessageEvent(this.spec.id(), direction, message.getStream(), message.getFunction(),
                message.wbit(), body, System.currentTimeMillis()));
    }

    private void onPrimary(SecsMessage primary) {
        try {
            int stream = primary.getStream();
            int function = primary.getFunction();

            /* 控制状态类报文委托给协议层的 Gem：这些应答的形状（COMMACK/ONLACK/TIACK/时钟格式）
               都已按 E30/E5 实现，自己重写只会引入偏差 */
            switch (stream * 100 + function) {
                case 101 -> {
                    this.communicator.gem().s1f2(primary);
                    return;
                }
                case 113 -> {
                    this.communicator.gem().s1f14(primary, COMMACK.OK);
                    return;
                }
                case 115 -> {
                    this.communicator.gem().s1f16(primary);
                    return;
                }
                case 117 -> {
                    this.communicator.gem().s1f18(primary, ONLACK.OK);
                    return;
                }
                case 217 -> {
                    this.communicator.gem().s2f18Now(primary);
                    return;
                }
                case 231 -> {
                    this.communicator.gem().s2f32(primary, TIACK.OK);
                    return;
                }
                default -> {
                    /* 其余报文按下面的分支处理 */
                }
            }

            if (stream == 1 && function == 3) {
                replyS1F4(primary);
                return;
            }

            if (stream == 1 && function == 11) {
                replyS1F12(primary);
                return;
            }

            if (stream == 2 && function == 23) {
                handleTraceInitialize(primary);
                return;
            }

            switch (stream * 100 + function) {
                case 233 -> {
                    handleDefineReport(primary);
                    return;
                }
                case 235 -> {
                    handleLinkEventReport(primary);
                    return;
                }
                case 237 -> {
                    handleEnableEventReport(primary);
                    return;
                }
                default -> {
                    /* 其余报文交给 S9Fx 统一回答 */
                }
            }

            replyUnsupported(primary);
        } catch (Exception e) {
            this.lastError = e.toString();
            Log.error("会话 " + this.spec.id() + " 处理报文失败", e);
        }
    }

    /**
     * 对无法处理的 Primary 回 S9Fx。
     *
     * <p>E5 把“流不认识”和“功能不认识”分成 S9F3 / S9F5。
     * 不回应的后果比回错更糟：主机侧只会看到 T3 超时，无法区分
     * “设备不支持”与“设备坏了”。</p>
     *
     * <p>S9 本身不再回 S9，避免两端互打形成死循环。</p>
     */
    private void replyUnsupported(SecsMessage primary) {
        int stream = primary.getStream();
        if (stream == 9) {
            return;
        }

        try {
            if (isKnownStream(stream)) {
                this.communicator.gem().s9f5(primary);
            } else {
                this.communicator.gem().s9f3(primary);
            }
        } catch (Exception e) {
            this.lastError = e.toString();
            Log.error("会话 " + this.spec.id() + " 回复 S9Fx 失败", e);
        }
    }

    private static boolean isKnownStream(int stream) {
        return stream == 1 || stream == 2 || stream == 5 || stream == 6 || stream == 10;
    }

    /**
     * S2F33：主机定义报告（RPTID → VID 列表）。
     *
     * <p>空的报告列表表示"清空全部主机定义"，此时回落到本地配置（ADR 0006）。</p>
     */
    private void handleDefineReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 reportList = primary.secs2().get(0);

            if (reportList.size() == 0) {
                this.hostReports.clearReports();
            } else {
                for (int i = 0; i < reportList.size(); i++) {
                    Secs2 item = reportList.get(i);
                    this.hostReports.defineReport(
                            Secs2Read.asInt(item.get(0)),
                            this.intList(item.get(1)));
                }
            }
        } catch (Exception e) {
            this.lastError = e.toString();
            code = 1;
        }

        this.ackDynamicReport(primary, 34, code);
    }

    /**
     * S2F35：主机建立"事件 → 报告"的链接。
     */
    private void handleLinkEventReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 body = primary.secs2();
            Secs2 links = body.size() >= 2 ? body.get(1) : body.get(0);

            if (links.size() == 0) {
                this.hostReports.clearLinks();
            } else {
                for (int i = 0; i < links.size(); i++) {
                    Secs2 item = links.get(i);
                    this.hostReports.link(
                            Secs2Read.asInt(item.get(0)),
                            this.intList(item.get(1)));
                }
            }
        } catch (Exception e) {
            this.lastError = e.toString();
            code = 1;
        }

        this.ackDynamicReport(primary, 36, code);
    }

    /**
     * S2F37：主机启用/禁用事件上报。
     *
     * <p>CEID 列表为空表示"全部"。</p>
     */
    private void handleEnableEventReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 body = primary.secs2();
            boolean enabled = body.get(0).getBoolean(0);
            List<Integer> ceids = body.size() >= 2 ? this.intList(body.get(1)) : List.of();

            this.hostReports.setEnabled(enabled, ceids);
        } catch (Exception e) {
            this.lastError = e.toString();
            code = 1;
        }

        this.ackDynamicReport(primary, 38, code);
    }

    private List<Integer> intList(Secs2 list) throws Exception {
        List<Integer> values = new ArrayList<>(list.size());

        for (int i = 0; i < list.size(); i++) {
            values.add(Secs2Read.asInt(list.get(i)));
        }
        return values;
    }

    private void ackDynamicReport(SecsMessage primary, int function, int code) throws Exception {
        this.communicator.send(primary, 2, function, false, Secs2.getBuilder().binary((byte) code));
    }

    /**
     * S1F11：主机查询状态变量的名字与单位。
     *
     * <p>很多主机在采集前会先枚举一遍（"你有哪些变量、叫什么、单位是什么"），
     * 拿到 S1F12 才决定采哪些 SVID。没有它，自动发现型主机就走不下去。
     * </p>
     *
     * <p>请求为空列表时返回全部变量——E5 允许这种"枚举全部"的用法，现场调试也常用。
     * </p>
     */
    private void replyS1F12(SecsMessage primary) throws Exception {
        List<Integer> requested = new ArrayList<>();

        for (Secs2 item : requestSvids(primary)) {
            requested.add(Secs2Read.asInt(item));
        }

        if (requested.isEmpty()) {
            requested.addAll(this.model.svids().keySet().stream().sorted().toList());
        }

        Secs2Builder b = Secs2.getBuilder();
        List<Secs2> rows = new ArrayList<>(requested.size());

        for (int svid : requested) {
            ResolvedSvid definition = this.model.svids().get(svid);

            rows.add(b.list(
                    b.uint4(svid),
                    b.ascii(definition == null ? "" : definition.svName()),
                    b.ascii(definition == null ? "" : definition.unitSymbol())));
        }

        this.communicator.send(primary, 1, 12, false, b.list(rows));
    }

    /**
     * S2F23：主机点播一条周期采样。
     *
     * <p>回答 S2F24 的 TIAACK 码；{@code TOTSMP=0} 表示终止该 TRID。
     * </p>
     */
    private void handleTraceInitialize(SecsMessage primary) throws Exception {
        int ack;

        try {
            ack = this.initializeTrace(primary.secs2());
        } catch (Exception e) {
            this.lastError = e.toString();
            ack = TraceManager.TIAACK_INVALID_PERIOD;
        }

        this.communicator.send(primary, 2, 24, false, Secs2.getBuilder().binary((byte) ack));
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

        return this.traces.initialize(trid, svids, dsperMs, totsmp, repgsz, this.clock.nowMs());
    }

    private void pumpTraces() {
        long now = this.clock.nowMs();
        List<TraceManager.Frame> frames = this.traces.poll(now);

        if (!this.communicatable) {
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
                    /* W 位会阻塞到 S6F2 或 T3，放到独立队列，Trace 采样照常推进 */
                    this.traceAckSender.execute(() -> {
                        try {
                            this.sendTraceFrame(body, true);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            this.lastError = "等待 S6F2 被中断: TRID " + frame.trid();
                        } catch (Exception e) {
                            this.lastError = e.toString();
                            Log.error("会话 " + this.spec.id() + " 等待 S6F2 失败", e);
                        }
                    });
                } else {
                    this.sendTraceFrame(body, false);
                }
            } catch (RejectedExecutionException e) {
                this.traceWbitDropped.incrementAndGet();
                Log.error("会话 " + this.spec.id() + " S6F1 ACK 队列已满", e);
            } catch (Exception e) {
                this.lastError = e.toString();
                Log.error("会话 " + this.spec.id() + " 发送 S6F1 失败", e);
            }
        }
    }

    private void sendTraceFrame(Secs2 body, boolean wbit) throws Exception {
        this.communicator.send(6, 1, wbit, body);
        this.traceFrames.incrementAndGet();
    }

    /**
     * S1F3 请求一组 SVID，S1F4 必须按<b>同样的顺序与个数</b>返回。
     *
     * <p>E5 允许空列表表示“全部 SVID”。自动发现型主机常用它一次拿全，
     * 这里与 S1F11 的空列表语义保持一致。</p>
     */
    private void replyS1F4(SecsMessage primary) throws Exception {
        long nowMs = this.clock.nowMs();
        List<Secs2> requested = requestSvids(primary);
        List<Secs2> values = new ArrayList<>();

        if (requested.isEmpty()) {
            for (int svid : this.model.svids().keySet().stream().sorted().toList()) {
                values.add(this.valueOf(svid, nowMs));
            }
        } else {
            for (Secs2 item : requested) {
                values.add(this.valueOf(item, nowMs));
            }
        }

        this.communicator.send(primary, 1, 4, false, Secs2.getBuilder().list(values));
    }

    private List<Secs2> requestSvids(SecsMessage primary) throws Secs2Exception {
        Secs2 body = primary.secs2();
        List<Secs2> items = new ArrayList<>();

        if (body == null || body.isEmpty()) {
            return items;
        }

        for (int i = 0; i < body.size(); i++) {
            items.add(body.get(i));
        }
        return items;
    }

    private Secs2 valueOf(Secs2 svidItem, long nowMs) throws Secs2Exception {
        return this.valueOf(Secs2Read.asInt(svidItem), nowMs);
    }

    private Secs2 valueOf(int svid, long nowMs) {
        ResolvedSvid definition = this.model.svids().get(svid);

        if (definition == null) {
            /* 未知 SVID：主机按自己的表解析，返回 0 比断开连接更友好 */
            return Secs2.getBuilder().uint4(0L);
        }

        OptionalDouble value = this.values.valueAt(svid, nowMs);
        return Secs2Mapper.of(definition.type(), value.orElse(0.0));
    }
}
