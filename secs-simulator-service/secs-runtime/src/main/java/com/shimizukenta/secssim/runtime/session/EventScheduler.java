package com.shimizukenta.secssim.runtime.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.core.event.ConditionDebouncer;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.EventSchedule;
import com.shimizukenta.secssim.core.event.Fire;
import com.shimizukenta.secssim.core.event.Trigger;
import com.shimizukenta.secssim.core.template.ExpressionException;
import com.shimizukenta.secssim.core.curve.Rng;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 事件编排的运行时：计划表、限流、条件去抖与延迟链。
 *
 * <p>会话层只管"什么时候该算一轮"，具体哪条计划在什么时刻触发、
 * 被限流器挡下没有、AfterEvent 链怎么级联，都收在这里。</p>
 */
final class EventScheduler {

    /** 每个 tick 求值条件的节流间隔（毫秒）。 */
    private static final long EVENT_TICK_MS = 100L;

    /** 当前编排表。 */
    private volatile EventSchedule schedule = EventSchedule.empty();

    /**
     * 预览用的编排表：包含运行时新增/修改的计划，排除被禁用的计划。
     *
     * <p>预览必须与"现在界面上的编排"一致，否则刚加的计划在预览里看不见。</p>
     */
    EventSchedule previewSchedule() {
        return new EventSchedule(this.schedule.plans().stream()
                .filter(plan -> !this.disabledPlans.contains(plan.id()))
                .toList());
    }

    /**
     * 在<b>完整</b>编排表里按 id 找一条计划（含被禁用的计划）。
     *
     * <p>与 {@link #previewSchedule()} 的区别：预览表过滤掉了禁用计划，供
     * "实际触发 / 预览"用；而单步试跑（dry-run）要能试跑界面列表里出现的
     * 任意一条计划——列表来自完整表，默认所有计划都处于禁用态。若沿用预览表，
     * 试跑任何一条默认计划都会报 "plan not found"。启停 {@link #setEventEnabled}
     * 同样按完整表查找，这里保持一致。</p>
     *
     * @param planId 计划标识
     * @return 命中的计划；不存在返回 {@code null}
     */
    EventPlan findPlan(String planId) {
        for (EventPlan plan : this.schedule.plans()) {
            if (plan.id().equals(planId)) {
                return plan;
            }
        }
        return null;
    }

    /** 事件随机源（抖动与运行时同源）。 */
    private final Rng eventRng;

    /** 设备级限流闸：与工艺周期共用同一只（拆分后两个运行时共享上限）。 */
    private final SessionRateGate gate;

    /** 上一次算到的时间点。 */
    private volatile long lastFireMs;

    /** 延迟链：AfterEvent 触发后排队等一会儿的事件。 */
    private final PriorityQueue<PendingFire> pendingFires = new PriorityQueue<>((a, b) -> {
        int byTime = Long.compare(a.atMs(), b.atMs());
        return byTime != 0 ? byTime : a.planId().compareTo(b.planId());
    });

    /** 条件去抖器：每个 OnCondition 计划一个。 */
    private final Map<String, ConditionDebouncer> conditionDebouncers = new ConcurrentHashMap<>();

    /** 已经报过错的条件计划，不重复刷屏。 */
    private final Set<String> conditionErrorLogged = ConcurrentHashMap.newKeySet();

    /** 被禁用的计划（运行时启停）。 */
    private final Set<String> disabledPlans = ConcurrentHashMap.newKeySet();

    /**
     * 每条计划的启动锚点（planId → enabledAt，时钟系毫秒）。
     *
     * <p>手动启动时刻即该计划的初始时间（2026-11-16 设计 D2）：At 在
     * {@code enabledAt + atMs} 触发、Periodic 第 k 次在 {@code enabledAt + k×period}；
     * 全停 / 禁用时移除。没有锚点的计划按时钟零点求值（纯区间语义）。</p>
     */
    private final Map<String, Long> enabledAnchors = new ConcurrentHashMap<>();

    /** CEID 是否已定义（供引用校验用）。 */
    private java.util.function.IntPredicate knownCeid;

    /** 当前时钟（毫秒）。 */
    private final java.util.function.LongSupplier clock;

    /** 值来源：条件表达式求值用。 */
    private final java.util.function.Supplier<ValueSource> values;

    /** 是否允许主动上报。 */
    private final java.util.function.BooleanSupplier canSend;

    /** 真正发一条 CEID（带计划身份，效果只施加该计划）。 */
    private final java.util.function.BiConsumer<Integer, String> emit;

    /** 出错回调。 */
    private java.util.function.Consumer<Exception> errorSink;

    /** 会话 id（错误日志用）。 */
    private String sessionId = "";

    /** 延迟链里排队的事件。 */
    record PendingFire(long atMs, String planId, int ceid) {
    }

    /** 因 CEID 被禁用而丢弃的条数。 */
    private final java.util.concurrent.atomic.AtomicLong skippedCounter = new java.util.concurrent.atomic.AtomicLong();

    /** 因限流被丢弃的条数。 */
    private final java.util.concurrent.atomic.AtomicLong rejectedCounter = new java.util.concurrent.atomic.AtomicLong();

    /** 记一次"事件被禁用而丢弃"。 */
    void recordSkipped() {
        this.skippedCounter.incrementAndGet();
    }

    /** 因 CEID 被禁用而丢弃的条数。 */
    long skippedCount() {
        return this.skippedCounter.get();
    }

    /** 因限流被丢弃的条数。 */
    long rejectedCount() {
        return this.rejectedCounter.get();
    }

    EventScheduler(Rng eventRng,
                   SessionRateGate rateGate,
                   java.util.function.LongSupplier clock,
                   java.util.function.Supplier<ValueSource> values,
                   java.util.function.BooleanSupplier canSend,
                   java.util.function.BiConsumer<Integer, String> emit) {
        this.eventRng = eventRng;
        this.gate = rateGate;
        this.clock = clock;
        this.values = values;
        this.canSend = canSend;
        this.emit = emit;
    }

    /**
     * 启动调度：从当前时刻起算。
     *
     * <p>不初始化的话 {@code lastFireMs} 会停在 0，上线那一刻
     * 会把"从会话开到现在"的所有周期一次性发出去——设备刚 ONLINE
     * 就涌出几百条，这正是"计划等 ONLINE"要避免的。</p>
     */
    void start() {
        this.lastFireMs = this.clock.getAsLong();
    }

    void errorSink(java.util.function.Consumer<Exception> sink) {
        this.errorSink = sink;
    }

    void sessionId(String id) {
        this.sessionId = id;
    }

    /** 设置 CEID 是否已定义的判定（引用校验用）。 */
    void knownCeid(java.util.function.IntPredicate predicate) {
        this.knownCeid = predicate;
    }

    /** 判断某个 CEID 是否被禁用（S2F37 或运行时禁用）。 */
    private java.util.function.IntPredicate ceidEnabled;

    /** 设置 CEID 启用判定。 */
    void ceidEnabled(java.util.function.IntPredicate predicate) {
        this.ceidEnabled = predicate;
    }

    /** CEID 是否可用。 */
    private boolean ceidEnabled(int ceid) {
        java.util.function.IntPredicate predicate = this.ceidEnabled;
        return predicate == null || predicate.test(ceid);
    }

    /** 现在能不能主动发。 */
    private boolean canSend() {
        java.util.function.BooleanSupplier supplier = this.canSend;
        return supplier == null || supplier.getAsBoolean();
    }

    /** 引用校验：CEID 必须已定义（本地目录或主机侧）。 */
    private void requireKnownCeid(int ceid) {
        java.util.function.IntPredicate known = this.knownCeid;
        if (known != null && !known.test(ceid)) {
            throw new IllegalArgumentException("CEID 未定义: " + ceid + "（先在「事件设置」里定义它）");
        }
    }

    /** 记录一次错误。 */
    private void recordError(Exception e) {
        java.util.function.Consumer<Exception> sink = this.errorSink;
        if (sink != null) {
            sink.accept(e);
        }
    }


    /**
     * 启用事件编排。必须在 {@link #open()} 之前调用。
     *
     * @param schedule        编排表
     * @param maxEventsPerSec 设备级每秒最大上报数
     */
    synchronized void enableSchedule(EventSchedule schedule, int maxEventsPerSec) {
        /* 评审 MED-5：先重建禁用集/去抖器，最后换表——pump 与本方法互斥（同一监视器），
           但把 schedule 的发布放到状态就绪之后，读路径任何时候看到的都是一致快照 */
        EventSchedule next = schedule == null ? EventSchedule.empty() : schedule;
        this.gate.configure(maxEventsPerSec);

        /* 3.0 需求：所有计划默认禁用。
           配置里的计划只描述"触发规则"，不表示"现在就要跑"——设备一上线就
           按周期往主机灌 S6F11，现场看到的是"没人点过就开始发"。这里统一置为
           禁用，由操作员在事件编排页显式「启动」后才参与调度。 */
        this.disabledPlans.clear();
        for (EventPlan plan : next.plans()) {
            this.disabledPlans.add(plan.id());
        }

        this.conditionDebouncers.clear();
        this.conditionErrorLogged.clear();
        this.enabledAnchors.clear();
        synchronized (this.pendingFires) {
            this.pendingFires.clear();
        }
        for (EventPlan plan : next.plans()) {
            if (plan.trigger() instanceof Trigger.OnCondition condition) {
                this.conditionDebouncers.put(plan.id(), new ConditionDebouncer(condition.debounceMs()));
            }
        }
        this.schedule = next;
    }

    /**
     * 全停：所有计划回到 stopped，清启用锚点、去抖进度与延迟链（设计 §2.1）。
     *
     * <p>挂点在 {@code HsmsSessionRuntime.stopAllPlans()}：控制状态离开 ONLINE、
     * 链路相位脱离 SELECTED、热重载（经 enableSchedule）、钉值 / S2F15 写常量 /
     * W 位与限流变化。工艺周期由调用方一并停掉。</p>
     */
    synchronized void stopAll() {
        this.disabledPlans.clear();
        for (EventPlan plan : this.schedule.plans()) {
            this.disabledPlans.add(plan.id());
        }
        this.enabledAnchors.clear();
        this.conditionDebouncers.clear();
        this.conditionErrorLogged.clear();
        synchronized (this.pendingFires) {
            this.pendingFires.clear();
        }
    }


    /** 当前启用中的计划数（分段生效的"保留了几条"提示用）。 */
    synchronized int enabledCount() {
        int count = 0;
        for (EventPlan plan : this.schedule.plans()) {
            if (!this.disabledPlans.contains(plan.id())) {
                count += 1;
            }
        }
        return count;
    }

    /** 只改限流：不换表、不动启用态（事件发送口径页）。 */
    synchronized void maxPerSec(int maxEventsPerSec) {
        this.gate.configure(maxEventsPerSec);
    }

    /**
     * 换编排表，但保留没改过的那几条计划的启用态与锚点（分段生效专用）。
     *
     * <p>与 {@link #enableSchedule} 的区别：那个是「重建会话 / 模型换了」，
     * 一律全停；这里只改事件计划页时用——操作员手上一堆正在跑的计划，
     * 不该因为改了一条不相关的计划全部回到禁用。规则：</p>
     *
     * <ul>
     *   <li>新表里内容与旧表完全一致（{@link EventPlan#equals}）且原本启用 → 保留启用态与锚点；</li>
     *   <li>新增 / 改动 / 原本禁用的计划 → 保持禁用（新增的还要操作员显式启动）；</li>
     *   <li>删掉的计划 → 挂起触发、去抖进度一并丢弃。</li>
     * </ul>
     *
     * @param schedule        新编排表
     * @param maxEventsPerSec 新限流
     */
    synchronized void replaceSchedulePreservingState(EventSchedule schedule, int maxEventsPerSec) {
        EventSchedule next = schedule == null ? EventSchedule.empty() : schedule;
        this.gate.configure(maxEventsPerSec);

        Map<String, EventPlan> previous = new java.util.LinkedHashMap<>();
        for (EventPlan plan : this.schedule.plans()) {
            previous.put(plan.id(), plan);
        }

        Set<String> keepRunning = new java.util.LinkedHashSet<>();
        Set<String> disabled = new java.util.LinkedHashSet<>();
        Map<String, Long> anchors = new java.util.LinkedHashMap<>();
        for (EventPlan plan : next.plans()) {
            EventPlan old = previous.get(plan.id());
            boolean unchanged = old != null && old.equals(plan);
            boolean wasEnabled = old != null && !this.disabledPlans.contains(plan.id());
            if (unchanged && wasEnabled) {
                keepRunning.add(plan.id());
                Long anchor = this.enabledAnchors.get(plan.id());
                if (anchor != null) {
                    anchors.put(plan.id(), anchor);
                }
            } else {
                disabled.add(plan.id());
            }
        }

        this.conditionDebouncers.keySet().retainAll(keepRunning);
        this.conditionErrorLogged.retainAll(keepRunning);
        synchronized (this.pendingFires) {
            this.pendingFires.removeIf(fire -> !keepRunning.contains(fire.planId()));
        }
        this.disabledPlans.clear();
        this.disabledPlans.addAll(disabled);
        this.enabledAnchors.clear();
        this.enabledAnchors.putAll(anchors);
        this.schedule = next;
    }

    /**
     * 当前编排列表（含启用状态、触发规则与归属）。
     *
     * @param ceidOwner CEID → 归属单元名（由会话从设备模型反查）
     * @return JSON 文本
     */
    String eventsJson(java.util.function.IntFunction<String> ceidOwner) {
        /* v1 列表也带活倒计时：周期计划启动后界面「下次事件」列才有数（评审 HH） */
        java.util.Map<String, PlanRuntimeStat> live = new java.util.LinkedHashMap<>();
        for (EventPlan plan : this.schedule.plans()) {
            Long next = this.nextFireAt(plan.id());
            if (next != null) {
                live.put(plan.id(), new PlanRuntimeStat(0L, 0L, 0L, next, "",
                        Math.max(0L, next - this.clock.getAsLong())));
            }
        }
        return SessionJsonViews.eventsJson(this.schedule.plans(), this.disabledPlans, ceidOwner,
                live, this::planStatus);
    }

    /**
     * 编排列表 + 每条计划的运行统计（v2 计划页用）。
     *
     * @param ceidOwner CEID → 归属单元名
     * @param stats     planId → 运行统计
     * @return JSON 文本
     */
    String eventsJson(java.util.function.IntFunction<String> ceidOwner,
                      java.util.Map<String, PlanRuntimeStat> stats) {
        /* next_fire_at 的活值在调度器手里（锚 = 启动时刻）：覆盖 DB 里可能过期的读数，
           周期计划启动后倒计时精确显示（2026-11-16 设计 §2.1） */
        java.util.Map<String, PlanRuntimeStat> merged = new java.util.LinkedHashMap<>(stats);
        for (EventPlan plan : this.schedule.plans()) {
            Long next = this.nextFireAt(plan.id());
            if (next == null) {
                continue;
            }
            PlanRuntimeStat old = merged.getOrDefault(plan.id(), PlanRuntimeStat.EMPTY);
            /* nextFireInMs：界面倒计时直接用（会话时钟与浏览器时钟不同源，绝对时刻没法减） */
            merged.put(plan.id(), new PlanRuntimeStat(old.fireCount(), old.skippedCount(),
                    old.lastFireAt(), next, old.lastError(), Math.max(0L, next - this.clock.getAsLong())));
        }
        return SessionJsonViews.eventsJson(this.schedule.plans(), this.disabledPlans,
                ceidOwner, merged, this::planStatus);
    }

    /** 计划当前是否处于停止（含全停后的状态）。 */
    boolean isDisabled(String planId) {
        return this.disabledPlans.contains(planId);
    }

    /**
     * 一条计划此刻的运行态（界面用）。
     *
     * <p>三个状态互斥：没启用是 {@code stopped}；启用了但设备没在线（严格 GEM
     * 下不允许上报）或被主机 S2F37 屏蔽了该 CEID 是 {@code waiting}；真正在发
     * 才是 {@code running}。</p>
     *
     * @param plan 计划
     * @return 运行态
     */
    SessionJsonViews.PlanStatus planStatus(EventPlan plan) {
        if (this.disabledPlans.contains(plan.id())) {
            return new SessionJsonViews.PlanStatus("stopped", "");
        }
        if (!this.canSend()) {
            return new SessionJsonViews.PlanStatus("waiting", "offline");
        }
        if (!this.ceidEnabled(plan.ceid())) {
            return new SessionJsonViews.PlanStatus("waiting", "ceid-disabled");
        }
        return new SessionJsonViews.PlanStatus("running", "");
    }


    /**
     * 运行时启用/禁用一个计划。
     *
     * <p>启用：锚点 = 当前时刻（手动启动时间即初始时间，D2）；
     * {@link Trigger.Periodic periodic} 计划<b>立即触发一次</b>（过限流、走台账），
     * 此后第 k 次在 {@code enabledAt + k×period ± 抖动}。
     * 禁用会清掉该计划的条件去抖状态、延迟队列条目与锚点：重新启用后
     * 不应把禁用期间攒下的事件补发出去。</p>
     *
     * <p>ONLINE 门控在 {@code HsmsSessionRuntime} 层做（409 not-online）——
     * 调度器不认识控制状态。</p>
     *
     * @param planId  计划标识
     * @param enabled 是否启用
     * @return 计划存在返回 {@code true}
     */
    synchronized boolean setEventEnabled(String planId, boolean enabled) {
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
            long now = this.clock.getAsLong();
            this.disabledPlans.remove(planId);
            this.enabledAnchors.put(planId, now);
            if (target.trigger() instanceof Trigger.OnCondition condition) {
                this.conditionDebouncers.computeIfAbsent(planId,
                        id -> new ConditionDebouncer(condition.debounceMs()));
            }
            if (target.trigger() instanceof Trigger.Periodic) {
                /* 周期计划启动即触发一次（D2）：走 firePlan 过限流与 S2F37，落台账 */
                this.firePlan(planId, target.ceid(), now, now);
            }
        } else {
            this.disabledPlans.add(planId);
            this.enabledAnchors.remove(planId);
            this.conditionDebouncers.remove(planId);
            synchronized (this.pendingFires) {
                this.pendingFires.removeIf(fire -> fire.planId().equals(planId));
            }
        }
        return true;
    }

    /** 实时启用锚点快照（预览用：预览标记与实跑同锚）。 */
    java.util.Map<String, Long> anchorsView() {
        return java.util.Map.copyOf(this.enabledAnchors);
    }

    /**
     * 周期计划的下一次触发时刻（倒计时用；锚 = 启动时刻）。
     *
     * <p>不含抖动（±jitterMs 内浮动）；禁用 / 非周期 / 未启用返回 {@code null}。</p>
     *
     * @param planId 计划标识
     * @return 下次触发时刻，或 {@code null}
     */
    Long nextFireAt(String planId) {
        Long anchor = this.enabledAnchors.get(planId);
        if (anchor == null) {
            return null;
        }
        for (EventPlan plan : this.schedule.plans()) {
            if (!plan.id().equals(planId)) {
                continue;
            }
            if (!(plan.trigger() instanceof Trigger.Periodic periodic)) {
                return null;
            }
            long now = this.clock.getAsLong();
            long k = Math.floorDiv(now - anchor, periodic.periodMs()) + 1L;
            if (k < 1L) {
                k = 1L;
            }
            return anchor + k * periodic.periodMs();
        }
        return null;
    }


    /* ======================================================================
     * 运行时编辑（设备页·事件 / 报告 / 状态变量的增删改;不写回配置,重载后失效）
     * ====================================================================== */

    /**
     * 运行时新增编排计划；id 重复返回 {@code false}。
     *
     * @param plan 计划
     * @return 是否成功
     */
    synchronized boolean addEventPlan(EventPlan plan) {
        this.requireKnownCeid(plan.ceid());
        List<EventPlan> plans = new ArrayList<>(this.schedule.plans());
        if (plans.stream().anyMatch(p -> p.id().equals(plan.id()))) {
            return false;
        }
        plans.add(plan);
        replaceSchedule(new EventSchedule(plans));
        /* 运行时新增的计划同样默认禁用：加完还要显式「启动」才会按规则触发 */
        this.disabledPlans.add(plan.id());
        return true;
    }


    /**
     * 运行时更新编排计划；不存在返回 {@code false}。
     *
     * @param planId 计划标识（必须与 next.id() 一致）
     * @param next   新计划
     * @return 是否成功
     */
    synchronized boolean updateEventPlan(String planId, EventPlan next) {
        if (!planId.equals(next.id())) {
            throw new IllegalArgumentException("planId 与新计划的 id 不一致: " + planId + " vs " + next.id());
        }
        this.requireKnownCeid(next.ceid());
        List<EventPlan> plans = new ArrayList<>(this.schedule.plans());
        int index = -1;
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i).id().equals(planId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return false;
        }
        plans.set(index, next);
        replaceSchedule(new EventSchedule(plans));
        /* 评审 MED-6：旧规则排进延迟链的 AfterEvent 条目一并清除——
           否则新计划会按旧 ceid/旧时刻被触发 */
        synchronized (this.pendingFires) {
            this.pendingFires.removeIf(fire -> fire.planId().equals(planId));
        }
        /* 改的是启用中的计划：锚点重置为现在，新触发规则从改完这刻起算 */
        if (!this.disabledPlans.contains(planId)) {
            this.enabledAnchors.put(planId, this.clock.getAsLong());
        }
        return true;
    }


    /**
     * 运行时删除编排计划（连同其待触发队列与去抖状态）。
     *
     * @param planId 计划标识
     * @return 是否删除了计划
     */
    synchronized boolean removeEventPlan(String planId) {
        List<EventPlan> plans = new ArrayList<>(this.schedule.plans());
        boolean existed = plans.removeIf(p -> p.id().equals(planId));
        if (!existed) {
            return false;
        }
        replaceSchedule(new EventSchedule(plans));
        this.disabledPlans.remove(planId);
        this.enabledAnchors.remove(planId);
        this.conditionDebouncers.remove(planId);
        this.conditionErrorLogged.remove(planId);
        synchronized (this.pendingFires) {
            this.pendingFires.removeIf(fire -> fire.planId().equals(planId));
        }
        return true;
    }


    /** 换编排表并重建条件去抖器（计划增删改共用）。 */
    void replaceSchedule(EventSchedule next) {
        this.schedule = next;
        this.conditionDebouncers.clear();
        this.conditionErrorLogged.clear();
        for (EventPlan plan : next.plans()) {
            if (plan.trigger() instanceof Trigger.OnCondition condition) {
                this.conditionDebouncers.put(plan.id(), new ConditionDebouncer(condition.debounceMs()));
            }
        }
    }


    synchronized void pumpEvents() {
        try {
            if (!this.canSend()) {
                /* 没有主机在线时不要积压事件：上线后从当前时刻重新开始，避免一次涌出几百条 */
                this.lastFireMs = this.clock.getAsLong();
                this.resetConditions();
                synchronized (this.pendingFires) {
                    this.pendingFires.clear();
                }
                return;
            }

            long now = this.clock.getAsLong();
            long from = this.lastFireMs;

            /* 条件触发必须每个 tick 求值，不能只靠区间查询 */
            this.pumpConditions(now);

            if (now > from) {
                this.lastFireMs = now;
                /* per-plan 启动锚点：At/Periodic 从 enabledAt 起算（D2） */
                for (Fire fire : this.schedule.between(from, now, this.eventRng, this.enabledAnchors)) {
                    this.firePlan(fire.planId(), fire.ceid(), fire.atMs(), now);
                }
            }

            /* AfterEvent 可能在同一 tick 内到期，甚至级联到下一层 */
            this.pumpPending(now);
        } catch (Exception e) {
            recordError(e);
            Log.error("会话 " + this.sessionId + " 事件上报失败", e);
        }
    }


    /**
     * 求值全部 OnCondition 计划。
     *
     * <p>去抖状态在 {@link ConditionDebouncer} 里；这里只负责把当前 SVID 值
     * 喂给表达式、按限流器发 CEID，并在主机掉线/事件被禁用时清状态。</p>
     */
    void pumpConditions(long nowMs) {
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

            if (!this.ceidEnabled(plan.ceid())) {
                debouncer.reset();
                continue;
            }

            boolean active;
            try {
                active = condition.expression().evaluate(name -> this.conditionVariable(name, nowMs)) != 0.0;
            } catch (RuntimeException e) {
                recordError(e);
                if (this.conditionErrorLogged.add(plan.id())) {
                    Log.error("会话 " + this.sessionId + " 条件表达式失败: plan " + plan.id(), e);
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
    void firePlan(String planId, int ceid, long atMs, long nowMs) {
        if (this.disabledPlans.contains(planId)) {
            return;
        }
        if (!this.ceidEnabled(ceid)) {
            this.skippedCounter.incrementAndGet();
            return;
        }
        if (!this.gate.tryAcquire(nowMs)) {
            this.rejectedCounter.incrementAndGet();
            return;
        }

        try {
            this.emit.accept(ceid, planId);
            this.recordFire(planId, atMs);
        } catch (Exception e) {
            recordError(e);
            Log.error("会话 " + this.sessionId + " 计划触发失败: plan " + planId, e);
        }
    }


    /** 源计划成功后，把它的 AfterEvent 后继加入延迟队列。 */
    void recordFire(String planId, long atMs) {
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
    void pumpPending(long nowMs) {
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
                recordError(new IllegalStateException("AfterEvent 链超过 64 跳，疑似环: " + next.planId()));
                Log.error("会话 " + this.sessionId + " 事件上报失败", null);
                return;
            }

            this.firePlan(next.planId(), next.ceid(), next.atMs(), nowMs);
        }
    }


    double conditionVariable(String name, long nowMs) {
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
            return this.values.get().valueAt(svid, nowMs).orElseThrow(
                    () -> new ExpressionException("条件表达式引用的 SVID 没有值: " + svid));
        }
        throw new ExpressionException("条件表达式只支持 t 和 svid.<id>: " + name);
    }


    void resetConditions() {
        for (ConditionDebouncer debouncer : this.conditionDebouncers.values()) {
            debouncer.reset();
        }
    }
}
