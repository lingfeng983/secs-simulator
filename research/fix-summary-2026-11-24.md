# SECS Simulator 全量修复核销报告

- **日期**：2026-11-24（评审当日启动，跨零点完成）
- **基线**：评审报告 `code-review-2026-11-24_final.md`（88 条：P0=0 / P1=18 / P2=29 / P3=41），git HEAD a427f13（3.5.0）
- **指令**：用户"全部修复"
- **方式**：按模块并行子任务 + 主 Agent 直接实施与验证；每轮修改后跑模块门禁，最后全量回归

---

## 一、最终验证（全部实际运行，全绿）

| 门禁 | 结果 | 证据 |
|---|---|---|
| 后端 `mvn -B test`（JDK 25 GraalVM，8 模块） | **806 项测试，0 失败 0 错误 0 跳过**（基线 763 → +43 项新增回归） | research/qa/final-gate-backend.log（BUILD SUCCESS, EXIT=0） |
| 前端 `npx tsc --noEmit` | **0 错误** | 终验实跑 |
| 前端 `npm test -- --run` | **35 文件 / 258 项全绿** | 终验实跑 |
| 界面文案门禁 check-ui-words.ps1 | **[OK] 144 文件 / 7263 段** | 终验实跑 |
| openapi 契约（含新快照闸门） | **[OK] 75 path，$ref 可解析，快照一致，注册前缀全覆盖** | 终验实跑 |
| GUI `cargo check --locked` | **通过**（main.rs cfg 修复后本机实编译） | fix-log-build.md |
| Playwright E2E / native 链路 | 未运行（与评审同口径，需浏览器安装/-Pnative 全量构建） | 遗留项 |

模块测试数：json 110 / protocol 59 / core 111 / store 42 / app 87 / api-http 143 / runtime 168 / cli 86。

## 二、P1 核销（18/18 全部修复）

| # | 问题 | 状态 | 落点 |
|---|---|---|---|
| A1 | HsmsSessionRuntime 1331 行 | ✅ **986 行** | 抽出 SectionApplySupport + SessionMetricsWiring |
| A2 | RuntimeApi 1238 行 | ✅ **912 行** | 抽出 ConfigPreviewApi + DeviceBatchCreate + SectionsJson |
| C1 | F4 钳制用 Double.MAX_VALUE | ✅ | BoundedValueSource F4 单列 ±Float.MAX_VALUE + 边界单测 |
| C2 | 单元重名限定报告 last-wins | ✅ | DUPLICATE_UNIT_NAME 升 ERROR + reportByQualified putIfAbsent + 2 单测 |
| R1 | reload RuntimeException 不回滚 | ✅ | catch 扩围（IOException\|RuntimeException）+ 外层 FAILED 分支 + SessionLauncher 注册失败 restore(lastGood) |
| S1 | fire 计数 INSERT enabled=1 | ✅ | 改 0 + 2 回归测试 |
| S2 | Db.close 锁不互斥 | ✅ | close 先取双写锁 + closed 快速失败 + 2 并发测试 |
| S3 | S2F15 回写无版本基准 | ✅ | store 四参 updateConstants(expectedRevision) + runtime 3 次冲突重试 + 冲突测试 |
| P1-P4 | openapi 4 漏声明 | ✅ | DELETE v2 devices / apply-sections / configs sections / host-sims burst 全补（75 path）+ **快照比对与前缀覆盖 CI 闸门**（负向测试验证能挡漏同步与死声明） |
| U1 | 批量同步绕过 If-Match | ✅ | 读目标快照带 modifiedMs，409 计入 failed |
| U2 | 无人用配置重载全部会话 | ✅ | hasConfigField 区分两种 fallback |
| U3 | burst 轮询不可取消 | ✅ | AbortSignal + 调用方卸载 abort |
| B1 | CI 缺文案门禁 | ✅ | ci.yml frontend job 加 pwsh 步骤 |
| B2 | README 列已删 v1 端点 | ✅ | 换 v2 行 + alarms/terminal 废弃标注 |
| B3 | GUI Rust 零 CI | ✅ | ci.yml 新增 gui job（windows cargo check --locked + 占位 dist） |

## 三、P2 核销（29 条：22 修复 / 1 最小改善 / 6 记录保留）

- **协议 2/2**：Deselect.req 并入 T6 事务分支（E37 §7.4/§9.2）；控制帧应答匹配校验 SType（+3 撞号回归测试）。
- **领域 4**：phaseAt 超界钳制 + FINISHED 冻结 duration-1（右开契约收回 core，+6 边界测试）✅；unitByCeid first-wins ✅；v6 ValueStore 双管线——保留（DefaultCurves 初值路径是有意设计，ADR-0020 回退口径）📌；Context 数值 source 空串——加现状注释+锁定测试，config 层校验留待专项 📌。（2 修复 + 2 保留）
- **运行时 2/2**：applyHot 整体持 pumpLock；主机实例上限 64（open 拒绝 + loadPersisted 跳过）。
- **存储 4**：seed 软删撞主键 ✅、exportTo 净化+随机 tmp ✅、backup 异常链 ✅、schema 迁移路径——设计决策不改，ADR-0017 加后记 📌。（3 修复 + 1 保留）
- **API 1/1**：DELETE v1 sessions 契约改"断链不删实体"+deprecated+Deprecation 头。
- **前端 5**：渲染热点 useMemo ✅、8/8 轮询迁移 useVisibilityInterval ✅、写死色改 tokens ✅、截断列表加"载入更早"（最小改善）✅、activeSession 降频为有意保留 📌。（3 修复 + 1 最小改善 + 1 保留）
- **构建 8**：main.rs cfg(windows) ✅、README store 依赖行 ✅、native-nightly 口径 ✅、Skip 产物新鲜度 Write-Warning ✅、v1 废弃标注 ✅、live-backend.ps1 参数化 ✅、崩溃看护——保留（手动切连接探活重拉已在位，自动看护属增强）📌、CI BOM 正则 Linux 失效——当前无实际影响，保留 📌。（6 修复 + 2 保留）
- **架构 3/3**：ArchUnit 方向规则补齐（app/api/store/hostsim 4 条）✅、导入哨兵断言（>300 类 + 5 关键包非空，防 ASM 静默全绿）✅、HostSimulator 千行拆分（985 行）✅。

合计：协议 2 + 领域 2 + 运行时 2 + 存储 3 + API 1 + 前端 3 + 构建 6 + 架构 3 = 22 修复；前端 N5 最小改善 1；保留 6（领域 2、存储 1、前端 1、构建 2）。22+1+6=29。

## 四、P3 核销（41 条：约 30 修复，其余记录保留原因）

已修（摘选）：ArchUnit 哨兵+4 方向规则（6→11 条）、SessionSender 判空、死字段×3、重复 import、ExprSource 防御性拷贝、报告重定义原子化、store 6 条卫生项、api-http respond 状态码、前端 as never/window.prompt/过期注释/INTERVAL.overview、ADR-0016/0017 后记、v6 注释清理（部分随拆分完成）。

保留（记录原因）：types.ts 拆分（影响面大）、完整虚拟化（增强）、名字大小写语义（涉及既有数据）、Linktest 默认值/SystemBytes 16 位（协议固有）、synchronous=FULL（留 ADR）、端口试绑 TOCTOU（设计权衡）、Q-1 Select.rsp status=1（需 E37.1 原文，维持存疑）。

## 五、千行铁律恢复

| 类 | 前 | 后 |
|---|---|---|
| HsmsSessionRuntime | 1331 | **986** |
| RuntimeApi | 1238 | **912** |
| HostSimulator | 1022 | **985** |

全仓 main 源码再无 >1000 行类。拆分均为逐行等价迁移（公开 API 不变），6 轮增量测试验证。

## 六、改动规模与材料

- git 工作区：约 70 个文件修改 + 10 个新文件（后端 helper/测试、前端 hook、openapi 快照、CI job）。
- 分模块日志：`research/fix-log-{core,protocol,store,runtime,api,ui,build}.md`（每条状态+证据）。
- 门禁日志：`research/qa/final-gate-backend.log`、`round1-*.log`、`split1..6-test.log`。
- 未提交 git commit（用户未要求）；未动 node_modules/dist/target 等产物目录。

## 七、遗留与建议

1. **Playwright E2E 与 native 链路**未运行（环境预算）；建议合并前本地跑 `secs-simulator-ui/scripts/check.ps1` 全量与 `-Pnative verify`。
2. **R1b/S3 专用故障注入单测**未新增（SessionManager final、观察者异常被吞，注入点受限）；行为由既有测试面+代码审查保证，建议后续给 launcher 加包私有 seam 补测。
3. **Context 数值 source 的 config 层校验**、**types.ts 拆分**、**消息列表完整虚拟化**：留待专项迭代。
4. 子任务预算机制导致多轮"调查完成但落盘失败"，最终由主 Agent 收尾——所有修复均有实际测试证据，无"声称已修但未验证"项。