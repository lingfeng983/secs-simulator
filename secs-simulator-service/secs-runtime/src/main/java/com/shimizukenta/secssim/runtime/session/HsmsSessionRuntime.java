package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.app.sml.SmlParser;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.event.NoticeEvent;
import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secssim.app.session.HostReportTable;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.app.session.TraceLimits;
import com.shimizukenta.secssim.app.session.TraceLimits;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.ManualEngineClock;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.PlanAction;
import com.shimizukenta.secssim.core.value.CurveValueSource;
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
    private static final long RATE_WINDOW_MS = 1_000L;
    private static final int DEFAULT_MAX_EVENTS_PER_SEC = 50;
    /** S6F11 等 ACK 的队列上限：主机不答 S6F12 时不能无限堆积。 */
    private static final int S6F11_ACK_QUEUE = 256;

    private final SessionSpec spec;
    /** 设备模型：热生效时可整体替换（改单元/变量不再需要重建连接）。 */
    private volatile ResolvedEquipment model;
    private final ValueStore store;
    /** 可热替换的值来源：S6F11/Trace/预览都持有它，模板热生效只换内部实现。 */
    private final ValueSourceHandle values;
    /** values 委托里的效果层（「设定」钉值要落在它身上）。 */
    private volatile EffectValueSource effectValues;
    /** 事件效果的运行时记录(v6):手动触发的效果无法重放,只能记录。 */
    private final EventEffectStore effects = new EventEffectStore();
    /** overview 速率统计(60 秒滑动窗口)。 */
    private final RateTracker msgRate = new RateTracker();
    private final RateTracker eventRate = new RateTracker();
    private volatile long lastEventAtMs = 0L;
    private final long openedAtMs = System.currentTimeMillis();
    private final EngineClock clock;
    private final HostReportTable hostReports = new HostReportTable();

    /** Trace（S2F23）容量限制，来自 session.trace 配置段。 */
    private final TraceLimits traceLimits;
    /** S6F11 组装器：随设备模型热替换。 */
    private volatile S6F11Assembler assembler;

    /** 保护一次 pump 的完整状态推进，见 {@link #pumpNow()}。 */
    private final Object pumpLock = new Object();

    /**
     * 设备常量（ECV）当前值，按 ECID 索引。
     *
     * <p>初值来自配置；主机 S2F15 写入后这里是"新值"，并由 {@link #constantSaver}
     * 写回配置文件，所以重载/重启后仍然保留。</p>
     */
    private final Map<Integer, EquipmentConstantDef> constants;

    /** 主机侧报文（S2 家族）的协议翻译协作者。 */
    private final HostCommandHandler hostSide;

    /** Trace（周期采样）的采集与上报协作者。 */
    private final TracePump tracePump;

    /** 终端消息收件箱（S10F3 → S10F4）；连接建立后才可用。 */
    private volatile TerminalInbox terminalInbox;

    /** 采集事件上报（S6F11）。 */
    private volatile EventReporter eventReporter;

    /** Primary 分发：把主机发来的报文分派到对应处理路径。 */
    private volatile PrimaryDispatcher dispatcher;

    /** 不断连热生效。 */
    private final HotReloader hotReloader;

    /** 曲线预览。 */
    private final PreviewBuilder previewBuilder;

    /** 手动发送 SML。 */
    private final SmlSender smlSender;

    /** SVID 取值与 S1F4 应答。 */
    private final SvidReader svidReader;

    /** 对外播报（数据报文与状态通知）。 */
    private final SessionNotifier notifier;

    /** 计数与概览视图。 */
    private final SessionMetrics metrics;

    /** HSMS 连接。 */
    private final HsmsConnector connector;

    /** 事件心跳。 */
    private final EventTicker ticker;

    /** 运行时编辑（钉值与目录增删改）。 */
    private final RuntimeEditsFacade edits;

    /** 设备常量表。 */
    private final ConstantStore constantStore;

    /** 控制状态。 */
    private final ControlStateHolder control;

    /** 运行时目录：CEID / RPTID / SVID 的临时增删改。 */
    private final RuntimeCatalog catalog;

    /** 事件编排的运行时：计划表、限流、条件去抖与延迟链。 */
    private final EventScheduler scheduler;

    /** 报警（S5F1）的发送与活动报警表。 */
    private final AlarmReporter alarmReporter;
    private final Rng eventRng;

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong wbitDropped = new AtomicLong();

    private volatile SecsCommunicator communicator;
    private volatile String lastError;
    private volatile boolean s6f11Wbit;
    private volatile boolean s6f1Wbit;
    /** 单线程串行等 S6F12：W 位发送会阻塞到 T3，不能占用 100ms 事件心跳。 */
    private volatile ExecutorService ackSender;
    /** 单线程串行等 S6F2：Trace 采样不能被 ACK 阻塞。 */
    private volatile ExecutorService traceAckSender;
    /**
     * 单线程串行等 S5F2：报警的 ACK 等待必须与 S6F11 分开。
     *
     * <p>共用一个 ACK 线程时，主机不回 S5F2 会让后续所有 S6F11 的 W 位发送排队，
     * 队列满后成批丢弃采集事件——一条报警就能把事件流堵死。</p>
     */
    private volatile ExecutorService alarmAckSender;
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
        this(spec, model, clock, values, spec.traceLimits());
    }

    /**
     * 创建会话（全参数）。
     *
     * @param spec        会话参数
     * @param model       已解析的设备模型
     * @param clock       仿真时钟
     * @param values      值来源；传 {@code null} 表示用默认曲线
     * @param traceLimits Trace 容量限制（{@code null} 用默认）
     */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model, EngineClock clock,
                              ValueSource values,
                              TraceLimits traceLimits) {
        this.spec = spec;
        this.model = model;
        this.clock = clock;
        this.traceLimits = traceLimits == null
                ? TraceLimits.DEFAULTS
                : traceLimits;

        Map<Integer, EquipmentConstantDef> init = new LinkedHashMap<>();
        for (EquipmentConstantDef def : spec.equipment().constants()) {
            init.put(def.id(), def);
        }
        this.constants = java.util.Collections.synchronizedMap(init);
        this.control = new ControlStateHolder(next -> this.publishNotice(
                com.shimizukenta.secssim.app.event.NoticeEvent.SESSION,
                "{\"controlState\":\"" + next.name() + "\"}"));
        this.hostSide = new HostCommandHandler(this.constants, this.hostReports);
        this.hostSide.errorSink(this::recordError);
        this.hostSide.sessionId(spec.id());
        this.hostSide.modelSupplier(() -> this.model);
        this.hostSide.controlStateSupplier(() -> this.control.state());
        this.hostSide.replySink((primary, stream, function, body) -> {
            try {
                this.communicator.send(primary, stream, function, false, body);
            } catch (com.shimizukenta.secs.SecsException | InterruptedException e) {
                /* 回话失败说明链路已经断了：记下错误，让上层决定要不要断链 */
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                this.recordError(e);
            }
        });
        this.store = new ValueStore(spec.seed());

        ValueSource initial;
        if (values == null) {
            DefaultCurves.bind(this.store, spec.equipment(), model);
            initial = new CurveValueSource(this.store);
        } else {
            initial = values;
        }
        final EffectValueSource initialSource = new EffectValueSource(initial, this.effects);
        this.effectValues = initialSource;
        this.values = new ValueSourceHandle(initialSource);

        this.assembler = new S6F11Assembler(model, this.values, this.hostReports);
        this.ackSender = newAckSender("s6f11-ack-" + spec.id());
        this.traceAckSender = newAckSender("s6f1-ack-" + spec.id());
        this.alarmAckSender = newAckSender("s5f1-ack-" + spec.id());

        this.scheduler = new EventScheduler(new Rng(spec.seed()).derive("events"),
                () -> this.clock.nowMs(),
                () -> this.values,
                this::canSendUnsolicited,
                (ceid, planId) -> {
                    try {
                        this.emitCeid(ceid, planId);
                    } catch (IOException e) {
                        this.recordError(e);
                    }
                });
        this.scheduler.errorSink(this::recordError);
        this.scheduler.ceidEnabled(ceid -> this.hostReports.isEnabled(ceid));
        this.scheduler.knownCeid(ceid -> this.model.ceids().containsKey(ceid)
                || this.hostReports.definedCeids().contains(ceid));
        this.scheduler.sessionId(spec.id());
        this.catalog = new RuntimeCatalog(() -> this.model, this.hostReports);
        this.catalog.onModelChanged(this::refreshModelViews);
        this.catalog.store(this.store);
        this.tracePump = new TracePump(new TraceManager(model, this.values, Instant.now(),
                this.traceLimits.maxTracesOrDefault(), this.traceLimits.maxSvidCountOrDefault()),
                () -> this.traceAckSender, () -> this.clock.nowMs());
        this.tracePump.sessionId(spec.id());
        this.tracePump.errorSink(this::recordError);
        this.tracePump.canSend(this::canSendUnsolicited);
        this.tracePump.sender((stream, function, wbit, body) -> {
            try {
                this.communicator.send(stream, function, wbit, body);
            } catch (com.shimizukenta.secs.SecsException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                this.recordError(e);
            }
        });
        this.tracePump.replyer((primary, stream, function, body) -> {
            try {
                this.communicator.send(primary, stream, function, false, body);
            } catch (com.shimizukenta.secs.SecsException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                this.recordError(e);
            }
        });
        this.alarmReporter = new AlarmReporter(() -> this.alarmAckSender, spec.id(),
                () -> this.communicator,
                this::publishNotice,
                this::recordError);
        this.eventRng = new Rng(spec.seed()).derive("events");
        /* 预览取 scheduler 的当前计划（含运行时增删改、排除禁用），
           而不是装载时那份不可变的 schedule。 */
        this.previewBuilder = new PreviewBuilder(
                () -> this.values, () -> this.model, this.scheduler::previewSchedule, spec.seed());
        this.notifier = new SessionNotifier(spec.id(), this.msgRate);
        this.connector = new HsmsConnector(spec);
        this.ticker = new EventTicker("events-" + spec.id());
        this.edits = new RuntimeEditsFacade(this.catalog, () -> this.model, () -> this.effectValues);
        this.constantStore = new ConstantStore(this.constants);
        this.metrics = new SessionMetrics(
                () -> this.eventReporter.emitted(),
                () -> this.scheduler.rejectedCount(),
                () -> this.scheduler.skippedCount(),
                () -> this.eventReporter.wbitDropped(),
                () -> this.tracePump.wbitDropped(),
                () -> this.terminalInbox == null ? 0L : this.terminalInbox.received(),
                () -> this.alarmReporter.alarmsSent(),
                () -> this.alarmReporter.alarmDropped(),
                () -> (long) this.alarmReporter.alarms().size(),
                () -> this.tracePump.framesSent(),
                () -> this.tracePump.activeCount(),
                () -> (long) this.hostReports.reportCount(),
                () -> (long) this.hostReports.linkCount(),
                () -> this.dispatcher == null ? 0L : this.dispatcher.s9Replies(),
                () -> this.spec.id(),
                () -> this.state().name(),
                () -> this.control.state().name(),
                () -> this.control.communicatable(),
                () -> this.model.name(),
                () -> this.spec.equipment().units().size(),
                () -> this.spec.port(),
                () -> this.msgRate.countInWindow(System.currentTimeMillis()),
                () -> this.eventRate.countInWindow(System.currentTimeMillis()),
                () -> this.lastEventAtMs,
                () -> this.openedAtMs);
        this.svidReader = new SvidReader(() -> this.model,
                () -> this.values,
                () -> this.communicator,
                this.hostSide,
                () -> this.clock.nowMs());
        this.smlSender = new SmlSender(() -> this.communicator,
                () -> this.state() == com.shimizukenta.secssim.app.session.SessionState.OPEN,
                () -> this.control.communicatable());
        this.hotReloader = new HotReloader(this::refreshModelViews,
                this::collectionEventWbit,
                this::traceWbit,
                this::enableSchedule,
                this.effects,
                this.values,
                this.store,
                () -> this.spec.equipment());
        this.eventReporter = new EventReporter(() -> this.assembler,
                () -> this.communicator,
                () -> this.ackSender,
                this.effects,
                /* 实时表：含运行时增删改，且已排除禁用计划 */
                this.scheduler::previewSchedule,
                ceid -> this.hostReports.isEnabled(ceid),
                this.scheduler::recordSkipped,
                this::recordEventFired,
                this::publishNotice,
                () -> this.clock.nowMs(),
                () -> this.s6f11Wbit,
                spec.id(),
                this::recordError);
        this.eventReporter.valueReader(
                svid -> this.values.valueAt(svid, this.clock.nowMs()).orElse(Double.NaN));
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
        this.tracePump.s6f1Wbit(enabled);
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
     * 曲线预览：分桶 min/max 包络 + avg 线。计算与序列化在 {@link PreviewSupport}。
     *
     * @param fromMs  起始时刻
     * @param toMs    结束时刻
     * @param buckets 桶数（1..500）
     * @return JSON 文本
     */
    @Override
    public String previewJson(long fromMs, long toMs, int buckets) {
        return previewJson(fromMs, toMs, buckets, java.util.Set.of(), true);
    }

    @Override
    public String previewJson(long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents) {
        return this.previewBuilder.toJson(fromMs, toMs, buckets, svids, withEvents);
    }

    @Override
    public String previewJson(long fromMs, long toMs, int buckets,
                              java.util.Set<Integer> svids, boolean withEvents, String planId) {
        return this.previewBuilder.toJson(fromMs, toMs, buckets, svids, withEvents, planId);
    }

    /** 预览的二进制格式（SCSP v1，小端）。 */
    @Override
    public byte[] previewBinary(long fromMs, long toMs, int buckets) {
        return this.previewBuilder.toBinary(fromMs, toMs, buckets);
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
        return this.smlSender.send(sml);
    }

    /** 挂上事件总线：会话收发的每条数据报文都会以事件形式发布。 */
    public void eventBus(EventBus bus) {
        this.notifier.eventBus(bus);
    }

    /** 挂上状态通知总线：事件/报警/状态迁移会以 NoticeEvent 发布。 */
    public void noticeBus(NoticeBus bus) {
        this.notifier.noticeBus(bus);
    }

    /**
     * 迁移控制状态并在变化时发布通知。
     *
     * @param next 新状态
     */
    private void changeControlState(EquipmentControlState next) {
        this.control.change(next);
        /* 主机把控制状态切到 ONLINE 时，offlinePolicy=queue 攒下的触发按 FIFO 补跑。
           只在状态迁移时调一次；队列为空时 flushPending 直接返回 0。 */
        if (next == EquipmentControlState.ONLINE && this.eventReporter != null) {
            this.eventReporter.replayPending();
        }
    }

    /** 发布一条通知（无总线时no-op）。 */
    private void publishNotice(String type, String payloadJson) {
        this.notifier.notice(type, payloadJson);
    }

    /** 事件编排：装载编排表并设限流；具体触发规则见 {@link EventScheduler}。 */
    public void enableSchedule(EventSchedule schedule, int maxEventsPerSec) {
        this.scheduler.enableSchedule(schedule, maxEventsPerSec);
    }

    /** 运行时编排表视图（界面用）。 */
    public String eventsJson() {
        /* 计划的归属 = 它引用的 CEID 的归属：按设备模型反查，不另存一份 */
        return this.scheduler.eventsJson(this::ceidOwner);
    }

    /**
     * 编排表 + 运行统计（v2 计划页：倒计时与「启用后确实在跑」的证据）。
     *
     * @param stats planId → 运行统计；null = 不带统计
     * @return JSON 文本
     */
    public String eventsJson(java.util.Map<String, PlanRuntimeStat> stats) {
        return this.scheduler.eventsJson(this::ceidOwner, stats);
    }

    /** CEID → 归属单元名；查不到（被主机的 S2F33 覆盖掉、或模型已换）返回空串。 */
    private String ceidOwner(int ceid) {
        ResolvedEquipment current = this.model;
        ResolvedCeid resolved = current == null ? null : current.ceids().get(ceid);
        return resolved == null ? "" : resolved.owner();
    }

    /** 运行时启停一个计划。 */
    public boolean setEventEnabled(String planId, boolean enabled) {
        return this.scheduler.setEventEnabled(planId, enabled);
    }

    /** 运行时编排表视图（界面用）。 */
    public synchronized boolean addEventPlan(EventPlan plan) {
        return this.scheduler.addEventPlan(plan);
    }

    /** 运行时更新编排计划（不写回配置）。 */
    public synchronized boolean updateEventPlan(String planId, EventPlan next) {
        return this.scheduler.updateEventPlan(planId, next);
    }

    /** 运行时删除编排计划（不写回配置）。 */
    public synchronized boolean removeEventPlan(String planId) {
        return this.scheduler.removeEventPlan(planId);
    }

    /** 不断连热生效：整体替换事件编排、限流、W 位与值来源；规则见 {@link HotReloader}。 */
    public void applyHot(EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit, boolean s6f1Wbit, ValueSource values) {
        applyHot(schedule, maxEventsPerSec, s6f11Wbit, s6f1Wbit, this.model, values);
    }

    /** 不断连热生效（全量）：连设备模型一起替换；规则见 {@link HotReloader}。 */
    public void applyHot(EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit, boolean s6f1Wbit,
                         ResolvedEquipment model, ValueSource values) {
        this.effectValues = this.hotReloader.apply(schedule, maxEventsPerSec,
                s6f11Wbit, s6f1Wbit, model, values);
    }

    /** 「设定」一个状态变量的值：钉住,覆盖曲线与事件效果,直到取消设定或热重载。 */
    public boolean pinSvidValue(int svid, double value) {
        return this.edits.pinSvidValue(svid, value);
    }

    /** 取消「设定」。 */
    public boolean unpinSvidValue(int svid) {
        return this.edits.unpinSvidValue(svid);
    }

    /** 是否被「设定」。 */
    public boolean isSvidPinned(int svid) {
        return this.edits.isSvidPinned(svid);
    }

    /**
     * 运行时目录编辑：增删改 CEID / RPTID / SVID。
     *
     * <p>这些改动只作用于当前会话，不写回配置文件；重载会话后按配置恢复。
     * 真正的校验与模型重建在 {@link RuntimeCatalog} 里。</p>
     */
    public boolean addReport(ResolvedReport report) {
        return this.edits.addReport(report);
    }

    public boolean updateReport(ResolvedReport report) {
        return this.edits.updateReport(report);
    }

    public boolean removeReport(int rptid) {
        return this.edits.removeReport(rptid);
    }

    public boolean addCeid(ResolvedCeid def) {
        return this.edits.addCeid(def);
    }

    public boolean updateCeid(ResolvedCeid def) {
        return this.edits.updateCeid(def);
    }

    public boolean removeCeid(int ceid) {
        return this.edits.removeCeid(ceid);
    }

    public boolean addSvid(ResolvedSvid def) {
        return this.edits.addSvid(def);
    }

    public boolean updateSvid(ResolvedSvid def) {
        return this.edits.updateSvid(def);
    }

    public boolean removeSvid(int svid) {
        return this.edits.removeSvid(svid);
    }

    /** 换设备模型后重建引用它的视图（S6F11 组装器与 Trace）。 */
    private void refreshModelViews(ResolvedEquipment next) {
        this.model = next;
        this.assembler = new S6F11Assembler(this.model, this.values, this.hostReports);
        /* 容量限制跟随会话配置，热重载时不变 */
        this.tracePump.traceLimits(this.traceLimits.maxTracesOrDefault(),
                this.traceLimits.maxSvidCountOrDefault());
        this.tracePump.reset(this.model, this.values);
    }

    /**
     * 已上报的 S6F11 条数。
     *
     * @return 条数
     */
    public long emittedCount() {
        return this.eventReporter.emitted();
    }

    /**
     * 因限流被丢弃的条数。
     *
     * @return 条数
     */
    public long rejectedCount() {
        return this.scheduler.rejectedCount();
    }

    /**
     * 已发送的 S6F1 报文数。
     *
     * @return 条数
     */
    public long traceFramesSent() {
        return this.tracePump.framesSent();
    }

    /**
     * 活跃的 Trace 数量。
     *
     * @return 数量
     */
    public int activeTraces() {
        return this.tracePump.activeCount();
    }

    /**
     * 立即处理一次到期的工作（事件上报 + Trace 采样）。
     *
     * <p>正常情况下由 100ms 心跳驱动；暴露出来是为了让测试与"确定性步进"场景
     * 能精确控制推进节奏，而不是靠 sleep 赌时序。
     * </p>
     *
     * <p><b>整体互斥</b>：心跳线程与外部线程（测试、确定性步进）可能同时调用本方法。
     * 它要改 {@code lastFireMs}、条件去抖状态、延迟队列——这些状态彼此关联，
     * 分开加锁挡不住交错。典型症状是"到点却没发"：心跳线程先推进了去抖状态，
     * 外部线程再来时看到已触发，于是谁都没发。</p>
     *
     * <p>锁内只做状态推进与投递，不做等待式 IO：S6F11 的 W 位等待走独立 ACK 队列，
     * 不会把心跳卡在 T3 上。</p>
     */
    public void pumpNow() {
        synchronized (this.pumpLock) {
            /* 采样照常推进（值来源是纯函数，不需要链路），只是链路不通时不发 */
            this.tracePump.pump();

            /* 事件这一段整个交给调度器：离线时它自己清场（不积压），
               上线后从当前时刻重新开始——lastFireMs 也归它管。
               两边各记一份会漏掉"离线期间的时间"，上线那一刻就会涌出一批旧事件。 */
            this.scheduler.pumpEvents();
        }
    }

    /**
     * 手动上报一个采集事件（不受限流约束：这是操作员的显式动作）。
     *
     * @param ceid CEID
     * @throws IOException 会话未打开或发送失败
     */
    public void emitCeid(int ceid) throws IOException {
        /* 手动触发没有计划上下文：效果取该 CEID 全部启用计划的并集 */
        this.eventReporter.emit(ceid, null);
    }

    /** 调度器触发：带计划身份，效果只施加该计划。 */
    void emitCeid(int ceid, String planId) throws IOException {
        this.eventReporter.emit(ceid, planId);
    }

    /**
     * 单步试跑计划（3.0 v2 {@code runtime/plans/{planId}/step}）。
     *
     * @param planId 计划 id
     * @throws IOException 计划不存在或发送失败
     */
    public void stepPlan(String planId) throws IOException {
        this.eventReporter.step(planId);
    }

    /**
     * 上报/清除一个报警（S5F1）。
     *
     * <p>S5F1 默认带 W 位并等待 S5F2；发送放到 ACK 队列，HTTP 请求不会阻塞到 T3。
     * ALCD 高位置 1 表示置位、清 0 表示清除，低 3 位是类别。</p>
     */
    @Override
    public void emitAlarm(int alid, boolean set, String text, int category) throws IOException {
        this.alarmReporter.emit(alid, set, text, category);
    }

    /**
     * 当前处于置位状态的报警。
     *
     * @return 快照
     */
    public List<AlarmState> alarms() {
        return this.alarmReporter.alarms();
    }

    @Override
    public String alarmsJson() {
        return SessionJsonViews.alarmsJson(this.alarms());
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

    /** 编排台账落地(3.0 需求 5);Bootstrap 装配时接入。 */
    private volatile PlanExecutor planExecutor;

    /**
     * 挂编排执行器(每次触发落一条台账并计入 runtime_plan_state)。
     *
     * @param executor 执行器
     */
    public void planExecutor(PlanExecutor executor) {
        this.planExecutor = executor;
        this.eventReporter.planExecutor(executor);
        this.eventReporter.onlineCheck(() ->
                this.control.state() == EquipmentControlState.ONLINE);
        /* 执行器本身不碰会话内部：效果证据、动作组装、W 位 ACK 队列都在这里接线 */
        executor.errorSink(this::recordError);
        executor.ackSender(() -> this.ackSender);
        executor.effectController(new PlanExecutor.EffectController() {
            @Override
            public PlanExecutor.EffectResult apply(int ceid, String planId) {
                return HsmsSessionRuntime.this.eventReporter.applyTracked(ceid, planId);
            }

            @Override
            public String rollback(Object token) {
                return HsmsSessionRuntime.this.eventReporter.rollback(token);
            }
        });
        executor.actionResolver(this::resolvePlanAction);
    }

    /** SML 动作里的运行时占位符：{@code {{svid.1001}}}。 */
    private static final Pattern SVID_PLACEHOLDER = Pattern.compile("\\{\\{\\s*svid\\.(\\d+)\\s*\\}\\}");

    /**
     * 把一条计划动作解析成可发送报文（设计 §2.7.1 的五种类型）。
     *
     * <p>S6F11 / SML 直接复用组装器与解析器；alarm / terminal / trace 用
     * 与 {@code AlarmReporter} / S10F1 / S6F1 相同的数据结构组装报文体，
     * 参数从 {@code action.params()} 取，缺省值保持现有行为。</p>
     */
    private SmlParser.Message resolvePlanAction(PlanAction action, int ceid, long atMs)
            throws Exception {
        return switch (action.kind()) {
            case PlanAction.KIND_S6F11 -> new SmlParser.Message(6, 11, this.s6f11Wbit,
                    this.assembler.build(ceid, atMs));
            case PlanAction.KIND_SML -> SmlParser.parse(resolvePlaceholders(action.sml(), atMs));
            case PlanAction.KIND_ALARM -> alarmAction(action);
            case PlanAction.KIND_TERMINAL -> terminalAction(action);
            case PlanAction.KIND_TRACE -> traceAction(action, atMs);
            default -> throw new IllegalArgumentException("未知动作类型: " + action.kind());
        };
    }

    private static SmlParser.Message alarmAction(PlanAction action) {
        int alid = actionInt(action, "alid", 1);
        int category = Math.max(1, Math.min(7, actionInt(action, "category", 1)));
        boolean set = !"false".equalsIgnoreCase(action.params().getOrDefault("set", "true"));
        String text = action.params().getOrDefault("text", set ? "ALARM " + alid : "CLEAR " + alid);
        if (!text.chars().allMatch(c -> c < 128)) {
            throw new IllegalArgumentException("ALTX 只能包含 US-ASCII 字符");
        }
        byte alcd = (byte) ((set ? 0x80 : 0x00) | category);
        var b = Secs2.getBuilder();
        Secs2 body = b.list(b.binary(alcd), b.uint4(alid), b.ascii(text));
        /* S5F1 与 AlarmReporter 一致：默认带 W，等 S5F2 */
        return new SmlParser.Message(5, 1, true, body);
    }

    private static SmlParser.Message terminalAction(PlanAction action) {
        int tid = actionInt(action, "tid", 0);
        String text = action.params().getOrDefault("text", "");
        var b = Secs2.getBuilder();
        Secs2 body = b.list(b.binary((byte) tid), b.ascii(text));
        return new SmlParser.Message(10, 1, false, body);
    }

    private SmlParser.Message traceAction(PlanAction action, long atMs) {
        int trid = actionInt(action, "trid", 1);
        List<Secs2> sampled = new ArrayList<>();
        String rawSvids = action.params().get("svids");
        if (rawSvids != null && !rawSvids.isBlank()) {
            for (String piece : rawSvids.split(",")) {
                if (piece.isBlank()) {
                    continue;
                }
                int svid;
                try {
                    svid = Integer.parseInt(piece.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("trace 动作 svids 含非法编号: " + piece.trim());
                }
                double value = this.values.valueAt(svid, atMs).orElse(0.0);
                sampled.add(Secs2Mapper.of(ValueType.F8, value));
            }
        }
        var b = Secs2.getBuilder();
        String stime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        Secs2 body = b.list(b.uint4(trid), b.uint4(1L), b.ascii(stime), b.list(sampled));
        return new SmlParser.Message(6, 1, this.s6f1Wbit, body);
    }

    private String resolvePlaceholders(String sml, long atMs) {
        if (sml == null || sml.indexOf('{') < 0) {
            return sml;
        }
        Matcher matcher = SVID_PLACEHOLDER.matcher(sml);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int svid = Integer.parseInt(matcher.group(1));
            double value = this.values.valueAt(svid, atMs).orElse(0.0);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(formatNumber(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value) : Double.toString(value);
    }

    private static int actionInt(PlanAction action, String key, int fallback) {
        String raw = action.params().get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("动作参数 " + key + " 应为整数: " + raw);
        }
    }

    /** 链路相位（3.0 需求 1）；变化经 {@code linkListener} 通知（持久化 + SSE 在装配层接）。 */
    private volatile SessionRuntime.LinkPhase linkPhase = SessionRuntime.LinkPhase.STOPPED;
    private volatile java.util.function.BiConsumer<String, SessionRuntime.LinkPhase> linkListener;

    /**
     * 生命周期锁：{@code open / disconnect / close} 互斥。
     *
     * <p>没有这把锁时，两个并发「连接」（HTTP 两次点击、或 connect 撞上 reload）
     * 都能通过 {@code communicator == null} 的检查，各建一条链路；后写者覆盖字段，
     * 先建的那条失去唯一引用，socket、协议线程、端口全部泄漏，而且两套心跳会
     * 同时推进调度器（同一事件被重复发出）。</p>
     */
    private final Object lifecycleLock = new Object();

    /**
     * 相位跳变锁：只保护 {@code linkPhase} 的读-改-写。
     *
     * <p>相位来自三条线程（协议回调、心跳、HTTP 命令），裸 volatile 上的读改写会丢更新：
     * 例如 {@code close()} 写入 STOPPED 后，迟到的 {@code communicatable(false)}
     * 又把它改回 DISCONNECTED，界面上就出现「已销毁但显示已断开」。通知放在锁外发，
     * 避免持锁做持久化与 SSE 广播。</p>
     */
    private final Object phaseLock = new Object();

    /** 挂链路相位监听（幂等覆盖）。 */
    public void onLinkPhase(java.util.function.BiConsumer<String, SessionRuntime.LinkPhase> listener) {
        this.linkListener = listener;
    }

    private void changeLinkPhase(SessionRuntime.LinkPhase next) {
        boolean changed;
        synchronized (this.phaseLock) {
            changed = this.linkPhase != next;
            if (changed) {
                this.linkPhase = next;
            }
        }
        if (!changed) {
            return;
        }
        var listener = this.linkListener;
        if (listener != null) {
            try {
                listener.accept(this.spec.id(), next);
            } catch (RuntimeException e) {
                recordError(e);
            }
        }
    }

    @Override
    public SessionRuntime.LinkPhase linkPhase() {
        return this.linkPhase;
    }

    @Override
    public void disconnect() {
        synchronized (this.lifecycleLock) {
            this.disconnectLocked();
        }
    }

    private void disconnectLocked() {
        /* 只断链：模型 / 值来源 / 编排 / 钉值 / 计划启用态全留在对象里，
           reconnect() 后原样恢复（3.0 设计 §2.4.1）。 */
        this.stopTicker();
        for (ExecutorService pool : new ExecutorService[] {
                this.ackSender, this.traceAckSender, this.alarmAckSender }) {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        SecsCommunicator comm = this.communicator;
        this.communicator = null;
        this.dispatcher = null;
        this.terminalInbox = null;
        if (comm != null) {
            try {
                comm.close();
            } catch (IOException e) {
                recordError(e);
            }
        }
        this.changeControlState(EquipmentControlState.OFFLINE);
        this.state.set(SessionState.CLOSED);
        this.changeLinkPhase(SessionRuntime.LinkPhase.DISCONNECTED);
    }

    @Override
    public void reconnect() throws IOException {
        /* 对象复用：open() 在 communicator 为空时重建链路，内存态天然保留 */
        this.open();
    }

    @Override
    public void open() throws IOException {
        synchronized (this.lifecycleLock) {
            this.openLocked();
        }
    }

    private void openLocked() throws IOException {
        if (this.communicator != null) {
            throw new IOException("session already opened: " + this.spec.id());
        }

        this.state.set(SessionState.OPENING);
        this.changeLinkPhase(SessionRuntime.LinkPhase.STARTING);
        if (this.ackSender == null || this.ackSender.isShutdown()) {
            this.ackSender = newAckSender("s6f11-ack-" + this.spec.id());
        }
        if (this.traceAckSender == null || this.traceAckSender.isShutdown()) {
            this.traceAckSender = newAckSender("s6f1-ack-" + this.spec.id());
        }
        if (this.alarmAckSender == null || this.alarmAckSender.isShutdown()) {
            this.alarmAckSender = newAckSender("s5f1-ack-" + this.spec.id());
        }

        try {
            /* open() 返回之前就可能收到报文（主机 SELECT 后立刻发 S1F1）。
               dispatcher 还没建好，直接 dispatch 会 NPE 丢帧——先存起来，就绪后按序补发。 */
            final Object gate = new Object();
            final java.util.List<SecsMessage> early = new java.util.ArrayList<>();
            this.communicator = this.connector.open(
                    /* 原始收帧（含 secondary 应答）只进报文监控 */
                    message -> this.publish("in", message),
                    message -> {
                        PrimaryDispatcher ready;
                        synchronized (gate) {
                            /* 取到局部变量再出锁用：出锁后 dispatcher 可能被
                               disconnect() 置空（旧写法会在读线程里 NPE，或把旧连接的
                               在途报文投给重连后的新 dispatcher） */
                            ready = this.dispatcher;
                            if (ready == null) {
                                early.add(message);
                                return;
                            }
                        }
                        ready.dispatch(message);
                    },
                    message -> this.publish("out", message),
                    value -> {
                        this.control.communicatable(value);
                        /* 链路相位跟着 communicatable 走：select 成功 = CONNECTED，
                           掉线 = DISCONNECTED（被动监听未 select 时保持 LISTENING） */
                        if (value) {
                            this.changeLinkPhase(SessionRuntime.LinkPhase.CONNECTED);
                            if (this.control.state() == EquipmentControlState.OFFLINE) {
                                this.changeControlState(EquipmentControlState.COMMUNICATING);
                            }
                        } else {
                            this.changeLinkPhase(SessionRuntime.LinkPhase.DISCONNECTED);
                            this.changeControlState(EquipmentControlState.OFFLINE);
                        }
                    },
                    this::recordFailure);
            this.terminalInbox = new TerminalInbox(this.communicator, this.spec.id(), this::recordError);
            synchronized (gate) {
                this.dispatcher = new PrimaryDispatcher(() -> this.communicator,
                        this.hostSide,
                        this.tracePump,
                        this.terminalInbox,
                        () -> this.control.state(),
                        this::changeControlState,
                        this::replyS1F4,
                        this::recordError,
                        this.spec.id());
                for (SecsMessage pending : early) {
                    this.dispatcher.dispatch(pending);
                }
                early.clear();
            }
            this.state.set(SessionState.OPEN);
            /* 只有被动端在这里推进到 LISTENING（端口已绑、等主机 SELECT）。
               主动端的 CONNECTED 交给 communicatable(true) 回调：在这里强写会盖掉
               "连不上"那次回调写的 DISCONNECTED，界面上就成了假的已连接。 */
            if (this.spec.passive() && this.linkPhase == SessionRuntime.LinkPhase.STARTING) {
                this.changeLinkPhase(SessionRuntime.LinkPhase.LISTENING);
            }
            this.startTicker();
        } catch (IOException | RuntimeException e) {
            recordError(e);
            this.state.set(SessionState.FAILED);
            this.changeLinkPhase(SessionRuntime.LinkPhase.FAILED);
            /* connector.open 成功之后、TerminalInbox/PrimaryDispatcher 构造失败时，
               已打开的 communicator 必须关掉：否则端口/线程会跟着一个 FAILED 会话泄漏。 */
            SecsCommunicator opened = this.communicator;
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException closeError) {
                    recordError(closeError);
                } finally {
                    this.communicator = null;
                }
            }
            throw e;
        }
    }

    @Override
    public void close() {
        synchronized (this.lifecycleLock) {
            this.closeLocked();
        }
    }

    private void closeLocked() {
        this.stopTicker();
        ExecutorService sender = this.ackSender;
        if (sender != null) {
            sender.shutdownNow();
        }
        ExecutorService traceSender = this.traceAckSender;
        if (traceSender != null) {
            traceSender.shutdownNow();
        }
        ExecutorService alarmSender = this.alarmAckSender;
        if (alarmSender != null) {
            alarmSender.shutdownNow();
        }

        SecsCommunicator comm = this.communicator;
        if (comm == null) {
            this.state.set(SessionState.CLOSED);
            this.changeLinkPhase(SessionRuntime.LinkPhase.STOPPED);
            return;
        }

        this.state.set(SessionState.CLOSING);
        try {
            comm.close();
        } catch (IOException e) {
            recordError(e);
        } finally {
            this.communicator = null;
            this.dispatcher = null;
            this.terminalInbox = null;
            this.state.set(SessionState.CLOSED);
            this.changeLinkPhase(SessionRuntime.LinkPhase.STOPPED);
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
     * 常量落盘回调：把当前常量表交给上层写回配置文件。
     *
     * <p>实现方负责"写到哪个文件 + 原子替换"；会话层只保证传出的是一份快照。</p>
     */
    @FunctionalInterface
    public interface ConstantSaver {

        /**
         * 保存常量。
         *
         * @param constants ECID → 常量
         * @throws IOException 写盘失败
         */
        void save(Map<Integer, EquipmentConstantDef> constants) throws IOException;
    }

    /**
     * 设置常量落盘回调（由 {@code SessionLauncher} 在启动会话时接入）。
     *
     * @param saver 回调；{@code null} 表示不落盘
     */
    public void constantSaver(ConstantSaver saver) {
        /* 协作者负责协议翻译，落盘这一下交给会话层——它才知道写哪个文件 */
        this.hostSide.constantSaver(saver::save);
    }

    /**
     * 当前设备常量快照（按 ECID 升序）。
     *
     * @return 常量列表
     */
    public List<EquipmentConstantDef> constants() {
        return this.constantStore.snapshot();
    }

    /** 是否允许主动发送（S6F11 / S6F1 这类 unsolicited）；规则见 {@link ControlStateHolder}。 */
    private boolean canSendUnsolicited() {
        return this.control.canSendUnsolicited();
    }

    @Override
    public EquipmentControlState controlState() {
        return this.control.state();
    }

    @Override
    public boolean setOnline(boolean online) {
        if (!this.control.communicatable()) {
            /* ONLINE 是"对某台主机"而言的：链路都没建，就没有可上的线。
               返回 false 让界面明确告诉操作员"先启动并等主机连上"。 */
            return false;
        }
        this.changeControlState(online ? EquipmentControlState.ONLINE : EquipmentControlState.OFFLINE);
        return true;
    }

    @Override
    public com.shimizukenta.secssim.app.session.HsmsConnectionTarget hostTarget() {
        return new com.shimizukenta.secssim.app.session.HsmsConnectionTarget(
                this.spec.bindHost(), this.spec.port(), this.spec.deviceId());
    }

    @Override
    public boolean hasPeer() {
        return this.control.communicatable();
    }

    @Override
    public Map<String, Long> counters() {
        return this.metrics.counters();
    }

    /**
     * 工作台聚合用的单会话片段（schema v6 的 overview 端点）。
     *
     * @return JSON 对象文本(不带外层花括号也不带逗号——由调用方拼数组)
     */
    @Override
    public String overviewJson() {
        return this.metrics.overviewJson(false);
    }

    @Override
    public String overviewJson(boolean hostSim) {
        return this.metrics.overviewJson(hostSim);
    }

    @Override
    public SessionState state() {
        return this.state.get();
    }

    /**
     * 把异常记为会话错误。
     *
     * <p>对端超时(W 位等 T3/T5 没等到回复)不算会话故障——报文已发出,
     * 只是主机没回;把它钉在 lastError 上,设备页会一直飘着一行红色,
     * 看起来像设备坏了。这类异常只进计数,不进 lastError。</p>
     *
     * @param e 异常
     */
    private static boolean isPeerTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SecsWaitReplyMessageException) {
                return true;
            }
            String name = cause.getClass().getName();
            if (name.contains("HsmsSsTimeout")) {
                return true;
            }
        }
        return false;
    }

    private void recordError(Exception e) {
        recordFailure(e);
    }

    /**
     * 记一次真正发出去的事件（速率与最近时刻）。
     *
     * <p>overview 里的 {@code s6f11PerMin} / {@code lastEventAt} 读的就是这两个字段；
     * 以前它们只有声明没有写入，工作台永远显示 0。</p>
     */
    private void recordEventFired() {
        long now = System.currentTimeMillis();
        this.eventRate.record(now);
        this.lastEventAtMs = now;
    }

    /**
     * 记下一条会话级错误（含协议层日志里的异常）。
     *
     * <p>协议层把 bind 失败、连接被拒、超时等都塞进日志队列；没有监听者时这些信息
     * 会直接消失。HsmsConnector 把其中的 Throwable 转到这里，界面与 lastError 才看得见。</p>
     *
     * @param e 错误
     */
    private void recordFailure(Throwable e) {
        if (e == null || isPeerTimeout(e)) {
            return;
        }
        this.lastError = e.toString();
    }

    @Override
    public String lastError() {
        return this.lastError;
    }

    private void startTicker() {
        /* 事件调度从"现在"起算，不补开以来的周期 */
        this.scheduler.start();
        this.lastFireMs = this.clock.nowMs();
        this.ticker.start(this.clock instanceof ManualEngineClock, this::pumpNow);
    }

    private void stopTicker() {
        this.ticker.stop();
    }

    /**
     * 把报文发布到事件总线。
     *
     * <p>只发布<b>数据报文</b>（stream ≥ 0）：HSMS 的 select/deselect 之类控制帧
     * 对使用者没有意义，混进报文面板只会造成噪音。
     * </p>
     */
    private void publish(String direction, SecsMessage message) {
        this.notifier.publish(direction, message);
    }

    @Override
    public String catalogJson() {
        return SessionJsonViews.catalogJson(this.model, this.hostReports, this.effectValues::isPinned);
    }

    /** 一条终端消息。 */
    public record TerminalMessage(int tid, String text, long atMillis) {
    }

    /** 最近收到的终端消息（新→旧）。 */
    public List<TerminalMessage> terminalMessages() {
        return this.terminalInbox == null
                ? List.of()
                : this.terminalInbox.messages().stream()
                        .map(m -> new TerminalMessage(m.tid(), m.text(), m.atMillis()))
                        .toList();
    }

    @Override
    public String terminalMessagesJson() {
        com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
        java.util.List<com.shimizukenta.jsonhub.JsonHub> items = new java.util.ArrayList<>();
        for (TerminalMessage message : this.terminalMessages()) {
            items.add(b.object(
                    b.pair("tid", message.tid()),
                    b.pair("text", message.text()),
                    b.pair("atMillis", message.atMillis())));
        }
        return b.array(items).toJson();
    }

    /**
     * S1F3 请求一组 SVID，S1F4 必须按<b>同样的顺序与个数</b>返回。
     *
     * <p>E5 允许空列表表示"全部 SVID"。自动发现型主机常用它一次拿全，
     * 这里与 S1F11 的空列表语义保持一致。</p>
     */
    private void replyS1F4(SecsMessage primary) throws Exception {
        this.svidReader.replyS1F4(primary);
    }

}
