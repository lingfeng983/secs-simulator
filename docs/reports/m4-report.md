# M4 验收报告 · S6F11 事件编排

（M4 分两段：本文只覆盖 S6F11，配置 JSON 在下一段。）

## 交付

| 组件 | 内容 |
|---|---|
| `event.Trigger` | 密封接口 + `At`（一次性）+ `Periodic`（周期 + 确定性抖动） |
| `event.EventPlan` / `EventSchedule` | 计划表；`between(from, to)` 区间查询（左开右闭，不重不漏） |
| `event.RateLimiter` | 滑动窗口限流，挂在**设备级** |
| `runtime.S6F11Assembler` | E30 报文体组装：CEID + 报告列表 + 值列表；`UNIT_ID` 缺省时自动注入到报告首位 |
| 会话 | `emitCeid(int)` 手动上报；`enableSchedule(...)` 自动上报（100ms 心跳）；设备级限流；无主机在线时不积压 |
| 演示 | `--demo-session` 现在会每 5 秒（±200ms 抖动）上报 CEID 2001 |

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **92 个**（core 73 + runtime 12 + cli 7），全绿 |
| 原生镜像 | `secs-sim.exe` **12.9 MB**，原生端到端 2 个用例 0.8 s |

### S6F11 结构证据（`SessionS6F11Test`）

| 用例 | 断言 |
|---|---|
| CEID 2001（报告 3001 `STATUS`） | 报文体 `L[2]`；CEID=2001；1 个报告；RPTID=3001；3 个值；值[0]=单元号 1；值[1]=值引擎当前温度 |
| CEID 2003（报告 3002 `MEASURE`，未含 UNIT_ID） | 值列表从 2 个变成 3 个，首位是自动注入的单元号 |
| CEID 9999（不存在） | 抛 `IOException`，且 `emittedCount` 不变 |

## 定时器为什么不是定时器

`Trigger` 只有"区间查询"一个方法：

```java
List<Long> fireTimes(long fromMs, long toMs, Rng rng, String planId);
```

好处是三条真实的仿真需求同时满足：可回放（抖动由 `(planId, k)` 派生）、
可加速（不依赖真实时间）、可批算（预览能一次算完一小时）。
细节见 [ADR 0005](../adr/0005-event-orchestration.md)。

## 已知边界

- **条件触发**未实现（`OnCondition`）。需要先定义"条件持续成立时如何去抖"。
- **事件链**（A 之后 2 秒发 B）未实现，需要把 `lastFireMs` 扩展为"已发生事件集合"。
- **S6F12 应答**未处理：当前 `S6F11` 不带 W 位；若主机要求应答，需要加单飞与 T3 处理。
- **配置落盘**未做：编排仍然是代码里构造的。
