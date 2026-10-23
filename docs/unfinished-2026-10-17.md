# 未完成清单 · 2026-10-17

基线：`master @ 5179263`，工作树干净。

验证：GraalVM 25 下 `mvn -B -o verify` = **BUILD SUCCESS**，**370 个测试全绿**
（json 110 / protocol 45 / core 90 / app 25 / api-http 37 / runtime 17 / cli 46）。

> **2026-10-18 更新**：在其后完成 M10 安全边界的最后一块——SERVER 账号/角色鉴权
> （ADR-0007），把角色分级接到前端、补上 SVID/CEID/RPTID 目录接口与主机覆盖来源标注，
> 并完成 M12 item 9：会话生命周期（新建 / 关闭 / 热重载）。
> 当前 `master @ 63a9f20` + 本轮改动 = **425 个测试全绿**
> （json 110 / protocol 45 / core 90 / app 25 / api-http 71 / runtime 33 / cli 51）。
>
> **2026-10-18 续**：在其后完成 M12 item 8 的第一步——配置读取 / 校验 / 保存 API
> （`GET/POST /api/v1/configs`、`GET/PUT /api/v1/configs/{name}`、
> `POST /api/v1/configs/{name}/validate`）与前端「配置编辑器」面板。
> 当时 **464 个测试全绿**（json 110 / protocol 45 / core 90 / app 25 / api-http 93 / runtime 50 / cli 51）。
>
> **2026-10-19 续**：完成 M12 item 8 的剩余部分——模板曲线 / 组合模板 / 实例参数的
> **结构化表单**（在原文之上编辑，未知键保留、排版统一），并给曲线预览补上
> 悬停读数、拖动放大 / 双击重置、SVID 截断提示。预览 JSON 新增 `svidTotal` / `truncated`。
>
> 同日继续 M12 item 10 的**报文落盘**：`<work-dir>/logs/messages.jsonl`（JSONL）、
> 最近 5000 条历史接口 `GET /api/v1/messages`、JSONL 导出、前端「载入历史 / 导出」。
> 随后完成**手动发送 SML**：`SmlParser` 受控子集、`POST /api/v1/sessions/{id}/message`、
> 前端「手动发送」面板；W 位同步等 T3 并返回 `timedOut/replySml`（ADR-0010）。
> 再完成**二进制曲线预览**：`GET /api/v1/preview.bin`（SCSP v1）与前端下载，
> 与 JSON 预览共用同一份 `previewData`。
> 随后完成**事件编排控制**：运行时列出/启用/禁用 `EventPlan`（禁用不补发、重载恢复配置），
> 前端新增「事件编排」面板。
> 再补上 **uPlot 预览引擎**：Canvas / uPlot / 多轴切换，图例开关、框选缩放、双击复位；
> 用 Playwright-core + Edge 手工冒烟（10 图 / 10 图例 / 无 pageerror），但未进 CI。
> 随后补上**审计**：`logs/audit.jsonl` + `GET /api/v1/audit`（admin），
> 关键操作与 403 都带 actor/role/target/detail。
> 再补上**设备控制状态**：OFFLINE / COMMUNICATING / ONLINE，
> 随 S1F13 / S1F15 / S1F17 与断链迁移，并在会话列表显示。
> 最后补上 **OpenAPI 3.0.3 契约**（22 路径 / 16 schema），CI 用
> `openapi-spec-validator` 校验。
> 再补上 **Playwright E2E**：Mock API 3 例（uPlot / 模板编辑+校验保存 /
> 事件切换+审计）与真实 Java 后端 1 例，CI 两个 job 均已接入。
> 最后补上 **JVM 兜底包**：jlink runtime + jars + web，约 37 MB，
> `start-jvm.cmd` 在不预装 JVM 的机器上可启动；再补一个 PowerShell 脚本安装器。
> 再补 **Linux 部署**：package-linux.sh + systemd unit/env + Dockerfile/compose。
> 最后补 **自动更新**（manifest + SHA256 + 回滚）、**jpackage MSI**、
> 以及 **原生 E2E 进 verify**（`mvn -Pnative verify`，NativeRuntimeIT 2 例）。
> 当前 **496 个后端测试**（json 110 / protocol 45 / core 90 / app 32 / api-http 105 / runtime 56 / cli 58）
> + **22 个前端单元/组件测试**（模板模型 13 + 服务端渲染 4 + 报文合并 5）
> + **3 个 Mock E2E + 1 个真实后端 E2E** 全绿。

> **2026-10-22 续**：处理 10-20 协议评审的 3 个 P0 与 1 个 P1：
> S6F11 补 DATAID（`L[3]`）、S2F33/S2F35 形状判定（标准优先 + 非标兼容）、
> 迟到 secondary 不再回 S9F5、计划驱动的主动上报限定在 ONLINE 之后。
> P1-1（A16 时区）经核对标准原文**不成立**，已撤销；P1-5（SystemBytes 应答判定加
> secondary 奇偶校验）已落地，并用撞号用例与"控制帧不进应答匹配"用例钉住；
> 顺带修掉 `HsmsSsMessage.toString()` 的空报文体判断缺陷；再补上 S2F41/F42（P1-3，
> HCACK 按状态返回，不做真实设备动作）；P2 批（1–7）已清理完毕（P2-8 复核不成立）。
> 随后补上 **P1-4 设备常量（S2F13–F16）**：`equipment.constants` 声明，S2F15 写入后
> 立即写回配置文件（只换常量段 + 原子替换），重载/重启后保留；EAC 0/1/2/3，整批不生效。
> 再修 `pumpNow` 缺少整体互斥（心跳与外部推进交错会导致"到点却没发"）。
> 当前后端 **524 个测试全绿**（json 110 / protocol 48 / core 90 / app 32 / api-http 106 / runtime 56 / cli 82）。
> **测试稳定性**：cli 模块集成风格用例曾因 `openAndWaitUntilCommunicating()` 返回时设备侧
> 尚未就绪而偶发失败，已由 `SessionTestSupport.awaitPeer` 统一修复（22 处）。
> **已知残留**：全量套件仍有约 1/6 概率的分散性偶发失败（单独跑均稳定通过、
> 干净基线上同样复现），属测试时序问题，处理边界待定。
> 详情见 [`code-review-2026-10-20.md`](code-review-2026-10-20.md) §四。

本清单分三类：

- **真缺口**：功能确实没做；
- **假完成**：文档/报告说做了，代码没有或没接上生产路径；
- **边界**：文档已承认未做，发版前要决定。

---

## 0. 本轮已修（P0 + 首批 P1）

| 项 | 状态 | 证据 |
|---|---|---|
| 打包 `start.ps1` 把包根算成上一级，双击 `start.cmd` 直接报 exe 不存在 | ✅ 已修 | [`packaging/start.ps1`](../packaging/start.ps1) 改为 `$root = $PSScriptRoot`；新增 `-CheckOnly` / `-NoBrowser`；[`scripts/package.ps1`](../scripts/package.ps1) 在打包后执行路径自检 |
| 配置里的 `globalSvids` 永远返回 0 | ✅ 已修 | [`DefaultCurves`](../secs-app/src/main/java/com/shimizukenta/secssim/app/session/DefaultCurves.java) 增加全局 SVID 绑定；`GlobalSvidDef` 增加可选 `min/max`；新增 `DefaultCurvesTest` 与 S1F3 真链路用例 |
| 未知 Stream/Function 不回 S9Fx，主机只能等 T3 | ✅ 已修 | [`HsmsSessionRuntime.replyUnsupported`](../secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/HsmsSessionRuntime.java) 按已知流回 S9F5、未知流回 S9F3；S9 自身不回，避免死循环；新增 `SessionUnsupportedMessageTest` |
| S1F3 空列表返回空，自动发现型主机拿不到变量 | ✅ 已修 | `replyS1F4` 空列表按 E5 返回全部 SVID；新增 `SessionS1F3Test#emptyS1F3ReturnsAllSvids` |
| S6F11 永远不带 W 位，主机要 ACK 时无法满足 | ✅ 已修 | `events.s6f11Wbit` 可配置；W 位发送走单线程队列等待 S6F12，不阻塞 100ms 事件心跳；新增 `SessionS6F11Test#s6f11WbitWaitsForS6f12` |
| S6F1 永远不带 W 位，主机要 S6F2 时无法满足 | ✅ 已修 | `events.s6f1Wbit` 可配置；S6F1 等待 S6F2 走独立单线程队列，不阻塞 Trace 采样；新增 `SessionTraceTest#s6f1WbitWaitsForS6f2` |
| 主机发 S10F3 终端消息没有应答 | ✅ 已修 | 新增 `handleTerminalDisplay`：保存最近 20 条、回 S10F4；格式非法回 `MessageWillNotBeDisplayed`；新增 `SessionTerminalMessageTest` |
| 没有报警上报能力 | ✅ 已修 | 新增 `emitAlarm` → S5F1（W 位等 S5F2）；HTTP `POST /sessions/{id}/alarm/{alid}`；前端报警置位/清除；新增 `SessionAlarmTest` 与 HTTP 路由用例 |
| 只有 at/periodic，不能按条件触发 | ✅ 已修 | 新增 `Trigger.OnCondition` + `ConditionDebouncer`；表达式支持比较运算；每 tick 求值 `t` / `svid.<id>`；新增 `SessionOnConditionTest` |
| 生产路径无法使用模板 DAG | ✅ 已修 | Schema v5 增加 `templates`；`TemplateConfig.build` 生成 `TemplateGraph`；`Main` 用 `GraphValueSource`；新增 `SessionConfigJsonTest#templateConfigBuildsGraph` 与 `ConfigDrivenSessionTest#schemaV5TemplatesDriveS1F3` |
| SERVER 模式只能明文 HTTP | ✅ 已修 | 新增 `--tls-keystore` / `--tls-password`，`HttpApiServer` 支持 `HttpsServer`；server 模式强制显式凭据（`--token` 或 `--auth-users`）；新增 `TlsServerTest` |
| 单一令牌 = 一把钥匙开所有门 | ✅ 已修 | 新增 `AccessControl` / `ApiRole`；`--auth-users` 账号文件分 viewer / operator / admin；401 与 403 分开；比对不提前 break 防计时侧信道；新增 ADR-0007、`AccessControlTest`、`HttpApiServerAuthTest` |
| 活动报警/终端消息只能存内存、界面看不到 | ✅ 已修 | `SessionRuntime` 增加 `alarmsJson` / `terminalMessagesJson`；HTTP `GET /sessions/{id}/alarms`、`/terminal`；前端每 2 秒刷新面板 |
| 不能表达“A 之后 N 秒发 B” | ✅ 已修 | 新增 `Trigger.AfterEvent` + 延迟队列；源计划成功后才入队；配置校验源计划存在/无环；新增 `SessionAfterEventTest` |

验证命令：

```powershell
powershell -File scripts/package.ps1 -SkipBuild
cmd /c "package\secs-sim-2.0.0-SNAPSHOT\start.cmd -CheckOnly"
```

---

## 1. P1：协议能力

- [x] **S5F1 报警**：已实现置位/清除 S5F1 + S5F2 等待，ALCD = 类别 | 0x80；HTTP/UI 可手动触发；`GET /sessions/{id}/alarms` + 前端面板。自动去抖与报警定义配置仍可补。
- [x] **S10F3 终端消息**：已实现 S10F3 → S10F4，保存最近 20 条；格式非法回 `MessageWillNotBeDisplayed`；`GET /sessions/{id}/terminal` + 前端面板。
- [x] **S6F11 的 W 位 + S6F12**：已支持 `events.s6f11Wbit`；开启后异步等待 S6F12，队列满计入 `wbitDropped`。S6F12 的 ACKC6 业务处理（非 0 时告警/重试）仍可补。
- [x] **S6F1 的 W 位 + S6F2**：已支持 `events.s6f1Wbit`；开启后异步等待 S6F2，队列满计入 `traceWbitDropped`。S6F2 的 ACKC6 业务处理仍可补。
- [x] **未知 Stream/Function 的 S9Fx**：已实现 S9F3/S9F5；S9 自身不再回 S9。S9F1/7/9/11 仍按具体错误类型细分时可补。
- [x] **OnCondition 条件触发**：已实现 `Trigger.OnCondition` + `ConditionDebouncer`，条件连续成立 `debounceMs` 才触发一次；表达式支持比较运算与 `svid.<id>` 变量。
- [x] **AfterEvent 事件链**：已实现 `Trigger.AfterEvent`，源计划成功后按延迟入队；配置层校验源计划存在且无环，运行时 64 跳防失控。
- [x] **S1F3 空列表语义**：已按 E5 惯例改为返回全部 SVID，与 S1F11 一致。
- [ ] **事件取值时刻**：100ms tick 下 `pumpEvents` 丢掉 `fire.atMs`，用 `clock.nowMs()` 取值；批处理/加速时时间语义会偏。
- [ ] **Trace 上限**：只允许 1 条 Trace、每条最多 10 个 SVID（已记录边界，按现场需求决定是否扩）。

## 2. P1：配置与运行时

- [x] **模板 DAG / 曲线进配置**：Schema v5 增加 `templates.curves/composites/instances`；`Main` 有模板时构造 `GraphValueSource`，无模板时仍走 `DefaultCurves`。未绑定 SVID 目前返回 0，模板编辑器/预览未做。
- [x] **没有内置模板库**：`thermoLoop` / `gasPanel` / `alarmTriplet` 未做。
- [x] **单会话**：一个进程可以装多份配置了。`POST /api/v1/sessions` 按 `--work-dir` 下的配置文件新建会话，`DELETE /api/v1/sessions/{id}` 关闭单个会话；`SessionManager` 增加 `remove(id)`。配置名只接受文件名，不接受路径。
- [x] **无热重载**：`POST /api/v1/sessions/{id}/reload` 按来源配置重新装载。**先编译再动旧会话**——配置有错时旧会话原样跑着。内置演示会话无配置来源，回 409 `not-reloadable`。
- [x] **无配置管理 API/界面**：已实现。`ConfigStore` 负责读原文、校验、原子写盘；`GET /api/v1/configs` 列目录、`POST` 新建骨架、`GET /api/v1/configs/{name}` 读原文、`POST .../validate` 只校验不落盘、`PUT` 校验通过后落盘；前端新增「配置编辑器」面板（读 / 改 / 校验 / 保存 / 新建）。**编辑器操作原始 JSON 文本**，不走模型往返（`toJson` 会静默丢掉未知键）；**先校验再落盘**，校验不过时原文件一个字节不动。**模板结构化编辑已落地**：曲线（含 step/pwl 关键帧）、组合模板输出表达式、实例 SVID 绑定与参数都能在表单里改；表单读当前原文解析出的 JSON，改模板 id / 实例 id 会同步引用，删除仍被引用的模板会被拒绝。**代价**：表单改动会把整份文件重排成 2 空格缩进（未知键保留），纯文本编辑仍逐字节保真。
- [x] **无事件编排控制**：`GET /api/v1/sessions/{id}/events` 列计划（id/CEID/触发规则/启用状态），`POST .../events/{planId}?enabled=` 运行时启用/禁用；禁用清条件去抖与延迟队列，不补发；前端新增「事件编排」面板。**运行时状态不写回配置，重载会恢复。**
- [ ] **`work-dir` 未真正使用**：**已作为配置目录与报文日志目录投入使用**（配置在根下，报文写到 `logs/messages.jsonl`；`ConfigStore` 只列普通文件）；README 承诺的**模板目录**仍未落地，运行日志仍写 stderr。
- [x] **无设备控制状态模型**：新增 `EquipmentControlState`（OFFLINE / COMMUNICATING / ONLINE）。HSMS selected 或 S1F13 → COMMUNICATING，S1F17 → ONLINE，S1F15 或断开 → OFFLINE；会话列表与前端显示。这是操作级简化模型，不是完整 E5 状态机。
- [x] **无审计**：新增 `AuditLog`（`<work-dir>/logs/audit.jsonl`）与 HTTP `AuditSink`；手动 CEID、报警、会话/配置/手动 SML/事件切换/停机/403 拒绝都有 actor+role+target+detail；`GET /api/v1/audit` 仅 admin；前端 admin 审计面板。见 ADR-0012。

## 3. P1：API 与前端

- [ ] **WebSocket**：README / pom / package-info 声称有，实际只有 SSE。
- [x] **二进制曲线流**：新增 `GET /api/v1/preview.bin`（SCSP v1，小端，48 字节头 + min/max/avg f64），与 JSON 预览共用 `previewData`；前端「下载二进制」。格式见 `docs/preview-binary.md`。
- [x] **预览静默截断**：`previewJson` 新增 `svidTotal` / `truncated`，前端明说"共 N 个 SVID，只显示前 32 条"；buckets 上限仍为 500（界面显示桶宽）。
- [x] **前端只有 Canvas 包络 + 均值线**：Canvas / uPlot / 多轴三种模式；uPlot 支持图例开关、框选缩放、双击复位与光标读数；多轴按单位分配 y 轴（左右交替），只画 avg。
- [x] **报文不落盘**：已实现 `<work-dir>/logs/messages.jsonl`（JSONL，追加 + flush），内存保留最近 5000 条；`GET /api/v1/messages` 支持 `session/direction/limit/format=jsonl`；前端「报文」面板可载入历史、导出 JSONL。**没有自动轮转/保留策略**。
- [x] **没有手动发送 SML/SECS 报文**：新增 `SmlParser`（受控子集）与 `POST /api/v1/sessions/{id}/message`（operator），前端新增「手动发送」面板；W 位同步等 T3 并返回 `timedOut/replySml`。见 ADR-0010。
- [x] **没有 SVID/CEID/RPTID 目录接口**：已实现 `GET /api/v1/sessions/{id}/catalog`，投影 `ResolvedEquipment`（与 S1F11/S2F33 同一份数据，报告内 SVID 顺序与 S6F11 组包一致）；前端新增「设备目录」面板，CEID 可按名称填入上报框。号段按单元独立，目录按会话取并每 2 秒轮询（主机随时可能改定义）。
- [x] **没有报警/终端消息/主机动态报告面板**：报警与终端消息面板上一轮已落地；主机动态报告并入「设备目录」——每条报告/事件标 `source`（local/host），CEID 标 `enabled`，并暴露 `hostReports` / `hostLinks` / `hostAllDisabled`。ADR-0006 要求的"报告来自本地还是主机"已兑现，且目录反映的是**实际发出去的那一份**（含主机新建、本地不存在的 RPTID）。
- [x] **没有会话生命周期操作**：已实现创建 / 关闭 / 按配置重载，前端会话表有对应按钮。
- [x] **没有配置读写界面**：已实现「配置编辑器」面板——列目录、读原文、改、校验、保存、新建。保存是 operator 及以上（改配置属于"会改变设备状态"），读与校验是 viewer。改配置**不会**让已在跑的会话自动改变，要在会话表点「重载」才生效（服务端日志也会提醒）。
- [x] **没有前端测试**：已接 Vitest 22 例 + Playwright 3 例（mock API）+ 1 例（真实 Java 后端）；CI 有 `npm test`、`frontend-e2e`、`frontend-e2e-real` 三个 job。
- [x] **前端不认角色**：`/api/v1/health` 与 `POST /api/v1/ticket` 回显 `user` / `role`；界面顶部显示「身份」，只读身份下「上报 / 置位 / 清除 / CEID / 目录填入」全部禁用并给出原因提示；401 与 403 在界面上分开报。**浏览器端到端验证本轮未做**（认证凭据需用户本人输入），只做了 `tsc --noEmit` + `vite build`。
- [x] **没有 OpenAPI 契约**：新增 `docs/openapi.yaml`（OpenAPI 3.0.3，覆盖 22 个路径/16 个 schema），CI 增加 `openapi-spec-validator` 校验。

## 4. P2：分发与工程化

- [ ] **代码签名**：未做；文档自己标了“正式分发前必须签，优先级最高”。
- [x] **安装器**：脚本安装器 + jpackage MSI。脚本 installer 已实测安装/启动自检/卸载；`scripts/package-msi.ps1` 用 jpackage + WiX 生成 39.1 MB MSI，admin 解包后 exe 启动/页面 200 通过。**MSI 未签名。**
- [x] **JVM 兜底包**：新增 `scripts/package-jvm.ps1` + `packaging/start-jvm.*`；jlink 运行时（java.base+jdk.httpserver）+ 应用 jar + web，约 37 MB；`start-jvm.cmd -CheckOnly`、真实启动/health/页面冒烟通过。
- [x] **自动更新**：`packaging/update.ps1` / `check-update.cmd` + manifest（version/url/sha256）；校验 SHA256、旧目录备份、安装失败回滚；本地 2.0.0→2.0.1 实测通过。**面向脚本安装器目录；无后台自动检查服务，需手动/计划任务触发；MSI 不参与。**
- [ ] **跨平台**：Linux 已有 `package-linux.sh` + systemd + Dockerfile；macOS 仍无产物，Linux 包也未在真实 Linux 上构建过。
- [ ] **`secs-dist` 是空模块**：只有 pom，打包实际在 `scripts/package.ps1`。
- [x] **原生 E2E 没进 verify**：`NativeRuntimeIT` 已修到当前 ticket/health/POST 语义，并通过 failsafe 绑定到 `mvn -Pnative verify`；本地构建原生镜像后 **2 个 IT 通过**。CI native job 已改为 `verify` 并构建前端。
- [ ] **里程碑验收脚本只有 M0/M1**：M2–M9 没有对应 `accept-*.ps1`。
- [ ] **构建前置未固化**：项目要求 JDK 25，本机默认 JDK 21；没有 Maven toolchains / enforcer。
- [x] **服务端部署**：新增 `packaging/systemd/secs-sim.service` / env 示例 / 部署 README、`packaging/docker/Dockerfile` / compose / entrypoint、`scripts/package-linux.sh`；Linux demo 配置在 JVM 上启动/health/session/preview 冒烟通过。**Docker 本机不可用，未实际构建镜像。**
- [ ] **发布未闭环**：版本仍是 `2.0.0-SNAPSHOT`；只有 `v1.0` / `v1.1` 标签；本地 master 领先 origin 28 个提交。
- [ ] **日志**：只有 stderr 单行日志，没有文件/轮转/级别；`HsmsSessionRuntime` 与 `SecsProbe` 已改用 `Log`，但 `Main` 在 `SECS_SIM_DEBUG` 下仍会打完整栈。
- [ ] **下轮评审**：`secs-cli` 端到端宿主与 `scripts/`、`packaging/` 的 PowerShell 健壮性未深评。
- [ ] **历史报告同步**：m6 报告仍写 S1F11 未做；m6b 仍写 S2F33/35/37 未做（m6c 已完成）；m0 的 OpenAPI 承诺已落地（`docs/openapi.yaml`）。README 与 RuntimeMode 的本轮同步已完成。

## 5. 已知接受风险

- 协议层 `Gem.s10f4`（以及 `s10fx`）用 `comm.send(10, func, ...)` 发新的 Primary，而不是对 S10F3 的 Reply；会话侧已显式 `send(primary, 10, 4, ...)` 规避。协议层冻结，未改源。
- JSON 数字：`1e5` 被拒；> 2^53 的整数走 double 会丢精度；重复 key 保序保留。
- `secs-protocol` 的 accept 连接上限、reply deviceId 匹配两项回退，无复现证据。
- SSE 写超时是“看门狗 + 断开”的缓解，不是 JDK API 级别的真正写超时。
- 模板结构化表单会把整份配置重排成 2 空格缩进；未知键保留，但纯文本排版不保真。要求逐字节保真的用户只能不用表单。
- Mock E2E 覆盖模板表单加曲线 → 校验 → 保存；真实后端 E2E 覆盖身份/会话/预览/uPlot/事件切换，CI 已接入。

## 6. 测试缺口

- [x] `globalSvids` 的 S1F3 取值（本轮已补 `DefaultCurvesTest` 与 S1F3 真链路用例）；S6F11 的同类用例仍可补。
- [x] S1F3 空列表（“全部 SVID”）语义。
- [x] S6F11 W 位 + S6F12；
- [x] S6F1 W 位 + S6F2。
- [x] 未知 Stream/Function 的 S9F3/S9F5 应答；S9F1/7/9/11 的错误分类用例仍可补。
- [x] S10F3 / S10F4；
- [x] S5F1 / S5F2。
- [x] OnCondition；
- [x] AfterEvent（事件链）。
- [x] 多会话、配置热重载、运行时创建/关闭会话（`SessionLauncherTest` 9 例：多会话、关闭、重载、配置坏了保住旧会话、演示会话不可重载、名字不能变成路径）。
- [x] 打包后的 `start.cmd` 路径自检（`-CheckOnly`）；解压后真正拉起进程的全链路冒烟仍建议补进 CI。
- [x] 前端组件/交互测试（Vitest 22 例：模板模型 13 + 服务端渲染 4 + 报文合并 5；CI 已跑 `npm test`）。
- [x] 报文落盘（MessageLog 4 例：追加/重启回读/过滤/坏行/半截行修复）与 HTTP 历史接口（鉴权/过滤/JSONL/503）。
- [x] 手动 SML：解析器 6 例（头/嵌套/各类型/转义/错误）、真实协议链路 3 例（W 位回复、W=0、无对端/坏 SML）、HTTP 映射 3 例。
- [x] 二进制预览：头字段/长度/与 JSON 数值一致/截断计数；HTTP 内容类型与鉴权。
- [x] 事件编排控制：运行时禁用期间不触发、重新启用恢复、未知计划返回 false；HTTP 列表/切换/鉴权/404/400。
- [x] 审计：AuditLog 3 例（落盘/回读/limit/坏行）；HTTP 审计 actor/role/action、admin-only、401/403/400/405。
- [x] 控制状态：真实链路 COMMUNICATING → ONLINE → OFFLINE → COMMUNICATING 迁移。
- [x] 浏览器 E2E：Mock API 3 例 + 真实 Java 后端 1 例全绿；CI 分别有 `frontend-e2e` / `frontend-e2e-real`。
- [x] 真实浏览器 UI E2E 进 CI：Mock API 3 例（uPlot、模板编辑+校验/保存、事件+审计）+ 真实 Java 后端 1 例（身份/会话/预览/uPlot/事件切换）。
- [ ] 大规模预览性能与 400 SVID / 多客户端并发的压力测试（SVID > 32 的截断提示已补）。

## 7. 建议顺序

**M10：清 P0 + 安全边界**
1. `start.ps1` 已修；补“解压 → start.cmd → READY/health/页面”冒烟。
2. `globalSvids` 已修；补配置文档与回归。
3. SERVER TLS 与账号/角色鉴权均已实现（`--tls-keystore`、`--auth-users`）；剩余安全边界只有"口令明文存储 + 无口令轮转"，需运维纪律兜底。
4. 未知 S/F 的 S9Fx、S6F11/S6F1 W 位、S10F3/S10F4、S5F1/S5F2、OnCondition、AfterEvent 均已完成；SERVER 安全边界已封口；设备目录、模板结构化表单、uPlot、审计、控制状态、OpenAPI 与 Playwright E2E（含真实后端）均已完成。下一步主要是 P2 分发/工程化。

**M11：协议能力**
5. S5F1 / S10F3：先定 ALCD/ALID/去抖、终端消息队列与 ACK。
6. OnCondition：定“条件持续成立时如何去抖”，在 `pumpEvents` 里每 tick 求值。
7. AfterEvent：把 `lastFireMs` 扩展为“已发生事件集合”。

**M12：配置与前端**
8. Schema v5 已完成（`templates` / `curves` / `instances` → `TemplateGraph` → `GraphValueSource`）；**配置读/校验/保存 API 与「配置编辑器」面板已完成**（原始 JSON 文本编辑，先校验再原子落盘）；**模板结构化表单**（曲线 / 组合模板 / 实例参数）与**曲线预览交互**（悬停读数、拖动放大、截断提示）也已完成。
9. 会话生命周期 API 与热重载已完成（创建 / 关闭 / 重载，`--work-dir` 作为配置目录）；**配置本身的读取、校验、保存 API 与前端编辑器也已完成**（item 8 的第一步）。
10. **uPlot 交互**（图例开关 / 框选缩放 / 双击复位）、**多轴（按单位）**、**二进制预览**、**报文落盘 + 历史 / 导出**、**手动 SML 发送**、SVID/CEID 目录、报警/报告面板已完成。
11. 前端测试与 E2E：Vitest 22 例 + Mock Playwright 3 例 + 真实后端 Playwright 1 例，CI 已接入。

**M13：分发**
12. 代码签名。**JVM 兜底包、脚本安装器、MSI、自动更新、Linux systemd/Dockerfile、原生 E2E 已完成。**
13. 2.0 发布：打 tag、写 release notes、把 `2.0.0-SNAPSHOT` 改为正式版本。

## 8. 已确认完成，不要重复做

- M0–M9-c 主体功能；370 个测试全绿。
- S1F1/F2、S1F3/F4（含空列表=全部）、**S1F11/F12**、S1F13–F18、S2F17/F18、S2F23/F24、S2F31/F32。
- 未知 Stream/Function 的 S9F3/S9F5 应答。
- S6F11 / S6F1 可选 W 位 + 异步等待 S6F12 / S6F2。
- S10F3 终端消息 + S10F4 ACK。
- S5F1 报警置位/清除 + S5F2 ACK（HTTP + 前端）。
- OnCondition 条件触发（含比较运算与去抖）。
- AfterEvent 事件链（延迟队列 + 环校验）。
- Schema v5 模板 DAG / 曲线进配置（`TemplateConfig` + `GraphValueSource`）。
- SERVER 模式 TLS（PKCS#12 keystore）+ 强制显式凭据。
- **API 账号/角色鉴权**（viewer / operator / admin，`--auth-users`，ADR-0007）；前端按角色禁用无权限按钮。
- **SVID / RPTID / CEID 目录接口**（`GET /api/v1/sessions/{id}/catalog`）+ 前端「设备目录」面板。
- **会话生命周期 API**（`POST/DELETE /api/v1/sessions`、`POST .../reload`）+ 前端按钮。
- **配置读写 API 与「配置编辑器」**（`ConfigStore`：读原文 / 校验 / 原子落盘 / 新建骨架）。
- **模板结构化表单**（曲线含 step/pwl 关键帧、组合模板输出表达式、实例 SVID 绑定与参数；引用同步、删除保护、未知键保留、2 空格重排）+ **曲线预览交互**（悬停读数、拖动放大 / 双击重置、`svidTotal`/`truncated` 截断提示）。
- **报文历史**（`MessageLog` → `<work-dir>/logs/messages.jsonl`，最近 5000 条；`GET /api/v1/messages` + JSONL 导出 + 前端载入历史）。
- **手动发送 SML**（受控子集解析 + W 位等待回复 + 前端面板，ADR-0010）。
- **事件编排控制**（列表 + 运行时启用/禁用 + 前端面板；禁用清理去抖/延迟队列）。
- **uPlot 预览引擎**（Canvas/uPlot/多轴；图例开关、框选缩放、双击复位；浏览器冒烟 10 图 10 图例、无 pageerror）。
- **审计日志**（JSONL + actor/role/action/target/detail；覆盖关键操作与 403；admin 查询与前端面板）。
- **设备控制状态**（OFFLINE / COMMUNICATING / ONLINE，S1F13/S1F15/S1F17 与断链迁移；会话列表显示）。
- **OpenAPI 3.0.3 契约**（22 路径 / 16 schema，CI 用 openapi-spec-validator 校验）。
- **Playwright E2E**（Mock 3 条 + 真实 Java 后端 1 条；CI 安装 Chromium）。
- **JVM 兜底包**（jlink runtime + jars + web，start-jvm；约 37 MB）。
- **脚本安装器**（JVM 包 + install/uninstall；快捷方式/卸载项；实测安装/卸载）。
- **Linux 部署**（package-linux.sh + systemd unit/env + Dockerfile/compose；demo 配置冒烟通过）。
- **自动更新**（manifest + SHA256 + 备份/回滚；2.0.0→2.0.1 实测）。
- **jpackage MSI**（WiX；39.1 MB；解包后 exe 启动/页面 200）。
- **原生 E2E**（failsafe；mvn -Pnative verify 跑 NativeRuntimeIT 2 例）。
- **二进制曲线预览**（SCSP v1：48 字节头 + min/max/avg f64，前端下载；格式文档 `docs/preview-binary.md`）。
- **前端 Vitest**（22 例，CI 已接入）。
- **S2F33/F34、S2F35/F36、S2F37/F38** + `HostReportTable`（m6b 报告的“未做”已过时）。
- S6F11 基本上报、S6F1 Trace、SML 渲染、曲线预览（JSON）。
- M9-b/M9-c 的 SSE 独立线程池、一次性 ticket、静态 ETag、SSE 看门狗、预览时间窗、重连补偿。
- core / json / protocol 的深评修复。
- CI 的 backend / frontend / frontend-e2e / frontend-e2e-real / openapi / ps1-bom / 每周 native verify。