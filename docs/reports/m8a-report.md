# M8-a 验收报告 · 零依赖静态前端

> **已被 M8-b 取代**：页面已迁移到 `frontend/`（React + Vite），`web/` 目录已删除。
> 本报告保留，因为其中的"同源 / 鉴权 / SSE / 触发命令"四条约定的验证结论仍然有效。

## 为什么先做这个

M8 原本的设想是 React + Vite（+ Tauri）。但那样一来，"看到界面"之前要先装一整套工具链，
而界面要验证的东西其实很具体：**鉴权、同源、SSE 重连、会话列表与触发命令**。

所以先做一个零依赖版本：

```
web/index.html + web/app.css + web/app.js   （纯 HTML/CSS/JS，无构建）
            ↑
        --web-root web        （后端同源托管）
```

## 它验证了什么

| 关注点 | 验证方式 |
|---|---|
| 同源 | 页面与 API 在同一个 `http://127.0.0.1:{port}`，浏览器不需要任何 CORS 配置 |
| 鉴权 | token 从 `?token=` 读入并存 `sessionStorage`；401 时页面弹出输入框 |
| SSE | `EventSource('/api/v1/events?token=…')`（EventSource 不能设请求头，所以走查询参数） |
| 会话列表 | 每 2 秒 `GET /api/v1/sessions`，展示状态、单元数、已上报/限流/跳过/S6F1 计数 |
| 触发命令 | 填 CEID → 点「上报」→ `POST /api/v1/sessions/{id}/ceid/{ceid}` |
| 静态托管 | `HttpApiServer` 的根上下文兜底，未知路径回落 `index.html`（为将来 SPA 留位） |

## 怎么用

```powershell
secs-sim --mode=integrated --demo-session --web-root web
# READY mode=integrated http=54321 token=<token> utf8=true sessions=1 hsms=5000 ...
# 浏览器打开 http://127.0.0.1:54321/?token=<token>
```

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **144 个**（core 79 + app 16 + api-http 13 + runtime 12 + cli 24），全绿 |
| 原生镜像 | `secs-sim.exe` **13.0 MB** |
| `node --check web/app.js` | 通过（Node 在本机可用，为将来上 React 留了路） |
| 原生 IT | 加了 `--web-root` 后 `GET /` 返回首页（含标题 `SECS Simulator`） |

### 静态托管的安全用例（`StaticAssetsTest`）

| 用例 | 断言 |
|---|---|
| `GET /` | 200，`text/html`，含标记内容 |
| `GET /app.js` | 200，`text/javascript` |
| `GET /some/deep/route` | 回落到 `index.html` |
| `GET /%2e%2e/secret.txt` | **不泄露** root 之外的文件 |
| `GET /api/v1/health` | 不被静态 handler 抢走 |

## 它的定位

不是最终界面，而是**行为基线**：

- 将来换 React 时，这个页面的每个交互都有明确对应的 API 调用，可以直接对照；
- 它同时是"API 契约的活文档"——接口改坏了，这个页面先崩。

## 下一步

M8-b 起可以在两条路里选：

1. **继续零依赖**：加报文面板（SML 文本流）、曲线预览（Canvas 手绘）。
2. **引入 React + Vite**：本机已有 Node，可先做开发版；打包/桌面化（Tauri）留到 M9。