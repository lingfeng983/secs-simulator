# SECS Simulator 全量代码评审 · 文件分析

Route: C（File-only，仅仓库源码/文档/本地构建产物，无外部检索）
Time Check: 2026-11-24（评审当日运行门禁；git HEAD a427f13，release 3.5.0，master 干净）
Constraints: 只读评审 + 本地测试执行；不修改任何产品代码

## 评审对象与方法

- 对象：`E:\ai\secs-simulator` 全仓（Java 后端 8 模块、Vite+React 前端、Tauri 桌面壳、脚本/CI/文档）。
- 方法：8 个维度并行子评审（每个维度独立证据预算）+ 父 Agent 对全部关键结论逐条回读源码复核 + 实际运行三条门禁（后端 mvn test、前端 tsc、前端 vitest）与 openapi 契约检查。
- 维度划分（8 个问题组）：①架构与依赖约束 ②协议符合性 ③领域与值引擎 ④运行时并发与生命周期 ⑤存储持久化 ⑥HTTP API 契约 ⑦前端 ⑧构建/CI/打包/文档。

## File Inventory（规模实测，find+wc 统计）

| 范围 | 主代码 | 测试 | 摘要 |
|---|---|---|---|
| secs-json | 29 文件 / 4,688 行 | 6 文件 / 1,340 行 | 无反射 JSON（jsonhub），仅 JDK |
| secs-protocol | 214 文件 / 19,808 行 | 10 文件 / 1,679 行 | HSMS-SS/SECS-I/SECS-II/SML/GEM，冻结模块（源自 secs4java8） |
| secs-core | 66 文件 / 5,151 行 | 14 文件 / 1,317 行 | 领域模型/曲线/值引擎/ID 编译/周期时钟，无 I/O 无 JSON |
| secs-store | 12 文件 / 3,379 行 | 5 文件 / 690 行 | SQLite config.db + runtime.db，仅依赖 json+sqlite-jdbc |
| secs-app | 42 文件 / 6,572 行 | 10 文件 / 1,697 行 | 会话/配置/日志/SML 文本层 |
| secs-api-http | 35 文件 / 7,540 行 | 12 文件 / 3,394 行 | REST+SSE，com.sun.net.httpserver+虚拟线程 |
| secs-runtime | 80 文件 / 19,705 行 | 29 文件 / 4,409 行 | 装配/生命周期/会话协议实现/主机模拟/压测 |
| secs-cli | 3 文件 / 147 行 | 26 文件 / 4,699 行 | 入口 CliMain + 端到端测试宿主 |
| **后端合计** | **481 文件 / 66,990 行** | **112 文件 / 19,225 行** | JDK 25，Maven 多模块 |
| secs-simulator-ui/src | 178 个 ts/tsx / 34,688 行（find 口径；含测试文件） | 35 个 *.test.ts(x) | Vite+React+TS，分层 app/api/features/shared/state/styles |
| secs-simulator-gui | Rust 壳（main.rs 504 行）+ assets | — | Tauri，Windows 安装程序形态 |
| docs | ADR 0001–0020、guides 4 篇、reports 归档（M0–M9、5 份历史评审、8 份发布说明） | — | 文档密度高，活文档/归档分区 |
| scripts / CI | package.ps1、2×check.ps1、check-openapi.py、ci.yml、native-nightly.yml | — | 双平台 CI 矩阵 + 夜间原生构建 |

## 门禁执行结果（2026-11-24 实测）

| 检查 | 结果 | 证据 |
|---|---|---|
| 后端 `mvn -B test`（JDK 25 GraalVM） | **BUILD SUCCESS，763 项测试 0 失败 0 错误 0 跳过**（8 模块：110/56/89/29/87/143/163/86），耗时 2:22 | research/qa/backend-mvn-test.log（MVN_EXIT=0） |
| 前端 `npx tsc --noEmit` | **通过**（TSC_EXIT=0） | research/qa/ui-tsc.log |
| 前端 `npm test -- --run`（Vitest） | **258 项全部通过**，1.64s | research/qa/ui-vitest.log（VITEST_EXIT=0） |
| `check-openapi.py` | **[OK] 71 个 path，$ref 全部可解析** | 当日运行输出 |
| Playwright E2E / native verify | 未运行（E2E 需浏览器安装与构建产物；native 需 -Pnative 全量构建，超出本次评审预算） | 见交叉验证"证据缺口" |

## Per-File Extraction（维度报告索引）

| 维度 | 报告 | 核心结论 |
|---|---|---|
| dim01 架构 | code-review-2026-11-24_dim01.md | 依赖纪律真实落地（import 抽查零越界）；ArchitectureTest 覆盖缺口 + allowEmptyShould 静默全绿风险；3 个超千行类（HsmsSessionRuntime 1331 / RuntimeApi 1238 / HostSimulator 1022） |
| dim02 协议 | code-review-2026-11-24_dim02.md | 无 P0/P1；2 个 P2 确认缺陷（发起方 Deselect 无 T6 事务；控制帧应答匹配不校验 SType）；14 项历史修复全部核实在位 |
| dim03 领域 | code-review-2026-11-24_dim03.md | 纯函数铁律成立；2 P1（F4 钳制用 Double.MAX_VALUE；单元重名限定报告引用 last-wins）；v7 值模型核心类 core 层零单测 |
| dim04 运行时 | code-review-2026-11-24_dim04.md | 线程模型清晰（每会话心跳+3 条 ACK 分池）；1 P1（reload 抛 RuntimeException 不回滚运行态编辑）；热重载多步替换非原子（P2） |
| dim05 存储 | code-review-2026-11-24_dim05.md | SQL 注入面干净、WAL/单写者/回滚严谨；3 P1（fire 计数 INSERT 硬编码 enabled=1；Db.close 锁不互斥；S2F15 常量回写非原子读改写）；2.x 迁移已移除但 README/ADR 未更新 |
| dim06 API | code-review-2026-11-24_dim06.md | 4 项 P1 契约漏声明（DELETE v2 devices、apply-sections、configs sections、host-sims burst）；SSE ticket/背压/DoS 防护质量高；v1→v2 触发迁移已同步 |
| dim07 前端 | code-review-2026-11-24_dim07.md | 历史 17 条中抽查 6 项落地属实；3 新 P1（批量同步绕过 If-Match；保存无人用配置 fallback 重载全部会话；burst 轮询不可取消）；8 处组件级轮询绕开中央调度 |
| dim08 构建/文档 | code-review-2026-11-24_dim08.md | 3 P1（CI 缺文案门禁；README 列已删 v1 端点；GUI Rust 无 CI）；版本 3.5.0 五处一致；main.rs 跨平台编译缺陷（P2） |

## Cross-File Mapping（跨文件印证）

1. **README 依赖表 vs POM**：README.md:29 写 store 依赖 "core + json + sqlite-jdbc"，实际 secs-store/pom.xml 只有 json+sqlite-jdbc（dim01/dim05/dim08 三方独立证实，父 Agent 复核）。实际更严格，文档滞后。
2. **README API 表 vs SessionHandler**：README.md:158-159 仍列 v1 ceid/alarm 触发端点；SessionHandler.java:479-482 证实已迁 v2 且 v1 回 404（dim06/dim08 印证，git log 456f189 佐证）。
3. **ADR-0017 迁移声明 vs 实现**：ConfigMigrator 已整体移除（RuntimeOptionsTest.java:64 注释），README/ADR 仍写"含 2.x JSON 一次性迁移"（dim05 发现，dim08 文档核对印证）。
4. **3.0.1 修复声明 vs 残留缺口**：重载失败回滚已实现（RuntimeApi.java:479-490 snapshot→restore→replay），但 catch 只覆盖 ConfigException|IOException，SessionLauncher.java:219-224 的 RuntimeException 路径漏网（dim04 发现，父 Agent 逐行复核证实）。
5. **3.5.0 If-Match 保护 vs 批量同步后门**：configs.ts:201-205 实现 If-Match 并发保护，DeviceOpsPanel.tsx:186 批量同步传 null 绕过（dim07 发现，父 Agent 复核证实）。
6. **ADR-0016 ArchUnit 声明 vs 实际规则**：ArchitectureTest 仅 6 条规则，hostsim→session 单向实际靠 package-private+编译器而非 ArchUnit（dim01 通读证实）。
7. **ADR-0020 v7 值模型 vs v6 残留**：EventEffect 链路已删净，但 runtime 仍有 ValueStore/CurveValueSource 第二装配管线（dim03 grep 行级证据，dim04 通读 runtime 证实初值/热重载基线用途）。

## Gaps（本次评审未覆盖）

- Playwright E2E（mock 36 条口径未验证）与真实后端 E2E、native-image 链路未运行。
- secs1（SECS-I 串口）与 SML 解析器全文未逐行审读（dim02 标注）。
- openapi 前缀内子路径级死声明未逐一核对（dim06 标注）。
- 前端 562→264 kB 首屏体积声明未实测（dim07 标注）。
- E37.1 英文原文不在库，Select.rsp status=1 语义争议无法裁决（dim02 Q-1 存疑项）。

## Consolidated Themes

- **主题 A：实现先行、契约/文档掉队**（openapi 4 漏声明、README 3 处过时、ADR 2 处漂移）——同一成因反复出现，缺机器比对闸门。
- **主题 B：并发保护的"最后一公里"缺口**（If-Match 被批量同步绕过、S2F15 回写无版本基准、reload RuntimeException 不回滚、Db.close 锁不互斥）——主路径保护到位，旁路/异常路径漏网。
- **主题 C：门禁覆盖不完整**（ArchUnit 空集静默绿、CI 缺文案门禁、GUI Rust 零 CI、v7 值模型核心零单测）。
- **主题 D：质量基本面扎实**（763+258 测试全绿、依赖纪律真实、纯函数铁律落地、历史修复全部在位、注释留痕文化好）。