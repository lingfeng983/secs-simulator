package com.shimizukenta.secssim.app.config;

import java.util.ArrayList;
import java.util.List;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.event.EventPlan;
import com.shimizukenta.secssim.core.event.Trigger;

/**
 * wafer 周期定义（schema v7）。
 *
 * <p>一个周期 = 一片 wafer 的处理过程。参数曲线与事件都是 {@code [0, duration)} 上的
 * 局部时间，重复由这里负责：时长（含每片 ±jitter）、片数、以及"事件在哪一片的哪一刻发生"。</p>
 *
 * <p><b>事件在构建期按片展开</b>：{@code count} 片 × {@code events} 条，
 * 每条变成一个普通的时间触发计划。这样调度器、限流、W 位、预览全部沿用既有实现，
 * 不需要在运行时维护第二套时间线。代价是片数与事件数相乘——25 × 5 = 125 条计划，
 * 是配置规模而不是运行时开销。</p>
 *
 * @param durationMs    标准周期长度
 * @param jitterMs      每片长度误差（±）
 * @param count         片数
 * @param noisePerWafer 每片是否用不同噪声
 * @param events        周期内事件（offset 相对片起点）
 */
public record CycleConfig(long durationMs, long jitterMs, int count, boolean noisePerWafer, List<Event> events) {

    /** 片数缺省：一小批。 */
    public static final int DEFAULT_COUNT = 25;

    /**
     * 周期内的一个事件。
     *
     * @param atMs 片内偏移（毫秒）
     * @param ceid 采集事件 ID
     */
    public record Event(long atMs, int ceid) {
    }

    /** 校验并创建。 */
    public CycleConfig {
        if (durationMs < CycleClock.MIN_PERIOD_MS) {
            throw new IllegalArgumentException("durationMs must be >= " + CycleClock.MIN_PERIOD_MS);
        }
        if (jitterMs < 0L || jitterMs >= durationMs) {
            throw new IllegalArgumentException("jitterMs must be in [0, durationMs)");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
        events = List.copyOf(events);
    }

    /**
     * 周期时钟。
     *
     * @param seed 种子
     * @return 时钟
     */
    public CycleClock clock(long seed) {
        return new CycleClock(this.durationMs, this.jitterMs, this.count, seed);
    }

    /**
     * 把一个时刻的事件展开成计划列表。
     *
     * <p>时刻 = {@code 片起点 + atMs}。抖动只改变片长，不移动片内偏移——
     * 工艺步的相对位置是刚性的，节拍才是会漂的。</p>
     *
     * @param seed 种子（决定每片起点）
     * @return 计划列表
     */
    public List<EventPlan> expandPlans(long seed) {
        CycleClock clock = this.clock(seed);
        List<EventPlan> plans = new ArrayList<>(this.count * this.events.size());
        for (int wafer = 0; wafer < this.count; wafer++) {
            long start = clock.startOf(wafer);
            for (int index = 0; index < this.events.size(); index++) {
                Event event = this.events.get(index);
                String id = "cyc" + wafer + "-" + index;
                plans.add(new EventPlan(id, event.ceid(), new Trigger.At(start + event.atMs())));
            }
        }
        return plans;
    }

    /**
     * 解析 {@code cycle} 段。
     *
     * @param root 配置根
     * @param path 字段路径
     * @return 周期定义；没有该段返回 {@code null}
     */
    static CycleConfig parse(JsonHub root, String path) {
        if (!root.containsKey("cycle")) {
            return null;
        }
        JsonHub cycle = JsonAccess.object(root, "cycle", path);
        String at = path + ".cycle";

        long durationMs = JsonAccess.longValue(cycle, "durationMs", at);
        long jitterMs = (long) JsonAccess.optionalDouble(cycle, "jitterMs", at).orElse(0.0);
        int count = JsonAccess.optionalInt(cycle, "count", at).orElse(DEFAULT_COUNT);
        boolean noisePerWafer = JsonAccess.bool(cycle, "noisePerWafer", false, at);

        if (durationMs < CycleClock.MIN_PERIOD_MS) {
            throw new ConfigException(at + ".durationMs", "周期长度至少 1000ms");
        }
        if (jitterMs < 0L) {
            throw new ConfigException(at + ".jitterMs", "抖动不能为负");
        }
        if (jitterMs >= durationMs) {
            throw new ConfigException(at + ".jitterMs", "抖动必须小于周期长度（否则片长会被压到 0）");
        }
        if (count < 1) {
            throw new ConfigException(at + ".count", "片数至少 1");
        }
        if (count > 10_000) {
            throw new ConfigException(at + ".count", "片数上限 10000（事件按片展开，再多会撑爆配置）");
        }

        List<Event> events = new ArrayList<>();
        if (cycle.containsKey("events")) {
            List<JsonHub> items = JsonAccess.elements(JsonAccess.array(cycle, "events", at), at + ".events");
            long safeLimit = durationMs - jitterMs;
            for (int i = 0; i < items.size(); i++) {
                String itemPath = at + ".events[" + i + "]";
                JsonHub item = items.get(i);
                long offset = JsonAccess.longValue(item, "atMs", itemPath);
                int ceid = JsonAccess.integer(item, "ceid", itemPath);
                if (ceid < 1) {
                    throw new ConfigException(itemPath + ".ceid", "CEID 必须 >= 1");
                }
                if (offset < 0L || offset >= durationMs) {
                    throw new ConfigException(itemPath + ".atMs",
                            "片内偏移必须落在 [0, " + durationMs + ") 内，实际 " + offset);
                }
                if (offset > safeLimit) {
                    throw new ConfigException(itemPath + ".atMs",
                            "偏移 " + offset + " 超过最短片长 " + safeLimit + "ms，抖动到最短的那片会吞掉这个事件");
                }
                events.add(new Event(offset, ceid));
            }
        }

        return new CycleConfig(durationMs, jitterMs, count, noisePerWafer, events);
    }
}
