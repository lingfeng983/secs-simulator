# ADR 0020 · 单一值定义与 wafer 周期：三类数据一条规则

- 日期:2026-11-15
- 状态:已接受
- 取代:[ADR 0004](0004-template-dag.md) 的模板图/实例绑定部分（受限表达式保留）
- 影响范围:`secs-core`（cycle/value/context/template 包）、`secs-app`（配置读写）、`secs-runtime`（报文与事件）、前端全部编辑器

## 背景

v6 的值模型是"三条时间线 + 一张绑定图":

- 参数曲线用**绝对时间 t**;事件计划用 **periodic 周期触发**;事件效果（effects）
  按**绝对时刻**施加。三者一重复就互相冲突:decay 跨周期污染、shift 超过 64 条
  被 FIFO 挤出、第 N 片的曲线位置与事件对不上。
- 值的来源有三种写法（`templates` 段 + 实例绑定、SVID 直配、默认曲线），
  配置里同一个事实要表达两次（模板体 + 绑定关系），界面需要"曲线/组合/实例"
  三层编辑器，用户很难回答"这个值为什么是 X"。
- 事件还带 `effects`（set/add/pulse/shift/resume），能在运行期改写 SVID 值，
  与"事件只是上报"的主机语义相悖，也让台账、回滚、限流各自背一套边界。

同时，模拟器的目标场景从"一台设备跑一条漂亮曲线"收敛为
"**一台设备按 wafer 逐片重复处理**:一个处理周期内参数怎么走、事件何时发生、
片与片之间 LOTID/WAFERID/RCPID 怎么推进"。

## 决策

### 1. 一个数据项一份值定义（`source`）

删除 `templates` 段、`instances` 绑定、`svids[].curve|template|output|params` 直配。
三类数据使用同一条规则:

| 类别 | 位置 | 变化单位 |
|---|---|---|
| 晶圆属性 Context（LOTID 等） | 配置顶层 `context[]` | 片号 |
| 设备常量 ECID | `equipment.constants[].source` | 相位/绝对时间/常量 |
| 参数变量 SVID | `units[].svids[].source`、`globalSvids[].source` | 相位/绝对时间/片号 |

`source` 只有五种形状:const / curve(+transform) / expr(+私有 vars) / cycle(数值) / cycle(文本)。
编号即 map 的 key，单写者由结构保证，不再需要 `DUPLICATE_SVID` 这类绑定冲突检查。

参数模板库保留，但从"绑定"退化为"**宏**":复制进设备时把组合模板折叠成
一条带私有 `vars` 的 `expr`（或带 `transform` 的曲线），设备里没有引用、没有实例。
改库不影响设备，改设备不影响库。

### 2. 事件只读

删除 `EventEffect` / `EventEffectStore` / `EffectValueSource` 全链路；
`events.plans[].effects` 字段删除（台账 DB 列保留、恒为空数组，不动表结构）。
事件触发 = 判可发 → 组装报文（读当前值）→ 发送。
运行期唯一的写入路径是调试用「设定」（`PinnedValueSource`，内存态、热重载失效）。

### 3. 唯一的周期时钟

新增 `cycle` 段:`durationMs / jitterMs / count / noisePerWafer / events[]`。

```text
duration_i = clamp(T + U(-J,+J))   // seed 派生，可复现
phase(t)   = t - start(index(t))
事件时刻    = start(i) + atMs      // 抖动只改片长，不移动片内偏移
```

- 曲线与表达式默认拿**片内相位**，跨片自动从头重放——重复不是"复位"，是纯函数。
- 周期事件在**构建期按片展开**成普通 `at` 触发计划（片数 × 事件数），
  调度器、限流、W 位、预览全部沿用既有实现，不引入第二套时间线。
- `jitterMs` 是否缩放事件取"不缩放"为默认:工艺步的相对位置是刚性的，会漂的是节拍。
- ID 推进（LOTID/WAFERID/RCPID）由 `context` 的 `mode:"cycle"` 表达，
  报告字段直接写 `ctx.<名>`，**不经过 SVID**（ASCII 里存不了非数字）。

### 4. 报告字段支持三种前缀

`reports[].svids` 升级为字段序列:槽位名（SVID）、`ecid.<编号>`、`ctx.<名>`，
顺序即报文顺序。组装器按字段类型编码，S1F3 等只认变量的路径用纯 SVID 序列。

## 后果

- **正面**:"第 3 片第 90 秒这个值为什么是 X"只用看一条 `source` 与当前 phase;
  配置往返不再有绑定表;界面从三层模板编辑器收敛为一个值定义编辑器。
- **负面**:同一模板复制到 N 个变量就是 N 份独立配置（丢掉"改一处全生效"），
  跨变量耦合只能写进同一条 `expr`——这是用可解释性换表达力的明确取舍。
- **不兼容**:`schemaVersion` 只接受 7;v4..v6 配置直接拒绝，不提供迁移器
  （模拟器数据均为可再生成的演示/测试资产，无生产数据需要迁移）。
- 随之删除的投影表:`template_curve` / `template_composite` / `template_instance` / `event_effect`。

## 实现

- `core/cycle/CycleClock`、`core/value/DataSource|SourceEvaluator|DataSourceValueSource`、`core/context/ContextTable`
- `app/config/DataSourceJson`、`CycleConfig`、`SessionConfig.compile()`（`UNKNOWN_CONTEXT_REF` 等 v7 校验）
- `runtime/session/S6F11Assembler`（字段序列编码）、`EcidValues`、`PinnedValueSource`
- 内置设备模板 6 个（bare/etch/cvd/tester/loadlock/cmp）与参数模板 16 套全部按 v7 重写
- 配置格式与语义见 [guides/schema-v7.md](../guides/schema-v7.md) 与 [guides/svid-value-model.md](../guides/svid-value-model.md)
