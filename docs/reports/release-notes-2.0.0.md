# SECS Simulator 2.0.0 发布说明

发布日期：2026-11-09

## 下载

| 平台 | 文件 | 大小 | 适用场景 |
|---|---|---|---|
| Windows | `SECS Simulator_2.0.0_x64-setup.exe` | 8.8 MB | 现场调试：装完即用，内置原生后端（`secs-sim.exe`），不需要 JVM |
| Linux | `secs-sim-server-2.0.0-linux.zip` | 1.0 MB | 部署在设备所在网段的服务端，由 Windows 端远程连接；需要 JDK 25+ |

## 这版是什么

2.0.0 是**结构收敛**版本，不是功能大改版：SECS/GEM 协议行为、HTTP API 路径、
界面操作方式与 1.x 保持一致。主要变化在仓库组织、后端分层和前端拆分，
外加版本号从 `2.0.0-SNAPSHOT` 正式化为 `2.0.0`。

使用者能直接感知的变化：

- 安装包与 Linux 包的文件名带正式版本号，不再出现 SNAPSHOT；
- Linux 服务端包补齐了入口模块 `secs-cli.jar`，`start-server.sh` 与包内 `README.txt`
  的手工启动命令同步为当前主类，开箱可启动；
- Linux 包内说明删掉了早已移除的 `--token` 段落（现在写它会被当成未知参数拒绝）；
- 协议、接口、配置格式无破坏性变更。

## 安装与启动

### Windows（推荐给现场调试）

1. 双击 `SECS Simulator_2.0.0_x64-setup.exe` 安装并启动。
2. 默认「本机运行」：桌面前端连内置的 `secs-sim.exe`，不依赖本机 JDK。
3. 需要连远程服务端时，点右上角「连接」填地址；留空并保存即切回本机，选择会记住。

### Linux 服务端

```bash
unzip secs-sim-server-2.0.0-linux.zip
cd secs-sim-server-2.0.0-linux
./start-server.sh                 # API 8080（绑 0.0.0.0）、HSMS 5000、数据目录 ./work
./start-server.sh -b 127.0.0.1    # 只绑回环
```

控制台会打印 `API 地址: http://<本机名>:8080`，浏览器可直接打开，
也可以在 Windows 端「连接」里填这个地址远程操作。

参数：`-p` API 端口、`-b` 绑定地址、`-h` HSMS 端口、`-w` 数据目录。
目标机必须装 JDK 25+（jar 用 class 文件版本 69 编译，JDK 21 会报
`UnsupportedClassVersionError`）。

## 能力速览

- **多设备会话**：运行时新建 / 关闭 / 热重载会话，一台设备一份 `<设备名>.json`；
  支持按模板批量创建与批量同步配置。
- **主机模拟**：一台设备允许一个本机主机实例；消息发送（16 条内置预设）、
  S6F1 采集、S1F3 采集（每 SVID 一张可缩放图）、主机视角收发记录。
- **事件与报告**：CEID 绑定报告、报告定义变量、事件编排（触发规则 + 效果层叠），
  单条计划可预览，全量周期预览按计划分色。
- **曲线预览**：Canvas / uPlot / 多轴三种渲染，窗口统计表（min/max/avg/末值/峰峰），
  另有 SCSP v1 二进制接口供自建看板使用。
- **报文监视**：实时收发、按类别屏蔽、拖拽放大、默认保留 2000 行、JSONL 导入导出。
- **结构化配置**：「连接」「结构」页逐项编辑并解释属性含义，「配置」页保留 JSON 原文
  逐字节写回；保存后相关会话自动重载。
- **运维与审计**：操作审计（可导出 CSV、重新导入只读查看）、日志级别运行时切换、
  Host 白名单与 Origin 校验（不引入账号体系）。

## 从 1.x 升级

- **配置文件**：格式为 Schema v5（见 `docs-schema-v5.md`），旧 v4 文件仍可读取。
- **HTTP API**：路径与方法未变，`docs/openapi.yaml` 已同步，脚本无需改动。
- **启动参数**：`--token` / `--auth-users` 已移除，写了会直接报"未知参数"；
  对外部署请用防火墙/网段隔离，必要时加 `--tls-keystore`。
- **数据目录**：`work/*.json` 一设备一文件，可直接沿用，无需迁移。
- **自行构建**：产物统一输出到 `build/installer`，前端只用 npm（不再有 pnpm 锁文件）。

## 质量与验证

| 检查 | 结果 |
|---|---|
| 后端单测 | 581 个测试通过 |
| 原生镜像 | `mvn -Pnative verify` 构建成功，`NativeRuntimeIT` 2 条通过 |
| 前端类型 / 单测 | `tsc` 0 错误，Vitest 119 条通过 |
| 前端 E2E | Playwright 36 条通过（37 条截图回归按设计跳过） |
| Windows 包 | 原生 `secs-sim.exe` 启动后 `/api/v1/health` 返回 `version=2.0.0` |
| Linux 包 | 解压后 `start-server.sh` 起 server 模式，`version=2.0.0`（`vm=jvm`），优雅停机正常 |

## 已知边界

- S2F41/F42 远程命令只回 HCACK，不执行任何设备动作。
- Spooling（S2F43–F48）未实现，断线期间的事件不缓存、不补发。
- 未建立通信（ONLINE）前的 S6F11 / S6F1 不主动发送；手动"上报"不受此限。
- 未知 SVID 的 S1F3 / S1F4 恒回 `U4 0`；S1F11 里名字给 `UNKNOWN` 以便区分。
- 报警文本（ALTX）仅支持 US-ASCII，入口即拒。
- 单会话 Trace 默认 1 条、每条 ≤10 SVID，可在 `session.trace` 调大后重建会话。

## 相关文档

- [使用手册](../guides/user-guide.md)
- [配置格式 Schema v5](../guides/schema-v5.md)
- [SVID 值变化机制](../guides/svid-value-model.md)
- [HTTP API 契约](../openapi.yaml)
- [ADR 0016 · 目录与分层收敛](../adr/0016-layout-and-layer-consolidation.md)
