# 配置格式 Schema v5

Schema v5 = Schema v4 + `templates` 段。设备、ID 空间、事件编排的写法不变，见
[Schema v4](schema-v4.md)；本文件只描述新增的模板/曲线配置。

读取器同时接受 v4 与 v5：v4 配置没有 `templates` 时仍走默认曲线；v5 配置有
`templates` 时由模板 DAG 驱动值引擎。

## 示例

```json
{
  "schemaVersion": 5,
  "session": { "id": "pri-01", "passive": true, "port": 5000, "seed": 20240601 },
  "equipment": {
    "name": "PRI-01",
    "idSpace": {},
    "units": [
      {
        "unitId": 1,
        "name": "CH1",
        "injectUnitId": false,
        "svids": [
          { "offset": 1, "slot": "TEMP_PV", "type": "F4" },
          { "offset": 2, "slot": "TEMP_SV", "type": "F4" },
          { "offset": 3, "slot": "HEATER", "type": "F4" }
        ],
        "reports": [],
        "ceids": []
      }
    ]
  },
  "templates": {
    "curves": [
      {
        "id": "temp-wave",
        "type": "sine",
        "min": 20,
        "max": 200,
        "periodMs": 60000
      }
    ],
    "composites": [
      {
        "id": "temp-derived",
        "outputs": {
          "SV": "in.wave.V * 0.9 + 10",
          "HEATER": "select(in.wave.V > 180, 1, 0)"
        }
      }
    ],
    "instances": [
      { "id": "wave", "template": "temp-wave", "svids": { "V": 1001 } },
      {
        "id": "derived",
        "template": "temp-derived",
        "svids": { "SV": 1002, "HEATER": 1003 },
        "params": { "target": 180 }
      }
    ]
  }
}
```

## 曲线类型

| type | 字段 | 说明 |
|---|---|---|
| `constant` | `value` | 固定值 |
| `ramp` | `from` / `to` / `durationMs` / `cyclic` | 线性斜坡 |
| `sine` | `min` / `max` / `periodMs` / `phaseMs` | 正弦波 |
| `step` | `steps[]`（`fromMs` / `value`） | 阶跃序列 |
| `pwl` | `points[]`（`tMs` / `value`） | 关键帧折线 |
| `uniform` | `min` / `max` / `resampleMs` | 区间随机 |
| `randomWalk` | `start` / `driftPerSec` / `sigma` / `gridMs` / `octaves` / `min` / `max` / `policy` | 有界随机游走，`policy` 为 `CLAMP` / `REFLECT` / `WRAP` |

曲线模板只有一个输出 `V`。

## 组合模板

`composites[].outputs` 是“输出名 → 表达式”。表达式规则见
[ADR 0004](adr/0004-template-dag.md)，变量：

- `t`：当前秒数
- `p.名字`：实例参数
- `in.实例.输出`：其它实例的输出
- 比较运算返回 0/1，可直接给 `select`

## 实例绑定

- `template`：曲线或组合模板的 `id`。
- `svids`：输出名 → 绝对 SVID。一个输出只能绑一个 SVID，一个 SVID 也只能有一个生产者。
- `params`：可选，写入参数链的最高层（`UNIT`）。

构建时校验：

1. 模板 id 不重复；
2. 实例引用的模板存在；
3. 输出名属于该模板；
4. SVID 在设备模型里存在；
5. 无环、无单写者冲突；
6. 事件计划（`events.plans[].ceid`）引用的 CEID 在设备模型里存在（`UNKNOWN_CEID`）。

任一失败都会在启动或编辑器「校验」时以 `$.templates...` / `$.equipment` 路径报错。

## 与默认曲线的关系

- 有 `templates`：由模板 DAG 提供值；未绑定到任何实例的 SVID 返回 0。
- 没有 `templates`：仍按 v4 的默认曲线（有界随机游走）生成。
- 模板输出**不会**按 SVID 声明的 `min/max` 裁剪：值域只影响默认曲线。曲线本身写越界，主机就会收到越界值。

因此 v4 配置无需修改即可继续运行。

## 界面里的结构化编辑

配置编辑器的「模板」区域把上述结构投影成表单：曲线参数、step/pwl 关键帧、
组合模板输出表达式、实例的 `svids` 与 `params` 都能直接改。

- 表单只读**当前原文解析出的 JSON**；原文不合法或 `templates` 形状不对时会禁用并报路径。
- 改动写回整份配置文本：未知键保留，但缩进/空行会统一成 2 空格。
- 改模板 `id` 会同步实例的 `template`；改实例 `id` 会同步表达式里的 `in.<实例>.`；
  删除仍被实例引用的模板会被拒绝。
- 表单不绕过校验：保存前仍走 `ConfigStore.validate` 的同一条启动路径。

## equipment.constants（设备常量 / ECV）

可选段。声明设备常量供主机用 S2F13/F14 查询、S2F15/F16 写入。

```json
"constants": [
  { "id": 501, "name": "TEMP_LIMIT", "type": "F4", "value": 5.0, "unit": "C" },
  { "id": 502, "name": "BATCH_SIZE", "type": "U2", "value": 100 }
]
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 绝对 ECID（≥1，同一设备内唯一） |
| `name` | 是 | 名称，S2F14 里不带出，仅用于配置可读性 |
| `type` | 是 | `U1/U2/U4/U8/I1/I2/I4/I8/F4/F8` |
| `value` | 是 | 初始值；整型类型要求是整数且不越界 |
| `unit` | 否 | 单位，仅用于配置可读性 |

**没有这段的旧配置照常工作**：S2F13 回空列表（或对指定 ECID 回 `U4 0`），S2F15 回 `EAC=1`。
因此不需要升级 `schemaVersion`。

**写入语义**（S2F15）：主机写入后立即覆盖配置文件里的 `equipment.constants` 段并原子替换文件。
其余段（session / 单元 / 模板 / 编排）、`schemaVersion` 与手写的未知键都保持不变；
唯一的变化是整份文件的缩进/空行会统一成 2 空格。
EAC 取值：`0` 全部接受、`1` 有未知 ECID、`2` 值越界或形状非法、
`3` 未 ONLINE。**整批不生效**，不做部分写入。

## Schema v6 增补（2026-10）

Schema v6 = v5 + 两类**可选**字段：事件效果（`events.plans[].effects`）与
SVID 直配值来源（`svids[].curve | svids[].template`）。v4/v5 配置零改动即是
合法 v6；`SCHEMA_VERSION=6`、`MIN_SCHEMA_VERSION=4` 不变。

### 事件效果 effects

`events.plans[]` 可选 `effects` 数组——**以事件为中心**声明"这个 CEID 触发时,
哪些 SVID 的值怎么变"。手动触发（HTTP）、定时/条件/链式触发统一生效；
效果在组装 S6F11 **之前**施加,因此事件报文里带的就是变化后的值。

| op | 必填字段 | 可选字段 | 语义 |
|---|---|---|---|
| `set` | `value` | `decayMs` | 改写为 value;带 decayMs 时线性衰减回"施加前的组合值" |
| `add` / `shift` | `delta` | — | 相对基线抬升/下降,持续生效直到 resume 或热重载 |
| `pulse` | `value`、`holdMs` | — | 瞬时跳变,保持 holdMs 后自动回落 |
| `resume` | —（且**不允许**带任何字段） | — | 清除该 SVID 的全部活动效果 |

约束（编译期校验,与「校验」按钮同一条路）：

- `svid` 必须在设备模型里存在（`UNKNOWN_EFFECT_SVID`）；
- 数值类型支持全部 op;BOOLEAN 只支持 `set`(0/1) 与 `resume`；
  ASCII/BINARY 只支持 `resume`（`EFFECT_TYPE_MISMATCH`）；
- 多余字段一律拒绝（写错键名不会静默无效）。

运行时值 = 基线曲线（模板图或默认曲线）+ 活动效果按施加顺序叠加。
手动触发产生的效果只存在于运行时内存,**无法被预览重放**——
预览是"配置的确定性影子"（下一节）。

### SVID 直配值来源

`equipment.units[].svids[]` 与 `equipment.globalSvids[]` 的每个 SVID 可选配：

```jsonc
{ "offset": 1, "slot": "TEMP_PV", "type": "F4", "min": 0, "max": 400,
  "curve":   { "type": "randomWalk", "start": 180, "sigma": 2.5, "min": 0, "max": 400 } }
// 或
{ "offset": 2, "slot": "PRESS", "type": "F4",
  "template": "chamber-press", "output": "V", "params": { "base": 1.2 } }
```

- `curve` 与 `template` **二选一**（同写报错）；`output`/`params` 只能搭配 `template`；
- 曲线对象的形状与 `templates.curves[]` 完全一致（去掉 id）；
- **单写者规则**：同一 SVID 在 `templates.instances` 绑定与直配中只能出现一次——
  直配在编译期合成为匿名模板实例（id `@svid:<n>` / `@curve:<n>`）,
  冲突由模板图统一报出；
- 直配引用的模板必须存在；组合模板多输出时必须用 `output` 指定；
- 非数值类型（ASCII/BOOLEAN/BINARY）不能直配（`SOURCE_TYPE_MISMATCH`）。

### 值来源的整体语义

- 配置里**没有任何**自定义值来源（templates 段为空且无直配）→ 全部走
  `DefaultCurves` 默认曲线（数值有界随机游走、UNIT_ID 常量、BOOLEAN 常量 1）；
- 有**任意一个**（templates 或直配）→ 模板图驱动,未绑定值来源的 SVID 取 0
  （与 v5 模板语义一致）。

### 预览的事件标记（确定性影子）

`GET /api/v1/preview` 与 `POST /api/v1/config-preview` 返回体新增：

- `events: [{at, ceid, planId}]`——窗口内的确定性事件时间线
  （periodic/at 按种子重放、onCondition 按 tick 求值、afterEvent 链），
  且**模拟出的效果已叠加进曲线**：草稿状态下就能看到"事件导致跳变"；
- 查询参数 `svids=401,402`（只画这几条）、`withEvents=false`（关闭标记）。

### 内置默认设备配置

`--work-dir` 首启播种两份开箱即用的 v6 配置（幂等不覆盖,删掉重启即恢复）：

- `demo-etch.json`——双腔刻蚀机：effects（pulse/set+decay/shift/BOOLEAN set）、
  OnCondition 闭环（超温→停加热）、每 SVID 直配曲线/模板混用;
- `demo-cvd.json`——CVD：`templates.instances` 组合模板 + 参数、
  一对适合做 S1F3 采集启停的 CEID（2001/2002）。

`--demo-session` 直接开这两台（端口取 `--hsms-port` 与 +1,deviceId 错开）。

### 主机模拟（运行时能力,不占 schema）

- `POST /api/v1/sessions/{id}/host-sim` 请求体可选
  `{"target": {"host": "..", "port": 5000, "deviceId": 0}}`——缺省连本机,
  给了连远程 SECS（远程无 catalog,SVID/CEID 全手填）；
- 采集器：`PUT/POST /api/v1/sessions/{id}/host-sim/collector`（配置
  startCeid/stopCeid/intervalMs/svids）、`.../collector/start|stop`（手动）、
  `GET .../collector`（状态 + series 数据,按应答项格式码解码,零元数据可画图）。
