# secs-store 修复日志（最终）

实施说明：子任务完成全部代码修改后预算耗尽未能验证；主 Agent 运行 `mvn -B -pl secs-store -am test` 确认 **42 项全绿（含约 12 个新增测试），BUILD SUCCESS**（research/qa/round1-store-test.log）。

| 编号 | 缺陷 | 状态 | 改动文件 | 备注 |
|---|---|---|---|---|
| S1 | fire 计数 upsert INSERT 分支硬编码 enabled=1 | ✅ 已完成 | PlanExecutionRepository.java | VALUES(?,?,1,…)→VALUES(?,?,0,…)：行不存在=设备已销毁/被对账清掉，在途 flush 重建不得视为已启用；启用态只由 savePlanState 写。UPDATE 分支不碰 enabled，正常路径语义不变。新增 fireFlushRebuildsRowAsDisabled / fireFlushDoesNotTouchEnabledOnExistingRow |
| S2 | Db.close 与在途写事务无互斥 | ✅ 已完成 | Db.java | close 先依次取 configWriterLock→runtimeWriterLock 再关连接（锁序固定，写路径从不嵌套，无死锁）；closed 改 volatile + ensureOpen()，closed 后读写抛明确 IllegalStateException；close 幂等。新增 closeWaitsForInFlightWriteTransaction（CountDownLatch 双闸门）/ closedDbFailsFastWithClearException |
| S3(store 侧) | updateConstants 无版本基准 | ✅ 已完成 | ConfigRepository.java | 新 API `updateConstants(String key, String newBody, Long expectedRevision, String actor)`：事务内比对 revision，不符抛 StoreException Kind.CONFLICT；expectedRevision=null 不校验；旧三参签名保留委托。新增 updateConstantsWithExpectedRevisionDetectsConcurrentEdit。runtime 侧接线见 fix-log-runtime.md |
| P2-2 | seed 撞软删同 id 主键 | ✅ 已完成 | TemplateRepository.java / ParamTemplateRepository.java | 存在性检查去掉 AND deleted_at IS NULL（软删行不复活）。新增 TemplateRepositoryTest（3 测试）+ ParamTemplateRepositoryTest 补 2 |
| P2-3 | exportTo 无净化+固定 .tmp 名 | ✅ 已完成 | ConfigRepository.java | requireExportableName（拒空名/斜杠/.. /冒号）+ tmp 名 ThreadLocalRandom hex 后缀。新增 exportRejectsPathBearingDeviceNames / exportUsesRandomizedTmpNameAndLeavesNoResidue |
| P2-4 | Schema.backup 吞 VACUUM 失败原因 | ✅ 已完成 | Schema.java | IOException.initCause(vacuumFailure) 保留异常链；复制兜底前 PRAGMA wal_checkpoint(TRUNCATE)。新增 backupFailureKeepsVacuumCauseInExceptionChain |
| P2-1 | schema 无迁移路径 | 不改（设计决策） | — | ADR-0017 漂移由文档批次加后记（见 fix-log-build.md） |
| P3-1 | 报文判冲突 | ✅ 已完成 | TemplateRepository.java | createCustom 改用 ConfigRepository.isUniqueViolation |
| P3-2 | "\|" 字符串打包 | ✅ 已完成 | ConfigRepository.java | 私有 record ConstantsUpdate(deviceId, revision) |
| P3-3 | 名字大小写 vs Windows | 跳过（设计决策） | — | 涉及 DB 唯一索引语义与既有数据 |
| P3-4 | synchronous=NORMAL | ✅ 注释级说明 | Db.java | tune 补掉电取舍说明；改 FULL 留 ADR 决策 |
| P3-5 | cleanupOrphans 七事务 | ✅ 已完成 | RuntimeStateRepository.java | 合并单 writeRuntime 事务，失败整体回滚 |
| P3-6 | System.err 诊断 | ✅ 已完成 | 相关 repository | 新增 droppedCount()/flushFailureCount() getter 供上层接指标 |

## 验证结果

- `mvn -B -pl secs-store -am test`：**BUILD SUCCESS，store 42 项全绿**（原 29 + 新增约 13），-Werror 零告警。
- 全仓终验见 final-gate-backend.log。