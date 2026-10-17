# ADR 0001 · 地基决策

- 状态：已接受
- 日期：M0
- 影响范围：整个仓库

## 背景

原仓库是 upstream `kenta-shimizu/secs-simulator` 的克隆，含 Swing 桌面版、CLI、
JSON-over-TCP 远程控制、宏引擎与半成品的 JavaFX 移植。我们要把它改造成
"多端口 / 多单元 / 模板化数据生成 / 可编排 S6F11 / 可远程访问"的新产品。

## 决策

1. **语言：全 Java 25**。不引入第二运行时。核心与协议同进程，数据与报文之间不做序列化。
2. **协议栈冻结**：只保留 `com.shimizukenta.secs*`（18,474 行），作为独立模块，
   只依赖 JDK，禁止反向依赖。它是本项目最贵的资产，也是最不需要改的部分。
3. **一套逻辑、两种形态**：`INTEGRATED`（桌面一体化，API 只绑回环）与
   `SERVER`（Linux 服务端，对外绑定 + 鉴权）共用同一个内核、同一套 API、同一份前端。
   一体化不是"绕过 API"，而是"API 只绑回环"，避免出现两套业务逻辑。
4. **删除无用代码**：`secssimulator.*`、`jsoncommunicator.*`、Swing、JavaFX、CLI、
   宏引擎、`module-info`、旧构建脚本全部删除，不留干扰项。
5. **JSON 去反射**：删除 jsonhub 的 POJO 反射解析器（`JsonHubToPojoParser` /
   `JsonHubFromPojoParser`）及其 API，使 `secs-json` 成为零反射模块，
   为 native-image 免除元数据负担。
6. **非模块化**：不使用 `module-info`。JPMS 与 native-image / 简单打包并不划算。
7. **禁用预览特性**：不使用 `--enable-preview`，避免 class 文件与 JDK 版本绑定。

## 后果

- 正面：内核可纯单测；协议层可长期不动；两种形态行为一致；native 化成本低。
- 负面：`secs-protocol` 保留 upstream 的代码风格（tab 缩进、中文缺失的 javadoc），
  与新代码风格不一致；为隔离这一点，它在构建上禁止 `-Xlint` 严格化。
- 后续：M1 起在 `secs-core` 中实现领域模型与 ID 规划器。
