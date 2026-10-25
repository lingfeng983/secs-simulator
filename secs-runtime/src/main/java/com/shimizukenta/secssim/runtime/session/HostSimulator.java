package com.shimizukenta.secssim.runtime.session;

import com.shimizukenta.secssim.app.session.HsmsConnectionTarget;
import com.shimizukenta.secssim.app.session.SessionRuntime;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.runtime.sml.SmlParser;
import com.shimizukenta.secssim.runtime.sml.SmlParseException;
import com.shimizukenta.secssim.runtime.sml.SmlRenderer;
import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secssim.app.log.Log;
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

    /** 每 SVID 保留的采样点数（3600 = 1 小时 @1Hz）。 */
    static final int MAX_SAMPLES_PER_SVID = 3600;

    private final String sessionId;
    private final HsmsConnectionTarget target;
    /** 本机模式才有:用于 S1F4 按声明类型解码与采集 SVID 存在性校验。 */
    private final ResolvedEquipment localModel;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private volatile SecsCommunicator communicator;
    private volatile String lastError = "";

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

    private HostSimulator(String sessionId, HsmsConnectionTarget target, ResolvedEquipment localModel) {
        this.sessionId = sessionId;
        this.target = target;
        this.localModel = localModel;
    }

    /**
     * 连接到设备会话（本机默认目标）。
     *
     * @param runtime 目标会话
     * @return 模拟器
     * @throws IOException         连接失败
     * @throws IllegalStateException 已有主机占用设备
     */
    public static HostSimulator open(SessionRuntime runtime) throws IOException {
        return open(runtime, null);
    }

    /**
     * 连接到设备会话或远程 SECS（schema v6）。
     *
     * @param runtime 会话（只作为工具实例的宿主与本地模型来源）
     * @param override 远程目标;{@code null} 连本机（现状行为）
     * @return 模拟器
     * @throws IOException 连接失败
     */
    public static HostSimulator open(SessionRuntime runtime, HsmsConnectionTarget override) throws IOException {
        boolean remote = override != null;
        HsmsConnectionTarget target = remote ? override : runtime.hostTarget();
        if (target == null) {
            throw new IOException("会话 " + runtime.id() + " 不支持主机模拟（无连接目标）");
        }

        ResolvedEquipment model = remote ? null : localModelOf(runtime);
        HostSimulator sim = new HostSimulator(runtime.id(), target, model);
        sim.connect();
        sim.open.set(true);
        Log.info("主机模拟器已" + (remote ? "远程" : "") + "连接会话 " + runtime.id()
                + "（" + target.bindHost() + ":" + target.port() + "）");
        return sim;
    }

    private static ResolvedEquipment localModelOf(SessionRuntime runtime) {
        return runtime instanceof HsmsSessionRuntime hsms ? hsms.model() : null;
    }

    private void connect() throws IOException {
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.protocol(HsmsSsProtocol.ACTIVE);
        config.socketAddress(new InetSocketAddress(this.target.bindHost(), this.target.port()));
        config.deviceId(this.target.deviceId());
        config.isEquip(false);

        SecsCommunicator comm = HsmsSsCommunicator.open(config);
        comm.addSecsMessageReceiveListener(this::onPrimary);
        this.communicator = comm;
        try {
            /* 连接与 SELECT 是异步的：不等通信建立就发会撞 NotConnected */
            comm.openAndWaitUntilCommunicating();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            comm.close();
            throw new IOException("主机模拟器等待通信建立被中断", e);
        }
    }

    /**
     * 断线重连（保留原目标,远程模式同样适用）。
     *
     * @throws IOException 重连失败
     */
    public void reconnect() throws IOException {
        SecsCommunicator old = this.communicator;
        if (old != null) {
            try {
                old.close();
            } catch (IOException e) {
                Log.warn("主机模拟器旧连接关闭失败（会话 " + this.sessionId + "）: " + e);
            }
        }
        this.connect();
        this.open.set(true);
        this.lastError = "";
    }

    /**
     * 设备发来的 W 位 primary 的自动应答，让设备侧流程不卡 T3；
     * S6F11 同时喂给采集器的 CEID 触发判定。
     */
    private void onPrimary(SecsMessage message) {
        int stream = message.getStream();
        int function = message.getFunction();
        try {
            if (message.wbit() && stream == 6 && (function == 11 || function == 1)) {
                this.communicator.send(message, 6, function == 11 ? 12 : 2, false,
                        Secs2.getBuilder().binary(new byte[] {0x00}));
            }
            if (stream == 6 && function == 11) {
                this.onS6f11(message);
            }
        } catch (Exception e) {
            Log.warn("主机模拟器自动应答失败（会话 " + this.sessionId + "）: " + e);
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

        /* 本机模式:SVID 必须在设备模型里;远程模式没有 catalog,不做存在性校验 */
        if (this.localModel != null) {
            for (CollectorSvid svid : svids) {
                if (!this.localModel.svids().containsKey(svid.id())) {
                    throw new IllegalArgumentException("采集 SVID " + svid.id() + " 不在设备配置里");
                }
            }
        }

        this.collectorStop("配置更新");
        this.collectorConfig = config;
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
            Thread thread = new Thread(runnable, "hostsim-collector-" + this.sessionId);
            thread.setDaemon(true);
            return thread;
        });
        this.collectorExecutor = executor;
        executor.scheduleAtFixedRate(this::collectorTick,
                0L, config.intervalMs(), TimeUnit.MILLISECONDS);
        Log.info("主机模拟采集器已启动（会话 " + this.sessionId + "，" + cause + "）");
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
        Log.info("主机模拟采集器已停止（会话 " + this.sessionId + "，" + cause + "）");
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

    /** S1F4 应答按位置解码;远程模式按应答项自带格式码,不依赖本地类型声明。 */
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
                value = decodeScalar(body.get(i));
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

    /** 宽容解码:双精度 → 整型 → 单字节 → 布尔;嵌套 L 返回 null（记解码错误）。 */
    private static Double decodeScalar(Secs2 item) {
        if (item.secs2Item() == Secs2Item.LIST || item.isEmpty()) {
            return null;
        }
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
                    for (double[] point : queue) {
                        ts.add(b.number((long) point[0]));
                        vs.add(b.number(point[1]));
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

        /* 本机模式下热重载删掉的 SVID 标 stale(数据停止追加) */
        List<JsonHub> stale = new ArrayList<>();
        if (this.localModel != null) {
            for (CollectorSvid svid : config.svids()) {
                if (!this.localModel.svids().containsKey(svid.id())) {
                    stale.add(b.number(svid.id()));
                }
            }
        }

        return b.object(
                b.pair("configured", true),
                b.pair("running", this.collectorRunning),
                b.pair("mode", this.localModel == null ? "remote" : "local"),
                b.pair("startCeid", config.startCeid()),
                b.pair("stopCeid", config.stopCeid()),
                b.pair("intervalMs", config.intervalMs()),
                b.pair("svids", b.array(svids)),
                b.pair("startedAt", this.collectorStartedAt),
                b.pair("stoppedAt", this.collectorStoppedAt),
                b.pair("samples", this.collectorSamples.get()),
                b.pair("decodeErrors", this.collectorDecodeErrors.get()),
                b.pair("lastRttMs", this.collectorLastRttMs),
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
     * 关闭连接。
     */
    public void close() {
        this.collectorStop("主机模拟关闭");
        if (this.open.compareAndSet(true, false)) {
            SecsCommunicator comm = this.communicator;
            if (comm != null) {
                try {
                    comm.close();
                } catch (IOException e) {
                    Log.warn("主机模拟器关闭失败（会话 " + this.sessionId + "）: " + e);
                }
            }
            this.communicator = null;
            Log.info("主机模拟器已断开会话 " + this.sessionId);
        }
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
     * 是否远程模式（schema v6）。
     *
     * @return 远程返回 {@code true}
     */
    public boolean isRemote() {
        return this.localModel == null;
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
     * 状态 JSON。
     *
     * @return {@code {"open":true,"target":"127.0.0.1:5000","mode":"local","remote":false,...}}
     */
    public String stateJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        return b.object(
                b.pair("open", this.isOpen()),
                b.pair("target", this.target.bindHost() + ":" + this.target.port()),
                b.pair("port", this.target.port()),
                b.pair("deviceId", this.target.deviceId()),
                b.pair("mode", this.isRemote() ? "remote" : "local"),
                b.pair("remote", this.isRemote()),
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
