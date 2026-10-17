# ADR 0003 · 曲线必须是时间的纯函数

- 状态：已接受
- 日期：M2
- 影响范围：`secs-core` 的 `curve` / `value`

## 问题

"持续随机上升"这种曲线，直觉实现是每 tick 累加一个随机增量：

```java
value += drift * dt + random.nextGaussian() * sigma;   // 直觉写法
```

它能跑，但会带来三个无法回避的问题：

1. **不可乱序求值**：Trace（S6F1）的采样点未必和 tick 对齐，
   想知道"t=1234ms 那一刻的值"就必须真的跑到那一刻。
2. **依赖调用次数**：tick 从 50ms 改成 100ms，曲线形状就变了，
   调试时"预览"和"实跑"会对不上。
3. **不可回放**：任何"从中间某点重新开始"的需求（断点续跑、对账、离线重算）都失效。

## 决策

曲线统一实现为 `(tMs, Rng) -> double` 的**纯函数**。
"随机游走"改用**哈希噪声场**：把 `(seed, floor(t/grid))` 混合成伪随机值，
再在网格点之间做 smoothstep 插值，多层叠加得到分形噪声。

```java
double at(long tMs, Rng rng) {
    double drift = driftPerSec * (tMs / 1000.0);
    double noise = sigma * rng.fbm(tMs, gridMs, octaves);
    return policy.apply(start + drift + noise, min, max);
}
```

## 后果

- **正面**：任意时刻可求值（Trace 采样、加速回放、离线重算）；tick 频率变化不影响曲线形状；
  SVID 之间用 `seed.derive("svid:1001")` 隔离，增删变量不扰动其它曲线。
- **负面**：噪声的统计特性不如真随机过程"自然"（没有真正的马尔可夫性），
  需要靠倍频层数与网格宽度调出观感；这一点在 M3 提供参数模板时再调优。
- **约束**：任何需要"带状态的物理过程"（一阶滞后、积分、计数器）都必须做成
  **包装/派生**，且状态只能单调前推；不能污染曲线本体。
