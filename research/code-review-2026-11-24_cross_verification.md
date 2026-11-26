# SECS Simulator 全量代码评审 · 交叉验证

Route: C（File-only）
Evidence Budget: 8 个维度子评审（各 22-36 次工具调用预算）+ 父 Agent 约 30 次定向复核读取/搜索 + 4 项门禁实际执行；外部检索 0 次（file-only 约束）。问题组规划 8 个，实际覆盖 8 个（--min-dimensions 8）。

## 问题地图（question map）

| 问题 | 需要的支持 | 来源 | 结论与限制 | 状态 |
|---|---|---|---|---|
| 依赖铁律是否真实落地 | POM+import 全量抽查+ArchUnit 通读 | dim01 + 父复核 ArchitectureTest 全文 | 落地成立；门禁本身有覆盖缺口（F-ARCH-1/2） | 已核实 |
| 协议层是否符合 E4/E5/E37 | 状态机/应答匹配源码 + docs/reference 对照 | dim02 + 父复核 AbstractHsmsSsCommunicator/HsmsSsReplyMessageManager | 2 个 P2 确认缺陷（F-PROTO-1/2）；Q-1 存疑待 E37.1 原文 | 已核实（Q-1 未决） |
| 值引擎纯函数与边界 | curve/value/cycle/compile 源码 | dim03 + 父复核 BoundedValueSource/EquipmentCompiler | 铁律成立；F-CORE-1/2 证实 | 已核实 |
| 重载/关闭/并发是否安全 | RuntimeApi/SessionLauncher/HotReloader 源码 | dim04 + 父复核 reload catch 链 | F-RT-1 证实（RuntimeException 漏网） | 已核实 |
| 存储一致性与原子性 | store 12 文件全文 + ConfigStore | dim05 + 父复核 PlanExecutionRepository/clearRuntimeEdits/savePlanState | F-ST-1/2/3 证实；F-ST-1 触发前提收窄（见冲突区） | 已核实（前提已修正） |
| openapi 与实现是否一致 | yaml 71 path ↔ 路由/分发表 | dim06 + 父程序化 yaml 解析复核 | 4 项漏声明证实（F-API-1..4）；dim06 原稿"72 条"系笔误已订正为 71 | 已核实 |
| 前端历史修复是否落地+新问题 | 历史报告第八节 ↔ 当前源码 | dim07 + 父复核 DeviceOpsPanel/useBackend/useServerOps/configs.ts | 6/7 抽查属实；F-UI-1/2/3 证实 | 已核实 |
| CI/打包/文档与实现一致 | ci.yml/check.ps1/package.ps1/main.rs/README | dim08 + 父复核 main.rs/README | F-BUILD-1/2/3 证实 | 已核实 |
| 当前代码是否全绿 | 实际运行门禁 | mvn test / tsc / vitest / check-openapi | 763+258 全绿，openapi OK | 已核实 |

## High Confidence（父 Agent 直接回读源码复核过的关键结论）

以下每条均由父 Agent 在子评审之外独立读取源码证实（双向证据）：

1. **F-ARCH-1** ArchitectureTest 全部 6 条规则 `allowEmptyShould(true)`，无导入类数哨兵；app/api/store/runtime 相互方向无规则。证据：ArchitectureTest.java:28-31,38,48,57,67,77,91（全文 94 行通读）。
2. **F-ARCH-2** 超千行类 3 个：HsmsSessionRuntime 1331 / RuntimeApi 1238 / HostSimulator 1022（read_file 总行数）。
3. **F-PROTO-1** 发起方 Deselect.req 走 default 分支发完即返回，不等 T6 应答：AbstractHsmsSsCommunicator.java:481-483（switch 仅 SELECT_REQ/LINKTEST_REQ）、:539-544（default）。
4. **F-PROTO-2** 控制事务应答匹配恒 true：HsmsSsReplyMessageManager.java:164-167 `if ( this.control ) { return true; }`。
5. **F-CORE-1** F4 钳制上界 Double.MAX_VALUE：BoundedValueSource.java:123 `case F4, F8 -> new Bounds(-Double.MAX_VALUE, Double.MAX_VALUE);`。
6. **F-CORE-2** 限定报告索引 last-wins：EquipmentCompiler.java:263 `this.reportByQualified.put(...)`（对比 :255 reports 表用 putIfAbsent+报错）。
7. **F-RT-1** reload 回滚 catch 范围缺口：RuntimeApi.java:483 `catch (ConfigException | IOException failure)` vs SessionLauncher.java:219-223 `catch (RuntimeException e) { ... throw e; }`；且目标变更路径 :215 先 `this.close(id)` 再注册，注册失败旧会话不拉回。
8. **F-ST-1** fire 计数 upsert INSERT 分支硬编码 enabled=1：PlanExecutionRepository.java:224-225 `VALUES(?,?,1,…)`；行被删后 flush 重建可复活已禁用计划。
9. **F-ST-2** Db.close 用 this 锁、写事务用 writerLock，无互斥（dim05 通读 Db.java:346 vs :235/248；父复核调用链）。
10. **F-ST-3** S2F15 常量回写读改写无版本基准（dim05 通读 ConfigStore.writeConstants:655-696 + ConfigRepository.updateConstants:196-226）。
11. **F-API-1..4** openapi 71 path 中无 `DELETE /api/v2/devices/{id}`（裸 path）、无 apply-sections、无 configs sections、无 host-sims burst——父 Agent yaml.safe_load 程序化核对 + 实现侧 DeviceRuntimeHandler.java:67/77、ConfigHandler.java:399、HostSimHandler.java:219 证实四端点真实存在。
12. **F-UI-1** 批量同步绕过 If-Match：DeviceOpsPanel.tsx:186 `saveConfig(targetConfig, text, null)` vs configs.ts:203-205（null 时不加 If-Match 头）。
13. **F-UI-2** 保存无人使用的配置 fallback 重载全部会话：useBackend.ts:434-437 `const targets = matched.length > 0 ? matched : sessions;`。
14. **F-UI-3** burst 轮询 `for(;;)` 无取消，最长 6 分钟：useServerOps.ts:70-91。
15. **F-BUILD-1** README.md:158-159 仍列已删 v1 触发端点（SessionHandler.java:479-482 注释证实迁 v2 回 404）。
16. **F-BUILD-2** README.md:29 store 依赖行多写 core（POM 实际仅 json+sqlite-jdbc）。
17. **F-BUILD-3** main.rs:292-295 无条件 `use std::os::windows::process::CommandExt`，非 Windows 编译必败（对比 :271-274 有 cfg 分支）。
18. **F-GATE-1** 后端 mvn test 763 项全绿（8 模块 110/56/89/29/87/143/163/86）、前端 tsc 0 错、vitest 258 项全绿、openapi 71 path OK——当日本机实测，日志在 research/qa/。

## Medium Confidence（子评审通读、父 Agent 抽验调用链但未逐行复核全文）

- dim04 热重载多步替换非原子（HotReloader.java:95-111 各步独立加锁）——子评审通读，父未复读 HotReloader 全文。
- dim05 TemplateRepository.seed 软删同 id 复活冲突、Schema.backup 吞错——子评审全文通读 store 12 文件，父未逐条复核。
- dim07 8 处组件级 setInterval 清单、消息监控渲染热点——子评审 grep+读文件，父未逐处复核。
- dim08 CI 缺 check-ui-words.ps1、GUI Rust 无 CI——子评审通读 ci.yml，父未复读 yml（结论与 README 声明矛盾明确，可信度高但按流程归中置信）。
- dim02 历史 14 项修复核实——子评审逐条给行号，父抽验 2 项（secondary-only、S9F7 相关分发）在位。
- dim03 RUNNING 态相位越界窗口、FINISHED 冻结相位=duration——子评审通读 cycle 包并给出 CycleRunner 缓解证据，父未复读。

## Low Confidence / 存疑

- **Q-1（dim02）**：主动端把 Select.rsp status=1 当成功（AbstractHsmsSsActiveCommunicator.java:198-204），与 docs/reference 通用整理版"非 0 即失败"相左；整理版自述未覆盖 E37.1 增量，英文原文不在库。**不定性为缺陷**，需标准原文裁决。
- dim03 "Context 配数值型 source 静默变空串"与"无类型 SVID NaN/Inf 无钳制"两条 P2 带前提：config 层是否拦截该组合未核实。
- dim06 前缀内子路径级死声明不能排除（未逐一读取分发实现）。

## Conflict Zones（冲突与订正记录）

1. **openapi path 计数**：dim06 原稿写 72 条，父 Agent yaml.safe_load 与 check-openapi.py 双口径实测 **71 条**。已订正 dim06 全文（5 处），结论（4 项漏声明）不受影响——漏声明判定基于逐 path 存在性而非计数。
2. **F-ST-1 触发前提收窄**：dim05 原稿称"行被 cleanupOrphans/reload 清掉后 flush 重建"。父复核：`clearRuntimeEdits`（RuntimeStateRepository.java:525-539）只清 pin/override/catalog 三表，**不清 runtime_plan_state**（RuntimePersister.java:84 注释明言"计划启用态不清"）；`cleanupOrphans`（:485-506）只删**非存活设备**的行。因此"重载清行→复活"路径不成立；真实触发面收窄为：设备销毁（行被清）后、异步 fire 队列仍有在途计数 flush 到同一 device_id，或未来新增清 plan_state 的路径。缺陷本体（INSERT 硬编码 enabled=1 与 savePlanState 的 enabled 语义冲突，PlanExecutionRepository.java:225 vs RuntimeStateRepository.java:272）仍然成立，严重度维持 P1 但触发概率下调。**最终报告按收窄后的前提表述。**
3. **前端规模口径**：任务简报给"178 文件"，dim07 实测 src 下 206 文件（含 css/test 等全部文件）；178 为 ts/tsx 口径（find 实测），两者不矛盾，最终报告注明口径。
4. **README"同一组检查"声明 vs ci.yml**：dim08 证实 CI 前端 job 缺文案门禁步骤——文档与实现冲突，按实现为准记 P1。
5. **native-nightly"非阻断"口径**：README 说非阻断，yml 注释说失败即红——两者可并存（不阻断 PR 合并但夜间会红），记文档措辞 P2 而非功能缺陷。

## Validation Updates

- 2026-11-24：dim01/dim03 子任务预算耗尽未落盘，dim01 由父 Agent 按子评审返回全文代写落盘；dim03 重派（预算纪律前置）后正常落盘。dim02/04/05/06/07/08 均正常落盘。
- 2026-11-24：dim06 "72 条"笔误订正为 71（见冲突区 1）。
- 2026-11-24：F-ST-1 前提收窄（见冲突区 2），dim05 原文保留作为历史，最终报告以本文件收窄口径为准。
- 门禁日志：research/qa/backend-mvn-test.log、ui-tsc.log、ui-vitest.log；问题计数：research/qa/issue-counts.json（P0=0，P1=18，P2=29，P3=41，合计 88 条，按各维度问题清单逐条程序化计数）。