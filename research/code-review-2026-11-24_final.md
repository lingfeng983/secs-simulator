# SECS Simulator 全量代码与功能评审报告

- **评审日期**：2026-11-24
- **评审对象**：`E:\ai\secs-simulator` 全仓（git HEAD `a427f13`，release 3.5.0，master 工作区干净）
- **评审方式**：8 个维度并行专项评审 + 主评审对全部关键结论逐条回读源码复核 + 当日本机实际运行门禁
- **配套材料**：8 份维度报告（`research/code-review-2026-11-24_dim01..08.md`）、文件分析、交叉验证记录、证据台账（20 条 verified + 1 条存疑）、校验器报告（`research/qa/`）

---

## 一、执行摘要

**总体判定：这是一个工程质量显著高于平均水平的代码库，可以继续在现有架构上迭代；本轮未发现 P0（崩溃/数据破坏/安全边界突破）缺陷，但存在 18 条 P1 需要排期修复，其中"并发保护旁路"与"契约掉队"两类是系统性模式，建议按模式治理而非逐点打补丁。**

| 维度 | 规模（实测） | 判定 |
|---|---|---|
| Java 后端 8 模块 | 主代码 481 文件 / 66,990 行；测试 112 文件 / 19,225 行 | 分层纪律真实落地，协议层质量高；runtime 有 3 个超千行类 |
| 前端（Vite+React+TS） | src 178 个 ts/tsx / 34,688 行；35 个单测文件 | 分层清晰，昨日 17 条问题整改基本属实；3 条新 P1 |
| Tauri 桌面壳 | main.rs 504 行 | 进程管理设计合理；跨平台编译缺陷 + 零 CI |
| 文档 | ADR 0001–0020 + 4 篇活文档 + 5 份历史评审 | 密度与留痕文化是资产；3 处 README 过时、2 处 ADR 漂移 |

**当日门禁实测（全部通过）**：
- 后端 `mvn -B test`（GraalVM JDK 25）：**763 项测试，0 失败 0 错误 0 跳过**，8 模块全绿，2 分 22 秒；
- 前端 `npx tsc --noEmit`：0 错误；`vitest`：**258 项全部通过**；
- `check-openapi.py`：71 个 path，$ref 全部可解析。

**问题总量（按各维度问题清单逐条程序化计数）**：P0 = 0，**P1 = 18**，P2 = 29，P3 = 41，合计 88 条。

**最值得优先做的三件事**：
1. **补契约闸门**：openapi 有 4 个已实现端点漏声明（全部来自近两周新功能），加一个"openapi ↔ 路由表双向比对"的 CI 步骤可根治这一类问题（§四.1）。
2. **堵并发保护旁路**：批量同步绕过 If-Match、S2F15 常量回写无版本基准、reload 异常路径不回滚——主路径保护都在，旁路漏网（§四.2）。
3. **给 v7 值模型补单测**：本轮 2 条领域 P1 都位于零单测的新核心类，纯函数设计使补测成本极低（§四.3）。

---

## 二、功能全景核实

README 声明的功能面与代码实现逐项对照，以下均经源码证实：

- **三形态运行**（INTEGRATED / SERVER / PROBE）：`Bootstrap` + `CliMain` 装配路径在位；READY 握手行、ready.txt 落盘与桌面壳解析语义一致（dim08 核对 main.rs:331-348 与打包冒烟正则）。
- **会话生命周期**：新建/启动/重载/销毁全链路在位；重载"先编译再动旧会话"、失败快照回滚（RuntimeApi.java:479-490）、连接变更重建 + lastGood 回滚（SessionLauncher）均实现；v1 触发端点已迁 v2 并回 404（SessionHandler.java:479-482）。
- **GEM 能力面**：S1F13/17 建链上线、S2F33/35/37 动态报告、S6F11 事件、S5F1 报警、S2F23 Trace（多 TRID、容量/样本/周期三重闸）、S2F41 HCACK、S10 终端、S2F15 常量可写落盘——全部在位；14 项历史协议修复逐条核实在位（dim02）。
- **值引擎 v7**：单一值定义（DataSource/SourceEvaluator）、周期时钟（CycleClock/CompiledCycleClock/LifecycleCycleClock）、确定性噪声（splitmix64+FNV-1a 哈希噪声场）、表达式双闸（深度 64/运算符 1024）——ADR-0020 实现清单全部存在，EventEffect 旧链路已删净（dim01/dim03）。
- **存储**：config.db + runtime.db 双库、WAL、单写者 BEGIN IMMEDIATE、进程文件锁、schema 版本闸——实现严谨；但 **2.x JSON 迁移已整体移除**（ConfigMigrator 不存在，`--migrate-config` 报未知参数），README/ADR-0017 仍声明"含一次性迁移"（dim05）。
- **前端页面**：工作台/设备十子页/主机模拟五子页/模板管理/审计的功能清单与历史分析报告一致；命令面板、快捷键、焦点管理、主题密度等交互契约抽查在位（dim07）。
- **能力边界诚实**：README"有意不做"清单（无 Spooling、S2F41 不做设备动作、ALTX 仅 ASCII 等）与实现一致，未发现虚标能力。

---

## 三、门禁与测试评估

| 检查 | 结果 | 说明 |
|---|---|---|
| 后端 mvn test | ✅ 763/763 | 当日本机实测（research/qa/backend-mvn-test.log） |
| 前端 tsc | ✅ 0 错 | research/qa/ui-tsc.log |
| 前端 vitest | ✅ 258/258 | research/qa/ui-vitest.log |
| openapi 契约 | ✅ 71 path | 只查重复键/$ref/path 操作，**不查实现↔契约一致性**（缺口见 §四.1） |
| Playwright E2E | ⚠️ 未运行 | 需浏览器安装；mock 用例条数口径（36 vs 75）未验证 |
| native-image 链路 | ⚠️ 未运行 | 需 -Pnative 全量构建，超出本次预算；CI 夜间任务覆盖 |

**测试结构性观察**：测试金字塔重心在 E2E（secs-cli 26 文件 4,699 行端到端是事实主测试面），而**最新的核心代码恰好落在覆盖洼地**——v7 值模型五个核心类（SourceEvaluator/DataSource/DataSourceValueSource/ContextTable/CompiledCycleClock）在 core 层零直接单测；协议层 19,808 行主代码对 1,679 行测试，控制帧撞号、发起方 Deselect 事务等边界无回归。本轮 2 条领域 P1（F4 钳制、重名单元）均因无单测而漏网。---

## 四、系统性模式（跨维度洞察）

### 4.1 实现先行、契约/文档掉队（成因相同，建议一处闸门根治）

四个独立维度各自发现"代码已改、契约未跟"：openapi 4 项漏声明（全部是近两周新功能）、README 仍列已删 v1 端点、README store 依赖表多写 core、ADR-0017 迁移声明与已移除的 ConfigMigrator 不符、ADR-0016 声称的 ArchUnit 守护部分不存在。现有 check-openapi.py 不做实现↔契约比对，缺口没有机器闸门。

**建议**：CI 增加 openapi paths ↔ ApiPaths/registerRoutes/分发表双向比对（或 path 清单快照测试）；README API 表改从 openapi 生成。一处闸门同时消灭 dim06 全部 P1 与 dim08 的 README P1。

### 4.2 并发保护"主路径完备、旁路漏网"

项目对并发保护有明确设计且主路径全部落地（If-Match、snapshot/restore、单写者锁、lifecycleLock），但每条保护都存在绕过它的旁路：

| 保护 | 主路径 | 旁路（本轮发现） |
|---|---|---|
| If-Match 并发保护 | 编辑器保存带 modifiedMs | 批量同步传 null（F-UI-1）；S2F15 常量回写不走 If-Match（F-ST-3） |
| 重载失败回滚 | ConfigException/IOException 走 restore+replay | RuntimeException 漏网且旧会话已关（F-RT-1） |
| 写事务互斥 | writerLock 串行化 | Db.close 用 this 锁，可抽走在途写连接（F-ST-2） |

**建议**：建立"写路径清单"——列出所有修改配置/运行态的入口（编辑器保存、分段保存、批量同步、S2F15 回写、reload、销毁），逐条标注并发保护与失败回滚覆盖；新增写路径必须过这张表。

### 4.3 门禁自身的可信度需要被测试

ArchitectureTest 全部规则 `allowEmptyShould(true)` + `DO_NOT_INCLUDE_JARS`，ASM 版本仅靠 dependencyManagement 钉住（父 POM 注释自述 JDK25 major 69 会让旧 ASM **静默跳过所有类**）——导入 0 类时整套门禁空转全绿，比没有测试更危险。CI 前端 job 缺文案门禁（与"同一组检查"声明不符）、GUI Rust 零 CI。

**建议**：ArchitectureTest 加"导入类数下限 + 关键包非空"哨兵断言（约 10 行）；CI 补 check-ui-words 与 cargo check。

### 4.4 质量基本面扎实，整改闭环真实运转

依赖纪律经全量 import 抽查零越界；纯函数铁律有实打实实现（确定性 Rng、注入时钟）；14 项协议历史修复全部在位；3.0.1/3.5.0 修复声明抽查基本属实；设计决策在代码注释处留痕（审计编号可追溯）。五份历史评审的问题能逐条核销，说明"评审-整改-核销"流程有效，值得保持。

---

## 五、P1 问题清单（18 条，建议排期修复）

> 每条均经主评审独立回读源码复核；完整证据（逐字摘录+行号）见对应维度报告。

### 架构（dim01）
| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| A1 | `HsmsSessionRuntime` 1331 行，违反"超千行拆分"约束 | read_file 总行数 1331 | 按 session 包既有协作者模式继续抽取 |
| A2 | `RuntimeApi` 1238 行，ADR-0016 拆分把臃肿转移给新类 | read_file 总行数 1238 | 按域拆分，RuntimeApi 退化为路由 |

### 领域（dim03）
| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| C1 | F4 钳制上界用 `Double.MAX_VALUE`，越界推迟到协议编码层爆炸 | BoundedValueSource.java:123 `case F4, F8 -> new Bounds(-Double.MAX_VALUE, Double.MAX_VALUE);` | F4 单列 `Float.MAX_VALUE`，补边界单测 |
| C2 | 单元重名仅 WARNING + 限定报告索引 last-wins，全局 CEID 限定引用静默取后编译单元的报告 | EquipmentCompiler.java:263 `reportByQualified.put(...)`（对比 :255 reports 表 putIfAbsent+报错） | 重名升级 ERROR 或限定索引改 putIfAbsent+报歧义 |

### 运行时（dim04）
| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| R1 | reload 抛 RuntimeException 时运行态编辑不回滚，且目标变更路径旧会话已关不拉回（3.0.1 修复残留缺口） | RuntimeApi.java:483 catch 仅 `ConfigException \| IOException`；SessionLauncher.java:215 先 close、:219-223 catch RuntimeException 后 rethrow | catch 扩为含 RuntimeException；注册失败用 lastGood 拉回旧会话 |

### 存储（dim05）
| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| S1 | fire 计数 upsert INSERT 分支硬编码 `enabled=1`，行被清后 flush 重建可复活已禁用计划 | PlanExecutionRepository.java:224-225 `VALUES(?,?,1,…)`。**触发前提已收窄**：clearRuntimeEdits 不清 plan_state（RuntimeStateRepository.java:527-528），实际触发面为设备销毁后在途计数 flush 等窄窗口 | INSERT 分支 enabled 默认 0 或从插入列拆出 |
| S2 | `Db.close()` 用 this 锁、写事务用 writerLock，停机可抽走在途写连接 | Db.java:346 vs :235/248 | close 先取两把写锁；closed 后读写快速失败 |
| S3 | S2F15 常量回写是非原子读改写，可静默覆盖并发用户编辑（无 409） | ConfigStore.writeConstants:655-696 读无版本基准；ConfigRepository.updateConstants:196-226 事务内不校验正文未变 | updateConstants 加 expectedRevision，事务内比对 |

### API 契约（dim06，四项同一成因）
| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1 | `DELETE /api/v2/devices/{id}` 已实现未声明 | DeviceRuntimeHandler.java:77-106；openapi 71 path 无此裸 path（程序化核对） | 补声明 + CI 比对闸门 |
| P2 | `POST /api/v2/devices/{id}/apply-sections` 未声明 | DeviceRuntimeHandler.java:67-69 | 同上 |
| P3 | `PUT /api/v1/configs/{name}/sections` 未声明 | ConfigHandler.java:399-409 | 同上 |
| P4 | `GET/POST /api/v1/host-sims/{hostId}/burst` 未声明（会话级已声明） | HostSimHandler.java:219-247 | 同上 |

### 前端（dim07）
| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| U1 | 批量同步 `saveConfig(targetConfig, text, null)` 绕过 If-Match，目标被另一窗口改过会静默整份覆盖 | DeviceOpsPanel.tsx:186；configs.ts:203-205 null 不加头 | 同步前读目标快照带 modifiedMs，409 计入 failed（回报机制已就绪） |
| U2 | 保存无人使用的配置时 fallback 重载**全部**运行中会话 | useBackend.ts:434-437 `matched.length > 0 ? matched : sessions` | 区分"config 字段缺失"与"确实没人用" |
| U3 | burst 完成轮询 `for(;;)` 不可取消，面板卸载后仍打后端最长 6 分钟 | useServerOps.ts:75-91 | 传 AbortSignal 或挂组件生命周期 |

### 构建/CI/文档（dim08）
| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| B1 | CI 前端 job 缺 `check-ui-words.ps1`，与 ci.yml"同一组检查"声明不符 | ci.yml:44-61 vs ui check.ps1:50-52 | CI 加 pwsh 步骤跑文案门禁 |
| B2 | README API 表仍列已删除的 v1 ceid/alarm 触发端点 | README.md:158-159 vs SessionHandler.java:479-482（迁 v2 回 404） | 删两行补 v2 指引（或从 openapi 生成） |
| B3 | GUI Rust 无任何 CI 检查（cargo check/clippy/fmt/test 均缺） | ci.yml/native-nightly.yml 全文 | 加 windows runner cargo check job |---

## 六、P2 问题摘要（29 条，计划内修复）

按主题归并（逐条证据见维度报告）：

- **协议**（dim02，2 条）：发起方 Deselect.req 无 T6 事务、不校验 rsp 状态码（AbstractHsmsSsCommunicator.java:481-544，违反 E37 §7.4/§9.2）；控制事务应答匹配恒 true，SystemBytes 撞号的数据帧会被当 Select.rsp/Linktest.rsp 吞掉（HsmsSsReplyMessageManager.java:164-167）。协议层冻结政策下这两条属"对照标准确认的实现缺陷"，符合修改例外条件。
- **领域**（dim03，4 条）：RUNNING 态末片后 phaseAt 越界窗口（违反 [0,duration) 契约，预览路径无 runtime 保护）；FINISHED 冻结相位=duration 恰在契约区间外；runtime 仍有 ValueStore/CurveValueSource 第二装配管线（v6 残留，漂移风险）；Context 配数值型 source 静默输出空串（带 config 层未核实前提）。
- **运行时**（dim04，2 条）：HotReloader 多步替换与心跳无共同锁，一个 tick 可混合新旧快照；主机实例数量/线程/连接无全局上限。
- **存储**（dim05，4 条）：schema 无迁移路径且 ADR 漂移；TemplateRepository.seed 对软删同 id 行必然 PRIMARY KEY 失败；exportTo 无文件名净化+固定 .tmp 名；Schema.backup 吞掉 VACUUM INTO 真实失败原因。
- **API**（dim06，1 条）：`DELETE /api/v1/sessions/{id}` 契约语义过时（文档写"关闭并移除"，实现已改"断链不删实体+Deprecation"）。
- **前端**（dim07，5 条）：消息监控渲染体内 `Math.max(...5000)` 热点；截断 400 行代替虚拟化；8 处组件级 setInterval 绕开中央调度（不随 document.hidden 暂停）；PlanExecutionTimeline 三处内联写死语义色违反 tokens 纪律；activeSession 离页降频为有意保留差异。
- **构建/文档**（dim08，8 条）：main.rs:292-295 无条件用 Windows-only `creation_flags`（非 Windows 编译必败，同文件 :271-274 却有 cfg 分支）；README store 依赖行多写 core；native-nightly"非阻断"口径不一；-SkipNative/-SkipFrontend 复用产物无新鲜度校验；v1 alarms/terminal 已废弃但 README 未标注；live-backend.ps1 硬编码本机路径入库；本机后端崩溃无自动重启；CI BOM 排除正则在 Linux 失效（当前无实际影响）。
- **架构**（dim01，3 条）：ArchUnit 覆盖缺口（app/api/store/runtime 相互方向无规则）；allowEmptyShould 静默全绿风险；HostSimulator 1022 行刚过线。

P3 共 41 条（注释漂移、死代码、卫生项），清单见各维度报告，不在此展开。

---

## 七、亮点（值得保持的实践）

1. **依赖纪律真实落地**：8 模块 import 全量抽查零越界；`com.sun.net.httpserver` 严格圈禁；protocol/json 叶子库确为仅 JDK；store 实际比 README 声明更严格（只认 json+JDBC）。
2. **确定性设计**：Rng 用 splitmix64+FNV-1a 把 (seed, 时间网格) 映射为可复现噪声，支持乱序求值/跳采/加速回放；时钟注入（EngineClock/ManualEngineClock）使领域逻辑可确定性测试。
3. **并发注释留痕文化**：线程模型清晰（每会话心跳+3 条 ACK 分池防报警堵死事件流），历史竞态修复在代码处留痕可追溯。
4. **防护面质量高**：SSE 一次性票（SecureRandom 256-bit、原子消费）、256 帧丢最旧背压、慢请求双保险+120s 截止、SQL 全 PreparedStatement、路径穿越分层防护、配置原子写。
5. **构建门禁有诚意**：-Werror 统一收口、enforcer 可读报错、双平台 CI 矩阵（端口语义差异被明确认知）、打包冒烟+版本三重校验。
6. **文档资产**：ADR 0001-0020 齐全、能力边界诚实（"有意不做"清单与实现一致）、历史评审整改闭环真实运转。

---

## 八、方法与证据限制

- **评审方式**：8 个维度子评审（独立证据预算）+ 主评审对全部 P1 及关键 P2 逐条回读源码复核；证据台账 20 条 verified 全部通过校验器逐字/数字/词元检查（`research/qa/code-review-2026-11-24_research_check.json`，delivery_mode=partial）。
- **未覆盖项**：Playwright E2E 与 native-image 链路未运行；SECS-I（secs1）与 SML 解析器全文未逐行审读；openapi 前缀内子路径级死声明未逐一核对；前端首屏 562→264 kB 声明未实测；部分 P2/P3 依赖子评审通读、主评审未逐条复读（各维度报告"置信度说明"列明）。
- **存疑项（不定性为缺陷）**：主动端把 Select.rsp status=1 当成功（AbstractHsmsSsActiveCommunicator.java:198-204），与库内 E37 通用整理版"非 0 即失败"相左，但整理版自述未覆盖 E37.1 增量、英文原文不在库（file-only 约束），需标准原文裁决。
- **已订正的评审误差**：dim06 原稿 openapi 计数 72 系笔误，程序化复核为 71（结论不受影响）；dim05 S1 的触发前提经复核收窄（clearRuntimeEdits 不清 plan_state），最终按收窄口径表述。
- **footnote 警告说明**：校验器对 dim06/dim08 报"footnote markers"实为正文中正则字符类 `[^A-Za-z0-9_-]` 的误识别，非真实缺失引用。

## 九、修复路线建议（四批次）

| 批次 | 内容 | 预估工作量 |
|---|---|---|
| ① 契约与文档 | openapi 补 4 声明 + CI 双向比对闸门；README 3 处订正；ADR-0017/0016 漂移注记 | 1 天 |
| ② 并发旁路 | U1 批量同步带 If-Match；S3 常量回写加版本基准；R1 catch 扩围+lastGood 拉回；S2 close 锁序；U2 fallback 区分；S1 enabled 拆出 | 2-3 天 |
| ③ 测试补洼地 | v7 值模型五类边界单测（含 C1/C2 回归）；协议控制帧撞号+Deselect 事务回归；ArchUnit 哨兵断言 | 2-3 天 |
| ④ 结构与卫生 | C1/C2 修复；协议 2 条 P2（按冻结例外条文提交）；HsmsSessionRuntime/RuntimeApi 拆分；CI 补文案门禁+cargo check；U3 可取消轮询；P3 批量清理 | 按迭代排期 |

---

## 十、来源

- 维度报告：`research/code-review-2026-11-24_dim01.md` … `dim08.md`（每条问题含文件路径:行号+逐字摘录）
- 文件分析：`research/code-review-2026-11-24_file_analysis.md`
- 交叉验证与订正记录：`research/code-review-2026-11-24_cross_verification.md`
- 洞察：`research/code-review-2026-11-24_insight.md`
- 证据台账：`research/code-review-2026-11-24_evidence.json`（20 verified + 1 unverified）
- 门禁日志：`research/qa/backend-mvn-test.log`、`ui-tsc.log`、`ui-vitest.log`；问题计数 `issue-counts.json`；校验器报告 `code-review-2026-11-24_research_check.json`
- 源码引用均为仓库相对路径（git HEAD a427f13），行号以评审当日文件为准。