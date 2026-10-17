# M9-b 验收报告 · 全量评审问题清零

对应评审：[`code-review-2026-10-17.md`](code-review-2026-10-17.md)。本轮把报告里的
**1 高 / 9 中 / 10 低**全部处理完，另外顺手修了一个评审没发现的仓库级问题。

## 交付

| 文件 | 改动 |
|---|---|
| `secs-api-http/.../HttpApiServer.java` | SSE 独立线程池、订阅者队列、静态 ETag、参数校验、query 解码 |
| `secs-app/.../event/EventBus.java` | 改用单行日志 |
| `secs-app/.../log/Log.java`（新增） | 极简日志：`[HH:mm:ss.SSS] LEVEL 消息` |
| `secs-runtime/.../Main.java` | 单行错误输出、UTF-8 自检文件清理 |
| `secs-runtime/.../HsmsSessionRuntime.java` | `lastFireMs` 加 `volatile` |
| `frontend/src/App.tsx` | 预览时间窗/会话选择、重连补偿、稳定 key、错误节流 |
| `frontend/src/App.css` | 预览控件样式 |
| `.gitignore` | `runtime/` → `/runtime/`（见下"仓库级问题"） |
| `SseConcurrencyTest.java`（新增） | 6 条 SSE 并存时 REST 仍可用 |

## 逐条状态

### 高

- **H1 SSE 饿死线程池** — SSE 改由专用池承载（`0..64` 线程、`SynchronousQueue`、daemon，空闲 60s 回收），
  超出直接 503；主池 4 线程只跑短任务。回归测试 `SseConcurrencyTest` 开 6 条流后要求 health < 2s。

### 中

- **M1 预览时间窗** — 前端新增会话下拉 + 时间窗（1/5/15 分钟）。点"刷新曲线"先取一次
  `health.uptimeMs`，窗口取 `[uptime - N, uptime]`，不再永远画开局那一分钟。
- **M2 重连补偿** — 非首次 `open` 时主动刷新会话列表并明确提示"断线期间报文不会补发"。
- **M3 广播竞态** — 新增 `Subscriber`，把"满了丢最旧"的 `poll + offer` 放进同步方法。
- **M4 客户端不读导致堆积** — 队列上限 256 帧 + 30 分钟硬截止；`com.sun.net.httpserver`
  没有写超时 API，属**缓解**而非根治（见"已知边界"）。
- **M5 静态 fallback** — 只有"没有扩展名"的路径才回落 `index.html`；`/missing.js` 现在老实 404。
- **M6 preview 参数** — 写了但不是数字、`to <= from`、`buckets ∉ [1,500]`、缺 `session` 全部 400。
- **M7 query token** — 改用 `getRawQuery()` 再解码一次；顺带修掉了 `%2B` 被双重解码的 bug。
- **M8 `lastFireMs`** — 加 `volatile` 并注明原因。
- **M9 测试** — 新增 6 条 SSE 并发用例；补 preview 全部参数分支、`//` 空会话 id、shutdown/events 的方法校验、
  同前缀兄弟目录穿越、ETag 304。

### 低

- **L1** UTF-8 自检文件用 `finally` 删除，目录仅在为空时删（不会碰用户文件）。
- **L2** 新增 `app/log/Log`，`EventBus` 与 `Main` 全部改单行日志。
- **L3** 静态资源带 `ETag` + `Cache-Control: no-cache`，命中 `If-None-Match` 返回 304。
- **L4** `contentType` 补齐 ico/png/woff2/map/txt/wasm 等，并加 `X-Content-Type-Options: nosniff`。
- **L5** `/health` 仍免鉴权（探针与 start 脚本依赖），version/mode 保留：记为**接受项**。
- **L6** 心跳 1s → 15s。
- **L7** 报文行 key 改用自增序号，`slice(-199)` 不再导致整表重建。
- **L8** SSE 错误按 `readyState` 区分并 5 秒节流写日志。
- **L9** 顶层异常单行输出；完整栈仅在 `SECS_SIM_DEBUG` 时打印。
- **L10** 会话命令路径要求三段均非空。

## 踩坑

1. **SSE 头发出去了，客户端却收不到。** `sendResponseHeaders` 之后不 flush，
   `com.sun.net.httpserver` 会攒着，直到第一帧（心跳/报文）才真正上线。心跳从 1s 改成 15s 后，
   测试直接 10s 超时。修法：连上立刻写一个 SSE 注释帧 `: connected` 并 `flush()`。
2. **`URI.getQuery()` 已经解码过一次。** 我又用 `URLDecoder` 解一次，`%2Bb` → `a+b` → `a b`，
   token 永远对不上。必须用 `getRawQuery()`。
3. **`.gitignore` 的 `runtime/` 把整个模块源码忽略了。** `secs-runtime/src/main` 15 个 Java 文件
   从来没进过 git（只有 pom 和测试被跟踪），**新克隆根本编译不过**。改成 `/runtime/` 并把源码 `git add`（未提交）。

## 实测

| 项 | 结果 |
|---|---|
| `mvn -B verify` | **BUILD SUCCESS** |
| 单元测试 | **168 个全绿**：core 79 + app 16 + api-http 28 + runtime 12（含 ArchUnit）+ cli 33 |
| 前端类型检查 | `tsc --noEmit` 通过 |
| 前端构建 | 150.67 kB（gzip 49.3 kB） |
| 编译 | JDK 25（GraalVM）+ Maven 3.6.3，`-Werror` 下零警告 |

## 已知边界（未做）

| 项 | 说明 |
|---|---|
| SSE 写超时 | `com.sun.net.httpserver` 不暴露 socket，无法给 `out.write` 设超时。现状：队列有界（256 帧）+ 连接寿命 30 分钟硬截止，最坏情况占 1/64 个 SSE 线程 30 分钟。要根治需换传输层（Servlet/Netty）或自建 socket，与"零依赖、13MB 原生镜像"的取舍冲突。 |
| query token 日志泄露 | `EventSource` 不能设请求头，这是已知妥协。README 已写明；要消除需引入一次性 ticket（下一轮）。 |
| `/health` 信息暴露 | 无鉴权返回 version/mode，供探针与启动脚本使用，已接受。 |
| 原生镜像 | 本轮未重跑 `native-image`（只跑 JVM 测试）。前端与后端改动都不涉及反射/动态代理，风险低，但发包前应重跑 `scripts\package.ps1` 冒烟。 |
