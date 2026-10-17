package com.shimizukenta.secssim.runtime.session;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secssim.core.compile.ResolvedEquipment;
import com.shimizukenta.secssim.core.compile.ResolvedSvid;
import com.shimizukenta.secssim.core.trace.TracePlan;
import com.shimizukenta.secssim.core.trace.TraceSpec;
import com.shimizukenta.secssim.core.value.ValueSource;

/**
 * 管理 S2F23 发起的 Trace（周期采样）。
 *
 * <p>采样点严格落在 {@code t0 + k × DSPER}，而不是"每次心跳凑一个点"：
 * 后者会让 SMPLN 与实际时间脱钩，主机拿到的时间序列就是歪的。
 * 由于值来源是纯函数，即使一次心跳跨过多个采样点也能逐个补算，
 * 加速播放与卡顿都不会丢样本。
 * </p>
 */
public final class TraceManager {

    /** TIAACK 码（SEMI E5）。 */
    public static final int TIAACK_OK = 0;
    /** 超出单条 Trace 允许的 SVID 数。 */
    public static final int TIAACK_SVID_EXCEEDED = 1;
    /** 已有 Trace 占用，不允许更多。 */
    public static final int TIAACK_TRACES_DENIED = 2;
    /** DSPER 非法。 */
    public static final int TIAACK_INVALID_PERIOD = 3;
    /** SVID 未定义。 */
    public static final int TIAACK_SVID_UNKNOWN = 4;
    /** REPGSZ 非法。 */
    public static final int TIAACK_REPGSZ_INVALID = 5;

    /** 同时允许的 Trace 条数（多数真机是 1）。 */
    public static final int MAX_TRACES = 1;
    /** 单条 Trace 允许的 SVID 数。 */
    public static final int MAX_SVIDS = 10;

    private static final DateTimeFormatter STIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSS", java.util.Locale.ROOT);

    /**
     * 一条待发送的 S6F1。
     *
     * @param trid        Trace ID
     * @param smpln       本条报文中最后一个样本的样本号
     * @param stime       采样时刻（墙钟）
     * @param values      扁平化的值（样本序 × SVID 序）
     * @param sampleCount 本条报文包含的样本数
     * @param svidCount   每个样本的 SVID 数
     */
    public record Frame(int trid, int smpln, String stime, List<Secs2> values, int sampleCount, int svidCount) {
    }

    private final ResolvedEquipment model;
    private final ValueSource values;
    private final Instant wallStart;
    private final ZoneId zone;

    private final Map<Integer, ActiveTrace> traces = new LinkedHashMap<>();

    /**
     * 创建管理器。
     *
     * @param model     已解析设备模型
     * @param values    值来源
     * @param wallStart 墙钟锚点（仿真 t=0 对应的真实时刻）
     */
    public TraceManager(ResolvedEquipment model, ValueSource values, Instant wallStart) {
        this.model = model;
        this.values = values;
        this.wallStart = wallStart;
        this.zone = ZoneId.systemDefault();
    }

    /**
     * 当前活跃的 Trace 数。
     *
     * @return 数量
     */
    public synchronized int activeCount() {
        return this.traces.size();
    }

    /**
     * 停止全部 Trace。
     */
    public synchronized void stopAll() {
        this.traces.clear();
    }

    /**
     * 处理一次 S2F23（已解析）。
     *
     * @param trid    TRID
     * @param svids   SVID 列表
     * @param dsperMs 采样周期
     * @param totsmp  总样本数（0 表示终止）
     * @param repgsz  每报文样本数
     * @param nowMs   当前仿真时刻（作为 t0）
     * @return TIAACK 码
     */
    public synchronized int initialize(int trid, List<Integer> svids, long dsperMs,
                                       int totsmp, int repgsz, long nowMs) {
        if (totsmp == 0) {
            this.traces.remove(trid);
            return TIAACK_OK;
        }
        if (totsmp < 0 || repgsz < 1 || repgsz > totsmp) {
            return TIAACK_REPGSZ_INVALID;
        }
        if (dsperMs < 1_000L) {
            return TIAACK_INVALID_PERIOD;
        }
        if (svids.size() > MAX_SVIDS) {
            return TIAACK_SVID_EXCEEDED;
        }
        for (int svid : svids) {
            if (!this.model.svids().containsKey(svid)) {
                return TIAACK_SVID_UNKNOWN;
            }
        }
        if (!this.traces.containsKey(trid) && this.traces.size() >= MAX_TRACES) {
            return TIAACK_TRACES_DENIED;
        }

        TraceSpec spec;
        try {
            spec = new TraceSpec(trid, svids, dsperMs, totsmp, repgsz);
        } catch (IllegalArgumentException e) {
            return TIAACK_INVALID_PERIOD;
        }

        /* 同 TRID 重新初始化：替换旧的定义，语义上等价于先停后启 */
        this.traces.put(trid, new ActiveTrace(spec, nowMs));
        return TIAACK_OK;
    }

    /**
     * 取走到期应发送的报文，并推进采样。
     *
     * @param nowMs 当前仿真时刻
     * @return 待发送报文（可能为空）
     */
    public synchronized List<Frame> poll(long nowMs) {
        List<Frame> frames = new ArrayList<>();
        List<Integer> finished = new ArrayList<>();

        for (Map.Entry<Integer, ActiveTrace> entry : this.traces.entrySet()) {
            ActiveTrace trace = entry.getValue();
            TraceSpec spec = trace.spec;

            while (trace.nextSample <= spec.totsmp()) {
                long at = TracePlan.sampleTimeMs(trace.t0Ms, spec.dsperMs(), trace.nextSample);
                if (at > nowMs) {
                    break;
                }

                trace.pending.add(this.sample(spec, at));
                trace.pendingTime = at;
                trace.nextSample++;

                boolean groupFull = trace.pending.size() >= spec.repgsz();
                boolean finishedSpec = trace.nextSample > spec.totsmp();

                if (groupFull || finishedSpec) {
                    frames.add(this.buildFrame(spec, trace));
                    trace.pending.clear();
                }
            }

            if (trace.nextSample > spec.totsmp()) {
                finished.add(entry.getKey());
            }
        }

        finished.forEach(this.traces::remove);
        return List.copyOf(frames);
    }

    private List<Secs2> sample(TraceSpec spec, long atMs) {
        List<Secs2> sample = new ArrayList<>(spec.svids().size());

        for (int svid : spec.svids()) {
            ResolvedSvid definition = this.model.svids().get(svid);
            OptionalDouble value = this.values.valueAt(svid, atMs);
            sample.add(Secs2Mapper.of(definition.type(), value.orElse(0.0)));
        }

        return List.copyOf(sample);
    }

    private Frame buildFrame(TraceSpec spec, ActiveTrace trace) {
        List<Secs2> flattened = new ArrayList<>(trace.pending.size() * spec.svids().size());
        for (List<Secs2> sample : trace.pending) {
            flattened.addAll(sample);
        }

        String stime = this.wallStart.plusMillis(trace.pendingTime)
                .atZone(this.zone)
                .toLocalDateTime()
                .format(STIME_FORMAT);

        return new Frame(spec.trid(), trace.nextSample - 1, stime,
                List.copyOf(flattened), trace.pending.size(), spec.svids().size());
    }

    /** 一条运行中的 Trace。 */
    private static final class ActiveTrace {

        private final TraceSpec spec;
        private final long t0Ms;
        private final List<List<Secs2>> pending = new ArrayList<>();

        private int nextSample = 1;
        private long pendingTime;

        ActiveTrace(TraceSpec spec, long t0Ms) {
            this.spec = spec;
            this.t0Ms = t0Ms;
        }
    }
}
