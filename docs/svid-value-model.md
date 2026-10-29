# SVID 值变化机制与规则

> **基线**：schema v6（`SessionConfig.SCHEMA_VERSION = 6`），代码截至 2026-10-28。
> **范围**：只回答"一个 SVID 的值从哪来、什么时候会变、几层规则谁压谁、哪些出口看到的是同一份值"。
> 协议字段格式（S1F4 / S6F11 / S6F1）不在本文展开，见 [E5/E30 对照](reference/project/E30-GEM-项目对照.md) 与 [schema-v5](schema-v5.md)。

## 0. 一句话模型

```text
value(svid, t) =
    pinned[svid]                    若该 SVID 被「设定」钉住（最高优先）
    effects( base(svid, t) )        否则：基线值叠加"时刻 t 仍活动"的事件效果
    0.0（按声明类型编码）            基线与钉值都没有时的协议出口兜底
```

两条硬约束贯穿全部实现：

1. **基线是时间的纯函数**：`base(svid, t)` 只依赖 `seed` 与 `t`，没有累加状态，所以任意时刻可重算、可回放（[ADR 0003](adr/0003-pure-curves.md)）。
2. **同一个 SVID 只能有一个生产者**：曲线、模板实例、SVID 直配三者互斥，冲突在编译期报错（[ADR 0004](adr/0004-template-dag.md)）。

## 1. 三层结构与优先级

| 优先级 | 层 | 实现 | 生命周期 | 写入入口 |
|---|---|---|---|---|
| 1（最高） | 「设定」钉值 | `EffectValueSource.pinned` | 取消设定或热重载即消失 | `PUT /sessions/{id}/svids/{svid}/value` |
| 2 | 事件效果 | `EventEffectStore` + `EffectValueSource` | 按 op 到期、被 `resume` 清除、被挤出（每 SVID 64 条）或热重载清空 | `events.plans[].effects[]`，事件真正上报时施加 |
| 3 | 基线 | `CurveValueSource`（默认曲线）或 `GraphValueSource`（模板 DAG） | 随配置/热重载整体替换 | 配置里的 `templates`、`svids[].curve/template` |
| 4 | 兜底 0 | 协议出口的 `orElse(0.0)` | — | 该 SVID 有定义但基线没有值 |

> 钉值优先于效果，效果优先于基线。**钉值不是效果**：它不会被 `resume` 清掉，也不会因 64 条上限被挤出。

值的读取链：

```text
配置 ──► 基线 ValueSource ──┐
                            ├─► EffectValueSource ──► S1F4 / S6F11 / S6F1 / 预览
EventEffectStore ───────────┘         ▲
                                      └── pinned（「设定」）
```

所有消费方共用同一个 `ValueSourceHandle` 委托，所以热重载换图只换内部实现，S6F11 组装器、Trace、预览立刻看到同一份新值（`ValueSourceHandle.java`）。

## 2. 基线层：值从哪来

### 2.1 二选一：默认曲线还是模板图

判定在 `SessionConfig.hasCustomValues()`：

```text
templates 段非空  或  任意 SVID 配了 curve/template（直配）
  ├─ 是 → TemplateGraph（GraphValueSource），未绑定输出的 SVID 没有值 → 出口为 0
  └─ 否 → ValueStore + DefaultCurves（CurveValueSource）
```

统一入口是 `ValueSources.of(...)`：真实启动与草稿预览都走它，避免"预览一套、启动另一套"。

### 2.2 默认曲线（没有自定义值来源时）

由 `DefaultCurves.bind(...)` 按 SVID 定义生成：

| SVID 形态 | 默认值 |
|---|---|
| 槽位名 `UNIT_ID` | 常量 = 该单元 `unitId` |
| 数值型（U1..F8） | 值域内**有界随机游走**：起点在值域 60% 处，漂移 `0.001×span`/秒，噪声 `0.02×span`，网格 2s、3 倍频、越界 `REFLECT` |
| 数值型但 `min/max` 都没配 | 退化为 `0..100` 再按上式构造 |
| `BOOLEAN` | 常量 `1.0` |
| `ASCII` / `BINARY` | 常量 `0.0` |

每个 SVID 的噪声源独立派生：`new Rng(seed).derive("svid:" + svid)`。因此**新增/删除一个变量不会改变其它变量的曲线**，A/B 对比与回归可复现。

### 2.3 模板 DAG 与 SVID 直配

- `templates.curves` → 单输出模板（输出名固定 `V`）；
- `templates.composites` → 多输出模板，输出是受限表达式；
- `templates.instances` → 输出名 → 绝对 SVID 的绑定；
- `svids[].curve` | `svids[].template`（+ 可选 `output` / `params`）→ 直配，编译期合成为匿名实例 `@svid:<id>`（内联曲线为 `@curve:<id>`）。

求值规则：

- 按拓扑序逐个实例求值，下游通过 `in.实例.输出` 读上游；
- 每个实例用 `rng.derive("instance:" + id)` 派生独立噪声；
- 表达式只允许 `t`、`p.参数`、`in.实例.输出` + 四则/比较 + 12 个纯函数，**无赋值、无循环、无副作用**；
- 一次 `evaluate(t)` 会算出全图输出，`GraphValueSource` 只缓存**最近一个时刻**的不可变快照（`(atMs, values)` 原子发布），不做无界缓存。

**值域只影响默认曲线**：模板输出、直配曲线、事件效果都**不会**按 SVID 声明的 `min/max` 裁剪。写越界，主机就收到越界值。

### 2.4 单写者：一个 SVID 只能有一个生产者

`TemplateGraph.Builder` 在构建期检查：

| 检查 | 错误码 |
|---|---|
| 同一 SVID 被多个输出/实例绑定（含直配合成实例） | `DUPLICATE_SVID`（`GraphException` → `ConfigException`） |
| 实例 id 重复 | `DUPLICATE_INSTANCE` |
| 输出名不属于模板 | `UNKNOWN_OUTPUT` / `MISSING_OUTPUT` |
| 依赖了不存在实例/输出 | `UNKNOWN_INSTANCE` / `UNKNOWN_OUTPUT` |
| 模板图有环 | `CYCLE` |

直配与 `templates.instances` 对同一 SVID 同时出现，也在这里以 `DUPLICATE_SVID` 报出——两套写法不会各查各的规则。

### 2.5 SVID 定义期规则（ID 与引用）

- 单元内 SVID 是**槽位相对**：绝对 ID = `svidBase + (单元位置-1) × svidStride + offset`（`offset ≥ 1`，`unitPosition` 是列表位置，不是 `unitId`）。
- 设备级 `globalSvids` 直接写绝对 `id`。
- 编译期拦截：`DUPLICATE_SVID`、`DUPLICATE_SLOT`、`DUPLICATE_OFFSET`、`STRIDE_TOO_SMALL_SVID`（最大 offset ≥ 步长会让相邻单元重叠）、`RESERVED_ID`（默认 1..999 保留）、`UNKNOWN_SVID_REF`（报告引用了不存在的 SVID）、`MISSING_UNIT_ID_SVID`（`injectUnitId=true` 却没有 `UNIT_ID` 槽位）、`NO_UNITS`。
- `DUPLICATE_GLOBAL_SVID_NAME` 只是 warning：按名索引 first-wins，真正保证唯一性的是 ID。

## 3. 事件效果层：事件如何改值

### 3.1 配置形状（schema v6）

```json
{
  "events": {
    "plans": [
      {
        "id": "start",
        "ceid": 2001,
        "trigger": { "type": "periodic", "periodMs": 10000 },
        "effects": [
          { "svid": 1001, "op": "set",   "value": 180, "decayMs": 30000 },
          { "svid": 1002, "op": "shift", "delta": 12 },
          { "svid": 1003, "op": "pulse", "value": 85, "holdMs": 5000 },
          { "svid": 1004, "op": "resume" }
        ]
      }
    ]
  }
}
```

运行时通过 `POST /sessions/{id}/events`（新增）、`PUT|DELETE /sessions/{id}/events/{planId}`（改/删）也能改，同一套解析（`RuntimeEdits.parseEventPlan`）。

### 3.2 五种 op 的语义

| op | 必填字段 | 对当前值的作用 | 何时失效 |
|---|---|---|---|
| `set` | `value` | **绝对写入**：当前值 = `value` | 无 `decayMs`：直到 `resume` / 被挤出 / 热重载；有 `decayMs`：从 `value` **线性回到施加前的组合值**，`decayMs` 后自动失效 |
| `add` / `shift` | `delta` | **相对叠加**：当前值 += `delta`（`add` 只是 `shift` 的别名） | 直到 `resume` / 被挤出 / 热重载 |
| `pulse` | `value`、`holdMs > 0` | **绝对写入**：当前值 = `value` | `holdMs` 后自动失效，回落到之前的组合结果 |
| `resume` | 无 | 不参与求值；施加瞬间**清空该 SVID 的全部已施加效果** | — |

字段与 op 严格匹配：`set` 不接受 `delta`/`holdMs`，`pulse` 不接受 `delta`/`decayMs`，`resume` 不接受任何参数字段，多写即报错（避免"键名写错但静默无效"）。`value`/`delta` 必须是有限数值，`holdMs > 0`，`decayMs ≥ 0`。

### 3.3 施加时机（最关键的一条）

效果**只在 S6F11 真正上报时施加**，唯一入口是 `EventReporter.emit(ceid)`：

```text
EventScheduler（periodic / at / onCondition / afterEvent）
        └─ firePlan：计划禁用？→ 丢；CEID 被 S2F37 禁用？→ 丢并计数；
                     限流器拒绝？→ 丢并计数；否则 → emit(ceid)
手动触发（HTTP）──────────────────────────────────► emit(ceid)（不受限流约束）
                                                      │
emit：CEID 被禁用？→ 丢弃（不施加效果）
      → applyPlanEffects(ceid)   ← 所有启用计划里 ceid 相同者的 effects，按计划顺序逐个 apply
      → 发布 s6f11 通知
      → 组装报文体（此时读到的已经是新值）
      → 带 W 位则进 ACK 队列 / 否则直接发
```

由此推出几条现场规则：

- **同一条 S6F11 的报文体已经反映本次效果**（先 apply、后 build）。
- 触发路径被挡下（计划禁用 / CEID 被 S2F37 禁用 / 限流拒绝 / 链路不可发）时，**根本走不到 `emit`，也就不会施加任何效果**。
- 多条计划共用同一个 CEID 时，**所有启用计划的效果都会被施加**，顺序为编排表里的计划顺序、计划内 `effects` 数组顺序。
- 效果在发送**之前**落库：即便随后 W 位 ACK 队列满、发送抛异常，值的变化已经生效。
- 手动触发与定时触发走同一入口，效果同样生效；区别只是手动触发绕过限流。

### 3.4 组合与衰减算法

`EffectValueSource.valueAt` 的实际算法（按施加顺序叠加，**后施加的覆盖先施加的**）：

```text
v = base
for e in store.activeAt(svid, t):          // 按施加顺序
    SET(no decay)     : v = value
    SET(decay > 0)    : p = min(1, (t - t0) / decay)
                        v = value + (v - value) * p     // 起点 value，终点"本效果施加前的组合值"
    ADD / SHIFT       : v = v + delta
    PULSE             : v = value                        // 注意是绝对值，不是加值
    RESUME            : 不出现在活动列表里
```

衰减的终点是"施加本效果之前的组合值"，不是裸基线：先 `shift +10`、再 `set 300 decay=1s`，1 秒后回到 110，而不是 100（`EventEffectTest#setDecaysBackToComposedBase` 固化了这个语义）。

### 3.5 "活动"的判定

`EventEffectStore.activeAt(svid, t)` 只保留在 `t` 时刻仍有效的条目：

| op | 活动条件 |
|---|---|
| `set`（无衰减） | `t ≥ t0`，永久 |
| `set`（有衰减） | `t0 ≤ t < t0 + decayMs` |
| `pulse` | `t0 ≤ t < t0 + holdMs` |
| `add` / `shift` | `t ≥ t0`，直到 `resume`/挤出/热重载 |
| `resume` | 施加时清队列，本身不作为条目参与求值 |

失效是**读取时计算**的，没有定时器线程、没有后台清理。因此历史求值（Trace 补点、预览回放）也能得到与当时一致的结果。

> 边界：`decayMs = 0` 合法但效果在 `t = t0` 已不活动，等价于"没生效"；需要"立即写入"请省略 `decayMs`。

### 3.6 存储、容量与生命周期

- 每个 SVID 一个 `ArrayDeque<Applied>`，上限 **64 条**，超出时**丢弃最旧**的（FIFO）。所以一个长期 `shift` 在被 64 条更新的效果挤出后会突然消失——这是刻意的内存上界。
- `resume` 清空该 SVID 的队列；`EventEffectStore.clear()` 在热重载时整体清空（旧模型的效果不能跨配置残留）。
- 效果记录**不落盘**：进程重启、会话重建、配置热重载都会清空。
- 钉值同样只在内存里，热重载时 `EffectValueSource` 被整体替换，`pinned` 随之丢失。
- 对"有定义但基线没有值"的 SVID，效果不会凭空产生值：`valueAt` 在基线为空时直接返回空（钉值是唯一例外）。

## 4. 钉值层：「设定」

`PUT /sessions/{id}/svids/{svid}/value` → `pinSvidValue`：

- 要求 SVID 在当前模型中存在，否则 404；body 不是 `{"value": <number>}` 时 400；
- 值原样保存，`valueAt` 第一优先级返回它——**曲线、模板、事件效果全部被遮住**；
- `DELETE` 同一路径 → `unpin`，立刻回到"基线 + 活动效果"；
- 热重载（含配置重载）后自动解除；
- 目录 JSON 的 `pinned` 标志来自后端真值（`SessionJsonViews.catalogJson(..., effectValues::isPinned)`），刷新不丢。

## 5. 闭环：值 → 条件 → 事件 → 值

事件心跳固定 **100ms**（`EventTicker` + `EventScheduler.EVENT_TICK_MS`）。每个 tick 的顺序：

1. `pumpConditions(now)`：对所有 `onCondition` 计划求值条件表达式；
2. 时间触发：`schedule.between(from, now)`（左开右闭，周期抖动按 `(planId, k)` 确定性派生）；
3. `pumpPending(now)`：到期的 `afterEvent` 链，允许同 tick 级联，**最多 64 跳**防环。

条件表达式的变量：

| 变量 | 取值 |
|---|---|
| `t` | 引擎时钟秒数 |
| `svid.<id>` | **经效果层修正后**的当前值（`EventScheduler.conditionVariable` → `this.values`） |

去抖语义（`ConditionDebouncer`）：条件必须**连续**成立 `debounceMs` 才触发一次；期间变 false 计时归零；触发后保持成立不重复触发，直到再次 false→true。

由此形成闭环：`值 →(OnCondition)→ CEID/S6F11 → effects → 值`，可以编排"温度越限 → 报警事件 → 参数突变"。代价是可能自激：效果改变值后让同一条件重新成立。

> 当前编译期只校验条件变量是 `t` 或存在的 `svid.<id>`（`UNKNOWN_CONDITION_VARIABLE` / `UNKNOWN_CONDITION_SVID`），**没有**检测"条件引用了本计划 effects 写入的 SVID"的自激告警。需要按场景自行设置 `debounceMs`，或用 `afterEvent` 拉开时间。

## 6. 读取出口：同一份值，不同的包装

所有出口都从同一个 `ValueSource` 读，因此效果与钉值天然一致：

| 出口 | 取值规则 | 未知 / 缺失 |
|---|---|---|
| S1F3 → S1F4 | 按请求顺序与个数逐个取值；空列表 = 模型中**全部 SVID 按 ID 升序** | 未定义 SVID → `U4 0`；有定义但无值 → 按声明类型编码 `0` |
| S6F11 | 报告内按 VID 顺序取值；本地报告若所属单元定义了 `UNIT_ID` 槽位，会把它顶到首位；主机自定义报告不注入 | 报告引用了未知 SVID → `U4 0` |
| S2F23 → S6F1 | 采样点严格落在 `t0 + k×DSPER`，逐点用同一值来源补算 | S2F23 初始化时未知 SVID → `TIAACK=4`；`>10` 个 → `1`；`DSPER < 1s` → `3` |
| 会话预览（`GET /preview`） | 分桶 min/max/avg，桶内按 `bucketMs/4` 采样；见 §6.1 | 只列 `values.svids()` 里有的 SVID；最多 32 条 |
| 草稿预览（`POST /config-preview`） | 按草稿编译出的 `ValueSources` 计算；见 §6.1 | 编译失败直接返回 `null` |
| S1F11 → S1F12 | 名字/单位，不取值 | 未定义 → 名字 `UNKNOWN`、单位空串（行数不能少） |

编码规则（`Secs2Mapper`）：

- 按 SVID **声明类型**编码：U1..U8/I1..I8 走 `(long) value` 截断，F4 走 `(float)`，F8 原样，ASCII 整数值不带小数点，BOOLEAN 为 `value != 0`，BINARY 取低 8 位；
- **不四舍五入、不按 `min/max` 裁剪**；
- 类型必须与声明一致：主机按自己的表解析，类型错了不会报错，只会得到垃圾值。

### 6.1 两种预览的差别（容易踩）

| | 会话预览 `GET /preview` | 草稿预览 `POST /config-preview` |
|---|---|---|
| 值来源 | 运行时 `EffectValueSource`（含**钉值**与**真实事件效果**） | 草稿编译出的基线 `ValueSources` |
| `withEvents=true` | 丢弃运行时效果层，取 `EffectValueSource.base()`，用 `EventSimulator` 从 `max(0, from-2h)` 开始在独立效果库上重放，曲线与事件标记都来自这次模拟 | 只调用 `EventSimulator.simulate(...)` 生成**事件标记**；曲线仍用基线 `values` |
| 结果 | 事件引起的跳变可见；**不包含**钉值、手动触发产生的效果 | 事件标记位置正确，但**曲线不会因 effects 跳变**——`RuntimeContext.configPreviewJson` 只把模拟结果用于标记，曲线仍用基线 `values`，与重设计文档"effects 叠加"的目标不一致 |
| 计划范围 | `scheduler.previewSchedule()`：含运行时增删改、排除禁用计划 | 草稿里的全部计划 |
| 上限 | 1000 个事件、32 条序列、500 桶 | 同左 |

另外两点：

- 预览回放的历史窗口最多向前 2 小时（`MAX_HISTORY_MS`），否则长期运行进程会越来越慢；更早施加、但仍活动的 `shift` 在窗口内可能看不到"施加前"的基线。
- `EventSimulator` 把缺失 SVID 的条件变量当 `0.0`，运行时则抛 `ExpressionException`（编译期已挡掉未知 SVID，所以只在绕过校验的运行时编辑场景出现差异）。

## 7. 运行时改写与热重载

| 动作 | 对值的影响 | 是否落盘 |
|---|---|---|
| 运行时新增 SVID | 绑一条**常量 0** 曲线（`RuntimeCatalog.addSvid`），可用「设定」给值；模型立即可见 | 否 |
| 运行时更新 SVID（名字/归属/类型/单位） | 只换定义，**值来源不动**；类型变化会影响下一次编码 | 否 |
| 运行时删除 SVID | 从模型移除并 `store.unbind`；此后 S1F3 视为未知 → `U4 0` | 否 |
| 运行时增删改报告/事件/计划 | 只影响 S6F11 结构与效果集合，不直接改值 | 否 |
| 「设定」/取消设定 | 见 §4 | 否 |
| 热重载（`session` 段未变） | 整体替换设备模型、编排、限流、W 位、值来源；**清空效果记录**、**丢失钉值**；Trace 重建，S6F11 组装器换模型引用；主机连接不断 | 是（来自配置） |
| 重建（`session` 段变化：id/端口/绑定地址/deviceId/超时） | 关旧开新，所有内存态（效果、钉值、运行时编辑）归零 | 是 |

热重载判定只看 `session` 段的规范 JSON 指纹（`SessionLauncher.structureFingerprint`）；`equipment` / `events` / `templates` 的任何变化都走不断连热生效。

## 8. 校验规则与错误码

### 8.1 编译期（`SessionConfig.compile()` + `TemplateConfig` + `EquipmentCompiler`）

| 错误码 | 触发条件 |
|---|---|
| `UNKNOWN_CEID` | 计划的 `ceid` 不在设备模型里 |
| `UNKNOWN_EFFECT_SVID` | 效果的 `svid` 不存在 |
| `EFFECT_TYPE_MISMATCH` | 非数值 SVID 上用了除 `resume` 或 `set` 0/1 之外的 op（BOOLEAN 只允许 `set 0/1`） |
| `SOURCE_TYPE_MISMATCH` | 非数值 SVID 配了 `curve` / `template` 直配 |
| `UNKNOWN_CONDITION_VARIABLE` | 条件变量不是 `t` / `svid.<id>`，或 id 解析失败 |
| `UNKNOWN_CONDITION_SVID` | 条件引用的 SVID 不存在 |
| `SELF_REFERENCING_TRIGGER` | `afterEvent` 的来源指向自己 |
| `UNKNOWN_SOURCE_PLAN` | `afterEvent` 的来源计划不存在 |
| `DUPLICATE_SVID` / `UNKNOWN_OUTPUT` / `DUPLICATE_INSTANCE` / `CYCLE` | 模板图构建失败，带 `$.templates...` / `$.equipment` 路径 |
| `UNKNOWN_SVID_REF` / `UNKNOWN_REPORT_REF` / `DUPLICATE_SLOT` / `DUPLICATE_OFFSET` / `STRIDE_TOO_SMALL_SVID` / `RESERVED_ID` / `MISSING_UNIT_ID_SVID` | 设备模型问题 |

### 8.2 运行期

- `EventEffect` 构造函数：字段缺失/多余、`holdMs ≤ 0`、`decayMs < 0`、非有限数值 → `IllegalArgumentException`（HTTP 400）。
- `DirectSource`：`curve` 与 `template` 必须二选一；`output/params` 只能配 `template`。
- 运行时新增/修改计划除构造期校验外，只额外校验 CEID 存在（`requireKnownCeid`），**不校验 effect 的 SVID 存在性与类型**——绕过编辑器直接调 API 时，写错 SVID 的效果会静默无效（基线为空时效果不产生值）。这是当前实现的宽松处。
- 运行时新增 SVID 要求 ID 不重复；报告/事件编辑当场校验引用存在。

## 9. 确定性边界与已知取舍

**可确定性重放**（给定 seed 与配置）：

- 默认曲线、模板 DAG、曲线直配；
- periodic / at / onCondition / afterEvent 的触发时刻（周期抖动由 `(planId, k)` 派生）；
- 事件效果（只要触发时刻可计算）——草稿预览与运行时预览的重放都依赖这一点。

**不可重放**（只存在于运行时）：

- 手动触发（HTTP `emitCeid`）产生的效果：没有对应的"时间函数"，预览无法复现；
- 「设定」钉值；
- 效果被挤出（64 条 FIFO）造成的状态差异，取决于实际触发历史；
- S2F37 主机禁用状态（预览不读取它）、运行时 SVID 增删改。

因此：**预览是"配置的确定性影子"，不是运行时录像**。

其他值得记住的边界：

1. 效果与模板输出都不按 `min/max` 裁剪。
2. `pulse` / `set` 是绝对值，`shift` / `add` 是相对值；`pulse` 会覆盖先前叠加。
3. `decayMs=0` 的 `set` 实际不生效。
4. `shift` 理论永久，但会被 64 条上限挤出。
5. S2F23 的 `DSPER ≥ 1s`：短于 1 秒的 `pulse`（如 500ms）在 Trace 采样里可能整段被跳过——取决于采样点是否落在窗口内。
6. 运行时新增的 SVID 只有常量 0 基线；不「设定」的话主机一直读到 0。
7. 草稿预览的曲线不含 effects 跳变（§6.1），别用它验证"事件驱动曲线"；要看跳变用会话预览 `GET /preview?withEvents=true`。
8. `injectUnitId` 目前只参与编译期校验；运行期组包只判断"该单元的 SVID 表里有没有 `UNIT_ID` 槽位"，`injectUnitId=false` 并不会阻止注入。

## 10. 代码地图

| 关注点 | 文件 |
|---|---|
| 值抽象 / 曲线来源 | [ValueSource.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/value/ValueSource.java)、[CurveValueSource.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/value/CurveValueSource.java)、[ValueStore.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/value/ValueStore.java) |
| 模板图求值与单写者 | [TemplateGraph.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/template/TemplateGraph.java)、[GraphValueSource.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/value/GraphValueSource.java) |
| 默认曲线 | [DefaultCurves.java](../secs-app/src/main/java/com/shimizukenta/secssim/app/session/DefaultCurves.java) |
| 效果定义与语义 | [EventEffect.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/event/EventEffect.java)、[EventPlan.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/event/EventPlan.java) |
| 效果施加 / 求值 / 存储 | [EventReporter.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/EventReporter.java)、[EffectValueSource.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/EffectValueSource.java)、[EventEffectStore.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/EventEffectStore.java) |
| 事件调度 / 条件 / 链 | [EventScheduler.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/EventScheduler.java)、[Trigger.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/event/Trigger.java)、[ConditionDebouncer.java](../secs-core/src/main/java/com/shimizukenta/secssim/core/event/ConditionDebouncer.java) |
| 值来源选择 / 编译校验 | [ValueSources.java](../secs-app/src/main/java/com/shimizukenta/secssim/app/config/ValueSources.java)、[SessionConfig.java](../secs-app/src/main/java/com/shimizukenta/secssim/app/config/SessionConfig.java)、[TemplateConfig.java](../secs-app/src/main/java/com/shimizukenta/secssim/app/config/TemplateConfig.java) |
| 读取出口与编码 | [SvidReader.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/SvidReader.java)、[S6F11Assembler.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/S6F11Assembler.java)、[Secs2Mapper.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/Secs2Mapper.java)、[TraceManager.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/TraceManager.java) |
| 预览与模拟 | [PreviewBuilder.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/PreviewBuilder.java)、[PreviewSupport.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/PreviewSupport.java)、[EventSimulator.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/EventSimulator.java) |
| 钉值 / 运行时编辑 / 热重载 | [RuntimeEditsFacade.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/RuntimeEditsFacade.java)、[RuntimeCatalog.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/RuntimeCatalog.java)、[HotReloader.java](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/HotReloader.java) |
| 效果语义测试 | [EventEffectTest.java](../secs-runtime/src/test/java/com/shimizukenta/secssim/runtime/session/EventEffectTest.java) |

## 11. 配置样例（最小可运行）

```json
{
  "schemaVersion": 6,
  "session": { "id": "pri-01", "passive": true, "port": 5000, "seed": 20240601 },
  "equipment": {
    "name": "PRI-01",
    "idSpace": {},
    "units": [
      {
        "unitId": 1,
        "name": "CH1",
        "svids": [
          { "offset": 1, "slot": "UNIT_ID", "type": "U4" },
          { "offset": 2, "slot": "TEMP", "type": "F4", "unit": "C", "min": 0, "max": 300 },
          { "offset": 3, "slot": "HEATER", "type": "F4" }
        ],
        "reports": [ { "offset": 1, "slot": "STATUS", "svids": ["TEMP", "HEATER"] } ],
        "ceids": [ { "offset": 1, "slot": "TEMP_HIGH", "reports": ["STATUS"] } ]
      }
    ]
  },
  "events": {
    "plans": [
      {
        "id": "heat-up",
        "ceid": 2001,
        "trigger": { "type": "onCondition", "expression": "svid.1002 > 200", "debounceMs": 1000 },
        "effects": [
          { "svid": 1003, "op": "pulse", "value": 85, "holdMs": 5000 },
          { "svid": 1002, "op": "shift", "delta": -15 }
        ]
      }
    ]
  }
}
```

## 附：规则速查

- 值 = `钉值 → 效果(基线) → 0`，越靠前优先级越高。
- 一个 SVID 只能有一个基线生产者；直配与 instances 也受同一单写者约束。
- 事件效果只在 `emit(S6F11)` 时施加，先于报文组装；计划/CEID 被禁、被限流、链路不可发则不施加。
- `set`/`pulse` 绝对值，`shift`/`add` 相对值，`resume` 清空，衰减终点是"施加前的组合值"。
- 效果每 SVID 保留 64 条、读取时判活、热重载清空、不落盘。
- 钉值压过一切，热重载消失，`resume` 清不掉。
- 基线纯函数：`seed + t` 决定默认曲线与模板图；S1F3/S6F11/S6F1/预览共用同一值来源。
- 未知 SVID 在协议出口回 `U4 0`；有定义但无值按声明类型回 0；不裁剪、不取整。
- 预览是配置的确定性影子：手动触发与钉值不可重放；草稿预览只画事件标记，不叠加 effects 曲线。
