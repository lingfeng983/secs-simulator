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

- [x] **e2e 用例按 3.0 IA 重写**：`frontend/e2e/fixtures.ts` 改为「可变状态假后端」（sessions/hosts/templates 可增删改，`calls`/`queries` 记调用与查询串，`mockApi()` 统一去掉 `showSaveFilePicker` 让导出走下载回退）；新增 `device.spec.ts`（21 例，设备域 12 页全覆盖）、`host.spec.ts`（20 例，主机域列表 + 详情 11 页 + 新建→连接→发送→采集→断开→删除整链）；重写 `app.spec.ts`（28 例，三组侧栏/设备树/筛选/批量/命令面板/主题密度/快捷键/连接设置/模板页/审计）。全量 **83 passed / 48 skipped**（截图用例需 `SHOTS=1`）。过程中修掉 4 个真实缺陷：`Overlay` 不响应 Esc（快捷键表关不掉）、`DeviceOpsPanel`「全选」永远禁用、设备「事件与报告」页缺报告表入口（`CeidPanel` 补 `both` 模式）、主机「收发记录」页拿不到报文缓冲。
- [x] **文案清理门禁**：`scripts/check-ui-words.ps1` 已落地并挂进 `check-all.ps1`（tsc 之后、Vitest 之前）。不只看字面量：剔除注释/import、剥掉 `${...}` 拼出的 key/路由/选择器（`host-zone:${hostId}:${next}` 这类不再误报）、中文按词匹配 + 白名单（列表/表格/报表/图表/仪表/表达 放行）、英文按词边界且大小写敏感（`?sql=` 不会命中 `SQL`）、.tsx 才做 JSX 文本、顺带扫 `docs/design/frontend-panel.html`。清理了 25 处真实命中：`落盘`→写入文件、`台账`→执行记录、`快照`→版本、`T6 事务`→T6 多段交互、报警页的 `logs/messages.jsonl` 说明、版本历史表头 `SHA-256`→`校验值`。当前 **99 文件 / 5481 段文案全绿**；已用探针验证「注入 `数据表` / `SQLite` / `hostId` 会 exit 1 并给出文件:行号」。
- [x] **HostDetailPanels 的 API 客户端注入是模块级 hack**（`setHostDetailClient`）：改为经 `PanelBaseProps` 正常传参，删除注入器。客户端由 `HostDetailPage` 用 `backend.client` 逐层下传；`requireClient` 在异步发送里兜底为空态；顺带修掉 `client ?? useHostsClient()` 的条件式伪 Hook。
- [x] 主机详情·数据采集页：曲线视图补齐（复用 `VariableChart`，按样本 epoch 时间轴画每个 SVID）。
- [ ] 主机详情·Trace 设置 / 采集设置（配置区）与运行区同名页当前复用同一实现（`readOnly` 只藏了发送按钮）；按设计拆「保存到设置」语义。
- [ ] 设备·配置区「值模板」页未单列（曲线/组合/实例目前在「结构」页内）；按设计 §3.3.1 决定是否拆页。
- [x] v1 面板死代码收敛：删除 `useHostSims`、`HostSimHostsPanel`、`HostSimFdcPanel`、`HostSimCollectPanel`（连同其 `parseSvidList` 单测）、`HostSimMessagesPanel`、`HostSimMonitorPanel`、`HostTargetPicker`；`HOST_PRESETS` / `PRESET_GROUPS` / `HostPreset` 搬到新文件 `hostsim/hostPresets.ts`（主机「报文模板」「消息发送」两页仍在用）。`SvidCheckList` **保留**——它仍被设备「结构」页的 SVID 勾选列表使用（原清单把这条写错了）。tsc 与 Vitest 130 项全绿。
- [ ] v1 端点语义收口（设计 §2.5 兼容表）：`DELETE /api/v1/sessions/{id}` 目前仍是「移除」；设计期望转 `disconnect` + Deprecation 头。M3 前端已不在用 v1 关闭流程，可安全切换（需同步改 v1 测试）。
- [x] `frontend/e2e` 之外，检查 `frontend/src/**` 是否还有引用已删除 `HostSimPage` 的残留：已全量 grep `frontend/src` 与 `frontend/e2e`，无残留。
- [ ] v1 端点语义收口的**依赖说明**：`DELETE /api/v1/sessions/{id}` 要转 `link/disconnect`，设备侧 v2 断开端点已经就位（见 §3），剩下的只是前端切过去 + v1 测试同步改写。

---

## 3. M2 遗留（生命周期/运行态持久化的深水区）

- [x] **runtime.db 写透**：新增 `RuntimePersister`（runtime 包）——钉值 / 取消钉值 / 计划启用态 / 运行时计划正文覆盖 / 目录编辑（ceid·report·svid）在**成功那一刻**落库，写失败只记日志、不影响正在处理的请求（runtime.db 掉了也不该让界面 500）。接线的入口：`RuntimeApi` 的 v1 编辑方法（pin/unpin/plan/report/ceid/svid CRUD、`setEventEnabled`）与 `RuntimeContext.setPlanEnabledV2`。主机动态报告本来就走 `host.variable_catalog`（`HostSimRegistry` 每次目录变更 `saveVariableCatalog`），重启照样恢复，`runtime_host_report` 表不再重复写（记入 §5 债务）。
- [x] **启动恢复**：
  - **主机实例装回内存**（顺带修掉一个真 bug）：`loadPersisted()` 原来在 `Bootstrap` 里调，装进的却是一个随后就被丢弃的 `HostSimRegistry`——真正对外服务的那个注册表是空的，重启后 `GET /api/v2/hosts` 为空、详情/连接/发送全 404。现在挪到 `RuntimeContext` 构造里，装进正在用的注册表。
  - **会话运行态重放**：`sessions.onRegister` 对新会话按依赖顺序恢复 runtime.db 里的编辑：目录编辑（重建模型）→ 计划正文覆盖 → 钉值 → 计划启用态。反过来就会丢东西：先恢复钉值的话，运行时新增 SVID 还没进模型，那次钉值会被拒绝。单条失败只跳过这一条（配置改过 / SVID 被删 / JSON 坏了），不让一条脏数据把设备带崩。
  - **相位收敛**：`resetStaleDevicePhases()` 启动时把上次进程留下的 `CONNECTED / LISTENING / STARTING` 改成 `DISCONNECTED`（保留行，配置版本不动），否则界面会显示一堆"连着的死设备"。
  - **销毁 vs 重载**：销毁（v1 关闭）与重载都 `clearRuntimeEdits`，但**都不清 `runtime_plan_state`**——启停是用户明确的开关选择，重连后照旧。重载先清库再重建会话，否则注册观察者会把刚清掉的钉值又装回来。
- [x] **SSE 生命周期帧**：`event: device-link`（`HsmsSessionRuntime.onLinkPhase` 钩子 → 落 `device_link` + 追加 `link_event` + 推帧）、`event: host-link`（`HostSimulator.onPhase`）、`event: config-changed`（保存/新建成功后）。SSE 侧不用改：`StreamHandler` 本来就把 `NoticeEvent.type()` 直接当 `event:` 名。
- [x] **设备侧 v2 链路端点**：`GET /api/v2/devices/{id}/link`（相位 + 控制状态 + 时间戳，内存相位优先）、`POST|PUT /api/v2/devices/{id}/link/{connect|disconnect|reload}`。connect 幂等（已连上直接 200）、disconnect 只断链（`HsmsSessionRuntime.disconnect()`：关 communicator 与 ACK 池，模型/钉值/计划启用态全留，`reconnect()` 复用同一对象）、reload 复用会话重载（会先清运行态编辑）。状态码：404 设备不存在/动作未知、405 方法不对、409 不可重载、502 连接失败。openapi 已补（64 path 全绿），HTTP 契约测试 `DeviceLinkApiTest`（5 项）。
- [ ] **设备侧 v2 运行态端点**：`/devices/{id}/runtime/{plans,catalog,svids/pin,message,alarm}` 仍未建；前端这类操作还走 v1 `/sessions/**`（功能可用，只是命名空间没切）。
- [ ] v1 `DELETE /api/v1/sessions/{id}` → `disconnect` + `Deprecation` 头：依赖上一项完成后再切，否则前端拿不到重连入口，删除就变成"设备消失且找不回来"。
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
| `runtime_host_report` 表 | 建了但没用：主机动态报告实际存在 `host.variable_catalog`（重启照样恢复）。二选一时留了这个方案，表待下个版本删 |
| 迁移来源标记 | `meta.migration_source` 只写日期；原绝对路径（`--config` 外部文件）未记 |

---

## 6. 设计文档与实现差异备忘（评审时对齐口径）

- 设备运行区当前 6 页：实时状态 / **事件计划（含台账时间轴，M4）** / 事件与报告 / 状态变量 / 报文收发 / 手动操作；设计稿为 实时状态 / 事件执行 / 事件与报告 / 状态变量 / 报文收发 / 手动操作（事件计划在配置区）。当前把「计划 CRUD + 执行台账」合并在一页，等 M4 落地后再评估是否拆。
- 设备配置区当前 6 页：概览 / 连接 / 结构 / 配置原文 / 设备常量 / 版本历史；设计稿为 8 页（含独立的「值模板」「事件计划」）。见 §2 对应条目。
- 主机配置区 5 页已按设计落地（概览 / 连接 / 报文模板 / Trace 设置 / 采集设置）。
