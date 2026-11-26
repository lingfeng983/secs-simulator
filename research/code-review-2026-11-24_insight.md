# SECS Simulator 全量代码评审 · 跨维度洞察

## Insight: "实现先行、契约掉队"是系统性模式，不是偶发疏漏

Derived From: Dim06（API 契约）、Dim08（文档/CI）、Dim05（ADR 漂移）
Evidence Cluster: F-API-1..4（openapi 4 漏声明）、F-BUILD-1/2（README 已删端点/依赖表）、ADR-0017 迁移声明 vs ConfigMigrator 已移除、ADR-0016 ArchUnit 声明 vs 实际规则
Rationale: 四个独立维度各自发现"代码已改、契约/文档未跟"的实例，且 openapi 4 项漏声明全部来自近两周的新功能（v2 销毁/apply-sections/分段保存/主机 burst）。现有 check-openapi.py 只查重复键与 $ref，不查实现↔契约双向一致性，缺口没有机器闸门。
Implications: 靠人工同步会持续复发。建议加一个 CI 步骤：解析 openapi paths 与 ApiPaths/registerRoutes/分发表做双向比对（或至少 path 清单快照测试），并把 README API 表改为从 openapi 生成。一处闸门可同时消灭 dim06 全部 P1 与 dim08 的 README P1。
Confidence: high

## Insight: 并发保护在主路径完备、旁路漏网——需要"保护清单"式审计而非逐点修补

Derived From: Dim04（运行时）、Dim05（存储）、Dim07（前端）
Evidence Cluster: F-RT-1（reload catch 不含 RuntimeException）、F-ST-3（S2F15 回写无版本基准）、F-UI-1（批量同步传 null 绕过 If-Match）、F-ST-2（Db.close 锁不互斥）
Rationale: 项目对并发保护有明确设计（If-Match、snapshot/restore、单写者锁、lifecycleLock），主路径全部落地且有注释留痕；但每条保护都存在一个"绕过它的旁路"：批量同步不走编辑器草稿、S2F15 不走 If-Match、RuntimeException 不走回滚 catch、close 不走写锁。四个旁路分属三个模块，模式一致。
Implications: 逐点修复之外，建议建立"写路径清单"：列出所有会修改配置/运行态的入口（编辑器保存、分段保存、批量同步、S2F15 回写、reload、销毁），逐条标注其并发保护与失败回滚覆盖，新增写路径必须过这张表。这比再加一层锁更能防复发。
Confidence: high

## Insight: 测试金字塔重心在 E2E，新核心（v7 值模型）恰好落在覆盖洼地

Derived From: Dim03（领域）、Dim02（协议）、Dim04（运行时）
Evidence Cluster: v7 值模型核心类（SourceEvaluator/DataSource/DataSourceValueSource/ContextTable/CompiledCycleClock）core 层零直接单测；协议层 19,808 行对 1,679 行测试；secs-cli 26 文件 4,699 行 E2E 是事实主测试面；F-CORE-1（F4 钳制）与 F-CORE-2（重名单元）均因无单测而漏网
Rationale: 763 项后端测试全绿说明既有覆盖面内质量稳定，但两个本轮确认的 P1 领域缺陷都位于"没有单测的新代码"。E2E 夹具与实现同源（dim02 指出），对协议边界缺陷（控制帧撞号、Deselect 事务）天然盲。v7 是 3.0 后的新地基，其纯函数特性（无 I/O、注入时钟、确定性 Rng）使单测成本极低——覆盖洼地与测试便利性倒挂。
Implications: 优先给 v7 值模型五个核心类补边界单测（F4 越界、重名单元、相位越界、NaN/Inf、周期切换瞬间），预计每个类 10-20 项即可覆盖本轮全部发现；协议层补控制帧撞号与发起方 Deselect 两条回归。这比继续加 E2E 的边际收益高。
Confidence: high

## Insight: 门禁的"静默全绿"风险比门禁缺失更值得警惕

Derived From: Dim01（架构）、Dim08（CI）
Evidence Cluster: F-ARCH-1（allowEmptyShould(true) + DO_NOT_INCLUDE_JARS + ASM 版本仅 dm 钉住，父 POM 注释自述 JDK25 major 69 会让旧 ASM 静默跳过所有类）、CI 缺文案门禁、GUI Rust 零 CI
Rationale: 项目有真实的门禁文化（-Werror、enforcer、双平台矩阵、契约检查），但 ArchitectureTest 在导入 0 类时会全部空转通过且无任何告警——这比没有测试更危险，因为它提供虚假安全感。父 POM 注释说明团队知道这个陷阱，却只差一个哨兵断言没有补。
Implications: 给 ArchitectureTest 加"导入类数下限 + 各关键包非空"断言（约 10 行代码）；CI 补文案门禁与 cargo check。门禁自身的可信度应作为门禁的一部分来测试。
Confidence: high

## Insight: 文档密度与注释留痕是本项目最大的可维护性资产，漂移修复成本低

Derived From: Dim01、Dim02、Dim04、Dim08 及历史报告核对
Evidence Cluster: 14 项协议历史修复全部在位且有行号可查；3.0.1/3.5.0 修复声明抽查基本属实；设计决策（D9-D12、审计编号）在代码注释处留痕；ADR 0001-0020 齐全
Rationale: 五份历史评审报告的问题清单能在当前代码中逐条核销，说明整改闭环真实运转；本轮发现的文档漂移（README 3 处、ADR 2 处、注释若干）都是"改代码时忘改文档"而非"文档从未对过"。
Implications: 文档修复是一次性小工作量（估半天），且可借 Insight 1 的生成式方案根治 API 表部分。评审-整改-核销的闭环流程值得保持。
Confidence: high