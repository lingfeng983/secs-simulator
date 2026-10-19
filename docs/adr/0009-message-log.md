# ADR 0009 · 报文落盘用 JSON Lines

- 状态：已接受
- 日期：M12
- 影响范围：`secs-app` 的 `MessageLog`，`secs-runtime` 的 `Main`，`secs-api-http` 的 `GET /api/v1/messages`，前端「报文」面板

## 问题

SSE 只负责"现在"：页面刷新、断线重连、或者只是想看上一分钟发生了什么，
现有面板都拿不到——它只在内存里保留最近 200 条，而且进程一退就没了。

报文是模拟器最重要的诊断材料。现场需要能回答：

- 刚才那条 S6F11 到底发出去了没有？
- 主机回了 S6F12 吗？ACKC6 是什么？
- 昨天那次失败前后，收发顺序是什么？

## 决策

### 1. 每条报文追加到 `<work-dir>/logs/messages.jsonl`

一行一个 JSON 对象，字段与 SSE 事件一致：

```json
{"sessionId":"line1","direction":"out","stream":6,"function":11,"wbit":true,"body":"...","at":1792419603536}
```

选 JSONL 而不是数据库/二进制日志：

- 现场可以 `Select-String` / `jq` / Excel 直接看；
- 追加写简单，崩溃最多丢最后一行；
- 不需要引入数据库依赖，符合原生镜像的体积约束。

### 2. 写日志失败不能影响设备运行

`MessageLog.append` 是 EventBus 的订阅者，异常自己吞掉并只记录一次错误。
一条诊断日志写不进去，不该让会话线程崩掉，也不该让 S6F11 停止上报。

同理，启动时 `work-dir` 不可写：服务继续跑，历史接口回 **503
`message-log-unavailable`**，而不是假装有历史或直接拒绝启动。

### 3. 内存环 + 启动回读文件尾部

- 内存保留最近 **5000** 条，供 HTTP 历史接口快速查询；
- 启动时只回读文件最后 **4 MiB**，避免日志很大时启动变慢；
- 文件本身不截断，完整历史留给运维归档。

### 4. HTTP 只读，支持过滤与两种格式

`GET /api/v1/messages?session=&direction=&limit=&format=`

- 角色：viewer；
- `session` / `direction` 可选，`direction` 只接受 `in` / `out`；
- `limit` 默认 500，范围 1..5000；
- `format=json`（默认）返回 `{"messages":[...],"total":N,"limit":N,"truncated":bool}`；
- `format=jsonl` 返回 `application/x-ndjson` 附件，方便直接下载。

### 5. 前端「载入历史 / 导出 JSONL」

- 首次连接或断线重连后，SSE 只推新报文；点「载入历史」按全部字段去重后
  与实时列表合并，补上断线期间缺的那一段；
- 「导出 JSONL」不经过浏览器内存里的 200 条上限，最多导出 5000 条；
- 「清空」只清界面，磁盘日志不动。

## 后果

- 页面刷新/断线后能补齐最近报文，不再只能靠"我记得刚才看到了"。
- `work-dir` 下多了一个 `logs/` 目录；`ConfigStore` 只列普通文件，不会把日志当配置。
- **没有轮转/保留策略**：文件会一直增长，需要运维按现场磁盘规划归档或清理。
- 同一 `work-dir` 不建议同时跑多个进程：它们会追加同一个 JSONL，行可能交错。
- 内存环是"最近 5000 条"，不是完整历史；更早的数据直接看文件。
