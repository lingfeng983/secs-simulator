package com.shimizukenta.secssim.runtime;

import java.util.List;

/**
 * 内置参数模板库：所有设备共享的「参数变化逻辑」起点（schema v7）。
 *
 * <p>v7 的模板体只有一段 {@code source}，因此"模板"不再是一张迷你模板图，
 * 而是一段可以直接拷贝到数据项上的值定义。库的作用回到它该有的样子：
 * <b>给人参考的量纲与波形</b>，复制出去就独立了。</p>
 *
 * <p>按真实工艺参数的典型形态整理：</p>
 * <ul>
 *   <li>单曲线 —— 温度、真空、流量、电源、计数（11 套）；</li>
 *   <li>分段工艺 {@code sequence} —— 一段一个波形，按工序先后排布（2 套）；</li>
 *   <li>组合 {@code expr} —— 私有曲线 + 一条算式，一次给出彼此相关的输出（3 套）。</li>
 * </ul>
 *
 * <p>组合模板是"多输出"折叠后的产物：{@code vars} 里的曲线只在这一个数据项内部可见，
 * 不产生中间 SVID，所以复制到哪个参数上都能自洽。目录是"默认送多少条"的唯一来源：
 * 条目删掉后，启动时 {@code Bootstrap} 会把库里同属内置、已不在目录中的行下线
 * （只动 readonly 行）。</p>
 */
public final class ParamTemplates {

    private ParamTemplates() {
    }

    /**
     * 目录里的一项。
     *
     * @param id          模板标识（稳定，播种幂等键）
     * @param name        界面显示名（讲清是什么设备的什么参数在怎么变）
     * @param description 一句话说明变化逻辑与量纲参考
     * @param body        模板体 JSON（{@code {"source": …}}）
     */
    public record Entry(String id, String name, String description, String body) {
    }

    /**
     * 模板目录。
     *
     * @return 全部内置参数模板
     */
    public static List<Entry> catalog() {
        return List.of(
                /* 温度 */
                new Entry("temp-constant", "恒温保温",
                        "恒定 180°C：保温/待机段的稳态温度设定值。",
                        """
                        {"source": {"mode": "const", "value": 180}}"""),
                new Entry("temp-sine", "正弦炉温波动",
                        "150~350°C、60s 周期：加热-散热平衡下的炉温周期波动。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "sine", "min": 150, "max": 350, "periodMs": 60000}}}"""),
                new Entry("temp-ramp", "工艺升温（斜坡）",
                        "20→400°C、5 分钟线性升温：升温段温控曲线，走完停在终点。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "ramp", "from": 20, "to": 400, "durationMs": 300000}}}"""),
                new Entry("temp-pwl-3seg", "升-保-降三段炉温",
                        "20→400→保温 10min→50°C：典型退火/氧化炉温程序。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "pwl", "points": [
                            {"tMs": 0, "value": 20}, {"tMs": 600000, "value": 400},
                            {"tMs": 1200000, "value": 400}, {"tMs": 1800000, "value": 50}]}}}"""),
                /* 压力 / 真空 */
                new Entry("vac-pwl-down", "抽真空（pwl 降压）",
                        "760→0.01 Torr 两分钟：Loadlock/工艺腔抽真空的典型降压曲线。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "pwl", "points": [
                            {"tMs": 0, "value": 760}, {"tMs": 60000, "value": 5},
                            {"tMs": 120000, "value": 0.01}]}}}"""),
                new Entry("vac-uniform", "腔压均匀波动",
                        "0.5~3.5 Torr、2s 重采样：工艺腔压的随机小波动。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "uniform", "min": 0.5, "max": 3.5, "resampleMs": 2000}}}"""),
                new Entry("vac-random-walk", "基压随机游走",
                        "0.5~2 mbar 有界游走、σ=0.05：真空基压的缓慢抖动。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "randomWalk", "start": 0.8, "driftPerSec": 0,
                            "sigma": 0.05, "gridMs": 1000, "octaves": 1, "min": 0.5, "max": 2,
                            "policy": "CLAMP"}}}"""),
                /* 流量 */
                new Entry("flow-uniform", "工艺气流均匀波动",
                        "100~300 sccm、3s 重采样：MFC 设定流量附近的随机波动。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "uniform", "min": 100, "max": 300, "resampleMs": 3000}}}"""),
                /* 电源 */
                new Entry("rf-sine", "射频功率正弦波动",
                        "800~1200W、60s 周期、相位 10s：等离子射频功率的周期波动。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "sine", "min": 800, "max": 1200, "periodMs": 60000, "phaseMs": 10000}}}"""),
                /* 计数 / 累积 */
                new Entry("count-ramp", "产量计数（循环爬升）",
                        "0→100、60s 一轮循环：班次产量的稳步累积。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "ramp", "from": 0, "to": 100, "durationMs": 60000, "cyclic": true}}}"""),
                new Entry("film-ramp", "沉积膜厚（循环爬升）",
                        "0→5000Å、2 分钟循环：膜厚累积到目标后清片重来。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "ramp", "from": 0, "to": 5000, "durationMs": 120000, "cyclic": true}}}"""),
                /* 分段工艺序列 */
                new Entry("seq-etch-chamber-pressure", "刻蚀腔压（抽真空→稳压→主刻蚀→破真空）",
                        "760→0.02→0.05 Torr：抽真空 15s、稳压 5s、进气 5s、主刻蚀 90s、过刻 30s、破真空 30s，全程 175s。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "sequence", "cyclic": false, "segments": [
                            {"durationMs": 15000, "curve": {"type": "ramp", "from": 760, "to": 0.02, "durationMs": 15000}},
                            {"durationMs": 5000, "curve": {"type": "uniform", "min": 0.012, "max": 0.03, "resampleMs": 1000}},
                            {"durationMs": 5000, "curve": {"type": "ramp", "from": 0.02, "to": 0.05, "durationMs": 5000}},
                            {"durationMs": 90000, "curve": {"type": "sine", "min": 0.045, "max": 0.055, "periodMs": 10000}},
                            {"durationMs": 30000, "curve": {"type": "uniform", "min": 0.04, "max": 0.06, "resampleMs": 2000}},
                            {"durationMs": 30000, "curve": {"type": "ramp", "from": 0.05, "to": 760, "durationMs": 30000}}]}}}"""),
                new Entry("seq-pecvd-chamber-temp", "PECVD 腔体温度（升温→保温→沉积→降温）",
                        "25→400→80°C：升温 60s、保温 30s、沉积 120s 正弦 ±4°C、降温 60s、待机 30s，全程 300s。",
                        """
                        {"source": {"mode": "curve", "clock": "phase",
                          "curve": {"type": "sequence", "cyclic": false, "segments": [
                            {"durationMs": 60000, "curve": {"type": "ramp", "from": 25, "to": 400, "durationMs": 60000}},
                            {"durationMs": 30000, "curve": {"type": "constant", "value": 400}},
                            {"durationMs": 120000, "curve": {"type": "sine", "min": 396, "max": 404, "periodMs": 60000}},
                            {"durationMs": 60000, "curve": {"type": "ramp", "from": 400, "to": 80, "durationMs": 60000}},
                            {"durationMs": 30000, "curve": {"type": "constant", "value": 80}}]}}}"""),
                /* 组合：私有曲线 + 一条算式（不是实例） */
                new Entry("combo-thermal-heater", "炉温取一位小数（组合）",
                        "150~350°C、60s 周期；输出按 0.1 取整——组合模板的最小形态：一条私有曲线 + 一条算式。",
                        """
                        {"source": {"mode": "expr", "clock": "phase",
                          "vars": {"v": {"type": "sine", "min": 150, "max": 350, "periodMs": 60000}},
                          "expr": "roundTo(v, 0.1)"}}"""),
                new Entry("combo-rf-net", "射频净功率（组合）",
                        "正向 800~1200W 周期波动、反射 2~30W 游走；输出 NET 是净功率。",
                        """
                        {"source": {"mode": "expr", "clock": "phase",
                          "vars": {
                            "fwd": {"type": "sine", "min": 800, "max": 1200, "periodMs": 60000, "phaseMs": 10000},
                            "ref": {"type": "randomWalk", "start": 5, "driftPerSec": 0.2, "sigma": 2,
                                    "gridMs": 1000, "octaves": 1, "min": 2, "max": 30, "policy": "CLAMP"}},
                          "expr": "fwd - ref"}}"""),
                new Entry("combo-flow-total", "双路气流总流量（组合）",
                        "两路 MFC 各 100~300 / 50~150 sccm 波动；输出 TOTAL 是两路之和。",
                        """
                        {"source": {"mode": "expr", "clock": "phase",
                          "vars": {
                            "ch1": {"type": "uniform", "min": 100, "max": 300, "resampleMs": 3000},
                            "ch2": {"type": "uniform", "min": 50, "max": 150, "resampleMs": 3000}},
                          "expr": "ch1 + ch2"}}"""));
    }
}
