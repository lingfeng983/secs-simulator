# 构建 / CI / 桌面壳 / 文档修复日志（dim08）

日期：2026-11-24。问题清单来源：`research/code-review-2026-11-24_dim08.md`。
约束：只动 `.github/workflows/`、`secs-simulator-gui/src-tauri/src/`、`README.md`、
两份 ADR、`scripts/live-backend.ps1`、`scripts/package.ps1` 与本日志；未触碰
secs-simulator-service / secs-simulator-ui 源码。

## 上一轮已完成（本轮核实通过）

| 项 | 内容 | 核实结果 |
|---|---|---|
| B1 | ci.yml frontend job 加 `check-ui-words.ps1` 步骤（ci.yml:59-62，`shell: pwsh`，working-directory=secs-simulator-ui，路径 `./scripts/check-ui-words.ps1` 正确） | ✅ 在位；`yaml.safe_load` 通过，jobs=backend/frontend/gui/contract |
| B2 | ci.yml 新增 gui job（ci.yml:67-86）：windows-latest，working-directory=`secs-simulator-gui/src-tauri`，`cargo check --locked`；占位 dist 步骤用 `$env:GITHUB_WORKSPACE` 拼 `secs-simulator-ui/dist` | ✅ 路径逻辑正确：`tauri.conf.json:7` 的 `frontendDist: "../../secs-simulator-ui/dist"` 相对 src-tauri 解析即仓库根 `secs-simulator-ui/dist`，与占位路径一致（pwsh 步骤不受 defaults.run 的 working-directory 之外的 cwd 影响，用绝对 GITHUB_WORKSPACE 拼接，稳妥） |
| B3 | README v1 ceid/alarm 触发端点已替换为 v2 并标废弃（现 README.md:158-161：v2 runtime 触发两行 + alarms/terminal 已标 Deprecation/Sunset） | ✅ 在位 |

## 本轮完成

1. **ci.yml 语法核实**：`python yaml.safe_load` 通过（4 个 job）；gui job 路径逻辑见上表 B2。未再改动 ci.yml。
2. **main.rs cfg(windows) 包裹**（`secs-simulator-gui/src-tauri/src/main.rs`，原 :292-295）：无条件 `use std::os::windows::process::CommandExt; command.creation_flags(0x0800_0000)` 块加 `#[cfg(windows)]`，附一行注释；与同文件 :271-274 既有双平台分支风格一致。改动仅 +2 行（cfg 属性 + 注释）。
3. **README:29 store 依赖行**：`core + json + sqlite-jdbc` → `json + sqlite-jdbc`；删除「含 2.x JSON 的一次性迁移」。与 secs-store/pom.xml 一致（评审已核实），ConfigMigrator 移除另经 `RuntimeOptionsTest.java:64-74` 证实（--migrate-config 报未知参数）。
4. **README:76 native-nightly 措辞**：「非阻断」→「不在 PR 门禁路径上；夜间失败会红」，与 native-nightly.yml:6-7 口径统一。
5. **README:156 DELETE 会话行**：「关闭并移除会话」→「断链不删实体（**已废弃**，建议 v2 `DELETE /api/v2/devices/{id}`）」。语义经 `SessionHandler.java:241,294` 注释证实（「关闭 = 断链不删实体」）。
6. **ADR 后记**（正文未动，文末新增「## 后记（2026-11-24）」）：
   - `docs/adr/0017-sqlite-config-store.md`：注明 ConfigMigrator 与 --migrate-config 已移除（迁移演练/legacy 条目按历史记录阅读）；投影表未实现。
   - `docs/adr/0016-layout-and-layer-consolidation.md`：注明 hostsim→session 单向由 package-private + 编译器守护，ArchUnit 规则本轮已补（archunit-junit5 1.5.1 在 secs-runtime test 依赖中，pom 已核实）。
7. **live-backend.ps1 参数化**：仓库根改为 `Split-Path $PSScriptRoot -Parent`；java 改 `$env:JAVA_HOME/bin/java.exe`（JAVA_HOME 缺失或 java.exe 不存在均 throw 可读错误）；新增 `param([int]$Port = 56193)`，`--port` 用 `"$Port"`。加 `$ErrorActionPreference='Stop'`。BOM 保留。
8. **package.ps1 产物陈旧警告**：新增 `Test-ArtifactFreshness`（产物 mtime vs max(git HEAD 提交时间, 源码目录最新文件 mtime，排除 target/node_modules/dist)，git 不可用降级为仅源码 mtime；异常只警告），在 `-SkipFrontend`（dist/index.html vs ui/src）与 `-SkipNative`（secs-sim.exe vs service 源码）分支各调用一次，`Write-Warning` 不阻断。-SkipJars 未纳入（本轮任务范围仅 Native/Frontend）。BOM 保留。

## 验证结果

- `yaml.safe_load('.github/workflows/ci.yml')`：通过。
- PowerShell `Parser::ParseFile`：`scripts/live-backend.ps1`、`scripts/package.ps1` 均 0 错误；两文件 UTF-8 BOM 均在。
- Rust：本机有 cargo，`cargo check --locked`（src-tauri，占位/现有 dist）编译通过（Finished dev profile, 4.96s）——main.rs cfg 改动实测无回归；非 Windows 分支仍无本机实测（无 Linux Rust 环境），由 ci gui job（windows）+ cfg 对称性保障。

## 改动文件汇总

- `secs-simulator-gui/src-tauri/src/main.rs`（+2 行）
- `README.md`（3 处行内修改：:29 / :76 / :156）
- `docs/adr/0016-layout-and-layer-consolidation.md`（文末后记）
- `docs/adr/0017-sqlite-config-store.md`（文末后记）
- `scripts/live-backend.ps1`（参数化重写头尾，核心 classpath 逻辑未动）
- `scripts/package.ps1`（+Test-ArtifactFreshness 函数与两处调用）
- `research/fix-log-build.md`（本日志）
- `.github/workflows/ci.yml`：本轮零改动（B1/B2 上轮已落，仅核实）

## 未覆盖 / 遗留（评审清单中本轮未做）

- ci.yml:106 BOM 排除正则仅匹配 `\`（Linux 失效，P2 潜在坑）——未改（不在本轮任务项）。
- -SkipJars 同版本陈旧 jar（P2）、package.json 版本纳入校验链（P3）、check-openapi 描述窄报（P3）、main.rs 锁风格统一（P3）、后端崩溃自动看护（P2）——均未动。
- CI 实际运行（push 后绿/红）未验证，本轮全部为本地静态/编译验证。