# 专项评审：构建 / CI / 打包 / 桌面壳 / 文档一致性（dim08，2026-11-24）

评审范围：`E:\ai\secs-simulator`（只读调查）。证据均来自本次实际读取的文件，引用格式 `路径:行号`。

## 现状概述

- CI 两条工作流：`ci.yml`（backend 双平台矩阵 + frontend + contract 三个 job）与 `native-nightly.yml`（双平台原生镜像夜间构建，无 `continue-on-error`）。本地门禁为两个 `check.ps1`，失败即停、带 UTF-8 BOM。
- 打包唯一入口 `scripts/package.ps1`（373 行）：版本号单源 `pom.xml`，与 `tauri.conf.json`、`Cargo.toml` 强制对齐，NSIS 安装包文件名再验一次版本；原生 exe 打包前做 READY + `/api/v1/health` 冒烟。
- 桌面壳 `main.rs`（504 行）：单窗口 + 托盘 + 单实例插件 + Windows Job Object kill-on-close；本机/远程切换持久化到 `connection.json`。
- 版本现状：pom 父/8 模块、ui `package.json`、gui `package.json`、`Cargo.toml`、`tauri.conf.json` 全部为 **3.5.0**，已逐一 grep 核实。
- 主要缺口：CI 缺前端文案门禁与 GUI Rust 检查；README 仍列已删除的 v1 ceid/alarm 触发端点；README 模块依赖表 secs-store 行与 POM 不符；`main.rs` 有一处非 Windows 编译必炸的无条件 Windows API 调用。

## 逐项核查

### 1. CI 覆盖 vs 本地门禁

`ci.yml:5` 头注释声称：「这里把同一组检查搬到每次 push/PR，避免"只在本机能跑"的回归。」实际三个 job：

- backend（`ci.yml:24-42`）：矩阵 `os: [ubuntu-latest, windows-latest]`，`fail-fast: false`，temurin JDK 25 + `cache: maven`，仅 `mvn -B -ntp test`。与后端门禁第 1 步一致；openapi 检查由 contract job 承担，整体等价。
- frontend（`ci.yml:44-61`）：node 24 + npm 缓存，步骤为 `npm ci` → `npx tsc --noEmit` → `npm test -- --run` → `npx playwright install --with-deps chromium` → `npx playwright test`。**没有 `check-ui-words.ps1`**，而本地前端门禁第 3 步是它（`secs-simulator-ui/scripts/check.ps1:50-52`：`Invoke-Step 'check-ui-words.ps1（界面文案）'`）。"同一组检查"声明不成立（P1）。
- contract（`ci.yml:63-90`）：python 3.12 + pyyaml 跑 `check-openapi.py`；pwsh 内联 BOM 检查。注意 `ci.yml:81` 排除正则 `'-notmatch '\\(node_modules|target|package|dist)\\'` 只匹配反斜杠路径，而该 job 在 ubuntu 上跑，路径分隔符是 `/`，排除逻辑在 Linux 上失效（当前 contract job 不装依赖、不构建，仓库内无这些目录的 .ps1 时无实际影响，属潜在坑，P2）。
- **GUI Rust 完全不在 CI**：`ci.yml` 无 cargo check/clippy/fmt/test 步骤，`native-nightly.yml` 只构建后端原生镜像。Rust 壳只在本地打包时被 `tauri build` 编译验证（P1）。
- native-nightly（`native-nightly.yml:22-31`）：windows + ubuntu 矩阵，`fail-fast: false`，先构建前端 dist（`:40-44`），GraalVM 25 + maven 缓存，`mvn -B -ntp -Pnative verify -pl secs-cli -am`，产物上传 `if-no-files-found: error`。`native-nightly.yml:6-7` 明言：「任务改为双平台矩阵、失败即红（去掉 continue-on-error）——夜间不红就等于没有覆盖。」而 `README.md:76` 写「CI 里由夜间任务 `native-nightly.yml` 构建（非阻断）」。两处口径需统一：夜间失败会红，但不在 push/PR 路径上、不阻断合并（P2 文档措辞）。
- 缓存：maven（setup-java / setup-graalvm）、npm（含 cache-dependency-path）均有；concurrency 取消旧运行（`ci.yml:19-21`）；`permissions: contents: read` 最小权限。

### 2. 两个 check.ps1 与 check-openapi.py

- 后端 `secs-simulator-service/scripts/check.ps1`：文件带 BOM（首行 `﻿#`）；`:11-12` `$ErrorActionPreference='Stop'` + UTF-8 输出编码；`:39-53` `Invoke-Step` 按 `$LASTEXITCODE` 判定，非 0 即 `exit $code`，注释明言「任一步失败立即以该步的退出码结束」。顺序：`mvn -B test`（`:55`）→ `check-openapi.py`（`:58-63`，**未装 python 则跳过并打印 skipped**，CI 侧恒跑，本地存在漏检窗口但已如实声明）。`:25-33` 对 `java -version` 写 stderr 的 NativeCommandError 问题做了临时放宽，处理正确。
- 前端 `secs-simulator-ui/scripts/check.ps1`：带 BOM；`-SkipE2E` 开关；顺序 npm ci（仅缺 node_modules 时，`:42-47`）→ tsc → 文案门禁（`:50-52` 用子进程 powershell 跑，注释解释「它自己会 exit 1，直接 & 会把整个门禁会话一起带走」）→ `npm test` → playwright。同样 Invoke-Step 失败即停。注意本地是 `& npm test`（无 `-- --run`），CI 是 `npm test -- --run`；`package.json` 的 test 脚本是否 `vitest run` 本次未读，若为 watch 模式本地门禁会挂起（未核实项）。
- `check-openapi.py`：**不只查重复键**。文件头（`:2`）：「OpenAPI 静态校验：重复键、$ref 可解析、path 至少有一个操作。」实现：UniqueKeyLoader 重复键抛错（`:25-32`）、每个 path 至少一个 HTTP 操作（`:74-76`）、内部 `#/` $ref 可解析（`:78-82`）。局限：外部 $ref 不校验；`resolve` 不处理 JSON Pointer 的 `~0/~1` 转义；非完整 OpenAPI 规范校验。CI 步骤名「openapi 重复键」（`ci.yml:72`）与 `docs/README.md:12`「校验重复键」的描述比实际能力窄（P3，低报而非高报）。

### 3. scripts/package.ps1

- 版本机制（`:67-87`）：注释「版本只有一个来源：secs-simulator-service/pom.xml。tauri.conf.json / Cargo.toml 必须跟它是同一个字符串」；正则取 pom 第一个 `<version>`，与 tauri.conf.json 的 `version`、Cargo.toml 的 `^version = "…"` 逐一比对，不一致直接 throw。收集产物时再验安装包文件名（`:243-244`：`if ($installer.Name -notlike "*_${version}_*-setup.exe") { throw … }`）。**ui/gui 的 package.json 版本不在校验链里**（当前恰好都是 3.5.0，漂移不会被拦，P3）。pom 取"第一个 `<version>`"的正则较脆弱（若文件前部出现其他 version 标签会取错，P3）。
- Skip 开关风险：`-SkipNative` 只查 `Test-Path $exe`（`:184`），不校验 exe 新鲜度或与当前源码/版本一致——同版本旧 exe 可进包；有 `Test-NativeBackend` 冒烟（`:115-154`，READY 正则 + 90 秒超时 + health JSON `ok:true`）兜住"起不来"，但兜不住"旧代码"。`-SkipFrontend` 只查 `dist/index.html` 存在（`:103-104`），无任何新鲜度校验。`-SkipJars` 按 `$module-$version.jar` 精确文件名查（`:285`），版本漂移能拦，同版本陈旧 jar 拦不住（P2）。
- 原生命令 stderr 的 NativeCommandError 全部按"临时放宽 + 退出码判定"处理（`:171-179`、`:222-231` 等），并留有事故注释（`:92-96` Write-Host 被当返回值的坑）。
- 产物命名：Windows 用 Tauri 生成的 `*_<version>_*-setup.exe` 原名拷入 `scripts/out/`；Linux 为 `secs-sim-server-$version-linux.zip`（`:358`），stage 内模块 jar 去版本号（`:309-310`，注释：升级换版本不用改 start-server.sh），写 `VERSION` 文件、README 占位符替换、`bash -n` 语法自检（无 bash 则警告跳过）。schema 文档存在性检查（`:329-334`）报错会列出现有 schema-*.md，可读性好。
- 打包路径 `mvn -DskipTests`（`:174`、`:270`）：测试交给门禁，打包不重跑，属明确分工。### 4. Tauri 桌面壳（secs-simulator-gui/src-tauri）

- 配置 `tauri.conf.json`：version 3.5.0（`:4`）；`frontendDist: "../../secs-simulator-ui/dist"`（`:7`）内嵌前端；CSP（`:26`）`default-src 'self'; … script-src 'self'; connect-src 'self' ipc: http://ipc.localhost http: https:`——connect-src 放开全部 http/https 是远程模式的必要代价；无 shell 插件、无 capabilities 中的 shell open 授权迹象（本次未读 capabilities 目录，见置信度）。`withGlobalTauri: true`（`:10`）、NSIS currentUser、仅 nsis target。
- `src/main.rs`（唯一 rs 文件，504 行）：
  - **READY 解析**（`:331-348`）：stdout 逐行经 mpsc 通道，`line.starts_with("READY")` 后 `split_whitespace().find_map(|part| part.strip_prefix("http="))` 取端口，30 秒超时；失败路径 kill+wait 并附 stderr 尾部（`:350-361`）。stderr 环形缓冲上限 64 KiB 且按 char boundary 裁剪（`:319-326`），不会切碎 UTF-8。与打包冒烟的正则 `READY[^\r\n]*http=(\d+)`（package.ps1:135）、verify-trace-flow.mjs 语义一致。
  - **进程管理**：启动 `spawn_local`（`:277-363`）`--mode=integrated --port=0 --work-dir=<app_data>/SecsSimulator`，`creation_flags(0x0800_0000)`（CREATE_NO_WINDOW）；Windows Job Object `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`（`:224-268`），注释明确「句柄泄漏是刻意的」，GUI 被强杀时内核回收后端；挂 Job 失败仅警告不致命（`:299-302`）。停止：托盘 quit（`:106-111`）与 `RunEvent::Exit`（`:144-150`）都调 `stop_backend`（kill+wait，`:49-56`）。关窗只隐藏（`:132-140`）。
  - **崩溃恢复**：无自动重启/看护线程；`switch_backend("local")` 时 `try_wait` 探活（`:388-409`），发现已退出则重新拉起——注释「后端可能已经自己退了（崩溃/被结束），此时"已是本机"不能当成还活着」。即崩溃后需要用户手动切一次连接才恢复，界面仅显示不可达（P2，可接受但应知晓）。
  - **远程切换**（`:425-441`）：trim + 去尾斜杠，`url::Url` 解析并限定 http/https；同地址幂等短路；切远程先 `stop_backend` 再持久化 `Persisted::Remote`。配置读写 `connection.json`，损坏/缺失回落 Local（`:177-182`）。
  - **跨平台编译缺陷**：`:292-295` 在 `spawn_local` 内无条件使用 `use std::os::windows::process::CommandExt; command.creation_flags(…)`，没有 `#[cfg(windows)]` 包裹；同文件 `attach_to_kill_on_close_job` 却做了双平台分支（`:271-274`）。该文件在非 Windows 目标上必然编译失败（P2；当前仅发 Windows 包故不爆，但 cfg 分支的存在暗示有跨平台意图）。
  - **Rust 质量抽查**：多处 `lock().unwrap()`（`:84`、`:371-372` 等）与 `stop_backend` 的 `if let Ok(...)` 风格不一；锁中毒时 panic。`self_test`（`:446-504`）成功路径 `sleep(25s)` 硬等待、大量 `expect`，属自检工具可容忍。`resource_root`（`:196-218`）多候选路径探测，兼容安装/便携两种布局。整体注释密度高、每个坑有解释，质量良好。

### 5. 版本一致性（grep 实测）

全部为 3.5.0：`secs-simulator-service/pom.xml:9`、8 个子模块 pom `:10`（cli/store/api-http/core/app/json/protocol/runtime）、`secs-simulator-ui/package.json:4`、`secs-simulator-gui/package.json:4`、`secs-simulator-gui/src-tauri/Cargo.toml:5`、`tauri.conf.json:4`。**当前无漂移**；同步机制只覆盖 pom↔tauri.conf↔Cargo 三处（见第 3 节）。

### 6. 文档一致性

- **模块依赖表错误（证实）**：`README.md:29` secs-store 行依赖约束写「core + json + sqlite-jdbc」；实际 `secs-simulator-service/secs-store/pom.xml:24-37` 依赖只有 `secs-json`、`sqlite-jdbc`、`junit-jupiter(test)`，且 `:20` 注释明言「本模块只认识 secs-json + JDBC」。**没有 secs-core**，README 多写了 core（P2）。
- **API 表列已删端点（证实）**：`README.md:158-159` 仍列 `POST /api/v1/sessions/{id}/ceid/{ceid}`（202/404/409）与 `POST /api/v1/sessions/{id}/alarm/{alid}?set=…`。实际 `SessionHandler.java:479-482`：「手动触发（ceid / alarm）也已迁 v2（POST|DELETE /api/v2/devices/{id}/runtime/…）…接口不存在回 404」，POST 兜底 404；v2 实现见 `DeviceRuntimeHandler.java:164-166`（alarm/alarms/ceid 分派）。README 该表未随删除更新（P1）。另 `README.md:160` 的 `GET …/alarms` 行未提已挂 Deprecation/Sunset 头与响应改为 `{"alarms":…,"hint":…}` envelope（`SessionHandler.java:407-420`）；`:161` terminal 同样已废弃未标注（P2）。
- **docs/README.md 分区**：`docs/README.md:8` 声称 adr「0001–0020」；glob 实测 `docs/adr/` 恰好 0001–0020 共 20 个文件，**齐全**，且 0004 标注「已被 0020 取代」与文件名对应。`:12` 对 check-openapi.py 的描述只提「校验重复键」，窄于实际（P3）。guides/reports/examples/reference 分区本次仅核对 adr 与 guides 引用文件名（schema-v7.md 被 package.ps1:329 引用存在性有脚本自检），未逐目录盘点。

### 7. live-backend.ps1 与 verify-trace-flow.mjs

- `scripts/live-backend.ps1`（23 行，自称 "temp helper"）：**硬编码本机绝对路径** `E:/ai/secs-simulator`（`:2`）与 `E:/Java/graalvm-jdk-25.0.4+7.1/bin/java.exe`（`:11`），固定端口 56193，依赖 `secs-cli/cp.txt`。模块 target/classes 前置以压过 .m2 旧 jar（`:4-8` 注释），思路正确但不可移植、无参数化、无失败处理（P2：临时脚本入库应参数化或移入不入库目录）。
- `scripts/verify-trace-flow.mjs`（450 行，读前 100）：对打包 exe 做全 HTTP 链路验收（新建设备→主机→连接→S1F3→Trace），默认目标 `src-tauri/resources/secs-sim.exe`，临时工作目录、READY 30 秒超时、stderr 环形缓冲 200 块、逐步 PASS/FAIL 记账、finally 收尾。工程质量好，与打包冒烟互补（冒烟只查 health，这个查业务链路）。未列入任何 CI/门禁（README:412 明言真实链路手动跑，属已知分工）。

### 8. build/ 与 .run/

- `.run/` 含 `CliMain.run.xml`、`CliMain-server.run.xml` 两个共享运行配置。`CliMain.run.xml:6`：`--mode=integrated --port 5000 --work-dir build/run --web-root secs-simulator-ui/dist`，与 `README.md:64-65`「HTTP 固定 5000…work-dir `build/run`、托管 `secs-simulator-ui/dist`」一致；server 配置文件存在与 README:68 的名称对应（内容未读）。
- `build/run/` 与根 `run/` 实际存有 config.db/runtime.db/wal/shm/lock、ready.txt、native/sqlitejdbc.dll、logs——是运行产物落进了工作区目录。是否被 .gitignore 覆盖本次未核实；若入库会把本地数据库/日志带进仓库（待核实项）。

## 问题清单

| 级别 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1 | CI 前端 job 缺 `check-ui-words.ps1`，与"同一组检查"声明不符 | ci.yml:5,57-61 vs ui check.ps1:50-52 | frontend job 加一步 `shell: pwsh` 跑文案门禁 |
| P1 | README API 表仍列已删除的 v1 ceid/alarm 触发端点 | README.md:158-159 vs SessionHandler.java:479-482 | 删两行，补 v2 `/api/v2/devices/{id}/runtime/…` 指引 |
| P1 | GUI Rust 无任何 CI 检查（cargo check/clippy/fmt/test 均缺） | ci.yml 全文、native-nightly.yml 全文 | 加 windows runner 的 `cargo check`（或 clippy）job；可顺带跑 `SECS_GUI_SELFTEST` |
| P2 | main.rs 无条件调用 Windows-only `creation_flags`，非 Windows 编译必败 | main.rs:292-295（对比 :271-274 有 cfg 分支） | 用 `#[cfg(windows)]` 块包裹 |
| P2 | README secs-store 依赖行多写 core | README.md:29 vs secs-store/pom.xml:24-37 | 改为「json + sqlite-jdbc」 |
| P2 | native-nightly「非阻断」口径不一：README 说非阻断，yml 说失败即红 | README.md:76 vs native-nightly.yml:6-7 | README 改为「不在 PR 门禁路径上，夜间失败会红」 |
| P2 | -SkipNative/-SkipFrontend 复用产物无新鲜度校验（同版本旧 exe/dist 可进包） | package.ps1:103-104,184 | 比对产物 mtime 与源码/git HEAD，或要求显式确认 |
| P2 | v1 alarms/terminal 已废弃（Deprecation/Sunset + envelope）但 README 未标注 | README.md:160-161 vs SessionHandler.java:407-420 | 表内加废弃标记与 v2 替代 |
| P2 | live-backend.ps1 硬编码本机盘符/JDK 路径入库 | live-backend.ps1:2,11 | 参数化（$PSScriptRoot 推根、JAVA_HOME）或移出仓库 |
| P2 | 本机后端崩溃无自动重启，仅手动切连接时探活重拉 | main.rs:388-409 | 可加轻量看护（try_wait 轮询 + 退避重启）或在 UI 提示重启入口 |
| P2 | CI BOM 检查排除正则只匹配 `\`，Linux 上失效 | ci.yml:81 | 正则改 `[\\/](node_modules|target|package|dist)[\\/]` |
| P3 | 版本同步机制不含 ui/gui package.json；pom 版本取"第一个 <version>"正则脆弱 | package.ps1:69-85；grep 五处版本 | 纳入 package.json 校验；pom 解析限定 project/version |
| P3 | check-openapi 能力被低报为"只查重复键"；$ref 不处理 ~0/~1 转义、外部 ref 不校验 | check-openapi.py:2,53-59；ci.yml:72；docs/README.md:12 | 更新描述；按需补转义处理 |
| P3 | 锁处理风格不一（unwrap vs if let Ok），中毒即 panic | main.rs:50,84,371 | 统一策略（unwrap 或 poisoned 恢复） |

## 亮点

- 版本单源 + 三重校验（pom↔tauri.conf↔Cargo↔安装包文件名），漂移在打包早期即报错且报错文案给出修法。
- 打包前原生 exe 真启动冒烟（READY + health JSON），并有注释解释"打得出来≠装完能用"的具体事故（seed 资源漏进镜像）。
- Job Object kill-on-close 处理 GUI 被强杀的孤儿进程问题，句柄故意泄漏有注释；stderr 64 KiB 环形缓冲按字符边界裁剪。
- 两个 check.ps1 与 package.ps1 系统性处理了 PS 5.1 的 stderr→NativeCommandError 陷阱，失败即停语义清晰；BOM 契约进了 CI。
- 大量"事故注释"（Write-Host 污染返回值、cp.txt 旧 jar、bash 缺失打断打包）把踩坑经验固化在代码旁。
- verify-trace-flow.mjs 提供一条命令复验装机业务链路，临时目录自收尾。

## 置信度说明

已核实：两条 workflow 全文、两个 check.ps1 全文、check-openapi.py 全文、package.ps1 全文（373 行分两段读完）、main.rs 全文、tauri.conf.json 全文、store POM 全文、README 55-209 行、docs/README.md 全文、adr 目录清单、五处版本号 grep、SessionHandler 350-499 行、live-backend.ps1 全文、.run 配置之一、build/run 与 run 目录清单。

未核实项（结论不覆盖）：
- ui `package.json` 的 test 脚本是否 `vitest run`（影响本地门禁 `npm test` 是否 watch 挂起的判断）。
- 提交 456f189 的具体 diff（v1 端点删除以当前源码注释与 404 兜底为证，未查 git 历史）。
- Tauri capabilities/权限文件（shell open 等授权仅从 conf 与 Cargo 依赖侧面推断未读 capabilities 目录）；`Cargo.toml` 依赖全文。
- `check-ui-words.ps1`、`build.ps1`、`start-server.sh`、`CliMain-server.run.xml` 内容。
- `.gitignore` 是否覆盖 `build/`、`run/`、`scripts/out/`（运行产物是否入库未确认）。
- verify-trace-flow.mjs 101-450 行、README 其余区间、docs guides/reports/examples 逐文件盘点、openapi.yaml 与 README API 表的逐端点对照。
- CI 实际运行历史（绿/红状态）未查，仅静态审查 yml。