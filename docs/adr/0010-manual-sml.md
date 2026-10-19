# ADR 0010 · 手动发送 SML 只实现受控子集

- 状态：已接受
- 日期：M12
- 影响范围：`secs-runtime` 的 `SmlParser` / `HsmsSessionRuntime.sendSml`，`secs-api-http` 的 `POST /api/v1/sessions/{id}/message`，前端「手动发送」面板

## 问题

模拟器需要能手动发一条报文，验证主机对任意 Stream/Function 的应答。
项目已有 `SmlRenderer`（Secs2 → SML），但没有反向解析。

完整实现 SEMI E5 的 SML 解析器成本很高：JIS8、UNICODE、数组简写、
注释、多种十六进制写法、厂商扩展都要覆盖；写宽了反而会出现
"看起来能解析，实际发出的字节和工程师预期不同"。

## 决策

### 1. 只解析手动发送真正需要的子集

支持：

- 报文头 `SxFy`、可选 `W`、可选结尾 `.`
- `<L [n] ...>`、`<A "TEXT">`、`<B 0x..>`、`<BOOLEAN 0x00/0x01>`
- `I1/I2/I4/I8`、`U1/U2/U4/U8`、`F4/F8`
- ASCII 转义 `\" \\ \n \r \t`

JIS8 / UNICODE / 数组简写 / 未识别类型一律报错，不做"尽量猜"。
**宁可拒绝，也不要悄悄发出一条与输入不同的报文。**

### 2. 报文头与 body 一起解析，保留 W 位

解析结果是一个不可变的 `Message(stream, function, wbit, body)`。
W 位直接决定发送后是否等待回复：`send` 返回空不代表失败，
可能只是 `W=0`；W=1 超时则会标 `timedOut=true`，因为报文已经发出去了。

### 3. HTTP 层只管状态码映射

`POST /api/v1/sessions/{id}/message` 的请求体就是 SML 原文：

- 400：SML 语法错误 / body 为空 / 超过 64 KiB
- 404：会话不存在
- 409：会话未打开或没有对端
- 500：协议层发送失败
- 200：返回 `sentSml`、`replySml`、回复头、`timedOut`、`elapsedMs`

请求体不包 JSON：手写报文时多套一层转义只会增加错误源。

### 4. 角色是 operator

发送任意报文会改变设备对主机的行为，和触发 CEID / 报警同级。
viewer 只能看历史和结果，不能发。

### 5. 防御性上限

- body 64 KiB；
- 嵌套深度 32；
- 数据项数量 10,000；
- 流号 1..127、功能号 0..255；
- 二进制值 0..255，无符号类型禁止负数。

这些限制在解析阶段就拒绝，而不是等构造 `Secs2` 时才抛异常。

## 后果

- 手动发送覆盖了绝大多数现场调试报文；遇到不支持的语法会明确报错。
- 没有"从文件选择模板"的功能：前端只给 S1F1 / S5F1 两个示例，
  其他报文从 `templates/sml/` 复制或手写。
- W 位发送是同步等待 T3 的；一个 HTTP 请求可能占住线程数十秒。
  当前请求线程池为 4，现场不要同时点很多条 W 位报文。
- 解析器没有复用协议层的 SML 类型；`SmlRenderer` 与 `SmlParser` 的语法集合需要人工保持同步。
