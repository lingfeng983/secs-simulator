package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secssim.app.sml.SmlParser;
import com.shimizukenta.secssim.app.event.EventBus;
import com.shimizukenta.secssim.app.event.NoticeBus;
import com.shimizukenta.secssim.app.session.DefaultCurves;
import com.shimizukenta.secssim.app.session.EquipmentControlState;
import com.shimizukenta.secssim.app.session.HostReportTable;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.session.SessionSpec;
import com.shimizukenta.secssim.app.session.SessionState;
import com.shimizukenta.secssim.app.session.TraceLimits;
import com.shimizukenta.secssim.core.EngineClock;
import com.shimizukenta.secssim.core.domain.EquipmentConstantDef;
import com.shimizukenta.secssim.core.compile.ResolvedCeid;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedReport;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.app.config.CycleConfig;
import com.shimizukenta.secssim.app.session.PlanEnableOutcome;
import com.shimizukenta.secssim.core.cycle.LifecycleCycleClock;
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

    final SessionSpec spec;
    /** 设备模型：热生效时可整体替换（改单元/变量不再需要重建连接）。 */
    volatile ResolvedEquipment model;
    private final ValueStore store;
    /** 可热替换的值来源：S6F11/Trace/预览都持有它，模板热生效只换内部实现。 */
    final ValueSourceHandle values;
    /** values 委托里的「设定」层（钉值落在它身上）。 */
    private volatile PinnedValueSource pinnedValues;
    /** overview 速率统计(60 秒滑动窗口)。 */
    private final RateTracker msgRate = new RateTracker();
    private final long openedAtMs = System.currentTimeMillis();
    final EngineClock clock;
    final HostReportTable hostReports = new HostReportTable();

    /** Trace（S2F23）容量限制，来自 session.trace 配置段。 */
    private final TraceLimits traceLimits;
    /** S6F11 组装器：随设备模型热替换。 */
    volatile S6F11Assembler assembler;

    /** 晶圆属性（v7）：报告里的 ctx.<名> 从这里取。 */
    volatile com.shimizukenta.secssim.core.context.ContextTable contextTable =
            com.shimizukenta.secssim.core.context.ContextTable.empty();

    /** 设备常量取值（v7）：常量也可以是时间函数。 */
    volatile EcidValues ecidValues;

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
    final HostCommandHandler hostSide;

    /** Trace（周期采样）的采集与上报协作者。 */
    final TracePump tracePump;

    /** 终端消息收件箱（S10F3 → S10F4）；连接建立后才可用。 */
    volatile TerminalInbox terminalInbox;

    /** 采集事件上报（S6F11）。 */
    private volatile EventReporter eventReporter;

    /** Primary 分发：把主机发来的报文分派到对应处理路径。 */
    volatile PrimaryDispatcher dispatcher;

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
    final HsmsConnector connector;

    /** 事件心跳。 */
    final EventTicker ticker;

    /** 运行时编辑（钉值与目录增删改）。 */
    private final RuntimeEditsFacade edits;

    /** 设备常量表。 */
    private final ConstantStore constantStore;

    /** 控制状态。 */
    final ControlStateHolder control;

    /** 运行时目录：CEID / RPTID / SVID 的临时增删改。 */
    private final RuntimeCatalog catalog;

    /** 事件编排的运行时：计划表、限流、条件去抖与延迟链。 */
    final EventScheduler scheduler;

    /** 设备级限流闸：事件编排与工艺周期共用同一只。 */
    private final SessionRateGate rateGate = new SessionRateGate();

    /** 工艺周期运行时（M3 拆分）：片推进 + 片内事件；与值求值共享生命周期时钟。 */
    private final CycleRunner cycleRunner;

    /** 报警（S5F1）的发送与活动报警表。 */
    private final AlarmReporter alarmReporter;
    private final Rng eventRng;

    final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong wbitDropped = new AtomicLong();

    volatile SecsCommunicator communicator;
    private volatile boolean s6f11Wbit;
    private volatile boolean s6f1Wbit;
    /** 单线程串行等 S6F12：W 位发送会阻塞到 T3，不能占用 100ms 事件心跳。 */
    volatile ExecutorService ackSender;
    /** 单线程串行等 S6F2：Trace 采样不能被 ACK 阻塞。 */
    volatile ExecutorService traceAckSender;
    /**
     * 单线程串行等 S5F2：报警的 ACK 等待必须与 S6F11 分开。
     *
     * <p>共用一个 ACK 线程时，主机不回 S5F2 会让后续所有 S6F11 的 W 位发送排队，
     * 队列满后成批丢弃采集事件——一条报警就能把事件流堵死。</p>
     */
    volatile ExecutorService alarmAckSender;

    /** 诊断信息：lastError 与 overview 事件速率。 */
    private final SessionDiagnostics diagnostics = new SessionDiagnostics();

    /** 链路生命周期：open / disconnect / close 与链路相位。 */
    private final SessionLifecycle lifecycle;

    /** 计划动作解析。 */
    private final PlanActionResolver planActions;

    /** 设备模型派生视图（S6F11 组装器与 Trace）。 */
    private final SessionModelViews modelViews;

    /** 终端消息视图。 */
    private final SessionTailView tailView;

    /** 发送出口。 */
    private final SessionSender sender;

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
        this(spec, model, clock, values, traceLimits, null, null);
    }

    /**
     * 创建会话（全参数 + 工艺周期）。
     *
     * @param spec           会话参数
     * @param model          已解析的设备模型
     * @param clock          仿真时钟
     * @param values         值来源；传 {@code null} 表示用默认曲线
     * @param traceLimits    Trace 容量限制（{@code null} 用默认）
     * @param cycle          cycle 配置段（{@code null} = 没有 cycle 段，周期无从启动）
     * @param lifecycleClock 生命周期周期时钟——必须与 {@code values} 内部用的是<b>同一个实例</b>，
     *                       值随周期启停（2026-11-16 设计 §5.2）；传 {@code null} 时
     *                       runner 用独立的直通时钟（仅状态展示，不影响值）
     */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model, EngineClock clock,
                              ValueSource values,
                              TraceLimits traceLimits,
                              CycleConfig cycle,
                              LifecycleCycleClock lifecycleClock) {
        this.spec = spec;
        this.model = model;
        this.clock = clock;
        this.traceLimits = traceLimits == null
                ? TraceLimits.DEFAULTS
                : traceLimits;
        this.lifecycle = new SessionLifecycle(this);
        this.sender = new SessionSender(this);
        /* 构造期就被 onModelChanged / HotReloader 以方法引用捕获，必须先建好 */
        this.modelViews = new SessionModelViews(this, this.traceLimits);

        Map<Integer, EquipmentConstantDef> init = new LinkedHashMap<>();
        for (EquipmentConstantDef def : spec.equipment().constants()) {
            init.put(def.id(), def);
        }
        this.constants = java.util.Collections.synchronizedMap(init);
        this.control = new ControlStateHolder(next -> this.publishNotice(
                com.shimizukenta.secssim.app.event.NoticeEvent.SESSION,
                "{\"controlState\":\"" + next.name() + "\"}"));
        this.hostSide = new HostCommandHandler(this.constants, this.hostReports,
                this.clock, this.ecidValues);
        this.hostSide.errorSink(this::recordError);
        this.hostSide.sessionId(spec.id());
        this.hostSide.modelSupplier(() -> this.model);
        this.hostSide.controlStateSupplier(() -> this.control.state());
        this.hostSide.replySink(this.sender::reply);
        /* S2F15 写设备常量 = 参数变化 → 全停（2026-11-16 设计 D6 清单第 6 条） */
        this.hostSide.stopAllHook(this::stopAllPlans);
        this.store = new ValueStore(spec.seed());

        ValueSource initial;
        if (values == null) {
            DefaultCurves.bind(this.store, spec.equipment(), model);
            initial = new CurveValueSource(this.store);
        } else {
            initial = values;
        }
        final PinnedValueSource initialSource = new PinnedValueSource(initial);
        this.pinnedValues = initialSource;
        this.values = new ValueSourceHandle(initialSource);

        this.assembler = new S6F11Assembler(model, this.values, this.hostReports,
                this.contextTable, this.ecidValues);
        this.ackSender = this.lifecycle.newAckSender("s6f11-ack-" + spec.id());
        this.traceAckSender = this.lifecycle.newAckSender("s6f1-ack-" + spec.id());
        this.alarmAckSender = this.lifecycle.newAckSender("s5f1-ack-" + spec.id());
        this.planActions = new PlanActionResolver(() -> this.assembler,
                () -> this.s6f11Wbit, () -> this.s6f1Wbit, () -> this.values);

        this.scheduler = new EventScheduler(new Rng(spec.seed()).derive("events"),
                this.rateGate,
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
        this.catalog.onModelChanged(this.modelViews::refresh);
        this.catalog.store(this.store);
        this.tracePump = new TracePump(new TraceManager(model, this.values, Instant.now(),
                this.traceLimits.maxTracesOrDefault(), this.traceLimits.maxSvidCountOrDefault()),
                () -> this.traceAckSender, () -> this.clock.nowMs());
        this.tracePump.sessionId(spec.id());
        this.tracePump.errorSink(this::recordError);
        this.tracePump.canSend(this::canSendUnsolicited);
        this.tracePump.sender(this.sender::send);
        this.tracePump.replyer(this.sender::reply);
        this.alarmReporter = new AlarmReporter(() -> this.alarmAckSender, spec.id(),
                () -> this.communicator,
                this::publishNotice,
                this::recordError);
        this.eventRng = new Rng(spec.seed()).derive("events");
        /* 预览取 scheduler 的当前计划（含运行时增删改、排除禁用），
           而不是装载时那份不可变的 schedule；片内事件标记由 CycleRunner 补齐（M3），
           所以 previewBuilder 在 cycleRunner 之后创建。 */
        this.notifier = new SessionNotifier(spec.id(), this.msgRate);
        this.connector = new HsmsConnector(spec);
        this.ticker = new EventTicker("events-" + spec.id());
        this.edits = new RuntimeEditsFacade(this.catalog, () -> this.model, () -> this.pinnedValues);
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
                () -> this.diagnostics.eventRate().countInWindow(System.currentTimeMillis()),
                () -> this.diagnostics.lastEventAtMs(),
                () -> this.openedAtMs);
        this.svidReader = new SvidReader(() -> this.model,
                () -> this.values,
                () -> this.communicator,
                this.hostSide,
                () -> this.clock.nowMs());
        this.smlSender = new SmlSender(() -> this.communicator,
                () -> this.state() == com.shimizukenta.secssim.app.session.SessionState.OPEN,
                () -> this.control.communicatable());
        this.hotReloader = new HotReloader(this.modelViews::refresh,
                this::collectionEventWbit,
                this::traceWbit,
                this::enableSchedule,
                this.values,
                this.store,
                () -> this.spec.equipment(),
                this::dataModel);
        this.eventReporter = new EventReporter(() -> this.assembler,
                () -> this.communicator,
                () -> this.ackSender,
                /* 实时表：含运行时增删改，且已排除禁用计划（实际触发用） */
                this.scheduler::previewSchedule,
                /* 完整表按 id 查（含禁用计划）：单步试跑要能跑列表里任意一条 */
                this.scheduler::findPlan,
                ceid -> this.hostReports.isEnabled(ceid),
                this.scheduler::recordSkipped,
                this.diagnostics::recordEventFired,
                this::publishNotice,
                () -> this.clock.nowMs(),
                () -> this.s6f11Wbit,
                spec.id(),
                this::recordError);

        /* 工艺周期运行时：与事件编排共用限流闸；片内事件经同一 reporter
           （S2F37 / 台账 plan_id=cycle / SSE 都在那里）。 */
        this.cycleRunner = new CycleRunner(() -> this.clock.nowMs(), this.rateGate,
                this::canSendUnsolicited,
                /* IOException 上抛给 CycleRunner.fire：失败不计 firedCount（评审 LOW-8） */
                (ceid, scheduledAtMs) -> this.eventReporter.emit(ceid, "cycle", scheduledAtMs));
        this.cycleRunner.errorSink(this::recordError);
        this.cycleRunner.sessionId(spec.id());
        this.cycleRunner.reconfigure(cycle, lifecycleClock != null
                ? lifecycleClock
                : new LifecycleCycleClock(
                        com.shimizukenta.secssim.core.cycle.CycleClock.passthrough(),
                        LifecycleCycleClock.State.RUNNING));

        this.previewBuilder = new PreviewBuilder(
                () -> this.values, () -> this.model, this.scheduler::previewSchedule, spec.seed(),
                () -> this.cycleRunner, this.scheduler::anchorsView);

        this.tailView = new SessionTailView(this);
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
        boolean previous = this.s6f11Wbit;
        this.s6f11Wbit = enabled;
        if (previous != enabled) {
            /* W 位变化 = 参数变化 → 全停（2026-11-16 设计 §2.1-6/D6） */
            this.stopAllPlans();
        }
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
        boolean previous = this.s6f1Wbit;
        this.tracePump.s6f1Wbit(enabled);
        this.s6f1Wbit = enabled;
        if (previous != enabled) {
            this.stopAllPlans();
        }
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
    void changeControlState(EquipmentControlState next) {
        this.control.change(next);
        /* 生命周期全停（2026-11-16 设计 §2.1-4/5）：离开 ONLINE 一律全停——
           S1F15、界面置离线、链路断开回落都经过这里。重新 ONLINE 后计划仍是
           stopped，由操作员手动逐条启动；offlinePolicy=queue 的补跑链路已删（M2）。 */
        if (next != EquipmentControlState.ONLINE) {
            this.stopAllPlans();
        }
    }

    /**
     * 全停钩子（2026-11-16 设计 §2.1）：事件编排计划与工艺周期一律回到 stopped。
     *
     * <p>清单：会话新建 / 配置重建（经 enableSchedule）、热重载、控制状态离开 ONLINE、
     * 链路相位脱离 SELECTED、参数变化（W 位 / 限流 / 主机 S2F15 写设备常量 /
     * 界面钉值与取消钉值）。清启用态、去抖进度、延迟链；cycle 回第 0 片待启动。</p>
     */
    public void stopAllPlans() {
        this.scheduler.stopAll();
        this.cycleRunner.stop();
    }

    /** 发布一条通知（无总线时no-op）。 */
    private void publishNotice(String type, String payloadJson) {
        this.notifier.notice(type, payloadJson);
    }

    /**
     * 接入 v7 数据模型：晶圆属性表 + 设备常量取值器。
     *
     * <p>装配器持有的是引用而不是快照：热生效时整体替换这两份，
     * S6F11 下一次组装立刻用新的属性/常量，不必重建会话。</p>
     *
     * @param context 晶圆属性表
     * @param ecids   设备常量取值器
     * @return this
     */
    public HsmsSessionRuntime dataModel(com.shimizukenta.secssim.core.context.ContextTable context,
                                        EcidValues ecids) {
        this.modelViews.dataModel(context, ecids);
        return this;
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

    /**
     * 运行时启停一个计划（2026-11-16 设计 §2.2）。
     *
     * <p>启用方向有 ONLINE 门控：链路 SELECTED 且控制状态 ONLINE 才允许，
     * 否则 {@link PlanEnableOutcome#NOT_ONLINE}（HTTP 409 not-online）。
     * 启用成功时周期计划立即触发一次（锚点 = 此刻）。</p>
     */
    public PlanEnableOutcome setEventEnabled(String planId, boolean enabled) {
        if (enabled && !this.control.canSendUnsolicited()) {
            return PlanEnableOutcome.NOT_ONLINE;
        }
        return this.scheduler.setEventEnabled(planId, enabled)
                ? PlanEnableOutcome.OK : PlanEnableOutcome.UNKNOWN_PLAN;
    }

    /** 周期计划的下一次触发时刻（锚 = 启动时刻；禁用 / 非周期 / 不存在返回 null）。 */
    public Long nextFireAtOf(String planId) {
        return this.scheduler.nextFireAt(planId);
    }

    /**
     * 工艺周期是否正在跑。
     *
     * <p>分段保存的守卫用它：只有运行中的周期才需要保护被其 CEID 引用的
     * SVID / 晶圆属性（2026-11-18 设计 §3.1 守卫 B）。</p>
     *
     * @return true = {@code RUNNING}
     */
    public boolean cycleRunning() {
        return this.cycleRunner != null && this.cycleRunner.running();
    }

    /**
     * 工艺周期运行态 JSON（cycle 页运行卡）。
     *
     * @return {@code {"state","startedAt","waferIndex","phaseMs","totalWafers","firedCount","hasCycle"}}
     */
    public String cycleJson() {
        return this.cycleRunner.stateJson();
    }

    /**
     * 启动工艺周期（ONLINE 门控同计划启停；锚点 = 点击时刻）。
     *
     * @return NOT_ONLINE = 设备离线；NO_CYCLE = 配置里没有 cycle 段
     */
    public PlanEnableOutcome cycleStart() {
        if (!this.control.canSendUnsolicited()) {
            return PlanEnableOutcome.NOT_ONLINE;
        }
        return this.cycleRunner.start(this.clock.nowMs())
                ? PlanEnableOutcome.OK : PlanEnableOutcome.NO_CYCLE;
    }

    /** 停止工艺周期（禁用方向不受 ONLINE 限制；值冻结在当前表现，D10）。 */
    public void cycleStop() {
        this.cycleRunner.stop();
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
        applyHot(schedule, maxEventsPerSec, s6f11Wbit, s6f1Wbit, model, values, null, null, null, null);
    }

    /**
     * 不断连热生效（2026-11-16 设计全量签名）。
     *
     * <p>编排 / 限流 / W 位 / 设备模型 / 值来源之外，再把晶圆属性表、设备常量
     * 取值器（修 P0：热路径丢弃新编译结果）与工艺周期（配置 + 生命周期时钟）
     * 一起换掉；值来源重建后时钟回 STOPPED——热重载本身就是全停事件（§2.1-3），
     * 计划启用态经 enableSchedule 清空，cycle 待手动启动。</p>
     *
     * @param contextTable   新晶圆属性表；{@code null} = 保持现状（兼容旧调用方）
     * @param ecidValues     新设备常量取值器；{@code null} = 保持现状
     * @param cycle          新 cycle 配置；{@code null} = 配置里没有 cycle 段
     * @param lifecycleClock 新生命周期时钟（与 {@code values} 同实例）
     */
    public void applyHot(EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit, boolean s6f1Wbit,
                         ResolvedEquipment model, ValueSource values,
                         com.shimizukenta.secssim.core.context.ContextTable contextTable,
                         EcidValues ecidValues,
                         CycleConfig cycle,
                         LifecycleCycleClock lifecycleClock) {
        ValueSource next = this.hotReloader.apply(schedule, maxEventsPerSec,
                s6f11Wbit, s6f1Wbit, model, values, contextTable, ecidValues);
        /* 热重载换掉值来源时，「设定」随旧配置一起失效：新来源没有设定层。
           评审 HIGH-3：apply 内部已把裸来源 set 进读路径句柄——这里必须把
           「设定」包装器重新 set 回去，否则热重载后钉值写进没人读的实例 */
        PinnedValueSource pinned = next instanceof PinnedValueSource p
                ? p : new PinnedValueSource(next);
        this.pinnedValues = pinned;
        this.values.set(pinned);
        /* cycle 配置与生命周期时钟一起换：装载即回 STOPPED，与"热重载全停"同构（D12） */
        this.cycleRunner.reconfigure(cycle, lifecycleClock != null
                ? lifecycleClock
                : new LifecycleCycleClock(
                        com.shimizukenta.secssim.core.cycle.CycleClock.passthrough(),
                        LifecycleCycleClock.State.RUNNING));
    }

    /**
     * 这次分段保存要动哪些运行态部件。
     *
     * <p>关键区分（2026-11-18 真机验守卫 B 后收紧）：</p>
     * <ul>
     *   <li>{@code structure}——单元/号段变了，旧编排、旧视图、旧引用全部不再成立，
     *       只有这一档要求"全停"；</li>
     *   <li>{@code catalog}——变量 / 报告 / 事件目录变了，要换模型视图、重建值来源与编排，
     *       但计划与周期可以照跑（未被引用的项按口径「可热改」）。</li>
     * </ul>
     *
     * <p>引用完整性不靠这里兜：删掉/改名被报告、周期、计划引用的对象，
     * 整份编译会在写盘前就 422 拦下，走不到 apply。</p>
     *
     * @param changed 实际变化的段
     */
    record SectionEffects(
            boolean structure,
            boolean catalog,
            boolean values,
            boolean schedule,
            boolean throttle,
            boolean data,
            boolean cycle) {

        static SectionEffects of(java.util.Set<com.shimizukenta.secssim.runtime.ConfigSection> changed) {
            boolean structure = changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.STRUCTURE);
            boolean catalog = changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.SVIDS)
                    || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.REPORTS)
                    || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.CEIDS);
            boolean views = structure || catalog;
            return new SectionEffects(
                    structure,
                    catalog,
                    /* 值来源与工艺周期共享同一个生命周期时钟：只改周期配置也必须重建值来源，
                       否则周期停了、曲线还在走（设计 §5.2）。钉值按 SVID 迁移，删掉的丢弃。 */
                    views || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.CYCLE),
                    views || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.PLANS),
                    views || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.THROTTLE),
                    views
                            || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.CONTEXT)
                            || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.CONSTANTS),
                    structure || changed.contains(com.shimizukenta.secssim.runtime.ConfigSection.CYCLE));
        }
    }

    /**
     * 按配置段最小生效（2026-11-18 设计 §4、§8）。
     *
     * <p>与 {@link #applyHot} 的区别：那个是「重建会话 / 模型换了」的全量替换，
     * 一律全停；这里只动真正变化的段——只改事件计划时，没改过的计划保留启用态
     * 与锚点、周期照跑；只改工艺周期时，周期回停止但计划、钉值、计数不动。</p>
     *
     * <p>设备结构段变化才做全量热重置：单元/号段换了，旧视图、旧编排、旧引用都不再有效。
     * 变量 / 报告 / 事件目录变化只换模型视图、重建值来源与编排，计划与周期照跑
     * （口径「未被引用的项可热改」）。</p>
     *
     * @param model          新设备模型
     * @param values         新值来源；{@code null} = 回落设备默认曲线
     * @param schedule       新编排表
     * @param maxPerSec      新限流
     * @param s6f11Wbit      S6F11 W 位
     * @param s6f1Wbit       S6F1 W 位
     * @param contextTable   新晶圆属性表；{@code null} = 保持现状
     * @param ecidValues     新设备常量取值器；{@code null} = 保持现状
     * @param cycle          新 cycle 配置；{@code null} = 配置里没有 cycle 段
     * @param lifecycleClock 新生命周期时钟（与 values 同实例）
     * @param changed        实际变化的段（由保存结果给出）
     * @return 这次动了哪些东西
     */
    public SectionApplyOutcome applySections(
            ResolvedEquipment model, ValueSource values,
            EventSchedule schedule, int maxPerSec, boolean s6f11Wbit, boolean s6f1Wbit,
            com.shimizukenta.secssim.core.context.ContextTable contextTable,
            EcidValues ecidValues, CycleConfig cycle,
            LifecycleCycleClock lifecycleClock,
            java.util.Set<com.shimizukenta.secssim.runtime.ConfigSection> changed) {

        SectionEffects fx = SectionEffects.of(changed);
        boolean modelChanged = fx.structure();
        boolean catalogChanged = fx.catalog();
        boolean valuesChanged = fx.values();
        boolean scheduleChanged = fx.schedule();
        boolean throttleChanged = fx.throttle();
        boolean dataChanged = fx.data();
        boolean cycleChanged = fx.cycle();

        /* --- 1. 模型与视图：结构变了先全停；只是目录（变量/报告/事件）变则换视图、运行不动 --- */
        if (modelChanged || catalogChanged) {
            this.model = model;
            this.modelViews.refresh(model);
        }
        if (modelChanged) {
            this.stopAllPlans();
        }

        /* --- 2. 值来源：重建后把钉值搬过去（只搬新模型里还存在的 SVID） --- */
        int pinsDropped = 0;
        if (valuesChanged) {
            ValueSource base = values;
            if (base == null) {
                DefaultCurves.bind(this.store, this.spec.equipment(), model);
                base = new CurveValueSource(this.store);
            }
            java.util.Map<Integer, Double> pins = this.pinnedValues == null
                    ? java.util.Map.of() : this.pinnedValues.pins();
            PinnedValueSource next = new PinnedValueSource(base);
            for (java.util.Map.Entry<Integer, Double> entry : pins.entrySet()) {
                if (base.svids().contains(entry.getKey())) {
                    next.pin(entry.getKey(), entry.getValue());
                } else {
                    pinsDropped += 1;
                }
            }
            this.pinnedValues = next;
            this.values.set(next);
        }

        /* --- 3. 编排：模型换了走全停装载；只改计划则保留未改动计划的启用态 --- */
        int plansKept = 0;
        if (scheduleChanged) {
            if (modelChanged) {
                this.scheduler.enableSchedule(schedule, maxPerSec);
            } else {
                this.scheduler.replaceSchedulePreservingState(schedule, maxPerSec);
                plansKept = this.scheduler.enabledCount();
            }
        } else if (throttleChanged) {
            this.scheduler.maxPerSec(maxPerSec);
        }

        /* --- 4. W 位 --- */
        if (throttleChanged) {
            this.collectionEventWbit(s6f11Wbit);
            this.traceWbit(s6f1Wbit);
        }

        /* --- 5. 晶圆属性 / 设备常量：装配器持有引用，换掉下一次 S6F11 就生效 --- */
        if (dataChanged && contextTable != null && ecidValues != null) {
            this.modelViews.dataModel(contextTable, ecidValues);
        }

        /* --- 6. 工艺周期：配置换了就回 STOPPED，等操作员手动重新启动 --- */
        if (cycleChanged) {
            this.cycleRunner.reconfigure(cycle, lifecycleClock != null
                    ? lifecycleClock
                    : new LifecycleCycleClock(
                            com.shimizukenta.secssim.core.cycle.CycleClock.passthrough(),
                            LifecycleCycleClock.State.RUNNING));
        }

        return new SectionApplyOutcome(modelChanged, catalogChanged, valuesChanged, scheduleChanged,
                throttleChanged, dataChanged, cycleChanged, pinsDropped, plansKept);
    }

    /**
     * 「设定」一个状态变量的值：钉住,覆盖曲线与事件效果,直到取消设定或热重载。
     *
     * <p>钉值 = 参数变化 → 全停（2026-11-16 设计 D6 清单第 6 条）。</p>
     */
    public boolean pinSvidValue(int svid, double value) {
        boolean pinned = this.edits.pinSvidValue(svid, value);
        if (pinned) {
            this.stopAllPlans();
        }
        return pinned;
    }

    /** 取消「设定」（同样是参数变化 → 全停）。 */
    public boolean unpinSvidValue(int svid) {
        boolean removed = this.edits.unpinSvidValue(svid);
        if (removed) {
            this.stopAllPlans();
        }
        return removed;
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

            /* 工艺周期：片推进 + 片内到期事件（M3 拆分；与编排共用限流与台账出口） */
            this.cycleRunner.pump();
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
        /* 执行器本身不碰会话内部：动作组装、W 位 ACK 队列都在这里接线 */
        executor.errorSink(this::recordError);
        executor.ackSender(() -> this.ackSender);
        executor.actionResolver(this::resolvePlanAction);
    }

    /**
     * 把一条计划动作解析成可发送报文（设计 §2.7.1 的五种类型）。
     *
     * <p>具体组装规则见 {@link PlanActionResolver}。</p>
     */
    private SmlParser.Message resolvePlanAction(PlanAction action, int ceid, long atMs)
            throws Exception {
        return this.planActions.resolve(action, ceid, atMs);
    }

    /** 挂链路相位监听（幂等覆盖）。 */
    public void onLinkPhase(java.util.function.BiConsumer<String, SessionRuntime.LinkPhase> listener) {
        this.lifecycle.onLinkPhase(listener);
    }

    @Override
    public SessionRuntime.LinkPhase linkPhase() {
        return this.lifecycle.linkPhase();
    }

    @Override
    public void disconnect() {
        this.lifecycle.disconnect();
    }

    @Override
    public void reconnect() throws IOException {
        this.lifecycle.reconnect();
    }

    @Override
    public void open() throws IOException {
        this.lifecycle.open();
    }

    @Override
    public void close() {
        this.lifecycle.close();
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

    void recordError(Exception e) {
        this.diagnostics.recordError(e);
    }

    /**

     * 记下一条会话级错误（含协议层日志里的异常）。
     *
     * <p>协议层把 bind 失败、连接被拒、超时等都塞进日志队列；没有监听者时这些信息
     * 会直接消失。HsmsConnector 把其中的 Throwable 转到这里，界面与 lastError 才看得见。</p>
     *
     * @param e 错误
     */
    void recordFailure(Throwable e) {
        this.diagnostics.recordFailure(e);
    }

    @Override
    public String lastError() {
        return this.diagnostics.lastError();
    }

    /**
     * 把报文发布到事件总线。
     *
     * <p>只发布<b>数据报文</b>（stream ≥ 0）：HSMS 的 select/deselect 之类控制帧
     * 对使用者没有意义，混进报文面板只会造成噪音。
     * </p>
     */
    void publish(String direction, SecsMessage message) {
        this.notifier.publish(direction, message);
    }

    @Override
    public String catalogJson() {
        return SessionJsonViews.catalogJson(this.model, this.hostReports, this.pinnedValues::isPinned);
    }

    /** 一条终端消息。 */
    public record TerminalMessage(int tid, String text, long atMillis) {
    }

    /** 最近收到的终端消息（新→旧）。 */
    public List<TerminalMessage> terminalMessages() {
        return this.tailView.messages();
    }

    @Override
    public String terminalMessagesJson() {
        return this.tailView.json();
    }

    /**
     * S1F3 请求一组 SVID，S1F4 必须按<b>同样的顺序与个数</b>返回。
     *
     * <p>E5 允许空列表表示"全部 SVID"。自动发现型主机常用它一次拿全，
     * 这里与 S1F11 的空列表语义保持一致。</p>
     */
    void replyS1F4(SecsMessage primary) throws Exception {
        this.svidReader.replyS1F4(primary);
    }

}