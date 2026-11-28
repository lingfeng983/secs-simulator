# 3.5.3 发布说明

发布日期：2026-11-28
类型：缺陷修复版（全量代码评审的 P0/P1 全部修复；含若干行为变更，升级前请读第一节）

这一版来自一次全量评审：主代码 57k 行、测试 20k 行、前端 34.7k 行，六路并行审完再逐条回查。
结论不是"功能写错了"，而是几处**看起来在保护、实际没保护**的地方——现有 800+ 项测试
全绿也拦不住。修完之后，CI 上原本每次运行都会留下的 2 条 failure 注解与 120 条 warning
注解也一并清零。

## 一、升级须知（行为变更）

| 变化 | 之前 | 现在 | 影响面 |
|---|---|---|---|
| 超限请求体状态码 | `400 bad-body` | `413 body-too-large` | 依赖状态码的脚本/前端分支 |
| 浏览器跨站写请求 | 照常执行 | `403 cross-origin-write-blocked` | 只影响带 `Origin` 的跨站请求；curl / 无 `Origin` 的脚本不变 |
| 集合端点路径 | 前缀匹配，`/api/v1/sessionsabc` 回 200 空集合 | 精确匹配，回 404 | 拼错路径的调用方 |
| 值定义变量名写错 | 能保存、能启动，运行期只留一行日志 | 保存时拒绝（`UNKNOWN_EXPR_VARIABLE` / `UNKNOWN_CURVE_VARIABLE`） | 已有配置可能需要修正 |
| 事件计划 id 重复 / AfterEvent 成环 | 能保存，运行期静默异常 | 保存时拒绝 | 同上 |
| SML 报文头 | 语法上无法表达"不带 W 的 primary" | 新增 `NW`（no wait） | 主动发送不需要回复的 primary 时可用 |
| 事件计数 `emitted` | 发送完成即累加 | 入队投递后异步累加 | 只看计数做同步断言的脚本要改为等收包 |
| 链路事件台账 | 无上限 | 每实体保留 2000 条 / 7 天 | 长期运行实例的历史台账会被裁剪 |
| 并发请求 | 无上限 | 512 并发门，超出回 503 | 极端压测时可能见到 503 |
| 全局 `readJson` 空正文 | 返回 `undefined` | 非 204 的空正文抛 `empty-body` | 依赖静默空值的调用方 |

配置文档格式、数据库 schema、HTTP API 形状不变，无需迁移。

## 二、会丢数据与拖垮进程的三处（P0）

- **保存期间的输入不再被误标已保存**：草稿基线此前推进到"保存成功那一刻的快照"，
  请求在途时继续输入的内容会被当成已保存，未保存标记消失、下次保存回"本页没有改动"。
  现在基线只推进到**实际提交的文本**，在途输入仍然是脏的。
- **长跑实例不再写满磁盘**：台账裁剪此前整个仓库只有启动时调用一次，`link_event` 更是
  完全没有裁剪路径。计划周期最低 1ms，连续跑几周会把 `plan_execution` 撑到磁盘满，
  之后所有写事务失败还会伪装成"保存失败"。现在小时级 daemon 任务同时裁剪两张表。
- **一台设备卡住不再拖住全进程**：`pumpNow` 曾在 `pumpLock` 内直接写 socket，对端 TCP
  零窗口时单次写可阻塞到 T6（默认 5 秒），而创建/关闭/重载都要这把锁且外层还持全局
  `lifecycleLock`，表现就是"整个界面点不动"。S6F11/S6F1 的发送（含非 W 位）全部改为
  入队，锁内只做投递。

## 三、正确性

- **SML**：新增 `NW` 语法；`U8` 改用 `BigInteger` 渲染，≥2^63 不再输出负数；
  宽度占位 `{n}` 限幅 1..32，避免构造超长字符串撑爆内存。
- **SECS-II 解码**：收包时校验长度能否整除元素宽度，畸形帧在 parse 阶段走 S9F7，
  而不是等到取值时 `BufferUnderflow`；解码失败输出 `null` 而非 `false`。
- **HSMS**：`linktest` 重置补 `notifyAll`（此前 `linktest(0)` 可能永久挂起）。
- **配置编译期**：新增三类拦截——表达式/曲线变量名、计划 id 唯一、AfterEvent 链无环。
- **存储一致性**：schema 重建的 DROP+CREATE+写版本收进单个 `BEGIN IMMEDIATE`；
  `meta` 缺 `schema_version` 从"当新库"改为报错；`UPDATE` 带 `WHERE revision=?`；
  `deleteDevice` 先解析唯一 id，不再用 `(name=? OR id=?)` 连带软删另一台。

## 四、HTTP、前端与桌面壳

- 集合端点精确匹配（8 处）、超限 413、读正文分块 + 15 秒上限、并发门 512、
  HEAD 与 405 的 `Allow` 头。
- 前端：请求序号防止旧快照覆盖新状态；删除/断开非 2xx 抛错并提示原因；
  换配置先清空端点；配置目录读取失败不再空 catch，顶栏给出可重试提示。
- Tauri：`switch_backend` 改为"新连接就绪后再停旧后端"；停后端先请求
  `/api/v1/shutdown` 等最多 3 秒再强杀。

## 五、CI：注解清零

- **每次运行都留下的 2 条 failure 注解**：线程池随 `close()` 关停后，accept 回调再
  `invokeAny` 会抛未受检的 `RejectedExecutionException`；它从 NIO 共享回调线程冒出去
  就成了未捕获异常。两个重载统一转成带 `communicator is closing` 文案的
  `ExecutionException`，调用方本就按"连接已终止"处理；新增用例钉住该契约。
- **120 条 warning 注解**：全部来自 `secs-protocol`（冻结模块）。`serial` / `this-escape`
  两类在该模块关闭并写明理由（这些类为沿用原库签名才实现 `Serializable`，运行期从不序列化），
  其余 lint 仍在观察；顺带修掉两处 byte 位运算的窄化告警与 5 处冗余泛型转换。
- **对 Maven Central 限流加重试**：此前一次 429 会让整个后端腿变红（需人工重跑）。
  属性走 `MAVEN_OPTS`——命令行 `-D` 在 Windows runner 上会被截断成
  `Unknown lifecycle phase .connector...`。
- **两条超长正文用例脱敏时序**：服务端只凭 `Content-Length` 就拒，回包与断连存在竞争，
  Linux/Windows runner 上偶发 `header parser received no bytes`。改为容忍该断连形态，
  核心断言（正文没进业务层）不变。

## 六、门禁

- 后端：`mvn -B test` 9 模块 BUILD SUCCESS，**822 项** 0 失败。
- 原生：`NativeRuntimeIT` 3/3，Windows 与 Ubuntu 两条腿都真跑（缺产物即 fail，不再 skip）。
- 前端：`tsc` 0 错、Vitest **259**、Playwright **75**、界面文案 144 文件 / 7266 段。
- CI：`46106880836` 五个 job 全绿，**0 error / 0 warning 注解**。

评审报告与逐条修复记录见[全量代码评审 2026-11-28](code-review-2026-11-28.md)。

## 七、已知遗留（不在本版范围）

SSE 的 `id:` / `Last-Event-ID` 断线回放、静态资源流式输出、Tauri 首屏骨架、
`SessionManager` 端口化重构。这四项要么是独立特性，要么改动面较大，留待 3.6.0 之后。
