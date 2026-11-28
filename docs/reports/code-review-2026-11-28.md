# 全量代码评审报告

评审日期：2026-11-28
基线版本：3.5.2（tag `v3.5.2`，HEAD `98be4a3`）
评审范围：`secs-simulator-service`（8 个模块，主代码 57.2k 行 / 测试 20.4k 行）、`secs-simulator-ui`（179 文件，34.7k 行）、`secs-simulator-gui`（Tauri 2 壳）、`scripts/`、`.github/workflows/`

## 评审方法

按职责切六路并行深审：协议与核心、运行时、存储与应用、HTTP 与序列化、前端与桌面壳、脚本/CI/测试有效性。每路只读审查，产出候选问题后由主评审逐条回查源码确认，剔除两类噪声：

- **文档化的设计取舍**（如无鉴权、`0.0.0.0` 默认绑定、未知 SVID 回 `U4 0`）——单列在末尾，不算缺陷；
- **纯风格偏好**（命名、注释、格式）——不予收录。

标注「已核实」= 本次回查过源码行；「待确认」= 逻辑成立但未逐行验证。

## 总体判断

工程门禁本身是扎实的（818 项后端测试、258 项前端单测、75 项 E2E、ArchUnit 分层守门、原生镜像夜间链路），但这次评审暴露一个共同模式：**"守卫声明"与"守卫实效"之间有缝**。四处最严重的问题都不是功能写错，而是"看起来在保护、实际没保护"——保存成功后基线推进错位、Linux 上的原生测试全被 skip 却仍绿、事件心跳抛异常后静默停摆、台账裁剪只在启动跑一次。这类缺陷的共同特征是**没有测试能变红**，所以能穿过全部门禁。

因此建议的修复优先级不是按"功能重要性"，而是按"能否被现有门禁捕获"排序：先补能变红的守卫，再修具体缺陷。

## P0 必须修（4 条）

### P0-1 保存成功后基线推进到「当前草稿」而非「已提交文本」

`secs-simulator-ui/src/state/configDraftStore.ts:167-180`（已核实）

```ts
for (const section of sections) {
  baseline[section] = entry.snapshot.current[section];   // 读的是此刻快照
}
```

`markSectionsSaved` / `markAllSaved` 在响应返回时取当前快照作基线。保存请求在途期间用户继续输入的内容，会被一并认定为"已保存"：页签的未保存标记消失，`dirty` 判据（`text !== baselineText`）也认为干净，下次点保存回"本页没有改动，没有保存"。

**后果**：静默丢输入，且用户无法感知——这是本次评审唯一会直接造成用户数据丢失的缺陷。

**修法**：发起请求前捕获 payload 文本随请求带走，回写时只把基线推进到该文本；`markAllSaved` 同理，不要读 `snapshot.text`。

**为何门禁没抓到**：现有测试只覆盖"保存成功 → 基线等于当前"，没有"保存在途时继续编辑"的时序用例。

### P0-2 台账裁剪只在启动时执行一次，长跑实例写满磁盘

`secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/Bootstrap.java:231`（已核实：全仓 `trim()` 仅此一处调用）

`planExecutions.trim()` 在启动引导时调一次。但计划周期最低可到 1ms，`link_event` 则完全没有裁剪路径。一个连续运行数周的实例，`plan_execution` 与 `link_event` 会无界增长。

**后果**：磁盘写满后所有写事务失败，配置保存、台账、审计全部不可写，且错误会以"保存失败"的形态出现，与真实原因（磁盘满）相距很远。

**修法**：改为小时级定时裁剪，两表都覆盖；`link_event` 补按时间 + 条数的保留策略；裁剪失败要记一次显式告警而不是静默。

### P0-3 `pumpNow` 在 `pumpLock` 内同步写 socket，对端阻塞时全进程生命周期操作卡死

`secs-runtime/src/main/java/com/shimizukenta/secssim/runtime/session/HsmsSessionRuntime.java:722-735`（已核实）

```java
public void pumpNow() {
    synchronized (this.pumpLock) {
        this.tracePump.pump();      // → innerSend → 单帧最多等 T6（默认 5s）
        this.scheduler.pumpEvents(); // → firePlan → comm.send
        this.cycleRunner.pump();
    }
}
```

心跳每 100ms 进一次 `pumpLock`，锁内直接写 socket。对端停止读取导致 TCP 零窗口时，单次写可阻塞到 T6 超时；而 `SessionLauncher` 的 create/reload/`applySections` 都需要这把锁，并且外层还持有全局 `lifecycleLock`。

**后果**：一台设备的对端卡住 → 该设备心跳持锁数秒 → 全进程所有设备的创建、关闭、重载一起排队，表现为"整个界面点不动"。

**修法**：发送改为入队（有界队列 + 独立发送线程），pump 只做状态推进与投递；至少要把 `send` 移出 `pumpLock`。

### P0-4 Linux 上的原生集成测试全部静默跳过，夜间腿零覆盖仍为绿

`secs-simulator-service/secs-cli/pom.xml:111` + `NativeRuntimeIT.java:50` + `.github/workflows/native-nightly.yml:28-36`（已核实）

failsafe 的系统属性固定写成 `target/secs-sim.exe`，但 GraalVM 在 Linux 上的产物名是 `secs-sim`（`imageName` 为 `secs-sim`，无 `.exe`）。测试里：

```java
assumeTrue(!exe.isBlank(), "skip: -Dsecs.smoke.exe not set");
assumeTrue(Files.isRegularFile(exePath), "skip: not a file: " + exe);
```

文件不存在被 `assumeTrue` 转成 skip，而不是失败。

**后果**：夜间矩阵的 Ubuntu 腿实际上一个原生用例都没跑，却始终绿灯；`release-notes-3.5.2.md` 里"NativeRuntimeIT 3/3 无跳过"这句只在 Windows 成立，作为发布证据不成立。

**修法**：按 OS 选择可执行名（`windows` → `.exe`，其它 → 无后缀）；"文件不存在"改为 `fail` 而非 `skip`，只有"未显式指定"才允许 skip。

**为何门禁没抓到**：skip 与 pass 在 CI 汇总里同样是绿色。

## P1 应该修（18 条）

### 正确性

| 位置 | 问题 | 修法 |
|---|---|---|
| `secs-app/sml/SmlParser.java:92` | 奇数 function 默认置 W，且语法上无法表达"不带 W 的 primary" | 默认 `wbit=false`，显式写 `W` 才置位；或补 `NOW` 语法 |
| `secs-protocol/secs2/Secs2BytesParser.java:95` | 数值项只校验字节数、不校验能否整除元素宽度，`I4`(3B) 照样入队，取值时 BufferUnderflow | parse 阶段校验 `size % width == 0`，抛 `Secs2BytesParseException` 走 S9F7 |
| `secs-protocol/secs2/Secs2Number.java:187` | 解码失败时 `toJsonValue` 返回 `false`，产出 `{"f":"I4","v":false}`，破坏"v 恒为数组"契约 | 改输出 `null`，与 `jsonNumber` 的 NaN 处理一致 |
| `secs-app/sml/SmlRenderer.java:90` | U8 用 `getLong` 渲染，≥2^63 输出负数，回填会被值域判非法 | 改 `getBigInteger` |
| `secs-core/value/DataSource.java:203` | `pattern` 里 `{n}` 直接拼进 format 宽度，`{999999999}` 触发巨量补零 OOM | 宽度限幅 1..32 并兜住 `IllegalFormatException` |
| `secs-protocol/hsmsss/AbstractHsmsSsCommunicator.java:900` | `linktest(0)` 被 `geZero()` 判真后 `wait(0)` 永久挂起，`resetLinktesting()` 只置标志不 notify | 统一按 `> 0` 判断，0 视为非法 |
| `secs-core/context/ContextTable.java:110` | `Optional.of(...text(...))`，文本为 null 时 NPE | 改 `ofNullable` |

### 并发与资源

| 位置 | 问题 | 修法 |
|---|---|---|
| `AbstractHsmsSsCommunicator.java:657` | 写超时按分片各算一个 T6，大报文遇零窗口可累计 N×5s 并持续持 `synchronized(channel)`，同期 LINKTEST/S9F7 全被堵 | 整条报文一个 deadline，剩余时限逐片递减 |
| `AbstractHsmsSsPassiveCommunicator.java:141` | accept 回调里同步阻塞到连接结束，每条 TCP 常驻一个共享 I/O 线程 | 回调只入队，连接处理交自有线程池 |
| `AbstractHsmsSsCommunicator.java:801` | 接收队列按条数限容（1024×8MB 可达 GB 级），满时静默丢最旧 primary 且无日志 | 按字节计容，丢弃计数并记日志 |
| `session/EventTicker.java:53` | `scheduleAtFixedRate` 的任务抛异常即被永久取消且无日志；`pumpNow` 未整体兜底（`pumpEvents` 有 try，`tracePump`/`cycleRunner` 没有） | `pumpNow` 整体 try/catch 记录后继续 |
| `session/SessionLifecycle.java:116` + `Bootstrap.java:198` | HSMS 协议回调线程与 `lifecycleLock` 内同步写 runtime.db | 观察者改为入队，落库异步化 |

### 校验与一致性

| 位置 | 问题 | 修法 |
|---|---|---|
| `secs-app/config/SessionConfig.java:214-228` | 不校验 Expr/Curve 的 `variables()` 是否落在 `vars`/`V`/`t`，写错变量名能保存能启动，运行期恒错只留日志 | 编译期比对变量集合并收 Issue |
| `SessionConfig.java:205-212` | 不查 plans 重复 id，也不查 AfterEvent 环（A→B→A） | 编译期做 id 唯一性与环检测 |
| `secs-store/ConfigRepository.java:303-314` | `deleteDevice` 用 `(name=? OR id=?)` 可能一次改多行，设备名恰等于另一台 `dev_` id 时连带软删 | 先解析唯一 id 再按 id 删 |
| `ConfigRepository.java:163-174` | UPDATE 不带 `WHERE revision=?`，CAS 完全依赖 Db 单写者锁 | UPDATE 带 revision 条件，0 行即 CONFLICT |
| `secs-store/Schema.java:288-291` | 备份→DROP→CREATE→写版本四步各自独立提交，`--allow-schema-reset` 升级中崩溃会留空库，下次被当新库建空表 | 用 `BEGIN IMMEDIATE` 包成一个事务，失败整体回滚 |

### HTTP 语义与前端

| 位置 | 问题 | 修法 |
|---|---|---|
| `secs-api-http/SessionHandler.java:44` 等 | `HttpServer` 按前缀匹配，`/api/v1/sessionsabc` 返回 200 集合，拼错端点永远拿不到 404 | 集合入口先断言 `path.equals(常量)` |
| `HttpSupport.java:300` | 超限正文一律回 400，客户端无法区分"请求坏了"与"太大了" | 改 413 |
| `HttpSupport.java:213-233` | 只对 `Content-Length` 预检，声明很小、逐字节慢发可阻塞到 120s deadline | 读循环内加单请求总耗时与速率双限 |
| `HttpApiServer.java:289-296` | 每任务一线程、无并发连接上限 | 加并发上限与读空闲超时 |
| `ui/api/hostsV2.ts:98-177` | 删除主机/断开/删模板只返回 `response.ok`，409/500 被当成功 | 非 2xx 抛带 `error` 的 ApiError |
| `ui/state/useHostsV2.ts:51-71` | `refresh` 无请求序号，2s 轮询与动作后刷新交叠，旧列表覆盖新相位 | 加请求序号/代际守卫 |
| `ui/state/deviceEndpoint.ts:98-135` | 换 `configName` 不清旧 endpoint，显示别的设备的 host/port | 变更时先置 null |

## P2 可改进（摘要）

- **并发细节**：`TracePump.s6f1Wbit` 非 volatile；`EventScheduler.pendingFires` 无容量上限；`BurstRunner` 重载不 cancel 旧压测；`HostSimRegistry.attach` 失败不回收新建对象；`Db` 只 catch `SQLException/RuntimeException`，`Error` 时不 rollback。
- **持久化**：`config.db` 与 `runtime.db` 共用 `synchronous=NORMAL`，掉电可丢已返回 200 的保存（建议 config.db 用 FULL）；`AuditLog` 按字符而非 UTF-8 字节计阈值；`RuntimeStateRepository` 的 `NOT IN` 占位符超 SQLite 变量上限。
- **HTTP/JSON**：`optionalInt()` 用 `intValue()` 截断（`4294967301` 静默变 5）；`ObjectJsonHub` 重复键 first-wins 但仍原样输出；静态资源整文件读入堆；SSE 无 `id:`/`Last-Event-ID`；`Deprecation` 头不是 RFC 9745 形态。
- **前端**：`api/base.ts` 空 body 返回 `undefined as T` 导致 TypeError；`MessageMonitorDrawer` 用 `at-index` 作 key 致展开状态错位；`useInputDraftCache` 靠序号定位控件，列表重排后回填错字段；`DataTable` 行下钻无键盘入口；暂停期间丢弃报文无提示。
- **Tauri**：`switch_backend` 先停旧后端再提交新状态，中途失败留下"已停但连接仍指旧 URL"；退出用 `kill()` 无优雅关闭；`connection.lock().unwrap()` 锁中毒 panic。

## 测试有效性专项

以下测试**不会因对应缺陷而失败**，是本次评审最值得优先处理的一类：

| 位置 | 问题 |
|---|---|
| `NativeRuntimeIT.java:50` | Linux 上 3/3 被 `assumeTrue` 跳过（见 P0-4） |
| `secs-protocol/.../ActiveCircuitWaitPatternTest.java:33` | 只验证 JDK `wait/notify` 语义，从未调用生产代码 `activeCircuit()`，回滚修复也照样通过 |
| `secs-core/.../CurveGoldenTest.java:35` | `fingerprint()` 用 `%08x` 恒为 8 字符，`length >= 8` 永远真 |
| `secs-runtime/.../EncodingBaselineTest.java:27,39` | JDK 25 默认编码恒 UTF-8（JEP 400），测的是 JDK 不是项目；`writeString` 后断言 `Files.exists` 恒真 |
| `ui/src/features/editors/templateConfig.test.ts:112,119` | `collectEcids`/`collectSvids` 只在 `if (parsed.ok)` 内断言，解析失败时零断言通过 |
| `ui/scripts/e2e-real/app.spec.ts:339` | `if (await palette.isVisible())` 无 else，Ctrl+K 失效也通过 |
| `secs-api-http/.../SseStallTest.java:77` | 20s 轮询等 5s 看门狗，慢 CI 偶发失败 |

另有两处门禁空转风险：
- `ui/scripts/check-ui-words.ps1:250`：`src` 缺失时 `$files` 为空，仍打印 `[OK] 0 个文件` 并 exit 0。
- `.github/workflows/ci.yml:41`：主 CI 只跑 `mvn test`，从不执行 `package.ps1`（版本四文件对齐、原生冒烟、NSIS 打包都不在阻断链路）；E2E 全走 mock，真实前后端契约只在 `E2E_REAL_URL` 存在时跑。

## 安全面（设计取舍的延伸，建议重新评估）

`AccessControl` 的类文档明确写着"内部工具，不做鉴权"，`RuntimeOptions:131` 默认 `bind=0.0.0.0`，`HttpApiServer:615` 恒定返回 `Access-Control-Allow-Origin: *` 且预检一律 204、不校验 Origin。

单看每一条都是已声明的定位。但三者叠加后的实际攻击面不只是"同网段可达"：**用户在浏览器里打开任意恶意网页，该页面即可向 `http://localhost:<port>` 发简单请求**——无凭据可校验、预检放行、无 Origin 校验，`/api/v1/shutdown`、配置写入、SML 下发全部可达。

建议至少做一条低成本收口：校验 `Origin`/`Sec-Fetch-Site`（非 `same-origin`/`none` 直接拒），或把 CORS 从 `*` 收成具体来源。这不改变"内部工具不设口令"的定位，但能挡掉浏览器侧的跨站请求。

## 建议的修复顺序

**第一批（3.5.3，先补能变红的守卫）**
1. P0-4 原生门禁按平台取可执行名 + 不存在即失败 —— 让夜间腿恢复实效
2. 测试有效性专项里的 6 条恒真断言 —— 让这类缺陷下次能被捕获
3. `check-ui-words.ps1` 零文件即失败、主 CI 增加打包脚本的版本一致性检查

**第二批（3.5.3，用户可感知的正确性）**

4. P0-1 草稿基线（唯一会丢用户输入的缺陷，优先级最高）
5. P0-3 发送移出 `pumpLock`
6. P1 表中的 SML W 位、Secs2 宽度校验、U8 渲染、DataSource 宽度限幅

**第三批（3.5.4，长跑与一致性）**

7. P0-2 台账定时裁剪 + `link_event` 保留策略
8. Schema 重建事务化、`UPDATE ... WHERE revision=?`、`deleteDevice` 语义
9. Expr/Curve 变量校验、plans 重复 id 与环检测
10. HTTP 精确路径匹配、413、慢速正文限制

**第四批（3.6.0，随能力补齐一起做）**

11. 前端请求序号/代际守卫、删除路径的错误传播
12. Tauri 切换与退出的优雅关闭
13. 安全面收口（Origin 校验）

## 明确不算缺陷（已声明的设计取舍）

- 不做鉴权（`AccessControl` 文档明示，保留 `Principal` 为将来复用）
- 默认 `bind=0.0.0.0`（帮助文本明示，需收窄时显式 `--bind`）
- 未知 SVID 恒回 `U4 0`、ALTX 仅 US-ASCII、端口试绑 TOCTOU、`synchronous=NORMAL`
- `SessionManager` 为 `final class`（已用包内测试工厂绕开，未改生产可见性）
- 投影表 / ConfigMigrator 已按 ADR 0017 后记移除

## 修复状态（2026-11-28 当日落地）

评审提出的 P0/P1 与测试有效性问题已全部修复，另修了多数低风险 P2。按批次记录：

**第一批 · 守卫实效**
- P0-4：`secs-cli/pom.xml` 按 OS 注入 `secs.smoke.exe`（Windows 才加 `.exe`），新增 `native-windows-suffix` profile；`NativeRuntimeIT` 的 `requireExe()` 改为「缺产物即 fail」，`demo-etch.json` / 前端 `dist` 缺失同样 fail，不再有 skip 通道。
- 恒真断言全部重写：`CurveGoldenTest`（形状 + 非零 + 换种子必变）、`EncodingBaselineTest`（改查 `file.encoding` 与目录枚举，删掉恒真的 `Files.exists`）、`templateConfig.test.ts`（先断言 `parsed.ok`）、`e2e-real/app.spec.ts`（Ctrl+K 必须打开面板，无 else 放行）。
- 删除只测 JDK 语义的 `ActiveCircuitWaitPatternTest`，回归覆盖改为 `HsmsSsIntegrationTest.longLivedSessionSurvivesCircuitWaitTick` 并在测试里写明。
- `check-ui-words.ps1`：扫到 0 个文件即失败。
- CI 新增「版本号一致性」检查：以内核 pom 为准核对 9 个 pom、`BuildInfo`、两个 `package.json`、`tauri.conf.json`、`Cargo.toml`。

**第二批 · 正确性**
- P0-1：`configDraftStore.markSectionsSaved/markAllSaved` 改为接收「提交时刻的快照」，请求在途的输入不再被误标已保存；补了对应的时序用例。
- P0-3：`PlanExecutor.send` 与 `EventReporter.emit` 的非 W 位分支改为入队（与 W 位共用有界队列），`TracePump` 的 S6F1 同样处理——`pumpLock` 内只做投递。
- SML 新增 `NW`（no wait）语法，可表达「不带 W 的 primary」；`SmlRenderer` 的 U8 改用 `getBigInteger`；`Secs2BytesParser` 校验长度整除元素宽度；`DataSource` 的 `{n}` 宽度限幅 1..32；`linktest` 的 `resetLinktesting()` 补 `notifyAll`；`ContextTable.textAt` 改 `ofNullable`；`Secs2Number` 解码失败输出 `null` 而非 `false`。

**第三批 · 长跑与一致性**
- P0-2：`Bootstrap` 增加小时级 daemon 任务，同时裁剪 `plan_execution` 与新增的 `RuntimeStateRepository.trimLinkEvents()`（每实体 2000 条 / 7 天）。
- `Schema.apply` 的 DROP+CREATE+写版本收进一个 `BEGIN IMMEDIATE` 事务；`readVersion` 对「meta 存在但缺 key」改为报错而非当新库；`backup` 的 WAL checkpoint 失败即中止重建。
- `ConfigRepository.saveDocument` 的 UPDATE 带上 `WHERE revision=?`（0 行即冲突）；`deleteDevice` 改为先按读路径解析唯一 id 再删。
- `SessionConfig.compile()` 新增三类校验：Expr/Curve 变量名可解析、计划 id 唯一、AfterEvent 链无环。

**第四批 · HTTP 与前端**
- 集合端点加 `requireExactPath`（8 处），拼错路径不再拿到 200 集合；超限正文改 413；`readBody` 分块读 + 15s 总耗时上限；全局并发门 512（超限 503）；静态资源与 health 接受 HEAD，405 带 `Allow`。
- 前端：`useHostsV2.refresh` 与 `usePlanExecutions.refresh` 加请求序号，SSE 相位更新作废在途快照；`hostsV2` 的删除/断开/删模板非 2xx 抛错；`deviceEndpoint` 换配置先清空；`refreshConfigs` 不再空 catch（新增 `configError` 并在顶栏显示可重试的提示）；`readJson` 遇非 204 的空正文抛 `empty-body`。
- Tauri：`switch_backend` 改为「新连接就绪后再停旧后端」（本地先拉起再停、远程先落配置再停）；`stop_backend` 先 POST `/api/v1/shutdown` 等最多 3 秒再强杀。

**第五批 · P2 与安全面**
- 浏览器跨站写拦截：非 GET/HEAD/OPTIONS 且 `Origin` 不可信（非同一主机、非 Tauri 来源）时回 403 `cross-origin-write-blocked`；无 `Origin` 的 curl/脚本不受影响。测试从「任意来源写都执行」改为「跨站写被拒」。
- `TracePump.s6f1Wbit` 加 `volatile`；`EventScheduler.pendingFires` 加 10000 条上限（淘汰最早到期）并把环检测的异常对象传进日志；`Db` 写事务在 `Error` 时也回滚；`AuditLog` 按 UTF-8 字节计轮转阈值；`EventBus` 捕 `Throwable`；`NumberJsonHub.optionalInt/optionalLong` 越界返回 empty（浮点仍按既有约定截断）；`ConfigTemplateHandler` 缺 id 回 `missing-id` 而不是 `bad-json`。

**同步更新的测试**：`SchemaVersionTest`（缺 key 改为断言抛错）、`PlanExecutorTest`（非 W 位改为入队并等待）、`SessionControlStateTest` / `SessionEventControlTest`（异步计数改为等收包后断言）、`ApiContractHardeningTest`（400 → 413）、`HttpApiServerTest`（跨站写改断言 403）。

**未做的部分**（明确留待后续，非本次范围）：SSE 的 `id:`/`Last-Event-ID` 断线回放、静态资源流式输出、Tauri 首屏骨架、`SessionManager` 端口化重构。这几项要么是独立特性，要么需要改动面较大，不塞进这次修复。

