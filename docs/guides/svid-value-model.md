# 数据值变化机制与规则

> **基线**：schema v7（`SessionConfig.SCHEMA_VERSION = 7`）。
> **范围**：只回答"一个值从哪来、什么时候会变、几层规则谁压谁、哪些出口看到的是同一份值"。
> 配置字段写法见 [schema-v7](schema-v7.md)。

## 0. 一句话模型

```text
值(item, t) = 设定[item]  ?  设定值
                        :  该 item 自己的 source 在 t 时刻的取值

source 的自变量由 clock 决定：
  phase    → 片内相位 phase(t) = t - start(index(t))
  absolute → 绝对时刻 t
  cycle    → 片号 index(t)
```

只有两层：**「设定」是调试用的运行态覆盖**（不写配置、热重载即失效），
其余一切由 `source` 唯一决定。没有事件效果层，没有模板图，没有优先级叠加。

## 1. 三类数据，一条规则

| 类别 | 存在哪 | 谁读它 | 变化单位 |
|---|---|---|---|
| 晶圆属性 `context` | 配置顶层 `context[]` | 报告 `ctx.<名>` | 片号（阶跃） |
| 设备常量 `constants` | `equipment.constants[].source` | `S2F13/S2F14`、报告 `ecid.<编号>` | 相位 / 绝对时间（可常量） |
| 参数变量 `svids` | `units[].svids[].source`、`globalSvids[].source` | `S1F3`、`S6F11`、`S6F1`、Trace、预览 | 相位 / 绝对时间 / 片号 |

三者的**规则完全一样**：一个编号一份 `source`，取值是 `t` 的纯函数。
差别只在"谁读"和"该用哪个 clock"。

## 2. 时间只有一条

```text
duration_i  = clamp(T + U(-J, +J))           // 每片长度，seed 派生、可复现
start(i)    = Σ_{k<i} duration_k
index(t)    = max{ i : start(i) ≤ t }
phase(t)    = t - start(index(t))            // 周期边界处归零
```

- 没有 `cycle` 段：`phase(t) = t`（直通），曲线按绝对时间走，等于"只跑一片"。
- 周期重复不是"复位"：`phase` 是时间的纯函数，跨片时自然从头开始，没有任何状态需要清。
- `jitterMs` 只改变片长；事件偏移 `atMs` 是片内相对位置，不随抖动缩放。
- 噪声默认每片相同（便于 A/B 与回归）；`noisePerWafer: true` 时按片号派生子种子。

## 3. 出口一致性

S1F3、S6F11、S6F1、Trace、曲线预览读的都是同一个 `ValueSource`：

```text
DataSourceValueSource        // 编号 → source，纯函数
  → UnitIdValueSource        // UNIT_ID 强制等于所属单元号
  → BoundedValueSource       // 只按声明类型的位宽收紧（不按 min/max 裁剪）
```

因此不会出现"报文变了、曲线没变"。**取值时刻**是各出口自己的时刻：

- S6F11：事件触发那一刻（组装报文时）；
- S1F3：报文到达那一刻；
- Trace：采样那一刻。

同一事件上报的值与主机随后 S1F3 读到的值会有正常的微小时间差——这不是谁改过值。

## 4. 事件只读

事件（CEID）声明"什么时刻、带哪些数据上报"，**不参与值的产生**：

- 配置里没有 `effects`，计划里也没有；
- 触发只做三件事：判可发（在线/启用/限流）→ 组装报文（读当前值）→ 发送；
- 因此同一条 CEID 被多个计划共用时不会互相影响；
- 想让"温度越限连带改别的量"，只能让那个量自己的 `source` 表达这层关系
  （同一个 `expr` 里算出来），不能靠事件去写。

## 5. 「设定」是唯一的运行态写入

运行区可以对某个 SVID「设定」一个值，用于现场调试：

- 只在内存里，不写配置、不进报文语义；
- 热重载、进程重启即失效；
- 它**遮蔽**该变量的 `source`，但仍受类型位宽约束。

除此之外，运行期没有任何写入路径。排查"这个值为什么是 X"只需要看一条 `source`
和当前 `phase`。

## 6. 常见误区

| 误解 | 实际 |
|---|---|
| "事件效果会衰减回基线" | v7 没有效果层；需要跳变就写成 `sequence` 的一段 |
| "曲线跨片会接着走" | `clock:"phase"` 每片从头；要连续累积用 `clock:"absolute"` 或 `cyclic:true` 的 ramp |
| "min/max 会裁剪曲线" | 不裁剪；它们只用于显示与默认值域，硬约束是类型位宽 |
| "模板改动会影响已配置设备" | 从库里复制是深拷贝；设备里存的是展开后的 `source`，没有引用 |
| "没配 source 的变量会拿默认曲线" | 只要有一个变量配了 `source`，整台设备走 `DataSourceValueSource`，没配的出口为 0 |

## 7. 实现位置

| 关注点 | 文件 |
|---|---|
| 周期时钟 | `core/cycle/CycleClock` |
| 值定义模型 | `core/value/DataSource`（const / curve / expr / cycle） |
| 求值 | `core/value/SourceEvaluator` |
| 编号 → 值 | `core/value/DataSourceValueSource` |
| 属性表 | `core/context/ContextTable` |
| 常量取值 | `runtime/session/EcidValues` |
| 报文编码 | `runtime/session/S6F11Assembler`、`Secs2Mapper` |
| 装配 | `app/config/ValueSources` |
