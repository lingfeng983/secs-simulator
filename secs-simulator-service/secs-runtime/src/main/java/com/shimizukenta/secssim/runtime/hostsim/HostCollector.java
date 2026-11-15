package com.shimizukenta.secssim.runtime.hostsim;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;
import com.shimizukenta.secs.secs2.Secs2Item;
import com.shimizukenta.secssim.app.log.Log;
import com.shimizukenta.secssim.runtime.session.Secs2Read;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个主机实例的 S1F3 采集器：armed/running 状态机、轮询、应答解码与 JSON 输出。
 *
 * <p>两个状态互斥：{@code armed} 是「已待命、等 startCeid 的 S6F11」，
 * {@code running} 才真正按 intervalMs 发 S1F3。配置下发与手动启动默认只武装，
 * 只有收到启动 CEID（或配置里没有启动 CEID）才进入 running。</p>
 *
 * <p>启停互斥统一用本类实例的监视器：{@code applyConfig} 的「停旧 + 换配置」
 * 必须整体完成，否则链路线程在两步之间触发 start 会用旧参数启动。</p>
 */
final class HostCollector {

    private final HostSimulator owner;
    private final String hostId;
    /** 采集计划标识（界面方案库的 id）：一台主机可以同时挂多份，靠它区分。 */
    private final String planId;

    private volatile HostSimulator.CollectorConfig config;
    private volatile boolean armed;
    private volatile boolean running;
    private volatile long startedAt;
    private volatile long lastRttMs;
    private volatile long stoppedAt;
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong decodeErrors = new AtomicLong();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final Map<Integer, Deque<double[]>> data = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService executor;

    HostCollector(HostSimulator owner, String hostId, String planId) {
        this.owner = owner;
        this.hostId = hostId;
        this.planId = planId == null || planId.isBlank() ? HostCollectors.DEFAULT_PLAN_ID : planId;
    }

    /** 采集计划标识。 */
    String planId() {
        return this.planId;
    }

    /** 当前是否在按周期轮询。 */
    boolean isRunning() {
        return this.running;
    }

    /** 当前是否在待命（等启动 CEID）。 */
    boolean isArmed() {
        return this.armed;
    }

    /** 已采样本点数（按 SVID 累加）。 */
    long sampleCount() {
        return this.samples.get();
    }

    /** 解码错误数。 */
    long decodeErrorCount() {
        return this.decodeErrors.get();
    }

    /** 最近一次 S1F3 往返耗时（ms）。 */
    long lastRttMillis() {
        return this.lastRttMs;
    }

    /** 当前配置；未下发过返回 null。 */
    HostSimulator.CollectorConfig currentConfig() {
        return this.config;
    }

    /**
     * 停旧采集并换配置；换掉之后不在新列表里的 SVID 队列没有消费者也没有出口，
     * 必须一并丢弃，否则反复改配置会让内存只增不减。
     */
    synchronized void applyConfig(HostSimulator.CollectorConfig next) {
        this.standby("配置更新");
        this.config = next;

        Set<Integer> keep = new HashSet<>();
        for (HostSimulator.CollectorSvid svid : next.svids()) {
            keep.add(svid.id());
        }
        this.data.keySet().retainAll(keep);
    }

    /** 连接成功后只恢复「待命」：轮询仍由 startCeid 的 S6F11 触发（配置随实例持久化）。 */
    void recoverAfterConnect() {
        if (this.config == null) {
            return;
        }
        this.standby("连接恢复");
    }

    /**
     * 手动启动：
     * <ul>
     *   <li>已在采集 → 什么都不做（幂等）；</li>
     *   <li>配置了启动 CEID → 只是武装待命，等目标发 S6F11；</li>
     *   <li>没有启动 CEID（{@code startCeid<=0}）→ 直接开始轮询。</li>
     * </ul>
     * 链路未建立时不报错，状态保持 armed=false / running=false。
     */
    void startManual() {
        HostSimulator.CollectorConfig current = this.config;
        if (current == null) {
            throw new IllegalStateException("采集器还没有配置");
        }
        /*
         * 已经在跑：手动「启动」必须幂等，绝不能把正在跑的轮询按回待命。
         * 界面收到启动 CEID 的 S6F11 时会补一次启动（它每 2 秒才刷新一次采集器状态，
         * 那一刻可能还以为没开始采）。老实现无条件走 standby → stop() 掉执行器，
         * 现场看到的就是"设备发了启动 CEID，只收到一两条 S1F3 之后就没数据了"：
         * 采集器挂在 armed 上，而那个 CEID 通常只来一次。
         */
        if (this.running) {
            return;
        }
        if (current.startCeid() <= 0) {
            this.start("手动");
        } else if (this.owner.linkOpen()) {
            this.standby("手动");
        } else {
            /* 链路未建立：不报错，结果状态为 armed=false / running=false */
            this.disarm("手动");
        }
    }

    /** 手动停止：退出待命，之后的启动 CEID 也不再触发轮询。 */
    void stopManual() {
        this.disarm("手动");
    }

    /** 进入待命：停掉轮询执行器，armed=true / running=false。 */
    private synchronized void standby(String cause) {
        this.stop(cause);
        this.running = false;
        this.armed = true;
    }

    /** 解除待命：armed=false / running=false。 */
    private synchronized void disarm(String cause) {
        this.stop(cause);
        this.running = false;
        this.armed = false;
    }

    private synchronized void start(String cause) {
        HostSimulator.CollectorConfig current = this.config;
        if (current == null || this.running || !this.owner.linkOpen()) {
            return;
        }
        this.data.clear();
        this.samples.set(0);
        this.decodeErrors.set(0);
        this.running = true;
        this.armed = false;
        this.startedAt = System.currentTimeMillis();
        this.stoppedAt = 0;

        ScheduledExecutorService next = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hostsim-collector-" + this.hostId + "-" + this.planId);
            thread.setDaemon(true);
            return thread;
        });
        this.executor = next;
        next.scheduleAtFixedRate(this::tick, 0L, current.intervalMs(), TimeUnit.MILLISECONDS);
        Log.info("主机采集器已启动（" + this.hostId + " / " + this.planId + "，" + cause + "）");
    }

    synchronized void stop(String cause) {
        if (!this.running) {
            return;
        }
        this.running = false;
        this.stoppedAt = System.currentTimeMillis();
        ScheduledExecutorService current = this.executor;
        if (current != null) {
            current.shutdownNow();
            this.executor = null;
        }
        Log.info("主机采集器已停止（" + this.hostId + " / " + this.planId + "，" + cause + "）");
    }

    /** 销毁实例时的收尾：执行器已经停掉的状态下再兜一次底。 */
    void shutdown() {
        ScheduledExecutorService current = this.executor;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /** 一轮 S1F3 轮询：上一轮没回来就跳过本拍，避免在 T3 上叠队列。 */
    private void tick() {
        if (!this.running || !this.inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            HostSimulator.CollectorConfig current = this.config;
            SecsCommunicator comm = this.owner.currentCommunicator();
            if (current == null || comm == null) {
                return;
            }

            List<Secs2> items = new ArrayList<>();
            for (HostSimulator.CollectorSvid svid : current.svids()) {
                items.add(Secs2.getBuilder().uint4(svid.id()));
            }
            Secs2 body = Secs2.getBuilder().list(items);

            long startedAt = System.currentTimeMillis();
            Optional<SecsMessage> reply;
            try {
                reply = comm.send(1, 3, true, body);
            } catch (Exception e) {
                this.owner.recordError("采集 S1F3 发送失败: " + e.getMessage());
                return;
            }
            this.lastRttMs = System.currentTimeMillis() - startedAt;
            if (reply.isEmpty()) {
                return;
            }

            this.decodeS1f4(current, reply.get());
        } finally {
            this.inFlight.set(false);
        }
    }

    /** S1F4 应答按位置解码；目录里声明过的类型优先，未声明按应答项格式码探测。 */
    private void decodeS1f4(HostSimulator.CollectorConfig current, SecsMessage reply) {
        Secs2 body = reply.secs2();
        long now = System.currentTimeMillis();

        if (body.secs2Item() != Secs2Item.LIST || body.size() != current.svids().size()) {
            this.decodeErrors.incrementAndGet();
            return;
        }

        for (int i = 0; i < current.svids().size(); i++) {
            HostSimulator.CollectorSvid svid = current.svids().get(i);
            Double value;
            try {
                value = decodeScalar(body.get(i), svid.id());
            } catch (Secs2Exception e) {
                value = null;
            }
            if (value == null) {
                this.decodeErrors.incrementAndGet();
                continue;
            }
            Deque<double[]> queue =
                    this.data.computeIfAbsent(svid.id(), key -> new ArrayDeque<>());
            synchronized (queue) {
                queue.addLast(new double[] {now, value});
                while (queue.size() > HostSimulator.MAX_SAMPLES_PER_SVID) {
                    queue.pollFirst();
                }
            }
            this.samples.incrementAndGet();
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
        String declared = this.owner.declaredTypeOf(svid);
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
     * 从 S6F11 body 取 CEID 并比对采集器启停（与本地/远程无关）。
     *
     * <p>标准是 L[3]{DATAID, CEID, REPORTS}，第 2 项是 CEID；部分设备发
     * L[2]{CEID, REPORTS}，这时第 2 项是列表、解析失败，退回第 1 项。
     * 这里不用 {@code open} 做前置门槛：真正的可启动检查在 start 里，
     * 链路正在建立时 open 可能还没置位，但帧已经到了，早退会白白丢掉这次触发。</p>
     */
    void onS6f11(SecsMessage message) {
        HostSimulator.CollectorConfig current = this.config;
        if (current == null) {
            return;
        }
        int ceid;
        try {
            Secs2 body = message.secs2();
            if (body.secs2Item() != Secs2Item.LIST || body.size() < 1) {
                return;
            }
            ceid = readCeid(body);
        } catch (Exception e) {
            Log.warn("主机解析 S6F11 CEID 失败（" + this.hostId + "）: " + e);
            return;
        }
        if (ceid < 0) {
            return;
        }

        if (ceid == current.startCeid() && (current.startCeid() <= 0 || (this.armed && !this.running))) {
            /* armed 保证重复的启动报文不会重复启动：一旦 running，armed 已被清掉 */
            Log.info("主机收到启动 CEID " + ceid + "（" + this.hostId + "），触发 S1F3 采集");
            this.start("CEID " + ceid);
        } else if (this.running && ceid == current.stopCeid()) {
            Log.info("主机收到停止 CEID " + ceid + "（" + this.hostId + "），停止 S1F3 采集并回到待命");
            this.standby("CEID " + ceid);
        }
    }

    /** S6F11 body 的 CEID 位置：L[3] 取 index 1，L[2] 依次试 index 1 / 0。 */
    private static int readCeid(Secs2 body) {
        if (body.size() >= 2) {
            try {
                return Secs2Read.asInt(body.get(1));
            } catch (Secs2Exception | RuntimeException e) {
                /* 第 2 项是报告列表：按 L[2]{CEID, REPORTS} 继续试 */
            }
        }
        if (body.size() >= 1) {
            try {
                return Secs2Read.asInt(body.get(0));
            } catch (Secs2Exception | RuntimeException e) {
                return -1;
            }
        }
        return -1;
    }

    /** 采集器状态与数据（未配置时 configured=false）。 */
    JsonHub toJsonHub() {
        JsonHubBuilder b = JsonHub.getBuilder();
        HostSimulator.CollectorConfig current = this.config;

        if (current == null) {
            return b.object(
                    b.pair("planId", this.planId),
                    b.pair("configured", false),
                    b.pair("running", false));
        }

        List<JsonHub> svids = new ArrayList<>();
        List<JsonHub> series = new ArrayList<>();
        boolean truncated = false;
        for (HostSimulator.CollectorSvid svid : current.svids()) {
            svids.add(b.object(
                    b.pair("id", svid.id()),
                    b.pair("name", svid.name()),
                    b.pair("unit", svid.unit())));

            Deque<double[]> queue = this.data.get(svid.id());
            List<JsonHub> ts = new ArrayList<>();
            List<JsonHub> vs = new ArrayList<>();
            if (queue != null) {
                synchronized (queue) {
                    int size = queue.size();
                    /* 超过返回点数就等间隔抽稀：保留首尾附近的可读性，不把 3600 点全塞进 JSON */
                    int stride = size <= HostSimulator.MAX_JSON_POINTS_PER_SVID
                            ? 1
                            : (int) Math.ceil((double) size / HostSimulator.MAX_JSON_POINTS_PER_SVID);
                    if (stride > 1) {
                        truncated = true;
                    }
                    int index = 0;
                    for (double[] point : queue) {
                        /* 末点尽量保留，但不能因此多出第 721 个点 */
                        if (index % stride == 0
                                || (index == size - 1 && ts.size() < HostSimulator.MAX_JSON_POINTS_PER_SVID)) {
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

        /* 变量目录里声明过但已移除的 SVID 标 stale（数据停止追加） */
        List<JsonHub> stale = new ArrayList<>();
        if (!this.owner.catalogEmpty()) {
            for (HostSimulator.CollectorSvid svid : current.svids()) {
                if (!this.owner.catalogContainsSvid(svid.id())) {
                    stale.add(b.number(svid.id()));
                }
            }
        }

        return b.object(
                b.pair("planId", this.planId),
                b.pair("configured", true),
                b.pair("running", this.running),
                b.pair("armed", this.armed),
                b.pair("startCeid", current.startCeid()),
                b.pair("stopCeid", current.stopCeid()),
                b.pair("intervalMs", current.intervalMs()),
                b.pair("svids", b.array(svids)),
                b.pair("startedAt", this.startedAt),
                b.pair("stoppedAt", this.stoppedAt),
                b.pair("samples", this.samples.get()),
                b.pair("decodeErrors", this.decodeErrors.get()),
                b.pair("lastRttMs", this.lastRttMs),
                b.pair("truncated", truncated),
                b.pair("staleSvids", b.array(stale)),
                b.pair("series", b.array(series)));
    }
}
