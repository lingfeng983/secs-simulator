# secs-simulator

SECS/GEM 多单元（multi-unit）设备模拟器。

**一套内核、两种形态**：既可单机一体化运行，也可作为 Linux 服务端供远程客户端访问；
两种形态共用同一份内核、同一套 HTTP/SSE API、同一份前端。

---

## 模块

| 模块 | 职责 | 依赖约束 |
|---|---|---|
| `secs-json` | 无反射 JSON 读写 | 仅依赖 JDK |
| `secs-protocol` | HSMS-SS / SECS-I / SECS-II / SML / GEM 报文层（**冻结**，极少改动） | 仅依赖 JDK |
| `secs-core` | 领域模型、ID 规划、曲线、值引擎、编排（无 I/O、无 JSON） | 仅依赖 JDK |
| `secs-app` | 应用服务：会话、配置、日志 | core + json |
| `secs-api-http` | REST + SSE + JSON 曲线预览 | app + json + `com.sun.net.httpserver` |
| `secs-runtime` | 装配与生命周期：INTEGRATED / SERVER / PROBE；会话的协议实现 | 以上全部 |
| `secs-cli` | 端到端测试宿主 | runtime |

依赖方向由 `ArchitectureTest` 强制，越界即构建失败。

## 构建

需要 JDK 25（推荐 GraalVM 25，自带 `native-image`）。

```powershell
$env:JAVA_HOME = 'E:\Java\graalvm-jdk-25.0.4+7.1'
mvn -B verify
mvn -B -Pnative verify       # 原生镜像 + NativeRuntimeIT（需先构建 frontend/dist）
```

`-Pnative` 会构建原生镜像并在 `verify` 阶段用 failsafe 跑
`NativeRuntimeIT`（API、SSE ticket、HSMS S1F1、S1F3、优雅停机）。
CI 的 `native` job 每周/手动运行同一命令。

里程碑验收脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/accept-M1.ps1
```

## 运行

同一个可执行文件，三种形态：

```powershell
# 一体化（桌面壳调用）：只绑回环，自动生成 token
secs-sim --mode=integrated

# 带一个两腔体演示会话：主机可以连上来问 S1F3
secs-sim --mode=integrated --demo-session --hsms-port 5000

# 从配置文件装载设备（格式见 docs/schema-v5.md）
secs-sim --mode=integrated --config ./device.json --hsms-port 5000

# 同时托管前端（先构建，见下）：浏览器直接打开 READY 行里的 http 地址
scripts\build-frontend.ps1
secs-sim --mode=integrated --demo-session --web-root frontend/dist

# 服务端：对外绑定，凭据必填（--token 或 --auth-users 二选一）
# 提供 keystore 时走 HTTPS
secs-sim --mode=server --bind 0.0.0.0 --port 8443 --token $env:SECS_TOKEN `
    --tls-keystore ./server.p12 --tls-password $env:SECS_TLS_PASSWORD

# 多账号分级：viewer 只读 / operator 可下发 / admin 可停机
secs-sim --mode=server --bind 0.0.0.0 --port 8080 --auth-users ./users.json

# 没有 --tls-keystore 时仍是明文 HTTP，建议置于 TLS 反向代理后
secs-sim --mode=server --bind 0.0.0.0 --port 8080 --token $env:SECS_TOKEN

# keystore 示例：
# keytool -genkeypair -alias secs-sim -keyalg RSA -storetype PKCS12 `
#   -keystore server.p12 -storepass changeit -dname CN=your-host -validity 3650

# 能力探针：开 HSMS 被动端口应答 S1F1，用于现场排障与 CI
secs-sim --mode=probe --hsms-port 5000
```

启动后向 stdout 打印一行握手，供桌面壳与脚本读取：

```
READY mode=integrated http=53466 token=<64位十六进制> accounts=1 utf8=true sessions=1 hsms=5000 work=C:\Users\...\SecsSimulator
```

`token` 只在单令牌模式下有值；用 `--auth-users` 时它是空的，`accounts` 是账号数。

HTTP API（v1），“角色”一列是**最低**角色，`admin` 可做 `operator` 与 `viewer` 的事：

| 端点 | 角色 | 说明 |
|---|---|---|
| `GET /api/v1/health` | 免鉴权 | 存活与 uptime；带凭据时才返回版本、形态、会话数、`user`/`role` |
| `POST /api/v1/ticket` | viewer | 换取一次性 SSE 票据（30 秒有效、只能用一次），并回显身份与角色 |
| `GET /api/v1/events?ticket=` | 票据 | SSE 事件流；票据从上一个端点取 |
| `GET /api/v1/sessions` | viewer | 会话列表（状态 / 设备 / 单元数 / 计数器） |
| `POST /api/v1/sessions` | operator | 新建会话，体为 `{"config":"demo"}` 或 `{"config":"设备.json"}`（201 / 400 / 409） |
| `POST /api/v1/sessions/bulk` | operator | 批量新建：一份配置模板 + 数量/前缀/起始端口/device-id，逐台独立创建（201 全成、207 部分失败并逐台给原因） || `DELETE /api/v1/sessions/{id}` | operator | 关闭并移除会话，端口立即解绑（200 / 404） |
| `POST /api/v1/sessions/{id}/reload` | operator | 按配置热重载（200 / 404 / 409 / 400） |
| `POST /api/v1/sessions/{id}/ceid/{ceid}` | operator | 手动触发一次 S6F11（202 / 404 / 409） |
| `POST /api/v1/sessions/{id}/alarm/{alid}?set=&text=&category=` | operator | 置位/清除 S5F1；`set` 默认 true，`category` 0..7（202 / 400 / 404 / 409） |
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
| `GET /api/v1/audit` | admin | 最近审计记录（`limit` 默认 200，最大 1000） |
| `POST /api/v1/shutdown` | admin | 优雅停机 |

鉴权只用 `Authorization: Bearer <口令>`。未认证回 401，认证通过但角色不够回 403
（两者必须区分，否则"口令打错了"和"权限不够"在现场没法排查）。
若启动时给了 `--tls-keystore`，上述 `http://` 地址应换成 `https://`（自签证书需客户端信任）。
`EventSource` 不能设置请求头，所以浏览器先 `POST /api/v1/ticket` 换一张
30 秒、一次性的票据再连 SSE。打包脚本打开的界面地址形如
`http://127.0.0.1:<port>/#token=<token>`，token 放在 hash 里，不会发给服务端。

### 账号与角色

`--token` 之外可以给 `--auth-users <path>`，按账号分级。两种凭据**二选一**，
同时给出时按账号文件走并打日志——悄悄忽略一个用户明确写了的参数比报错更难查。

```json
{"users": [
  {"name": "guest", "role": "viewer",   "secret": "..."},
  {"name": "line1", "role": "operator", "secret": "..."},
  {"name": "owner", "role": "admin",    "secret": "..."}
]}
```

- 分级依据是"这个操作会不会改变设备状态"：读不会，触发 CEID 与报警会，关停会。
- 账号名为空、口令为空、账号名或口令重复、角色拼错都会在**启动阶段**报错退出，不会带着半个鉴权跑起来；
  错误信息带 `$.users[0].role` 这样的字段路径。
- 口令在文件里是**明文**：服务端要在运行时比对，没法只存哈希。
  账号文件与私钥同级，别进版本库、别打进分发包。
- 角色常量按能力递增排列，**调整顺序等于给已部署的账号集体降级/升级**，不要重排。

### 会话生命周期

一个进程不再只装一份配置：可以在运行时新建、关闭、按配置重载会话。

```bash
curl -X POST  -H 'Authorization: Bearer T' -d '{"config":"line1.json"}' \
     http://127.0.0.1:PORT/api/v1/sessions
curl -X POST  -H 'Authorization: Bearer T' http://127.0.0.1:PORT/api/v1/sessions/line1/reload
curl -X DELETE -H 'Authorization: Bearer T' http://127.0.0.1:PORT/api/v1/sessions/line1
```

- **`--work-dir` 就是配置目录**。接口只接受**文件名**，不含任何路径分隔符——
  接口给的是"名字"不是"路径"，否则一次 API 调用就能把服务器上任意 JSON 当配置读进来试。
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
curl -H 'Authorization: Bearer T' http://127.0.0.1:PORT/api/v1/configs
curl -H 'Authorization: Bearer T' http://127.0.0.1:PORT/api/v1/configs/line1.json
curl -X POST -H 'Authorization: Bearer T' --data-binary @line1.json \
     http://127.0.0.1:PORT/api/v1/configs/line1.json/validate
curl -X PUT  -H 'Authorization: Bearer T' --data-binary @line1.json \
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

SSE 只推"现在"。报文同时追加到 `<work-dir>/logs/messages.jsonl`，
页面刷新或断线后可以在「报文」面板点**载入历史**补齐，也可以导出 JSONL。

```bash
# 最近 100 条，按会话与方向过滤
curl -H 'Authorization: Bearer T' \
     'http://127.0.0.1:PORT/api/v1/messages?session=line1&direction=out&limit=100'

# 直接下载 JSONL
curl -H 'Authorization: Bearer T' -OJ \
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
curl -H 'Authorization: Bearer T' -o preview.bin \
     'http://127.0.0.1:PORT/api/v1/preview.bin?session=line1&from=0&to=60000&buckets=120'
```

- SCSP v1，小端；48 字节固定头 + 每条序列的 `svid` 与 `min/max/avg` 双精度数组。
- 头部含 `svidTotal` / `seriesCount`，可判断是否截断。
- 完整布局与 Python 读取示例见 [二进制预览格式](docs/preview-binary.md)。
- 界面「曲线预览」里的「下载二进制」按钮使用同一格式。

### 手动发送 SML

设备侧可以手动发一条任意 SML 报文，不用等事件计划或主机请求：

```bash
curl -X POST -H 'Authorization: Bearer T' --data-binary @templates/sml/equip/s5f1-example.sml \
     http://127.0.0.1:PORT/api/v1/sessions/line1/message
```

- 请求体就是 SML 原文，不包 JSON。支持 SxFy 头、`W` 位、`L/A/B/BOOLEAN/I*/U*/F*`；
  不支持 JIS8 / UNICODE / 数组简写，解析失败回 **400 `bad-sml`**（带位置）。
- 没有对端（主机未 select）回 **409 `session-not-open`**；协议层发送失败回 500。
- 带 `W` 时同步等 T3；超时不等于没发出去，响应里 `timedOut=true`、`replySml=null`。
- 成功响应包含规范化后的 `sentSml`、`replySml`、`replyStream/Function/Wbit`、`elapsedMs`。
- 正文上限 64 KiB；只有 operator 及以上能发（会改变设备对主机的行为）。

### OpenAPI

HTTP API 的机器可读契约在 [docs/openapi.yaml](docs/openapi.yaml)（OpenAPI 3.0.3）。
CI 会用 `openapi-spec-validator` 校验；改接口时请同步这个文件。

### 审计

关键操作会追加到 `<work-dir>/logs/audit.jsonl`（一行一个 JSON），
也可以由 admin 通过接口查看最近记录：

```bash
curl -H 'Authorization: Bearer T' 'http://127.0.0.1:PORT/api/v1/audit?limit=100'
```

- 字段：`at / actor / role / action / target / detail`。
- 覆盖：手动 CEID、报警、会话创建/关闭/重载、配置创建/保存、手动 SML、
  事件编排切换、停机，以及角色不足的 403 拒绝。
- **审计失败不影响业务**；写日志失败只记录一次错误。
- 查询只给 **admin**：审计比报文历史更敏感。
- 没有自动轮转；需要按现场磁盘规划归档。

### 事件编排控制

运行中的编排计划可以列出并临时启用/禁用，不用改配置、不用重载：

```bash
curl -H 'Authorization: Bearer T' http://127.0.0.1:PORT/api/v1/sessions/line1/events
curl -X POST -H 'Authorization: Bearer T' \
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

前端是独立的 Vite + React + TypeScript 工程（`frontend/`），构建后由后端同源托管：

```powershell
scripts\build-frontend.ps1                 # 产出 frontend/dist
secs-sim --web-root frontend/dist          # 与 API 同源，浏览器直接打开
```

开发时用 `pnpm --dir frontend dev`（`vite.config.ts` 已把 `/api` 代理到 `127.0.0.1:5000`）。
前端单元 / 组件测试用 `npm --prefix frontend test`（Vitest；CI 会跑）。
浏览器 E2E 用 Playwright：

```powershell
cd frontend
npx playwright install chromium
npm run test:e2e
```

- **Mock E2E**：Vite preview + Playwright 路由拦截 API，不启动 Java；
  CI 的 `frontend-e2e` job 跑 9 条用例。
- **真实后端 E2E**：先启动 `secs-sim --demo-session`，再指向它：

```powershell
$env:E2E_REAL_URL='http://127.0.0.1:5000'
$env:E2E_REAL_TOKEN='e2e-token'
npm --prefix frontend run test:e2e:real
```

CI 的 `frontend-e2e-real` job 会构建后端、启动 demo 会话，再跑这条真实链路。

### 界面结构

界面是**侧边栏导航 + 单面板显示**：十个面板按「设备 / 数据 / 工具」分组，
一次只显示一个，切过去就立刻拉一次新数据——不再九个面板纵向堆叠、越滚越长。

- **命令面板**（`Ctrl+K`）：跳面板、找目录里的 CEID/SVID、执行常用动作。
- **数字键 `1..9` / `0`**：直接跳到第 N 个面板；`R` 立即刷新；`/` 聚焦搜索框；`?` 看快捷键表。
- **提示条**：操作结果直接弹在右下角（「事件流」面板退居事后查账）。
- **通用表格**：会话、报警、目录、审计、报文都能搜索、点列头排序、导出 CSV。
- **报文面板**：按会话 / 方向 / 正文过滤，可暂停接收（服务端照收，界面先攒着）。
- **主题与密度**：浅色 / 深色 / 跟随系统，紧凑 / 宽松两档；当前面板与这些偏好跨刷新保留。

曲线预览有三种渲染模式，切换不会重新请求后端：

- **Canvas**：轻量、所有 SVID 共享缩放区间，适合快速扫趋势；
- **uPlot**：每条曲线独立图例（点击可隐藏 min/max/avg）、框选缩放、双击复位，
  适合逐条细看；
- **多轴**：所有 SVID 的 avg 画在一张图里，按 `unit` 分配 y 轴（左右交替），
  适合同时看温度/压力/流量等不同量纲。

图下方另有**窗口统计表**（min/max/avg/末值/峰峰）：图能看出形状，看不出数值量级，
值班时先扫一眼统计表，再决定细看哪条线。

面板清单：会话表（状态/控制状态/计数）、活动报警与终端消息、**事件编排控制**、
曲线预览（Canvas / uPlot / 多轴切换、悬停读数、拖动放大、截断提示、二进制下载、窗口统计）、
设备目录（CEID/RPTID/SVID）、报文面板（SSE 实时推送 + 历史载入 / JSONL 导出 + 过滤 + 暂停）、
**手动发送 SML**、**配置编辑器**（全段结构化表单 / JSON 原文 / 校验 / 保存 / 新建）、
**审计（admin）**、事件流日志。

前端源码按职责分层：`src/state/`（轮询、SSE、偏好、通知）、`src/panels/`（面板）、
`src/ui/`（通用控件）、`src/charts/`（图表）、`src/lib/`（纯函数，可单测）。

界面的完整使用说明（启动登录、每个面板的操作、常见问题）见
**[用户手册](docs/user-guide.md)**。

## 打包

```powershell
scripts\build-frontend.ps1     # 前端产物
scripts\package.ps1            # 原生镜像 + 组装 + zip
```

- 产物：`package/secs-sim-<version>-win-x64.zip`，内含 `secs-sim.exe`、`web/`、
  `start.cmd`/`start.ps1`、`README.txt`、配置格式说明与 `VERSION`。
- 双击 `start.cmd` 即用，目标机器无需安装 JVM。
- 构建需要 JDK 25（推荐 GraalVM 25，自带 `native-image`）；`package.ps1` 会调用
  `mvn -Pnative` 生成原生镜像，可用 `-SkipBuild` 复用已有的 `secs-cli/target/secs-sim.exe`。

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
| 单会话 Trace | 1 条、每条 ≤10 SVID | 多条需按 TRID 分别点播，容量上限见配置校验 |

## 四条铁律

1. **`secs-protocol` 只依赖 JDK**，任何新代码都不得反向依赖它，也不得修改它。
   例外只有一种：**对照 E4/E5/E37.1 原文确认为实现缺陷**（不是风格问题），
   且必须在提交信息里写清条文依据。已发生的例外：
   [应答匹配只接受 secondary](docs/code-review-2026-10-20.md)（E37.1 的应答判定要求
   "同 SystemBytes + 对应 secondary"）。
2. **禁止 `--enable-preview`**。预览特性会把 class 文件绑死到具体 JDK，且与 native-image 不兼容。
3. **曲线是时间的纯函数**：不允许在曲线内部累加状态（见 [ADR 0003](docs/adr/0003-pure-curves.md)）。
4. **模板图是纯函数图**：`Template.evaluate` 不得有副作用；一个 SVID 只能有一个生产者（见 [ADR 0004](docs/adr/0004-template-dag.md)）。

## 文档

- [ADR 0001 · 地基决策](docs/adr/0001-foundation.md)
- [ADR 0002 · 领域模型与 ID 空间](docs/adr/0002-domain-and-idspace.md)
- [ADR 0003 · 曲线必须是纯函数](docs/adr/0003-pure-curves.md)
- [ADR 0004 · 模板 DAG 与受限表达式](docs/adr/0004-template-dag.md)
- [ADR 0005 · 事件调度与设备级限流](docs/adr/0005-event-orchestration.md)
- [ADR 0006 · 主机动态定义报告](docs/adr/0006-dynamic-reports.md)
- [ADR 0007 · API 账号与角色分级](docs/adr/0007-api-roles.md)
- [ADR 0008 · 配置编辑器走原始 JSON 文本](docs/adr/0008-config-editor-raw-text.md)
- [ADR 0009 · 报文落盘用 JSON Lines](docs/adr/0009-message-log.md)
- [ADR 0010 · 手动发送 SML 只实现受控子集](docs/adr/0010-manual-sml.md)
- [ADR 0011 · 事件编排的运行时启用/禁用](docs/adr/0011-event-runtime-control.md)
- [ADR 0012 · 审计日志与业务日志分离](docs/adr/0012-audit-log.md)
- [OpenAPI 3.0 契约](docs/openapi.yaml)
- [二进制曲线预览格式](docs/preview-binary.md)
- [M0](docs/m0-report.md) · [M1](docs/m1-report.md) · [M2](docs/m2-report.md) · [M3](docs/m3-report.md) · [M4](docs/m4-report.md) · [M4-b](docs/m4b-report.md) · [M5](docs/m5-report.md) · [M6](docs/m6-report.md) · [M7](docs/m7-report.md) · [M6-b](docs/m6b-report.md) · [M6-c](docs/m6c-report.md) · [M8-a](docs/m8a-report.md) · [M8-b](docs/m8b-report.md) · [M8-c](docs/m8c-report.md) · [M8-d](docs/m8d-report.md) · [M8-e](docs/m8e-report.md) · [M9](docs/m9-report.md) · [M9-b / M9-c](docs/m9b-report.md)
- [全量代码评审 2026-10-17](docs/code-review-2026-10-17.md)
- [全量代码评审 2026-10-20（协议符合性专项）](docs/code-review-2026-10-20.md)
- [SECS/GEM 标准参考资料](docs/reference/README.md)（E4/E5/E30/E37 中文整理 + 项目对照）
- [未完成清单 2026-10-17](docs/unfinished-2026-10-17.md)
- [配置格式 Schema v5](docs/schema-v5.md)（v4 仍兼容）
- [配置格式 Schema v4](docs/schema-v4.md)