# M9 验收报告 · 打包与分发（绿色包）

## 交付

| 产物 | 说明 |
|---|---|
| `scripts/package.ps1` | 组装绿色包：原生 exe + 前端产物 + 启动脚本 + 配置文档，压成 zip |
| `packaging/start.ps1` / `start.cmd` | 拉起进程 → 读 `READY` 行 → 解析 token/端口 → 自动打开浏览器 |
| `packaging/README.txt` | 面向使用者的说明（怎么启动、怎么换成自己的配置） |

zip 结构：

```
secs-sim-<version>/
├── secs-sim.exe          13.7 MB（GraalVM 原生，无需 JVM）
├── web/                  前端产物（1 html + 1 css + 1 js）
├── start.cmd / start.ps1 一键启动
├── README.txt
└── docs-schema-v4.md     配置格式
```

## 怎么用

```powershell
scripts\build-frontend.ps1        # 产出 frontend/dist
scripts\package.ps1               # 构建原生镜像 + 组装 + 压缩
# 使用者解压后双击 start.cmd
```

## 实测

| 项 | 结果 |
|---|---|
| 打包 | **5.4 MB** zip（原生 exe 压缩后） |
| 包内冒烟 | 解压目录直接运行 `secs-sim.exe --web-root .\web`：`READY ... sessions=1`，`GET /` 返回 200（402 B 首页） |
| 单元测试 | 154 个全绿（本次未改后端逻辑） |

## 踩坑：又一次 UTF-8 BOM

新写的 `.ps1` 没有 BOM，PowerShell 5.1 按 ANSI 读中文，把
`throw "缺少前端产物..."` 里的引号吃坏，报 `Unexpected token`，且行号指向 throw 那一行。
**这和 M1 踩的是同一个坑**——说明"新脚本一律写 BOM"应该进检查清单，而不是靠记忆。
现在 `scripts/` 与 `packaging/` 下的全部 `.ps1` 都已带 BOM。

## 已知边界（未做）

| 项 | 说明 |
|---|---|
| 安装器（MSI/EXE） | 现在是绿色 zip，不是安装程序；`jpackage` 可以补，但需要 jlink 运行时或改用原生 exe 的自解压 |
| 代码签名 | 未签名；未签名的"会开本地端口的 exe"容易被杀软拦，正式分发前必须签 |
| 自动更新 | 未做（Tauri updater 或自研检查版本） |
| JVM 模式打包 | 目前只打原生 exe；`jlink` + `jpackage` 的 JVM 版可作为"原生镜像在某些机器跑不起来"的兜底 |
| 跨平台 | 只能各平台各自构建（native-image 不支持交叉编译） |
