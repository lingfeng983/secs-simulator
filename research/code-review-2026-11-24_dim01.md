# 架构与模块依赖约束专项评审（dim01）

- 评审日期：2026-11-24
- 范围：`secs-simulator-service/` 父 POM + 8 模块、ArchitectureTest、源码 import 抽查、超长类、ADR-0016 / ADR-0020 一致性
- 方法：只读静态分析（read_file / grep / 行数统计）；未运行构建或测试
- 交付说明：本文件由子评审员完成调查、父 Agent 代为落盘（子任务工具预算耗尽），内容原样保留

## 现状概述

父 POM（`secs-simulator-service/pom.xml`）管理 8 个模块，JDK 25（`maven.compiler.release=25`，第 37 行），enforcer 在 validate 阶段拦截 JDK 版本（第 96-102 行），compiler pluginManagement 统一 `-Xlint:all,-missing-explicit-ctor` + `-Werror`（第 126-129 行）。全仓（secs-simulator-service 下）grep `enable-preview` **零匹配**，铁律 2 成立。

实际依赖图（据各模块 pom.xml 逐一读取）：

| 模块 | 编译期依赖（除 junit test） | 与 README 声称的差异 |
|---|---|---|
| secs-json | 无 | 一致 |
| secs-protocol | 无（冻结，-Werror 豁免有条文注释） | 一致 |
| secs-core | 无 | 一致 |
| secs-store | secs-json + sqlite-jdbc | **README 声称含 secs-core，实际 POM 没有**；POM 注释自述"只认识 secs-json + JDBC" |
| secs-app | core + json + protocol | 一致 |
| secs-api-http | app + json | 一致 |
| secs-runtime | store + app + api-http + protocol + json（+ archunit test） | — |
| secs-cli | runtime + json + **sqlite-jdbc（M0 尖峰遗留）** | 驱动依赖未收敛到 store |

ArchitectureTest 位于 secs-runtime 测试目录，含 6 条规则；import 抽查未发现任何越界依赖。超长类 3 个（均在 secs-runtime），其中 2 个显著超千行。ADR-0020 的实现文件全部存在，但少量注释/文案仍停留在 v6 时代。

## 逐项核查

### 1. POM 依赖与插件配置

**1.1 无 --enable-preview，release=25 统一**
`secs-simulator-service/pom.xml:37`：
```xml
<maven.compiler.release>25</maven.compiler.release>
```
`pom.xml:96-97`：
```xml
<requireJavaVersion>
  <version>[25,)</version>
```
grep `enable-preview|--enable-preview` 于 secs-simulator-service 全目录：`No matches found`。surefire argLine 仅编码设置（`pom.xml:137`）：
```xml
<argLine>-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8</argLine>
```

**1.2 零告警门禁与冻结模块豁免（有条文依据）**
`pom.xml:126-129`：
```xml
<compilerArgs>
  <arg>-Xlint:all,-missing-explicit-ctor</arg>
  <arg>-Werror</arg>
</compilerArgs>
```
`secs-protocol/pom.xml:29-35`：
```xml
<!--
  冻结模块（源自 secs4java8，229 个类保持原样）：只观察告警，不用 -Werror 阻断。
  需要改本模块时，先按父 POM 的零告警门禁自查后再改这里。
-->
<compilerArgs>
  <arg>-Xlint:all,-missing-explicit-ctor</arg>
</compilerArgs>
```
铁律 1 的"例外须条文依据"在 POM 层面满足：豁免仅限告警门禁，依赖仍只有 junit（test，经父 dm 第 55-57 行注入 test scope）。

**1.3 secs-store 依赖与 README 声称不符（实际更严格）**
`secs-store/pom.xml:19-32`：
```xml
<!--
  依赖纪律（3.0 设计 §2.1）：本模块只认识 secs-json + JDBC。
  不 import secs-app / secs-api-http / secs-runtime 的任何类型——
  配置解析（编译校验、投影）由上层注入，见 ConfigRepository.DocumentProjector。
-->
<dependencies>
  <dependency>
    <groupId>com.shimizukenta</groupId>
    <artifactId>secs-json</artifactId>
  </dependency>
  <dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
  </dependency>
```
源码侧印证：store 全部 main 源码 grep `^import com\.shimizukenta\.` 零匹配；jsonhub 仅以全限定名出现在 `HostRepository.java:308`：
```java
com.shimizukenta.jsonhub.JsonHubBuilder b = com.shimizukenta.jsonhub.JsonHub.getBuilder();
```

**1.4 ASM 版本钉住仅靠 dependencyManagement**
`pom.xml:62-66`：
```xml
<!-- ArchUnit 用 ASM 解析 class 文件；JDK 25 的 major 69 需要新版本 ASM，否则会静默跳过所有类 -->
<dependency>
  <groupId>org.ow2.asm</groupId><artifactId>asm</artifactId>
  <version>${asm.version}</version>
</dependency>
```
`asm.version=9.11`（第 40 行）。注意：dependencyManagement 只约束传递依赖版本，是否真的生效（ArchUnit 是否以非 shaded 方式依赖 org.ow2.asm）**未核实**（未运行 `mvn dependency:tree`）。与 ArchitectureTest 的 `allowEmptyShould(true)` 组合存在"静默全绿"风险（见问题清单）。

**1.5 secs-cli 遗留 sqlite-jdbc 直依赖（有注释说明）**
`secs-cli/pom.xml:33-38`：
```xml
<!-- M0 尖峰（3.0 设计 §2.3.1）：验证 sqlite-jdbc 的 JNI 本地库在 native-image 里可用；
     M1 存储地基落地后依赖迁往 secs-store，这里保留给 db-spike 冒烟路径 -->
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
</dependency>
```

**1.6 其他**：版本集中于父 dm（junit 5.11.4 / archunit 1.5.1 / sqlite-jdbc 3.49.1.0，第 38-71 行），子 POM 无版本号硬编码；native profile 显式 `-Pnative` 才启用（`secs-cli/pom.xml:60-61`），failsafe 只跑 NativeRuntimeIT。未发现插件配置违规。

### 2. ArchitectureTest 强制力度

文件：`secs-simulator-service/secs-runtime/src/test/java/com/shimizukenta/secssim/arch/ArchitectureTest.java`（94 行，已通读）。

**它检查什么**（6 条规则）：
- core ↛ app/api/runtime/cli（第 34-40 行）
- core ↛ jsonhub/secs（第 43-50 行）
- 仅 `api.http..` 可用 `com.sun.net.httpserver..`（第 53-59 行）
- protocol 只依赖 `java.. / javax.. / com.shimizukenta.secs..`（第 62-69 行）
- json 同上（第 72-79 行）
- core/app/api/runtime/cli ↛ `java.lang.reflect..`（第 82-93 行）

**它漏掉什么**：
1. **无 app/api/store/runtime 之间的方向规则**：app ↛ store/api/runtime、api ↛ runtime/store、store ↛ 上层，均无 ArchUnit 规则（store 的纪律只写在 POM 注释里）。当前代码合规是靠自觉而非门禁。
2. **core "仅 JDK" 未被强制**：规则只枚举了外层包和 jsonhub/secs，core 引入 `java.sql`、`java.net` 等 I/O 不会失败（"无 I/O"目前仅靠 grep 验证成立）。
3. **反射规则口径窄**：只查 `java.lang.reflect..` 依赖，不覆盖 `Class.forName`、`java.lang.invoke`（MethodHandles）；且 **store 不在名单内**——`secs-store/.../Db.java:136` 实际存在：
   ```java
   Class.forName("org.sqlite.JDBC");
   ```
   （驱动加载属合理用途，但与"新代码不用反射"的口径存在缺口。）
4. **空集静默通过**：所有规则均 `.allowEmptyShould(true)`（如第 38 行），且导入器为：
   ```java
   private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
           .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
           .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
           .importPackages("com.shimizukenta");
   ```
   （第 28-31 行）。若 ASM 版本不匹配导致 0 类导入（父 POM 第 62 行注释自述此风险），或依赖以 jar 形式解析（IDE 单独跑 runtime 测试时其他模块可能是 jar），全部规则空转通过。没有"导入类数 > N"的哨兵断言。
5. **cli 类不在检查范围**：runtime 的 POM 不依赖 secs-cli，classpath 上没有 cli 类；OUTER_LAYERS 里的 `"com.shimizukenta.secssim.cli.."`（第 25 行）实际是空匹配。
6. 无同模块内分层规则（如 `runtime/hostsim` ↛ runtime 根、`session` 内部协作方向）、无环检测（slices）。ADR-0016 第 70 行声称"包边界能被 ArchUnit 与编译错误守住（hostsim → session 单向）"——ArchUnit 中**并无该规则**，实际由 package-private + 编译错误守住（见第 3 项验证）。### 3. 源码 import 抽查（实际依赖方向）

- **secs-core 无 I/O 无 JSON**：main 全量 grep `^import (java\.io|java\.nio|java\.net|java\.sql|javax\.|com\.shimizukenta\.(jsonhub|secs)\.)` → 零匹配；再 grep `^import (com|org|sun|jdk)\.|System\.out|System\.err|getResource|InputStream|OutputStream|Files\.|FileReader|FileWriter` → 命中全部为 core 内部包 import。时间源仅 `SystemEngineClock.java:16,21`（`System.nanoTime()`），且存在 `EngineClock` 接口与 `ManualEngineClock`（文件清单），时钟是注入的。**成立**。
- **secs-protocol 仅 JDK**：main 全量 grep `^import (org\.|net\.|io\.|sun\.|com\.shimizukenta\.(jsonhub|secssim))` → 零匹配；`^import (com|org|net|io)\.` 采样 200 条全部为 `com.shimizukenta.secs..` 内部。**成立**（残余风险见置信度）。
- **secs-app** ↛ store/api/runtime/cli：grep 零匹配。**成立**。
- **secs-api-http** ↛ store/runtime/cli，且不 import protocol（`com.shimizukenta.secs.`）：grep 零匹配。**成立**。
- **com.sun.net.httpserver 仅在 api-http**：全 8 模块 main grep，命中全部位于 `secs-api-http/.../api/http/`（HttpApiServer.java:29-33 等 18 处）。**成立**。
- **hostsim ↛ runtime 根**（ADR-0016 约束）：`runtime/hostsim` grep `^import com\.shimizukenta\.secssim\.runtime\.[A-Z]` → 零匹配；且 runtime 根协作者均 package-private（`RuntimeContext.java:42` `final class RuntimeContext`、`RuntimeApi.java:40` `final class RuntimeApi`），子包无法访问，编译器兜底。**成立**。
- **反射**：全 main grep `Class\.forName|java\.lang\.reflect|java\.lang\.invoke` 仅命中 `store/Db.java:136`（见上）。

### 4. 超长类（>1000 行）

行数来自逐文件行数统计，并经 read_file 返回的总行数交叉验证（4 个文件一致）。全 8 模块 main 源码扫描结果：

| 文件 | 行数 | 证据 |
|---|---|---|
| `secs-runtime/.../runtime/session/HsmsSessionRuntime.java` | **1331** | read_file 返回 "of 1331" |
| `secs-runtime/.../runtime/RuntimeApi.java` | **1238** | read_file 返回 "of 1238" |
| `secs-runtime/.../runtime/hostsim/HostSimulator.java` | **1022** | read_file 返回 "of 1022" |

接近阈值（800-999，供预警）：`RuntimeContext.java` 803（read_file 确认 "of 803"）、`ConfigStore.java` 802、`HttpApiServer.java` 814、`HostCommandHandler.java` 795。冻结模块最大 `AbstractHsmsSsCommunicator.java` 939（<1000，且冻结豁免）。secs-core/app/store/json/cli 无超千行类。

### 5. ADR 一致性

**ADR-0016（docs/adr/0016-layout-and-layer-consolidation.md，已通读）**：
- "RuntimeContext 拆成门面 + 三个协作者"（第 41-44 行）→ 已落地但**已演化**：`RuntimeContext.java:27-38` javadoc 列出 RuntimeApi、HostSimRegistry、BurstRunner、DeviceSessions、DeviceV2Delegates、HostV2Delegates 等协作者，正文却仍写"拆成四个"（第 28 行）而列表有 7 项；门面本身 803 行、RuntimeApi 1238 行——拆分把臃肿转移给了新类，千行铁律被突破。
- "协作者都是 package-private"（第 44 行）→ `RuntimeContext.java:42`、`RuntimeApi.java:40` 证实。
- "HostCommandHandler 留在 session"（第 39-40 行）→ `runtime/session/HostCommandHandler.java` 存在（795 行）。
- "Main 迁入 secs-cli"（第 34 行）→ `secs-cli/.../CliMain.java` 存在（42 行），启动流程在 runtime/Bootstrap.java（748 行，`RuntimeContext.java:40` 注释佐证）。
- "runtime/hostsim 不得反向依赖 runtime 根"（第 70 行）→ import 层面成立（见第 3 项），但声称的 ArchUnit 守护不存在。
- 前端部分（api.ts barrel、panels.css @import 等）：**未核实**（本任务未读前端）。

**ADR-0020（docs/adr/0020-single-source-value-model.md，已通读）**：
- 实现清单（第 86-88 行）中的文件全部存在：core/cycle/CycleClock、core/value/DataSource|SourceEvaluator|DataSourceValueSource、core/context/ContextTable、app/config/DataSourceJson、CycleConfig、SessionConfig、runtime/session/S6F11Assembler、EcidValues、PinnedValueSource（文件行数清单逐一确认）。
- "删除 EventEffect / EventEffectStore / EffectValueSource 全链路"（第 47 行）→ 全仓 grep `EventEffect|EffectValueSource|template_instance`：main 源码仅剩 `PinnedValueSource.java:12` 的历史注释（"v6 的 {@code EffectValueSource} 有两层……v7 取消了"）。**已落地**。
- "不再需要 DUPLICATE_SVID 这类绑定冲突检查"（第 39 行）→ 实际 `EquipmentCompiler.java:105、196` 仍以 `Issue.error("DUPLICATE_SVID", ...)` 报 **ID 唯一性**错误，语义已从"绑定冲突"转为"编号唯一"，与 ADR 精神兼容；但第 88-89 行 javadoc 仍写：
  ```java
  * 一个生产者"的是 <b>ID 唯一性</b>——重复 ID 会在上方以 {@code DUPLICATE_SVID} 显式报出；
  * 此外运行期模板图构建也会以单写者规则（{@code DUPLICATE_SVID}）拒绝重复绑定，作为兜底。
  ```
  "运行期模板图构建"在 v7 已不存在——**注释漂移**。
- 曲线时间纯函数（ADR-0003 保留部分）：`Rng.java:46-49`（`uniform01(long bucket)` 纯哈希）、`RandomWalkCurve.java:52-57`（`at(long tMs, Rng rng)` 仅由 t 与 seed 决定）证实采样类的纯函数性；Rng javadoc 明言"与调用次数无关……可以乱序求值、可以跳采"。

### 6. 整体架构评估

分层清晰度**高**：叶子库（json/protocol）→ core（纯领域）→ app（用例）→ api-http（适配器）→ runtime（装配）→ cli（入口），store 独立旁路仅依赖 json+JDBC，全部经源码验证无越界。耦合点集中在 runtime（唯一同时看见 store/app/api/protocol 的模块），符合装配层定位。可测试性好：core 无 I/O + 注入时钟（EngineClock/ManualEngineClock）+ 确定性 Rng，纯函数可直接断言；api-http 通过 `ApiContext` 接口与 runtime 解耦（RuntimeContext implements ApiContext，`RuntimeContext.java:42`）。package-private 协作设计真实有效（编译器强制子包隔离）。主要短板：门禁覆盖不全（第 2 项）+ runtime 内三个超千行类。

## 问题清单

| # | 严重度 | 描述 | 证据 | 建议 |
|---|---|---|---|---|
| 1 | **P1** | `HsmsSessionRuntime` 1331 行，违反"超千行拆分"约束；集会话协议应答、限流、热重载、值存储装配于一身 | `secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/HsmsSessionRuntime.java`（read_file 总行数 1331；第 51-52 行注释自述"设备级限流与单飞都在这里"） | 按已有协作者模式（session 包内已有 40+ 小类）继续抽取：应答分发、限流、模型热替换各自成 package-private 类 |
| 2 | **P1** | `RuntimeApi` 1238 行：ADR-0016 拆分把 RuntimeContext 的臃肿转移到了新"接口落地"类，千行约束再次被突破 | `runtime/RuntimeApi.java`（总行数 1238；第 36 行注释"也是原来最臃肿的一块"） | 按域拆分（会话/配置/报文/审计/模板各一个落地类），RuntimeApi 退化为路由 |
| 3 | **P2** | ArchitectureTest 覆盖缺口：app/api/store/runtime 相互方向、core 仅 JDK、hostsim→session 均无规则；ADR-0016 第 63-64 行声称的 ArchUnit 守护部分不存在 | `ArchitectureTest.java:21-93`（全部 6 条规则已通读）；`docs/adr/0016-layout-and-layer-consolidation.md:63-64,70` | 补规则：app↛store/api/runtime/cli；api↛runtime/store；store↛secssim 上层；core onlyDependOn JDK+自身；hostsim↛runtime 根 |
| 4 | **P2** | 全部规则 `allowEmptyShould(true)` + `DO_NOT_INCLUDE_JARS` + ASM 版本仅靠 dm 钉住：导入 0 类或依赖以 jar 解析时整套门禁静默全绿；cli 类根本不在 classpath | `ArchitectureTest.java:28-31,38`；父 `pom.xml:62-66`；`secs-runtime/pom.xml:19-42`（无 cli 依赖） | 加哨兵断言（导入类数下限、各包非空）；对关键包去掉 allowEmptyShould；用 dependency:tree 确认 ASM 9.11 实际生效 |
| 5 | **P2** | `HostSimulator` 1022 行，刚过千行线；hostsim 包内已有 HostCollector/HostCollectors 可继续分担 | `runtime/hostsim/HostSimulator.java`（总行数 1022） | 抽出连接生命周期与采集调度，正文只留 S2 命令编排 |
| 6 | **P3** | 反射禁令口径窄且不含 store：`Class.forName` 在 store 实际使用，规则查不到 | `store/Db.java:136` `Class.forName("org.sqlite.JDBC");`；`ArchitectureTest.java:82-93` | 明确条文豁免 JDBC 驱动加载，或规则改为覆盖 `Class.forName`/`java.lang.invoke` 并把 store 纳入 |
| 7 | **P3** | 注释/文案漂移：EquipmentCompiler javadoc 引用已删除的"运行期模板图构建"；RuntimeContext javadoc"拆成四个"实列 7 项；HostSimulator javadoc 仍写"schema v6"（v7 已强制）；api-http POM 描述写"WebSocket"而实现是 SSE | `core/compile/EquipmentCompiler.java:88-89`；`runtime/RuntimeContext.java:28-38`；`runtime/hostsim/HostSimulator.java:39,44`；`secs-api-http/pom.xml:17` | 一次性清理 v6 遗留注释，与 ADR-0020 对齐 |
| 8 | **P3** | secs-cli 直依赖 sqlite-jdbc（M0 尖峰遗留），驱动依赖未收敛到 store，注释自述 M1 后应迁移 | `secs-cli/pom.xml:33-38` | 将 db-spike 冒烟改走 store 或移入 store 测试，删除 cli 直依赖 |
| 9 | **P3** | README 依赖表与实际的偏差需修正文档口径：store 实际不依赖 secs-core（POM 与代码一致地"只认识 json+JDBC"），这是更严格而非违规，但声称的依赖表应更新 | `secs-store/pom.xml:19-32`；store main 无任何 `com.shimizukenta` import（grep 零匹配） | 更新架构描述文档中的依赖表 |

## 亮点

1. **依赖纪律真实落地**：8 模块 import 抽查零越界；`com.sun.net.httpserver` 严格圈禁在 api-http（全仓 grep 证实）；protocol/json 两个叶子库确为仅 JDK。
2. **确定性噪声设计优秀**：`Rng.java` 用 splitmix64+FNV-1a 把 (seed, 时间网格) 映射为可复现噪声，"与调用次数无关"（第 10-11 行注释），支持乱序求值/跳采/加速回放——曲线纯函数铁律有实打实的实现支撑。
3. **时钟注入**：core 唯一时间源 `SystemEngineClock` 藏在 `EngineClock` 接口后，配 `ManualEngineClock`，领域逻辑可确定性测试。
4. **构建门禁有诚意**：enforcer 提前给出可读的 JDK 版本错误（父 POM 第 77-84 行注释解释了为什么）；零告警 -Werror 统一收口到父 POM；冻结模块豁免有明确条文与自查要求。
5. **package-private 协作 + 接口反转**：RuntimeContext/RuntimeApi 均包私有，api-http 只认 `ApiContext` 接口，装配层可整体替换；hostsim 子包隔离由编译器兜底。
6. **ADR 与代码高度对应**：ADR-0020 列出的实现文件全部真实存在，EventEffect 全链路确已删除，仅剩注释残迹。
7. **对工具链陷阱有认知**：父 POM 第 62 行明确记录 JDK25 major 69 与 ASM 的静默跳过问题（尽管防护还差一个哨兵断言）。

## 置信度说明

- **高置信**（全量或定向 grep + 逐文件读取）：POM 全部 9 个文件逐行读取；ArchitectureTest 全文通读；无 --enable-preview；core 无 I/O/JSON、protocol 无第三方 import、app/api/store/hostsim 方向合规、httpserver 圈禁、反射唯一命中点；3 个超千行类（行数经 read_file 总数交叉验证）；ADR-0016/0020 全文及对应实现文件存在性。
- **中置信**：protocol "仅 JDK" 的排除性 grep 覆盖了 org/net/io/sun/jsonhub/secssim 前缀，未穷举所有可能的第三方 groupId 前缀（如 `dev.`、`ch.`）；曲线纯函数仅采样 Rng 与 RandomWalkCurve 两个类，其余 curve/cycle 类未逐行审读。
- **未核实**：ASM 9.11 是否实际被 ArchUnit 解析到（未运行 dependency:tree）；`mvn verify` 是否全绿（未运行构建）；ADR-0016 前端部分（api.ts barrel、panels.css、hook 拆分）；`RuntimeContext` 是否真的"只留健康/close/委托"（仅读了前 70 行 + 行数，803 行的正文未通读）；EquipmentCompiler 第 80-115 行上下文（读取时预算耗尽，DUPLICATE_SVID 结论仅基于 grep 逐字命中行）；store 投影注入机制（ConfigRepository.DocumentProjector）细节。