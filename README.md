# secs-simulator

SECS/GEM 多单元（multi-unit）设备模拟器。

**一套内核、两种形态**：既可单机一体化运行，也可作为 Linux 服务端供远程客户端访问；
两种形态共用同一份内核、同一套 HTTP/WebSocket API、同一份前端。

---

## 模块

| 模块 | 职责 | 依赖约束 |
|---|---|---|
| `secs-json` | 无反射 JSON 读写 | 仅依赖 JDK |
| `secs-protocol` | HSMS-SS / SECS-I / SECS-II / SML / GEM 报文层（**冻结**，极少改动） | 仅依赖 JDK |
| `secs-core` | 领域模型、ID 规划、曲线、值引擎、编排（无 I/O、无 JSON） | 仅依赖 JDK |
| `secs-app` | 应用服务：会话、配置、模板注册表、预览、审计 | core + json |
| `secs-api-http` | REST + WebSocket + 二进制曲线流 | app + json + `com.sun.net.httpserver` |
| `secs-runtime` | 装配与生命周期：INTEGRATED / SERVER / PROBE；会话的协议实现 | 以上全部 |
| `secs-cli` | 端到端测试宿主（打包职责 M9 迁往 `secs-dist`） | runtime |
| `secs-dist` | 打包：jlink 镜像、AOT 缓存、native-image | runtime + cli |

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

# 从配置文件装载设备（格式见 docs/schema-v4.md）
secs-sim --mode=integrated --config ./device.json --hsms-port 5000

# 同时托管前端（先构建，见下）：浏览器直接打开 READY 行里的 http 地址
scripts\build-frontend.ps1
secs-sim --mode=integrated --demo-session --web-root frontend/dist

# 服务端：对外绑定，交由 systemd 托管
secs-sim --mode=server --bind 0.0.0.0 --port 8443 --token $env:SECS_TOKEN

# 能力探针：开 HSMS 被动端口应答 S1F1，用于现场排障与 CI
secs-sim --mode=probe --hsms-port 5000
```

启动后向 stdout 打印一行握手，供桌面壳与脚本读取：

```
READY mode=integrated http=53466 token=<64位十六进制> utf8=true sessions=1 hsms=5000 work=C:\Users\...\SecsSimulator
```

HTTP API（v1）：

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `GET /api/v1/health` | 否 | 存活与 uptime；带 token 时才返回版本、形态、会话数 |
| `POST /api/v1/ticket` | 是 | 换取一次性 SSE 票据（30 秒有效、只能用一次） |
| `GET /api/v1/events?ticket=` | 票据 | SSE 事件流；票据从上一个端点取 |
| `GET /api/v1/sessions` | 是 | 会话列表（状态 / 设备 / 单元数 / 计数器） |
| `POST /api/v1/sessions/{id}/ceid/{ceid}` | 是 | 手动触发一次 S6F11（202 / 404 / 409） |
| `GET /api/v1/preview` | 是 | 曲线预览：`session/from/to/buckets`，返回分桶 min/max/avg |
| `POST /api/v1/shutdown` | 是 | 优雅停机 |

鉴权只用 `Authorization: Bearer <token>`。URL 里不再接受长期 token：
`EventSource` 不能设置请求头，所以浏览器先 `POST /api/v1/ticket` 换一张
30 秒、一次性的票据再连 SSE。打包脚本打开的界面地址形如
`http://127.0.0.1:<port>/#token=<token>`，token 放在 hash 里，不会发给服务端。

## 前端

前端是独立的 Vite + React + TypeScript 工程（`frontend/`），构建后由后端同源托管：

```powershell
scripts\build-frontend.ps1                 # 产出 frontend/dist
secs-sim --web-root frontend/dist          # 与 API 同源，浏览器直接打开
```

开发时用 `pnpm --dir frontend dev`（`vite.config.ts` 已把 `/api` 代理到 `127.0.0.1:5000`）。

界面目前包含：会话表（状态/计数）、报文面板（SSE 实时推送收发的 SECS 报文，SML 文本）、事件流心跳、手动触发 CEID。

## 打包

```powershell
scripts\build-frontend.ps1     # 前端产物
scripts\package.ps1            # 原生镜像 + 组装 + zip（约 5.4 MB）
```

产物是绿色包：`secs-sim.exe` + `web/` + `start.cmd`，双击即用（无需安装 JVM）。

## 三条铁律

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
- [M0](docs/m0-report.md) · [M1](docs/m1-report.md) · [M2](docs/m2-report.md) · [M3](docs/m3-report.md) · [M4](docs/m4-report.md) · [M4-b](docs/m4b-report.md) · [M5](docs/m5-report.md) · [M6](docs/m6-report.md) · [M7](docs/m7-report.md) · [M6-b](docs/m6b-report.md) · [M6-c](docs/m6c-report.md) · [M8-a](docs/m8a-report.md) · [M8-b](docs/m8b-report.md) · [M8-c](docs/m8c-report.md) · [M8-d](docs/m8d-report.md) · [M8-e](docs/m8e-report.md) · [M9](docs/m9-report.md)
- [配置格式 Schema v4](docs/schema-v4.md)