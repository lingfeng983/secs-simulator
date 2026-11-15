package com.shimizukenta.secssim.runtime.hostsim;

import com.shimizukenta.jsonhub.JsonHub;
import com.shimizukenta.jsonhub.JsonHubBuilder;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secssim.app.log.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一台主机的全部 S1F3 采集器：按「采集计划」分组，各计划独立轮询、独立启停、独立存数据。
 *
 * <p>3.0 之前一台主机只有一份采集器，启动 B 会把 A 的配置替换掉、A 的数据就断了。
 * 现场要的是「A 与 B 同时采」——两份计划各有自己的采样周期与 SVID 集合，
 * 互不干扰。这里以计划 id（界面方案库里的 id）为键管理，缺省计划用
 * {@link #DEFAULT_PLAN_ID}，老的单份配置（没有 planId）自然落到它上面。</p>
 *
 * <p>并发：{@code apply/start/stop} 来自 HTTP 线程，{@link #onS6f11} 来自链路线程。
 * 对 map 的读一律先复制快照再遍历，避免在遍历时被增删。</p>
 */
final class HostCollectors {

    /** 没带计划 id 的请求（老客户端）落到这个计划上。 */
    static final String DEFAULT_PLAN_ID = "default";

    private final HostSimulator owner;
    private final String hostId;
    /** 按插入顺序（= 首次下发顺序）保存，JSON 与日志顺序稳定。 */
    private final Map<String, HostCollector> byPlan = new LinkedHashMap<>();

    HostCollectors(HostSimulator owner, String hostId) {
        this.owner = owner;
        this.hostId = hostId;
    }

    /** 计划是否已经下发过配置。 */
    synchronized boolean configured(String planId) {
        HostCollector collector = this.byPlan.get(key(planId));
        return collector != null && collector.currentConfig() != null;
    }

    /** 该计划的采集器（可能不存在）。 */
    synchronized HostCollector get(String planId) {
        return this.byPlan.get(key(planId));
    }

    /** 下发（或更新）某个计划的配置：只影响这一份，其它计划照常在采。 */
    synchronized void apply(String planId, HostSimulator.CollectorConfig config) {
        String key = key(planId);
        HostCollector collector = this.byPlan.get(key);
        if (collector == null) {
            collector = new HostCollector(this.owner, this.hostId, key);
            this.byPlan.put(key, collector);
        }
        collector.applyConfig(config);
    }

    /**
     * 手动启动。
     *
     * @param planId 计划 id；空/null 表示"全部已配置的计划"
     * @throws IllegalStateException 指定了计划但该计划还没有配置
     */
    void start(String planId) {
        if (planId == null || planId.isBlank()) {
            for (HostCollector collector : this.snapshot()) {
                collector.startManual();
            }
            return;
        }
        HostCollector collector = this.get(planId);
        if (collector == null || collector.currentConfig() == null) {
            throw new IllegalStateException("采集计划还没有配置: " + planId);
        }
        collector.startManual();
    }

    /** 手动停止；planId 为空表示全部停（机台断开、主机删除等收尾也用它）。 */
    void stop(String planId) {
        if (planId == null || planId.isBlank()) {
            this.stopAll("手动停止");
            return;
        }
        HostCollector collector = this.get(planId);
        if (collector == null) {
            throw new IllegalStateException("采集计划还没有配置: " + planId);
        }
        collector.stopManual();
    }

    /** 逐条分发 S6F11：每条计划只认自己的启停 CEID。 */
    void onS6f11(SecsMessage message) {
        for (HostCollector collector : this.snapshot()) {
            collector.onS6f11(message);
        }
    }

    /** 连接成功后各计划都回到「待命」（轮询仍由启动 CEID 触发）。 */
    void recoverAfterConnect() {
        for (HostCollector collector : this.snapshot()) {
            collector.recoverAfterConnect();
        }
    }

    /** 全部停下（保留配置）。 */
    void stopAll(String cause) {
        for (HostCollector collector : this.snapshot()) {
            collector.stop(cause);
        }
    }

    /** 实例销毁时收尾。 */
    void shutdown() {
        for (HostCollector collector : this.snapshot()) {
            collector.shutdown();
        }
    }

    /**
     * 移除一份采集器：先停（含待命）再丢掉。
     *
     * <p>配置区删掉一份采集方案时调它——不这么做，那份采集器还在后台按周期采、
     * 还会随 {@code collector_json} 一起持久化，重启后又复活，而界面上已经没有它那一行了。</p>
     *
     * @return 这份采集器原本是否存在
     */
    synchronized boolean remove(String planId) {
        String key = key(planId);
        HostCollector collector = this.byPlan.remove(key);
        if (collector == null) {
            return false;
        }
        collector.stopManual();
        collector.shutdown();
        Log.info("主机采集器已移除（" + this.hostId + " / " + key + "）");
        return true;
    }

    /** 任意一份在采。 */
    boolean anyRunning() {
        for (HostCollector collector : this.snapshot()) {
            if (collector.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /** 状态与数据：顶层给汇总，明细在 {@code collectors} 数组里。 */
    String toJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<HostCollector> snapshot = this.snapshot();
        List<JsonHub> items = new ArrayList<>();
        boolean configured = false;
        boolean running = false;
        boolean armed = false;
        for (HostCollector collector : snapshot) {
            items.add(collector.toJsonHub());
            configured |= collector.currentConfig() != null;
            running |= collector.isRunning();
            armed |= collector.isArmed();
        }
        return b.object(
                b.pair("configured", configured),
                b.pair("running", running),
                b.pair("armed", armed),
                b.pair("collectors", b.array(items))).toJson();
    }

    /** 全部计划的配置（持久化用；不含运行态）。 */
    String configsJson() {
        JsonHubBuilder b = JsonHub.getBuilder();
        List<JsonHub> items = new ArrayList<>();
        for (HostCollector collector : this.snapshot()) {
            HostSimulator.CollectorConfig config = collector.currentConfig();
            if (config == null) {
                continue;
            }
            List<JsonHub> svids = new ArrayList<>();
            for (HostSimulator.CollectorSvid svid : config.svids()) {
                svids.add(b.object(
                        b.pair("id", svid.id()),
                        b.pair("name", svid.name()),
                        b.pair("unit", svid.unit())));
            }
            items.add(b.object(
                    b.pair("planId", collector.planId()),
                    b.pair("startCeid", config.startCeid()),
                    b.pair("stopCeid", config.stopCeid()),
                    b.pair("intervalMs", config.intervalMs()),
                    b.pair("svids", b.array(svids))));
        }
        return b.object(b.pair("collectors", b.array(items))).toJson();
    }

    /** 复制一份当前采集器列表，遍历时不持锁。 */
    private synchronized List<HostCollector> snapshot() {
        return new ArrayList<>(this.byPlan.values());
    }

    private static String key(String planId) {
        return planId == null || planId.isBlank() ? DEFAULT_PLAN_ID : planId;
    }
}
