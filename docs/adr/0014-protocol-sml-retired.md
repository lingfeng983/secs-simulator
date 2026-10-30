# ADR 0014 · 协议层 SML 实现下线（保留类，不保留入口）

- 状态：已接受
- 日期：2026-10-30
- 影响范围：`secs-protocol` 的 `SecsCommunicator` / `AbstractSecsCommunicator`，`com.shimizukenta.secs.sml` 包
- 关联：ADR 0010（手动发送只实现受控子集），README「四条铁律」第 1 条

## 问题

项目里有两套 SML 解析器：

1. `com.shimizukenta.secs.sml`（上游库自带）：只支持部分格式、不支持
   Unicode/JIS8、列表长度语义与本产品不同；
2. `secssim.runtime.sml`（本产品）：支持 `SxFy [W] <...>.`、行间距、
   单元号注入约定，是手动发送与界面渲染真正在用的那套。

协议层的两个入口 `send(SmlMessage)` / `send(SecsMessage, SmlMessage)`
全仓零调用、零测试：它们只是把上游语言绑定挂在公共接口上。留着会让读者以为
"这条路径能用"，而实际语法子集与产品语义不一致——最坏情况是有人照着接口
写代码，发出去一条与输入不同的报文。

## 决策

### 1. 从公共 API 摘掉两个入口

`SecsCommunicator` 接口与 `AbstractSecsCommunicator` 实现中的
`send(SmlMessage)`、`send(SecsMessage, SmlMessage)` 删除，类级 Javadoc 的
对应索引一并删掉。除此之外没有行为变化：底层 `send(stream, function, wbit, Secs2)`
才是真正的发送路径，运行时一直用的就是它。

### 2. 保留包，但标注 unsupported

照 `com.shimizukenta.secs.secs1` 的先例（本产品只支持 HSMS-SS，那套 SECS-I
保留但不承诺修复），`com.shimizukenta.secs.sml` 的 `package-info` 明确写清：
本产品不使用、不修、不测；需要手动发报文走 `POST /sessions/{id}/message`。

保留类而不删除文件，是为了不制造与上游库目录结构的无谓偏差；真正的边界
是"公共 API 不再暴露"，这一点比删文件更不容易被重新引入。

### 3. 铁律 1 的例外登记

README 的"协议层冻结"原本只允许"对照 E4/E5/E37.1 确认的实现缺陷"。
本次是**公共 API 收窄**，不是行为修复，因此按例外处理并登记在案：
只删无调用方的入口，不改任何报文编解码逻辑。

## 后果

- `secs-protocol` 对外暴露的发送入口只剩 `Secs2` 版本，语义唯一。
- 上游库若将来更新 SML 实现，本产品不会自动获得，也不需要跟——这是有意为之。
- 想在测试里构造 `SmlMessage` 的路径没有了；需要 SML 的场景统一走运行时那套，
  与生产代码同源。
