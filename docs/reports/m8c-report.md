# M8-c 验收报告 · SSE 报文推送 + 报文面板

## 交付

| 层 | 内容 |
|---|---|
| `secs-app/event` | `MessageEvent`（会话/方向/流/功能/W 位/报文体/时刻）+ `EventBus`（订阅者异常不影响发布者） |
| `secs-runtime` | 会话在**接收**与**发送**两侧发布事件；只发数据报文（`stream ≥ 0`），过滤 HSMS 控制帧 |
| `secs-api-http` | SSE 订阅者队列（容量 256，满则丢最旧）+ `event: message` 帧 |
| `frontend` | 报文面板：时间 / 会话 / 方向 / SxFy W / 报文体（截断 120 字 + 悬停全文），可清空 |

## 四个设计决定

| 决定 | 理由 |
|---|---|
| 事件总线放在 `secs-app`，不放在 HTTP 层 | 会话不该认识 SSE；日志、测试、将来的录制模块都订阅同一个总线 |
| 只发布 `stream ≥ 0` 的报文 | HSMS 的 select/deselect 等控制帧对使用者无意义，混进面板只是噪音 |
| 订阅者队列满时**丢最旧** | 界面宁可少几条，也不能把内存拖爆或反压到会话 |
| 报文体截断到 2000 字符 | 一条大报文（长 SVID 列表）不该把 SSE 通道撑爆 |

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **146 个**（core 79 + app 16 + api-http 14 + runtime 12 + cli 25），全绿 |
| 前端 | 重建后 148 KB（新增报文面板） |
| 原生镜像 | `secs-sim.exe` 13.0 MB，原生端到端 2 个用例 1.8 s |

### 用例

| 用例 | 断言 |
|---|---|
| `SessionMessageBusTest` | 主机发 S1F11 → 总线上出现 `in S1F11` 与 `out S1F12`；且没有任何 `stream < 0` 的事件 |
| `SseMessageTest` | 向总线发布事件后，SSE 订阅者收到 `event: message` 帧，且含 `stream`/`function`/`direction` 字段 |

## 已知边界

- 报文体的 `body` 目前是 SECS-II 的 **JSON** 表示（`Secs2.toJson()`），不是 SML。
  要做 SML 文本需要自己写渲染器（协议层没提供），留给 M8-d。
- 面板只保留最近 200 条（前端内存上限），更早的报文需要落盘（`messages.jsonl`）才有意义。

## 下一步（M8-d）

| 目标 | 需要的后端能力 |
|---|---|
| SML 文本渲染 | 写一个 `Secs2 → SML` 渲染器（纯函数，可在 core 里测） |
| 曲线预览 | 值引擎批算 + 分桶降采样 + Float32 二进制接口 |
| 模板画布 | 模板图 CRUD + React Flow |
