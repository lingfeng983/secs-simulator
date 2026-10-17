# 未完成清单 · 2026-10-17

基线：`master @ 5179263`，工作树干净。

验证：GraalVM 25 下 `mvn -B -o verify` = **BUILD SUCCESS**，**347 个测试全绿**
（json 110 / protocol 45 / core 86 / app 21 / api-http 33 / runtime 12 / cli 40）。

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

验证命令：

```powershell
powershell -File scripts/package.ps1 -SkipBuild
cmd /c "package\secs-sim-2.0.0-SNAPSHOT\start.cmd -CheckOnly"
```

---

## 1. P1：协议能力

- [ ] **S5F1 报警**：未实现，只有 `templates/sml/equip/s5f1-example.sml` 样例。需要先定义 ALCD/ALID、去抖与恢复语义。
- [ ] **S10F3 终端消息**：未实现，只有样例。需要队列、显示时长与 S10F4 ACK。
- [x] **S6F11 的 W 位 + S6F12**：已支持 `events.s6f11Wbit`；开启后异步等待 S6F12，队列满计入 `wbitDropped`。S6F12 的 ACKC6 业务处理（非 0 时告警/重试）仍可补。
- [x] **S6F1 的 W 位 + S6F2**：已支持 `events.s6f1Wbit`；开启后异步等待 S6F2，队列满计入 `traceWbitDropped`。S6F2 的 ACKC6 业务处理仍可补。
- [x] **未知 Stream/Function 的 S9Fx**：已实现 S9F3/S9F5；S9 自身不再回 S9。S9F1/7/9/11 仍按具体错误类型细分时可补。
- [ ] **OnCondition 条件触发**：`Trigger` 只允许 `At` / `Periodic`；ADR-0005 明确暂未实现。需要定义“条件持续成立时如何去抖”。
- [ ] **AfterEvent 事件链**：没有 `AfterEvent`；只有标量 `lastFireMs`，不能表达“A 之后 N 秒发 B”。
- [x] **S1F3 空列表语义**：已按 E5 惯例改为返回全部 SVID，与 S1F11 一致。
- [ ] **事件取值时刻**：100ms tick 下 `pumpEvents` 丢掉 `fire.atMs`，用 `clock.nowMs()` 取值；批处理/加速时时间语义会偏。
- [ ] **Trace 上限**：只允许 1 条 Trace、每条最多 10 个 SVID（已记录边界，按现场需求决定是否扩）。

## 2. P1：配置与运行时

- [ ] **模板 DAG / 表达式 / 参数分层没有生产入口**：Schema v4 没有 `templates` / `curves` / `params`；`Main` 以 `values=null` 构造会话，永远走 `DefaultCurves`。需要 Schema v5 + 模板注册表 + 图装载器。
- [ ] **没有内置模板库**：`thermoLoop` / `gasPanel` / `alarmTriplet` 未做。
- [ ] **单会话**：一个进程只能装一份配置；`SessionManager` 只有 `register` / `closeAll`，没有运行时创建/关闭单个会话。
- [ ] **无热重载**：配置只在启动时读一次。
- [ ] **无配置管理 API/界面**：不能读取、校验、保存配置，也没有配置编辑器。
- [ ] **无事件编排控制**：不能运行时列出/启用/禁用 `EventPlan`；界面只能手动填 CEID。
- [ ] **`work-dir` 未真正使用**：README 说它是配置/日志/模板目录，实际只做 UTF-8 自检；日志写 stderr，配置由 `--config` 任意指定。
- [ ] **无设备控制状态模型**：E5 的 INIT/COMMUNICATING/ONLINE 等状态没有本地建模，只有 `communicatable` 布尔值。
- [ ] **无审计**：README 声称 secs-app 有“审计”，实际没有 audit 类；手动触发 CEID、配置装载都没有审计记录。

## 3. P1：API 与前端

- [ ] **WebSocket**：README / pom / package-info 声称有，实际只有 SSE。
- [ ] **二进制曲线流**：没有 `series.bin`；预览只有 JSON。
- [ ] **预览静默截断**：`previewJson` 用 `.limit(32)` 截断 SVID，前端不知道；buckets 上限 500。
- [ ] **前端只有 Canvas 包络 + 均值线**：没有缩放、游标读数、多轴，也没有 uPlot。
- [ ] **报文不落盘**：面板只保留 200 条，没有 `messages.jsonl`、回放、导出。
- [ ] **没有手动发送 SML/SECS 报文**：`templates/sml/**` 样例未被任何代码/测试引用。
- [ ] **没有 SVID/CEID/RPTID 目录接口**：界面默认 CEID 写死 2001，用户只能猜。
- [ ] **没有报警/终端消息/主机动态报告面板**：`hostReports` / `hostLinks` 计数在 JSON 里，但界面没展示；ADR-0006 要求的“报告来自本地还是主机”没有做。
- [ ] **没有会话生命周期操作**：创建/关闭/重开/重载配置都没有。
- [ ] **没有前端测试**：`frontend/package.json` 没有 test 脚本；CI 只跑 `tsc --noEmit` + `vite build`。
- [ ] **没有 OpenAPI 契约**：m0 承诺 M7 前落地，仓库里没有 `openapi.yaml`。

## 4. P2：分发与工程化

- [ ] **代码签名**：未做；文档自己标了“正式分发前必须签，优先级最高”。
- [ ] **安装器**：只有绿色 zip，没有 MSI/EXE 自解压/jpackage。
- [ ] **JVM 兜底包**：未做；只有 native 镜像。
- [ ] **自动更新**：未做。
- [ ] **跨平台**：只能各平台各自构建；没有 Linux/macOS 产物。
- [ ] **`secs-dist` 是空模块**：只有 pom，打包实际在 `scripts/package.ps1`。
- [ ] **原生 E2E 没进 verify**：`NativeRuntimeIT` / `NativeSmokeIT` 是 `*IT`，Surefire 默认不跑；CI 的 native job 只在每周/手动触发，且只做 READY + health 冒烟。
- [ ] **里程碑验收脚本只有 M0/M1**：M2–M9 没有对应 `accept-*.ps1`。
- [ ] **构建前置未固化**：项目要求 JDK 25，本机默认 JDK 21；没有 Maven toolchains / enforcer。
- [ ] **服务端部署**：README 说 systemd，仓库没有 `.service`；没有 Dockerfile。
- [ ] **发布未闭环**：版本仍是 `2.0.0-SNAPSHOT`；只有 `v1.0` / `v1.1` 标签；本地 master 领先 origin 28 个提交。
- [ ] **日志**：只有 stderr 单行日志，没有文件/轮转/级别；`HsmsSessionRuntime` 与 `SecsProbe` 已改用 `Log`，但 `Main` 在 `SECS_SIM_DEBUG` 下仍会打完整栈。
- [ ] **下轮评审**：`secs-cli` 端到端宿主与 `scripts/`、`packaging/` 的 PowerShell 健壮性未深评。
- [ ] **历史报告同步**：m6 报告仍写 S1F11 未做；m6b 仍写 S2F33/35/37 未做（m6c 已完成）；m0 的 OpenAPI 承诺仍未落地。README 与 RuntimeMode 的本轮同步已完成。

## 5. 已知接受风险

- JSON 数字：`1e5` 被拒；> 2^53 的整数走 double 会丢精度；重复 key 保序保留。
- `secs-protocol` 的 accept 连接上限、reply deviceId 匹配两项回退，无复现证据。
- SSE 写超时是“看门狗 + 断开”的缓解，不是 JDK API 级别的真正写超时。

## 6. 测试缺口

- [x] `globalSvids` 的 S1F3 取值（本轮已补 `DefaultCurvesTest` 与 S1F3 真链路用例）；S6F11 的同类用例仍可补。
- [x] S1F3 空列表（“全部 SVID”）语义。
- [x] S6F11 W 位 + S6F12；
- [x] S6F1 W 位 + S6F2。
- [x] 未知 Stream/Function 的 S9F3/S9F5 应答；S9F1/7/9/11 的错误分类用例仍可补。
- [ ] S5F1 / S10F3。
- [ ] OnCondition / AfterEvent。
- [ ] 多会话、配置热重载、运行时创建/关闭会话。
- [x] 打包后的 `start.cmd` 路径自检（`-CheckOnly`）；解压后真正拉起进程的全链路冒烟仍建议补进 CI。
- [ ] 前端组件/交互测试、UI E2E。
- [ ] 大规模预览（SVID > 32、buckets > 500）的截断提示与性能。
- [ ] 400 SVID / 多客户端并发的压力测试。

## 7. 建议顺序

**M10：清 P0 + 安全边界**
1. `start.ps1` 已修；补“解压 → start.cmd → READY/health/页面”冒烟。
2. `globalSvids` 已修；补配置文档与回归。
3. 决定 SERVER 模式：实现 HTTPS + 账号/角色，或把 RuntimeMode/README 的承诺降级为“明文 + 单 token，仅限内网/反代”。
4. 未知 S/F 的 S9Fx、S6F11/S6F1 W 位均已完成；下一步补 S5F1/S10F3。

**M11：协议能力**
5. S5F1 / S10F3：先定 ALCD/ALID/去抖、终端消息队列与 ACK。
6. OnCondition：定“条件持续成立时如何去抖”，在 `pumpEvents` 里每 tick 求值。
7. AfterEvent：把 `lastFireMs` 扩展为“已发生事件集合”。

**M12：配置与前端**
8. Schema v5：`templates` / `curves` / `params`，把 `TemplateGraph` 接进 `Main`。
9. 配置热重载、多会话、会话生命周期 API。
10. 二进制预览、uPlot 交互、报文落盘、手动 SML 发送、SVID/CEID 目录、报警/报告面板。
11. 前端测试与 E2E。

**M13：分发**
12. 代码签名、安装器、JVM 兜底包、自动更新、Linux 服务端打包与 systemd 单元。
13. 2.0 发布：打 tag、写 release notes、把 `2.0.0-SNAPSHOT` 改为正式版本。

## 8. 已确认完成，不要重复做

- M0–M9-c 主体功能；347 个测试全绿。
- S1F1/F2、S1F3/F4（含空列表=全部）、**S1F11/F12**、S1F13–F18、S2F17/F18、S2F23/F24、S2F31/F32。
- 未知 Stream/Function 的 S9F3/S9F5 应答。
- S6F11 / S6F1 可选 W 位 + 异步等待 S6F12 / S6F2。
- **S2F33/F34、S2F35/F36、S2F37/F38** + `HostReportTable`（m6b 报告的“未做”已过时）。
- S6F11 基本上报、S6F1 Trace、SML 渲染、曲线预览（JSON）。
- M9-b/M9-c 的 SSE 独立线程池、一次性 ticket、静态 ETag、SSE 看门狗、预览时间窗、重连补偿。
- core / json / protocol 的深评修复。
- CI 的 backend / frontend / ps1-bom / 每周 native 冒烟。
