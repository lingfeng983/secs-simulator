# HTTP API 层专项评审（维度 06：实现质量 + openapi 契约一致性）

- 日期：2026-11-24；范围：`secs-simulator-service/secs-api-http/src/main/java`（35 个主源文件）+ `scripts/openapi.yaml` + 历史审计抽查 + 测试目录盘点。
- 方式：只读评审（read_file / grep / glob），未运行任何构建或测试。
- 前提：ADR-0018（内网开放访问：无鉴权、CORS 全放行、默认 0.0.0.0）为已接受决策，本报告不将其列为缺陷，只评估该决策下的剩余风险。

## 现状概述

结构上与任务描述一致：`HttpApiServer.java`（814 行）只保留生命周期、路由表（`registerRoutes`，HttpApiServer.java:316-358）、线程模型与静态资源兜底；资源域拆分为 SessionHandler / ConfigHandler / PreviewHandler / HostSimHandler / OpsHandler / StreamHandler，另有 DeviceRuntimeHandler / DeviceLinkHandler / PlanExecutionsHandler / CatalogEditHandler / ConfigTemplateHandler / SessionHostSimRoutes 等二级拆分；公共设施集中在 `HttpSupport`（响应、审计、请求体/查询解析、JSON 转义），路径常量集中在 `ApiPaths`。

线程与限时模型（均有代码证据）：
- 虚拟线程默认开启：`VIRTUAL_THREADS = !"false".equalsIgnoreCase(System.getProperty("secssim.http.virtual"))`（HttpApiServer.java:79-80），请求池 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual()...)`（:290-292），平台线程回退固定 8（:89）。
- 慢请求专用池：4 线程 + 16 排队，满则 503 `busy`（:66-69、:627-634）；慢路由判据 `isSlowRequest`（:700-719）覆盖 host-sim、message/reload/burst/validate、config-preview、messages。
- 请求截止：默认 120s，`scheduleDeadline` 到点 `ex.close()`，SSE 豁免（:97-98、:727-743）。
- SSE：专用池 0..64 + SynchronousQueue，超限 503（:176-184、StreamHandler.java:174-177）；看门狗 5s 巡检、60s 写停滞踢除（:60-63、StreamHandler.java:182-194）。

请求体上限三档：4 KiB 默认 / 256 KiB 配置 / 64 KiB SML（HttpSupport.java:28-39），读体前预检 Content-Length（:216-225）+ `readNBytes(limit+1)` 双保险（:226-232）。

总体评价：实现质量高于同类内部工具的常见水准——错误映射用枚举/字段而非字符串 contains、SSE 帧防注入、路径穿越防护、慢正文防饥饿都有针对性代码和注释，且多数配有回归测试。主要缺口在 **openapi 契约同步**：2026-11-18 审计声称的修复确已落地，但审计之后新增的若干可变端点未回写 openapi（详见下表）。

## 契约对照表（不一致项逐条）

方法：grep `^  /` 提取 openapi 全部 71 条 path（父 Agent 复核：yaml.safe_load 计数 71，check-openapi.py 同口径；原文误写 72 已订正），与 `ApiPaths` 常量、`registerRoutes` 注册表及各 Handler 分发逻辑对照；重点端点逐行读实现核对。

**先说重点核查结论（v1 触发端点迁移）：已同步，无问题。**
- openapi 的 path 清单中不存在 `/api/v1/sessions/{id}/ceid/...`、`/api/v1/sessions/{id}/alarm/...`（71 条 path 全列核对；grep `sessions/\{id\}/ceid|sessions/\{id\}/alarm/` 零命中）。
- v2 已声明：`/api/v2/devices/{id}/runtime/alarm/{alid}` post+delete（openapi.yaml:790-827）、`/runtime/ceid/{ceid}` post（:852-864）、`/runtime/alarms` get（:828-851）。
- 实现一致：v1 残余 POST 子资源统一 404（SessionHandler.java:479-482，注释明说"手动触发（ceid / alarm）也已迁 v2"）；v2 分发 `case "alarm"/"alarms"/"ceid"`（DeviceRuntimeHandler.java:164-166），响应码 202/400/404/409/502 与 openapi 声明逐条吻合（`respondTrigger`，DeviceRuntimeHandler.java:438-447）。

**发现的不一致项：**

| # | 类型 | 端点 | 实现证据 | openapi 证据 | 级别 |
|---|---|---|---|---|---|
| C1 | 漏声明 | `DELETE /api/v2/devices/{id}`（销毁会话） | DeviceRuntimeHandler.java:75-81 分发、:95-106 实现（200/404/502） | path 清单无 `/api/v2/devices/{id}:` 条目（grep `devices/\{id\}:` 零命中） | P1 |
| C2 | 漏声明 | `POST /api/v2/devices/{id}/apply-sections` | DeviceRuntimeHandler.java:67-69 分发、:178 起实现 | grep `apply-sections` 全文零命中 | P1 |
| C3 | 漏声明 | `PUT /api/v1/configs/{name}/sections`（分段保存） | ConfigHandler.java:400-409；ApiPaths.java:110 `SECTIONS_SUFFIX = "sections"` | grep `sections` 全文零命中 | P1 |
| C4 | 漏声明 | `GET/POST /api/v1/host-sims/{hostId}/burst` | HostSimHandler.java:219-247 | host-sims 族仅声明 send/collector/start/stop（openapi.yaml:1291-1398），grep `burst` 无 host-sims 命中 | P1 |
| C5 | 语义漂移 | `DELETE /api/v1/sessions/{id}` | 实现为"断链不删实体"，回 `{"ok":true,"phase":"DISCONNECTED","hint":...}` 并带 Deprecation/Sunset 头（SessionHandler.java:289-328） | openapi.yaml:213 仍写 `summary: 关闭并移除会话`，未提 Deprecation/hint/phase | P2 |
| C6 | 待核对 | `POST /api/v1/sessions/{id}/message` 的 502 | 实现 IOException → 502 `send-failed`（SessionHandler.java:519-523） | openapi.yaml:319-350 该段响应码本次未读取，无法确认是否声明 502 | 缺口 |

反方向（openapi 有而实现无）：71 条 path 均能对应到已注册的 HttpServer 上下文前缀，未发现整条死声明。但前缀内的子路径（如 `/api/v2/hosts/{hostId}/variables`、`/runtime/catalog/{kind}/{key}`、`/runtime/plans/{planId}/step|enabled`）的分发实现本次未逐一读取，**不能排除子路径级死声明**（见置信度说明）。

历史审计（docs/reports/audit-2026-11-18-api-contract.md）抽查 3 项，均核实落地：
1. **批次二（v2 alarms 查询）**：`handleDeviceAlarmsV2` 存在且回 200/404/405（DeviceRuntimeHandler.java:396-411）；openapi:828-851 已声明信封 `{"alarms":[…]}`；v1 `/alarms` 已收敛为带 hint 的信封：`respond(ex, 200, "{\"alarms\":" + body ... + ",\"hint\":\"use GET /api/v2/devices/{id}/runtime/alarms\"}");`（SessionHandler.java:418-419），openapi:241/269 对 alarms/terminal 标 `deprecated: true`。✅
2. **批次四（SSE 帧身份收敛）**：`sessionScoped` 仅对 S6F11/ALARM/SESSION/DEVICE_LINK 写顶层 sessionId（StreamHandler.java:301-306），`broadcastNotice` 按此拼帧（:271-283），注释直接引用"审计 2026-11-18 P2-1"。✅
3. **批次三（删 7 组死声明）**：openapi path 清单中已无 v1 `sessions/{id}/ceids|reports|svids` 及 POST events 条目（71 条全列核对）。✅
（批次一 NoticeEvent 注释补 save-section 未抽查，见缺口。）

## 逐项核查（文件路径:行号 + 逐字摘录）

### 1. HttpApiServer：路由/线程/截止/兜底
- 路由表集中一处，集合路径先于前缀注册并有注释解释匹配陷阱（HttpApiServer.java:339-343）：`/* 集合路径要注册在实例前缀之前：HttpServer 按注册顺序匹配上下文，... 实例 id 取不到就直接崩。 */`
- 500 兜底不回显堆栈（HttpApiServer.java:683）：`byte[] body = "{\"ok\":false,\"error\":\"internal-error\"}".getBytes(StandardCharsets.UTF_8);` 堆栈只进服务端日志（:681 `Log.error(...)`）。✅ 无内部信息泄漏。
- 截止豁免 SSE（:732-733）：`if (ApiPaths.EVENTS.equals(path)) { return null; }`
- 静态资源防穿越（:514-524）：`target = this.webRoot.resolve(path.substring(1)).normalize();` + `if (!target.startsWith(this.webRoot)) { respond(ex, 403, ...); }`；`%00` 等非法路径 `InvalidPathException` → 400 `bad-path`（:515-519）。`/api/*` 未注册路径回 JSON 404 而非 SPA HTML（:507-510）。✅
- 观测性小瑕疵：本类私有 `respond`（:779-786）**不写** `HttpSupport.LAST_STATUS`，而访问日志读的是它（:659 `int status = HttpSupport.lastStatus();`）——health/shutdown/静态资源/慢池 503/兜底 500 的访问日志状态码恒为 0。见问题 P3-1。

### 2. HttpSupport：错误响应/JSON 解析/参数校验
- 三态 400 收敛（HttpSupport.java:294-318）：超限 → `body-too-large`，空/语法错/非对象 → `bad-body`；注释明说非对象 JSON 曾导致 500（:273-276）。✅
- `limitOrBad` 哨兵语义（:334-346）：`return value < 1 || value > max ? -1 : value;`，NumberFormatException 同样 -1，不静默兜底。✅ 负数/超大/NaN 全部拒绝。
- 结构化错误映射（:133-159）：`respondCollector` 按 `CollectorResult.outcome()` 枚举给 200/404/409/400，注释点名旧 contains 判法的坑。✅
- 附件名收口（:492-502）：只留 `[A-Za-z0-9._-]`，其余替换 `_`，防 Content-Disposition 头注入。✅
- `requireBody` 以抛 `IllegalArgumentException` 表达超限/空（:252-261）——若调用方未捕获会落到兜底 500 而非 400；本次未逐一核对全部调用点的捕获情况（缺口，列 P3-5 待查）。
- 请求 Content-Type 不校验（readJsonObject 不看头直接解析）：宽松但无害，列 P3。

### 3. 输入校验与防护
- SML 64 KiB（SessionHandler.java:493-500）：`String sml = readBody(ex, MAX_SML_BYTES);` 超限 400 `body-too-large`、空 400 `empty-sml`。✅
- 慢正文防饥饿（HttpSupport.java:214-215 注释）：`/* 先看声明的长度：Content-Length 巨大 + 每秒 1 字节是打满 4/8 个请求线程的标准手法，不预检就会一直阻塞在 readNBytes 上。 */` 配合 120s 截止双保险；有专项测试 HalfSentBodyStarvationTest。✅
- 数值边界：preview `buckets` 1..500、`to<=from` 400、窗口 24h 上限且捕获 long 溢出（PreviewHandler.java:104-117）：`/* windowMs < 0 捕获 to-from 溢出：from 为极小负数、to 为极大正数时差值会绕回负值 */`；messages/audit limit 范围校验 400 `bad-limit`（OpsHandler.java:61-68、143-150）；host messages `limitOrBad(ex, 500, 5000)`（HostSimHandler.java:449-451）。✅
- URL 解码口径统一：查询串用 `getRawQuery()` + 显式 decode（PreviewHandler.java:86、StreamHandler.java:161）；路径段用已解码的 `getPath()` 且不二次 decode（ConfigHandler.java:649 `/* getPath() 已解码：不要再 decode，含 % 的名字会被解坏 */`；SessionHandler.java:347 同理）。✅
- 配置文件名穿越防护在 runtime 层（ConfigStore.java:748）：`if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..") || trimmed.contains(":")) {`。HTTP 层 `configNameFromPath` 限定 1-2 段（ConfigHandler.java:647-656）。✅（Windows 盘符 `:` 也被拒。）
- alarm 触发入口校验：alid≥1、category 1..7、text 仅 US-ASCII，全部 400 前置（DeviceRuntimeHandler.java:335-381），注释：`/* ALCD 低 3 位是类别，0 无定义：入口就拒，别等 AlarmReporter 抛 IOException 变 500 */`。✅### 4. SSE / StreamHandler
- 熵源与格式（StreamHandler.java:88-89、336-340）：`private static final SecureRandom TICKET_RANDOM = new SecureRandom();` + `byte[] bytes = new byte[32]; TICKET_RANDOM.nextBytes(bytes); return java.util.HexFormat.of().formatHex(bytes);` —— 256 bit CSPRNG，足够。✅
- 一次性语义（:343-350）：`Ticket value = this.tickets.remove(ticket); return value != null && value.expiresAtMs() >= System.currentTimeMillis() ? value : null;` ConcurrentHashMap.remove 原子消费，重放必失败。✅
- TTL 30s（:39 `TICKET_TTL = Duration.ofSeconds(30L)`）；签发时清过期（:133）+ 单账号未消费上限 8 张、淘汰最旧（:48、:137、:315-332），防 ticket map 膨胀。✅
- 并发上限 64（:86 `SSE_THREADS_MAX = 64`），超限 503 `too-many-streams`（:176）。注意：票据在提交 SSE 池**之前**已消费（:162→:173），503 时票据作废，客户端须重新取票（语义合理但契约未写明）。
- 背压：每订阅者 256 帧队列，满则丢最旧（:83、:414-422 `if (!this.queue.offer(frame)) { this.queue.poll(); this.queue.offer(frame); }`）——慢客户端只丢帧不阻塞广播。✅
- 看门狗与断线清理：5s 巡检、60s 无写进展踢除（:182-194）；`abort()` 放到固定 2 线程的 ABORT_EXECUTOR 执行，避免 close 卡死看门狗（:64-77、:451-473）；写循环 finally 中 `subscriber.markClosed(); this.subscribers.remove(subscriber);`（:397-400）。10 分钟寿命上限（:54）+ 15s 心跳（:51）。✅
- 帧注入防护（:209-228）：事件名 `replaceAll("[^A-Za-z0-9_-]", "")`，data 逐行加 `data:` 前缀，任何字节逃不出 data 字段。✅ 有 SseFrameTest 5 例。
- 小瑕疵：`broadcastNotice` 对 payload 用 `startsWith("{") && endsWith("}")` 判对象后直接 substring 拼接（:279-280），`"{bad}"` 这类"看着像对象"的字符串会产出非法 JSON 帧；payload 来自服务端内部总线，非用户直接可控，列 P3。

### 5. HostSimHandler：SSRF 面与 burst 上限
- 远程目标（HostSimHandler.java:53-54 注释）：`/* 请求体:{ "session":"line1", "host":"...", "port":5000, ... } session 必填;host/port 给了就是远程目标,不给就连本机设备。 */`；port 校验 `if (port < 1 || port > 65535)` → 400 `bad-port`（:80-81）；host 任意字符串、无白名单/私网黑名单；hostId 拒 `/` 与 `\`（:88-90）。
- **评估（ADR-0018 语境）**：这确实是一个"任意内网 TCP 目标"入口——但它是 HSMS/SECS 模拟器连接远程主机的**核心功能**，且协议非 HTTP，无法读回任意响应内容（只能发 SECS 报文并观察连接结果），实际能力约等于内网 TCP 端口探测器。在"内网互信、有意开放"的已接受模型下，这属于设计内功能而非缺陷；剩余风险是：若模拟器所在网段与其他敏感内网段互通，任何能访问 API 的人可借它做横向端口扫描/连接占用。建议（非必改）：文档标注该能力，或提供可选的目标网段 allowlist 配置。列 P3-建议。
- burst：HTTP 层透传 JSON，上限在 runtime（BurstRunner.java:114-118）：`if (count < 1 || count > 5000)` → `count-out-of-range`；`if (intervalMs < 0 || intervalMs > 60_000L)` → `interval-out-of-range`；同会话单任务（409 burst-running/burst-busy），慢池 4+16 再兜一层。✅ 状态码按解析字段判定（SessionHandler.java:555-573、HostSimHandler.java:263-274），注释明确拒绝 contains 判法。✅

### 6. 错误处理一致性
- 映射体系整体一致：枚举 outcome → 状态码（SessionOutcome/CollectorResult/TriggerOutcome/ConfigResult），编辑类统一 `respondEditStatus`（ConfigHandler.java:700-708：200/201/400/404/409，default 500 `edit-failed`）。
- 协议层失败统一 502：v1 手动发送（SessionHandler.java:523）、v2 触发（DeviceRuntimeHandler.java:445 `case FAILED -> respond(ex, 502, ...)`）、注释明说与 v2/主机模拟统一。✅
- 500 不回显堆栈/内部细节（HttpApiServer.java:683），错误 message 回显处均经 `json()` 转义（如 SessionHandler.java:514-515 `bad-sml` 附 message），无 JSON 注入。✅
- 405 处理各 handler 自带，`Allow` 头未设置（P3 小项）。

### 7. 历史审计抽查
见契约对照表末尾：批次二、三、四项均有实现与 openapi 双侧证据，审计"四批次已落地"的声称**在抽查范围内属实**。批次一（NoticeEvent 注释）未抽查。审计自述遗留"前端 setAlarm/trigger 仍走 v1 触发端点"——本次后端已确认 v1 触发回 404 且 openapi 已删；**前端是否已切换本次未核查**（不在本维度范围，但若未切则前端报警/触发功能会 404，建议父任务另派人核查 ui/src/api）。

### 8. 测试覆盖评估（api-http/src/test，12 文件）

@Test 计数（search_files count 全量统计，共 **143** 个）：

| 文件 | @Test 数 | 覆盖面 |
|---|---|---|
| HttpApiServerTest | 63 | 主 API 行为（会话/配置/预览/运维/错误码） |
| ConfigApiTest | 25 | 配置 CRUD/校验/If-Match/分段 |
| ApiContractHardeningTest | 11 | 历史缺陷回归：非对象 JSON→400、非法 limit、失败动作状态码、%00 路径 |
| DeviceRuntimeApiTest | 11 | v2 运行时端点 |
| StaticAssetsTest | 8 | 静态资源/穿越/404 语义 |
| DeviceLinkApiTest | 6 | 链路动作 |
| SseMessageTest | 6 | SSE 报文帧 |
| SseFrameTest | 5 | 帧注入防护 |
| ParamTemplateApiTest | 5 | 参数模板库 |
| SseConcurrencyTest / SseStallTest / HalfSentBodyStarvationTest | 各 1 | 并发上限 / 写停滞踢除 / 慢正文饥饿 |

评价：对本层最危险的三类问题（错误状态码漂移、SSE 资源泄漏/注入、慢正文 DoS）都有专项回归，且 ApiContractHardeningTest 头注释明说"每一条都对应一个曾经会给出错误状态码的真实缺陷"（ApiContractHardeningTest.java:24-27）——测试是缺陷驱动而非覆盖率驱动，质量高。弱点：SSE 并发/停滞各仅 1 例；**openapi↔实现的机器比对测试不存在**（C1-C4 这类漏声明正是缺这道闸门的结果）；本次未运行测试，通过与否未验证。

## 问题清单

**P0：无。**（未发现可远程崩溃、数据破坏或绕过已接受安全模型之外边界的问题。）

**P1（契约缺口，建议本轮补齐 openapi）**
- P1-1 = C1：`DELETE /api/v2/devices/{id}` 已实现未声明（DeviceRuntimeHandler.java:75-106）。生成客户端/脚本无从发现该端点。建议补 path（200/404/502）。
- P1-2 = C2：`POST /api/v2/devices/{id}/apply-sections` 未声明（DeviceRuntimeHandler.java:67-69、:178 起）。
- P1-3 = C3：`PUT /api/v1/configs/{name}/sections` 未声明（ConfigHandler.java:400-409）。分段保存是 2026-11-18 新功能，openapi 未跟上。
- P1-4 = C4：host-sims 实例 `/burst` GET/POST 未声明（HostSimHandler.java:219-247）；会话级 burst 已声明（openapi.yaml:427），主机级同形却缺席。
- 共同建议：加一个 CI 校验（解析 openapi paths ↔ ApiPaths/注册表/分发表比对，或至少 path 清单快照测试），堵住"实现先行、契约掉队"的复发路径——本次 4 项全是同一成因。

**P2**
- P2-1 = C5：`DELETE /api/v1/sessions/{id}` 契约语义过时：openapi.yaml:213 `summary: 关闭并移除会话` vs 实现"断链不删实体 + Deprecation/Sunset + hint"（SessionHandler.java:289-328）。调用方按文档预期"设备从列表消失"会与实际行为相反。建议改 summary/description、声明 Deprecation 头与 hint 字段。

**P3（小项/建议）**
- P3-1 访问日志状态码盲区：HttpApiServer 私有 `respond`（:779）不经 `HttpSupport.respond`（:457-458 才写 LAST_STATUS），health/shutdown/静态/慢池 503/兜底 500 的访问日志 status 恒 0（:659）。取证时"他拿到了什么"对这些路径失效。建议本类 respond 委托 HttpSupport。
- P3-2 `broadcastNotice` 的 payload 对象判定靠首尾字符（StreamHandler.java:279-280），畸形 payload 可产出非法 JSON 帧；来源为内部总线，风险低。建议 JsonHub 校验后再拆壳。
- P3-3 SSE 503 时票据已消费（StreamHandler.java:162-177），重连需重新取票；行为合理但 openapi `/api/v1/events` 未描述，前端若直接复用旧票会 401。建议契约注明。
- P3-4 SSRF 面文档化：`{"host","port"}` 无目标限制（HostSimHandler.java:66-91）为设计内功能（见逐项核查 5），建议在 ADR-0018 或 openapi 描述中显式标注该能力与网段边界假设；可选 allowlist。
- P3-5 `requireBody` 抛 IAE 的调用点若未捕获会变 500 而非 400（HttpSupport.java:252-261）；本次未逐一核对调用点，列为待查而非确认缺陷。
- P3-6 杂项：405 无 `Allow` 头；请求 Content-Type 不校验（宽松解析）；`HttpApiServer.json()` 与 `HttpSupport.json()` 完全重复（:789-813 vs :517-541），可删一份。
- P3-7 源码观感：HttpApiServer.java:77 与 StreamHandler.java:389 存在疑似编辑残留（javadoc 中孤立的 `false;` 行、心跳帧赋值后同行紧跟 `}`），均在注释/合法语法内不影响编译，建议顺手清理。

## 亮点

1. **缺陷驱动的错误映射纪律**：多处注释明确记录"contains 猜状态"的历史坑并改为枚举/字段判定（HttpSupport.java:124-125、SessionHandler.java:466-469、HostSimHandler.java:260-261），且有 ApiContractHardeningTest 回归。
2. **SSE 工程化完整**：CSPRNG 一次性票 + 原子消费 + 单账号配额；队列丢最旧背压；写停滞看门狗 + 独立 abort 池防 close 卡死；帧级注入防护——每个机制都有注释解释"为什么"，多数有测试。
3. **DoS 面系统收敛**：慢正文双保险（CL 预检 + readNBytes + 120s 截止）、慢路由隔离池 + 有界队列 503、预览窗口 24h 上限（注释明说跨域全放行下无上限等于免费 CPU 耗尽入口，HttpSupport.java:44-47）、burst count/interval 上限——说明团队在 ADR-0018 开放模型下认真做了剩余风险治理。
4. **路径穿越防护分层清晰**：静态资源 normalize+startsWith+%00 处理；配置名在 ConfigStore 单点收口；getPath/getRawQuery 解码口径有注释固化。
5. **v1→v2 迁移的废弃礼仪**：Deprecation/Sunset 头 + body hint 指向替代端点，openapi 同步标 deprecated——比常见的"直接删"友好得多。

## 置信度说明

- **高置信**（逐行读到实现与 openapi 双侧证据）：v1 触发端点删除与 v2 声明同步；C1-C5 五项不一致；SSE 票据/背压/看门狗机制；SML/limit/buckets/burst 边界；500 不泄堆栈；静态资源防穿越；审计批次二/三/四落地；测试文件与 @Test 计数（程序统计）。
- **中置信**：C1-C4 的"openapi 无声明"基于全文 grep 零命中 + 71 条 path 全列核对，若 openapi 用了非常规缩进/锚点复用表达这些路径则可能漏检（已用宽松关键词 `sections|apply-sections|burst` 复核，风险很低）。
- **明确缺口**（受调查预算限制，未覆盖）：
  1. openapi 前缀内子路径级死声明未逐一核对（hosts/{hostId}/variables、catalog/{kind}/{key}、plans/{planId}/step|enabled 等的 Handler 分发未读）；
  2. C6：v1 message 端点 openapi 响应码段未读；
  3. ConfigHandler/SessionHandler/HostSimHandler/OpsHandler 未全文通读（按关键符号定位抽查），可能存在未发现的局部问题；
  4. 审计批次一（NoticeEvent 注释）与前端 setAlarm/trigger 切换状态未核查；
  5. `requireBody` 调用点捕获情况未核对（P3-5）；
  6. 未运行任何测试/构建，"测试覆盖评估"仅基于静态盘点，不代表当前全绿。
- 本报告所有行号均来自本次实际 read_file/grep 输出；未读到的内容一律标注缺口，未做推断性断言。