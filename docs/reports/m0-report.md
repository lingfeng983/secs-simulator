# M0 验收报告

> **后续进展（2026-10-23 注）**：本文提到的 API 契约已落地于
> [`openapi.yaml`](../openapi.yaml)（OpenAPI 3.0.3，CI 用 `openapi-spec-validator` 校验）。

日期：M0 完成时
环境：Windows 11 / Oracle GraalVM 25.0.4+7.1 / Maven 3.6.3 / JDK release 25

## 交付内容

| 项 | 结果 |
|---|---|
| 模块拆分 | 9 个模块（parent + 8），依赖方向由 ArchUnit 强制 |
| 代码保留 | `secs-protocol` 205 文件 / 18,474 行；`secs-json` 29 文件（反射已清零） |
| 删除 | `secssimulator.*`(82)、`jsoncommunicator.*`(29)、Swing/JavaFX/CLI/宏引擎、`module-info`、旧构建脚本、docs 旧站、IDE 垃圾 |
| 单元测试 | 16 个（含 6 条架构规则 + 4 条规则自证 + 编码基线 2 + 时钟 4） |
| 架构规则覆盖 | 一次导入 **312 个类**并逐条检查，非空跑 |
| 原生镜像 | `secs-smoke.exe` **12.7 MB**，构建 **25 s** |

## 关键发现（都是会咬人的）

### 1. ArchUnit 在 JDK 25 上会「静默空跑」

最初的架构测试全部通过——但那是因为 `ClassFileImporter` 一颗类都没导进来。
ArchUnit 1.3.0 内置的 ASM 读不了 JDK 25 的 class 文件（major 69），跳过时**不报错**。
若没有写自证测试，这个坑会一直潜伏到有人真的越界才发现。

修复：ArchUnit `1.5.1` + 显式提升 `org.ow2.asm:asm` 到 `9.11`。
防复发：`RuleSanityTest` 断言夹具与主代码均非空，并故意制造一次越界证明规则会失败。

### 2. native-image 能吃下这套异步 NIO 协议栈

最大的未知数是 `AsynchronousSocketChannel`。实测结论：**可用**。

`NativeSmokeIT` 用 JVM 侧的 HSMS-SS 主动连接去打原生镜像的被动端口，
完成 select 握手并换到 S1F2 应答，说明异步通道的建立、读写、超时链路在 native 下正常。

### 3. native 进程不会因为 main 返回而退出

第一次跑端到端时，`/shutdown` 之后进程挂着不退。
修复：关闭路径显式限时（HTTP `stop(0)`、通信器关闭放在受 join 超时保护的守护线程里），
最后 `System.exit(code)`。这不是测试环境的怪癖——桌面一体化模式下"关窗不退"是真实用户问题。

### 4. GraalVM 25 的时区与编码

- `-H:+IncludeAllTimeZones` 已弃用：GraalVM 25 默认包含全部时区，构建参数已移除。
- 中文目录 + 中文文件名往返在 native 下通过（`腔体配置/模板-温度.json`），
  证明 zh_CN 构建机不会把默认编码带歪；构建参数仍显式带 `-Dfile.encoding=UTF-8`。

## 未决项（M1 起处理）

- `secs-cli` 里的 `NativeSmoke` 是风险验证程序，M2 起由真正的 `secs-runtime` 入口替代；
- `secs-app` / `secs-api-http` / `secs-dist` 目前只有包声明，等 M1–M2 填充；
- API 契约（`openapi.yaml`）尚未落地，M7 前完成。
