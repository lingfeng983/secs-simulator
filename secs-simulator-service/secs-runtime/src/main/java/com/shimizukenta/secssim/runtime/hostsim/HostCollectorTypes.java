package com.shimizukenta.secssim.runtime.hostsim;

import java.util.List;

/**
 * S1F3 采集器的配置形状（从 HostSimulator 抽出，评审 2026-11-24 千行拆分）。
 * 校验语义与拆分前逐行等价；常量仍在 {@link HostSimulator}。
 */
final class HostCollectorTypes {

    private HostCollectorTypes() {
        /* 只有类型声明 */
    }

    /** 采集目标 SVID（远程模式 name/unit 可空）。 */
    record CollectorSvid(int id, String name, String unit) {

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
    record CollectorConfig(int startCeid, int stopCeid, long intervalMs, List<CollectorSvid> svids) {

        /** 校验并创建。 */
        public CollectorConfig {
            if (startCeid < 1 || stopCeid < 1) {
                throw new IllegalArgumentException("startCeid/stopCeid 必须 >= 1");
            }
            if (startCeid == stopCeid) {
                throw new IllegalArgumentException("startCeid 与 stopCeid 不能相同: " + startCeid);
            }
            if (intervalMs < HostSimulator.MIN_INTERVAL_MS) {
                throw new IllegalArgumentException("intervalMs 必须 >= " + HostSimulator.MIN_INTERVAL_MS + " 但为 " + intervalMs);
            }
            svids = List.copyOf(svids);
            if (svids.isEmpty()) {
                throw new IllegalArgumentException("采集 SVID 列表不能为空");
            }
            if (svids.size() > HostSimulator.MAX_COLLECTOR_SVIDS) {
                throw new IllegalArgumentException("采集 SVID 最多 " + HostSimulator.MAX_COLLECTOR_SVIDS
                        + " 个，当前 " + svids.size() + " 个");
            }
        }
    }
}
