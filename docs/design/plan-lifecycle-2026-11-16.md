# 计划生命周期重设计（2026-11-16）

> 来源：事件计划后端全面审计（2026-11-16 会话）+ 四点需求澄清。
> 范围：设备侧事件编排（EventPlan）与工艺周期（Cycle）的生命周期、离线语义、
> 以及两者的逻辑拆分（§1–§4）；§5 追加值生命周期关联——参数变化（SVID 曲线 /
> 设备常量 / 晶圆属性）随周期状态机启停。不影响主机模拟侧的采集计划（3.1.0 S1F3）。

## 0. 需求原文与澄清结论

| # | 需求原文 | 澄清结论 |
|---|---------|---------|
| 1 | 热重载区分：相关计划停止、无关计划继续；连接参数变化/客户端重连一律全停；刚启动的设备全停需手动启动；手动启动时间=计划初始时间；周期性计划点击启动立即触发 | 相关性判定**放弃**（见决策 D1）：任何配置变化一律全停；At 基准=相对启动时刻（D2） |
| 2 | OFFLINE 时无法启动计划；ONLINE→OFFLINE 转移所有计划停止；参数变化也停止 | "参数变化"并入全停事件清单（§2.1），具体范围见 D6 |
| 3 | 不进行重跑/补跑；发送失败直接丢弃；清理 W 位的记录 | 删除 offlinePolicy=queue 与整条补跑链路；W 位失败后台账记 FAILED（D7） |
| 4 | 周期性计划和自定义计划是两套逻辑 | "周期性计划"= **工艺周期（cycle）**，不是事件编排的 Periodic 触发。cycle 与事件编排彻底拆成两套逻辑：后台两套运行时 + 页面两块展示 |

### 决策记录

| # | 决策 | 理由 |
|---|------|------|
| D1 | 热重载/参数变化不做相关性判定，一律全停 | 相关性判定要 diff 配置段与计划引用图，成本高、边界模糊（模板算不算相关？）；全停语义最简单可靠，操作员重开一次即可 |
| D2 | At 的时间基准 = 相对启动时刻（启动后 atMs 毫秒触发） | 与"手动启动时间=初始时间"统一；绝对时刻语义在"启动晚了永远错过"上正是本次要修的痛点 |
| D3 | offlinePolicy 收敛为 `skip` / `fail`，删除 `queue` | 需求 3 明确不补跑；queue 及其离线队列、flushPending、溢出台账整体删除 |
| D4 | 计划启用态**不再跨重启/重建回放**；runtime_plan_state 只留统计 | "刚启动的设备所有计划都是停止的"；断开重连（不重建会话）时内存态保留 |
| D5 | cycle 触发也走 PlanExecutor 台账，plan_id 固定 `cycle` | 台账证据统一出口；cycle 不进 runtime_plan_state（它不是"计划"，没有启停粒度到片的必要） |
| D6 | 全停事件清单含：主机 S2F15 写设备常量、界面钉值/取消钉值 | "参数变化一律全停"的字面覆盖；若实际使用太激进可后续收窄（改动点集中在 §2.1 清单一处） |
| D7 | W 位发送失败 → 该动作台账 FAILED + 计划 last_error；不做异步行级修正 | 台账行在入队时刻已提交，异步修正要引入第二行；FAILED 一条 + last_error 已够排障 |
| D8 | 离线/限流丢弃**不产生台账行** | 用户方向是"丢弃"而非"留证"；可观测性由计划页状态列（waiting/offline）与 skipped/rejected 计数承担 |

---

## 1. 现状与差距

| 现状 | 问题 |
|------|------|
| `enableSchedule` 全禁用 + `restoreRuntimeEdits` 回放启用态 | 重启/热重载行为互相矛盾：热重载全停但 override 行残留（重启复活）；重启又恢复启用态——与"刚启动全停"都不一致 |
| Periodic/At 锚点 = 仿真时钟零点 | 启动时刻不影响相位；启动晚了第一次触发相位错乱、At 直接错过 |
| cycle 构建期展开成 `cyc<w>-<i>` 的 At 计划混进同一张编排表 | 计划列表塞满展开计划；cycle 无法整体启停；锚点同样在时钟零点，启动晚了前面的片全部错过 |
| OFFLINE 清场但启用态保留，上线自动继续 | 需求：OFFLINE 是"停止"，要手动重新启动 |
| `setPlanEnabledV2` 不检查控制状态 | OFFLINE 时也能点启动（实际不发，状态 waiting） |
| offlinePolicy=queue + flushPending/replayPending | 补跑绕过设备级限流；补跑在状态迁移线程同步发送；补跑不级联 AfterEvent |
| W 位动作入 ACK 队列即记 SENT | 真实失败（T3）只进日志，台账永远 SENT |

## 2. 目标设计

### 2.1 统一生命周期规则（事件编排）

**全停事件清单**——以下任一事件发生，所有事件编排计划与工艺周期一律回到 stopped（清启用态、清去抖进度、清延迟链、cycle 回第 0 片待启动）：

1. 会话新建 / 进程重启（含 `--config` 启动、接口创建）
2. 配置重建（连接参数变化，关旧开新）
3. **热重载**（任何配置变化，不做相关性判定，见 D1）
4. 控制状态离开 ONLINE（S1F15、界面置离线、**链路断开回落**——现状断链已自动回 OFFLINE，复用该转移钩子）
5. 主机客户端断开重连（链路相位脱离 SELECTED）
6. 参数变化：W 位（s6f11Wbit/s6f1Wbit）、限流 maxPerSec、主机 S2F15 写设备常量、界面钉值/取消钉值（见 D6）

**启动规则**（单条计划 / cycle 整体）：

- 前置：链路 SELECTED **且** 控制状态 ONLINE。不满足 → 后端拒绝（409，error=`not-online`），UI 禁用按钮。
- 启动时刻 `enabledAt = clock.now()` 即该计划的**初始时间**：
  - `Periodic`：**立即触发一次**（过限流、走台账），此后第 k 次（k≥1）在 `enabledAt + k×period ± 抖动`；抖动仍按 `(planId, k)` 确定性派生；
  - `At`：在 `enabledAt + atMs` 触发一次；
  - `OnCondition`：去抖计时从 `enabledAt` 起算（条件已成立也须连续满足 debounceMs）；
  - `AfterEvent`：不变（依赖源计划触发）。
- 停止：清该计划去抖状态与延迟链条目（现状语义保留）。

**实现要点**：

- `Trigger` 增加带锚点的求值入口：`fireTimes(fromMs, toMs, rng, planId, anchorMs)`；
  `At`/`Periodic` 按 anchor 平移，`OnCondition`/`AfterEvent` 仍返回空。旧 3 参方法委托
  `anchor=0`，供 `EventSchedule.between`（纯区间语义）与旧测试保持不变；
  `EventScheduler` 的 pump 路径改传 per-plan `enabledAt`（`Map<String,Long>`，setEventEnabled 时写入）。
- "立即触发一次"在 `setEventEnabled(planId, true)` 里直接 `firePlan(planId, ceid, now, now)`
  （前置条件已保证 ONLINE，且过限流、落台账）。
- 全停钩子：`EventScheduler.stopAll()`（disabledPlans 全收 + 清去抖/延迟链 + lastFireMs 归位）；
  挂点：`changeControlState(next != ONLINE)`、`SessionLifecycle` 链路相位脱离 SELECTED 回调、
  `applyHot`（现状 enableSchedule 已全停，补齐钉值/S2F15/W 位/限流变化的挂点）。
- `Bootstrap.restoreRuntimeEdits` **删除计划启用态回放段**（D4）；计划正文覆盖/目录编辑/钉值回放保留
  （钉值回放不触发计划——回放发生在会话注册时，计划本来就全是停止态）。
- `runtime_plan_state.next_fire_at` 开始写入：Periodic 计划 = `enabledAt + k×period` 的下一次；
  非 Periodic 不写。计划页倒计时列随之生效（顺带清掉审计遗留的"无写者"问题）。

### 2.2 OFFLINE 门控

- 后端：`setPlanEnabledV2` / `setEventEnabled`（enabled=true 方向）在
  `!communicatable || controlState != ONLINE` 时返回 `EventOutcome.NOT_ONLINE` → HTTP 409
  `{"ok":false,"error":"not-online"}`。禁用（false 方向）不受限。
- cycle 启动 API 同一门控。
- UI：设备非 ONLINE 时启动按钮禁用，tooltip「设备离线：先上线再启动」；状态列继续用
  `stopped / waiting / running` 语义（waiting 保留给"启用了但 S2F37 屏蔽"的场景）。

### 2.3 去补跑与发送失败语义（需求 3）

- **删除**：`PlanExecutor.pending` 离线队列、`flushPending`、`EventReporter.replayPending`、
  `HsmsSessionRuntime.changeControlState` 里的补跑挂点、queue 溢出台账、
  `PlanExecutorTest` 对应用例。
- `offlinePolicy`：配置解析保留 `queue` 值但按 `skip` 处理并记一条 Issue（迁移提示）；
  UI 下拉去掉 queue 选项。语义变为：
  - 设备不在线（调度门控清场）：静默丢弃，不记台账（D8），计划状态列 `waiting/offline`；
  - 发送失败：该动作记 FAILED，**不重试、不补发**。
- W 位：ACK 队列任务失败（T3/中断/队列满）→ `sink.recordFire(planId, false, error)` +
  台账补一条 `FAILED`（新幂等键后缀 `|ack|seq`，D7）；链路关闭时 ACK 线程
  `shutdownNow` 清空挂起任务（现状已有，文档化确认）。
- 补跑删除后，审计 P1-4（补跑绕限流）自然消失；PlanExecutor 的 `Dependencies.online` 只剩
  manual/step 两条入口可达，注释同步收敛。

### 2.4 工艺周期与事件编排拆分（需求 4）

**概念分界**：工艺周期回答"一片 wafer 的处理流程按什么节拍重复"；事件编排回答
"哪些异常路径在什么规则下上报"。两者只有两个交点：都发 S6F11（经同一 CEID 目录与
S2F37 门控）、都受设备级限流约束。

**后台（两套运行时，一套出口）**：

```
EventTicker(100ms) → pumpNow
  ├─ EventScheduler.pumpEvents      事件编排：条件求值 / 带锚点区间触发 / 延迟链
  ├─ CycleRunner.pump               工艺周期：片推进 + 片内偏移事件
  └─ 共用：SessionRateGate(设备级限流) → EventReporter.emit → PlanExecutor(台账)
```

- 新组件 `CycleRunner`（runtime/session）：
  - 持有 `CycleConfig` 与 `CycleClock`，**锚点 = 启动时刻**（替换现在的时钟零点）；
  - 状态机 `STOPPED → RUNNING →（跑满 count 片）FINISHED`；停止后再启动从第 0 片重来；
  - 每 tick 推进片边界，片内到期事件 `emit(ceid, "cycle")`——过限流与 S2F37，落台账
    （plan_id=`cycle`，D5）；
  - 运行态 JSON：`{state, startedAt, waferIndex, phaseMs, totalWafers, firedCount}`；
  - 全停事件（§2.1 清单）同样作用于 cycle：回 STOPPED 待手动启动。
- `SessionConfig.effectiveSchedule()` 拆开：events 段计划进 `EventScheduler`；
  cycle **不再展开混入**。`CycleConfig.expandPlans` 保留给预览合成用。
- `SessionJsonViews.eventsJson` / `plansV2Json` 不再出现 `cyc*` 计划；
  cycle 信息走独立端点。
- 预览：`PreviewBuilder` 的事件时间线 = `EventSimulator(编排计划, 锚=窗口起点)` ∪
  `CycleRunner.simulate(from, to)`（按"如果现在启动"展开片时刻）。

**页面（一能力一页）**：

- 新设备子页「工艺周期」（`/device/:id/cycle`）：
  - 周期参数卡（durationMs / jitterMs / count / noisePerWafer，编辑走配置草稿）；
  - 片内事件表（atMs → CEID，增删改，写回配置 `cycle.events`）；
  - 运行卡：状态（停止/运行中/已完成）、第几片、片内相位、进度、已发事件数；
    启动/停止按钮（ONLINE 门控同 §2.2）；
  - 周期预览曲线（现有 cyclePreview 逻辑迁移）。
- 「事件编排」子页（现 EventsPanel 拆干净）：
  - 只列手写计划；列表列：id / CEID / 归属 / 触发规则 / 状态 / 启停 / 下次触发倒计时 /
    fireCount / lastError；
  - 启动按钮 ONLINE 门控；周期计划启动后倒计时精确显示（§2.1）；
  - 现有"事件周期预览"按钮与展开计划痕迹移除（预览归 cycle 页）。
- 侧栏/路由：设备子页 9 → 10（cycle 独立成页），`router.ts` 文案同步
  （"工艺周期与事件计划"一句拆两处）。

### 2.5 契约与兼容

| 变化 | 类型 | 说明 |
|------|------|------|
| `events.plans` Periodic/At 语义 | breaking | 锚点时钟零点 → 启动时刻；3.2 版本说明公告 |
| `cycle` 展开锚点 | breaking | 同上；旧配置"启动晚了前几片错过"的行为被修正为"从第 0 片完整跑" |
| `offlinePolicy=queue` | 废弃 | 解析保留、按 skip、配置校验给 Issue；4.0 删除 |
| `POST /devices/{id}/plans/{planId}/enabled` | 409 新增 | error=`not-online` |
| `GET /devices/{id}/plans` | 变化 | 不含 `cyc*` 计划；Periodic 计划新增 `nextFireAt` |
| 新端点 | 新增 | `POST /api/v2/devices/{id}/cycle/start|stop`、`GET /api/v2/devices/{id}/cycle` |
| `restoreRuntimeEdits` | 行为 | 计划启用态不再跨重启回放（统计仍持久化） |

---

## 3. 实施计划

### M1 生命周期规则（后端）
1. `Trigger` 锚点求值入口 + `EventScheduler` per-plan `enabledAt`；
2. `setEventEnabled` 启动即触发一次（Periodic）+ ONLINE 门控（409 not-online）；
3. `stopAll()` 与三处挂点（控制状态转移 / 链路相位 / applyHot）；
   钉值、S2F15 写常量挂全停钩子（D6 清单）；
4. `restoreRuntimeEdits` 删启用态回放；`next_fire_at` 写入；
5. 测试：锚点语义（At 相对/Periodic 立即+周期）、全停钩子矩阵、OFFLINE 拒绝启动。

### M2 去补跑（后端）
1. 删 queue 链路（队列/flush/replay/溢出台账/用例）；offlinePolicy 解析降级 + Issue；
2. W 位 ACK 失败 → 台账 FAILED + last_error（D7）；errorSink 汇合点整理；
3. 测试：离线触发丢弃、W 位失败台账、断链清队列。

### M3 Cycle 后台拆分
1. `CycleRunner`（锚点=启动时刻、状态机、tick 推进、emit/台账/限流接入）；
2. `effectiveSchedule` 拆分；`SessionRateGate` 抽出共享限流；
3. cycle 运行态端点 + 预览合成；
4. 测试：片推进时序（含抖动漂移）、跑满自动 FINISHED、全停作用、台账 plan_id=`cycle`。

### M4 页面拆分（前端）
1. `/device/:id/cycle` 子页（参数卡 + 片内事件表 + 运行卡 + 预览迁移）；
2. EventsPanel 清理（去 cyc* / 去预览按钮 / 倒计时列 / ONLINE 门控按钮）；
3. mock 服务（mock-ui-server.mjs）补 cycle 端点；e2e 两页各补主链路用例。

### M5 契约收尾
openapi.yaml、breaking 变更说明（3.2）、docs/design/todo 清单勾稽。

## 4. 测试清单（关键用例）

- 启动 Periodic：立即一条 → enabledAt+k×period 各一条（手动时钟精确断言）；
- 启动 At（atMs=5000）：启动后 5s 触发；重启后（全停）不再触发；
- ONLINE→OFFLINE：全停；重新 ONLINE：仍是 stopped，手动启动才恢复；
- OFFLINE 下启动请求 → 409 not-online；
- 热重载（改 SVID 曲线 / 改计划正文 / 改 W 位）：三种都是全停；
- cycle：启动锚点=点击时刻；跑满 count 片 FINISHED；S2F37 屏蔽 CEID 时片内事件走 skipped 计数；
- W 位 T3 失败：台账 FAILED、last_error 更新、心跳不被阻塞。

---

## 5. 值生命周期关联（2026-11-16 追加）

> 来源：值变化时机审计（设备常量 ECID / 晶圆属性 Context / 变量 SVID 共用
> DataSource 纯函数，与事件计划完全解耦）。本节把值系统接进 §2.1 的生命周期
> 与 §2.4 的 CycleRunner 状态机——此前值完全不受生命周期控制：设备停止态、
> 跑满之后、热重载前后，曲线照走不误，这正是"参数变化"与"计划"没有关联的根源。

### 5.1 审计结论与本设计的缝合点

| 审计发现（2026-11-16） | 本设计 §1–§4 覆盖情况 | 缝合方式 |
|---|---|---|
| P0 热生效漏换 Context/EcidValues（`dataModel` 只有 open 一处调用，热路径丢弃新编译结果） | §2.1-3 只说热重载全停；M1 要动 `applyHot` | 并入 M1：applyHot 全量传 + 热路径补 `dataModel` |
| cycle.count 耗尽相位无界（`phaseAt` 超出末片时长后继续增长，Sine/RandomWalk 永不停，与 `CycleClock` javadoc「停在最后一片」矛盾） | CycleRunner 有 FINISHED 但只管片内事件，值求值未接 | FINISHED 冻结值相位（§5.2） |
| 变化起点 = 会话启动（EngineClock 原点），主机晚连看到曲线中段；"重看"只能重启会话 | D2 给了计划/周期锚 = 启动时刻；值仍锚时钟零点 | 值相位锚 = 周期启动时刻（D9） |
| EC/Context 无「设定」（pin 仅 SVID） | D6 把钉值/S2F15 写常量列为全停事件（反向影响） | 保留差距，本节不展开 |
| 曲线级变化窗口（startMs/endMs）无法配置 | 无 | 被状态机替代：STOPPED/RUNNING/FINISHED 即窗口 |
| 曲线语义双实现（UI paramTemplateSimulation.ts vs 后端 SourceEvaluator）漂移风险 | M3 预览合成 `CycleRunner.simulate` | 后端预览与实跑同锚（§5.5 M3 追加 3）；UI 客户端双实现收敛属审计 P3，另行推进 |

### 5.2 设计：工艺周期 = 值变化的主时钟

**不建议**按事件计划（At/Periodic）逐计划绑值：§2.4 的概念分界（事件编排管
"异常上报"、周期管"节拍"）是成立的，逐计划绑值会把两个运行时耦合回一张图，
重蹈 v6 模板图覆辙。值是过程物理量，跟节拍走。由此：

```
CycleRunner 状态机                 值表现（PHASE 曲线）
STOPPED（待启动/被全停）          →  冻结在进入停止时的值（待机保持，D10）
RUNNING（startedAt = 点击时刻）   →  phase = now − startedAt，从第 0 片初值起跑
FINISHED（跑满 count 片）         →  冻结在末相位（修掉相位无界）
```

- **实现形态**：`LifecycleCycleClock` 装饰器（volatile 状态 + 锚点；内部持编译期
  `CycleClock` 做片表与抖动），与 `CycleClock` 同接口；`SourceEvaluator` 不动——
  `DataSourceValueSource` / `EcidValues` / `ContextTable` 本来就收 `CycleClock`
  参数，装配处注入装饰器即可。
- **CYCLE 槽**（CycleNumber/CycleText）由 runner 片号驱动：STOPPED = 槽 0、
  FINISHED = 末槽；晶圆属性换档与片内事件天然同步（同一状态机）。
- **ABSOLUTE 曲线**锚 = startedAt：重启周期即重放一次性曲线（D11，breaking）。
- **「设定」层级不变**：pin 覆盖在冻结/运行值之上；D6 已把钉值列为全停事件，
  钉值 → cycle 全停 → 值冻结，语义自洽。
- **时间源不变**：时钟读数仍来自会话 `EngineClock`，装饰器只持有状态与锚点，
  不引入第二条时间线（与 D2 同一套时间观）。
- **全停清单一致性**：热重载重建值来源时，有 cycle 段时钟回 STOPPED、
  无 cycle 段自动 RUNNING（D12），与 D1"全停 + 手动启动"同构；
  §2.1 清单对值与事件同时生效，无需新增挂点。

### 5.3 决策记录（续）

| # | 决策 | 理由 |
|---|------|------|
| D9 | 值相位锚 = 工艺周期启动时刻；启停 cycle 同时驱动值与片内事件 | 与 D2 统一（"手动启动时间=初始时间"对值同样成立）；一个按钮管两边；主机晚连看到完整曲线 |
| D10 | STOPPED 值语义 = 冻结在进入停止时的值（待机保持）；启动瞬间跳到第 0 片初值 | 真实设备待机时过程量保持；与 D2 衔接（启动即初值）。备选"待机即显示第 0 片初值"被否：全停瞬间值跳变，主机侧难排查 |
| D11 | ABSOLUTE 曲线随周期重锚，重启周期即重放 | "重启重放"是演示/压测核心诉求；保留会话原点则"重看"只对 PHASE 成立。breaking，3.2 公告 |
| D12 | 无 cycle 段：passthrough 时钟自动 RUNNING（锚 = 会话启动），行为保持 3.1 | 旧配置零改动；24h 后 FINISHED 冻结，顺带修掉 passthrough 相位无界 |

P0 不设新决策：§2.1-3 已宣称热重载全量生效，`dataModel` 只在 open 调用属实现
遗漏，按 bug 修。

### 5.4 契约追加（并入 §2.5）

| 变化 | 类型 | 说明 |
|------|------|------|
| 值相位锚点（PHASE/CYCLE/ABSOLUTE） | breaking | 时钟零点 → 周期启动时刻；无 cycle 段保持会话启动锚（D12） |
| ABSOLUTE 曲线 | breaking | 周期重启即重放（D11） |
| `GET /api/v2/devices/{id}/cycle` | 字段 | `state` 承载值冻结语义：STOPPED 期间 SVID 读值为冻结值、Trace 采得平线（文档化） |

### 5.5 实施计划合并（⟵ 标注追加项）

**M1 生命周期规则（后端）**——原 1–5 保持，追加：

6. P0 修复：`applyHot` 全量签名（schedule / 限流 / W 位 / model / values /
   **contextTable / ecidValues**），热路径补 `dataModel(...)`；`HotReloader.apply`
   收口。与 §2.1-3 的 stopAll 挂点是同一函数，合并提交；
7. 测试：热重载后改晶圆属性 / EC 值定义，S2F13 与 S6F11 断言新值生效。

**M3 Cycle 后台拆分**——原 1–4（Runner / effectiveSchedule 拆分 / 端点+预览 /
测试）保持，追加（值生命周期主体，内部排序：Runner → 时钟 → 装配 → 预览）：

5. ⟵ `LifecycleCycleClock`（状态 + 锚点装饰器）：状态由 CycleRunner
   start / stop / finish 三处更新；片内事件 emit 与值求值读同一状态；
6. ⟵ 装配线：`prepare()` 构建三套值对象时注入装饰器；热重载重建后时钟回
   STOPPED；无 cycle 段自动 RUNNING（D12）；
7. ⟵ 预览对齐：PreviewBuilder 值曲线与 `CycleRunner.simulate` 同锚
   （草稿窗口 = 模拟时间线，运行窗口 = 当前状态）；
8. ⟵ Trace 语义文档化：STOPPED 期间采样得平线；
9. ⟵ 测试增补（并入 §4）：
   - 启动周期：S1F3 读值 = 第 0 片曲线初值；启动前读值为冻结值；
   - 停止周期：值冻结、Trace 平线；重启：同种子同片同值（复现性）；
   - 跑满 count：FINISHED，Sine / RandomWalk / Ramp(cyclic) 停在末相位（回归审计 P1-1）；
   - 热重载：值来源换新 + 时钟回 STOPPED；
   - 无 cycle 段：自动运行，除 24h 冻结外与 3.1 一致；
   - ABSOLUTE：重启周期后 ramp 重放（D11 断言）。

**M4 页面拆分（前端）**——原 1–3 保持，追加：

4. ⟵ cycle 页运行卡补"值状态"：待启动（冻结）/ 运行中（第 N 片·相位）/ 已完成（已冻结）；
5. ⟵ 变量 / 常量 / 晶圆属性三面板顶部门禁文案：「变化随工艺周期启停」；
6. ⟵ mock-ui-server.mjs 补 cycle 端点状态对值的影响（内置浏览器验证流程不变）。

**M5 契约收尾**——追加：

- ⟵ openapi 补 §5.4 三行；breaking 说明补 D9 / D11。

**顺序与依赖**：M1 追加项独立可先行（与 stopAll 挂点同一函数，合并提交）；
M3 追加项依赖 CycleRunner 主体落地；M2（去补跑）与本节无交集。
