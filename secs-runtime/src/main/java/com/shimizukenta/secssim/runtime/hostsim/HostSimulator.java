package com.shimizukenta.secssim.runtime.hostsim;

import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.app.sml.SmlParser;
import com.shimizukenta.secssim.app.sml.SmlParseException;
import com.shimizukenta.secssim.app.sml.SmlRenderer;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Item;
import com.shimizukenta.secs.secs2.Secs2Exception;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主机模拟器：以<b>主机角色</b>连到一台 SECS 设备，补上 S2F33/35/37
 * 这类"只有主机能发"的报文的界面入口。
 *
 * <p>连接目标（schema v6）：默认是同进程的设备会话（{@code hostTarget()}）；
 * 也可以指定远程 SECS（host/port/deviceId 手填）——远程模式没有本地
 * catalog，SVID/CEID 相关的配置全部由调用方手填，采集值按应答项
 * 自带的格式码解码（零元数据可画图）。</p>
 *
 * <p>S1F3 采集器（v6）：收到 startCeid 的 S6F11 开始按 intervalMs 轮询
 * 配置的 SVID，收到 stopCeid 停止；数据按时间戳入环形队列供前端画图。
 * 与 FDC Trace（S2F23 设备侧推送）互补——一条是主机拉、一条是设备推。</p>
 *
 * <p>设备只接受一个选中的主机连接：本模拟器 open 失败（ALREADY_USED）
 * 通常意味着真主机已经在线——这是正确行为，不是缺陷（远程模式无法
 * 预知占用情况，冲突由连接行为自然暴露）。</p>
 */
public final class HostSimulator {

    /** 采集周期下限：再快会挤占设备的 W 位 ACK 队列。 */
    public static final long MIN_INTERVAL_MS = 200L;

    /** 主机模拟器建立通信的上限：对端不可达时不把 HTTP 请求永远挂着。 */
    static final long CONNECT_TIMEOUT_MS = 6_000L;

    /** 每 SVID 保留的采样点数（3600 = 1 小时 @1Hz）。 */
    static final int MAX_SAMPLES_PER_SVID = 3600;

    /**
     * 采集 SVID 数量上限。
     *
     * <p>每一个 SVID 都是一条独立曲线；不设上限时一次 S1F3 能塞几百个，
     * 应答与 JSON 都会线性膨胀。</p>
     */
    static final int MAX_COLLECTOR_SVIDS = 256;

    /**
     * 单条曲线一次最多返回的点数。
     *
     * <p>内存里保留 {@link #MAX_SAMPLES_PER_SVID} 点给降采样用，
     * 但 HTTP 响应最多给这么多；超过时等间隔抽稀并标 {@code truncated:true}。</p>
     */
    static final int MAX_JSON_POINTS_PER_SVID = 720;

    /** 主机实例 id(持久稳定 host_01J…)。 */
    private final String hostId;
    /** 显示名。 */
    private volatile String name;
    private volatile HsmsConnectionTarget target;
    /** 是否在失败/掉线后自动重连(退避定时器在实例侧)。 */
    private final boolean autoReconnect;
    /** 实例自带变量目录:svid → 条目(名称/单位可自动读取,类型只能声明)。 */
    private final Map<Integer, CatalogEntry> variableCatalog = new ConcurrentHashMap<>();
    /** 设置版本:保存设置 +1;appliedRevision 追平它 = 设置已生效。 */
    private final AtomicLong settingRevision = new AtomicLong(1);
    private final AtomicLong appliedRevision = new AtomicLong(1);
    /** 链路相位(host_link 的事件源)。 */
    private volatile HostLinkPhase phase = HostLinkPhase.DISCONNECTED;
    private volatile java.util.function.BiConsumer<String, HostLinkPhase> phaseListener;
    private volatile boolean manualDisconnect;
    private volatile ScheduledExecutorService retryExecutor;
    private final AtomicLong retryCount = new AtomicLong();
    private volatile long nextRetryAt;
    /** 事件总线：所有主机的收发都从这里发布(perspective=host)。 */
    private volatile com.shimizukenta.secssim.app.event.EventBus eventBus;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private volatile SecsCommunicator communicator;
    private volatile String lastError = "";

    /**
     * 建链/断链互斥锁。
     *
     * <p>{@code connect()} 里要在最多 6 秒的等待后才能确定成败，这段时间
     * {@code communicator} 已经非空；没有互斥时第二次 connect（用户连点、
     * HTTP 与退避重试撞在一起）会看到非空就抛「已连接」，或者更糟——两条
     * 线程都通过检查各建一条链路，后写者覆盖字段，先建的那条真实连接
     * 既不在字段里也无处关闭，socket 与协议线程直接泄漏。</p>
     */
    private final Object connectLock = new Object();

    /** 退避重试的创建/取消互斥：并发观察同一次断链会建出两个定时器，被覆盖的那个永不关闭。 */
    private final Object retryLock = new Object();

    /* ===== S1F3 采集器状态 ===== */
    private volatile CollectorConfig collectorConfig;
    private volatile boolean collectorRunning;
    private volatile long collectorStartedAt;
    private volatile long collectorLastRttMs;
    private volatile long collectorStoppedAt;
    private final AtomicLong collectorSamples = new AtomicLong();
    private final AtomicLong collectorDecodeErrors = new AtomicLong();
    private final AtomicBoolean collectorInFlight = new AtomicBoolean(false);
    private final Map<Integer, Deque<double[]>> collectorData = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService collectorExecutor;

    /** 主机链路相位(host_link.phase)。 */
    public enum HostLinkPhase {
        DISCONNECTED, CONNECTING, CONNECTED, FAILED,
    }

    /** 变量目录条目:名称/单位可自动读取;类型只能声明(S1F12 不含类型)。 */
    public record CatalogEntry(String name, String unit, String declaredType) {
    }

    private HostSimulator(String hostId, String name, HsmsConnectionTarget target,
                          boolean autoReconnect, Map<Integer, CatalogEntry> catalog) {
        this.hostId = hostId;
        this.name = name;
        this.target = target;
        this.autoReconnect = autoReconnect;
        if (catalog != null) {
            this.variableCatalog.putAll(catalog);
        }
    }

    /**
     * 从持久行装配实例(不连接;连接由显式 connect() 触发)。
     *
     * @param view 主机实体(host 表)
     * @param bus  事件总线
     * @return 模拟器
     */
    public static HostSimulator fromView(com.shimizukenta.secssim.store.HostRepository.HostView view,
                                         com.shimizukenta.secssim.app.event.EventBus bus) {
        HostSimulator sim = new HostSimulator(view.id(), view.name(),
                new HsmsConnectionTarget(view.targetHost(), view.targetPort(), view.targetDeviceIdNum()),
                view.autoReconnect(), parseCatalog(view.variableCatalogJson()));
        sim.eventBus = bus;
        sim.settingRevision.set(view.settingRevision());
        return sim;
    }

    /** 变量目录 JSON → 映射;坏目录按空目录继续(手动填写形态)。 */
    private static Map<Integer, CatalogEntry> parseCatalog(String json) {
        Map<Integer, CatalogEntry> result = new ConcurrentHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonHub root = JsonHub.fromJson(json);
            JsonHub svids = root.get("svids");
            if (svids != null && svids.isArray()) {
                for (JsonHub item : svids.values()) {
                    int svid = item.get("svid").optionalInt().orElse(0);
                    if (svid < 1) {
                        continue;
                    }
                    result.put(svid, new CatalogEntry(
                            item.get("name") == null ? "" : item.get("name").optionalString().orElse(""),
                            item.get("unit") == null ? "" : item.get("unit").optionalString().orElse(""),
                            item.get("type") == null ? "" : item.get("type").optionalString().orElse("")));
                }
            }
        } catch (RuntimeException e) {
            Log.warn("主机变量目录解析失败（按空目录继续）: " + e);
        }
        return result;
    }

    /** 目录 → JSON(持久化用)。 */
    public String catalogJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> items = new ArrayList<>();
        for (Map.Entry<Integer, CatalogEntry> entry : this.variableCatalog.entrySet()) {
            items.add(b.object(
                    b.pair("svid", entry.getKey()),
                    b.pair("name", entry.getValue().name()),
                    b.pair("unit", entry.getValue().unit()),
                    b.pair("type", entry.getValue().declaredType())));
        }
        return b.object(b.pair("svids", b.array(items))).toJson();
    }

    /** 声明/更新一个变量的解码类型。 */
    public void declareType(int svid, String type) {
        CatalogEntry entry = this.variableCatalog.get(svid);
        String safe = type == null ? "" : type;
        this.variableCatalog.put(svid, entry == null
                ? new CatalogEntry("", "", safe)
                : new CatalogEntry(entry.name(), entry.unit(), safe));
    }

    /** 挂相位监听(持久化 + SSE 在装配层接)。 */
    public void onPhase(java.util.function.BiConsumer<String, HostLinkPhase> listener) {
        this.phaseListener = listener;
    }

    private void changePhase(HostLinkPhase next) {
        HostLinkPhase previous = this.phase;
        if (previous == next) {
            return;
        }
        this.phase = next;
        var listener = this.phaseListener;
        if (listener != null) {
            try {
                listener.accept(this.hostId, next);
            } catch (RuntimeException e) {
                Log.error("主机相位监听失败: " + this.hostId, e);
            }
        }
    }

    /** 当前相位。 */
    public HostLinkPhase phase() {
        return this.phase;
    }

    /** 显示名。 */
    public String name() {
        return this.name;
    }

    /** 自动重连开关。 */
    public boolean autoReconnect() {
        return this.autoReconnect;
    }

    /** 设置版本(保存设置 +1)。 */
    public long settingRevision() {
        return this.settingRevision.get();
    }

    /** 当前连接实际生效的设置版本。 */
    public long appliedRevision() {
        return this.appliedRevision.get();
    }

    /** 重试次数(autoReconnect 开启时递增)。 */
    public long retryCount() {
        return this.retryCount.get();
    }

    /** 下一次重试时刻(epoch ms);无排程为 0。 */
    public long nextRetryAt() {
        return this.nextRetryAt;
    }

    /** 变量目录(只读视图)。 */
    public Map<Integer, CatalogEntry> variableCatalog() {
        return Map.copyOf(this.variableCatalog);
    }

    /**
     * 保存设置(名称/目标):settingRevision +1,下次连接生效。
     *
     * @param name      新名称;null 不改
     * @param newTarget 新目标;null 不改
     */
    public void saveSettings(String name, HsmsConnectionTarget newTarget) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (newTarget != null) {
            this.target = newTarget;
        }
        this.settingRevision.incrementAndGet();
    }

    private void cancelRetry() {
        synchronized (this.retryLock) {
            ScheduledExecutorService retry = this.retryExecutor;
            if (retry != null) {
                retry.shutdownNow();
            }
            this.retryExecutor = null;
            this.nextRetryAt = 0;
        }
    }

    /** autoReconnect 开启时按指数退避排一次重连(5s 起、上限 60s)。 */
    private void scheduleRetryIfEnabled() {
        if (!this.autoReconnect || this.communicator != null || this.manualDisconnect) {
            return;
        }
        synchronized (this.retryLock) {
            /* 双检：等锁期间可能已经连上了，或另一个线程刚排过同一次退避 */
            if (this.communicator != null || this.manualDisconnect) {
                return;
            }
            if (this.retryExecutor == null || this.retryExecutor.isShutdown()) {
                this.retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "hostsim-retry-" + this.hostId);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            long attempt = this.retryCount.incrementAndGet();
            long delay = Math.min(60_000L, 5_000L * (1L << Math.min(4, attempt - 1)));
            this.nextRetryAt = System.currentTimeMillis() + delay;
            Log.info("主机 " + this.hostId + " 将在 " + delay / 1000 + " 秒后重试（第 " + attempt + " 次）");
            this.retryExecutor.schedule(() -> {
                if (this.communicator == null && !this.manualDisconnect) {
                    try {
                        this.connect();
                    } catch (IOException | RuntimeException e) {
                        /* connect 内部已置 FAILED 并排下一次退避 */
                    }
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 主机实例 id。
     *
     * @return 实例 id
     */
    public String hostId() {
        return this.hostId;
    }

    /**
     * 挂上事件总线：本实例的收发报文会以 {@code hostId} 标记发布。
     *
     * <p>本机主机的报文设备侧也会发一份（不带 hostId），消息监控页按方向去重；
     * 远程主机的报文对端不在本进程里，只有这条通道，必须打开才能监控。</p>
     *
     * @param bus 事件总线；{@code null} 表示不发布
     */
    public void eventBus(com.shimizukenta.secssim.app.event.EventBus bus) {
        this.eventBus = bus;
        SecsCommunicator comm = this.communicator;
        if (comm != null) {
            /* 重连后会换一个 communicator，这一步也由 connect() 重新挂 */
            installPassThrough(comm);
        }
    }

    /**
     * 打开一个主机实例并立即建链（目标由调用方解析好；3.0 里与设备无关）。
     *
     * @param hostId 实例 id
     * @param name   显示名
     * @param target 连接目标（任意地址）
     * @param bus    事件总线
     * @return 已连接的模拟器
     * @throws IOException 连接失败
     */
    public static HostSimulator open(String hostId, String name, HsmsConnectionTarget target,
                                     com.shimizukenta.secssim.app.event.EventBus bus) throws IOException {
        HostSimulator sim = new HostSimulator(hostId, name, target, false, null);
        sim.eventBus = bus;
        sim.connect();
        return sim;
    }

    public void connect() throws IOException {
        synchronized (this.connectLock) {
            this.connectLocked();
        }
    }

    private void connectLocked() throws IOException {
        if (this.communicator != null) {
            throw new IOException("主机已连接: " + this.hostId);
        }
        this.cancelRetry();
        this.manualDisconnect = false;
        this.lastError = "";
        this.changePhase(HostLinkPhase.CONNECTING);
        HsmsConnectionTarget current = this.target;

        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress(current.bindHost(), current.port()));
        config.deviceId(current.deviceId());
        config.isEquip(false);

        SecsCommunicator comm = HsmsSsCommunicator.open(config);
        comm.addSecsMessageReceiveListener(this::onPrimary);
        /* 链路相位的事件源（设计 §2.4.2）：communicatable 是唯一权威 */
        comm.addSecsCommunicatableStateChangeListener(communicatable -> {
            if (communicatable) {
                this.open.set(true);
                this.changePhase(HostLinkPhase.CONNECTED);
            } else {
                this.open.set(false);
                this.changePhase(HostLinkPhase.DISCONNECTED);
                scheduleRetryIfEnabled();
            }
        });
        this.installPassThrough(comm);
        this.communicator = comm;
        /* 连接与 SELECT 是异步的：不等通信建立就发会撞 NotConnected。
           但 openAndWaitUntilCommunicating() 内部是无限等待，对端不可达时会
           一直挂着——HTTP 请求永远不返回、界面一直"连接中…"。
           放到独立线程里等，超时就关掉连接如实报错。 */
        java.util.concurrent.atomic.AtomicReference<Exception> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread opener = new Thread(() -> {
            try {
                comm.openAndWaitUntilCommunicating();
            } catch (Exception e) {
                failure.set(e);
            }
        }, "hostsim-connect-" + this.hostId);
        opener.setDaemon(true);
        opener.start();
        try {
            opener.join(CONNECT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.failConnect(comm, "等待通信建立被中断", e);
            throw new IOException("主机模拟器等待通信建立被中断", e);
        }
        if (opener.isAlive()) {
            this.failConnect(comm, "连接超时（" + CONNECT_TIMEOUT_MS + " ms 内未建立通信）", null);
            throw new IOException("主机模拟器连接超时（" + CONNECT_TIMEOUT_MS + " ms 内未建立通信）");
        }
        if (failure.get() != null) {
            /* FAILED 是终态：必须真的销毁 communicator——ACTIVE 侧内部是
               for(;;)+T5 无限重连，留着它会静默重试并抢链路（设计约束 5） */
            this.failConnect(comm, "主机连接失败: " + failure.get().getMessage(), failure.get());
            throw new IOException("主机连接失败: " + failure.get().getMessage(), failure.get());
        }
        this.open.set(true);
        this.appliedRevision.set(this.settingRevision.get());
        this.retryCount.set(0);
        this.nextRetryAt = 0;
        this.changePhase(HostLinkPhase.CONNECTED);
        Log.info("主机 " + this.hostId + " 已连接 " + current.bindHost() + ":" + current.port());
        /* 采集配置已在（重连恢复）：连接成功后自动恢复采集 */
        if (this.collectorConfig != null && !this.collectorRunning) {
            this.collectorStart("连接恢复");
        }
    }

    /**
     * 建链失败的统一收尾：关连接、清字段、置 FAILED、排一次退避。
     *
     * <p>字段必须清空。旧写法只 {@code comm.close()} 而把已关闭对象留在
     * {@code communicator} 里，后果是三重的：下一次 {@code connect()} 直接抛
     * 「主机已连接」；{@code scheduleRetryIfEnabled()} 的 {@code communicator != null}
     * 判断永远为真，autoReconnect 静默失效（界面还显示着已开启）；超时分支的
     * {@code opener} 线程也还挂在 {@code openAndWaitUntilCommunicating()} 里。</p>
     *
     * @param comm    这次建链用的连接
     * @param message 记进 lastError 的人话原因
     * @param cause   原始异常；可为 null
     */
    private void failConnect(SecsCommunicator comm, String message, Exception cause) {
        try {
            comm.close();
        } catch (IOException | RuntimeException e) {
            Log.warn("关闭建链失败的连接出错: " + this.hostId + ": " + e);
        } finally {
            if (this.communicator == comm) {
                this.communicator = null;
            }
        }
        this.open.set(false);
        this.changePhase(HostLinkPhase.FAILED);
        this.lastError = message;
        if (cause != null) {
            Log.warn("主机 " + this.hostId + " 建链失败: " + message);
        }
        this.scheduleRetryIfEnabled();
    }

    /**
     * 只断链（断开 = 保留实例、目标与采集设置，设计 §2.4.1）。
     */
    public void disconnect() {
        synchronized (this.connectLock) {
            this.disconnectLocked();
        }
    }

    private void disconnectLocked() {
        this.manualDisconnect = true;
        this.cancelRetry();
        this.collectorStop("手动断开");
        SecsCommunicator comm = this.communicator;
        this.communicator = null;
        this.open.set(false);
        if (comm != null) {
            try {
                comm.close();
            } catch (IOException e) {
                Log.warn("主机关闭连接失败: " + this.hostId + ": " + e);
            }
        }
        this.changePhase(HostLinkPhase.DISCONNECTED);
        Log.info("主机 " + this.hostId + " 已断开（实例与设置保留）");
    }

    /**
     * 使设置生效（与设备「重载」对齐）：按最新设置重连并恢复采集。
     *
     * @throws IOException 重连失败
     */
    public void apply() throws IOException {
        this.reconnect();
    }

    /**
     * 断线重连（保留原目标；= disconnect + connect）。
     *
     * <p>两段在同一把锁里做完：分开调用会留下「已断开、还没连」的窗口，
     * 并发请求或退避定时器能挤进来再建一条链路。</p>
     *
     * @throws IOException 重连失败
     */
    public void reconnect() throws IOException {
        synchronized (this.connectLock) {
            this.disconnectLocked();
            this.manualDisconnect = false;
            this.connectLocked();
        }
    }

    /**
     * 把主机侧的收发挂到事件总线（3.0：所有主机都发布，perspective=host；
     * 设备侧同一帧是设备视角，二者不去重）。
     */
    private void installPassThrough(SecsCommunicator comm) {
        final com.shimizukenta.secssim.app.event.EventBus bus = this.eventBus;
        if (bus == null) {
            return;
        }
        comm.addSendedMessagePassThroughListener(message -> this.publishHost(bus, message, true));
        comm.addReceiveMessagePassThroughListener(message -> this.publishHost(bus, message, false));
    }

    /**
     * 发布一条主机侧报文。
     *
     * <p>{@code direction} 全项目统一按<b>设备视角</b>记（{@code in} = 设备收到），
     * 界面再按需要翻转成主机视角；远程主机这条通道也照这个约定写，
     * 否则同一个监控列表里两种方向语义会混在一起。</p>
     */
    private void publishHost(com.shimizukenta.secssim.app.event.EventBus bus,
                             SecsMessage message, boolean sentByHost) {
        if (message.getStream() < 0) {
            return;
        }
        String body;
        try {
            body = SmlRenderer.render(message.secs2());
        } catch (RuntimeException e) {
            body = "<不可渲染>";
        }
        bus.publish(com.shimizukenta.secssim.app.event.MessageEvent.ofHost(
                this.hostId, sentByHost ? "in" : "out",
                message.getStream(), message.getFunction(), message.wbit(),
                body, System.currentTimeMillis()));
    }

    /**
     * 设备发来的 W 位 primary 的自动应答，让设备侧流程不卡 T3；
     * S6F11 同时喂给采集器的 CEID 触发判定。
     */
    private void onPrimary(SecsMessage message) {
        int stream = message.getStream();
        int function = message.getFunction();
        try {
            /* 设备主动发来的 W 位报文要应答，否则设备要等到 T3 超时：
               S1F1 → S1F2（在线数据）、S5F1 → S5F2（报警确认）、
               S6F11 → S6F12 / S6F1 → S6F2（事件与 Trace 确认）。 */
            if (message.wbit() && stream == 1 && function == 1) {
                this.communicator.send(message, 1, 2, false,
                        Secs2.getBuilder().list(
                                Secs2.getBuilder().ascii("SECS-SIM"),
                                Secs2.getBuilder().ascii("2.0")));
            } else if (message.wbit() && stream == 5 && function == 1) {
                /* E5：S5F2 的 ACKC5 是 1 字节 B，0 = 已接受。 */
                this.communicator.send(message, 5, 2, false,
                        Secs2.getBuilder().binary(new byte[] {0x00}));
            }
            if (message.wbit() && stream == 6 && (function == 11 || function == 1)) {
                this.communicator.send(message, 6, function == 11 ? 12 : 2, false,
                        Secs2.getBuilder().binary(new byte[] {0x00}));
            }
            if (stream == 6 && function == 11) {
                this.onS6f11(message);
            }
        } catch (Exception e) {
            Log.warn("主机自动应答失败（" + this.hostId + "）: " + e);
        }
    }

    /** 从 S6F11 body 的 L[3] 第 2 项取 CEID,比对采集器启停(与本地/远程无关)。 */
    private void onS6f11(SecsMessage message) {
        CollectorConfig config = this.collectorConfig;
        if (config == null || !this.open.get()) {
            return;
        }
        int ceid;
        try {
            Secs2 body = message.secs2();
            if (body.secs2Item() != Secs2Item.LIST || body.size() < 2) {
                return;
            }
            ceid = Secs2Read.asInt(body.get(1));
        } catch (Exception e) {
            return;
        }

        if (!this.collectorRunning && ceid == config.startCeid()) {
            this.collectorStart("CEID " + ceid);
        } else if (this.collectorRunning && ceid == config.stopCeid()) {
            this.collectorStop("CEID " + ceid);
        }
    }

    /* ===== S1F3 采集器 ===== */

    /** 采集目标 SVID（远程模式 name/unit 可空）。 */
    public record CollectorSvid(int id, String name, String unit) {

        /** 校验并创建。 */
        public CollectorSvid {
            if (id < 1) {
                throw new IllegalArgumentException("collector svid must be >= 1 but was " + id);
            }
            name = name == null ? "" : name;
            unit = unit == null ? "" : unit;
        }
    }

    /** 采集器配置。 */
    public record CollectorConfig(int startCeid, int stopCeid, long intervalMs, List<CollectorSvid> svids) {

        /** 校验并创建。 */
        public CollectorConfig {
            if (startCeid < 1 || stopCeid < 1) {
                throw new IllegalArgumentException("startCeid/stopCeid 必须 >= 1");
            }
            if (startCeid == stopCeid) {
                throw new IllegalArgumentException("startCeid 与 stopCeid 不能相同: " + startCeid);
            }
            if (intervalMs < MIN_INTERVAL_MS) {
                throw new IllegalArgumentException("intervalMs 必须 >= " + MIN_INTERVAL_MS + " 但为 " + intervalMs);
            }
            svids = List.copyOf(svids);
            if (svids.isEmpty()) {
                throw new IllegalArgumentException("采集 SVID 列表不能为空");
            }
            if (svids.size() > MAX_COLLECTOR_SVIDS) {
                throw new IllegalArgumentException("采集 SVID 最多 " + MAX_COLLECTOR_SVIDS
                        + " 个，当前 " + svids.size() + " 个");
            }
        }
    }

    /**
     * 下发采集配置（可在连接前后随时调用;会停止正在运行的采集）。
     *
     * @param json {@code {"startCeid":101,"stopCeid":102,"intervalMs":1000,"svids":[{"id":401,"name":"T","unit":"C"}]}}
     * @throws IllegalArgumentException 形状或取值非法
     */
    public void setCollectorConfig(String json) {
        JsonHub root;
        try {
            root = JsonHub.fromJson(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("采集配置不是合法 JSON: " + e.getMessage(), e);
        }

        int startCeid = JsonAccessHelper.intField(root, "startCeid");
        int stopCeid = JsonAccessHelper.intField(root, "stopCeid");
        long intervalMs = JsonAccessHelper.longField(root, "intervalMs", 1_000L);
        JsonHub svidsJson = JsonAccessHelper.arrayField(root, "svids");

        List<CollectorSvid> svids = new ArrayList<>();
        for (int i = 0; i < svidsJson.length(); i++) {
            JsonHub item = svidsJson.get(i);
            int id = JsonAccessHelper.intField(item, "id");
            svids.add(new CollectorSvid(id,
                    JsonAccessHelper.stringField(item, "name"),
                    JsonAccessHelper.stringField(item, "unit")));
        }

        CollectorConfig config = new CollectorConfig(startCeid, stopCeid, intervalMs, svids);

        /* 变量目录非空时做存在性校验（目录为空 = 手动填写形态，不校验） */
        if (!this.variableCatalog.isEmpty()) {
            for (CollectorSvid svid : svids) {
                if (!this.variableCatalog.containsKey(svid.id())) {
                    throw new IllegalArgumentException("采集 SVID " + svid.id() + " 不在变量目录里（可先从目标读取变量）");
                }
            }
        }

        /* 「停旧采集 + 换配置」必须是一个整体：collectorStart 由链路线程在 S6F11
           里触发，两步之间被唤醒会用<b>旧</b>参数启动，之后不再重读。 */
        synchronized ( this ) {
            this.collectorStop("配置更新");
            this.collectorConfig = config;

            /* 换配置后，不在新列表里的 SVID 队列没有消费者也没有出口：
               反复改采集配置会让内存只增不减 */
            Set<Integer> keep = new HashSet<>();
            for (CollectorSvid svid : config.svids()) {
                keep.add(svid.id());
            }
            this.collectorData.keySet().retainAll(keep);
        }
    }

    /** 手动启动采集（正常流程由 startCeid 的 S6F11 触发）。 */
    public void collectorStartManual() {
        if (this.collectorConfig == null) {
            throw new IllegalStateException("采集器还没有配置");
        }
        this.collectorStart("手动");
    }

    /** 手动停止采集。 */
    public void collectorStopManual() {
        this.collectorStop("手动");
    }

    private synchronized void collectorStart(String cause) {
        CollectorConfig config = this.collectorConfig;
        if (config == null || this.collectorRunning || !this.open.get()) {
            return;
        }
        this.collectorData.clear();
        this.collectorSamples.set(0);
        this.collectorDecodeErrors.set(0);
        this.collectorRunning = true;
        this.collectorStartedAt = System.currentTimeMillis();
        this.collectorStoppedAt = 0;

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hostsim-collector-" + this.hostId);
            thread.setDaemon(true);
            return thread;
        });
        this.collectorExecutor = executor;
        executor.scheduleAtFixedRate(this::collectorTick,
                0L, config.intervalMs(), TimeUnit.MILLISECONDS);
        Log.info("主机采集器已启动（" + this.hostId + "，" + cause + "）");
    }

    private synchronized void collectorStop(String cause) {
        if (!this.collectorRunning) {
            return;
        }
        this.collectorRunning = false;
        this.collectorStoppedAt = System.currentTimeMillis();
        ScheduledExecutorService executor = this.collectorExecutor;
        if (executor != null) {
            executor.shutdownNow();
            this.collectorExecutor = null;
        }
        Log.info("主机采集器已停止（" + this.hostId + "，" + cause + "）");
    }

    /** 一轮 S1F3 轮询:上一轮没回来就跳过本拍,避免在 T3 上叠队列。 */
    private void collectorTick() {
        if (!this.collectorRunning || !this.collectorInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            CollectorConfig config = this.collectorConfig;
            SecsCommunicator comm = this.communicator;
            if (config == null || comm == null) {
                return;
            }

            List<Secs2> items = new ArrayList<>();
            for (CollectorSvid svid : config.svids()) {
                items.add(Secs2.getBuilder().uint4(svid.id()));
            }
            Secs2 body = Secs2.getBuilder().list(items);

            long startedAt = System.currentTimeMillis();
            Optional<SecsMessage> reply;
            try {
                reply = comm.send(1, 3, true, body);
            } catch (Exception e) {
                this.lastError = "采集 S1F3 发送失败: " + e.getMessage();
                return;
            }
            this.collectorLastRttMs = System.currentTimeMillis() - startedAt;
            if (reply.isEmpty()) {
                return;
            }

            this.decodeS1f4(config, reply.get());
        } finally {
            this.collectorInFlight.set(false);
        }
    }

    /** S1F4 应答按位置解码;目录里声明过的类型优先,未声明按应答项格式码探测。 */
    private void decodeS1f4(CollectorConfig config, SecsMessage reply) {
        Secs2 body = reply.secs2();
        long now = System.currentTimeMillis();

        if (body.secs2Item() != Secs2Item.LIST || body.size() != config.svids().size()) {
            this.collectorDecodeErrors.incrementAndGet();
            return;
        }

        for (int i = 0; i < config.svids().size(); i++) {
            CollectorSvid svid = config.svids().get(i);
            Double value;
            try {
                value = decodeScalar(body.get(i), svid.id());
            } catch (Secs2Exception e) {
                value = null;
            }
            if (value == null) {
                this.collectorDecodeErrors.incrementAndGet();
                continue;
            }
            Deque<double[]> queue =
                    this.collectorData.computeIfAbsent(svid.id(), key -> new ArrayDeque<>());
            synchronized (queue) {
                queue.addLast(new double[] {now, value});
                while (queue.size() > MAX_SAMPLES_PER_SVID) {
                    queue.pollFirst();
                }
            }
            this.collectorSamples.incrementAndGet();
        }
    }

    /**
     * 宽容解码：目录里声明过的类型优先，未声明按应答项格式码探测
     * （双精度 → 整型 → 单字节 → 布尔；嵌套 L 返回 null 记解码错误）。
     */
    private Double decodeScalar(Secs2 item, int svid) throws Secs2Exception {
        if (item.secs2Item() == Secs2Item.LIST || item.isEmpty()) {
            return null;
        }
        CatalogEntry entry = this.variableCatalog.get(svid);
        String declared = entry == null ? "" : entry.declaredType();
        if (!declared.isEmpty()) {
            return switch (declared.toUpperCase(java.util.Locale.ROOT)) {
                case "U1", "U2", "U4", "U8", "I1", "I2", "I4", "I8" -> (double) item.getInt(0);
                case "F4", "F8" -> item.getDouble(0);
                case "BOOLEAN" -> item.getBoolean(0) ? 1.0 : 0.0;
                default -> probeScalar(item);
            };
        }
        return probeScalar(item);
    }

    private static Double probeScalar(Secs2 item) {
        try {
            return item.getDouble(0);
        } catch (Secs2Exception ignored) {
            /* 尝试下一种 */
        }
        try {
            return (double) item.getInt(0);
        } catch (Secs2Exception ignored) {
            /* 尝试下一种 */
        }
        try {
            return (double) (item.getByte(0) & 0xFF);
        } catch (Secs2Exception ignored) {
            /* 尝试下一种 */
        }
        try {
            return item.getBoolean(0) ? 1.0 : 0.0;
        } catch (Secs2Exception ignored) {
            return null;
        }
    }

    /**
     * 从目标读取变量目录：发 S1F11 W + 空列表，解析 S1F12 的 SVID/名称/单位。
     *
     * <p>目标不支持（S1F0 / 超时 / 异常）时抛 IOException：目录保持原样、
     * 连接不受影响（设计 §3.4.2 的失败处理）。</p>
     *
     * @return 读取到的条目数
     * @throws IOException 未连接或目标未提供变量清单
     */
    public int refreshVariables() throws IOException {
        SecsCommunicator comm = this.communicator;
        if (comm == null || !this.open.get()) {
            throw new IOException("主机未连接：先连接目标再读取变量");
        }
        Optional<SecsMessage> reply;
        try {
            reply = comm.send(1, 11, true, Secs2.getBuilder().list());
        } catch (com.shimizukenta.secs.SecsWaitReplyMessageException e) {
            throw new IOException("目标未响应 S1F11（T3 超时），请手动填写变量");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取变量被中断", e);
        } catch (com.shimizukenta.secs.SecsException e) {
            throw new IOException("S1F11 发送失败: " + e.getMessage(), e);
        }
        if (reply.isEmpty()) {
            throw new IOException("目标未提供变量清单（S1F0），请手动填写");
        }
        Secs2 body = reply.get().secs2();
        if (body.secs2Item() != Secs2Item.LIST || body.isEmpty()) {
            throw new IOException("目标返回的 S1F12 为空，请手动填写");
        }

        Map<Integer, CatalogEntry> read = new ConcurrentHashMap<>();
        for (int i = 0; i < body.size(); i++) {
            String svName;
            String unit;
            Secs2 item;
            try {
                item = body.get(i);
            } catch (com.shimizukenta.secs.secs2.Secs2Exception e) {
                continue;
            }
            if (item.secs2Item() != Secs2Item.LIST || item.size() < 3) {
                continue;
            }
            int svid;
            try {
                svid = Secs2Read.asInt(item.get(0));
                svName = safeAscii(item.get(1));
                unit = safeAscii(item.get(2));
            } catch (com.shimizukenta.secs.secs2.Secs2Exception | RuntimeException e) {
                continue;
            }
            CatalogEntry previous = this.variableCatalog.get(svid);
            /* 自动获取只覆盖名称与单位，不覆盖使用者已声明的类型 */
            read.put(svid, new CatalogEntry(svName, unit,
                    previous == null ? "" : previous.declaredType()));
        }
        if (read.isEmpty()) {
            throw new IOException("目标返回的变量清单为空，请手动填写");
        }
        this.variableCatalog.clear();
        this.variableCatalog.putAll(read);
        Log.info("主机 " + this.hostId + " 变量目录已读取 " + read.size() + " 项（类型仍需声明）");
        return read.size();
    }

    private static String safeAscii(Secs2 item) {
        try {
            return item.getAscii();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 采集器状态与数据。
     *
     * @return JSON（running/config/series...;未配置时 configured=false）
     */
    public String collectorJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        CollectorConfig config = this.collectorConfig;

        if (config == null) {
            return b.object(
                    b.pair("configured", false),
                    b.pair("running", false)).toJson();
        }

        List<JsonHub> svids = new ArrayList<>();
        List<JsonHub> series = new ArrayList<>();
        boolean truncated = false;
        for (CollectorSvid svid : config.svids()) {
            svids.add(b.object(
                    b.pair("id", svid.id()),
                    b.pair("name", svid.name()),
                    b.pair("unit", svid.unit())));

            Deque<double[]> queue = this.collectorData.get(svid.id());
            List<JsonHub> ts = new ArrayList<>();
            List<JsonHub> vs = new ArrayList<>();
            if (queue != null) {
                synchronized (queue) {
                    int size = queue.size();
                    /* 超过返回点数就等间隔抽稀：保留首尾附近的可读性，不把 3600 点全塞进 JSON */
                    int stride = size <= MAX_JSON_POINTS_PER_SVID
                            ? 1
                            : (int) Math.ceil((double) size / MAX_JSON_POINTS_PER_SVID);
                    if (stride > 1) {
                        truncated = true;
                    }
                    int index = 0;
                    for (double[] point : queue) {
                        /* 末点尽量保留，但不能因此多出第 721 个点 */
                        if (index % stride == 0
                                || (index == size - 1 && ts.size() < MAX_JSON_POINTS_PER_SVID)) {
                            ts.add(b.number((long) point[0]));
                            vs.add(b.number(point[1]));
                        }
                        index++;
                    }
                }
            }
            series.add(b.object(
                    b.pair("svid", svid.id()),
                    b.pair("name", svid.name()),
                    b.pair("unit", svid.unit()),
                    b.pair("t", b.array(ts)),
                    b.pair("v", b.array(vs))));
        }

        /* 变量目录里声明过但已移除的 SVID 标 stale(数据停止追加) */
        List<JsonHub> stale = new ArrayList<>();
        if (!this.variableCatalog.isEmpty()) {
            for (CollectorSvid svid : config.svids()) {
                if (!this.variableCatalog.containsKey(svid.id())) {
                    stale.add(b.number(svid.id()));
                }
            }
        }

        return b.object(
                b.pair("configured", true),
                b.pair("running", this.collectorRunning),
                b.pair("startCeid", config.startCeid()),
                b.pair("stopCeid", config.stopCeid()),
                b.pair("intervalMs", config.intervalMs()),
                b.pair("svids", b.array(svids)),
                b.pair("startedAt", this.collectorStartedAt),
                b.pair("stoppedAt", this.collectorStoppedAt),
                b.pair("samples", this.collectorSamples.get()),
                b.pair("decodeErrors", this.collectorDecodeErrors.get()),
                b.pair("lastRttMs", this.collectorLastRttMs),
                b.pair("truncated", truncated),
                b.pair("staleSvids", b.array(stale)),
                b.pair("series", b.array(series)))
                .toJson();
    }

    /* ===== SML 发送(原有能力) ===== */

    /**
     * 以主机角色发送一条 SML。
     *
     * @param sml SML 文本
     * @return 与设备 sendSml 同形状的结果 JSON
     * @throws IOException 发送失败
     */
    public String sendSml(String sml) throws IOException {
        SecsCommunicator comm = this.communicator;
        if (comm == null || !this.open.get()) {
            throw new IllegalStateException("主机模拟器未连接");
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
        } catch (com.shimizukenta.secs.SecsWaitReplyMessageException e) {
            reply = Optional.empty();
            waitTimedOut = true;
        } catch (com.shimizukenta.secs.SecsSendMessageException e) {
            /* 发送失败常见于设备重载后端口重绑：标记断开，让调用方可重开 */
            this.lastError = e.toString();
            this.open.set(false);
            throw new IOException("发送失败（连接可能已断，可重新打开主机模拟）: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("发送被中断", e);
        } catch (com.shimizukenta.secs.SecsException e) {
            throw new IOException("发送失败: " + e.getMessage(), e);
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;

        String replySml = reply.map(value -> SmlRenderer.render(value.secs2())).orElse(null);
        String sentSml = message.body().isEmpty() ? "" : SmlRenderer.render(message.body());
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

    /**
     * 销毁语义（注册表移除实例时用）；一般断开用 {@link #disconnect()}。
     */
    public void destroy() {
        this.disconnect();
        this.cancelRetry();
        ScheduledExecutorService collector = this.collectorExecutor;
        if (collector != null) {
            collector.shutdownNow();
        }
    }

    /** close() 保留为 destroy 的同义别名（旧调用方）。 */
    public void close() {
        this.destroy();
    }

    /**
     * 连接状态。
     *
     * @return 已连接返回 {@code true}
     */
    public boolean isOpen() {
        return this.open.get() && this.communicator != null;
    }

    /**
     * 连接目标。
     *
     * @return 目标
     */
    public HsmsConnectionTarget target() {
        return this.target;
    }

    /**
     * 最近一次错误（连接/发送）；空串表示没有。
     *
     * @return 错误描述
     */
    public String lastError() {
        return this.lastError;
    }

    /**
     * 状态 JSON（3.0 形状；session/mode/remote 为 v1 兼容字段）。
     */
    public String stateJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        return b.object(
                b.pair("id", this.hostId),
                b.pair("name", this.name),
                b.pair("phase", this.phase.name()),
                b.pair("open", this.isOpen()),
                b.pair("target", this.target.bindHost() + ":" + this.target.port()),
                b.pair("port", this.target.port()),
                b.pair("deviceId", this.target.deviceId()),
                b.pair("autoReconnect", this.autoReconnect),
                b.pair("retryCount", this.retryCount.get()),
                b.pair("nextRetryAt", this.nextRetryAt),
                b.pair("settingRevision", this.settingRevision.get()),
                b.pair("appliedRevision", this.appliedRevision.get()),
                /* v1 兼容字段（3.0 无本机/远程之分）：旧前端未升级期间不崩 */
                b.pair("session", ""),
                b.pair("mode", "target"),
                b.pair("remote", true),
                b.pair("lastError", this.lastError))
                .toJson();
    }

    /** {@code setCollectorConfig} 里的轻量 JSON 读取（避免与 secs-app JsonAccess 依赖纠缠）。 */
    private static final class JsonAccessHelper {

        private JsonAccessHelper() {
        }

        static int intField(JsonHub obj, String key) {
            if (!obj.containsKey(key)) {
                throw new IllegalArgumentException("缺少必填字段: " + key);
            }
            return obj.get(key).optionalInt()
                    .orElseThrow(() -> new IllegalArgumentException(key + " 应为整数"));
        }

        static long longField(JsonHub obj, String key, long fallback) {
            if (!obj.containsKey(key)) {
                return fallback;
            }
            return obj.get(key).optionalLong()
                    .orElseThrow(() -> new IllegalArgumentException(key + " 应为整数"));
        }

        static String stringField(JsonHub obj, String key) {
            if (!obj.containsKey(key)) {
                return "";
            }
            return obj.get(key).optionalString().orElse("");
        }

        static JsonHub arrayField(JsonHub obj, String key) {
            if (!obj.containsKey(key)) {
                throw new IllegalArgumentException("缺少必填字段: " + key);
            }
            JsonHub value = obj.get(key);
            if (value == null || !value.isArray()) {
                throw new IllegalArgumentException(key + " 应为数组");
            }
            return value;
        }
    }
}
