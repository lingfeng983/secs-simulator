import type { JsonObject } from './templateConfig';

/**
 * 内置 SVID 变化模板库（曲线预设）。
 *
 * <p>按真实工艺参数的典型变化形态整理：温度、压力/真空、流量、电源、
 * 计数/累积、通用六类，共 20 套。用于「结构」页新建曲线与
 * 「变量配置」值来源（内联曲线）的快速填入——选一条预设就把该类
 * 参数最常见的波形与量纲写进 curve，用户再微调即可。</p>
 */

export interface CurvePreset {
  /** 展示名（讲清是什么设备的什么参数在怎么变）。 */
  name: string;
  /** 一句话说明变化逻辑与量纲参考。 */
  description: string;
  /** 曲线正文（不含 id；type + 参数），与 schema v5 curves[] 一致。 */
  curve: JsonObject;
}

export interface CurvePresetGroup {
  category: string;
  items: readonly CurvePreset[];
}

export const BUILTIN_CURVE_PRESETS: readonly CurvePresetGroup[] = [
  {
    category: '温度',
    items: [
      {
        name: '恒温保温',
        description: '恒定 180°C：保温/待机段的稳态温度设定值。',
        curve: { type: 'constant', value: 180 },
      },
      {
        name: '正弦炉温波动',
        description: '150~350°C、60s 周期：加热-散热平衡下的炉温周期波动。',
        curve: { type: 'sine', min: 150, max: 350, periodMs: 60000, phaseMs: 0 },
      },
      {
        name: '工艺升温（斜坡）',
        description: '20→400°C、5 分钟线性升温：升温段温控曲线，走完停在终点。',
        curve: { type: 'ramp', from: 20, to: 400, durationMs: 300000, cyclic: false },
      },
      {
        name: '升-保-降三段炉温',
        description: '20→400→保温 10min→50°C：典型退火/氧化炉温程序（pwl 关键帧）。',
        curve: {
          type: 'pwl',
          points: [
            { tMs: 0, value: 20 },
            { tMs: 600000, value: 400 },
            { tMs: 1200000, value: 400 },
            { tMs: 1800000, value: 50 },
          ],
        },
      },
      {
        name: '阶跃工艺步温',
        description: '25→150→300→80°C 每 60s 一步：按工艺步切换的温度设定。',
        curve: {
          type: 'step',
          steps: [
            { fromMs: 0, value: 25 },
            { fromMs: 60000, value: 150 },
            { fromMs: 120000, value: 300 },
            { fromMs: 180000, value: 80 },
          ],
        },
      },
    ],
  },
  {
    category: '压力 / 真空',
    items: [
      {
        name: '抽真空（pwl 降压）',
        description: '760→0.01 Torr 两分钟：Loadlock/工艺腔抽真空的典型降压曲线。',
        curve: {
          type: 'pwl',
          points: [
            { tMs: 0, value: 760 },
            { tMs: 60000, value: 5 },
            { tMs: 120000, value: 0.01 },
          ],
        },
      },
      {
        name: '腔压均匀波动',
        description: '0.5~3.5 Torr、2s 重采样：工艺腔压的随机小波动。',
        curve: { type: 'uniform', min: 0.5, max: 3.5, resampleMs: 2000 },
      },
      {
        name: '基压随机游走',
        description: '0.5~2 mbar 有界游走、σ=0.05：真空基压的缓慢抖动。',
        curve: {
          type: 'randomWalk',
          start: 0.8, driftPerSec: 0, sigma: 0.05, gridMs: 1000,
          octaves: 1, min: 0.5, max: 2, policy: 'CLAMP',
        },
      },
      {
        name: '充气破真空（阶跃回压）',
        description: '0.01→5→760 Torr 阶梯回升：工艺结束后的放气破真空过程。',
        curve: {
          type: 'step',
          steps: [
            { fromMs: 0, value: 0.01 },
            { fromMs: 30000, value: 5 },
            { fromMs: 90000, value: 760 },
          ],
        },
      },
    ],
  },
  {
    category: '流量',
    items: [
      {
        name: '工艺气流均匀波动',
        description: '100~300 sccm、3s 重采样：MFC 设定流量附近的随机波动。',
        curve: { type: 'uniform', min: 100, max: 300, resampleMs: 3000 },
      },
      {
        name: '小流量均匀波动',
        description: '20~80 sccm、3s 重采样：辅气/吹扫小流量。',
        curve: { type: 'uniform', min: 20, max: 80, resampleMs: 3000 },
      },
      {
        name: '脉冲通气（方波）',
        description: '0/200 sccm 交替、30s 一拍：脉冲吹扫/脉冲送气的通断流量。',
        curve: {
          type: 'step',
          steps: [
            { fromMs: 0, value: 0 },
            { fromMs: 30000, value: 200 },
          ],
        },
      },
    ],
  },
  {
    category: '电源',
    items: [
      {
        name: '射频功率正弦波动',
        description: '800~1200W、60s 周期、相位 10s：等离子射频功率的周期波动。',
        curve: { type: 'sine', min: 800, max: 1200, periodMs: 60000, phaseMs: 10000 },
      },
      {
        name: '射频反射功率游走',
        description: '2~30W 有界游走：匹配前的反射功率抖动（越大越失配）。',
        curve: {
          type: 'randomWalk',
          start: 5, driftPerSec: 0.2, sigma: 2, gridMs: 1000,
          octaves: 1, min: 2, max: 30, policy: 'CLAMP',
        },
      },
      {
        name: '偏压恒定',
        description: '恒定 -350V 的直流偏压（正弦叠加前的稳态值）。',
        curve: { type: 'constant', value: 350 },
      },
    ],
  },
  {
    category: '计数 / 累积',
    items: [
      {
        name: '产量计数（循环爬升）',
        description: '0→100、60s 一轮循环：班次产量的稳步累积。',
        curve: { type: 'ramp', from: 0, to: 100, durationMs: 60000, cyclic: true },
      },
      {
        name: '不良计数（随机）',
        description: '0~6、5s 重采样：零星出现的不良品计数。',
        curve: { type: 'uniform', min: 0, max: 6, resampleMs: 5000 },
      },
      {
        name: '沉积膜厚（循环爬升）',
        description: '0→5000Å、2 分钟循环：膜厚累积到目标后清片重来。',
        curve: { type: 'ramp', from: 0, to: 5000, durationMs: 120000, cyclic: true },
      },
    ],
  },
  {
    category: '通用',
    items: [
      {
        name: '默认有界随机游走',
        description: '0~100、σ=1：与"默认曲线"同款的有界随机游走，量纲按需改。',
        curve: {
          type: 'randomWalk',
          start: 50, driftPerSec: 0, sigma: 1, gridMs: 1000,
          octaves: 1, min: 0, max: 100, policy: 'REFLECT',
        },
      },
      {
        name: '缓慢向上漂移',
        description: '0.5/秒漂移、σ=2、20~200 夹持：老化/温漂类慢趋势。',
        curve: {
          type: 'randomWalk',
          start: 20, driftPerSec: 0.5, sigma: 2, gridMs: 1000,
          octaves: 1, min: 20, max: 200, policy: 'CLAMP',
        },
      },
      {
        name: '快抖动',
        description: '45~55、0.5s 重采样：读数高频抖动（传感噪声）。',
        curve: { type: 'uniform', min: 45, max: 55, resampleMs: 500 },
      },
    ],
  },
];

export const BUILTIN_CURVE_PRESET_COUNT = BUILTIN_CURVE_PRESETS
  .reduce((sum, group) => sum + group.items.length, 0);

/** 按名字在内置变化模板库里找预设。 */
export function findCurvePreset(name: string): CurvePreset | undefined {
  for (const group of BUILTIN_CURVE_PRESETS) {
    const hit = group.items.find((item) => item.name === name);
    if (hit !== undefined) {
      return hit;
    }
  }
  return undefined;
}
