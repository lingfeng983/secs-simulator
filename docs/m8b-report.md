# M8-b 验收报告 · React + Vite 前端

## 从零依赖页到 React

M8-a 的零依赖页面证明了 API 契约（同源、鉴权、SSE、触发命令），但它手写 DOM、无法复用组件。
M8-b 把它换成 Vite + React + TypeScript，同时保持"零后端改动"。

| 项 | 选择 |
|---|---|
| 构建 | Vite 5 |
| 框架 | React 18 + TypeScript（strict） |
| 依赖 | 只有 react / react-dom，**没有 UI 库、没有图表库**（先立骨架） |
| 与后端关系 | `vite build` 产出 `dist/`，后端 `--web-root frontend/dist` 同源托管 |

## 工程结构

```
frontend/
├── index.html
├── package.json          # 只有 react/react-dom + vite/typescript
├── tsconfig.json         # strict, noEmit（类型检查交给编辑器/CI，构建只转译）
├── vite.config.ts        # dev 代理 /api -> 127.0.0.1:5000
└── src/
    ├── main.tsx
    ├── App.tsx           # 会话表 + 事件流 + 触发命令
    ├── api.ts            # 契约类型 + fetch 封装 + token 处理
    └── App.css
```

## 功能

| 功能 | 说明 |
|---|---|
| token | 从 `?token=` 读入并存 `sessionStorage`；缺失时页面内输入 |
| 会话表 | 每 2 秒 `GET /api/v1/sessions`，展示状态/设备/单元/已上报/限流/跳过/S6F1 |
| 触发命令 | 填 CEID → 点「上报」→ `POST /api/v1/sessions/{id}/ceid/{ceid}`，日志里显示 HTTP 状态 |
| 事件流 | `EventSource` 连 `/api/v1/events?token=…`，显示连接状态与最近心跳 |
| 日志 | 上限 400 行，自动滚到底 |

## 本机构建与排错记录

用 DSH 自带的 Node 24 + pnpm 11：

```powershell
$env:DSH_NODE = '...\node.exe'
$env:DSH_PNPM = '...\pnpm.mjs'
powershell -ExecutionPolicy Bypass -File scripts/build-frontend.ps1
```

踩到两个工具链问题，都已修在脚本里：

### 1. pnpm 11 默认拒绝执行依赖的构建脚本

`pnpm install` 直接报 `ERR_PNPM_IGNORED_BUILDS: esbuild` 并退出 1。
在 `package.json` 的 `pnpm.onlyBuiltDependencies` 写白名单**无效**（pnpm 10 起迁到 workspace 配置，
pnpm 11 下同样没生效）。可用的做法是命令行作用域白名单：

```
pnpm install --config.onlyBuiltDependencies=esbuild
```

比 `--dangerously-allow-all-builds` 安全（只放行 esbuild）。

### 2. PowerShell 5.1 透传 `.mjs` 的退出码不可靠

脚本里直接 `& node pnpm.mjs install` 后检查 `$LASTEXITCODE`，**手动跑是 0，脚本里却是非 0**，
导致构建脚本误报失败。改用 `cmd.exe /c` 包一层后退出码正确。

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **144 个**（core 79 + app 16 + api-http 13 + runtime 12 + cli 24），全绿 |
| 前端构建 | `dist/` 产出 index.html + 1 个 CSS + 1 个 JS，共 **147 KB** |
| 原生镜像 | `secs-sim.exe` 13.0 MB |
| 原生 IT | 用 `--web-root frontend/dist` 启动，`GET /` 返回 React 页面；S1F3、会话列表、409/202 触发全通过 |

## 怎么用

```powershell
scripts\build-frontend.ps1                       # 产出 frontend/dist
secs-sim --mode=integrated --demo-session --web-root frontend/dist
# 浏览器打开 READY 行里的 http 地址（带 ?token=）
```

开发模式（热更新）：

```powershell
# 终端 1：后端
secs-sim --mode=integrated --demo-session --port 5000
# 终端 2：前端
pnpm --dir frontend dev      # http://127.0.0.1:5173，/api 已代理到 5000
```

## 下一步（M8-c）

- 报文面板：需要后端把 SECS 报文事件推进 SSE（目前 SSE 只有心跳）
- 曲线预览：`uPlot` + 后端二进制曲线接口（Float32）
- 模板画布：`React Flow`

这三块都需要后端配合加"数据通道"，因此 M8-c 是前后端一起动的一批。
