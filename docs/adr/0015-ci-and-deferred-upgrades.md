# ADR 0015 · 补 CI；Jetty / SQLite / 签名 / Spooling 暂不做

- 状态：已接受
- 日期：2026-10-30
- 影响范围：`.github/workflows/`、`.gitignore`、`README.md`；无产品代码变更

## 问题

仓库此前没有 CI（`.github` 甚至被 `.gitignore` 忽略），质量闸门只有本地脚本
`scripts/check-all.ps1`。这带来两个具体风险：

1. "只在本机能跑"的回归没人挡——尤其是端口探测、`SO_REUSEADDR`、
   原生二进制路径这类平台相关行为；
2. 原生链路（`-Pnative`）从来没人定期跑，镜像哪天坏了都不会有人知道。

同时，早先的路线图里列了一批"升级项"：Jetty、SQLite、安装包签名、Spooling。
它们一直被标成"待决策"，没有形成书面结论。

## 决策

### 1. 主 CI：后端双平台 + 前端 + 契约

`.github/workflows/ci.yml`，push/PR/手动触发：

- **后端矩阵（ubuntu + windows）**：`mvn -B test`。两个平台都跑是有意的：
  端口冲突探测、`SO_REUSEADDR` 语义、路径分隔符在两边不同，单平台绿不算数；
- **前端**：`npm ci` → `tsc --noEmit` → Vitest → Playwright（Chromium）；
- **契约**：openapi 重复键（`check-openapi.py`）与 `.ps1` UTF-8 BOM
  （`check-bom.ps1`）。

本地 `check-all.ps1` 保留：它带 `-SkipBackend` / `-SkipE2E`，更适合提交前自查；
CI 不替代本地门禁，只是让"忘了跑"不再无声无息。

### 2. 原生镜像夜间任务（非阻断）

`.github/workflows/native-nightly.yml`：每天 02:00（Asia/Shanghai）在
windows-latest 上构建前端产物、装 GraalVM 25、跑 `-Pnative verify`
（含 `NativeRuntimeIT`），产物上传为 artifact。

`continue-on-error: true` 的理由：镜像构建十分钟上下，且依赖 runner 自带的
MSVC 工具链；它坏了应该有人看，但不该阻塞日常 CI。发版仍以本地
`-Pnative verify` 为准。

### 3. Jetty：不做

线程饥饿已由 ADR 0013 的虚拟线程解决。换 Jetty 的收益是零，成本是
依赖树、native-image 配置和全部路由代码的适配。**当前不换，也没有计划换。**

### 4. SQLite：不做

报文日志与审计是"追加 + 定期轮转"的形态，现有文件实现（`MessageLog` 异步写、
`AuditLog` 轮转）已经够用。引入 sqlite-jdbc 会带来 JNI 依赖，直接破坏
单文件原生镜像的价值；换成纯 Java 实现（如 H2）则是把一个简单问题复杂化。

### 5. 安装包签名：不做

代码签名需要证书与私钥托管，属于外部资产，不是代码问题。
打包脚本（`scripts/package-windows.ps1`）已经预留了签名步骤的位置；
拿到证书后填入即可，不需要先行重构。

### 6. Spooling（S2F43–S2F48）：不做，保留为特性提案

Spooling 是 GEM 的可选能力：设备在主机离线时把事件缓冲起来，重连后按
S2F43/F45/F47 的约定补发。实现它需要动协议层（报文识别）与 GEM 状态机，
还要定义"缓存多大、什么时候丢、怎么与现有事件编排共存"。

**结论：不作为本次升级项**。若现场确实需要"断线不丢事件"，先提需求，
按 ADR 流程单独设计（数据保留、重发顺序、背压三项必须先定清楚）。

## 后果

- 每次 push 都有客观的"两边平台都绿"的证据；
- 原生链路有了夜间探针，坏掉会留下日志与产物；
- 四个未采纳项有了书面结论，不再以"待决策"的模糊状态存在。
