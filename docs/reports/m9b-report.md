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
| 单元测试 | **173 个全绿**：core 79 + app 16 + api-http 33 + runtime 12（含 ArchUnit）+ cli 33 |
| 前端类型检查 | `tsc --noEmit` 通过 |
| 前端构建 | 151.92 kB（gzip 49.58 kB） |
| 编译 | JDK 25（GraalVM）+ Maven 3.6.3，`-Werror` 下零警告 |
| 原生镜像 | `secs-sim.exe` 13,705,216 B；zip 5.4 MB；冒烟通过 |

## 边界问题处理（M9-c）

原报告列的四个"已知边界"已全部处理：

| 项 | 处理 |
|---|---|
| SSE 写超时 | 新增看门狗：每 5 秒巡检，连接连续 60 秒没有任何一帧写成功即判定客户端不读，移除订阅者并在独立线程 `exchange.close()`（关闭本身可能阻塞，不能拖住巡检线程）。连接寿命同时从 30 分钟收到 10 分钟，浏览器到点用新票据重连。回归测试 `SseStallTest` 用不读数据的裸 socket 灌满窗口，5 秒内验证连接被回收。 |
| query token 泄露 | 普通端点只认 `Authorization: Bearer`，URL 里不再接受长期 token。新增 `POST /api/v1/ticket`：用 Bearer 换 32 字节随机票据，30 秒有效、只能消费一次（`ConcurrentHashMap.remove` 原子消费）。SSE 走 `?ticket=`。前端改为每次（重）连都申请新票据，因此不再依赖 EventSource 的自动重连。打包脚本打开的地址改为 `#token=`，hash 不会发给服务端；前端读到后立刻 `history.replaceState` 抹掉。 |
| `/health` 信息暴露 | 免鉴权只返回 `{"ok":true,"uptimeMs":…}`；版本、形态、会话数、HSMS 端口需要 Bearer。前端 health 轮询带 token，界面信息不变。 |
| 原生镜像未重跑 | 已重跑并冒烟：`secs-sim.exe` 13,705,216 B，zip 5.4 MB。冒烟覆盖：未鉴权 health 无版本、带 token 有版本、query token 401、票据签发/连接/复用 401、静态首页 200、工作目录无 UTF-8 自检残留。 |

### 工具链踩坑

GraalVM 在 Windows 上需要 MSVC 的 `cl.exe`。本机 VS 的 C++ 工作负载实际在
`C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools`，但它不在默认 PATH 里；
而**在这个执行环境里用 PowerShell 设置 `$env:PATH` 不会传给子 Java 进程**
（`System.getenv("PATH")` 拿到的是被重置过的 PATH），所以 `mvn -Pnative` 一直报
`cl.exe not found`。最终在 Bash 里导出 `PATH/INCLUDE/LIB` 后构建成功（22.9 秒）：

```bash
MSVC="/c/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207"
export PATH="$MSVC/bin/Hostx64/x64:/e/Java/graalvm-jdk-25.0.4+7.1/bin:$PATH"
export JAVA_HOME='E:\Java\graalvm-jdk-25.0.4+7.1'
export INCLUDE='<MSVC>\include;<SDK>\Include\10.0.26100.0\ucrt;...'
export LIB='<MSVC>\lib\x64;<SDK>\Lib\10.0.26100.0\ucrt\x64;...'
mvn -B -Pnative -pl secs-cli -am -DskipTests package
```

注：Windows SDK 版本是 `10.0.26100.0`；换机器时这两段路径要按 `vswhere` 结果改。

