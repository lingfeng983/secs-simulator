# 全量代码评审 · 2026-10-17

评审基线：`293f20e feat: 绿色包打包与一键启动 (M9-a)`，工作区干净（无未提交改动）。

**覆盖说明**：
- `secs-app` + `secs-api-http`：深度评审完成（逐文件）。
- `secs-runtime` / `frontend` / `Main` / `HsmsSessionRuntime` / `EventBus` / `previewJson`：关键路径一手精读。
- `secs-protocol`（205 文件）：上游冻结 vendored 代码，仅抽查，未深评。
- `secs-core` / `secs-json` / `secs-cli`：本轮未深评（列入下轮补评）。

总体结论：**架构健康**。模块依赖由 ArchitectureTest 强制、安全基线到位（SecureRandom token、路径穿越防护、常量时间比较）、154 个测试全绿、文档与里程碑记录完整。发现的问题集中在 HTTP 层线程模型与前端交互细节，没有协议层或数据层的致命缺陷。

---

## 一、问题清单

### 高（建议本周修）

| # | 位置 | 问题 |
|---|---|---|
| H1 | `HttpApiServer.java:130,427-463` | **SSE 会饿死 HTTP 线程池**。executor 是 `newFixedThreadPool(4)`，每个 SSE 连接在 `handleEvents` 里占住一个线程最长 30 分钟。开 4 个浏览器标签页后，health/sessions/preview 全部排队超时。修法：SSE 用独立（虚拟线程或 cached）池，或改用异步写。 |

### 中

| # | 位置 | 问题 |
|---|---|---|
| M1 | `App.tsx:162` | 曲线预览时间窗硬编码 `(0, 60_000)`，永远渲染会话开局那一分钟的静态曲线；且只取 `sessions[0]`。前端没有时间窗选择器，预览功能基本不可用（后端参数是齐的）。 |
| M2 | `App.tsx:118-131` | SSE 断开期间事件丢失且无任何补偿（后端 30 分钟强制断开 + EventSource 自动重连，重连间隙的报文永久丢失）。至少要在重连后触发一次 sessions 刷新并在 UI 上标注丢失窗口。 |
| M3 | `HttpApiServer.java:188-193` | SSE 广播"队列满则 poll 最旧再 offer"在多发布者下有竞态：一次 poll+offer 之间另一线程也可能 poll，极端时丢两帧。EventBus 发布方来自 communicator 线程，不保证单线程。可改为单发布线程或 synchronized。 |
| M4 | `HttpApiServer.java:437-462` | SSE 客户端 TCP 零窗口时 `out.write` 可能长时间不抛异常，订阅者队列一直占着内存直到 30 分钟 deadline。可加写超时或定期检测。 |
| M5 | `HttpApiServer.java:265-267` | 静态资源 404 一律 fallback 到 `index.html` 并返回 200：打错的 API 路径（未被 /api 前缀覆盖时）会拿到 HTML 而不是 404，排查困难。建议 fallback 仅对非文件扩展名路径生效。 |
| M6 | `HttpApiServer.java:385-392` | preview 的 `from/to/buckets` 非法时静默回落默认值而不是 400，调用方无法察觉参数被忽略。 |
| M7 | `HttpApiServer.java:472-478` | 查询参数 token 未做 URL-decode（与 `parseQuery` 行为不一致），带特殊字符的 token 走 `?token=` 会认证失败；且 query token 会进访问日志/浏览器历史（为 EventSource 的已知妥协，建议 EventSource 改用 cookie 或一次性 ticket）。 |
| M8 | `HsmsSessionRuntime.java:89` | `lastFireMs` 是非 volatile 的可变 long，目前只在 ticker 单线程读写，但它是类字段且语义上跨"调度/HTTP 触发"两条路径，后续演进极易踩可见性坑。建议 volatile 或移入 ticker 局部状态。 |
| M9 | `secs-api-http` 测试 | 缺"多 SSE 客户端并发 + API 可用性"测试（正是 H1 场景）；路径穿越有 StaticAssetsTest 覆盖，但 sibling-prefix（`webroot-evil`）场景建议补显式用例。 |

### 低

| # | 位置 | 问题 |
|---|---|---|
| L1 | `Main.java:199-210` | `utf8RoundTrip` 每次启动在 workDir 留下 `腔体配置/模板-温度.json` 测试文件且从不清理。 |
| L2 | `EventBus.java:38` | 订阅者异常仅 `System.err.println`，无结构化日志/时间戳，现场排查困难。整个后端日志都依赖 System.out/err，建议引入极简日志门面（不引框架，保持原生镜像体积）。 |
| L3 | `HttpApiServer.java:273` | 静态文件每次请求都 `readAllBytes`，无缓存/ETag；前端只有 3 个文件问题不大，加上 `Cache-Control: no-store` 后每次全量传输。 |
| L4 | `HttpApiServer.java:294-313` | contentType 白名单缺 `.ico`/`.map`/`.txt`/`.woff2`，前端一旦加 favicon 就会以 octet-stream 下发。 |
| L5 | `HttpApiServer.java:143-148` | `/api/v1/health` 无鉴权暴露 version/mode/sessions 数。本地工具可接受，server 模式建议脱敏。 |
| L6 | `HttpApiServer.java:57` | SSE 心跳 1 秒过于激进（一般 15-30 秒），长连接场景浪费带宽；30 分钟强制断开 + 心跳可一并提供。 |
| L7 | `App.tsx:304` | 报文列表 key 用 `${message.at}-${index}`，index 参与使 React 复用失真；append-only 场景可改纯序号或消息 SystemBits。 |
| L8 | `App.tsx:131` | SSE error 监听只改状态文案，不区分"服务关闭/网络错误"，也不把错误写入事件流日志。 |
| L9 | `Main.java:54-56` | 顶层异常直接 `printStackTrace`，对桌面壳（读 stdout 的 packaging 脚本）不友好，建议统一输出到 stderr 的单行格式。 |
| L10 | `HttpApiServer.java:344-346` | `handleSessionCommand` 对 `//ceid/1`（空 sessionId）与尾斜杠路径的 split 行为未显式处理，落到 bad-path 之外的行为依赖 split 语义。 |

### 抽查通过（无问题）

- token 生成 `SecureRandom` + `HexFormat`（`Main.java:189-193`）✅
- 路径穿越防护 `normalize` + `startsWith` 组件级比较（`HttpApiServer.java:259-263`）✅
- token 比较用 `MessageDigest.isEqual` 常量时间（`HttpApiServer.java:487-489`）✅
- previewJson 分桶有界（buckets≤500、采样步长自适应）无 OOM 风险（`HsmsSessionRuntime.java:178-224`）✅
- EventBus 订阅者异常隔离 ✅
- 优雅停机链路（shutdown hook + latch + closeAll）✅
- 代码中无 TODO/FIXME 残留 ✅

---

## 二、后续任务清单

### A. 本轮技术债修复（建议 M9-b，一次修复完毕）

1. **[高] H1**：SSE 移出固定线程池——`handleEvents` 改虚拟线程（JDK 25 直接可用，原生镜像需确认 native-image 支持）或独立 cached 池。
2. **[中] M3/M4**：广播单线程化 + SSE 写超时。
3. **[中] M1**：前端预览加时间窗选择（最近 1 分钟 / 5 分钟 / 全部）+ 会话下拉，后端零改动。
4. **[中] M5/M6**：静态 fallback 收紧为无扩展名路径；preview 参数非法返回 400。
5. **[中] M7**：query token 做 URL-decode；中期 EventSource 鉴权改一次性 ticket。
6. **[中] M8**：`lastFireMs` 加 volatile（一行）。
7. **[低] L1-L10**：随批清理（utf8 测试文件清理、contentType 补全、心跳降频、前端 key/错误处理、日志单行化）。
8. **[测试] M9**：补多 SSE 客户端并发用例 + sibling-prefix 穿越用例。

### B. 功能路线图（按 docs 既有边界，M10+ 候选）

1. **S5F1 报警 + S10F3 终端消息**（m6 报告遗留，需要报警模型语义设计：ALCD/ALID/去抖）。
2. **条件触发 OnCondition**（ADR-0005 遗留：需定义"条件持续成立时如何去抖"）。
3. **事件链**（A 之后 N 秒发 B：`lastFireMs` 扩展为已发生事件集合）。
4. **模板/曲线进配置**：目前模板 DAG 由代码构造，schema-v4 需扩展"每个 SVID 绑定哪条曲线"的表达。
5. **预览二进制通道**：单次预览 >2MB 或 SVID>150 时启用预留的 `series.bin`。
6. **前端曲线交互**：缩放/游标读数/多轴（引入 uPlot 的触发条件已写在 m8e 报告）。

### C. 分发与工程化（M9 已知边界）

1. **代码签名**：未签名 exe 会被杀软拦截，正式分发前必须做（优先级最高）。
2. **安装器**：jpackage MSI 或原生 exe 自解压。
3. **JVM 模式兜底包**：jlink + jpackage，应对原生镜像跑不起来的机器。
4. **自动更新**：检查版本 + 下载替换（Tauri updater 或自研）。
5. **检查清单固化**（已完成）：`scripts/check-bom.ps1` —— 含非 ASCII 的 `.ps1` 必须带 UTF-8 BOM，CI 每次 push 执行；顺带修复 `scripts/accept-M0.ps1` 历史缺 BOM。另：`@vitejs/plugin-react` 锁定 4.3.4（4.7.0 依赖含 `workspace:*` 协议，npm 无法解析），提交 `frontend/package-lock.json` 支撑 CI 的 `npm ci`。
6. **CI**（已完成）：`.github/workflows/build.yml` —— backend（GraalVM JDK 25 `mvn verify`）+ frontend（`npm ci` / `tsc --noEmit` / `vite build`）+ ps1-bom；native 镜像构建与启动冒烟仅在每周定时与手动触发时跑（windows-latest + MSVC）。

### D. 下轮评审补课

1. `secs-core`（领域模型/曲线/编译器，79 个测试但无深评）：✅ 深评完成（2 高 / 4 中 / 7 低），修复进行中。
2. `secs-json`（解析健壮性：嵌套深度、大数字、转义）：✅ 深评完成（2 高 / 8 中 / 9 低），修复进行中。
3. `secs-protocol` 抽查升级为定点深评（HSMS 帧长度字段、SECS-II 解码边界）：✅ 深评完成（2 高 / 5 中 / 4 低），修复进行中。
4. `secs-cli` 端到端测试宿主与 `scripts/`、`packaging/` PowerShell 脚本健壮性：未开始。

---

*评审方法：并行分模块深评（app/api-http 完成）+ 关键路径一手精读 + 文档已知边界核对。protocol（vendored 冻结）未深评，secs-core/secs-json/secs-cli 列入补课。*

---

## 三、修复状态（M9-b，2026-10-17）

全部 20 条已处理，明细见 [`m9b-report.md`](m9b-report.md)。

| ID | 状态 | 说明 |
|---|---|---|
| H1 | 已修 | SSE 独立线程池（0..64，满则 503），主池只跑短任务 |
| M1 | 已修 | 前端会话下拉 + 1/5/15 分钟时间窗，按 `uptimeMs` 取 `[now-N, now]` |
| M2 | 已修 | 重连后刷新会话列表并提示"断线期间不补发" |
| M3 | 已修 | `Subscriber.enqueue` 同步化，消除 poll/offer 竞态 |
| M4 | 已修 | 看门狗：60 秒无写入进展即断开（5 秒巡检）；连接寿命 30 → 10 分钟；`SseStallTest` 覆盖 |
| M5 | 已修 | 仅无扩展名路径回落 `index.html` |
| M6 | 已修 | preview 参数非法一律 400 |
| M7 | 已修 | URL 不再接受长期 token：改用 30 秒一次性 ticket；打包地址改 `#token=`，前端读完即抹掉 |
| M8 | 已修 | `lastFireMs` 加 `volatile` |
| M9 | 已修 | 6 条 SSE 并发、preview 全分支、sibling 前缀、ETag 304 等用例 |
| L1 | 已修 | UTF-8 自检文件清理（目录仅空时删） |
| L2 | 已修 | 新增 `app/log/Log`，单行时间戳日志 |
| L3 | 已修 | ETag + 304 + `no-cache` |
| L4 | 已修 | contentType 补全 + `nosniff` |
| L5 | 已修 | `/health` 免鉴权只回 ok/uptimeMs；版本、形态、会话数需 Bearer |
| L6 | 已修 | 心跳 1s → 15s |
| L7 | 已修 | 报文行 key 改自增序号 |
| L8 | 已修 | SSE 错误按 readyState 记录并 5 秒节流 |
| L9 | 已修 | 顶层异常单行输出，栈由 `SECS_SIM_DEBUG` 打开 |
| L10 | 已修 | 会话命令路径三段均非空 |

**评审外的额外发现**：`.gitignore` 的 `runtime/` 规则把 `secs-runtime/src/main` 下 15 个
Java 源文件全部忽略了（只有 pom 和测试在 git 里），新克隆无法构建。已改为 `/runtime/`
并把源码 `git add`。

