# 配置格式 Schema v7

> 当前版本。**不兼容 v6 及更早**：没有迁移器，旧文件会被拒绝（`schemaVersion` 必须为 7）。
> 旧模型里的 `templates` / `instances` / `events[].effects` 已整体删除。

## 一句话模型

**三类数据，各自一份值定义，事件只读不改。**

```text
晶圆属性 Context  ──按片变化（LOTID / WAFERID / RCPID / PPID / BATCHID）
设备常量 ECID     ──常量，或随时间 / 周期变化
参数变量 SVID     ──随时间变化，按片重复
        │
        └── 每个数据项一份 source；时间是唯一的自变量（相位 phase 或绝对 t）

事件（CEID）触发 → 组装报告（RPT）→ 报文里读这一刻的值
S1F3 到达        → 读这一刻的值
```

事件、S1F3、S6F1、Trace、预览读的是**同一个**值函数，谁也不写回。

## 顶层骨架

```jsonc
{
  "schemaVersion": 7,
  "session":   { "id": "etch-01", "passive": true, "bindHost": "0.0.0.0",
                 "port": 15730, "deviceId": 10, "seed": 20260225 },
  "context":   [ /* 晶圆属性 */ ],
  "equipment": { "name": "...", "idSpace": {…}, "constants": […], "globalSvids": […], "units": […] },
  "cycle":     { /* wafer 周期（可空） */ },
  "events":    { "maxPerSec": 50, "plans": [ /* 周期外的异常路径 */ ] }
}
```

| 段 | 必填 | 说明 |
|---|---|---|
| `schemaVersion` | 是 | 固定 `7` |
| `session` | 是 | 会话、端口、Device ID、随机种子 |
| `equipment` | 是 | 单元、变量、常量、报告、CEID |
| `context` | 否 | 晶圆属性；报告里用 `ctx.<名>` 引用 |
| `cycle` | 否 | 没有这一段＝不按片重复，曲线按绝对时间走 |
| `events` | 否 | 手写事件计划；周期内事件写在 `cycle.events` |

## 一、三类数据

### 1.1 晶圆属性（`context`）

```jsonc
"context": [
  { "id": "LOTID",   "type": "ASCII", "source": { "mode": "cycle", "pattern": "LOT{02}", "start": 1, "every": 25 } },
  { "id": "WAFERID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "W{03}",   "start": 1 } },
  { "id": "RCPID",   "type": "ASCII", "source": { "mode": "cycle", "list": ["ETCH-A1", "ETCH-B2"], "every": 25 } },
  { "id": "PPID",    "type": "ASCII", "source": { "mode": "const", "value": "PP-ETCH-01" } }
]
```

- `id` 是报文里 `ctx.<id>` 引用的名字，同段内唯一。
- `type` 可以是 `ASCII` 或数值型；`BINARY` 拒绝（报文里没有可读语义）。
- 属性值按**片号**取值，不随片内时间变化——同一片里读多少次都一样。

### 1.2 设备常量（`equipment.constants[]`）

```jsonc
"constants": [
  { "id": 501, "name": "TEMP_LIMIT", "type": "F4", "value": 240, "unit": "C" },
  { "id": 502, "name": "PUMP_RPM",   "type": "U4", "value": 1800, "unit": "rpm",
    "source": { "mode": "curve", "clock": "phase",
                "curve": { "type": "ramp", "from": 0, "to": 3000, "durationMs": 30000 } } }
]
```

- 没配 `source`：常量就是 `value`，S2F15 可写。
- 配了 `source`：取值随时间变化，**S2F15 拒写**（回 `EAC=1`）——写死一个下一秒又变的值没有意义。
- 两种形态对 `S2F13/S2F14` 都返回“此刻”的值。

### 1.3 参数变量（`equipment.units[].svids[]` / `globalSvids[]`）

```jsonc
{ "offset": 1, "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 400,
  "source": { "mode": "curve", "clock": "phase",
              "curve": { "type": "randomWalk", "start": 180, "sigma": 2.5, "min": 0, "max": 400 } } }
```

- `offset` 是段内偏移，绝对 SVID = 段起点 + offset；`globalSvids` 用绝对编号。
- `min`/`max` 只用于显示与 `DefaultCurves` 的默认值域；**值定义不会被裁剪到 min/max**，
  真正的硬约束是类型宽度。
- `BOOLEAN` 可以配值定义（走 0/1 数值管道）；`ASCII`/`BINARY` 不行。

## 二、`source` 的五种形状

```jsonc
{ "mode": "const",  "value": 180 }                                        // 常量
{ "mode": "curve",  "clock": "phase", "curve": {…}, "transform": "…" }    // 曲线（可选表达式变换）
{ "mode": "expr",   "clock": "phase", "vars": {…}, "expr": "a * 0.9" }    // 私有曲线 + 表达式
{ "mode": "cycle",  "start": 1, "step": 2, "every": 10 }                  // 按片号：数值
{ "mode": "cycle",  "pattern": "LOT{02}", "start": 1, "every": 25 }       // 按片号：文本（仅 context）
```

### 时钟 `clock`

| 值 | 自变量 | 用途 |
|---|---|---|
| `phase`（默认） | 片内相位 `[0, duration)` | 参数变量、按片重复的常量 |
| `absolute` | 绝对毫秒 `t` | 只跑一次的慢漂移（不需要每片重来） |
| `cycle` | 片号 | 等价于 `mode:"cycle"` |

没有 `cycle` 段时相位＝绝对时刻，`phase` 与 `absolute` 等价。

### 曲线类型

`constant` / `ramp` / `sine` / `step` / `pwl` / `uniform` / `randomWalk` / `sequence`。
字段与旧版一致（`CurveJson`），例如：

- `{"type":"sine","min":0.2,"max":2.0,"periodMs":30000}`
- `{"type":"step","steps":[{"fromMs":5000,"value":1},{"fromMs":55000,"value":0}]}`
- `{"type":"sequence","segments":[{"durationMs":20000,"curve":{…}}, …],"cyclic":false}`

### 表达式（`mode:"expr"`）

变量只有两个来源：`t`（秒）与 `vars` 里的名字（**本项私有曲线**）。
运算：四则、比较、以及 `clamp / lerp / min / max / abs / roundTo / sum / avg / select / sin / floor / ceil`。

```jsonc
{ "mode": "expr", "clock": "phase",
  "vars": { "base": { "type": "constant", "value": 1.2 },
            "amp":  { "type": "constant", "value": 0.4 },
            "periodSec": { "type": "constant", "value": 45 } },
  "expr": "clamp(base + amp * sin(6.283185307 * t / periodSec), 0, 10)" }
```

`vars` **不是实例**：名字只在这个 source 里可见，不产生 SVID，也没有全局绑定表。
库里原来是“组合模板”的东西，复制进设备时就折叠成这个形状。

### `transform`

`curve` 模式可带一段只含 `V` 的表达式，用来做单位换算或取整：

```jsonc
{ "mode": "curve", "curve": { "type": "randomWalk", … }, "transform": "roundTo(V, 0.1)" }
```

### 按片号 `mode:"cycle"`

- 数值：`value_i = start + step × ⌊(i - 1) / every⌋`（`step` 缺省 0，即恒定）。
- 文本：`pattern` 用 `{02}`/`{03}` 补零，`every` 每几片进一档；`list` 按 `every` 轮换。

## 三、周期（`cycle`）

```jsonc
"cycle": {
  "durationMs": 60000,          // 标准片长，>= 1000
  "jitterMs": 500,              // 每片 ±误差，必须 < durationMs
  "count": 25,                  // 片数，1..10000
  "noisePerWafer": false,       // 每片是否用不同随机噪声
  "events": [
    { "atMs": 3000,  "ceid": 2001 },
    { "atMs": 30000, "ceid": 2003 },
    { "atMs": 55000, "ceid": 2002 }
  ]
}
```

```text
duration_i  = clamp(T + U(-J, +J))       // 由 seed 派生，可复现
start(i)    = Σ_{k<i} duration_k
phase(t)    = t - start(index(t))
事件时刻     = start(i) + atMs            // 抖动只改片长，不移动片内偏移
```

- 片长抖动**不缩放事件位置**：工艺步的相对位置是刚性的，会漂的是节拍。
- `noisePerWafer: true` 时随机曲线按片号派生子种子（同一片内仍可复现）。
- 周期事件在**构建期按片展开**成普通时间触发计划（`count × events` 条），
  所以限流、W 位、预览沿用同一套实现。
- `atMs ∈ [0, durationMs)`，且不得大于 `durationMs - jitterMs`
  （否则最短的那一片会吞掉这个事件）。

## 四、事件与报告

### 事件（`events.plans[]`）

```jsonc
{ "id": "pl-overheat", "ceid": 2004,
  "trigger": { "type": "onCondition", "expression": "svid.1001 > 240", "debounceMs": 5000 },
  "actions": [ { "kind": "s6f11" } ],
  "offlinePolicy": "skip", "onSendFailure": "keep" }
```

触发类型：`at`（绝对时刻，通常由 `cycle` 展开生成）、`periodic`、`afterEvent`、`onCondition`。
手动触发走运行区接口，不需要在配置里声明。**没有 `effects`**：事件不改变任何值。

### 报告（`units[].reports[]`）

`svids` 是**字段序列**，顺序即报文顺序，三种前缀：

```jsonc
{ "offset": 1, "slot": "STATUS",
  "svids": ["UNIT_ID", "TEMP_PV", "PRESS", "ctx.LOTID", "ctx.WAFERID", "ecid.501"] }
```

| 写法 | 含义 |
|---|---|
| `TEMP_PV` | 本单元（或全局）的 SVID 槽位名 |
| `ecid.501` | 设备常量编号 |
| `ctx.LOTID` | 晶圆属性名 |

`injectUnitId: true` 的单元会把 `UNIT_ID` 顶到首位。主机用 S2F33/S2F35 自定义报告时只有编号，
没有属性/常量；这时报告按纯 SVID 序列组包。

## 五、校验清单（编译期拦下，不留到运行期）

| 代码 | 条件 |
|---|---|
| `UNKNOWN_CEID` | 计划或周期事件引用的 CEID 不存在 |
| `UNKNOWN_SVID_REF` | 报告引用的槽位名不存在 |
| `UNKNOWN_ECID_REF` | 报告的 `ecid.<编号>` 不存在或不是编号 |
| `UNKNOWN_CONTEXT_REF` | 报告的 `ctx.<名>` 没定义 |
| `UNKNOWN_SOURCE_SVID` / `UNKNOWN_SOURCE_ECID` | 值定义挂在不存在的数据项上 |
| `SOURCE_TYPE_MISMATCH` | 给 `ASCII`/`BINARY` 变量配了数值型值定义 |
| `DUPLICATE_CONTEXT` | 晶圆属性重名 |
| `UNKNOWN_CONDITION_SVID` | `onCondition` 里的 `svid.<id>` 不存在 |
| `UNKNOWN_SOURCE_PLAN` / `SELF_REFERENCING_TRIGGER` | `afterEvent` 指向不存在的计划或自己 |

解析期（`SessionConfigJson.parse`）只报语法/字段路径错误，语义留给编译期——
编辑器的「校验」与启动走同一道门。

## 六、和 v6 的差异

| v6 | v7 |
|---|---|
| `templates.curves / composites / instances` | 删除；一个数据项一份 `source` |
| `svids[].curve` / `template` / `output` / `params` | 删除；写成 `source` |
| `events.plans[].effects` | 删除；事件只读 |
| 值 = pinned > effects > 基线 | 值 = 设定（调试）> `source` |
| 曲线用绝对 `t` | 有 `cycle` 时用片内相位 |
| `schemaVersion` 4..6 | 只接受 7 |
