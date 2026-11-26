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
import com.shimizukenta.secssim.runtime.session.HsmsSessionRuntime;
import com.shimizukenta.secssim.runtime.session.Secs2Read;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.hsmsss.HsmsSsProtocol;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Item;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>连接目标（schema v7）：默认是同进程的设备会话（{@code hostTarget()}）；
 * 也可以指定远程 SECS（host/port/deviceId 手填）——远程模式没有本地
 * catalog，SVID/CEID 相关的配置全部由调用方手填，采集值按应答项
 * 自带的格式码解码（零元数据可画图）。</p>
 *
 * <p>S1F3 采集器：收到 startCeid 的 S6F11 开始按 intervalMs 轮询
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

    /** S1F3 采集器组：一台主机可以同时挂多份（按采集计划分开管）。 */
    private final HostCollectors collectors;

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
        this.collectors = new HostCollectors(this, hostId);
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
        /* 采集配置随实例持久化：进程重启 / 主机重连后启停 CEID 判定必须还在，
           否则用户「下发配置」后一重连就变成「收到 CEID 但什么都没发生」。
           新旧两种格式都认（老库是单份配置，新库是 collectors 数组）。 */
        String collectorJson = view.collectorJson();
        if (collectorJson != null
                && (collectorJson.contains("startCeid") || collectorJson.contains("collectors"))) {
            try {
                sim.restoreCollectorConfig(collectorJson);
            } catch (RuntimeException e) {
                Log.warn("主机采集配置恢复失败（" + view.id() + "）: " + e.getMessage());
            }
        }
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

    /**
     * 只改名称：不进设置版本、不要求重连。
     *
     * <p>名称不参与建链（目标地址 / 端口 / Device ID 才参与），保存后立刻生效。
     * 以前走 {@link #saveSettings} 会把 settingRevision +1，界面就一直挂着
     * 「设置待生效」，而点「使设置生效」只是白白断链重连一次。</p>
     *
     * @param name 新名称（空白忽略）
     */
    public void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    /** 已生效的设置版本（与 {@link #settingRevision()} 相等 = 没有待生效改动）。 */
    public long appliedRevision() {
        return this.appliedRevision.get();
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
        this.collectors.recoverAfterConnect();
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
        this.collectors.stopAll("手动断开");
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
     *
     * <p>采集器的 CEID 触发判定也挂在这条 pass-through 上：它在 socket 读线程
     * 收到 DATA 帧时立刻触发，早于 replyMgr / 接收队列，也不依赖自动应答是否成功。
     * 以前挂在 {@link #onPrimary} 的末尾，一旦前面的 S6F12 自动应答抛异常
     * （重连窗口里 communicator 被置空、或对端已断开），整段 try 就跳到 catch，
     * 「收到启动 CEID」与「开始采集」之间会静默断开。</p>
     */
    private void installPassThrough(SecsCommunicator comm) {
        comm.addReceiveMessagePassThroughListener(message -> {
            if (message.getStream() == 6 && message.getFunction() == 11) {
                this.collectors.onS6f11(message);
            }
        });
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
            /* CEID 判定已在 installPassThrough 里做，不能再挂在这里：
               自动应答失败会连累它一起被跳过。 */
        } catch (Exception e) {
            Log.warn("主机自动应答失败（" + this.hostId + "）: " + e);
        }
    }


    /* ===== S1F3 采集器 ===== */


    /**
     * 下发一份采集配置（可在连接前后随时调用；只影响这一份，其它计划照常采）。
     *
     * <p>待命（armed）状态下不轮询，收到 {@code startCeid} 的 S6F11 才开始采集。</p>
     *
     * @param json {@code {"planId":"p1","startCeid":101,"stopCeid":102,"intervalMs":1000,"svids":[{"id":401,"name":"T","unit":"C"}]}}；
     *             {@code planId} 可省，省略时落到默认计划（老客户端的单份配置）
     * @throws IllegalArgumentException 形状或取值非法
     */
    public void setCollectorConfig(String json) {
        JsonHub root = parseCollectorJson(json);
        String planId = JsonFieldReader.stringField(root, "planId");
        this.applyCollectorConfig(planId, root);
    }

    /** 解析并校验一份配置，落到指定计划（planId 空串 = 默认计划）。 */
    private void applyCollectorConfig(String planId, JsonHub root) {
        int startCeid = JsonFieldReader.intField(root, "startCeid");
        int stopCeid = JsonFieldReader.intField(root, "stopCeid");
        long intervalMs = JsonFieldReader.longField(root, "intervalMs", 1_000L);
        JsonHub svidsJson = JsonFieldReader.arrayField(root, "svids");

        List<HostCollectorTypes.CollectorSvid> svids = new ArrayList<>();
        for (int i = 0; i < svidsJson.length(); i++) {
            JsonHub item = svidsJson.get(i);
            int id = JsonFieldReader.intField(item, "id");
            svids.add(new HostCollectorTypes.CollectorSvid(id,
                    JsonFieldReader.stringField(item, "name"),
                    JsonFieldReader.stringField(item, "unit")));
        }

        HostCollectorTypes.CollectorConfig config = new HostCollectorTypes.CollectorConfig(startCeid, stopCeid, intervalMs, svids);

        /* 变量目录非空时做存在性校验（目录为空 = 手动填写形态，不校验） */
        if (!this.variableCatalog.isEmpty()) {
            for (HostCollectorTypes.CollectorSvid svid : svids) {
                if (!this.variableCatalog.containsKey(svid.id())) {
                    throw new IllegalArgumentException("采集 SVID " + svid.id() + " 不在变量目录里（可先从目标读取变量）");
                }
            }
        }

        /* 「停旧采集 + 换配置」在采集器内部整体完成：启动由链路线程在 S6F11
           里触发，两步之间被唤醒会用旧参数启动，之后不再重读。 */
        this.collectors.apply(planId, config);
    }

    private static JsonHub parseCollectorJson(String json) {
        try {
            return JsonHub.fromJson(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("采集配置不是合法 JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 从持久行恢复采集配置：兼容两种格式。
     *
     * <ul>
     *   <li>新格式 {@code {"collectors":[{planId,startCeid,...}, ...]}}：逐份恢复；</li>
     *   <li>老格式（根上直接是 startCeid/stopCeid/svids）：当成默认计划的一份。</li>
     * </ul>
     */
    private void restoreCollectorConfig(String json) {
        JsonHub root = parseCollectorJson(json);
        JsonHub list = root.get("collectors");
        if (list != null && list.isArray()) {
            for (JsonHub item : list.values()) {
                String planId = JsonFieldReader.stringField(item, "planId");
                this.applyCollectorConfig(planId, item);
            }
            return;
        }
        if (root.get("startCeid") != null) {
            this.applyCollectorConfig("", root);
        }
    }

    /** 全部计划的配置（持久化用，不含运行态）。 */
    public String collectorConfigJson() {
        return this.collectors.configsJson();
    }

    /** 手动启动采集（全部已配置的计划）：配置了启动 CEID 的只是武装待命。 */
    public void collectorStartManual() {
        this.collectors.start(null);
    }

    /** 手动启动某一份采集计划。 */
    public void collectorStartManual(String planId) {
        this.collectors.start(planId);
    }

    /** 手动停止全部采集：退出待命（armed=false），之后的启动 CEID 也不再触发轮询。 */
    public void collectorStopManual() {
        this.collectors.stop(null);
    }

    /** 手动停止某一份采集计划。 */
    public void collectorStopManual(String planId) {
        this.collectors.stop(planId);
    }

    /**
     * 移除某一份采集计划的采集器（先停，再从配置里删掉）。
     *
     * @return 这份采集器原本是否存在
     */
    public boolean collectorRemove(String planId) {
        return this.collectors.remove(planId);
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
        return this.collectors.toJson();
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
        this.collectors.shutdown();
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

    /* 采集器辅助类的包内状态入口：只收窄可见性，不扩大 public 面。 */
    SecsCommunicator currentCommunicator() {
        return this.communicator;
    }

    boolean linkOpen() {
        return this.open.get();
    }

    void recordError(String message) {
        this.lastError = message;
    }

    String declaredTypeOf(int svid) {
        CatalogEntry entry = this.variableCatalog.get(svid);
        return entry == null ? "" : entry.declaredType();
    }

    boolean catalogContainsSvid(int svid) {
        return this.variableCatalog.containsKey(svid);
    }

    boolean catalogEmpty() {
        return this.variableCatalog.isEmpty();
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

}
