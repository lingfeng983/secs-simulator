# 3.0 未完成任务清单

- 日期：2026-11-13
- 依据：[3.0 重新设计说明书](./redesign-2026-11-12.md)（含三轮修订）、[ADR 0017](../adr/0017-sqlite-config-store.md)
- 已提交里程碑：M0 `d138a56` → M1 `f7aab67` → M2 `8e1f56b` → M3 `f556c12` → M4 `a29fc22`
- **M4 编排执行器已完成并验收全绿**：后端接线、策略字段往返、执行器缺口、v2 台账/试跑/启用态、SSE 节流、前端时间轴与单测均已落地。当前剩余工作从 **§2 M3 遗留** 开始。

---

## 0. 当前状态与恢复须知

### 0.1 M4 交付清单（已完成）

| 文件 | 状态 | 说明 |
|---|---|---|
| `secs-core/.../event/PlanAction.java`（新） | 完成 | 动作记录（kind=s6f11/sml/alarm/terminal/trace） |
| `secs-core/.../event/EventPlan.java` | 完成 | 扩 `actions / offlinePolicy / onSendFailure`，保留 3 参 / 4 参兼容构造 |
| `secs-runtime/.../session/PlanExecutor.java`（新） | 完成 | 幂等预留→离线三策略→效果→逐条发送→台账；queue 补跑 / rollback / 五种动作 / action 级 wbit / W 位 ACK 队列 |
| `secs-runtime/.../session/EventReporter.java` | 完成 | `planExecutor` 钩子、`step()`、`replayPending()`、效果 before→after 证据 |
| `secs-runtime/.../session/HsmsSessionRuntime.java` | 完成 | `planExecutor(...)` 注入、`resolvePlanAction(...)`、ONLINE 触发补跑 |
| `secs-runtime/.../Bootstrap.java` | 完成 | `SessionManager.onRegister` 给每个会话接执行器；ULID 归一；停机 flush |
| `secs-runtime/.../PlanFiredThrottle.java`（新） | 完成 | `plan-fired` 200ms 合并、FAILED 立即推 |
| `secs-runtime/.../session/PlanExecutorTest.java`（新） | 完成 | 17 项：顺序契约/幂等/离线/多动作/回滚/wbit/异步落库 |
| `secs-runtime/.../PlanFiredThrottleTest.java`（新） | 完成 | 4 项：合并窗口/立即推/设备隔离/转义 |

**M4 验收结果**：`mvn -B test` 全绿（runtime 124 / cli 85 等）、前端 `build` + 136 项 Vitest 全绿、`check-openapi.py` 62 path 全绿、`-Pnative verify` 全绿、`check-all.ps1 -SkipE2E` 全绿（按用户要求不跑 Playwright）、mock-ui-server v2 端点冒烟 200。

### 0.2 验收命令（每个里程碑收尾都要跑）

```bash
mvn -B test                                  # 后端全量
cd frontend && npm run build && npm test     # 前端 tsc+build+vitest
python scripts/check-openapi.py              # openapi 结构检查
mvn -B -ntp -Pnative verify -pl secs-cli -am # 原生闸门（M0 后保持绿）
```

界面走查：`npm run build` → `node scripts/mock-ui-server.mjs`（已含 v2 mock）→ 内置浏览器（**不跑 Playwright**，用户明确要求）。

---

## 1. M4 编排执行器（进行中）

### 1.1 后端接线（必做）

- [x] **Bootstrap 给每个会话接 PlanExecutor**：`SessionManager.register(...)` 增加注册观察者（或 SessionLauncher 挂钩），对每个 `HsmsSessionRuntime` 调 `planExecutor(new PlanExecutor(deviceId, sink))`；`sink.commit` → `PlanExecutionRepository.append`，`sink.recordFire` → `RuntimeStateRepository.recordPlanFire`。
  - 注意：`deviceId` 传解析后的 ULID（`ConfigRepository.findDevice(stem)`），不是 legacy stem。
  - `PlanExecutionRepository` 已在 Bootstrap 创建，但目前只在 export 前关闭路径引用，需确认停机路径 `closeStorage` 已覆盖（已覆盖）。
- [x] **SessionConfigJson 解析/回写 actions 与策略**：`events.plans[]` 增加 `actions`（kind/stream/function/wbit/sml/params）、`offlinePolicy`、`onSendFailure`；`toJson` 原样回写（`eventsJson()` 与配置保存往返不丢字段）。
- [x] **RuntimeEdits.parseEventPlan 接受 actions**（运行时增删改计划路径）。
- [x] **ConfigProjector**：`plan_action` 表写入 + `event_plan.offline_policy / on_send_failure` 两列落值（DDL 已有）。
- [x] **台账查询 API**：`GET /api/v2/devices/{id}/runtime/executions?limit=&planId=&status=&dryRun=`（`PlanExecutionRepository.query` 已实现；挂 `RuntimeContext` + `ConfigHandler` 或新 handler，注意 `/api/v2/devices/` 前缀要与 revisions 路由共存）。
- [x] **单步试跑**：`POST /api/v2/devices/{id}/runtime/plans/{planId}/step` → 触发一次、台账 `dry_run=1`、不计 fire_count、不推进倒计时。
- [x] **SSE `plan-fired`**：200ms 合并窗口、`FAILED` 立即推（设计 §2.4.3）；前端时间轴订阅。
- [x] **openapi**：补 executions / step 路径。

### 1.2 执行器缺口（设计 §2.7 明确要求，当前从简）

- [x] `offlinePolicy=queue`：当前按 skip 记台账并注明；要补有界内存队列（≤256，重连 FIFO 补跑，按 now 施加效果、明确标注「不是 Spooling」）。
- [x] `onSendFailure=rollback`：当前忽略（恒 keep）；补边界实现（仅全 set/add 且此后未被其它 fire 触碰，否则 `rollback_skipped`）。
- [x] `alarm / terminal / trace` 动作类型：当前记 FAILED「未实现」；补发送器接线。
- [x] action 级 `wbit`：当前 s6f11 用会话默认 W 位，动作上的 wbit 被忽略。
- [x] W 位发送的 `ackSender` 队列语义：当前执行器内同步 `comm.send(6,11,true,…)`，与 EventReporter 的异步 ACK 队列不一致，W 位计划会阻塞心跳线程，需对齐。
- [x] 启用态持久化端点：`POST /api/v2/devices/{id}/runtime/plans/{planId}/enabled` 写 `runtime_plan_state`（当前 only 计数）。

### 1.3 前端事件执行页

- [x] 设备·运行区「事件计划」页加入**执行时间轴**（设计 §3.2-5）：计划卡（开关/倒计时/累计/最后错误）+ 时间轴（状态 SENT/PARTIAL/FAILED/SKIPPED、报文条数、SVID before→after）+ 只看失败 + 导出 CSV + SVID 曲线叠加事件竖线。
- [x] `state/usePlanExecutions.ts` 轮询/SSE 订阅新端点；mock-ui-server 补 executions mock。

### 1.4 测试与收尾

- [x] 单测：顺序契约（effects→build→send）、幂等键去重、offline skip/fail、多 action、SML 动作、台账异步落库（发送线程零同步 DB 写）。
- [x] `mvn -B test` + 前端 build/test + 原生 verify 全绿后提交 M4。

---

## 2. M3 遗留（设计 §3 未完全落地项）

- [ ] **e2e 用例按 3.0 IA 重写**：`frontend/e2e/app.spec.ts` / `fixtures.ts` 仍引用旧结构（`.nav-group` 文案「设备」、设备扁平子页签、`#/hostsim/*`）；mockApi 缺 `/api/v2/*`。本地不跑 Playwright，但 CI（`check-all.ps1`）会跑。
- [ ] **文案清理门禁**：`scripts/check-ui-words.ps1`（设计 §3.5：技术词按词边界 + 白名单）并挂进 `check-all.ps1`；同步清理前端字符串里的实现词（`hostId`、`logs/messages.jsonl`、接口路径等）。
- [x] **HostDetailPanels 的 API 客户端注入是模块级 hack**（`setHostDetailClient`）：改为经 `PanelBaseProps` 正常传参，删除注入器。客户端由 `HostDetailPage` 用 `backend.client` 逐层下传；`requireClient` 在异步发送里兜底为空态；顺带修掉 `client ?? useHostsClient()` 的条件式伪 Hook。
- [x] 主机详情·数据采集页：曲线视图补齐（复用 `VariableChart`，按样本 epoch 时间轴画每个 SVID）。
- [ ] 主机详情·Trace 设置 / 采集设置（配置区）与运行区同名页当前复用同一实现（`readOnly` 只藏了发送按钮）；按设计拆「保存到设置」语义。
- [ ] 设备·配置区「值模板」页未单列（曲线/组合/实例目前在「结构」页内）；按设计 §3.3.1 决定是否拆页。
- [ ] v1 面板死代码收敛：`useHostSims`、`HostSimHostsPanel`、`HostSimFdcPanel`、`HostSimCollectPanel`、`HostSimMessagesPanel`、`HostSimMonitorPanel`、`HostTargetPicker`、`SvidCheckList` 已无页面挂载（仅 `HOST_PRESETS` 等被导出复用）——删除或标记过渡。
- [ ] v1 端点语义收口（设计 §2.5 兼容表）：`DELETE /api/v1/sessions/{id}` 目前仍是「移除」；设计期望转 `disconnect` + Deprecation 头。M3 前端已不在用 v1 关闭流程，可安全切换（需同步改 v1 测试）。
- [x] `frontend/e2e` 之外，检查 `frontend/src/**` 是否还有引用已删除 `HostSimPage` 的残留：已全量 grep `frontend/src` 与 `frontend/e2e`，无残留。
- [ ] v1 端点语义收口的**依赖说明**：设计 §2.5 的 `DELETE /api/v1/sessions/{id}` → `link/disconnect` 需要设备侧 v2 断开能力（§3 M2 第 4 项），当前 `context.closeSession` 仍是「关闭并移除」。建议与 M2 一起做，避免先加废弃头却保留移除语义。

---

## 3. M2 遗留（生命周期/运行态持久化的深水区）

- [ ] **runtime.db 写透**：钉值（`pin/unpin`）、计划启用态、运行时计划正文覆盖、目录编辑、主机动态报告**尚未写入** `runtime.db`（仓储方法已就绪，运行路径未接）；「断开保留、重连恢复」目前靠内存对象存活，进程重启即丢。
- [ ] **启动恢复**：启动对账只做了 `cleanupOrphans` + 台账裁剪；`device_link` 相位回填、`runtime_svid_pin` / `runtime_plan_state` 等恢复路径未接。
- [ ] **SSE 生命周期帧**：`device-link` / `host-link` / `config-changed` 三类未加（`message` 已带 `perspective`）；主机相位变化目前靠详情页 2s 轮询。
- [ ] 设备侧 v2 端点：`/devices/{id}/link/{connect|disconnect|reload}`、`/devices/{id}/runtime/{plans,catalog,svids/pin,message,alarm}` 未建（设计 §2.5）；当前仍走 v1 `/sessions/**`。
- [ ] 断开展示：v1 会话关闭后设备从列表消失（v1 语义），设计期望「已断开」行保留——等设备 v2 端点落地后由 M3 前端切过去。

---

## 4. 打包 / CI / 发布（M5）

- [ ] **Linux 包缺 secs-store**：`scripts/package-linux.ps1` 的 `$modules` 与 `packaging/start-server.sh` 的 classpath 都要加 `secs-store.jar`（否则 JVM 形态起不来 SQLite）。
- [ ] Windows 安装包：原生 exe 构建 `-am` 已含 secs-store；安装后首次启动会释放 `native/sqlitejdbc.dll` 到工作目录——在干净机器上实测一次首启。
- [ ] 版本号：`pom.xml`（2.0.1）→ 3.0.0；`gui/src-tauri/tauri.conf.json`、`Cargo.toml` 同步（打包脚本会校验三者一致）。
- [ ] 文档：README / `docs/guides/user-guide.md` / `docs/guides/schema-v5.md`（补 actions/offlinePolicy 段）/ ADR 0017 状态改为「已接受」/ `docs/openapi.yaml` v2 全量校对。
- [ ] `scripts/check-all.ps1` 全绿（含 openapi、BOM、mvn、vitest、Playwright、启动自检）。
- [ ] 发布说明 + 打 tag `v3.0.0`。

---

## 5. 已知可接受的技术债（不阻塞 3.0.0，但要记录）

| 项 | 说明 |
|---|---|
| `PlanExecutor` 幂等窗口 | 同键 60s 内视为重复；设计是「内存幂等表判重」无窗口语义，跨小时的重启补算会重发一次（心跳扫描本身不会重复产出同 atMs，风险低） |
| v1 兼容字段 | `HostSimulator.stateJson` 恒返回 `session:"" / mode:"target" / remote:true`，前端 M3 已不依赖；待 v1 端点退役时删 |
| 设备常量页 | 客户端解析配置原文（只读展示）；ECV 的写入口仍是主机 S2F15 |
| 台账保留 | 5000 条 / 7 天裁剪在启动时跑一次；每小时增量清理任务未挂 |
| 迁移来源标记 | `meta.migration_source` 只写日期；原绝对路径（`--config` 外部文件）未记 |

---

## 6. 设计文档与实现差异备忘（评审时对齐口径）

- 设备运行区当前 6 页：实时状态 / **事件计划（含台账时间轴，M4）** / 事件与报告 / 状态变量 / 报文收发 / 手动操作；设计稿为 实时状态 / 事件执行 / 事件与报告 / 状态变量 / 报文收发 / 手动操作（事件计划在配置区）。当前把「计划 CRUD + 执行台账」合并在一页，等 M4 落地后再评估是否拆。
- 设备配置区当前 6 页：概览 / 连接 / 结构 / 配置原文 / 设备常量 / 版本历史；设计稿为 8 页（含独立的「值模板」「事件计划」）。见 §2 对应条目。
- 主机配置区 5 页已按设计落地（概览 / 连接 / 报文模板 / Trace 设置 / 采集设置）。
