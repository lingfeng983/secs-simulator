# M1 验收报告 · 领域模型 + 运行入口

## 交付

| 项 | 内容 |
|---|---|
| 领域模型 | `UnitId` / `SvidDef` / `CeidDef` / `ReportDef` / `GlobalSvidDef` / `GlobalCeidDef` / `UnitSpec` / `EquipmentSpec` / `IdSpacePolicy` / `IdRange` |
| 编译与校验 | `EquipmentCompiler`：槽位 → 绝对 ID 解析 + 14 条不变式检查，一次报出全部问题 |
| 运行入口 | `runtime.Main`：`--mode=integrated\|server\|probe`、`--port 0`、token 自动生成、stdout 握手 |
| HTTP API | `/api/v1/health`（免鉴权）、`/api/v1/events`（SSE 心跳）、`/api/v1/shutdown`（需 Bearer） |
| 探针 | `--mode=probe`：开 HSMS-SS 被动端口并应答 S1F1，用于现场排障与 CI |

## 校验规则一览

| 代码 | 含义 |
|---|---|
| `NO_UNITS` | 设备没有任何单元 |
| `DUPLICATE_UNIT_ID` / `DUPLICATE_UNIT_NAME` | 单元编号重复（错误）/ 单元名重复（提示） |
| `DUPLICATE_SLOT` | 同单元内 SVID / 报告 / CEID 槽位重名 |
| `DUPLICATE_OFFSET` | 同单元内段内偏移重复 |
| `DUPLICATE_SVID` / `DUPLICATE_REPORT` / `DUPLICATE_CEID` | 解析后的绝对 ID 冲突 |
| `STRIDE_TOO_SMALL_SVID` / `_REPORT` / `_CEID` | 段步长 ≤ 最大偏移，相邻单元会重叠 |
| `RESERVED_ID` | 落在保留区间（默认 1..999） |
| `UNKNOWN_SVID_REF` / `UNKNOWN_REPORT_REF` | 报告引用了不存在的 SVID / CEID 引用了不存在的报告 |
| `AMBIGUOUS_REPORT_REF` | 设备级 CEID 用了跨单元重名的短报告名 |
| `MISSING_UNIT_ID_SVID` | 开了 `injectUnitId` 却没有 `UNIT_ID` 槽位 |

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **30 个**（core 18 + runtime 12），全绿 |
| 架构规则 | 6 条，实际检查 312 个类 |
| 原生镜像 | `secs-sim.exe` **12.7 MB**，增量重建 26 s |
| 原生端到端 | `NativeRuntimeIT`：握手 → health → 鉴权 401 → SSE 首帧 → S1F1/S1F2 → 停机退出 0，**0.6 s** |
| 验收脚本 | `scripts/accept-M1.ps1` 退出码 0 |

## 踩到的三个坑（都已修）

### 1. Windows PowerShell 5.1 会按 ANSI 读取无 BOM 的 .ps1

验收脚本里有中文，读成 GBK 后引号配对错乱，PowerShell 直接报
"Missing closing '}'"——看起来像语法错误，其实是编码问题。
**修法：.ps1 一律写 UTF-8 BOM。**

### 2. `Invoke-RestMethod` 会走系统代理，访问 127.0.0.1 也失败

这台机器配了代理，脚本里 `Invoke-RestMethod http://127.0.0.1:PORT/...` 报
"Unable to connect to the remote server"，但用 curl/浏览器都是通的。
**修法：验收脚本改用裸 socket 验证端口连通性，不再依赖 PS 的 HTTP 客户端。**
（Java 侧不受影响：`HttpClient` 默认不走系统代理，所以 `NativeRuntimeIT` 一直是对的。）

### 3. `TcpListener(0).LocalEndpoint.Port` 在未 Start 时拿不到端口

脚本原想"先挑一个空闲端口再传给进程"，结果拿到的是 0，
进程自己分配了端口并打印在 READY 里，脚本却去连 0。
**修法：不预选端口，从 READY 行解析真实端口。** 这条也顺带让脚本更贴近桌面壳的真实行为
（壳同样是从 stdout 握手行读端口）。

## 下一步（M2）

- `secs-app`：Schema v4 的 JSON 读写（复用 `secs-json`，无反射）+ `--config` 装载
- 会话管理：一个端口一个 `SimSession`，与 `EquipmentSpec` 绑定
- 值引擎基座：`Curve` 与 `ValueStore` 起步
