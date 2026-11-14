# secs-simulator

SECS/GEM 多单元（multi-unit）设备模拟器。

**一套内核、两种形态**：既可单机一体化运行，也可作为 Linux 服务端供远程客户端访问；
两种形态共用同一份内核、同一套 HTTP/SSE API、同一份前端。

---

## 仓库结构

| 目录 | 内容 |
|---|---|
| `secs-simulator-service/` | Java 后端（父 POM + 下表 8 个 Maven 模块）、`openapi.yaml`、`packaging/`（打进 Linux 包的启动素材）、`tools/host-e2e/`（实机协议探针）、`scripts/`（后端门禁） |
| `secs-simulator-ui/` | Web 前端（Vite + React），含 mock/实机 Playwright 探针与 `scripts/`（前端门禁） |
| `secs-simulator-gui/` | Tauri 桌面壳，`assets/`（图标源）与 `scripts/make-icon.py` |
| `scripts/` | 全仓唯一脚本 `package.ps1`：生成安装包（产物落 `scripts/out/`） |
| `docs/` | 文档（分区见 [docs/README.md](docs/README.md)） |

## 模块

（均在 `secs-simulator-service/` 下）

| 模块 | 职责 | 依赖约束 |
|---|---|---|
| `secs-json` | 无反射 JSON 读写 | 仅依赖 JDK |
| `secs-protocol` | HSMS-SS / SECS-I / SECS-II / SML / GEM 报文层（**冻结**，极少改动） | 仅依赖 JDK |
| `secs-core` | 领域模型、ID 规划、曲线、值引擎、编排（无 I/O、无 JSON） | 仅依赖 JDK |
| `secs-store` | 持久化层：`config.db`（设备/主机/模板/计划动作/报告/报警）与 `runtime.db`（钉值/计划启用态/目录编辑/链路相位/执行台账），含 2.x JSON 的一次性迁移 | core + json + sqlite-jdbc |
| `secs-app` | 应用服务：会话、配置、日志、SML 文本层（`app.sml`） | core + json + protocol |
| `secs-api-http` | REST + SSE + JSON 曲线预览 | app + json + `com.sun.net.httpserver` |
| `secs-runtime` | 装配与生命周期：INTEGRATED / SERVER / PROBE；会话的协议实现 | 以上全部 |
| `secs-cli` | 进程入口 `CliMain` 与端到端测试宿主 | runtime |

依赖方向由 `ArchitectureTest` 强制，越界即构建失败。

### 类结构

超过千行的类会拆开——一个类只做一件事，协作对象通过注入拿到自己需要的东西。
拆分后的主要布局：

| 模块 | 拆分后的主要类 |
|---|---|
| `secs-protocol` | `Gem` 按能力域拆成 `GemCommunication` / `GemClock` / `GemRemoteCommand` / `GemEventReport` / `GemError` / `GemTerminal` 六个子接口，`AbstractGem` 组合它们，共享状态在 `GemSupport` |
| `secs-api-http` | `HttpApiServer` 只留路由表与生命周期，按资源域拆成 `SessionHandler` / `ConfigHandler` / `PreviewHandler` / `HostSimHandler` / `OpsHandler` / `StreamHandler`，公共部分在 `HttpSupport`，路径常量在 `ApiPaths` |
| `secs-app` | SML 解析与渲染独立成 `app.sml` 包（只做 `Secs2 ↔ 文本`，不含会话状态） |
| `secs-runtime` | `HsmsSessionRuntime` 拆出事件上报（`EventReporter` / `EventScheduler`）、设备侧 S2 命令处理（`session/HostCommandHandler`）、报文分发（`PrimaryDispatcher`）、采样（`TracePump`）、报警（`AlarmReporter`）、终端消息（`TerminalInbox`）、预览（`PreviewBuilder`）、热生效（`HotReloader`）等协作者；装配由 `RuntimeContext` 门面 + `RuntimeApi`（API 落地）、`HostSimRegistry`（主机实例簿记）、`BurstRunner`（服务端压测）三个 package-private 协作者组成，入口是 `secs-cli` 的 `CliMain` |

## 构建

需要 JDK 25（推荐 GraalVM 25，自带 `native-image`）。

```powershell
$env:JAVA_HOME = 'E:\Java\graalvm-jdk-25.0.4+7.1'
cd secs-simulator-service
mvn -B verify
mvn -B -Pnative verify       # 原生镜像 + NativeRuntimeIT（需先构建 secs-simulator-ui/dist）
```

`-Pnative` 会构建原生镜像并在 `verify` 阶段用 failsafe 跑
`NativeRuntimeIT`（API、SSE ticket、HSMS S1F1、S1F3、优雅停机）。
日常门禁不覆盖原生链路；CI 里由夜间任务 `native-nightly.yml` 构建（非阻断），
发版前仍以本地 `-Pnative verify` 为准。

### 本地质量门禁

提交前分别跑两个模块的门禁：

```powershell
powershell -ExecutionPolicy Bypass -File secs-simulator-service/scripts/check.ps1   # mvn test + openapi 契约
powershell -ExecutionPolicy Bypass -File secs-simulator-ui/scripts/check.ps1        # tsc + 文案 + Vitest + Playwright（-SkipE2E 可跳）
```

CI（`.github/workflows/ci.yml`）在每次 push/PR 上跑同一组检查：后端测试在
Linux 与 Windows 两个平台矩阵上跑（端口/SO_REUSEADDR 语义不同，单平台绿不算数），
前端跑 `tsc` + Vitest + Playwright，另有 openapi 重复键与 `.ps1` BOM 契约检查。

后端门禁依次执行 `mvn -B test` → `check-openapi.py`（装了 Python 才跑）；
前端门禁依次执行 前端依赖（缺 `node_modules` 时才 `npm ci`）→ `npx tsc --noEmit`
→ `check-ui-words.ps1` → `npm test` → `npx playwright test`，任一步失败即以该步退出码结束。
需要 JDK 25（`JAVA_HOME` 指向 JDK 25，推荐 GraalVM 25）；Playwright 浏览器先装一次：
`npx playwright install chromium`。

## 运行

同一个可执行文件，三种形态：

```powershell
# 一体化（桌面壳调用）：只绑回环，默认开放访问（免令牌）
secs-sim --mode=integrated

# 带一个两腔体演示会话：主机可以连上来问 S1F3
secs-sim --mode=integrated --demo-session --hsms-port 5000

# 从配置文件装载设备（格式见 docs/guides/schema-v5.md）
secs-sim --mode=integrated --config ./device.json --hsms-port 5000

# 同时托管前端（先构建，见下）：浏览器直接打开 READY 行里的 http 地址
secs-simulator-ui\scripts\build.ps1
secs-sim --mode=integrated --demo-session --web-root secs-simulator-ui/dist

# 服务端：默认只绑回环（127.0.0.1），对外绑定必须显式 --bind
# 内部工具不做鉴权，浏览器/脚本直连即可；提供 keystore 时走 HTTPS
secs-sim --mode=server --bind 0.0.0.0 --port 8443 `
    --tls-keystore ./server.p12 --tls-password $env:SECS_TLS_PASSWORD

# 无 TLS 的内网直连
secs-sim --mode=server --bind 0.0.0.0 --port 8080

# keystore 示例：
# keytool -genkeypair -alias secs-sim -keyalg RSA -storetype PKCS12 `
#   -keystore server.p12 -storepass changeit -dname CN=your-host -validity 3650

# 能力探针：开 HSMS 被动端口应答 S1F1，用于现场排障与本地脚本
secs-sim --mode=probe --hsms-port 5000
```

启动后向 stdout 打印一行握手，供桌面壳与脚本读取：

```
READY mode=integrated http=53466 utf8=true sessions=1 hsms=5000 work=C:\Users\...\SecsSimulator
```

`http=` 是界面/API 地址；`sessions=` 是已启动会话数。同时会把 http/端口写进
`<work-dir>/ready.txt`（不含任何凭据），供部署脚本读取。

`--mode=server` 默认绑回环，跨机访问必须显式 `--bind 0.0.0.0`（或具体网卡地址）。
**本工具是内部工具，不做鉴权**：没有令牌、账号或角色门槛；对外暴露时请用网络策略隔离。

HTTP API（v1）。下表「原角色」是历史能力分级，仅作语义参考——现在所有端点直连可调用：

| 端点 | 原角色 | 说明 |
|---|---|---|
| `GET /api/v1/health` | 免鉴权 | 存活、uptime、版本、形态、会话数 |
| `POST /api/v1/ticket` | viewer | 换取一次性 SSE 票据（30 秒有效、只能用一次） |
| `GET /api/v1/events?ticket=` | 票据 | SSE 事件流；票据从上一个端点取 |
| `GET /api/v1/sessions` | viewer | 会话列表（状态 / 设备 / 单元数 / 计数器） |
| `POST /api/v1/sessions` | operator | 新建会话，体为 `{"config":"demo"}` 或 `{"config":"设备.json"}`（201 / 400 / 409） |
| `GET/PUT /api/v1/log-level` | viewer/operator | 读取 / 运行时切换日志级别（error/info/debug，切换记审计） |
| `POST /api/v1/sessions/bulk` | operator | 批量新建：一份配置模板 + 数量/前缀/起始端口/device-id，逐台独立创建（201 全成、207 有失败并逐台给原因，可能全部失败） |
| `DELETE /api/v1/sessions/{id}` | operator | 关闭并移除会话，端口立即解绑（200 / 404） |
| `POST /api/v1/sessions/{id}/reload` | operator | 按配置热重载（200 / 404 / 409 / 400） |
| `POST /api/v1/sessions/{id}/ceid/{ceid}` | operator | 手动触发一次 S6F11（202 / 404 / 409） |
| `POST /api/v1/sessions/{id}/alarm/{alid}?set=&text=&category=` | operator | 置位/清除 S5F1；`set` 默认 true，`category` 1..7（202 / 400 / 404 / 409） |
| `GET /api/v1/sessions/{id}/alarms` | viewer | 当前活动报警（ALID / 类别 / 文本 / 置位时间） |
| `GET /api/v1/sessions/{id}/terminal` | viewer | 最近 20 条终端消息（S10F3） |
| `GET /api/v1/sessions/{id}/catalog` | viewer | 设备目录：CEID / RPTID / SVID 的绝对 ID 与语义，按 ID 升序 |
| `GET /api/v1/sessions/{id}/events` | viewer | 事件编排列表（计划 id / CEID / 触发规则 / 启用状态） |
| `POST /api/v1/sessions/{id}/events/{planId}?enabled=` | operator | 运行时启用/禁用一个计划（200 / 400 / 404） |
| `GET /api/v1/preview` | viewer | 曲线预览：`session/from/to/buckets`，返回分桶 min/max/avg + `svidTotal`/`truncated` |
| `GET /api/v1/preview.bin` | viewer | 同一份预览的 SCSP v1 二进制（小端，固定头 + min/max/avg 双精度） |
| `GET /api/v1/messages` | viewer | 最近报文历史：`session/direction/limit/format`；`format=jsonl` 下载 JSONL |
| `POST /api/v1/sessions/{id}/message` | operator | 手动发送 SML 报文，体为 SML 原文（200 / 400 / 404 / 409 / 500） |
| `GET /api/v1/configs` | viewer | 配置目录列表（文件名 / 字节数 / 修改时间） |
| `POST /api/v1/configs` | operator | 新建配置，体为 `{"name":"设备.json"}`，内容是能直接编译的骨架（201 / 400 / 409） |
| `GET /api/v1/configs/{name}` | viewer | 读配置**原文**（编辑器要看到用户写的每一个键） |
| `POST /api/v1/configs/{name}/validate` | viewer | 只校验不落盘，回 `{"valid":bool,"problems":[...]}` |
| `PUT /api/v1/configs/{name}` | operator | 校验通过后原子落盘，体就是配置原文（200 / 400 / 422 / 500） |
| `GET /api/v1/templates` | viewer | 模板列表（内置 + 自定义） |
| `POST /api/v1/templates` | operator | 新建自定义模板，体 `{"id","text"}` |
| `GET/PUT/DELETE /api/v1/templates/{id}` | viewer/operator | 读原文 / 更新 / 删除（内置模板只读） |
| `GET /api/v1/audit` | admin | 最近审计记录（`limit` 默认 200，最大 1000） |
| `GET/POST /api/v1/host-sims` | viewer/operator | 主机实例列表 / 新建实例（本机设备或远程 `{"host","port","deviceId"}`，201 / 400 / 409） |
| `GET/DELETE /api/v1/host-sims/{id}` | viewer/operator | 读实例状态 / 断开实例（200 / 404） |
| `POST /api/v1/host-sims/{id}/send` | operator | 以主机角色发 SML（体为 SML 原文，200 / 400 / 404 / 409 / 502） |
| `GET/POST /api/v1/host-sims/{id}/collector` | viewer/operator | 读 S1F3 采集器状态 / 下发采集配置（`startCeid`/`stopCeid`/`intervalMs`/`svids`） |
| `POST /api/v1/host-sims/{id}/collector/{start\|stop}` | operator | 手动启停采集器 |
| `GET/POST /api/v1/host-sims/{id}/burst` | viewer/operator | 读压测状态 / 受理一轮主机报文压测（POST 202、忙 409、参数 400） |
| `POST /api/v1/shutdown` | admin | 优雅停机 |

**不做鉴权**：所有端点直连可调用，没有令牌/账号/登录，也不会回 401/403。
若启动时给了 `--tls-keystore`，上述 `http://` 地址应换成 `https://`（自签证书需客户端信任）。
`EventSource` 不能设置请求头，所以浏览器先 `POST /api/v1/ticket` 换一张
30 秒、一次性的票据再连 SSE（票据只为解决 EventSource 的限制，不是凭据）。

### 访问控制

内部工具定位：**直连访问**。历史上存在过的 `--token` 与 `--auth-users` 已移除，
启动参数里再写它们会直接报"未知参数"。默认只绑回环；需要跨机时显式 `--bind`，
对外部署请用防火墙/网段隔离，必要时加 `--tls-keystore` 保护链路。

审计日志仍记录操作主体（直连访问下为 `local`），用于回答"谁在什么时候改了什么"。

**浏览器侧防护**（不引入账号体系）：

- **Host 白名单**：只接受回环名/IP、`--bind` 地址、本机网卡地址，其余 `Host` 一律 403。
  这是 DNS rebinding 的唯一有效防线（浏览器同源判定看 Host）。用**域名**访问远程服务时，
  把域名加进 `SECS_SIM_ALLOWED_HOSTS`（逗号分隔）或 `-Dsecssim.allowedHosts`。
- **写请求 Origin 校验**：`PUT/POST/DELETE` 带 `Origin` 且不在白名单时，**在执行前**回 403——
  CORS 只决定浏览器能不能读响应，挡不住无正文的跨站简单请求（停机、触发上报）。
  没有 `Origin` 的 curl / 脚本 / 桌面壳首跳不受影响。
- **请求截止时间**：单请求默认 120 秒（`-Dsecssim.http.requestTimeoutMs`），到点关闭连接，
  避免慢速正文长期占住请求线程；SSE 长连接由各自的看门狗负责。

### 会话生命周期

一个进程不再只装一份配置：可以在运行时新建、关闭、按配置重载会话。

```bash
curl -X POST  -d '{"config":"line1.json"}' \
     http://127.0.0.1:PORT/api/v1/sessions
curl -X POST  http://127.0.0.1:PORT/api/v1/sessions/line1/reload
curl -X DELETE http://127.0.0.1:PORT/api/v1/sessions/line1
```

- **`--work-dir` 就是配置目录**。接口只接受**文件名**，不含任何路径分隔符——
  接口给的是"名字"不是"路径"，否则一次 API 调用就能把服务器上任意 JSON 当配置读进来试。
- **一台设备一份配置文件**：文件名为 `<设备名>.json`；工作台「新建设备」只选模板，
  后端自动生成唯一设备名并写 `<设备名>.json`。模板存放在 `<work-dir>/templates/<模板id>.json`，
  创建时会记下顶层 `"template"` 标记，供侧栏按模板分组。
- **重载先编译再动旧会话**：配置有错时旧会话原样跑着，只返回失败。
  一个手滑的逗号不该让设备凭空消失。
- 内置演示会话（`demo`）没有配置来源，重载返回 **409 `not-reloadable`**，而不是笼统的 500。
- 配置没指定端口时会用 `--hsms-port` 的值，因此**多个会话要在配置里各自写明端口**，
  否则第二个会话会撞端口。
- 界面上的「新建 / 重载 / 关闭」与「上报 / 报警」一样要 operator 及以上。
- 会话列表新增 `controlState`：`OFFLINE` / `COMMUNICATING` / `ONLINE`。
  HSMS selected 或 S1F13 后进入 COMMUNICATING，S1F17 接受后 ONLINE，
  S1F15 或主机断开后回到 OFFLINE。这是面向操作的简化模型，不是完整 E5 状态机。

### 配置编辑器

配置可以在界面上读、改、校验、保存，不必回到命令行改文件。

```bash
curl http://127.0.0.1:PORT/api/v1/configs
curl http://127.0.0.1:PORT/api/v1/configs/line1.json
curl -X POST --data-binary @line1.json \
     http://127.0.0.1:PORT/api/v1/configs/line1.json/validate
curl -X PUT  --data-binary @line1.json \
     http://127.0.0.1:PORT/api/v1/configs/line1.json
```

- **编辑器操作的是原始 JSON 文本**，不做模型往返：`toJson` 只认模型里有的字段，
  手写的额外键会被静默丢掉。一个保存动作悄悄改掉用户的配置，比报错糟糕得多。
  保存时逐字节写回，多写的键、空格和缩进都不会被动过。
- **先校验再落盘**：存一份编译不过的配置等于给下一次启动埋雷。
  校验判据和真实启动路径一致——解析 → 编译设备模型 → 建模板图，
  过了才写盘。校验不过时**原文件一个字节都不动**。
- **校验和保存分成两个动作**：想确认改动能不能编译，不必先覆盖一份能用的配置。
- 写盘是**原子的**（先写临时文件再整体替换）：崩在半路不会留下半截 JSON，
  而这份配置正是设备启动时要读的东西。
- **接口只收文件名**，与建会话、列目录同一套规则（`ConfigStore.requireFileName` 是唯一出处）。
- 界面上的「新建」由后端生成骨架：只有一份判据，才不会出现"界面上给的模板起不来"。
- **改配置不会让已在跑的会话自动改变**：会话是启动那一刻把配置读进内存的，
  存完要在「会话」里点**重载**才生效（服务端日志里也会提醒）。
- 保存是 operator 及以上（改配置属于"会改变设备状态"）；读与校验是 viewer。
- **模板可以在原文之上做结构化编辑**：曲线（含 step/pwl 关键帧）、组合模板输出表达式、
  实例的 SVID 绑定与参数都有表单。表单读的是当前原文解析出的 JSON，改完写回整份文本；
  原文不是合法 JSON、或 `templates` 形状不对时表单会禁用并指出字段路径。
  改模板 id 会同步实例引用；删除仍被实例引用的模板会被拒绝。
  **代价**：结构化编辑会把整份文件重排成 2 空格缩进（未知键保留），
  纯文本编辑仍然逐字节保真。

### 报文历史

SSE 只推"现在"。报文除了追加到 `<work-dir>/logs/messages.jsonl`，还会按会话拆成
`logs/devices/<会话>.jsonl`（设备视角，只含该设备进/出报文）与
`logs/hosts/<会话>.jsonl`（主机视角，方向翻转并带 `perspective:"host"`）——现场排障时
直接把对应文件发给设备/主机责任人，不必在混合日志里 grep。页面刷新或断线后可以在
「报文」面板点**载入历史**补齐，也可以导出 JSONL（设备页/主机页各带只导出本实体的按钮）。

```bash
# 最近 100 条，按会话与方向过滤
curl \
     'http://127.0.0.1:PORT/api/v1/messages?session=line1&direction=out&limit=100'

# 直接下载 JSONL
curl -OJ \
     'http://127.0.0.1:PORT/api/v1/messages?format=jsonl&limit=5000'
```

- 一行一个 JSON 对象，字段与 SSE 事件一致（`sessionId/direction/stream/function/wbit/body/at`）。
- 内存保留最近 **5000** 条；启动时只回读文件最后 **4 MiB**，完整历史看文件本身。
- 写日志失败**不影响设备运行**；此时历史接口回 `503 message-log-unavailable`。
- 日志**没有自动轮转**，需要按现场磁盘规划归档或清理。
- 同一个 `--work-dir` 不建议跑多个进程，否则多个进程会追加同一个文件。

### 二进制曲线预览

`GET /api/v1/preview.bin` 与 JSON 预览走同一份计算，只是换成二进制正文：

```bash
curl -o preview.bin \
     'http://127.0.0.1:PORT/api/v1/preview.bin?session=line1&from=0&to=60000&buckets=120'
```

- SCSP v1，小端；48 字节固定头 + 每条序列的 `svid` 与 `min/max/avg` 双精度数组。
- 头部含 `svidTotal` / `seriesCount`，可判断是否截断。
- 完整布局与 Python 读取示例见 [二进制预览格式](docs/guides/preview-binary.md)。
- 界面「曲线预览」里的「下载二进制」按钮使用同一格式。

### 手动发送 SML

设备侧可以手动发一条任意 SML 报文，不用等事件计划或主机请求：

```bash
curl -X POST --data-binary @docs/examples/sml/equip/s5f1-example.sml \
     http://127.0.0.1:PORT/api/v1/sessions/line1/message
```

- 请求体就是 SML 原文，不包 JSON。支持 SxFy 头、`W` 位、`L/A/B/BOOLEAN/I*/U*/F*`；
  不支持 JIS8 / UNICODE / 数组简写，解析失败回 **400 `bad-sml`**（带位置）。
- 没有对端（主机未 select）回 **409 `session-not-open`**；协议层发送失败回 500。
- 带 `W` 时同步等 T3；超时不等于没发出去，响应里 `timedOut=true`、`replySml=null`。
- 成功响应包含规范化后的 `sentSml`、`replySml`、`replyStream/Function/Wbit`、`elapsedMs`。
- 正文上限 64 KiB；只有 operator 及以上能发（会改变设备对主机的行为）。

### OpenAPI

HTTP API 的机器可读契约在 [secs-simulator-service/openapi.yaml](secs-simulator-service/openapi.yaml)（OpenAPI 3.0.3）。
改接口时请同步这个文件；同一个 path 的多个方法写在一个声明里（YAML 不允许重复键）。

### 审计

关键操作会追加到 `<work-dir>/logs/audit.jsonl`（一行一个 JSON），
也可以由 admin 通过接口查看最近记录：

```bash
curl 'http://127.0.0.1:PORT/api/v1/audit?limit=100'
```

- 字段：`at / actor / role / action / target / detail`。
- 覆盖：手动 CEID、报警、会话创建/关闭/重载、配置创建/保存、手动 SML、
  事件编排切换、停机等操作。
- **审计失败不影响业务**；写日志失败只记录一次错误。
- 查询只给 **admin**：审计比报文历史更敏感。
- 没有自动轮转；需要按现场磁盘规划归档。

### 事件编排控制

运行中的编排计划可以列出并临时启用/禁用，不用改配置、不用重载：

```bash
curl http://127.0.0.1:PORT/api/v1/sessions/line1/events
curl -X POST \
     'http://127.0.0.1:PORT/api/v1/sessions/line1/events/demo-process-start?enabled=false'
```

- 这是**运行时状态**，不写回配置；重载会话会按配置文件恢复。
- 禁用会清掉该计划的条件去抖状态与延迟队列条目，**禁用期间的事件不补发**。
- 计划不存在回 404 `unknown-plan`，会话不存在回 404 `unknown-session`。
- 改状态是 operator 及以上；viewer 只能看列表。

### 设备目录

`GET /api/v1/sessions/{id}/catalog` 把"绝对 ID → 语义"的映射摊开：

```json
{"ceids":   [{"ceid":2001,"owner":"CH1","slot":"PROCESS_START",
              "reports":[3001],"source":"local","enabled":true}],
 "reports": [{"rptid":3001,"owner":"CH1","slot":"STATUS",
              "svids":[1090,1001],"source":"local"}],
 "svids":   [{"svid":1001,"owner":"CH1","slot":"TEMP_PV","type":"F4","unit":"C"}],
 "hostReports": 0, "hostLinks": 0, "hostAllDisabled": false}
```

- 主机侧报文里只有数字，而"2001 是哪个事件"只有设备知道；目录让界面上不用再让人猜。
- **`source` 标的是"实际发出去的那一份"**：`host` 表示被主机的 S2F33/S2F35 覆盖过
  （ADR-0006 规定主机优先），`local` 表示仍是本地配置。
- **主机新建、本地没有的 RPTID 也会出现在目录里** —— 它一样会被发出去，
  只列本地配置的话，现场只能对着报文猜设备"哪来的这个报告"。
- `enabled: false` 表示该事件被 S2F37 禁用（直接丢弃、不缓存）。
- 数据直接投影编译后的 `ResolvedEquipment`，与 S1F11/S2F33 应答同源，
  **报告里 SVID 的顺序也与 S6F11 实际组包一致**（`injectUnitId` 会把 `UNIT_ID` 顶到首位）。
- **号段按单元独立**：演示设备的 CH1 起始 CEID 是 2001、CH2 是 2101，
  所以目录必须按会话取，跨设备照搬编号会指错事件。
- 界面用目录里的 CEID 填入上报框；ALID 不在此列——报警不是配置项，是运行时自由编号。
- 目录每 2 秒轮询一次：主机随时可能改定义，只在切换时拉一次会让"来源"列一直停在"本地"。

## 前端

前端是独立的 Vite + React + TypeScript 工程（`secs-simulator-ui/`），构建后由后端同源托管：

```powershell
secs-simulator-ui\scripts\build.ps1        # 产出 secs-simulator-ui/dist
secs-sim --web-root secs-simulator-ui/dist # 与 API 同源，浏览器直接打开
```

开发时用 `npm --prefix secs-simulator-ui run dev`（`vite.config.ts` 已把 `/api` 代理到 `127.0.0.1:5000`）。
前端单元 / 组件测试用 `npm --prefix secs-simulator-ui test`（Vitest；`secs-simulator-ui/scripts/check.ps1` 会跑）。
浏览器 E2E 用 Playwright：

```powershell
cd secs-simulator-ui
npx playwright install chromium
npm run test:e2e
```

- **Mock E2E**：Vite preview + Playwright 路由拦截 API，不启动 Java；
  36 条用例覆盖页面能力与焦点管理 / 密度 / 侧栏折叠等交互契约
  （另有 37 条截图回归默认跳过，见下文）。
- **真实后端 E2E**：先启动 `secs-sim --demo-session`，再指向它：

```powershell
$env:E2E_REAL_URL='http://127.0.0.1:5000'
npm --prefix secs-simulator-ui run test:e2e:real
```

这条真实链路不在模块门禁脚本里：它需要一个已经起来的后端，按上面的命令手动跑
（一键编排见 `secs-simulator-service/tools/host-e2e/run-e2e.ps1`）。

### 界面结构

界面是**左侧导航 + 单功能页面**：工作台(总览 + 设备与会话)、每台设备一组子页、主机模拟(独立入口)、管理页。
左侧是固定的深色「机箱」——静态入口在上、设备(动态)在中、管理入口在下；
`[` 可收成图标栏，设备项变成两字母缩写 + 状态色。

- **设备页**：一条设备头(设备名/会话/状态/单元数) + 一行子页标签，子页一页一能力。
  **事件**与**报告**拆成两页：事件只定义 CEID 及绑定报告，报告只定义 RPTID 及变量，
  弹框里都从已有数据点选，不再手填 ID 列表。子页内容不重复显示标题/说明，
  说明挂在页签的悬浮提示上。
  结构化配置不再挤在「配置」页：**连接**页逐项列出 session（端口/地址/device-id/超时）并解释每个属性的作用；
  **结构**页拆成「设备全局 / 单元 / 模板」三个页签：全局只留号段规则与设备常量（平铺无折叠），
  单元只留 unitId/名称/是否注入 UNIT_ID，模板页单独编辑曲线/组合/实例并提供「选模板 + 勾 SVID 一键生成实例」。
  **配置**页只保留 JSON 原文，支持语法高亮与一键格式化，
  默认占满窗口剩余高度、内容超出时内部滚动，逐字节写回，覆盖所有字段。
  「消息」子页实时展示本设备与主机的收发报文，可按探测 / 采集 / Trace / 事件 / 通信 / 报警 / 终端
  逐类屏蔽；列表可拖拽放大（上下左右）、默认保留 2000 行，支持导出/导入 JSONL。
  「状态变量」只保留逐变量预览：一个参数一条曲线，
  事件竖线按计划分色，回放会带上窗口之前仍在生效的事件效果。
- **主机模拟**：顶层入口。**一台设备只允许一个本机主机**，新建/断开只在「主机实例」页；
  其它四个子页只能选择已有实例。
  ① **消息发送**可多选主机，把同一条 SML 依次发出去并逐台显示回复与耗时；
  同一条报文还能**压测连发**（次数 + 间隔，服务端后台执行、统计成功/失败与 P50/P95）；
  内置 16 条常用预设（心跳/建链/上线/下线、S1F3/S1F11/S2F13/S2F17、报告/事件、Trace、终端），
  每条都带用途说明和正常回复，选中只是填入模板，仍可改 ID/数值再发。
  收发记录不再挤在发送页，独立成**消息监控**页。
  ② **S6F1 采集** 的配置入口按主机类型分流：**本机设备**用表单（TRID / DSPER / TOTSMP / REPGSZ）
  + 变量多选（不限数量，上限交给设备的 `session.trace.maxSvidCount`）；
  **远程主机**没有本地 catalog，整条 S2F23 手填。
  **S6F1 只在设备 ONLINE 后上报**：通信中时下发只回 S2F24、没有帧，页面会提示并提供一键 S1F17 上线 / S1F15 下线；
  默认「每条样本数=1」，下发后约一个采样周期就能看到帧。
  ③ **S1F3 采集** 同样分流：本机设备多选 SVID（不限数量），远程主机手填 SVID 列表，
  并可手动发一条整报文 S1F3 做一次性验证；每个 SVID 一张带坐标轴的大图，
  支持框选缩放、滚轮缩放与双击复位。
  **消息监控**（第五个子页）把所有主机实例的收发实时汇总：本机主机的报文由设备会话发布，
  **远程主机的报文由主机实例自己采集**（设备侧看不见，历史上只能去对端日志里找）；
  可按主机 / 方向 / SxFy / 报文体关键字过滤，方向与设备页相反（主机视角），支持导出过滤结果。
- **事件驱动曲线**：曲线与事件标记同源——后端把事件效果层叠进值来源再算图，
  所以事件触发的跳变和「发送事件」的竖线对得上。事件编排列表每行可「预览」单条计划
  （排除其它计划干扰）；全量周期预览按计划分色，图例与竖线颜色一致。
  效果编辑以表格呈现，一行一条效果，弹框可四边拖拽放大。
- **图表交互**：单变量图支持拖动框选放大、滚轮缩放时间轴、Shift+滚轮平移、
  Ctrl+滚轮纵向缩放、双击复位，并提供 ＋/－/复位按钮；事件周期预览的图例可随时收起，
  时间窗可选到 30 分钟。
- **W 位默认**：系统生成的 primary 与手动 SML 的奇数 function 默认带 W（secondary 不允许带 W）。
  设备的 S6F11/S6F1 配置默认 `true`，S5F1 始终带 W；主机模拟自动回 S1F2/S5F2/S6F12/S6F2/S10F4，
  设备对主机发来的 W primary 也都回对应 secondary。不需要回复的 secondary 不会误加 W。
- **工作台**：新建设备只需选模板，自动生成唯一设备名（即 `<设备名>.json`）、端口与 Device ID；
  勾选多台后可「批量同步配置」——以第一台为源，只改会话 id/端口/设备号/名称，其余配置保持一致。
  工作台的批量上报与 CEID 输入已移除，上报统一走设备页。
- **模板管理**（顶层入口）：内置模板只读，可从任意模板复制出自定义模板并直接改 JSON；
  自定义模板存放在 `<work-dir>/templates/<id>.json`，支持新增、编辑、删除。
- **侧栏设备**：支持按名称/模板筛选；可一键切换「平铺 / 按模板分组」。
  分组后是三级菜单：模板 → 设备 → 设备子页，模板组和设备子页都可展开/收起。
- **弹框**：所有操作弹框（新增/编辑/确认/批量/远程主机）四边四角可拖拽调整大小；
  字段较少的表单（事件/报告）一栏一行。
- **命令面板**(`Ctrl+K`)：跳页面、打开某台设备的某个子页、执行常用动作。
- **快捷键**：数字 `1..9`/`0` 跳静态入口，`R` 刷新，`/` 聚焦搜索框，`?` 看快捷键表。
- **提示条**：操作结果弹在右下角；浮层带焦点圈定，关闭后焦点还给原来的按钮。
- **通用表格**：搜索、点列头排序、拖列宽、导出 CSV，全站一套。可编辑表（会话 / 主机实例 / SVID /
  事件 / 报告）在导出旁都有「导入 CSV」；配置支持导入/导出 JSON，消息支持导入/导出 JSONL，
  S1F3 采集支持 CSV 离线看图，审计 CSV 可导入只读查看。
- **主题与密度**：浅色 / 深色 / 跟随系统，紧凑 / 宽松两档；主题在首帧前就定好，不会闪白。

曲线预览有三种渲染模式，切换不会重新请求后端：

- **Canvas**：轻量、所有 SVID 共享缩放区间，适合快速扫趋势；
- **uPlot**：每条曲线独立图例（点击可隐藏 min/max/avg）、框选缩放、双击复位，
  适合逐条细看；
- **多轴**：所有 SVID 的 avg 画在一张图里，按 `unit` 分配 y 轴（左右交替），
  适合同时看温度/压力/流量等不同量纲。

图下方另有**窗口统计表**（min/max/avg/末值/峰峰）：图能看出形状，看不出数值量级，
值班时先扫一眼统计表，再决定细看哪条线。

页面清单：工作台（KPI 读数条 + 「设备与会话」一张表，读数与生命周期操作同行）、设备页十个子页
（事件、报告、事件编排、报警、状态变量、连接、结构、配置 JSON、发送、消息监视）、
主机模拟（实例 / 消息发送（含压测） / S6F1 采集 / S1F3 采集 / 消息监控）、模板管理、审计。
运行时的增删改不写回配置，重载会话后按配置恢复；配置层的修改在「连接」「结构」或「配置」页保存，
相关会话自动重载。

前端源码按职责分层：

| 目录 | 内容 |
|---|---|
| `src/app/` | 入口 `main.tsx` / `App.tsx` / `App.css`、`router.ts`、`layout/`（侧栏、顶栏、导航） |
| `src/api/` | HTTP 契约与客户端：`types` `base` `sessions` `configs` `messages` `hostsim` + `index`；`src/api.ts` 只做转发 |
| `src/features/` | `pages/` `panels/`（含 hostsim）`charts/` `editors/`（模板与结构化表单） |
| `src/shared/` | `ui/`（通用控件）、`lib/`（纯函数，可单测） |
| `src/state/` | 轮询、SSE、偏好、通知、各域 hook（`useBackend` 只做装配） |
| `src/styles/` | `tokens / base / controls / layout / panels / responsive` |

样式同样分层，收敛在 `src/styles/`（入口 `src/app/App.css` 只做 @import）：
`tokens.css` 是唯一的调色板与尺度来源（深浅两套令牌），`base / controls / layout / panels / responsive`
各管一层，`panels.css` 再按主题转发到 `panels/*.css`。组件里不写死颜色和间距——
换主题只是换一层变量，canvas 曲线也从令牌取色。
决策与约束见 [ADR 0016](docs/adr/0016-layout-and-layer-consolidation.md)。

截图回归：`SHOTS=1 npx playwright test e2e/shots.spec.ts` 会把浅色/深色/窄屏各页写进
`secs-simulator-ui/shots/redesign/`，用来比对视觉改动（默认跳过，不参与断言）。

界面的完整使用说明（启动登录、每个面板的操作、常见问题）见
**[用户手册](docs/guides/user-guide.md)**。

## 打包

只有一种发布形态入口，全仓唯一打包脚本 `scripts/package.ps1`（会先自动构建前端）：

```powershell
# 两个安装包都打，产物落在 scripts/out/
powershell -ExecutionPolicy Bypass -File scripts/package.ps1

# 只打其一
powershell -ExecutionPolicy Bypass -File scripts/package.ps1 -Target Windows
powershell -ExecutionPolicy Bypass -File scripts/package.ps1 -Target Linux
```

- **Windows 安装程序** `scripts/out/SECS Simulator_<版本>_x64-setup.exe`：打开直接进界面
  （前端内嵌在壳里），默认启动内置的 `secs-sim.exe`（原生镜像，无需 JVM）连本机；
  右上角「连接」可随时切到远程服务端（本机后端自动停止），留空切回本机，选择会记住。
- **Linux 服务端** `scripts/out/secs-sim-server-<版本>-linux.zip`：部署在设备所在的
  Linux 机器，`./start-server.sh` 以 server 模式对外并托管 web，被 Windows 端远程操作。
- 构建需要 JDK 25（推荐 GraalVM 25，自带 `native-image`）+ Node；安装程序另需 Rust
  工具链（`tauri build` 用）。`-SkipNative` / `-SkipJars` / `-SkipFrontend` 可复用已有产物。

## 能力边界

主机侧对接时常会问"你们支持到哪一步"。下面这些是**有意不做**或**只做了协议面**的，
写清楚是为了避免现场按"支持"理解：

| 能力 | 现状 | 说明 |
|---|---|---|
| S2F41/F42 远程命令 | 结构完整，**不做设备动作** | 按 E30 执行条件回 HCACK（未 ONLINE 回 4、ONLINE 回 0、RCMD 非法回 1），CPACK 恒空。模拟器没有真实 START/STOP 语义，不会改变任何状态 |
| S2F13–F16 设备常量（ECV） | 已实现，**可写并落盘** | 常量在 `equipment.constants` 声明；S2F15 写入后立即写回配置文件（原子替换），重载/重启后保留。写入需 ONLINE；未知 ECID 或越界整批拒绝，不做部分写入 |
| 未知 SVID 的 S1F3/F4 取值 | 恒回 `U4 0` | 类型可能与主机的 SVID 表不一致；S1F11 里名字给 `UNKNOWN` 可区分 |
| 报警 ALTX | 仅 US-ASCII | 入口即拒（400），不会出现"接口成功但主机收不到" |
| 未建立通信时的主动上报 | 不发 | S6F11/S6F1 只在 ONLINE 之后发送；手动"上报"按钮是操作员显式动作，不受此限 |
| Spooling（S2F43–F48） | 未实现 | 不补发；断线期间的事件不缓存 |
| 单会话 Trace | 默认 1 条、每条 ≤10 SVID | 可在 `session.trace` 配置段调大（`maxTraces` / `maxSvidCount`），改后走重建重载；多条需按 TRID 分别点播 |

## 四条铁律

1. **`secs-protocol` 只依赖 JDK**，任何新代码都不得反向依赖它，也不得修改它。
   例外只有一种：**对照 E4/E5/E37.1 原文确认为实现缺陷**（不是风格问题），
   且必须在提交信息里写清条文依据。已发生的例外：
   [应答匹配只接受 secondary](docs/reports/code-review-2026-10-20.md)（E37.1 的应答判定要求
   "同 SystemBytes + 对应 secondary"）；
   [S9F7 非法数据应答](docs/reports/audit-2026-10-29.md)（E5：Stream/Function 可识别
   但数据无法解析时设备必须回 S9F7，不再静默丢帧；见该文档 §12.7）；
   [协议层 SML 入口摘除](docs/adr/0014-protocol-sml-retired.md)（公共 API 收窄：删除
   零调用方的 `send(SmlMessage)` 入口，不动报文编解码逻辑）。
2. **禁止 `--enable-preview`**。预览特性会把 class 文件绑死到具体 JDK，且与 native-image 不兼容。
3. **曲线是时间的纯函数**：不允许在曲线内部累加状态（见 [ADR 0003](docs/adr/0003-pure-curves.md)）。
4. **模板图是纯函数图**：`Template.evaluate` 不得有副作用；一个 SVID 只能有一个生产者（见 [ADR 0004](docs/adr/0004-template-dag.md)）。

## 文档

完整目录与分区见 [docs/README.md](docs/README.md)（guides / adr / reports / examples / reference）。

- [ADR 0001 · 地基决策](docs/adr/0001-foundation.md)
- [ADR 0002 · 领域模型与 ID 空间](docs/adr/0002-domain-and-idspace.md)
- [ADR 0003 · 曲线必须是纯函数](docs/adr/0003-pure-curves.md)
- [ADR 0004 · 模板 DAG 与受限表达式](docs/adr/0004-template-dag.md)
- [ADR 0005 · 事件调度与设备级限流](docs/adr/0005-event-orchestration.md)
- [ADR 0006 · 主机动态定义报告](docs/adr/0006-dynamic-reports.md)
- [ADR 0008 · 配置编辑器走原始 JSON 文本](docs/adr/0008-config-editor-raw-text.md)
- [ADR 0009 · 报文落盘用 JSON Lines](docs/adr/0009-message-log.md)
- [ADR 0010 · 手动发送 SML 只实现受控子集](docs/adr/0010-manual-sml.md)
- [ADR 0011 · 事件编排的运行时启用/禁用](docs/adr/0011-event-runtime-control.md)
- [ADR 0012 · 审计日志与业务日志分离](docs/adr/0012-audit-log.md)
- [ADR 0013 · HTTP 请求处理改用虚拟线程](docs/adr/0013-http-virtual-threads.md)
- [ADR 0014 · 协议层 SML 实现下线](docs/adr/0014-protocol-sml-retired.md)
- [ADR 0015 · 补 CI；Jetty/SQLite/签名/Spooling 暂不做](docs/adr/0015-ci-and-deferred-upgrades.md)
- [ADR 0016 · 目录与分层收敛](docs/adr/0016-layout-and-layer-consolidation.md)
- [结构收敛验收报告 2026-11-09](docs/reports/refactor-2026-11-09.md)
- [3.0.1 发布说明](docs/reports/release-notes-3.0.1.md) — 缺陷修复版：Trace 重连停发、重载失败丢运行态编辑、界面轮询与导入假成功
- [3.0.0 发布说明](docs/reports/release-notes-3.0.0.md)
- [2.0.1 发布说明](docs/reports/release-notes-2.0.1.md)
- [2.0.0 发布说明](docs/reports/release-notes-2.0.0.md)
- [OpenAPI 3.0 契约](secs-simulator-service/openapi.yaml)
- [二进制曲线预览格式](docs/guides/preview-binary.md)
- [M0](docs/reports/m0-report.md) · [M1](docs/reports/m1-report.md) · [M2](docs/reports/m2-report.md) · [M3](docs/reports/m3-report.md) · [M4](docs/reports/m4-report.md) · [M4-b](docs/reports/m4b-report.md) · [M5](docs/reports/m5-report.md) · [M6](docs/reports/m6-report.md) · [M7](docs/reports/m7-report.md) · [M6-b](docs/reports/m6b-report.md) · [M6-c](docs/reports/m6c-report.md) · [M8-a](docs/reports/m8a-report.md) · [M8-b](docs/reports/m8b-report.md) · [M8-c](docs/reports/m8c-report.md) · [M8-d](docs/reports/m8d-report.md) · [M8-e](docs/reports/m8e-report.md) · [M9](docs/reports/m9-report.md) · [M9-b / M9-c](docs/reports/m9b-report.md)
- [全量代码评审 2026-10-17](docs/reports/code-review-2026-10-17.md)
- [全量代码评审 2026-10-20（协议符合性专项）](docs/reports/code-review-2026-10-20.md)
- [全量代码评审 2026-10-29（五模块复核 + 整改路线）](docs/reports/audit-2026-10-29.md)
- [实机全量测试 2026-10-30（原生 exe + 真实主机 + 浏览器）](docs/reports/host-e2e-2026-10-30.md)
- [SECS/GEM 标准参考资料](docs/reference/README.md)（E4/E5/E30/E37 中文整理 + 项目对照）
- [未完成清单 2026-10-17](docs/reports/unfinished-2026-10-17.md)
- [配置格式 Schema v5](docs/guides/schema-v5.md)（v4 仍兼容）
- [配置格式 Schema v4](docs/guides/schema-v4.md)
- [SVID 值变化机制与规则](docs/guides/svid-value-model.md)（默认曲线 / 模板图 / 事件效果 / 钉值的优先级与边界）