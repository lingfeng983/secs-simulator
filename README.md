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
| `secs-core` | 领域模型、ID 规划、曲线、模板 DAG、值引擎、编排（无 I/O、无 JSON） | 仅依赖 JDK |
| `secs-app` | 应用服务：会话、配置、模板注册表、预览、审计 | core + json |
| `secs-api-http` | REST + WebSocket + 二进制曲线流 | app + json + `com.sun.net.httpserver` |
| `secs-runtime` | 装配与生命周期：INTEGRATED / SERVER / PROBE | 以上全部 |
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

# 服务端：对外绑定，交由 systemd 托管
secs-sim --mode=server --bind 0.0.0.0 --port 8443 --token $env:SECS_TOKEN

# 能力探针：开一个 HSMS 被动端口并应答 S1F1，用于现场排障与 CI
secs-sim --mode=probe --hsms-port 5000
```

启动后向 stdout 打印一行握手，供桌面壳与脚本读取：

```
READY mode=probe http=54321 token=<64位十六进制> hsms=5000 work=C:\Users\...\SecsSimulator
```

HTTP API（v1）：

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `GET /api/v1/health` | 否 | 存活、版本、形态、UTF-8 回归结果 |
| `GET /api/v1/events` | 是 | SSE 事件流（当前为心跳，M2 起推实时值） |
| `GET /api/v1/shutdown` | 是 | 优雅停机 |

鉴权用 `Authorization: Bearer <token>`；浏览器 `EventSource` 无法设置请求头，可改用 `?token=`。

## 两条铁律

1. **`secs-protocol` 只依赖 JDK**，任何新代码都不得反向依赖它，也不得修改它（除非协议规范变更）。
2. **禁止 `--enable-preview`**。预览特性会把 class 文件绑死到具体 JDK，且与 native-image 不兼容。

## 文档

- [ADR 0001 · 地基决策](docs/adr/0001-foundation.md)
- [ADR 0002 · 领域模型与 ID 空间](docs/adr/0002-domain-and-idspace.md)
- [M0 报告](docs/m0-report.md) · [M1 报告](docs/m1-report.md)
