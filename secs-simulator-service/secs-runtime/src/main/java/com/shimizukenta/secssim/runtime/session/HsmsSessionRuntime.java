package com.shimizukenta.secssim.runtime.session;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
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
 * 用 HSMS-SS 实现一个设备会话：S1F1→S1F2、S1F3→S1F4（值来自 {@link ValueSource}）、
 * S6F11 主动上报（手动/编排触发）。设备级限流与"单飞"在这里——单元剧本配错不能把主机打爆。
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

    /** 设备常量（ECV）当前值：初值来自配置，S2F15 写入后经 constantSaver 落盘，重载/重启保留。 */
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

    volatile SecsCommunicator communicator;
    private volatile boolean s6f11Wbit;
    private volatile boolean s6f1Wbit;
    /** 单线程串行等 S6F12：W 位发送会阻塞到 T3，不能占用 100ms 事件心跳。 */
    volatile ExecutorService ackSender;
    /** 单线程串行等 S6F2：Trace 采样不能被 ACK 阻塞。 */
    volatile ExecutorService traceAckSender;
    /** 单线程串行等 S5F2：与 S6F11 分池——共用时主机不回 S5F2 会把事件流堵死。 */
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

    /** 创建会话；clock 可注入手动时钟以获得确定性（测试用）。 */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model, EngineClock clock) {
        this(spec, model, clock, null);
    }

    /** 创建会话并指定值来源；values 传 null 用默认曲线。 */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model, EngineClock clock, ValueSource values) {
        this(spec, model, clock, values, spec.traceLimits());
    }

    /** 创建会话（带 Trace 容量限制；traceLimits 传 null 用默认）。 */
    public HsmsSessionRuntime(SessionSpec spec, ResolvedEquipment model, EngineClock clock,
                              ValueSource values,
                              TraceLimits traceLimits) {
        this(spec, model, clock, values, traceLimits, null, null);
    }

    /**
     * 创建会话（全参数 + 工艺周期）。cycle 传 null = 没有 cycle 段；
     * lifecycleClock 必须与 values 内部用的是<b>同一个实例</b>（值随周期启停，设计 §5.2），
     * 传 null 时 runner 用独立直通时钟（仅状态展示，不影响值）。
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
        this.metrics = SessionMetricsWiring.create(this);
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
     * S6F11 是否带 W 位等 S6F12（默认 false：逐条 ACK 会阻塞到 T3；开启走单线程队列）。
     * W 位变化 = 参数变化 → 全停（设计 D6）。
     */
    public void collectionEventWbit(boolean enabled) {
        boolean previous = this.s6f11Wbit;
        this.s6f11Wbit = enabled;
        if (previous != enabled) {
            /* W 位变化 = 参数变化 → 全停（2026-11-16 设计 §2.1-6/D6） */
            this.stopAllPlans();
        }
    }

    /** S6F1 是否带 W 位等 S6F2（取舍同 S6F11：默认 false，开启走独立队列不阻塞采样）。 */
    public void traceWbit(boolean enabled) {
        boolean previous = this.s6f1Wbit;
        this.tracePump.s6f1Wbit(enabled);
        this.s6f1Wbit = enabled;
        if (previous != enabled) {
            this.stopAllPlans();
        }
    }

    /** 值存储（诊断与测试用）。 */
    public ValueStore valueStore() {
        return this.store;
    }

    /** 已解析的设备模型。 */
    public ResolvedEquipment model() {
        return this.model;
    }

    /** 值来源（诊断与预览用）。 */
    public ValueSource valueSource() {
        return this.values;
    }

    /** 曲线预览：分桶 min/max 包络 + avg 线（buckets 1..500）；计算与序列化在 {@link PreviewSupport}。 */
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
     * 手动发送一条 SML 报文：解析失败 400、没有对端 409、协议层发送失败 500，
     * 三者不能混成一个 "send failed"。
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

    /** 迁移控制状态并在变化时发布通知；离开 ONLINE 一律全停（设计 §2.1-4/5）。 */
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
     * 全停钩子（设计 §2.1）：计划与工艺周期回 stopped。触发面：新建/重建/热重载/
     * 离开 ONLINE/脱离 SELECTED/参数变化（W 位、限流、S2F15、钉值）。清启用态/去抖/延迟链。
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
     * 接入 v7 数据模型（晶圆属性表 + 设备常量取值器）：装配器持有引用而非快照，
     * 热生效整体替换后下一次 S6F11 立刻用新值，不必重建会话。
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

    /** 运行时编排表视图（界面用）；计划归属按设备模型反查（ceidOwner）。 */
    public String eventsJson() {
        return this.scheduler.eventsJson(this::ceidOwner);
    }

    /** 编排表 + 运行统计（v2 计划页：倒计时与「启用后确实在跑」的证据）；stats 为 null 不带统计。 */
    public String eventsJson(java.util.Map<String, PlanRuntimeStat> stats) {
        return this.scheduler.eventsJson(this::ceidOwner, stats);
    }

    /** CEID → 归属单元名；查不到（被主机 S2F33 覆盖、或模型已换）返回空串。 */
    private String ceidOwner(int ceid) {
        ResolvedEquipment current = this.model;
        ResolvedCeid resolved = current == null ? null : current.ceids().get(ceid);
        return resolved == null ? "" : resolved.owner();
    }

    /**
     * 运行时启停一个计划（2026-11-16 设计 §2.2）：启用方向有 ONLINE 门控
     * （SELECTED 且 ONLINE 才允许，否则 NOT_ONLINE → HTTP 409）；启用成功周期计划立即触发一次。
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

    /** 工艺周期是否 RUNNING（分段保存守卫 B 用：运行中的周期才保护被引用项）。 */
    public boolean cycleRunning() {
        return this.cycleRunner != null && this.cycleRunner.running();
    }

    /** 工艺周期运行态 JSON（cycle 页运行卡）。 */
    public String cycleJson() {
        return this.cycleRunner.stateJson();
    }

    /** 启动工艺周期（ONLINE 门控同计划启停；锚点 = 点击时刻）；NOT_ONLINE / NO_CYCLE 见返回值。 */
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

    /** 运行时新增编排计划（不写回配置）。 */
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
     * 不断连热生效（全量签名）：编排/限流/W 位/模型/值来源 + 晶圆属性表/常量取值器/工艺周期
     * 一起换；热重载是全停事件（§2.1-3），cycle 待手动启动。
     * contextTable/ecidValues 传 null = 保持现状；lifecycleClock 与 values 同实例。
     */
    public void applyHot(EventSchedule schedule, int maxEventsPerSec,
                         boolean s6f11Wbit, boolean s6f1Wbit,
                         ResolvedEquipment model, ValueSource values,
                         com.shimizukenta.secssim.core.context.ContextTable contextTable,
                         EcidValues ecidValues,
                         CycleConfig cycle,
                         LifecycleCycleClock lifecycleClock) {
        /* 多步替换与心跳共用 pumpLock（评审 2026-11-24 P2-1）：各步独立加锁时，
           一个 tick 可能拿到新组装器+旧值来源的混合快照；整段持锁让替换原子可见。 */
        synchronized (this.pumpLock) {
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
    }

    /** 分段生效判定（从本类抽到 SectionApplySupport；此处保留别名兼容既有引用）。 */
    record SectionEffects(
            boolean structure, boolean catalog, boolean values,
            boolean schedule, boolean throttle, boolean data, boolean cycle) {
        static SectionEffects of(java.util.Set<com.shimizukenta.secssim.runtime.ConfigSection> changed) {
            SectionApplySupport.SectionEffects fx = SectionApplySupport.SectionEffects.of(changed);
            return new SectionEffects(fx.structure(), fx.catalog(), fx.values(),
                    fx.schedule(), fx.throttle(), fx.data(), fx.cycle());
        }
    }

    /**
     * 按配置段最小生效（设计 §4/§8）；实现在 {@link SectionApplySupport}。
     * 与 applyHot（全量替换一律全停）不同：只动真正变化的段（评审 2026-11-24 A1 拆分）。
     *
     * <p>与 applyHot / pumpNow 共用 {@code pumpLock}（后续升级 UP-02）：分段替换同样
     * 是多步写入（视图 → 值来源 → 编排 → 数据 → 周期），不持锁时心跳可能读到
     * 新编排 + 旧值来源的混合快照。锁顺序固定为调用方的 lifecycleLock → 本类 pumpLock，
     * 反向获取不存在（pumpNow 不回调 SessionLauncher）。</p>
     */
    public SectionApplyOutcome applySections(
            ResolvedEquipment model, ValueSource values,
            EventSchedule schedule, int maxPerSec, boolean s6f11Wbit, boolean s6f1Wbit,
            com.shimizukenta.secssim.core.context.ContextTable contextTable,
            EcidValues ecidValues, CycleConfig cycle,
            LifecycleCycleClock lifecycleClock,
            java.util.Set<com.shimizukenta.secssim.runtime.ConfigSection> changed) {
        synchronized (this.pumpLock) {
            return SectionApplySupport.applySections(this.sectionParts,
                    model, values, schedule, maxPerSec, s6f11Wbit, s6f1Wbit,
                    contextTable, ecidValues, cycle, lifecycleClock, changed);
        }
    }

    /** 「设定」状态变量：钉住覆盖曲线，直到取消或热重载；钉值 = 参数变化 → 全停（D6）。 */
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

    

    /** 已上报的 S6F11 条数。 */
    public long emittedCount() {
        return this.eventReporter.emitted();
    }

    /** 因限流被丢弃的条数。 */
    public long rejectedCount() {
        return this.scheduler.rejectedCount();
    }

    /** 已发送的 S6F1 报文数。 */
    public long traceFramesSent() {
        return this.tracePump.framesSent();
    }

    /** 活跃的 Trace 数量。 */
    public int activeTraces() {
        return this.tracePump.activeCount();
    }

    /**
     * 立即处理一次到期工作（事件上报 + Trace 采样）；100ms 心跳驱动，测试可确定性步进。
     *
     * <p><b>整体互斥</b>：去抖状态/延迟队列彼此关联，分开加锁挡不住交错（"到点却没发"）。
     * 锁内只做状态推进与投递，不做等待式 IO——W 位等待走独立 ACK 队列，不卡心跳。</p>
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

    /** 手动上报一个采集事件（操作员显式动作，不受限流约束）。 */
    public void emitCeid(int ceid) throws IOException {
        /* 手动触发没有计划上下文：效果取该 CEID 全部启用计划的并集 */
        this.eventReporter.emit(ceid, null);
    }

    /** 调度器触发：带计划身份，效果只施加该计划。 */
    void emitCeid(int ceid, String planId) throws IOException {
        this.eventReporter.emit(ceid, planId);
    }

    /** 单步试跑计划（v2 runtime/plans/{planId}/step）；计划不存在或发送失败抛 IOException。 */
    public void stepPlan(String planId) throws IOException {
        this.eventReporter.step(planId);
    }

    /**
     * 上报/清除报警（S5F1，默认带 W 等 S5F2，走 ACK 队列不阻塞 HTTP 到 T3）；
     * ALCD 高位 1=置位 0=清除，低 3 位类别。
     */
    @Override
    public void emitAlarm(int alid, boolean set, String text, int category) throws IOException {
        this.alarmReporter.emit(alid, set, text, category);
    }

    /** 当前处于置位状态的报警（快照）。 */
    public List<AlarmState> alarms() {
        return this.alarmReporter.alarms();
    }

    @Override
    public String alarmsJson() {
        return SessionJsonViews.alarmsJson(this.alarms());
    }

    /** 一条活动报警（category 0..7，atMillis 为置位墙钟）。 */
    public record AlarmState(int alid, String text, int category, long atMillis) {
    }

    @Override
    public String id() {
        return this.spec.id();
    }

    /** 编排台账落地(3.0 需求 5);Bootstrap 装配时接入。 */
    private volatile PlanExecutor planExecutor;

    /** 挂编排执行器（每次触发落台账并计入 runtime_plan_state）。 */
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

    /** 把计划动作解析成可发送报文（设计 §2.7.1 五种类型）；规则见 {@link PlanActionResolver}。 */
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

    /** 常量落盘回调：实现方负责写哪个文件+原子替换；会话层只保证传出快照。 */
    @FunctionalInterface
    public interface ConstantSaver {

        /** 保存常量（ECID → 常量）；写盘失败抛 IOException。 */
        void save(Map<Integer, EquipmentConstantDef> constants) throws IOException;
    }

    /** 设置常量落盘回调（SessionLauncher 启动时接入）；null 表示不落盘。 */
    public void constantSaver(ConstantSaver saver) {
        /* 协作者负责协议翻译，落盘这一下交给会话层——它才知道写哪个文件 */
        this.hostSide.constantSaver(saver::save);
    }

    /** 当前设备常量快照（按 ECID 升序）。 */
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
     * 记下会话级错误（含协议层日志里的异常）：协议层把 bind 失败/超时塞进日志队列，
     * HsmsConnector 把 Throwable 转到这里，界面与 lastError 才看得见。
     */
    void recordFailure(Throwable e) {
        this.diagnostics.recordFailure(e);
    }

    @Override
    public String lastError() {
        return this.diagnostics.lastError();
    }

    /** 把报文发布到事件总线；只发数据报文（stream ≥ 0），控制帧不进报文面板。 */
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
     * S1F3 请求一组 SVID，S1F4 按同样顺序与个数返回；E5 空列表 = 全部 SVID
     * （与 S1F11 空列表语义一致）。
     */
    void replyS1F4(SecsMessage primary) throws Exception {
        this.svidReader.replyS1F4(primary);
    }


    /** 分段生效的部件访问器（SectionApplySupport；一次装配，语义同拆分前内联实现）。 */
    private final SectionApplySupport.Parts sectionParts = new SectionApplySupport.Parts() {
        @Override public ResolvedEquipment model() { return HsmsSessionRuntime.this.model; }
        @Override public void model(ResolvedEquipment next) { HsmsSessionRuntime.this.model = next; }
        @Override public SessionModelViews modelViews() { return HsmsSessionRuntime.this.modelViews; }
        @Override public ValueStore store() { return HsmsSessionRuntime.this.store; }
        @Override public SessionSpec spec() { return HsmsSessionRuntime.this.spec; }
        @Override public PinnedValueSource pinnedValues() { return HsmsSessionRuntime.this.pinnedValues; }
        @Override public void pinnedValues(PinnedValueSource next) { HsmsSessionRuntime.this.pinnedValues = next; }
        @Override public ValueSourceHandle values() { return HsmsSessionRuntime.this.values; }
        @Override public EventScheduler scheduler() { return HsmsSessionRuntime.this.scheduler; }
        @Override public CycleRunner cycleRunner() { return HsmsSessionRuntime.this.cycleRunner; }
        @Override public void stopAllPlans() { HsmsSessionRuntime.this.stopAllPlans(); }
        @Override public void collectionEventWbit(boolean enabled) { HsmsSessionRuntime.this.collectionEventWbit(enabled); }
        @Override public void traceWbit(boolean enabled) { HsmsSessionRuntime.this.traceWbit(enabled); }
    };

    /* 指标装配用的包内访问器（SessionMetricsWiring；评审 2026-11-24 A1 拆分） */
    AlarmReporter alarmReporter() { return this.alarmReporter; }
    SessionDiagnostics diagnostics() { return this.diagnostics; }
    PrimaryDispatcher dispatcher() { return this.dispatcher; }
    EventReporter eventReporter() { return this.eventReporter; }
    RateTracker msgRate() { return this.msgRate; }
    long openedAtMs() { return this.openedAtMs; }
    TerminalInbox terminalInbox() { return this.terminalInbox; }
}
