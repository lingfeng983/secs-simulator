# secs-runtime 修复日志（最终）

实施说明：子任务两轮均因工具预算耗尽未能落盘代码，最终由主 Agent 按已核实方案直接实施并验证。

| 编号 | 缺陷 | 状态 | 改动文件 | 备注 |
|---|---|---|---|---|
| R1a | RuntimeApi 内层/外层 catch 漏 RuntimeException | ✅ 已完成 | RuntimeApi.java | 内层 catch 改 `IOException \| RuntimeException`（ConfigException 是其子类，避免并列编译错）；外层补 RuntimeException 分支返回 FAILED |
| R1b | SessionLauncher 注册失败不回滚旧会话 | ✅ 已完成 | SessionLauncher.java | 目标变更路径 catch 内 opened.close() 后复用 restore(id, source, source.lastGood(), e) 拉回旧会话 |
| S3 | ConfigStore.writeConstants 无冲突重试 | ✅ 已完成 | ConfigStore.java | 读 DocumentView 带 revision，调 store 新四参 updateConstants(key,newBody,expectedRevision,"device")；CONFLICT 重读重算最多 3 次，仍冲突放弃记日志；合并逻辑抽 mergeConstants 静态 helper |
| P2-1 | applyHot 多步替换非原子 | ✅ 已完成 | HsmsSessionRuntime.java | applyHot 全量签名方法体整体包 synchronized(pumpLock)，心跳不出混合快照 |
| P2-2 | 主机实例无上限 | ✅ 已完成 | HostSimRegistry.java | MAX_HOST_INSTANCES=64；open 超限先拒（不写持久行）；loadPersisted 超限跳过并记日志；类注释口径同步 |
| P3 | SessionSender 关闭窗口 NPE 噪音 | ✅ 已完成 | SessionSender.java | reply/send 先取 communicator 判空，close 竞态安静丢弃 |
| P3 | emitted/wbitDropped 死字段 | ✅ 已完成 | HsmsSessionRuntime.java | 删两字段 + AtomicLong 死 import（真身在 EventReporter/TracePump） |
| P3 | SessionLifecycle.lastFireMs 只写不读 | ✅ 已完成 | SessionLifecycle.java | 删字段+赋值+javadoc（真身在 EventScheduler） |
| P3 | HostSimRegistry 重复 import | ✅ 已完成 | HostSimRegistry.java | 删重复 HsmsConnectionTarget import |
| P3 | RuntimePersister:84 注释口径 | ✅ 核实无需改 | — | 与 clearRuntimeEdits 实现（只清 pin/override/catalog）核对一致 |
| Arch | ArchUnit 哨兵 + 方向规则 | ✅ 已完成 | ArchitectureTest.java | 新增 importerActuallySeesClasses（>300 类 + 5 关键包非空）；app↛api/runtime/cli/store、api↛runtime/cli/store、store↛上层、hostsim↛runtime 根 4 条方向规则（均不带 allowEmptyShould）；6→11 条规则全绿 |
| Core | CycleRunnerTest 右开端点断言 | ✅ 已完成 | CycleRunnerTest.java | 3 处断言 10_000→9_999、2_000→1_999、86_400_000→86_399_999（上游 core 相位契约 [0,duration) 修复的预期 fallout） |

## 千行拆分（评审 A1/A2/P2-5，主 Agent 实施）

| 类 | 拆分前 | 拆分后 | 抽出物 |
|---|---|---|---|
| HsmsSessionRuntime | 1331 | **986** | SectionApplySupport（分段生效判定+落地，Parts 访问器接线）、SessionMetricsWiring（指标 supplier 装配）；冗长 javadoc 压缩保留"为什么" |
| RuntimeApi | 1238 | **912** | ConfigPreviewApi（草稿预览）、DeviceBatchCreate（批量新建）、SectionsJson（分段响应 JSON 拼装 9 个静态 helper） |
| HostSimulator | 1022 | **985** | HostCollectorTypes（CollectorSvid/CollectorConfig 记录迁移，HostCollector/HostCollectors 引用同步） |

拆分原则：逻辑逐行等价迁移、公开 API 不变（SectionEffects 保留兼容别名）、每次拆分后跑模块测试验证。

## 验证结果（实际运行）

- `mvn -B -pl secs-runtime -am test`（JDK 25 GraalVM）：**BUILD SUCCESS**，runtime 168 项全绿（含 ArchUnit 11 条、CycleRunnerTest 9 项）；上游 core 111 / store 42 / protocol 59 / app 87 / api-http 143 全绿。
- 拆分分 6 轮增量验证（research/qa/split1..6-test.log），每轮 BUILD SUCCESS。
- 新增测试：ArchUnit 哨兵+4 方向规则（5 个新 @Test）；R1/S3 的行为级回归依赖既有 reload/writeConstants 测试面 + 全量 E2E（secs-cli 86 项）覆盖，未新增专用注入测试（SessionManager final 且观察者异常被吞，注入点受限——已在评审报告遗留项说明）。

## 遗留

- R1b/S3 的专用故障注入单测未新增（注入点受限）；行为由既有测试面与代码审查保证。
- dim04 其余 P3（端口试绑 TOCTOU 等）未修：TOCTOU 属设计权衡（先试绑再正式绑的窗口极小且有 lastGood 兜底），记录保留。