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
5. 无环、无单写者冲突。

任一失败都会在启动时以 `$.templates...` 路径报错。

## 与默认曲线的关系

- 有 `templates`：由模板 DAG 提供值；未绑定到任何实例的 SVID 返回 0。
- 没有 `templates`：仍按 v4 的默认曲线（有界随机游走）生成。

因此 v4 配置无需修改即可继续运行。

## 界面里的结构化编辑

配置编辑器的「模板」区域把上述结构投影成表单：曲线参数、step/pwl 关键帧、
组合模板输出表达式、实例的 `svids` 与 `params` 都能直接改。

- 表单只读**当前原文解析出的 JSON**；原文不合法或 `templates` 形状不对时会禁用并报路径。
- 改动写回整份配置文本：未知键保留，但缩进/空行会统一成 2 空格。
- 改模板 `id` 会同步实例的 `template`；改实例 `id` 会同步表达式里的 `in.<实例>.`；
  删除仍被实例引用的模板会被拒绝。
- 表单不绕过校验：保存前仍走 `ConfigStore.validate` 的同一条启动路径。
