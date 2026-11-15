package com.shimizukenta.secssim.runtime;

import java.util.List;

/**
 * 内置参数模板库：所有设备共享的「参数变化逻辑」起点。
 *
 * <p>按真实工艺参数的典型变化形态整理：温度、压力/真空、流量、电源、
 * 计数/累积、通用六类，共 20 套。每套是一个单曲线简形模板体
 * （{@link com.shimizukenta.secssim.app.config.ParamTemplateBody} 归一化为
 * 完整形入库），设备配置时从库里选择复制。放在后端与设备模板同理：
 * 播种要过模板体校验这道判据，库里不会有起不来的模板。</p>
 *
 * <p>目录是"默认送多少条"的唯一来源：条目从目录里删掉后，启动时
 * {@code Bootstrap} 会把库里同属内置、但已不在目录中的行一并下线
 * （只动 readonly 行，自定义模板与设备里已复制的快照不受影响）。</p>
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
     * @param body        模板体 JSON（单曲线简形）
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
                        {"curve": {"type": "constant", "value": 180}}"""),
                new Entry("temp-sine", "正弦炉温波动",
                        "150~350°C、60s 周期：加热-散热平衡下的炉温周期波动。",
                        """
                        {"curve": {"type": "sine", "min": 150, "max": 350, "periodMs": 60000, "phaseMs": 0}}"""),
                new Entry("temp-ramp", "工艺升温（斜坡）",
                        "20→400°C、5 分钟线性升温：升温段温控曲线，走完停在终点。",
                        """
                        {"curve": {"type": "ramp", "from": 20, "to": 400, "durationMs": 300000, "cyclic": false}}"""),
                new Entry("temp-pwl-3seg", "升-保-降三段炉温",
                        "20→400→保温 10min→50°C：典型退火/氧化炉温程序（pwl 关键帧）。",
                        """
                        {"curve": {"type": "pwl", "points": [
                            {"tMs": 0, "value": 20},
                            {"tMs": 600000, "value": 400},
                            {"tMs": 1200000, "value": 400},
                            {"tMs": 1800000, "value": 50}]}}"""),
                new Entry("temp-step", "阶跃工艺步温",
                        "25→150→300→80°C 每 60s 一步：按工艺步切换的温度设定。",
                        """
                        {"curve": {"type": "step", "steps": [
                            {"fromMs": 0, "value": 25},
                            {"fromMs": 60000, "value": 150},
                            {"fromMs": 120000, "value": 300},
                            {"fromMs": 180000, "value": 80}]}}"""),
                /* 压力 / 真空 */
                new Entry("vac-pwl-down", "抽真空（pwl 降压）",
                        "760→0.01 Torr 两分钟：Loadlock/工艺腔抽真空的典型降压曲线。",
                        """
                        {"curve": {"type": "pwl", "points": [
                            {"tMs": 0, "value": 760},
                            {"tMs": 60000, "value": 5},
                            {"tMs": 120000, "value": 0.01}]}}"""),
                new Entry("vac-uniform", "腔压均匀波动",
                        "0.5~3.5 Torr、2s 重采样：工艺腔压的随机小波动。",
                        """
                        {"curve": {"type": "uniform", "min": 0.5, "max": 3.5, "resampleMs": 2000}}"""),
                new Entry("vac-random-walk", "基压随机游走",
                        "0.5~2 mbar 有界游走、σ=0.05：真空基压的缓慢抖动。",
                        """
                        {"curve": {"type": "randomWalk", "start": 0.8, "driftPerSec": 0, "sigma": 0.05,
                            "gridMs": 1000, "octaves": 1, "min": 0.5, "max": 2, "policy": "CLAMP"}}"""),
                new Entry("vac-step-back", "充气破真空（阶跃回压）",
                        "0.01→5→760 Torr 阶梯回升：工艺结束后的放气破真空过程。",
                        """
                        {"curve": {"type": "step", "steps": [
                            {"fromMs": 0, "value": 0.01},
                            {"fromMs": 30000, "value": 5},
                            {"fromMs": 90000, "value": 760}]}}"""),
                /* 流量 */
                new Entry("flow-uniform", "工艺气流均匀波动",
                        "100~300 sccm、3s 重采样：MFC 设定流量附近的随机波动。",
                        """
                        {"curve": {"type": "uniform", "min": 100, "max": 300, "resampleMs": 3000}}"""),
                new Entry("flow-pulse", "脉冲通气（方波）",
                        "0/200 sccm 交替、30s 一拍：脉冲吹扫/脉冲送气的通断流量。",
                        """
                        {"curve": {"type": "step", "steps": [
                            {"fromMs": 0, "value": 0},
                            {"fromMs": 30000, "value": 200}]}}"""),
                /* 电源 */
                new Entry("rf-sine", "射频功率正弦波动",
                        "800~1200W、60s 周期、相位 10s：等离子射频功率的周期波动。",
                        """
                        {"curve": {"type": "sine", "min": 800, "max": 1200, "periodMs": 60000, "phaseMs": 10000}}"""),
                new Entry("rf-reflect-walk", "射频反射功率游走",
                        "2~30W 有界游走：匹配前的反射功率抖动（越大越失配）。",
                        """
                        {"curve": {"type": "randomWalk", "start": 5, "driftPerSec": 0.2, "sigma": 2,
                            "gridMs": 1000, "octaves": 1, "min": 2, "max": 30, "policy": "CLAMP"}}"""),
                new Entry("bias-constant", "偏压恒定",
                        "恒定 -350V 的直流偏压（正弦叠加前的稳态值）。",
                        """
                        {"curve": {"type": "constant", "value": -350}}"""),
                /* 计数 / 累积 */
                new Entry("count-ramp", "产量计数（循环爬升）",
                        "0→100、60s 一轮循环：班次产量的稳步累积。",
                        """
                        {"curve": {"type": "ramp", "from": 0, "to": 100, "durationMs": 60000, "cyclic": true}}"""),
                new Entry("defect-uniform", "不良计数（随机）",
                        "0~6、5s 重采样：零星出现的不良品计数。",
                        """
                        {"curve": {"type": "uniform", "min": 0, "max": 6, "resampleMs": 5000}}"""),
                new Entry("film-ramp", "沉积膜厚（循环爬升）",
                        "0→5000Å、2 分钟循环：膜厚累积到目标后清片重来。",
                        """
                        {"curve": {"type": "ramp", "from": 0, "to": 5000, "durationMs": 120000, "cyclic": true}}"""),
                /* 通用 */
                new Entry("generic-walk", "默认有界随机游走",
                        "0~100、σ=1：与「默认曲线」同款的有界随机游走，量纲按需改。",
                        """
                        {"curve": {"type": "randomWalk", "start": 50, "driftPerSec": 0, "sigma": 1,
                            "gridMs": 1000, "octaves": 1, "min": 0, "max": 100, "policy": "REFLECT"}}"""),
                new Entry("generic-drift", "缓慢向上漂移",
                        "0.5/秒漂移、σ=2、20~200 夹持：老化/温漂类慢趋势。",
                        """
                        {"curve": {"type": "randomWalk", "start": 20, "driftPerSec": 0.5, "sigma": 2,
                            "gridMs": 1000, "octaves": 1, "min": 20, "max": 200, "policy": "CLAMP"}}"""),
                new Entry("generic-jitter", "快抖动",
                        "45~55、0.5s 重采样：读数高频抖动（传感噪声）。",
                        """
                        {"curve": {"type": "uniform", "min": 45, "max": 55, "resampleMs": 500}}"""));
    }
}
