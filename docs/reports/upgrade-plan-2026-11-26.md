# 后续升级计划与任务清单

基线：3.5.1（tag `v3.5.1`）
整理日期：2026-11-26
实施状态：3.5.2 的 P0 六项（UP-01～UP-06）已完成，待发版；P1/P2 按下述顺序推进
范围：SECS/GEM 仿真器全栈（service 九个模块、UI、Tauri 壳、构建与发布）

优先级口径：

- **P0**：正确性或门禁失效风险，近期必须收口；不修会让后续功能建立在不可信的地基上。
- **P1**：能力缺口或契约漂移，影响可用性/可维护性，按版本排期。
- **P2**：需要独立立项或外部依赖，先做 ADR/评估再实施。

工作量口径：**S**（≤2 天）、**M**（3–5 天）、**L**（≥1 周，需拆子任务）。

## 一、现状判断

3.5.1 之后工程门禁是可信的：前端 tsc/Vitest/Playwright、后端九模块 `mvn -B test`、原生 `-Pnative verify`、安装包冒烟与 CI 五 job 均为绿。当前遗留问题集中在三处：

1. **正确性缺口**：值来源的非法组合仍静默降级；热替换与心跳的锁覆盖不一致。
2. **覆盖缺口**：几个高风险并发点没有专项测试；ArchUnit 仍有可空转的规则。
3. **契约漂移**：文档/规格表与实现不同步，且缺少机器闸门阻止继续漂移。

功能侧的大特性（Spooling、S2F41 动作语义）不属于近期范围，先立 ADR 再排期。

## 二、路线图

| 版本 | 主题 | 目标 | 出口标准 |
|---|---|---|---|
| 3.5.2 | 稳定性收口 | 正确性缺口清零、专项测试补齐、门禁自证有效 | P0 清单全部关闭，全量门禁绿 |
| 3.6.0 | 能力补齐 | 事件时间语义、ACK 处理、数据清理与性能基线 | P1 功能项完成并有验收数据 |
| 3.7.0 | 契约收口 | 前端切 v2、文档同步、契约机器闸门、GUI CI 加固 | v1 编辑调用归零、契约检查可拦截漂移 |
| 4.0.0 | 重大特性 | Spooling、设备动作语义、签名与分发矩阵 | 每项先有 ADR，再按验收场景交付 |

## 三、3.5.2 稳定性收口（P0）

### UP-01 Context 字段的值来源校验收口

- **状态**：已完成（3.5.2）。

- **问题**：`SourceEvaluator` 对 Context 配了数值型 source（Curve/Expr/CycleNumber）时走 `default -> ""`，静默编码为空 ASCII；现有测试还把这个行为钉住了。
- **动作**：在 config 层定义「Context 字段 × 允许 source 类型」矩阵，保存时拒绝非法组合并指出字段；`SourceEvaluator` 保留兜底但不再作为正常路径。
- **验收**：非法组合保存返回 4xx 且错误信息含字段名；合法组合行为不变；新增至少 6 条负例与 3 条兼容正例；`mvn -pl secs-core,secs-app test` 绿。
- **风险**：此前能保存的配置会开始报错，需要发布说明与迁移提示。
- **来源**：`secs-core/src/main/java/com/shimizukenta/secssim/core/value/SourceEvaluator.java:79`；`secs-core/src/test/java/.../SourceEvaluatorTest.java:86`。
- **工作量**：M

### UP-02 applySections 纳入 pumpLock

- **状态**：已完成（3.5.2）。

- **问题**：`applyHot` 与 `pumpNow` 都持 `pumpLock`，但 `applySections` 委托给不持锁的 `SectionApplySupport`，调用方只持 `lifecycleLock`；与心跳交错时可能读到混合快照。
- **动作**：把分段应用纳入 `pumpLock`，并统一锁顺序为 `lifecycleLock → pumpLock`（调用方 `SessionLauncher` 已持前者），禁止反向获取；锁顺序写进类注释。
- **验收**：新增并发测试模拟分段应用与心跳 100 次交错，断言每次快照要么全旧要么全新；`mvn -pl secs-runtime test` 绿；无死锁（测试超时保护）。
- **来源**：`secs-runtime/.../session/HsmsSessionRuntime.java:610`；`.../session/SectionApplySupport.java:129`；`.../runtime/SessionLauncher.java:252`。
- **工作量**：S–M

### UP-03 高风险并发点专项测试

- **状态**：已完成（3.5.2）。

- **问题**：`BurstRunner` 取消语义、`ControlStateHolder` 并发迁移与通知次数、`HostSimRegistry` 64 实例边界均无专项测试。
- **动作**：新增 `BurstRunnerTest`、扩充 `ControlStateHolderTest`、新增 `HostSimRegistryTest`。
- **验收**：取消延迟 <1 秒；并发迁移后状态唯一且通知次数符合契约；第 65 台被拒绝且不产生持久化行；三个测试文件在 `mvn -B test` 中稳定通过。
- **来源**：`secs-runtime/.../BurstRunner.java:496`；`.../session/ControlStateHolder.java:56`；`.../HostSimRegistry.java:47`。
- **工作量**：M

### UP-04 R1b/S3 故障注入测试

- **状态**：已完成（3.5.2）。

- **问题**：重载失败回滚（R1b）与 S2F15 并发写（S3）只有 store 层乐观锁测试，缺少会话层故障注入。
- **动作**：为 `SessionManager` 引入包内可见的测试工厂或注入接口，不改变生产代码可见性；构造重载失败与双写竞争场景。
- **验收**：重载失败后旧会话仍可用且返回明确错误；双写一成一败（409），失败方不覆盖成功方；不新增生产 API。
- **来源**：`secs-app/.../session/SessionManager.java:20`；`research/fix-log-runtime.md:34`。
- **工作量**：M

### UP-05 ArchUnit 门禁自证有效

- **状态**：已完成（3.5.2）。

- **问题**：哨兵只校验 core/app/api/runtime/store 五包；`protocolLayerOnlyDependsOnJdk`、`jsonLayerOnlyDependsOnJdk` 的目标包不在哨兵内；`newCodeNeverUsesReflection` 的 `cli..` 分支在本模块恒空。
- **动作**：哨兵扩到 protocol/json/cli 包并设每包下限；删除恒空规则分支或迁到能看到该包的模块；`RuleSanityTest` 改为逐包断言。
- **验收**：故意注释掉一条规则或清空一个包，测试必须变红；`mvn -pl secs-runtime test` 绿。
- **来源**：`secs-runtime/src/test/java/.../arch/ArchitectureTest.java:45,139,167`；`.../arch/RuleSanityTest.java:24`。
- **工作量**：S

### UP-06 构建链遗留告警与复用检查

- **状态**：已完成（3.5.2）。

- **问题**：`-SkipJars` 没有产物新鲜度检查；CI action 仍固定 `checkout@v4`/`setup-java@v4`/`setup-node@v4`，runner 已提示 Node 20 弃用。
- **动作**：`-SkipJars` 复用 jar 时对比源码/HEAD 时间并告警；升级 action 主版本，确认无 deprecated 告警。
- **验收**：旧 jar 触发告警但不阻断；CI 五 job 绿且 annotation 中不再出现弃用提示。
- **来源**：`scripts/package.ps1:317`；`.github/workflows/ci.yml:35,51,74,100`。
- **工作量**：S

## 四、3.6.0 能力补齐（P1）

### UP-07 事件取值时刻改用 fire.atMs

- **问题**：100ms tick 下 `pumpEvents` 用 `clock.nowMs()` 取值，批处理/加速时事件时间语义偏。
- **动作**：按事件的 `fire.atMs` 取值；明确同一批事件的取样顺序。
- **验收**：加速场景下事件值与触发时刻一致；新增时间语义测试；既有曲线测试不回归。
- **来源**：`docs/reports/unfinished-2026-10-17.md` §1。
- **工作量**：S

### UP-08 S6F12/S6F2 的 ACKC6 处理与 S9 错误细分

- **问题**：ACKC6 非 0 时无告警/重试；S9F1/7/9/11 只有笼统处理。
- **动作**：非 0 时记录并告警，按配置决定是否重试；S9 按错误类型细分到日志与计数器。
- **验收**：注入非 0 ACKC6 能观察到告警与计数；S9 各类有独立测试。
- **工作量**：M

### UP-09 未知 SVID 的类型一致性

- **问题**：S1F3/F4 对未定义 SVID 恒回 `U4 0`，与主机 SVID 表类型可能不一致。
- **动作**：优先按配置类型回值；无配置时给出明确 `UNKNOWN` 语义并在响应/日志可辨。
- **验收**：三种类型（数值/文本/数组）各有用例；契约文档同步。
- **工作量**：M

### UP-10 ALTX 多字节文本

- **问题**：报警文本入口仅接受 US-ASCII，非 ASCII 直接 400。
- **动作**：确认 SEMI E5 对 ALTX 的编码要求后支持 UTF-8；入口与协议编码保持一致。
- **验收**：中文报警文本可置位并在界面/报文正确显示；越界字符有明确拒绝原因。
- **工作量**：S–M

### UP-11 台账增量清理与冗余表清理

- **问题**：5000 条/7 天裁剪只在启动跑一次；`runtime_host_report` 表建了没用。
- **动作**：运行期每小时增量清理；删除冗余表并提供 schema 迁移。
- **验收**：长跑测试中台账不无界增长；迁移后旧库可正常打开；无残留引用。
- **来源**：`docs/reports/release-notes-3.0.0.md`；`docs/design/todo-3.0.md` §5。
- **工作量**：S

### UP-12 性能与容量基线

- **问题**：大规模预览、400 SVID、多客户端并发没有压测数据。
- **动作**：建立可复现压测脚本与基线（p95 延迟、内存、连接数），纳入版本报告。
- **验收**：3.6.0 发布说明附带基线数字；回归阈值写进脚本。
- **工作量**：M

## 五、3.7.0 契约收口（P1/P2）

### UP-13 前端全量切换 v2 编辑端点

- **问题**：前端仍在调 `/api/v1/sessions/**` 的目录、事件、报文、压测、重载等端点，v2 已就绪。
- **动作**：逐端点切换并删除 v1 封装；制定 v1 退役时间表（Sunset 2027-06-30）。
- **验收**：前端源码中不再出现 v1 编辑调用；Playwright 全绿；v1 仅保留读兼容。
- **来源**：`secs-simulator-ui/src/api/sessions.ts:81-287`；`docs/design/todo-3.0.md` §5。
- **工作量**：L

### UP-14 契约机器闸门

- **问题**：`check-openapi.py` 只查声明，不校验实现与契约一致；文档漂移靠人眼。
- **动作**：增加「注册路由 ↔ openapi path/方法」比对；对已删能力建立禁用词清单（如投影表、ConfigMigrator）。
- **验收**：故意删一个实现或改一个 path，契约检查必须失败；CI 绿。
- **工作量**：M

### UP-15 文档同步包

- **问题**：ADR 0017 投影表/ConfigMigrator 描述、ADR 0015 `continue-on-error` 表述、规格对照表 S2F13/S2F41/S5F3 标注、`todo-3.0.md` 与 `m4-report.md` 均有滞后。
- **动作**：一次性校正并在文首标注「以代码为准」；建立文档链接检查。
- **验收**：文档中不再出现已删能力的肯定描述；链接检查通过。
- **工作量**：S

### UP-16 GUI CI 加固

- **问题**：GUI job 只 `cargo check`，无 clippy/fmt/test，无 Linux Rust。
- **动作**：加 `cargo fmt --check`、`cargo clippy -D warnings`、可行的单测；评估 Linux 目标。
- **验收**：CI 中 GUI job 覆盖三项检查且绿；失败能定位到具体文件。
- **工作量**：S

## 六、4.0.0 重大特性（P2，先 ADR）

### UP-17 Spooling（S2F43–F48）

- **前置**：ADR 定清数据保留上限、重发顺序、背压策略、与计划启用态的关系。
- **验收**：断线期间事件可缓存、重连按序补发、超限有明确丢弃策略；有容量与故障测试。
- **工作量**：L

### UP-18 S2F41/F42 设备动作语义

- **问题**：当前只按 E30 条件回 HCACK，CPACK 恒空，无真实 START/STOP。
- **动作**：定义支持的 command 集合与状态迁移，明确不支持的 HCACK 码。
- **验收**：START/STOP 改变会话状态；非法命令返回约定的 HCACK；协议测试覆盖。
- **工作量**：M

### UP-19 代码签名与分发矩阵

- **前置**：代码签名依赖外部证书采购，文档已标「正式分发前优先级最高」。
- **动作**：安装包与 exe 签名；评估 macOS 产物、容器化服务端、瘦 JRE 兜底。
- **验收**：Windows 安装无「未知发布者」；签名校验步骤进发布清单；其他平台按评估结论执行。
- **工作量**：M（不含证书采购周期）

### UP-20 主机 Trace/采集配置的产品口径拆分

- **问题**：主机配置区与运行区同名页复用同一实现，产品语义（保存到设置 vs 临时生效）未定。
- **动作**：先定产品口径，再决定是否拆页面与数据流。
- **验收**：产品文档明确两者边界；实现与文档一致。
- **来源**：`docs/design/todo-3.0.md` §2/§5。
- **工作量**：M

### UP-21 长期工程质量项

- `types.ts` 拆分、消息列表完整虚拟化、名字大小写语义统一。
- 前两项为可维护性/性能增强；第三项涉及既有数据，需迁移方案。
- **验收**：拆分后类型检查与测试全绿；虚拟化在 5000 条报文下滚动流畅；大小写变更带迁移脚本。
- **工作量**：L

## 七、明确不做或保留的设计取舍

| 项 | 结论 | 依据 |
|---|---|---|
| 未知 SVID 恒回 `U4 0` | 保留，除非 UP-09 落地 | 类型信息不足，靠 S1F11 `UNKNOWN` 区分 |
| ALTX 仅 ASCII | 由 UP-10 决定是否改变 | 现有入口即拒，行为明确 |
| 端口试绑 TOCTOU | 保留，lastGood 兜底 | 跨进程抢占无法彻底消除 |
| `synchronous=NORMAL` | 保留 | ADR 0017 的写入性能取舍 |
| Q-1 `Select.rsp status=1` 当成功 | 暂不改 | 与整理版标准相左，需英文原文裁决 |
| 投影表 | 不实现 | ADR 0017 后记：运行态直读写 runtime.db |
| ConfigMigrator / `--migrate-config` | 已删除 | ADR 0017 后记 |
| Spooling | 不进 3.x | ADR 0015 §6 |
| 慢路由独立执行器 | 保留 | 语义已转为限制重活并发 |
| Linktest 默认值 / SystemBytes 16 位 | 保留 | 协议固有 |

## 八、依赖与风险

1. **UP-04 依赖注入点方案**：不要为了测试把 `SessionManager` 改成非 final；优先包内测试工厂。
2. **UP-02 锁顺序**：`pumpLock` 与 `lifecycleLock` 必须约定唯一顺序，否则有死锁风险；测试要带超时。
3. **UP-01 兼容性**：非法组合从静默变报错，属于行为变更，需在 3.5.2 发布说明单列。
4. **UP-13 依赖 UP-14**：先有契约闸门，再大规模删 v1 封装，避免删漏。
5. **UP-17 依赖策略 ADR**：Spooling 的保留/顺序/背压不定，不进入编码。
6. **UP-19 依赖外部证书**：采购周期可能长于开发，建议提前启动。

## 九、建议执行顺序

1. **迭代一（3.5.2）**：UP-01 → UP-02 → UP-05（先修正确性，再修门禁自证）。
2. **迭代二（3.5.2）**：UP-03 → UP-04 → UP-06，收口后发 3.5.2。
3. **迭代三（3.6.0）**：UP-07 → UP-08 → UP-09 → UP-10 → UP-11 → UP-12。
4. **迭代四（3.7.0）**：UP-14 → UP-13 → UP-15 → UP-16。
5. **4.0.0**：先写 UP-17 的 ADR，再评估 UP-18/UP-19/UP-20/UP-21 的投入。

每个迭代的出口统一为：目标清单全关、`mvn -B -Pnative verify` 绿、Playwright 绿、发布说明记录行为变化。
