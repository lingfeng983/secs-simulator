# 存储层专项评审（维度 05）— secs-store / ConfigStore

- 日期：2026-11-24
- 范围：`secs-simulator-service/secs-store/src/main/java`（12 文件，全部通读）+ `secs-runtime/.../ConfigStore.java`（802 行，全部通读）+ `docs/adr/0017-sqlite-config-store.md`（全文）+ store 测试方法清单（grep 命中 35 个方法名，未逐个读测试体）
- 性质：只读评审，未修改任何源码，未运行测试

## 现状概述

存储层按 ADR-0017 落地为双 SQLite 库：`config.db`（device / config_document / config_revision / device_template / param_template / host_template / host / host_plan / meta）与 `runtime.db`（device_link / host_link / runtime_plan_state / plan_execution / runtime_svid_pin / runtime_plan_override / runtime_catalog_edit / runtime_host_report / link_event / meta），DDL 见 `Schema.java:69-262`。

关键架构事实（均已读到源码确认）：

1. **单写者 + 每次新建读连接**：每库一条常驻写连接，`synchronized` 串行化，事务用裸 SQL `BEGIN IMMEDIATE → COMMIT/ROLLBACK`（`Db.java:234-268`）；读路径每次从 `SQLiteDataSource` 取新连接并逐条重设 PRAGMA（`Db.java:205-224`），无连接池（注释里明确了取舍，`Db.java:198-203`）。
2. **跨进程互斥**靠伴生文件 `config.db.lock` 的 `FileChannel.tryLock`，刻意不锁 db 本体以避开 Windows 字节段锁冲突（`Db.java:25-28, 154-175`）。
3. **schema 版本自建 meta 表**（非 `user_version`），当前 `VERSION = 3`（`Schema.java:32`），不匹配即拒绝启动；`--allow-schema-reset` 才备份（优先 `VACUUM INTO`）后 DROP 重建（`Schema.java:275-293`）。**没有任何 schema 迁移路径**。
4. **两处与任务背景/ADR 的重大偏差**：
   - **2.x JSON 一次性迁移已被整体移除**。`RuntimeOptionsTest.java:64` 逐字注释：「迁移入口已随 ConfigMigrator 一起移除，残留 {@code --migrate-config} 应报未知参数」，且测试断言 `--migrate-config` 抛 `IllegalArgumentException`（`RuntimeOptionsTest.java:73-74`）。全仓 main 源码 grep `Migrat` 仅命中注释。ADR §4 描述的幂等迁移/`legacy/` 归档/`legacy_id` 均无对应实现；`Schema.java` 的 device 表也没有 `legacy_id` 列。
   - **结构化投影表已不存在**。`ConfigRepository.java:24-25`：「正文之外不维护任何结构化副本：查询与展示一律解析正文」；`SchemaVersionTest.java:118` 有测试 `configSchemaHasNoProjectionTables`。任务背景与 `secs-store/pom.xml:22` 注释提到的 `ConfigRepository.DocumentProjector` **在代码中不存在**（全仓 grep 仅命中该 pom 注释），属于陈旧注释。
5. **依赖纪律合规**：`secs-store/pom.xml:24-36` 仅声明 secs-json、sqlite-jdbc、junit-jupiter；12 个 main 文件的 import 中无任何 secs-app / secs-api-http / secs-runtime 类型（JSON 用 `com.shimizukenta.jsonhub`，即 secs-json）。
6. `ConfigStore` 位于 **secs-runtime**（非 store），是 v1 文件名语义到 config.db 的门面；配置文件原子写仅残存于 `ConfigRepository.exportTo`（导出通道）。

## 逐项核查

### 1. ADR-0017 设计意图 vs 实现

ADR 承诺 WAL + foreign_keys + busy_timeout=5000、单写者、进程文件锁、VACUUM INTO 备份、revision 乐观锁、S2F15 不写快照——这些均已实现（见下）。偏差：ADR §3 写「`ReentrantLock` + 专用虚拟线程」，实现是 `synchronized` + 调用者线程（`Db.java:235, 248`），台账写线程是**平台线程**（`PlanExecutionRepository.java:81` `Thread.ofPlatform()`）；ADR §2 投影表、§4 迁移链路已移除（见上）。ADR 未随实现更新，属文档漂移。

### 2. Db.java：连接、事务、并发

- 驱动加载：`Db.java:136` `Class.forName("org.sqlite.JDBC")`，失败抛 `StoreException("找不到 sqlite-jdbc 驱动")`；本地库由 `DbBootstrap.ensureNativeLibrary` 先释放到 `<work-dir>/native/` 并设 `org.sqlite.lib.path/name`（`DbBootstrap.java:30-46`）。
- PRAGMA（`Db.java:146-149` 逐字）：
  ```java
  st.execute("PRAGMA journal_mode=WAL");
  st.execute("PRAGMA foreign_keys=ON");
  st.execute("PRAGMA busy_timeout=5000");
  st.execute("PRAGMA synchronous=NORMAL");
  ```
  读连接同样逐条重设（`Db.java:205-224`），注释正确指出 foreign_keys/busy_timeout 是连接级属性。`synchronous=NORMAL` 在 WAL 下意味着掉电可能丢最近事务（不损坏库），对配置库是可讨论的取舍，代码未注释这一取舍。
- 事务边界（`Db.java:253-268`）：锁内 `BEGIN IMMEDIATE`，注释解释了为何不用 `setAutoCommit(false)`（「sqlite-jdbc 的 setAutoCommit(false) 自带隐式 BEGIN，再发 BEGIN IMMEDIATE 会撞 "within a transaction"」，`Db.java:254-255`）。失败 `rollbackQuietly` 把回滚异常 `addSuppressed` 到主异常（`Db.java:277-283`），不掩盖原始错误——正确。
- 打开失败路径逐级清理：writer 关闭失败挂 suppressed、锁释放（`Db.java:105-121`）——严谨。
- **缺口**：`close()`（`Db.java:345-362`）以 `synchronized(this)` 保护，但 `writeConfig` 用 `configWriterLock`，两把锁不同——close 与在途写事务可并发，写连接可能被中途关闭；`closed` 标志也不被 write/read 路径检查。停机顺序完全依赖上层调用纪律（未核实上层）。
- SQLite 并发写：进程内单写者 + 进程间文件锁，busy_timeout 只是兜底；WAL 下读写不互锁的声明与实现一致。

### 3. Schema / 迁移路径

- 版本判定「先读后判」（`Schema.java:277-291`）：`found != 0 && found != VERSION` 时拒绝，报错文案含操作指引（`Schema.java:281-285`）。`readVersion` 对 meta 表缺失/键缺失返回 0（视为新库），非整数抛 SQLException（`Schema.java:302-319`）——不会静默覆盖版本号。
- 重置：`backup()` 优先 `VACUUM INTO '<path>'`（单引号转义 `'→''`，`Schema.java:360`），失败**吞掉 SQLException** 退化为复制 db + `-wal` + `-shm` 三文件（`Schema.java:362-372`）；复制也失败才抛「拒绝继续重建」。备份文件名带毫秒时间戳（`Schema.java:357-358`）。`dropAllTables` 先关外键再 DROP，标识符双引号转义（`Schema.java:390-404`）。
- `createAll + writeVersion` **不在事务里**（`Schema.java:290-291`）：中途崩溃留下部分表、版本未写 → 下次启动按「新库」走 `CREATE TABLE IF NOT EXISTS` 补齐，可自愈；重置路径中途崩溃有 .bak 兜底。半迁移风险因迁移功能已移除而不存在，但 **schema v1/v2 → v3 没有任何数据迁移**：要么手工删库重导，要么 `--allow-schema-reset` 备份后清库，备份是 SQLite 文件而非 JSON，普通用户无工具导回（`exportTo` 只导当前库）。
- 2.x JSON 迁移的幂等性/半迁移状态核查结论：**功能已不存在**，仅存 `ContentHash.java:10`「迁移自检的判据是"逐字节相符"」等注释残迹；`--config` 仍是显式单文件导入（`RuntimeOptions.java:167`），其实现未读（见置信度）。

### 4. ConfigRepository / HostRepository：注入、投影、保真

- **SQL 注入**：两仓储所有外部输入均走 `PreparedStatement` 占位符。字符串拼接仅限内部常量：`RuntimeStateRepository.overrides` 的表名/keyExpr 来自固定字面量（`RuntimeStateRepository.java:349, 375, 381`）、`cleanupOrphans` 表名白名单 + `nCopies("?")` 占位（`RuntimeStateRepository.java:486-495`）、`PlanExecutionRepository.trim` 拼 int 常量（`PlanExecutionRepository.java:320-324`）、`ParamTemplateRepository.pruneBuiltin` 占位符（`ParamTemplateRepository.java:225-227`）。**未发现注入面**。
- 乐观锁（`ConfigRepository.java:152-157` 逐字）：
  ```java
  if (expectedRevision != null && expectedRevision != currentRevision) {
      throw StoreException.conflict("配置在编辑期间被修改过（当前 v" + currentRevision
  ```
  Long 与 long 比较自动拆箱，无装箱同一性陷阱；正文 SHA 相同则幂等返回当前 revision（159-161）。快照裁剪 `LIMIT 1 OFFSET 199`（`ConfigRepository.java:545-555`），保留 200 版。
- 唯一冲突判定用 sqlite-jdbc 扩展错误码 2067/1555，报文匹配仅兜底（`ConfigRepository.java:432-442`）；但 `TemplateRepository.createCustom:99` 仍只用 `getMessage().contains("PRIMARY")` 文本判据，与自家注释宣称的原则不一致。
- **投影注入机制不存在**（见现状 §4），文档漂移。
- **原文保真**：`saveDocument` 原样存传入 body；`exportTo` 用 `Files.writeString(tmp, body, UTF_8)` + 原子 move（`ConfigRepository.java:388-407`），测试 `exportRoundTripsBytesExactly` 存在。但 S2F15 路径 `ConfigStore.writeConstants:696` 提交的是 `b.object(rootPairs).prettyPrint()`——**整份正文经 JsonHub 往返重排格式**，键序保留、字节不保真（ADR §2 允许结构化编辑重排，但「逐字节」声明只对导出成立）。### 5. runtime.db 台账与重载恢复语义

- 钉值/计划启用态/覆盖/目录编辑全部是 `INSERT … ON CONFLICT … DO UPDATE` 的单行 upsert，各自独立写事务（`RuntimeStateRepository.java:215-292, 328-371`）。`savePlanState` 的 `start_at` 只在「禁用→启用」沿刷新（`RuntimeStateRepository.java:273-274`），语义细致。
- 启动对账：`resetStaleDevicePhases` 把 CONNECTED/LISTENING/STARTING 收敛为 DISCONNECTED（`RuntimeStateRepository.java:102-113`）；`cleanupOrphans` 按存活设备白名单清 7 张表（`RuntimeStateRepository.java:485-521`），注释自认「过渡期逻辑」且**按表逐个开事务**——中途失败会留下部分清理状态（下次启动重跑可收敛，危害低）。
- 重载清空：`clearRuntimeEdits` 在**一个事务**里清 pin/plan_override/catalog_edit 三表（`RuntimeStateRepository.java:525-539`）——原子性正确；但不清 `runtime_plan_state` 与 `runtime_host_report`，是否属预期未核实（生命周期层未读）。
- **执行台账**（`PlanExecutionRepository.java`）：发送线程只入 `ArrayBlockingQueue(4096)`，专用写线程 50ms/64 条批量落库，台账行与 fire 计数同队列同事务（`PlanExecutionRepository.java:62-68, 153-176`）；幂等键 `ON CONFLICT(idempotency_key) DO NOTHING`（182）；满队丢当前条并限频打印（107-113，注释明确「绝不自旋」的理由）；批写失败不杀线程、计数留痕（131-138）；停机 `interrupt + join(2000)` 后把剩余冲完（141-150, 344-356）。裁剪用窗口函数 `ROW_NUMBER() OVER (PARTITION BY device_id …)`，注释解释了为何不用标量子查询（317-322）——这是修过的真 bug 的痕迹。
- **一致性缺口（见问题清单 P1-1）**：`upsertFires` 的 INSERT 分支硬编码 `enabled=1`（`PlanExecutionRepository.java:225` `VALUES(?,?,1,?,?,?,?,?)`）。异步批写与用户「禁用计划」的 `savePlanState(enabled=0)` 竞争时，若计数行恰被清理/重置后由 flush 重建，会把已禁用计划写回 enabled=1。
- 可空整数列统一走 `longOrNull`（`RuntimeStateRepository.java:301-304`），注释记录了 SQLite JDBC 小整数返回 Integer 的 ClassCastException 教训。

### 6. ConfigStore 原子写与 requireFileName

- `ConfigStore` 本体**不再写文件**（正文全部入库）；原子写残存两处：
  - `ConfigRepository.exportTo`（`ConfigRepository.java:388-407`）：`tmp = dir.resolve(fileName + ".tmp")` 固定名——同目录并发导出会互踩（进程锁挡跨进程，进程内无挡）；`move()` 先 `ATOMIC_MOVE`，`AtomicMoveNotSupportedException` 时退化 `REPLACE_EXISTING`（非原子，Windows 上目标被占用会抛 IOException 而非静默半写）。tmp 与目标同目录，天然同盘，无跨盘 rename 问题。崩溃窗口：tmp 写完、move 前崩溃只留 `.tmp` 垃圾，目标旧文件完好——可接受。**导出文件名未做 requireFileName 校验**：`device.name()` 来自 createDevice，而 `createDevice` 的 name 参数除 requireNonNull 外无字符校验（`ConfigRepository.java:74-101`）；经 ConfigStore 创建时受 requireFileName 保护，但仓储可被其它调用方直接使用（如 `--config` 导入路径，未核实），含 `/` 或 `..` 的 name 会使 `dir.resolve` 越界写文件。
  - `Schema.backup` 的 VACUUM INTO / 三文件复制（见 §3），复制路径本身非原子，但仅用于备份。
- `requireFileName`（`ConfigStore.java:740-758` 逐字核心）：
  ```java
  if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..") || trimmed.contains(":")) {
      throw new ConfigException(field, "只能是 --work-dir 下的文件名，不能带路径");
  }
  ```
  外加 `.json` 后缀强制与 `Path.of` 合法性试构造。覆盖了路径分隔符、`..`、盘符/ADS（`:`）；**未覆盖** Windows 保留设备名（`CON.json`、`NUL.json` 等——如今正文入库、无实际文件创建，危害仅剩 exportTo 时在 Windows 上写失败）与尾随点/空格（`"x.json "` 已被 trim，`"x.json."` 不以后缀结尾会被拒）。由于名字最终只是 DB 键而非路径，穿越风险实际很低；真正用名字拼路径的只有 exportTo（见上）。大小写：Windows 文件系统大小写不敏感，但 SQLite `name=?` 精确匹配大小写敏感——`Line1.json` 与 `line1.json` 是两台设备，导出到同一目录在 Windows 上会互相覆盖（P3）。

### 7. 测试覆盖（store/src/test，5 文件）

grep 到 35 个方法（含 setUp/工具方法），测试名清单：
- `DbTest`：openCreatesBothDatabasesWithSchema / secondOpenOnSameWorkDirIsRejected / rollbackOnFailedTransaction / nativeLibraryIsExtractedIntoWorkDir——覆盖建库、进程锁、事务回滚、本地库释放。
- `SchemaVersionTest`：版本不匹配拒启且不动库 / Db.open 透传拒绝 / allowReset 备份重建 / 缺 meta 视为新库 / configSchemaHasNoProjectionTables / deviceLinkHasNoDeadColumns——版本策略覆盖良好。
- `ConfigRepositoryTest`：创建保存幂等 / revision 冲突 / 重名拒绝 / updateConstants 不写快照且追平 runtime / 保留 200 版裁剪 / name-or-id 解析 / 软删保历史释名 / **exportRoundTripsBytesExactly** / 版本历史倒序。
- `HostAndExecutionStoreTest`：host CRUD 与 setting_revision / host 模板 / 台账队列 flush 与查询 / **fireRecordsAreAggregatedInTheSameBatchAsLedger** / link_event 与孤儿清理。
- `ParamTemplateRepositoryTest`：seed 幂等与内置只读 / 自定义 CRUD / 重复冲突 / notFound。

## 问题清单

**P1（应尽快修）**

- **P1-1 异步 fire 计数可将已禁用计划复活为 enabled=1**。证据：`PlanExecutionRepository.java:224-225` INSERT 分支 `VALUES(?,?,1,…)`；用户禁用走 `RuntimeStateRepository.savePlanState`（`RuntimeStateRepository.java:266-292`）两条路径无共享锁序保证。行存在时 UPDATE 分支不改 enabled（安全），仅 INSERT 分支（行被 cleanupOrphans/reload 清掉后 flush 重建）触发。建议：INSERT 分支 enabled 默认 0，或 ON CONFLICT 前先查行、或把 enabled 从该 upsert 的插入列中拆出。
- **P1-2 `Db.close()` 与在途写事务无互斥**。证据：`Db.java:346` `synchronized`（this 锁）vs `Db.java:235/248`（configWriterLock/runtimeWriterLock）；close 不等待写锁、write/read 不查 `closed`。停机时若台账写线程或请求线程仍在写，`configWriter.close()` 可把连接从写事务脚下抽走，得到混乱的 SQLException 而非有序停机。建议：close 先依次获取两把写锁再关连接，并让 closed 后的 write/read 快速失败。
- **P1-3 S2F15 常量回写非原子读改写，可覆盖并发编辑**。证据：`ConfigStore.writeConstants:655-696` 先 `readDocument`（独立读连接、无版本基准），改 constants 子树后 `updateConstants`；而 `ConfigRepository.updateConstants:196-226` 事务内只读 revision 自增，**不校验正文是否仍是读到的那份**。读与写之间用户 saveDocument 提交的新正文会被基于旧正文的 newBody 整体覆盖（丢失用户编辑，且无 409）。ADR §5 只承诺「单写者串行化」，但串行化不等于读改写原子。建议：updateConstants 增加 expectedSha/expectedRevision 参数，事务内比对不符即冲突重试。

**P2（计划内修）**

- **P2-1 schema 无迁移路径且 ADR/pom 注释与实现漂移**。证据：`Schema.java:24-27`「本版本不提供迁移」；`RuntimeOptionsTest.java:64` ConfigMigrator 已移除；`secs-store/pom.xml:22` 仍引用不存在的 `ConfigRepository.DocumentProjector`；ADR §2 投影表、§4 迁移、§3 ReentrantLock/虚拟线程均与实现不符。升级用户只能删库或 reset，历史版本快照随 .bak 封存、无导回工具。建议：更新 ADR（或补 superseding ADR）、清理陈旧注释；为 v-next 预留导出→重建→导入通道（Schema.ResetStrategy 接口已留位）。
- **P2-2 `TemplateRepository.seed` 对软删同 id 行必然失败**。证据：`TemplateRepository.java:180-202`：存在性检查带 `deleted_at IS NULL`，但 INSERT 撞的是全表主键——若同 id 行已软删，检查放行、INSERT 抛 PRIMARY KEY 冲突且被包成 GENERAL（非 CONFLICT），每次启动播种都失败。`ParamTemplateRepository.seed:174-196` 同构。建议：检查不带软删过滤，或 `ON CONFLICT(id) DO NOTHING`（HostRepository.seedTemplate:268 已是正确写法）。
- **P2-3 exportTo 无文件名净化 + 固定 .tmp 名**。证据：`ConfigRepository.java:388-393`；`createDevice` 不校验 name 字符（`ConfigRepository.java:74-101`）。仓储级调用方传入含路径分隔符的 name 时导出可越界写。建议：exportTo 内复用与 requireFileName 等价的校验（store 层不能 import ConfigStore，可在 store 内实现），tmp 名加随机后缀。
- **P2-4 `Schema.backup` 吞掉 VACUUM INTO 的真实失败原因**。证据：`Schema.java:359-364` catch(SQLException) 空注释体，磁盘满/权限错也走三文件复制兜底；WAL 活跃时复制三文件本身可能不一致（注释只考虑「版本过旧/目标已存在」两种良性原因）。建议：区分「不支持 VACUUM INTO」与其它 SQLException，后者直接失败；或复制前 `PRAGMA wal_checkpoint(TRUNCATE)`。

**P3（低危/打磨）**

- **P3-1** `TemplateRepository.createCustom:99` 用报文 `contains("PRIMARY")` 判冲突，未复用 `ConfigRepository.isUniqueViolation`（后者注释自己声明报文判据不可靠）。
- **P3-2** `updateConstants` 用 `deviceId + "|" + next` 字符串打包跨事务传值再 `lastIndexOf('|')` 拆（`ConfigRepository.java:225-229`）——可用 record，避免脆弱协议；ULID 不含 `|` 所以当前无实际 bug。
- **P3-3** 设备名大小写敏感（DB）vs Windows 文件系统不敏感：`A.json`/`a.json` 两台设备导出同目录会互相覆盖（`ConfigRepository.java:388`）。
- **P3-4** `synchronous=NORMAL`（`Db.java:149`）对权威配置库意味着掉电可丢最近已提交事务；ADR 未讨论此取舍，建议至少在注释/ADR 里声明（或 config.db 用 FULL）。
- **P3-5** `cleanupOrphans` 七表七个事务（`RuntimeStateRepository.java:489-520`），可合并为单事务；中断可重跑收敛，故仅打磨。
- **P3-6** `PlanExecutionRepository` 丢弃/失败诊断走 `System.err.println`（`PlanExecutionRepository.java:112, 137`），store 层无日志门面可以理解（同 Db.schemaBackups 的处理哲学），但建议同样把计数暴露成 getter 供上层接入日志/指标。

## 亮点

1. **事务纪律教科书级**：裸 SQL BEGIN IMMEDIATE 的驱动坑位注释（`Db.java:254-255`）、rollback 异常 addSuppressed 不吞主因（277-283）、open 失败逐级清理（105-121）。
2. **进程锁不锁 db 本体**、避开 Windows 字节段锁干扰，注释把理由写透（`Db.java:25-28`）。
3. **版本策略「先读后判、绝不写后覆盖」**（`Schema.java:23-27`），且有专门测试断言拒绝时库不被碰。
4. **台账异步批写设计成熟**：满队丢行不自旋的理由、批失败留痕计数、停机冲尾、幂等键去重、窗口函数裁剪修掉标量子查询陷阱——每处都有「为什么」级注释（`PlanExecutionRepository.java:107-109, 131-134, 317-319`）。
5. **扩展错误码判冲突**（2067/1555）而非报文匹配，并解释了驱动包装/升级的脆弱性（`ConfigRepository.java:423-442`）。
6. `longOrNull` 记录了 SQLite JDBC Integer/Long 陷阱（`RuntimeStateRepository.java:294-304`）；`clearRuntimeEdits` 三表单事务保证重载原子。
7. 依赖纪律真实成立：pom 只有 secs-json + sqlite-jdbc，12 个 main 文件零上层 import；`Db.schemaBackups()` 用「把事实带出去、不在 store 层打日志」的方式解耦（`Db.java:290-300`）。
8. ULID 单调生成处理了时钟回拨（`Ids.java:43-46`）。

## 测试覆盖评估

- **覆盖较好**：schema 版本拒绝/重置/备份、进程锁互斥、事务回滚、revision 冲突与幂等保存、200 版裁剪、S2F15 不写快照+追平、导出逐字节往返、台账 flush 与 fire 同批聚合、软删语义。
- **明显缺口**：
  1. **并发**：无多线程竞争 saveDocument/updateConstants 的测试（P1-3 的读改写窗口正是缺测点）；无 close-vs-write 竞争测试（P1-2）。
  2. **崩溃/损坏恢复**：无「写事务中途杀进程后 WAL 恢复」「db 文件截断/损坏」「Schema.apply 中途失败」测试；rollbackOnFailedTransaction 只覆盖 SQL 异常路径。
  3. **迁移**：因功能已移除而无迁移测试属合理，但 2.x work-dir 升级到 3.x 的**用户路径**（拒启文案→手工处置）没有任何端到端验证。
  4. **台账边界**：满队丢弃、批写失败重试/留痕、close 冲尾、幂等键重复、trim 边界（恰 5000 条/7 天）未见对应测试名。
  5. **seed 软删复活**（P2-2）无测试。
  6. requireFileName 的测试在 runtime 模块（未读），store 侧 exportTo 恶意 name 无测试。
- 注：以上基于测试**方法名清单**（grep），未逐个读断言体；个别缺口可能以不同名义被部分覆盖。

## 置信度说明

**高置信（全文通读）**：Db / Schema / DbBootstrap / ConfigRepository / HostRepository / TemplateRepository / ParamTemplateRepository / RuntimeStateRepository / PlanExecutionRepository / ContentHash / StoreException / Ids（12/12 main 文件）、ConfigStore（802 行全文）、ADR-0017 全文、secs-store/pom.xml。问题清单各条的行号与摘录均出自实际读取。

**未核实项**：
1. store 测试 5 文件的**断言体**未读（仅方法名），覆盖评估按名推断，可能有低估。
2. `--config` 导入路径（Bootstrap/SessionLauncher）是否对 name 做净化——影响 P2-3 的实际可达性；`Bootstrap.java` 未读。
3. 上层停机顺序（是否先停请求/台账线程再 Db.close）——影响 P1-2 的实际触发概率。
4. reload 生命周期是否另行清 `runtime_plan_state` / `runtime_host_report`（M2 层未读）；P1-1 的触发前提（计数行被清后 flush 重建）依赖该层行为。
5. `NativeRuntimeIT`、native-nightly 工作流现状（ADR M0 闸门是否仍达标）未查。
6. sqlite-jdbc 实际解析版本（父 pom dependencyManagement）未读，VACUUM INTO 可用性按 3.47+ 假设。
7. 未运行任何测试或构建；所有结论为静态阅读结论。
8. P1-3 中「用户编辑被覆盖」的窗口推演基于代码路径逻辑，未做运行时复现。