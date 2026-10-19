# ADR 0012 · 审计日志与业务日志分离

- 状态：已接受
- 日期：M12
- 影响范围：`secs-app` 的 `AuditLog`，`secs-api-http` 的 `AuditSink` / `GET /api/v1/audit`，前端「审计」面板

## 问题

"谁在什么时候把哪条配置改了/把哪个事件触发了"是现场排障和安全审查的必答题。
之前只有 stderr 运行日志和报文日志：

- 运行日志是给进程看的，不是给人查的；
- 报文日志只能证明协议上发生了什么，证明不了"是谁点的"。

## 决策

### 1. 审计是独立 JSONL，不与报文混在一起

路径：`<work-dir>/logs/audit.jsonl`。一行一个对象：

```json
{"at":1792419603536,"actor":"line1","role":"operator","action":"trigger-ceid","target":"line1","detail":"ceid=2001 outcome=OK"}
```

报文是协议证据，审计是操作证据；保留策略、访问权限和查询方式都不同。

### 2. 在 HTTP 层记录 actor，而不是穿透业务接口

`HttpApiServer` 持有可选的 `AuditSink`。需要审计的 handler 显式调用
`audit(ex, action, target, detail)`；helper 从请求头重新解析 Principal。

这样不需要把 `Principal` 传进 `ApiContext` 的每个方法，也不会把
HTTP 身份概念泄漏到会话/配置业务层。

### 3. 记录拒绝，不只记录成功

`requireRole` 在角色不足时写一条 `denied`，detail 里带 `required` 与当前 role。
401 没有身份可记，跳过。

覆盖的动作包括：手动 CEID、报警、会话创建/关闭/重载、配置创建/保存、
手动 SML、事件编排切换、停机。

### 4. 审计失败不影响业务

写日志失败只记录一次错误然后继续。审计是旁路，不是事务的一部分；
不能因为磁盘满了就让设备停止响应。

### 5. 查询仅 admin

`GET /api/v1/audit?limit=` 需要 admin。审计里有账号名、目标与操作结果，
比报文历史更敏感。

## 后果

- 关键操作有人、角色、目标、结果和时间，能直接回答"是谁干的"。
- 审计文件没有自动轮转，需要运维归档。
- 拒绝请求也会留下记录；这是有意的，便于发现越权尝试。
- 只覆盖 API 操作；进程启动/停止等生命周期事件仍只在 stderr 里。
