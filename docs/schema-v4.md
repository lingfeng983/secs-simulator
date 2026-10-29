# 配置格式 Schema v4

> **Schema v5 已在 v4 基础上增加 `templates`（曲线/模板 DAG）。**
> v4 配置仍被接受；需要把 SVID 绑定到曲线时见 [Schema v5](schema-v5.md)。

一份配置描述"一台设备 + 若干单元 + 事件编排"。

```json
{
  "schemaVersion": 4,
  "session": {
    "id": "pri-01",
    "passive": true,
    "bindHost": "127.0.0.1",
    "port": 5000,
    "deviceId": 100,
    "seed": 20240601
  },
  "equipment": {
    "name": "PRI-01",
    "idSpace": {
      "svidBase": 1000, "ceidBase": 2000, "reportBase": 3000,
      "svidStride": 100, "ceidStride": 100, "reportStride": 100,
      "reserve": [[1, 999]]
    },
    "globalSvids": [
      { "id": 9001, "name": "FACILITY_TEMP", "type": "F4", "min": 18, "max": 26 }
    ],
    "globalCeids": [
      { "id": 1001, "name": "CONTROL_STATE_CHANGE", "reports": ["CH1.STATUS"] }
    ],
    "units": [
      {
        "unitId": 1,
        "name": "CH1",
        "injectUnitId": true,
        "svids": [
          { "offset": 1,  "slot": "TEMP_PV", "type": "F4", "unit": "C", "decimals": 2, "min": 0, "max": 400 },
          { "offset": 2,  "slot": "PRESS",   "type": "F4", "unit": "Torr", "decimals": 3, "min": 0, "max": 10 },
          { "offset": 90, "slot": "UNIT_ID", "type": "U4" }
        ],
        "reports": [
          { "offset": 1, "slot": "STATUS", "svids": ["UNIT_ID", "TEMP_PV", "PRESS"] }
        ],
        "ceids": [
          { "offset": 1, "slot": "PROCESS_START", "reports": ["STATUS"] }
        ]
      }
    ]
  },
  "events": {
    "maxPerSec": 20,
    "s6f11Wbit": true,
    "s6f1Wbit": true,
    "plans": [
      { "id": "start", "ceid": 2001,
        "trigger": { "type": "periodic", "periodMs": 5000, "jitterMs": 200 } },
      { "id": "once", "ceid": 2101,
        "trigger": { "type": "at", "atMs": 1500 } },
      { "id": "hot", "ceid": 2102,
        "trigger": { "type": "onCondition", "expression": "svid.1001 > 190", "debounceMs": 1000 } },
      { "id": "follow", "ceid": 2103,
        "trigger": { "type": "afterEvent", "sourcePlan": "hot", "delayMs": 2000 } }
    ]
  }
}
```

## 关键概念

| 概念 | 说明 |
|---|---|
| `offset` | 单元内的**段内偏移**。绝对 ID = 段起点 + offset，段起点 = base + (单元位置-1) × stride |
| 单元位置 vs `unitId` | 位置决定号段（1、2、3…），`unitId` 是写给主机看的语义编号，可以跳号 |
| `injectUnitId` | 为真时，若报告里没有 `UNIT_ID`，组装 S6F11 时会自动加到报告首位。**默认 `true`**；开启时必须在该单元定义 `UNIT_ID` 槽位，否则编译报 `MISSING_UNIT_ID_SVID` |
| `globalSvids` / `globalCeids` | 设备级共享项，不属于任何单元；全局 CEID 引用跨单元报告要写 `单元名.报告名` |
| `globalSvids.min/max` | 可选值域（仅数值型）。不配时默认曲线退化为 0..100 的有界随机游走；BOOLEAN 固定 1，ASCII/BINARY 固定 0 |
| `events.s6f11Wbit` | 可选，默认 `true`。为 `true` 时 S6F11 带 W 位并等待 S6F12；发送走单线程队列，避免 T3 阻塞事件心跳 |
| `events.s6f1Wbit` | 可选，默认 `true`。为 `true` 时 S6F1 带 W 位并等待 S6F2；发送走独立单线程队列，不阻塞 Trace 采样 |
| `events.plans[].trigger.type` | `at` / `periodic` / `onCondition` / `afterEvent`。`onCondition` 额外字段：`expression`（支持 `t` 与 `svid.<id>`，比较运算返回 0/1）、`debounceMs`（条件连续成立多久才触发，默认 0）；`afterEvent` 额外字段：`sourcePlan`（源计划 id）、`delayMs`（延迟毫秒，默认 0） |
| `events.plans[].ceid` | **必须**在设备模型里有定义（单元 `ceids` 或 `globalCeids`）。不存在时配置校验报 `UNKNOWN_CEID`，不会等到运行期才失败 |
| `session` 缺省值 | `passive` 默认 `true`、`bindHost` 默认 `127.0.0.1`、`port` 默认 `0`（表示用 `--hsms-port`，不是随机端口）、`deviceId` 默认 `10`、`seed` 默认 `0` |
| `reserve` | 禁止占用的号段（默认 1..999 留给标准消息） |
| `session.timeouts` | 可选。HSMS-SS 超时（秒，>0）：`{"t3":60,"t7":15}`；没写的键用协议层默认（T3=45/T5=10/T6=5/T7=10/T8=6）。改超时属于会话结构变化，重载时走重建（主机会断连） |

## 错误定位

解析错误会带字段路径：

```
配置错误: 未知数据类型: FLOAT（字段: $.equipment.units[0].svids[0].type）
```

语义错误（重复 ID、段重叠、跨单元引用等）由编译器一次性报全，两者不混在一起：
**语法错立刻报，语义错批量报。**

## 两条容易踩的坑

1. **BOM**：用记事本或 `Set-Content -Encoding utf8` 保存会带 UTF-8 BOM。
   解析器会剥掉它，但建议编辑器保存为"UTF-8 无 BOM"。
2. **段步长**：某单元内最大的 `offset` 必须小于 `stride`，否则相邻单元号段重叠，编译期会报
   `STRIDE_TOO_SMALL_SVID`。
