# 3.1.0 发布说明

发布日期：2026-11-15
类型：功能版（新增并发采集能力；无数据库 schema 变更、无破坏性 API 变更）

3.0.1 之后针对现场「一台主机要同时盯多组变量」的需求，把 S1F3 采集器从单份改成按采集计划分组；
同时修掉几个会静默丢数据的时序问题与「装完起不来」的桌面版缺陷，并把「包打得出来 ≠ 装完能用」
的冒烟门禁加进打包流程。升级即生效，无需迁移。

## 新功能：一台主机同时跑多份 S1F3 采集计划

| | 3.0.1 | 3.1.0 |
|---|---|---|
| 采集器 | 一台主机一份 | 按采集计划（`planId`）各一份，互不干扰 |
| 下发配置 | 下发 B 会覆盖 A，A 的数据就断了 | 只更新指定计划，其它计划照常在采 |
| 启停 | 整台主机一起启停 | 可只启停某一份；省略 `planId` = 全部 |
| 状态 | 一份汇总 | 顶层汇总 + `collectors[]` 明细（每份的运行/待命/样本/曲线） |
| 重连 / 重启 | 单份配置恢复 | 逐份恢复，各等自己的启动 CEID |

- 删除配置区里的采集方案时，后端同步停掉并移除对应采集器、重存配置——以前界面删了、
  后台还在按周期采，重启后又随配置复活，而界面上早没那一行了。
- 持久化兼容：`collector_json` 改用 `{"collectors":[...]}`；老库里的单份配置自动落到
  `default` 计划，升级不需要迁移。老客户端不带 `planId` 的请求也按单份语义处理。
- API：`CollectorConfig` 增加可选 `planId`；
  `POST /api/v1/host-sims/{id}/collector/start|stop?planId=` 可指定计划；
  `CollectorState` 顶层 `configured/running/armed` 改为「任意一份」的汇总，
  明细在 `collectors[]`（OpenAPI 同步）。
- 界面：采集配置表每行独立状态与「启动 / 停止」，批量启停按份跳过；主机概览显示计划份数
  与合计样本；采集与 Trace 的启停按钮恒为「启动 ↔ 停止」两态，避免首帧未到时重复下发
  S2F23、或把正在采的采集器按回待命（状态推断抽成 `hostRuntimeStatus.ts` 并加单测）。

## 必须升级的理由（用户可见的缺陷）

| 问题 | 触发方式 | 修复前表现 | 修复后 |
|---|---|---|---|
| 桌面版装完只剩「后端不可达」 | 安装后首次启动 | 原生镜像漏了内置模板 `seed/*.json`，播种时直接退出；安装包装得出来、看着完全正常 | 资源打进镜像；打包前真起一遍 exe，等 READY 并请求 `/api/v1/health`，不通过不出包 |
| 第二个启动事件石沉大海 | 同一毫秒手动触发两个 CEID | 撞同一个幂等键 `deviceId|manual|atMs`，第二条 S6F11 被静默丢弃，第二份采集计划永远等不到启动 CEID | 台账标识落到实际匹配到的计划；没匹配计划时按 CEID 区分 |
| 采集中点「启动」反而停采 | 设备发启动 CEID 后界面补一次启动 | 老实现先停掉轮询再回待命，只收到一两条 S1F3 就没数据了 | 手动启动幂等，已在跑直接返回 |
| 重复点「连接」报失败 | 新建主机时后端已顺手建链，再点一下连接 | 回 502「连接失败」，其实链路好好的 | 已连接 / 连接中直接回 200 |
| 200 不是 JSON | 桌面壳本机后端没起来，`/api/*` 落到内嵌 `index.html` | 界面只报「200 返回的不是 JSON」，真正原因（后端起不来）被盖住 | 请求直接带失败原因退出，横幅给出可排查的话；200 返回 HTML 时明确提示地址 / 后端问题 |

## 打包与验收工程

- 原生镜像补 `-H:IncludeResources=seed/.*` 与 reachability 资源声明。
- `scripts/package.ps1` 修三处：`build.ps1` 的 `Write-Host` 输出被当成函数返回值污染拷贝路径、
  随包配置格式说明从 schema-v5 升到 v7（漏改会在打包最后一步才报错）、PATH 里没有 bash 时
  把整套打包打断；新增「原生后端冒烟」门禁（临时工作目录真起 exe，READY + health）。
- 打包脚本另修一处：PS 5.1 在 `Stop` 模式下会把原生命令的 stderr 警告当终止错误抛出，
  JDK 25 的警告足以打断 native image 与 maven jars；现在四处原生调用临时放宽、
  按退出码判成败，输出照常显示。
- 新增 `scripts/verify-trace-flow.mjs`：新建设备 → 新建主机 → 连接 → S1F3 采集 →
  S2F23/S6F1 Trace 采集，全程只走 HTTP API，测的就是装进安装包的那个 exe。
- Linux 包 README 更新数据目录（`config.db` / `runtime.db` / `ready.txt`）、
  `thirdparty/` 与 `native/` 目录说明、schema-v7 文档。
- 两条端到端用例同步到当前界面（设备模板弹框标题与「配置原文」页签、报文预设表格化）。

## 验收

| 层 | 命令 | 结果 |
|---|---|---|
| 后端 | `mvn -B test` | 695 项通过（json 110 / protocol 56 / core 80 / store 24 / app 83 / api-http 133 / runtime 123 / cli 86） |
| 契约 | `check-openapi.py` | 76 path 全绿 |
| 类型 | `npx tsc --noEmit` | 0 错误 |
| 组件 | `npm test -- --run` | 27 文件 / 180 项 |
| 端到端 | `npx playwright test` | 68 passed |
| 文案 | `check-ui-words.ps1` | 137 文件 / 6475 段 |
| 打包 | `scripts/package.ps1 -Target All` | 原生后端冒烟（READY + `/api/v1/health`）通过；Windows 19.7 MB / Linux 15.4 MB 双端产物 |
| 装机后全链路 | `node scripts/verify-trace-flow.mjs` | 37/37 项通过（新建设备 → 主机 → 连接 → 两份采集计划并发 → 独立启停 → 方案删除回收 → S2F23/S6F1 Trace） |

## 升级步骤

- **桌面版**：直接覆盖安装 `SECS Simulator_3.1.0_x64-setup.exe`（工作目录与两个 SQLite 库向前兼容）。
- **服务端（Linux/JVM）**：解压覆盖 `secs-sim-server-3.1.0-linux.zip`，重启进程即可。
- 3.0.1 → 3.1.0 无数据库 schema 变更，也没有配置迁移；老的单份采集配置自动落到
  `default` 计划。想用多计划并发，在配置区给每个采集方案重新下发一次配置即可（界面会自动带上 `planId`）。
