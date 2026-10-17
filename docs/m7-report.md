# M7 验收报告 · HTTP 会话 API

## 交付

| 端点 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/v1/health` | GET | 否 | 存活、版本、形态、UTF-8 回归、会话数 |
| `/api/v1/events` | GET | 是 | SSE 心跳（M8 起推实时值） |
| `/api/v1/sessions` | GET | 是 | 会话列表：id / 状态 / 设备名 / 单元数 / 计数器 |
| `/api/v1/sessions/{id}/ceid/{ceid}` | POST | 是 | 手动触发一次 S6F11 |
| `/api/v1/shutdown` | GET | 是 | 优雅停机 |

会话列表的元素形状：

```json
{"id":"demo","state":"OPEN","device":"DEMO-01","units":2,"lastError":"",
 "counters":{"emitted":1,"rejected":0,"traceFrames":0,"activeTraces":0}}
```

## 三个语义决定

### 1. 触发事件用 POST，不是 GET

它会让主机的报文流里多出一条 S6F11 —— 这是**改变设备状态**，不是幂等查询。
用 GET 的后果是任何一次页面预取或重试都会凭空多发一条事件。

### 2. "没有主机在线"返回 409，不是 500

第一次实现时，主机还没连上就触发，得到 500。这是错的：
设备没坏、服务没坏，只是**它的对端不在**，属于可恢复的状态冲突。
为此在 `SessionRuntime` 上加了 `hasPeer()`，把"没有对端"与"发送失败"分开：

| 情况 | 状态码 |
|---|---|
| 会话不存在 | 404 |
| 会话存在但没有主机会话 | 409 |
| 组装或发送真的失败 | 500 |
| 已发出 | 202 |

### 3. 会话列表要鉴权，健康检查不鉴权

健康检查只暴露"进程活着"，适合做存活探针；
会话列表会暴露设备名、单元数、业务计数器，属于运行信息，必须带 token。

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **127 个**（core 79 + app 10 + api-http 8 + runtime 12 + cli 18），全绿 |
| 原生镜像 | `secs-sim.exe` **13.0 MB**，原生端到端 2 个用例 0.8 s |

### 用例

`HttpApiServerTest`（桩上下文，8 个）：
健康检查免鉴权；会话列表无 token → 401、有 token → 200 且转发上下文内容；
触发命令 202、无 token 401、用 GET → 405；未知会话 404、未打开 409；
路径损坏 400（非数字 CEID、非 ceid 段）。

`NativeRuntimeIT`（原生镜像，真实上下文）：
`/api/v1/sessions` 返回 `{"id":"demo","state":"OPEN","units":2,...}`；
主机未连上时触发 → **409**；HSMS 连上后再触发 → **202**。

## 还没做

- 会话的 create/start/stop/delete 端点（目前只能靠 `--config` / `--demo-session` 创建）。
- `PUT /api/v1/sessions/{id}/config`（在线改配置）与乐观锁（ETag）。
- 预览接口（`/preview/run` 与二进制曲线流）——它属于 M8，需要前端配合定形状。
