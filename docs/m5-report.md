# M5 验收报告 · Trace（S2F23 / S2F24 + S6F1）

## 交付

| 组件 | 内容 |
|---|---|
| `core.trace.TraceSpec` | 一条 Trace 的定义与边界校验（DSPER ≥ 1s、1 ≤ REPGSZ ≤ TOTSMP） |
| `core.trace.TracePlan` | 纯计算：第 k 个样本时刻 = `t0 + k × DSPER`、报文分组、`hhmmss` ↔ 毫秒 |
| `runtime.TraceManager` | S2F23 校验与状态机、按采样时刻取值、S6F1 组包、TOTSMP=0 终止 |
| 会话 | 路由 S2F23 → S2F24；100ms 心跳采样并按 `REPGSZ` 发 S6F1；`pumpNow()` 供确定性步进 |

## 规范符合性

| 规范点 | 实现 |
|---|---|
| S2F23 五个元素 | `TRID / DSPER(A[6]) / TOTSMP / REPGSZ / L[SVID]`，少于 5 个按非法周期处理 |
| DSPER 是 `hhmmss` 文本 | 严格 6 位数字、mm/ss ≤ 59、允许 hh > 23（现场确有超长采样） |
| TOTSMP=0 表示终止 | 先判断 0，再解析 DSPER —— 否则主机用 `000000` 终止时会被误判成"周期非法" |
| REPGSZ 分组 | 每 `REPGSZ` 个样本一条 S6F1，最后一条只含剩余样本 |
| SMPLN | 本条报文中**最后一个**样本的样本号（1 起） |
| SV 列表 | 扁平展开，样本序 × SVID 序，**不带 SVID 号**（主机按 S2F23 顺序解释） |
| STIME | 16 位 `yyyyMMddHHmmssSS`，由"墙钟锚点 + 仿真时刻"算出，因此可复现 |
| S2F24 | `<B TIAACK>`，码值 0/1/2/3/4/5 |

### TIAACK 码

| 码 | 含义 | 触发条件 |
|---|---|---|
| 0 | OK | 接受（或成功终止） |
| 1 | Too many SVIDs | SVID 数 > 10 |
| 2 | No more traces | 已有一条 Trace，又来一条不同的 TRID |
| 3 | Invalid period | DSPER 非法 / 结构不合法 |
| 4 | Unknown SVID | SVID 未在设备模型中定义 |
| 5 | Invalid REPGSZ | REPGSZ < 1 或 > TOTSMP |

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **113 个**（core 79 + app 10 + runtime 12 + cli 12），全绿 |
| 原生镜像 | `secs-sim.exe` **13.0 MB**，原生端到端 2 个用例 1.0 s |

### 真链路证据（`SessionTraceTest`）

用手动时钟 + `pumpNow()` 精确推进，断言的是"第 k 个样本发生在 t0 + k × DSPER"，不是靠 sleep 赌时序。

| 用例 | 断言 |
|---|---|
| DSPER=1s / TOTSMP=3 / REPGSZ=1 | t=0 无报文；t=1s 一条 S6F1，SMPLN=1，2 个值（1 样本 × 2 SVID），取值恰为引擎在 t=1s 的值；随后 SMPLN=2、3；播完 `activeTraces()==0` |
| REPGSZ=2 / TOTSMP=3 | 采到 1 个样本不发；采到第 2 个发一条（4 个值、SMPLN=2）；第 3 个发一条（2 个值、SMPLN=3） |
| TOTSMP=0 | 立即终止，且此后不再上报 |
| 非法请求 | 未知 SVID → 4；已有 Trace 再来一条 → 2；DSPER=`0000` → 3 |

## 已知边界

- **S6F1 不带 W 位**：多数主机不回 S6F2，因此默认不等 ACK；若现场要求应答，需要补单飞与 T3 处理。
- **只允许 1 条 Trace**：多数真机如此；多 Trace 只需把 `MAX_TRACES` 调大并给 `TRID` 加去重。
- **S6F1 采样与主机时钟的偏差**：STIME 用的是"墙钟锚点 + 仿真时刻"，不是采样瞬间的真实墙钟；
  这是为了可复现（否则预览与回放的时间戳会漂）。
