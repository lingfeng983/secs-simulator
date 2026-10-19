# ADR 0011 · 事件编排的运行时启用/禁用

- 状态：已接受
- 日期：M12
- 影响范围：`secs-runtime` 的 `HsmsSessionRuntime`，`secs-api-http` 的会话命令，前端「事件编排」面板

## 问题

配置里的 `events.plans` 是启动时编译进 `EventSchedule` 的。现场经常需要：

- 临时停掉某条周期事件，避免刷屏；
- 排查某个条件触发时先禁用其它计划；
- 不重启进程、不改配置文件。

如果只能改配置再重载，重载会换掉设备实例并断通信，代价远大于"停一条计划"。

## 决策

### 1. 禁用状态是运行时状态，不写回配置

`HsmsSessionRuntime` 维护一个 `disabledPlans` 集合。它只影响当前进程，
不修改 JSON、不修改 `EventPlan`，也不改变会话的配置来源。

重载会重新编译配置并调用 `enableSchedule`，后者清空禁用集——
**重载后恢复配置里的启用状态**，这是可预期的。

### 2. 所有触发路径都必须检查禁用

事件有三个入口：

- 时间区间 `EventSchedule.between`（At / Periodic）
- 每 tick 的 `OnCondition`
- `AfterEvent` 延迟队列

只在 `between` 里过滤会留下两个洞：条件触发仍会发，延迟队列仍会补发。
因此 `firePlan` 统一兜底，`pumpConditions` 提前 reset 去抖，
`recordFire` 不再给禁用的 `AfterEvent` 目标入队。

### 3. 禁用时清理已排队/去抖状态

- 删除该计划的条件去抖器；
- 从 `pendingFires` 删除该计划的延迟事件。

**禁用期间的事件不补发。** 重新启用从当前时刻继续，不把禁用窗口内
"本该发生"的周期事件一次性倒出来。

### 4. API

- `GET /api/v1/sessions/{id}/events`：viewer，列出计划 id / CEID / 触发规则 / 启用状态；
- `POST /api/v1/sessions/{id}/events/{planId}?enabled=true|false`：operator。

会话不存在与计划不存在都回 404，但错误码分开：
`unknown-session` / `unknown-plan`。现场需要知道是哪个名字打错了。

### 5. 不提供"新增/删除计划"

新增计划涉及配置编译与 DAG 校验，应该走配置编辑器 + 重载；
这里只做"临时开关"，避免出现两份编排定义。

## 后果

- 现场可以临时停一条计划而不中断通信。
- 禁用状态不会持久化；重启/重载后回到配置状态。
- 前端每 2 秒轮询一次列表；切换后立即再拉一次，避免按钮状态滞后。
- 触发规则在 JSON 里是扁平字段（`triggerType + 参数`），
  不是嵌套对象——HTTP 层用 `JsonHubBuilder` 拼对象时更直接。
