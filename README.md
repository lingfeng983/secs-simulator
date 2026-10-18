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
| `secs-dist` | 打包模块（当前占位；实际打包脚本在 `scripts/package.ps1`） | runtime + cli |

依赖方向由 `ArchitectureTest` 强制，越界即构建失败。

## 构建

需要 JDK 25（推荐 GraalVM 25，自带 `native-image`）。

```powershell
$env:JAVA_HOME = 'E:\Java\graalvm-jdk-25.0.4+7.1'
mvn -B verify
```

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
| `POST /api/v1/sessions/{id}/ceid/{ceid}` | operator | 手动触发一次 S6F11（202 / 404 / 409） |
| `POST /api/v1/sessions/{id}/alarm/{alid}?set=&text=&category=` | operator | 置位/清除 S5F1；`set` 默认 true，`category` 0..7（202 / 400 / 404 / 409） |
| `GET /api/v1/sessions/{id}/alarms` | viewer | 当前活动报警（ALID / 类别 / 文本 / 置位时间） |
| `GET /api/v1/sessions/{id}/terminal` | viewer | 最近 20 条终端消息（S10F3） |
| `GET /api/v1/sessions/{id}/catalog` | viewer | 设备目录：CEID / RPTID / SVID 的绝对 ID 与语义，按 ID 升序 |
| `GET /api/v1/preview` | viewer | 曲线预览：`session/from/to/buckets`，返回分桶 min/max/avg |
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

界面目前包含：会话表（状态/计数）、报文面板（SSE 实时推送收发的 SECS 报文，SML 文本）、事件流心跳、手动触发 CEID、报警置位/清除（S5F1）、活动报警与终端消息面板。

## 打包

```powershell
scripts\build-frontend.ps1     # 前端产物
scripts\package.ps1            # 原生镜像 + 组装 + zip（约 5.4 MB）
```

产物是绿色包：`secs-sim.exe` + `web/` + `start.cmd`，双击即用（无需安装 JVM）。

## 四条铁律

1. **`secs-protocol` 只依赖 JDK**，任何新代码都不得反向依赖它，也不得修改它（除非协议规范变更）。
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
- [M0](docs/m0-report.md) · [M1](docs/m1-report.md) · [M2](docs/m2-report.md) · [M3](docs/m3-report.md) · [M4](docs/m4-report.md) · [M4-b](docs/m4b-report.md) · [M5](docs/m5-report.md) · [M6](docs/m6-report.md) · [M7](docs/m7-report.md) · [M6-b](docs/m6b-report.md) · [M6-c](docs/m6c-report.md) · [M8-a](docs/m8a-report.md) · [M8-b](docs/m8b-report.md) · [M8-c](docs/m8c-report.md) · [M8-d](docs/m8d-report.md) · [M8-e](docs/m8e-report.md) · [M9](docs/m9-report.md) · [M9-b / M9-c](docs/m9b-report.md)
- [全量代码评审 2026-10-17](docs/code-review-2026-10-17.md)
- [未完成清单 2026-10-17](docs/unfinished-2026-10-17.md)
- [配置格式 Schema v5](docs/schema-v5.md)（v4 仍兼容）
- [配置格式 Schema v4](docs/schema-v4.md)