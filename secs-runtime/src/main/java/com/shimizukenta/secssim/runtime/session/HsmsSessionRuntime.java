package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
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
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.gem.ACKC10;
import com.shimizukenta.secs.gem.COMMACK;
import com.shimizukenta.secs.gem.OFLACK;
import com.shimizukenta.secs.gem.ONLACK;
import com.shimizukenta.secs.gem.TIACK;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Builder;
import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secs.secs2.Secs2Exception;
import com.shimizukenta.secs.secs2.Secs2Item;
import com.shimizukenta.secs.secs2.Secs2IrregalDataFormatException;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.MessageEvent;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secssim.app.session.HostReportTable;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.event.ConditionDebouncer;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Fire;
import com.shimizukenta.secssim.core.event.RateLimiter;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.template.ExpressionException;
import com.shimizukenta.secssim.core.value.CurveValueSource;
import com.shimizukenta.secssim.core.trace.TracePlan;
import com.shimizukenta.secssim.core.value.ValueSource;
import com.shimizukenta.secssim.core.value.ValueStore;
import com.shimizukenta.secssim.runtime.sml.SmlParseException;
import com.shimizukenta.secssim.runtime.sml.SmlParser;
import com.shimizukenta.secssim.runtime.sml.SmlRenderer;

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
    /** 终端消息只保留最近 N 条；再老的现场也不需要回看。 */
    private static final int MAX_TERMINAL_MESSAGES = 20;
    /** S6F11 等 ACK 的队列上限：主机不答 S6F12 时不能无限堆积。 */
    private static final int S6F11_ACK_QUEUE = 256;

    /** 二进制预览格式版本与固定头长度。 */
    private static final short PREVIEW_BINARY_VERSION = 1;
    private static final int PREVIEW_BINARY_HEADER_BYTES = 48;

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
    private final AtomicLong terminalReceived = new AtomicLong();
    private final Deque<TerminalMessage> terminalMessages = new ArrayDeque<>();
    private final AtomicLong alarmsSent = new AtomicLong();
    private final AtomicLong alarmDropped = new AtomicLong();
    private final Map<Integer, AlarmState> activeAlarms = new ConcurrentHashMap<>();
    private final Map<String, ConditionDebouncer> conditionDebouncers = new ConcurrentHashMap<>();
    private final Set<String> conditionErrorLogged = ConcurrentHashMap.newKeySet();
    /** AfterEvent 的待触发队列；按计划时刻升序。 */
    private final PriorityQueue<PendingFire> pendingFires = new PriorityQueue<>(
            Comparator.comparingLong(PendingFire::atMs).thenComparing(PendingFire::planId));

    private volatile SecsCommunicator communicator;
    private volatile String lastError;
    private volatile EventBus eventBus;
    private volatile boolean communicatable;
    private volatile EquipmentControlState controlState = EquipmentControlState.OFFLINE;
    private volatile EventSchedule schedule = EventSchedule.empty();
    private final Set<String> disabledPlans = ConcurrentHashMap.newKeySet();
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
        PreviewData data = this.previewData(fromMs, toMs, buckets);
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        java.util.List<com.shimizukenta.jsonhub.JsonHub> series = new java.util.ArrayList<>();

        for (PreviewSeries value : data.series()) {
            series.add(b.object(
                    b.pair("svid", value.svid()),
                    b.pair("owner", value.owner()),
                    b.pair("unit", value.unit()),
                    b.pair("min", b.array(numbers(value.min(), b))),
                    b.pair("max", b.array(numbers(value.max(), b))),
                    b.pair("avg", b.array(numbers(value.avg(), b)))));
        }

        return b.object(
                b.pair("from", data.fromMs()),
                b.pair("to", data.toMs()),
                b.pair("buckets", data.buckets()),
                b.pair("bucketMs", data.bucketMs()),
                b.pair("svidTotal", data.svidTotal()),
                b.pair("truncated", data.svidTotal() > data.series().size()),
                b.pair("series", b.array(series))).toJson();
    }

    /**
     * 预览的二进制格式（小端）。
     *
     * <pre>
     * magic 4 字节 "SCSP"
     * version u16 = 1
     * flags   u16 = 0
     * from    i64
     * to      i64
     * buckets u32
     * bucketMs u64
     * svidTotal u32
     * seriesCount u32
     * reserved u32
     * 每条序列：svid i32, min f64[buckets], max f64[buckets], avg f64[buckets]
     * </pre>
     *
     * @param fromMs  起始
     * @param toMs    结束
     * @param buckets 桶数（1..500）
     * @return 二进制正文
     */
    @Override
    public byte[] previewBinary(long fromMs, long toMs, int buckets) {
        PreviewData data = this.previewData(fromMs, toMs, buckets);
        int count = data.buckets();
        int seriesCount = data.series().size();
        int size = PREVIEW_BINARY_HEADER_BYTES
                + seriesCount * (Integer.BYTES + 3 * count * Double.BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) 'S').put((byte) 'C').put((byte) 'S').put((byte) 'P');
        buffer.putShort(PREVIEW_BINARY_VERSION);
        buffer.putShort((short) 0);
        buffer.putLong(data.fromMs());
        buffer.putLong(data.toMs());
        buffer.putInt(count);
        buffer.putLong(data.bucketMs());
        buffer.putInt(data.svidTotal());
        buffer.putInt(seriesCount);
        buffer.putInt(0);

        for (PreviewSeries value : data.series()) {
            buffer.putInt(value.svid());
            for (double v : value.min()) {
                buffer.putDouble(v);
            }
            for (double v : value.max()) {
                buffer.putDouble(v);
            }
            for (double v : value.avg()) {
                buffer.putDouble(v);
            }
        }
        return buffer.array();
    }

    private PreviewData previewData(long fromMs, long toMs, int buckets) {
        int count = Math.max(1, Math.min(500, buckets));
        long span = Math.max(1L, toMs - fromMs);
        long bucketMs = Math.max(1L, span / count);
        long sampleMs = Math.max(1L, bucketMs / 4L);

        java.util.List<Integer> allSvids = this.values.svids().stream().sorted().toList();
        java.util.List<Integer> svids = allSvids.stream().limit(32).toList();
        java.util.List<PreviewSeries> series = new java.util.ArrayList<>();

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

            ResolvedSvid resolved = this.model.svids().get(svid);
            series.add(new PreviewSeries(svid,
                    resolved == null ? "" : resolved.owner(),
                    resolved == null ? "" : resolved.unitSymbol(),
                    min, max, avg));
        }

        return new PreviewData(fromMs, toMs, count, bucketMs, allSvids.size(), series);
    }

    private record PreviewData(long fromMs, long toMs, int buckets, long bucketMs,
                               int svidTotal, java.util.List<PreviewSeries> series) {
    }

    private record PreviewSeries(int svid, String owner, String unit,
                                 double[] min, double[] max, double[] avg) {
    }

    /**
     * 手动发送一条 SML 报文。
     *
     * <p>解析失败是调用方的问题（400），会话没有对端是设备状态（409），
     * 协议层发送失败才是 500。三者不能混成一个 "send failed"。</p>
     *
     * @param sml SML 文本
     * @return 发送结果 JSON
     * @throws IOException 发送失败
     */
    @Override
    public String sendSml(String sml) throws IOException {
        SecsCommunicator comm = this.communicator;
        if (this.state() != SessionState.OPEN || comm == null || !this.communicatable) {
            throw new IllegalStateException("会话未打开或尚未与主机建立通信");
        }

        SmlParser.Message message;
        try {
            message = SmlParser.parse(sml);
        } catch (SmlParseException e) {
            throw new IllegalArgumentException("SML 解析失败: " + e.getMessage(), e);
        }

        long startedAt = System.currentTimeMillis();
        Optional<SecsMessage> reply;
        boolean waitTimedOut = false;
        try {
            reply = comm.send(message.stream(), message.function(), message.wbit(), message.body());
        } catch (SecsWaitReplyMessageException e) {
            /* T3 超时不是"没发出去"：报文已经发了，只是主机没回 */
            reply = Optional.empty();
            waitTimedOut = true;
        } catch (SecsException e) {
            throw new IOException("发送失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("发送被中断", e);
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;

        String replySml = reply.map(value -> SmlRenderer.render(value.secs2())).orElse(null);
        String sentSml = message.body().size() < 0 ? "" : SmlRenderer.render(message.body());
        JsonHubBuilder b = JsonHub.getBuilder();
        return b.object(
                b.pair("ok", true),
                b.pair("stream", message.stream()),
                b.pair("function", message.function()),
                b.pair("wbit", message.wbit()),
                b.pair("sentSml", sentSml),
                b.pair("timedOut", waitTimedOut || (message.wbit() && reply.isEmpty())),
                b.pair("replyStream", reply.map(SecsMessage::getStream).orElse(0)),
                b.pair("replyFunction", reply.map(SecsMessage::getFunction).orElse(0)),
                b.pair("replyWbit", reply.map(SecsMessage::wbit).orElse(false)),
                b.pair("replySml", replySml),
                b.pair("elapsedMs", elapsedMs))
                .toJson();
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
        this.disabledPlans.clear();

        this.conditionDebouncers.clear();
        this.conditionErrorLogged.clear();
        synchronized (this.pendingFires) {
            this.pendingFires.clear();
        }
        for (EventPlan plan : this.schedule.plans()) {
            if (plan.trigger() instanceof Trigger.OnCondition condition) {
                this.conditionDebouncers.put(plan.id(), new ConditionDebouncer(condition.debounceMs()));
            }
        }
    }

    /**
     * 当前编排列表（含启用状态与触发规则）。
     *
     * @return JSON 文本
     */
    public String eventsJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        java.util.List<JsonHub> events = new java.util.ArrayList<>();

        for (EventPlan plan : this.schedule.plans()) {
            java.util.List<com.shimizukenta.jsonhub.JsonObjectPair> pairs = new java.util.ArrayList<>();
            pairs.add(b.pair("id", plan.id()));
            pairs.add(b.pair("ceid", plan.ceid()));
            pairs.add(b.pair("enabled", !this.disabledPlans.contains(plan.id())));
            triggerJson(plan.trigger(), pairs, b);
            events.add(b.object(pairs));
        }

        return b.object(b.pair("events", b.array(events))).toJson();
    }

    /**
     * 运行时启用/禁用一个计划。
     *
     * <p>禁用会清掉该计划的条件去抖状态与延迟队列条目：重新启用后
     * 不应把禁用期间攒下的事件补发出去。</p>
     *
     * @param planId  计划标识
     * @param enabled 是否启用
     * @return 计划存在返回 {@code true}
     */
    public boolean setEventEnabled(String planId, boolean enabled) {
        EventPlan target = null;
        for (EventPlan plan : this.schedule.plans()) {
            if (plan.id().equals(planId)) {
                target = plan;
                break;
            }
        }
        if (target == null) {
            return false;
        }

        if (enabled) {
            this.disabledPlans.remove(planId);
            if (target.trigger() instanceof Trigger.OnCondition condition) {
                this.conditionDebouncers.computeIfAbsent(planId,
                        id -> new ConditionDebouncer(condition.debounceMs()));
            }
        } else {
            this.disabledPlans.add(planId);
            this.conditionDebouncers.remove(planId);
            synchronized (this.pendingFires) {
                this.pendingFires.removeIf(fire -> fire.planId().equals(planId));
            }
        }
        return true;
    }

    private static void triggerJson(Trigger trigger,
                                    java.util.List<com.shimizukenta.jsonhub.JsonObjectPair> pairs,
                                    JsonHubBuilder b) {
        if (trigger instanceof Trigger.At at) {
            pairs.add(b.pair("triggerType", "at"));
            pairs.add(b.pair("atMs", at.atMs()));
        } else if (trigger instanceof Trigger.Periodic periodic) {
            pairs.add(b.pair("triggerType", "periodic"));
            pairs.add(b.pair("periodMs", periodic.periodMs()));
            pairs.add(b.pair("jitterMs", periodic.jitterMs()));
        } else if (trigger instanceof Trigger.OnCondition condition) {
            pairs.add(b.pair("triggerType", "onCondition"));
            pairs.add(b.pair("expression", condition.expression().source()));
            pairs.add(b.pair("debounceMs", condition.debounceMs()));
        } else if (trigger instanceof Trigger.AfterEvent after) {
            pairs.add(b.pair("triggerType", "afterEvent"));
            pairs.add(b.pair("sourcePlan", after.sourcePlan()));
            pairs.add(b.pair("delayMs", after.delayMs()));
        }
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

        if (!this.canSendUnsolicited()) {
            this.lastFireMs = this.clock.nowMs();
            this.resetConditions();
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

    /**
     * 上报/清除一个报警（S5F1）。
     *
     * <p>S5F1 默认带 W 位并等待 S5F2；发送放到 ACK 队列，HTTP 请求不会阻塞到 T3。
     * ALCD 高位置 1 表示置位、清 0 表示清除，低 3 位是类别。</p>
     */
    @Override
    public void emitAlarm(int alid, boolean set, String text, int category) throws IOException {
        SecsCommunicator comm = this.communicator;
        if (comm == null) {
            throw new IOException("session not open: " + this.spec.id());
        }
        if (alid < 1) {
            throw new IOException("ALID 必须 >= 1: " + alid);
        }
        if (category < 0 || category > 7) {
            throw new IOException("报警类别必须在 0..7: " + category);
        }

        String altx = text == null || text.isBlank()
                ? (set ? "ALARM " + alid : "CLEAR " + alid)
                : text;
        byte alcd = (byte) ((set ? 0x80 : 0x00) | category);
        Secs2 body = Secs2.getBuilder().list(
                Secs2.getBuilder().binary(alcd),
                Secs2.getBuilder().uint4(alid),
                Secs2.getBuilder().ascii(altx));

        try {
            this.ackSender.execute(() -> {
                try {
                    comm.send(5, 1, true, body);
                    this.alarmsSent.incrementAndGet();
                    if (set) {
                        this.activeAlarms.put(alid,
                                new AlarmState(alid, altx, category, System.currentTimeMillis()));
                    } else {
                        this.activeAlarms.remove(alid);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.lastError = "等待 S5F2 被中断: ALID " + alid;
                } catch (Exception e) {
                    this.lastError = e.toString();
                    Log.error("会话 " + this.spec.id() + " 等待 S5F2 失败: ALID " + alid, e);
                }
            });
        } catch (RejectedExecutionException e) {
            this.alarmDropped.incrementAndGet();
            throw new IOException("S5F1 ACK 队列已满: ALID " + alid, e);
        }
    }

    /**
     * 当前处于置位状态的报警。
     *
     * @return 快照
     */
    public List<AlarmState> alarms() {
        return List.copyOf(this.activeAlarms.values());
    }

    @Override
    public String alarmsJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<AlarmState> sorted = new ArrayList<>(this.activeAlarms.values());
        sorted.sort(Comparator.comparingInt(AlarmState::alid));

        List<JsonHub> items = new ArrayList<>(sorted.size());
        for (AlarmState alarm : sorted) {
            items.add(b.object(
                    b.pair("alid", alarm.alid()),
                    b.pair("text", alarm.text()),
                    b.pair("category", alarm.category()),
                    b.pair("atMillis", alarm.atMillis())));
        }
        return b.array(items).toJson();
    }

    /**
     * 一条活动报警。
     *
     * @param alid     ALID
     * @param text     报警文本
     * @param category 类别 0..7
     * @param atMillis 置位时刻（墙钟）
     */
    public record AlarmState(int alid, String text, int category, long atMillis) {
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
            comm.addSecsCommunicatableStateChangeListener(value -> {
                this.communicatable = value;
                if (!value) {
                    this.controlState = EquipmentControlState.OFFLINE;
                } else if (this.controlState == EquipmentControlState.OFFLINE) {
                    this.controlState = EquipmentControlState.COMMUNICATING;
                }
            });
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

    /**
     * 是否允许主动发送（S6F11 / S6F1 这类 unsolicited）。
     *
     * <p>E30 的两条约束，本项目历史上只满足第一条：</p>
     * <ol>
     *   <li>链路没 SELECTED（{@code communicatable=false}）时不能发——发送必然失败；</li>
     *   <li>链路选上了但主机还没建立通信（S1F13 之前）或已 S1F15 下线时，
     *       也只能发 S1F13/S9Fx 这类少数报文。<b>主动上报必须等 ONLINE 之后</b>。</li>
     * </ol>
     *
     * <p>修复前 {@code controlState} 只用于界面显示，OFFLINE 之后 S6F11/S6F1 照发不误，
     * 主机侧会看到"设备说它下线了，却还在上报数据"——严格的 GEM 主机按违规处理。</p>
     *
     * <p>状态搬迁：建链 → {@code COMMUNICATING}（此时主机还没发 S1F13）；
     * S1F15/S1F17 → {@code OFFLINE}/{@code ONLINE}；断链 → {@code OFFLINE}。
     * 所以真正能发主动上报的只有 {@code ONLINE} 这一档，其余两档都要拦住。</p>
     */
    private boolean canSendUnsolicited() {
        return this.communicatable && this.controlState == EquipmentControlState.ONLINE;
    }

    @Override
    public EquipmentControlState controlState() {
        return this.controlState;
    }

    @Override
    public boolean hasPeer() {
        return this.communicatable;
    }

    @Override
    public Map<String, Long> counters() {
        return Map.ofEntries(
                Map.entry("emitted", this.emitted.get()),
                Map.entry("rejected", this.rejected.get()),
                Map.entry("skipped", this.skipped.get()),
                Map.entry("wbitDropped", this.wbitDropped.get()),
                Map.entry("traceWbitDropped", this.traceWbitDropped.get()),
                Map.entry("terminalMessages", this.terminalReceived.get()),
                Map.entry("alarmsSent", this.alarmsSent.get()),
                Map.entry("alarmDropped", this.alarmDropped.get()),
                Map.entry("activeAlarms", (long) this.activeAlarms.size()),
                Map.entry("traceFrames", this.traceFrames.get()),
                Map.entry("activeTraces", (long) this.traces.activeCount()),
                Map.entry("hostReports", (long) this.hostReports.reportCount()),
                Map.entry("hostLinks", (long) this.hostReports.linkCount()));
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
            if (!this.canSendUnsolicited()) {
                /* 没有主机在线时不要积压事件：上线后从当前时刻重新开始，避免一次涌出几百条 */
                this.lastFireMs = this.clock.nowMs();
                this.resetConditions();
                synchronized (this.pendingFires) {
                    this.pendingFires.clear();
                }
                return;
            }

            long now = this.clock.nowMs();
            long from = this.lastFireMs;

            /* 条件触发必须每个 tick 求值，不能只靠区间查询 */
            this.pumpConditions(now);

            if (now > from) {
                this.lastFireMs = now;
                for (Fire fire : this.schedule.between(from, now, this.eventRng)) {
                    this.firePlan(fire.planId(), fire.ceid(), fire.atMs(), now);
                }
            }

            /* AfterEvent 可能在同一 tick 内到期，甚至级联到下一层 */
            this.pumpPending(now);
        } catch (Exception e) {
            this.lastError = e.toString();
            Log.error("会话 " + this.spec.id() + " 事件上报失败", e);
        }
    }

    /**
     * 求值全部 OnCondition 计划。
     *
     * <p>去抖状态在 {@link ConditionDebouncer} 里；这里只负责把当前 SVID 值
     * 喂给表达式、按限流器发 CEID，并在主机掉线/事件被禁用时清状态。</p>
     */
    private void pumpConditions(long nowMs) {
        for (EventPlan plan : this.schedule.plans()) {
            if (!(plan.trigger() instanceof Trigger.OnCondition condition)) {
                continue;
            }

            ConditionDebouncer debouncer = this.conditionDebouncers.computeIfAbsent(
                    plan.id(), id -> new ConditionDebouncer(condition.debounceMs()));

            if (this.disabledPlans.contains(plan.id())) {
                debouncer.reset();
                continue;
            }

            if (!this.hostReports.isEnabled(plan.ceid())) {
                debouncer.reset();
                continue;
            }

            boolean active;
            try {
                active = condition.expression().evaluate(name -> this.conditionVariable(name, nowMs)) != 0.0;
            } catch (RuntimeException e) {
                this.lastError = e.toString();
                if (this.conditionErrorLogged.add(plan.id())) {
                    Log.error("会话 " + this.spec.id() + " 条件表达式失败: plan " + plan.id(), e);
                }
                debouncer.reset();
                continue;
            }
            this.conditionErrorLogged.remove(plan.id());

            if (debouncer.update(nowMs, active)) {
                this.firePlan(plan.id(), plan.ceid(), nowMs, nowMs);
            }
        }
    }

    /**
     * 触发一个计划：限流、发 S6F11、成功后记录 AfterEvent 链。
     *
     * <p>所有路径（区间触发 / 条件触发 / 延迟链）都从这里走，
     * 保证链式事件不会绕过设备级限流。</p>
     */
    private void firePlan(String planId, int ceid, long atMs, long nowMs) {
        if (this.disabledPlans.contains(planId)) {
            return;
        }
        if (!this.hostReports.isEnabled(ceid)) {
            this.skipped.incrementAndGet();
            return;
        }
        if (!this.limiter.tryAcquire(nowMs)) {
            this.rejected.incrementAndGet();
            return;
        }

        try {
            this.emitCeid(ceid);
            this.recordFire(planId, atMs);
        } catch (Exception e) {
            this.lastError = e.toString();
            Log.error("会话 " + this.spec.id() + " 计划触发失败: plan " + planId, e);
        }
    }

    /** 源计划成功后，把它的 AfterEvent 后继加入延迟队列。 */
    private void recordFire(String planId, long atMs) {
        synchronized (this.pendingFires) {
            for (EventPlan plan : this.schedule.plans()) {
                if (!this.disabledPlans.contains(plan.id())
                        && plan.trigger() instanceof Trigger.AfterEvent after
                        && after.sourcePlan().equals(planId)) {
                    this.pendingFires.add(new PendingFire(atMs + after.delayMs(), plan.id(), plan.ceid()));
                }
            }
        }
    }

    /** 取出到期的延迟事件；允许同一 tick 内级联，但限制跳数防环。 */
    private void pumpPending(long nowMs) {
        int guard = 0;

        for (;;) {
            PendingFire next;
            synchronized (this.pendingFires) {
                next = this.pendingFires.peek();
                if (next == null || next.atMs() > nowMs) {
                    return;
                }
                this.pendingFires.poll();
            }

            if (++guard > 64) {
                this.lastError = "AfterEvent 链超过 64 跳，疑似环: " + next.planId();
                Log.error("会话 " + this.spec.id() + " " + this.lastError, null);
                return;
            }

            this.firePlan(next.planId(), next.ceid(), next.atMs(), nowMs);
        }
    }

    private record PendingFire(long atMs, String planId, int ceid) {
    }

    private double conditionVariable(String name, long nowMs) {
        if ("t".equals(name)) {
            return nowMs / 1000.0;
        }
        if (name.startsWith("svid.")) {
            int svid;
            try {
                svid = Integer.parseInt(name.substring("svid.".length()));
            } catch (NumberFormatException e) {
                throw new ExpressionException("条件表达式里的 SVID 非法: " + name, e);
            }
            return this.values.valueAt(svid, nowMs).orElseThrow(
                    () -> new ExpressionException("条件表达式引用的 SVID 没有值: " + svid));
        }
        throw new ExpressionException("条件表达式只支持 t 和 svid.<id>: " + name);
    }

    private void resetConditions() {
        for (ConditionDebouncer debouncer : this.conditionDebouncers.values()) {
            debouncer.reset();
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

            /* E5：S9F3/S9F5 只能针对无法识别的 primary 发送，迟到的 secondary 必须静默丢弃。
               协议层在 W=0 时不登记应答管理器，主机按惯例回的 S6F12/S6F2 会一路进到本方法；
               若不在这里挡掉，就会对一条 secondary 回 S9F5（SystemBytes 与主机应答相同的新 primary），
               严格的主机会走异常处理甚至断链。偶数 function 一律视为 secondary。 */
            if (function % 2 == 0) {
                return;
            }

            /* 控制状态类报文委托给协议层的 Gem：这些应答的形状（COMMACK/ONLACK/TIACK/时钟格式）
               都已按 E30/E5 实现，自己重写只会引入偏差 */
            switch (stream * 100 + function) {
                case 101 -> {
                    this.communicator.gem().s1f2(primary);
                    return;
                }
                case 113 -> {
                    this.communicator.gem().s1f14(primary, COMMACK.OK);
                    if (this.controlState == EquipmentControlState.OFFLINE) {
                        this.controlState = EquipmentControlState.COMMUNICATING;
                    }
                    return;
                }
                case 115 -> {
                    this.communicator.gem().s1f16(primary);
                    this.controlState = EquipmentControlState.OFFLINE;
                    return;
                }
                case 117 -> {
                    this.communicator.gem().s1f18(primary, ONLACK.OK);
                    this.controlState = EquipmentControlState.ONLINE;
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
                case 241 -> {
                    handleRemoteCommand(primary);
                    return;
                }
                case 1003 -> {
                    handleTerminalDisplay(primary);
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
     * S10F3：主机发来一条终端消息。
     *
     * <p>E5 的结构是 {@code <L[2] <B TID> <A TEXT>>}。设备只负责“接收并显示”，
     * 因此保存最近 {@value #MAX_TERMINAL_MESSAGES} 条并回 S10F4；格式不对回
     * {@code MessageWillNotBeDisplayed}，而不是静默丢弃。</p>
     */
    private void handleTerminalDisplay(SecsMessage primary) {
        ACKC10 ack = ACKC10.AcceptedForDisplay;

        try {
            Secs2 body = primary.secs2();
            if (body.size() < 2) {
                throw new IllegalArgumentException("S10F3 需要 TID + TEXT");
            }

            int tid = body.get(0).getByte(0) & 0xFF;
            String text = body.get(1).getAscii();

            synchronized (this.terminalMessages) {
                this.terminalMessages.addLast(new TerminalMessage(tid, text, System.currentTimeMillis()));
                while (this.terminalMessages.size() > MAX_TERMINAL_MESSAGES) {
                    this.terminalMessages.removeFirst();
                }
            }
            this.terminalReceived.incrementAndGet();
        } catch (Exception e) {
            this.lastError = e.toString();
            ack = ACKC10.MessageWillNotBeDisplayed;
            Log.error("会话 " + this.spec.id() + " 处理 S10F3 失败", e);
        }

        try {
            /*
             * 不用 gem().s10f4：协议层 s10fx 用的是 comm.send(10, 4, ...)，
             * 发的是新的 Primary，而不是对 S10F3 的 Reply，主机会一直等 T3。
             * 这里显式用 primary 构造 Secondary。
             */
            this.communicator.send(primary, 10, 4, false, ack.secs2());
        } catch (Exception e) {
            this.lastError = e.toString();
            Log.error("会话 " + this.spec.id() + " 回复 S10F4 失败", e);
        }
    }

    /**
     * 最近收到的终端消息（新→旧）。
     *
     * @return 不可变快照
     */
    public List<TerminalMessage> terminalMessages() {
        synchronized (this.terminalMessages) {
            return List.copyOf(this.terminalMessages);
        }
    }

    @Override
    public String terminalMessagesJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> items = new ArrayList<>();

        for (TerminalMessage message : this.terminalMessages()) {
            items.add(b.object(
                    b.pair("tid", message.tid()),
                    b.pair("text", message.text()),
                    b.pair("atMillis", message.atMillis())));
        }
        return b.array(items).toJson();
    }

    /**
     * SVID / RPTID / CEID 目录，<b>反映实际发出去的那一份</b>，不只是本地配置。
     *
     * <p>取的是 {@link HostReportTable} 叠加之后的<b>有效</b>定义：主机用
     * S2F33/S2F35 下发过的条目以主机为准（ADR-0006），没碰过的仍是本地配置。
     * </p>
     *
     * <p>只列本地配置是不够的：主机完全新建一个本地没有的 RPTID 时，它一样会被发出去。
     * 漏掉这种条目，现场就只能对着报文猜设备"哪来的这个报告"。
     * </p>
     *
     * <p>按 ID 升序：这是给人看的清单，稳定顺序才能让界面上两次刷新之间的
     * 差异一眼看出是配置变了，而不是"顺序抖了"。
     * </p>
     */
    @Override
    public String catalogJson() {
        JsonHubBuilder b = JsonHub.getBuilder();

        /* 本地配置 + 主机新建的条目，键是绝对 ID */
        Map<Integer, ResolvedReport> reports = new TreeMap<>(this.model.reports());
        Map<Integer, ResolvedCeid> ceids = new TreeMap<>(this.model.ceids());

        List<Integer> hostCeids = new ArrayList<>(this.hostReports.definedCeids());
        for (int ceidId : hostCeids) {
            ceids.computeIfAbsent(ceidId, id -> new ResolvedCeid(id, "", "", List.of()));
        }
        List<Integer> hostRptids = new ArrayList<>(this.hostReports.definedRptids());
        for (int rptid : hostRptids) {
            reports.computeIfAbsent(rptid, id -> new ResolvedReport(id, "", "", List.of()));
        }

        List<JsonHub> reportItems = new ArrayList<>();
        for (ResolvedReport report : reports.values()) {
            /* 主机定义过就以主机为准，否则回落到本地配置 */
            List<Integer> effective = this.hostReports.svidsOf(report.id()).orElse(report.svidIds());
            reportItems.add(b.object(
                    b.pair("rptid", report.id()),
                    b.pair("owner", report.owner()),
                    b.pair("slot", report.slot()),
                    b.pair("svids", b.array(numbers(b, effective))),
                    b.pair("source", this.hostReports.svidsOf(report.id()).isPresent() ? "host" : "local")));
        }

        List<JsonHub> ceidItems = new ArrayList<>();
        for (ResolvedCeid ceid : ceids.values()) {
            List<Integer> effective = this.hostReports.reportsFor(ceid.id()).orElse(ceid.reportIds());
            ceidItems.add(b.object(
                    b.pair("ceid", ceid.id()),
                    b.pair("owner", ceid.owner()),
                    b.pair("slot", ceid.slot()),
                    b.pair("reports", b.array(numbers(b, effective))),
                    b.pair("source", this.hostReports.reportsFor(ceid.id()).isPresent() ? "host" : "local"),
                    b.pair("enabled", this.hostReports.isEnabled(ceid.id()))));
        }

        List<JsonHub> svids = new ArrayList<>();
        this.model.svids().values().stream()
                .sorted(Comparator.comparingInt(ResolvedSvid::id))
                .forEach(svid -> svids.add(b.object(
                        b.pair("svid", svid.id()),
                        b.pair("owner", svid.owner()),
                        b.pair("slot", svid.slot()),
                        b.pair("type", svid.type().name()),
                        b.pair("unit", svid.unitSymbol()))));

        return b.object(
                b.pair("ceids", b.array(ceidItems)),
                b.pair("reports", b.array(reportItems)),
                b.pair("svids", b.array(svids)),
                b.pair("hostReports", this.hostReports.reportCount()),
                b.pair("hostLinks", this.hostReports.linkCount()),
                b.pair("hostAllDisabled", this.hostReports.isAllDisabled())).toJson();
    }

    /** 整数列表转 JSON 数组。 */
    private static List<JsonHub> numbers(JsonHubBuilder b, List<Integer> values) {
        return values.stream().map(b::number).map(n -> (JsonHub) n).toList();
    }

    /**
     * 一条终端消息。
     *
     * @param tid      终端 ID
     * @param text     文本
     * @param atMillis 收到时的墙钟毫秒
     */
    public record TerminalMessage(int tid, String text, long atMillis) {    }

    /**
     * S2F41：主机下发远程命令。回 S2F42。
     *
     * <p>E30 的形状：</p>
     * <pre>
     * S2F41 W  &lt;L[2] &lt;A RCMD&gt; &lt;L[n] &lt;L[2] &lt;A CPNAME&gt; &lt;V CPVAL&gt;&gt;&gt;&gt;
     * S2F42    &lt;L[2] &lt;B HCACK&gt; &lt;L[n] &lt;L[2] &lt;A CPNAME&gt; &lt;V CPACK&gt;&gt;&gt;&gt;
     * </pre>
     *
     * <p>HCACK 语义按 E30 §执行条件：</p>
     * <ul>
     *   <li>{@code 0} 接受并已执行；</li>
     *   <li>{@code 1} 命令存在但参数非法（本实现的命令集不带参数，因此只在形状非法时出现）；</li>
     *   <li>{@code 2} 当前无法执行（不在 ONLINE / 控制器处于 LOCAL）；</li>
     *   <li>{@code 3} 命令不存在；</li>
     *   <li>{@code 4} 需要初始化（S1F13 未完成）。</li>
     * </ul>
     *
     * <p><b>能力边界</b>：模拟器不对 RCMD 做任何设备动作（没有真实的 START/STOP 语义），
     * 只回结构正确的 S2F42。这样主机上线流程里"探测命令支持"的一步能走通，
     * 而不是落 S9F5 —— 对主机来说"不支持命令"与"设备故障"是两回事，
     * 回 S9F5 会让它走异常分支。真实动作需要下游给出命令集与副作用定义后再接。</p>
     */
    private void handleRemoteCommand(SecsMessage primary) throws Exception {
        int hcack;

        try {
            Secs2 body = primary.secs2();

            if (body.size() == 0) {
                /* 体为空：形状不合法，回 1。不能让 get(0) 抛下标越界后再混进同一个 catch，
                   否则日志里看不出"主机发了空报文"还是"RCMD 类型错"。 */
                throw new Secs2IrregalDataFormatException("S2F41 报文体为空");
            }

            String rcmd = body.get(0).getAscii();

            if (rcmd == null || rcmd.isBlank()) {
                hcack = 1;
            } else if (this.controlState == EquipmentControlState.OFFLINE
                    || this.controlState == EquipmentControlState.COMMUNICATING) {
                /* 通信尚未建立或已离线：E30 要求回 4（需初始化），而不是假装执行了 */
                hcack = 4;
            } else {
                /* ONLINE：模拟器没有真实设备动作，命令"被接受"但不产生副作用 */
                hcack = 0;
            }
        } catch (Exception e) {
            this.lastError = e.toString();
            hcack = 1;
        }

        Secs2Builder b = Secs2.getBuilder();
        this.communicator.send(primary, 2, 42, false, b.list(
                b.binary((byte) hcack),
                /* 本项目所有命令都不带参数，参数级确认列表为空 */
                b.list()));
    }

    /**
     * S2F33：主机定义报告（RPTID → VID 列表）。
     *
     * <p>空的报告列表表示"清空全部主机定义"，此时回落到本地配置（ADR 0006）。</p>
     */
    private void handleDefineReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 reportList = reportListOf(primary.secs2());

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
     * 从 S2F33 报文体取出报告列表。
     *
     * <p>E5 的标准形状是 {@code L[2]{U4 DATAID, L[n]{L[2]{U4 RPTID, L[m]{U4 VID}}}}}
     * —— 报告列表在<b>第 2 个</b>元素。早期夹具和少数实现的省略写法是
     * {@code L{L{...}, L{}}}，报告列表在第 1 个元素。两种都要能吃下：
     * 只认一种的后果是标准主机发来时把 DATAID 标量当成列表，
     * 取元素抛 {@code Secs2IrregalDataFormatException} → 恒回 DRACK=1，动态报告彻底不可用。</p>
     *
     * <p><b>判定顺序必须先标准、后兼容</b>：标准形状的 DATAID 是 U4 标量，非标形状的第 1 个元素
     * 是列表，两者按类型就能分开。不能反过来“先看第 2 个元素是不是 L[0] 就当成兼容形状”——
     * 标准形状的“清空全部”（{@code L[2]{DATAID=0, L[0]}}）正是这个样子，
     * 抢判会把 DATAID 当成“报告 0”写进覆盖层，主机后面的事件就发不出来了。</p>
     *
     * <p>标准形状里 DATAID 不是 U4（例如主机把 DATAID 也写成列表）时，按标准解析失败，
     * 再回退到非标形状解析——这是唯一会被两种形状同时命中的情形。</p>
     *
     * @param body S2F33 报文体
     * @return 报告列表（可能是 {@code L[0]}）
     * @throws Secs2Exception 形状无法识别
     */
    private static Secs2 reportListOf(Secs2 body) throws Secs2Exception {
        if (body.size() == 0) {
            throw new Secs2IrregalDataFormatException("S2F33 报文体为空");
        }

        if (body.size() >= 2) {
            Secs2 dataId = body.get(0);
            Secs2 second = body.get(1);

            if (!isList(dataId) && isList(second)
                    && (second.size() == 0 || isReportEntry(second.get(0)))) {
                return second;
            }
        }

        Secs2 first = body.get(0);

        if (isList(first) && (first.size() == 0 || isReportEntry(first.get(0)))) {
            return first;
        }

        throw new Secs2IrregalDataFormatException("S2F33 报文体里没有报告列表");
    }

    /**
     * 从 S2F35 报文体取出「事件 → 报告」链接列表。
     *
     * <p>形状判断与 {@link #reportListOf(Secs2)} 同一套规则：标准形状
     * {@code L[2]{DATAID, L[n]{L[2]{CEID, L[m] RPTID}}}} 优先，省略 DATAID 的
     * {@code L{L{...}, L{}}} 兼容保留。</p>
     *
     * @param body S2F35 报文体
     * @return 链接列表（可能是 {@code L[0]}）
     * @throws Secs2Exception 形状无法识别
     */
    private static Secs2 linkListOf(Secs2 body) throws Secs2Exception {
        if (body.size() == 0) {
            throw new Secs2IrregalDataFormatException("S2F35 报文体为空");
        }

        if (body.size() >= 2) {
            Secs2 dataId = body.get(0);
            Secs2 second = body.get(1);

            if (!isList(dataId) && isList(second)
                    && (second.size() == 0 || isLinkEntry(second.get(0)))) {
                return second;
            }
        }

        Secs2 first = body.get(0);

        if (isList(first) && (first.size() == 0 || isLinkEntry(first.get(0)))) {
            return first;
        }

        throw new Secs2IrregalDataFormatException("S2F35 报文体里没有事件链接列表");
    }

    /** {@code L[2]{U4 CEID, L[m] RPTID}} */
    private static boolean isLinkEntry(Secs2 item) throws Secs2Exception {
        return isScalarAndList(item);
    }

    /** {@code L[2]{U4 RPTID, L[m] VID}} */
    private static boolean isReportEntry(Secs2 item) throws Secs2Exception {
        return isScalarAndList(item);
    }

    /** 是否为 {@code L[2]{U4 标量, L[m]}}：S2F33 / S2F35 的条目都长这样。 */
    private static boolean isScalarAndList(Secs2 item) throws Secs2Exception {
        return isList(item) && item.size() == 2 && !isList(item.get(0)) && isList(item.get(1));
    }

    private static boolean isList(Secs2 item) {
        return item.secs2Item() == Secs2Item.LIST;
    }

    /**
     * S2F35：主机建立"事件 → 报告"的链接。
     */
    private void handleLinkEventReport(SecsMessage primary) throws Exception {
        int code = 0;

        try {
            Secs2 links = linkListOf(primary.secs2());

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

        if (!this.canSendUnsolicited()) {
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
