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
| `secs-core` | 领域模型、曲线、模板 DAG、值引擎、编排（无 I/O、无 JSON） | 仅依赖 JDK |
| `secs-app` | 应用服务：会话、配置、模板注册表、预览、审计 | core + json |
| `secs-api-http` | REST + WebSocket + 二进制曲线流 | app + json + `com.sun.net.httpserver` |
| `secs-runtime` | 装配与生命周期：INTEGRATED / SERVER 两种形态 | 以上全部 |
| `secs-cli` | headless 命令行与自测 | runtime |
| `secs-dist` | 打包：jlink 镜像、AOT 缓存、native-image | runtime + cli |

依赖方向由 `ArchitectureTest` 强制，越界即构建失败。

## 构建

需要 JDK 25（推荐 GraalVM 25，附带的 `native-image` 用于后续原生打包）。

```powershell
$env:JAVA_HOME = 'E:\Java\graalvm-jdk-25.0.4+7.1'
mvn -B verify
```

或直接跑里程碑验收脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/accept-M0.ps1
```

## 两条铁律

1. **`secs-protocol` 只依赖 JDK**，任何新代码都不得反向依赖它，也不得修改它（除非协议规范变更）。
2. **禁止 `--enable-preview`**。预览特性会把 class 文件绑死到具体 JDK，且与 native-image 不兼容。

## 文档

- 架构决策记录：[docs/adr](docs/adr)
- 技术方案（技术选型、里程碑、风险）见仓库内 `docs/`。
