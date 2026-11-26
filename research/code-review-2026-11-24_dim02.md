# 协议层专项评审 · 2026-11-24（维度02：SECS/GEM 符合性与代码质量）

评审对象：`secs-simulator-service/secs-protocol/src/main/java`（协议层，声明冻结）。
方法：只读精读关键类 + 对照 `docs/reference/` 标准整理 + 抽查历史评审
（`docs/reports/code-review-2026-10-20.md`）已修项。所有行号均为本轮实际读取所得。
冻结政策口径：只有对照标准确认的实现缺陷才列问题；风格与配置建议单独标注。

---

## 现状概述

- 包结构（glob 实测）：`com.shimizukenta.secs` 根包（通信器基类/属性/异常）+ 五个子包
  `hsmsss`（30 文件）、`gem`（47 文件）、`secs2`（34 文件）、`secs1`、`secs1ontcpip`。
  根包 glob 在 200 条处截断，总文件数未逐一清点（任务简报称 214 个，未独立复核）。
- HSMS-SS 实现总体质量高：状态机四态齐全、控制帧收发语义与 E37 整理版逐条对得上，
  历史评审的协议层修复（secondary-only 应答匹配、S9F7、Deselect 双向处理、T8 消息间豁免、
  选中连接引用比对、写超时）全部在当前代码中核实到位（见"历史修复核实"）。
- GEM 按能力域拆为六个子接口 + `GemSupport` 共享状态，结构清晰；协议层提供的是
  **报文原语**（发送/应答/ACK 解析），S6F11 组包、S5F1 发送、S2F41 处理、S2F23 Trace
  的**设备侧业务**在 `secs-runtime`（`S6F11Assembler` / `HostCommandHandler` /
  `PrimaryDispatcher`），本轮对 runtime 仅做修复抽查，不做全量评审。
- 本轮新发现：2 个确认的协议语义缺陷（均为 P2，发起方 Deselect 无 T6 事务、控制帧应答
  匹配不校验 SType），2 个低危观察项，1 个需 E37.1 原文裁决的存疑项。无 P0/P1。

---

## 逐项核查（证据）

### 1. HSMS 状态机（NOT CONNECTED / CONNECTED / NOT SELECTED / SELECTED）

四态由 `HsmsSsCommunicateState` 属性承载，初值 NOT_CONNECTED：

> `AbstractHsmsSsCommunicator.java:48`
> `private final Property<HsmsSsCommunicateState> hsmsSsCommStateProperty = Property.newInstance(HsmsSsCommunicateState.NOT_CONNECTED);`

**被动端**（`AbstractHsmsSsPassiveCommunicator.java`）：
- NOT SELECTED 收 DATA → Reject(4)：`:340` `send(createRejectRequest(msg, HsmsSsMessageRejectReason.NOT_SELECTED));`
- Select.req：无占用 → SUCCESS 并进入 SELECTED（`:345-351`）；已有选中连接 → ALREADY_USED(3)（`:355`）。
- SELECTED 态再收 Select.req → ACTIVED(1)：`:428` `send(createSelectResponse(msg, HsmsSsMessageSelectStatus.ACTIVED));`
- SELECTED 收 Deselect.req → 回 status=0、原子降为 NOT_SELECTED、TCP 保留并回到等 SELECT：
  `:446-448`
  ```java
  send(createDeselectResponse(msg));
  removeSelectedConnection(this, HsmsSsCommunicateState.NOT_SELECTED);
  return true;
  ```
  外层循环 `:224-237` 回到 `awaitSelect()`，符合 E37 §7.4 响应方语义（整理版 `:181`）。
- NOT SELECTED 收 Deselect.req → status=1（通信未建立）：`:387` `send(createDeselectResponse(msg, (byte)1));`，对照整理版 Deselect Status 表（`04-E37…md:287`）✅
- SEPARATE_REQ → 立即结束连接、不作答（`:365-368`、`:436-439`），符合整理版 §7.6"响应方不回任何响应"（`:190`）✅
- 非应答期收到 SELECT_RSP/DESELECT_RSP/LINKTEST_RSP → Reject(TRANSACTION_NOT_OPEN=3)（`:370-377`、`:450-456`），符合整理版 Reject 原因码表（`:298`）✅
- REJECT_REQ 只吸收不作答，注释明确防 SystemBytes 回带互回死循环（`:378-383`）✅
- 未知 SType/PType → Reject(1/2)（`:390-403`）✅

**主动端**（`AbstractHsmsSsActiveCommunicator.java`）：
- 建连后先置 NOT_SELECTED（`:191`），Select 成功置 SELECTED（`:221`），T5 重连退避在
  `:46` `hsmsSsConfig().timeout().t5().sleep();` ✅
- 连接结束按"先注销、再降级"收尾（`:256-264`），注释解释了反序会造成的 send 语义错误。
- Deselect.req → 应答后主动断链按 T5 重连（`:315-322`），与被动端保留 TCP 的差异有意为之（注释自述）。
- ⚠️ Select.rsp 状态码处理：`:198-204` 把 `SUCCESS` **和 `ACTIVED`(1)** 都当成功进入 SELECTED。
  E37 整理版 §7.2 写"收到非 0 状态 → 失败，无状态迁移"（`04-E37…md:157`）。但整理版头部
  明确"未覆盖 E37.1 附属标准的增量约束"（`:68`），HSMS-SS 实践中 status=1 常按"已激活、可继续"
  处理。列存疑项 Q-1，不凭整理版断言缺陷。

**选中连接的并发保护**（防"假掉线"/状态错位）：
- `AbstractHsmsSsCommunicator.java:144-153`：`removeSelectedConnection(c, targetState)` 在同一
  `syncSelectedConnection` 临界区内比对引用、置空、发状态通知，注释详述了 A/B 连接交错场景。
- `:105-114` `addSelectedConnection` 同锁互斥，保证单选中连接（HSMS-SS 单连接约束）。✅

### 2. 定时器 T3/T5/T6/T7/T8 与 Linktest

- **T3**（数据应答）：`AbstractHsmsSsCommunicator.java:507-529`，DATA 且 `msg.wbit()` 时
  `replyMgr.reply(msg, hsmsSsConfig().timeout().t3())`，空则抛 `HsmsSsTimeoutT3Exception`（`:522`）；
  W=0 直接 `innerSend` 返回 empty（`:531-535`）。语义正确：T3 只约束期待应答的 primary。✅
- **T6**（控制事务）：`:481-499` SELECT_REQ / LINKTEST_REQ 走 `timeout().t6()`，超时抛
  `HsmsSsTimeoutT6Exception`。⚠️ **DESELECT_REQ 落入 default 分支**（`:539-544`
  `this.innerSend(msg); return Optional.empty();`）——见问题 P2-1。
- **T7**（未选中等待）：被动端 `awaitSelect()` 用 `executeInvokeAny(tasks, hsmsSsConfig().timeout().t7())`
  （`AbstractHsmsSsPassiveCommunicator.java:263-266`），超时记 `HsmsSsTimeoutT7Exception` 并关连接（`:268-271`）。
  Deselect 后回到 awaitSelect 时 T7 重新生效。✅
- **T8**（字符间）：`AbstractHsmsSsCommunicator.java:723-729` 新帧首读显式豁免 T8
  （注释："inter-message idle … T8 must not police it (SEMI E37)"），帧内后续读全部套 T8
  （`:844-855`），超时抛 `HsmsSsTimeoutT8Exception` 断链（`:814`）。✅
- **Linktest 周期**：`linktesting()`（`:878-929`）按 `hsmsSsConfig().linktest()` 周期等待，
  收包会 `resetLinktesting()`（`:761`、`:774`、`:931-935`）推迟心跳；Linktest 无 RSP 或非
  LINKTEST_RSP → return → invokeAny 结束 → 断链，符合"T6 到期通信失败须断开"（整理版 `:353`）。✅
  配置默认 **关闭**：`HsmsSsCommunicatorConfig.java:33` `TimeProperty.newInstance(-1.0F)`。
  实现存在、默认不启用，属配置建议（P3-2），非缺陷。
- **写超时**：`innerSend(ByteBuffer)`（`:645-674`）用 T6 作单次写上限，超时
  `f.cancel(true)` 后抛终止异常——防 TCP 零窗口把发送线程永久占住。✅

### 3. SystemBytes 管理与应答匹配

- 生成：`AbstractHsmsSsCommunicator.java:183-211`，`AtomicInteger` 自增，设备侧高 2 字节填
  sessionId、主机侧填 0，低 2 字节 `bs[2]=(byte)(n>>8); bs[3]=(byte)n;` —— **有效计数空间 16 位**，
  65536 条后回绕（ contributing factor，见 P2-2/P3-4）。字节序 MSB 在前，与整理版 §8.1.4 一致。
- 应答复用 primary 头 6-9 字节：`send(SecsMessage primary,…)`（`:279-295`）✅；
  控制应答（Select/Deselect/Linktest/Reject）均回带 `pri[6..9]`（`:332-335`、`:371-374`、`:396-399`、`:417-420`）✅
- 匹配（`HsmsSsReplyMessageManager.java`）：
  - `:99-101` 只有 secondary（偶数 function）可被当应答消费：
    ```java
    if ( msg.getStream() >= 0 && msg.getFunction() >= 0 && (msg.getFunction() % 2) != 0 ) {
        return Optional.of(msg);
    }
    ```
  - `:164-169` 数据 Pack 匹配 `stream == this.stream && function == this.function`（期望 F=primary+1，`:149`）；
  - `:30-40` `exit` 只删自己登记的 Pack（`p.belongsTo(primaryMsg)` 引用比对），防回绕后误删他人事务；
  - ⚠️ `:165-167` **控制 Pack `matches` 恒 true**（`if (this.control) { return true; }`）——见问题 P2-2。
- 迟到 secondary 的最终去向：协议层不匹配 → 入接收队列 → runtime
  `PrimaryDispatcher.java:106-108` `if (function % 2 == 0) { return; }` 静默丢弃，双层兜底 ✅

### 4. HSMS 收帧与 SECS-II 编解码

- 长度前缀：4 字节零 + 4 字节长度拼 `getLong`（`AbstractHsmsSsCommunicator.java:708-740`），
  `len<10` 短帧断链（`:742-750`，注释说明流位置已失无法重同步）、`len>maxMessageBytes` 拒收断链
  （`:752-759`，防伪造巨长度耗尽堆）✅；发送侧长度上限 `0xFFFFFFFF` 校验（`:561-563`）。
- 接收队列有界 1024、满丢最旧（`:463-465`、`:796-801`），注释明确"put 阻塞读线程会把 W 位应答
  一起卡死（自锁）"——有意的背压取舍，极端刷包时可能丢 primary（记录为设计取舍，非缺陷）。
- 头解析：`HsmsSsMessage.java:51` `head[2] & 0x7F` 取 Stream、`:56` `head[3] & 0xFF` 取 Function、
  `:61` W 位 `0x80`、`:72` deviceId 大端拼接且掩码防符号扩展 ✅；控制帧 S/F 返回 -1（`:51,56`）。
- SType 表：`HsmsSsMessageType.java:7-15` DATA=0/0、SELECT 1/2、DESELECT 3/4、LINKTEST 5/6、
  REJECT 7、SEPARATE 9，与整理版 SType 表（`04-E37…md:229-239`）逐项一致 ✅；
  Reject.req 字节 2 按原因取被拒消息 PType 或 SType（`AbstractHsmsSsCommunicator.java:408`）✅
- SECS-II 解析（`Secs2BytesParser.java`）：
  - 长度字节 1/2/3 字节编码均按大端拼装（`:62-76`）✅
  - 嵌套深度上限 64（`:18`、`:80-83`），拒绝而非 StackOverflow ✅
  - 单项上限 16 MiB **先校验后分配**（`:175`、`:250-253`），且 `size > remaining()` 拒绝（`:255-258`）✅
  - 全部 15 种格式码分支（`:97-161`），未知格式抛 `Secs2UnsupportedDataFormatException` ✅
  - 解析剩余字节校验"not reach end bytes"（`:39-41`）✅
- 解析失败 → S9F7：`AbstractHsmsSsCommunicator.java:804-811` catch `Secs2Exception` 后
  `replyIllegalData(headbs)`；`replyIllegalData`（`:684-703`）只对"奇数 function、非 S9、
  本连接为选中连接"回应，防 secondary 被应答 / S9 互答死循环 / 串链路。✅（E5 整理版
  `02-E5…md:1801`："Stream/Function 都能识别，但数据格式无法解析"）
- 编码侧值域异常统一包装：`innerSend` catch `Secs2ValueOutOfRangeException` →
  `HsmsSsSendMessageException`（`:637-642`），防非受检异常穿透调用方 ✅
- **多块消息**：HSMS-SS 无 SECS-I 式 multi-block（整条消息单帧传输，本协议不适用）；
  `secs1` / `secs1ontcpip` 包本轮未读，SECS-I 多块与 T1/T2/T4/RTY **未核实**。

### 5. S9Fx 错误报文

`AbstractGemError.java:32-93` 提供 S9F1/F3/F5/F7/F9/F11 六个原语，统一
`support.comm.send(9, func, false, Secs2.binary(refMsg.header10Bytes()))`（`:92`）——
B[10] 裸头、W=0，符合 E5 ✅。触发点核实：
- S9F7：协议层自动触发（上节）✅
- S9F3/S9F5：runtime `PrimaryDispatcher` 分发兜底（历史评审已核，本轮读到其偶数丢弃门槛
  `:106-108`；S9F3/F5 具体分支本轮未逐行复读）
- **S9F1（Device ID 不识别）**：协议层 `reading()` 未校验收到的 SessionID 与本地配置一致性，
  本轮也未在已读代码中发现自动触发点；runtime 是否处理**未核实**（见缺口 G-1）。
- **S9F9（T3 事务超时）**：原语存在（`:68-75`），T3 超时当前只抛异常给调用方，未见自动发送；
  HSMS 单块场景下可接受，多块 inquire/grant 会话不涉及。
- **S9F13（会话超时）**：gem 包 grep 无 `s9f13`，未提供。协议层未实现多块 inquire/grant 会话，
  属能力边界而非缺陷（E5 整理版 `:133`、`:1813`）。

### 6. GEM 能力核查（协议层原语 + runtime 抽查）

- **S1F13/F14 建链**：`AbstractGemCommunication.java:89-96` 发送后过滤 S1F14 取
  `COMMACK.get(r.get(0))`；`:122` 应答体 `Secs2.list(commack.secs2(), ss)`，设备侧
  ss=`L{MDLN,SOFTREV}`（`:110-115`）、主机侧 `L{}`（`:119`）——形状符合 E5 ✅
- **S1F17/F18 上线**：`:154-181` 同构，ONLACK 解析 ✅；S1F16 恒回 `OFLACK.OK`（`:151`），
  模拟器语义可接受。runtime 侧先迁状态再应答（`PrimaryDispatcher.java:118-136`，注释明确
  时序理由）✅
- **S2F33/F35/F37**：主机侧组包 `L{DATAID, L{reports/links}}`
  （`AbstractGemEventReport.java:59-61`、`:102-104`、`:165-167`），
  `toS2F33Report()` = `L{RPTID, L{VIDs}}`（`AbstractDynamicReport.java:26-30`）——标准形状 ✅；
  应答解析均带 S/F 过滤（如 `:64-65`）。设备侧解析在 runtime
  `HostCommandHandler.reportListOf`（`:587`，javadoc `:569-580` 明确"先标准形状、后兼容"
  及清空全部的抢判陷阱）✅
- **报告重定义**：`AbstractDynamicEventReportConfig.java:90-98` 先 remove 再 add，
  注释说明 CopyOnWriteArraySet.add 对相等元素静默丢弃的坑；links/events 同
  （`:162-168`、`:185-191`）。⚠️ remove+add 非原子（P3-3）。
- **S6F11**：协议层 gem 包无 s6f11（grep 证实），组包在 runtime
  `S6F11Assembler.java:117` `builder.uint4(DATAID),`，`:49-50` `public static final int DATAID = 0;`，
  javadoc `:36-37` 明确 L[3] 且 DATAID 恒 0 为 E30 允许写法 ✅（历史 P0-2 已修，见下）
- **S5F1 报警**：协议层只有 s5f2/s5f4 应答原语（`AbstractGemTerminal.java:32-56`，W 位守卫 ✅）；
  发送侧在 runtime（本轮未读发送代码，ALCD/ALTX 校验依据历史修复记录，未复核）。
- **S2F41 HCACK**：`HCACK.java` 枚举存在于 gem 包；处理在 runtime
  `HostCommandHandler.java:499` `void handleRemoteCommand(SecsMessage primary)`（方法体本轮未读，
  HCACK=0/1/4 语义依据历史修复记录 + `SessionRemoteCommandTest` 存在，**语义细节未核实**）。
- **S2F23 Trace**：gem 包 grep 无 `s2f23`；Trace 在 runtime（`SessionTraceTest` 存在），本轮未读，**未核实**。
- **S10 终端**：`AbstractGemTerminal.java:94-107` `s10fx` 有 W 位守卫且
  `support.comm.send(primaryMsg, 10, func, false, ackc10.secs2())` 复用 primary SystemBytes，
  注释自述修复动机（`:104-105`）✅
- **时钟**：`AbstractGemClock.java:106-121` 按配置 A12/A16 输出；
  `AbstractClock.java:143-145`
  ```java
  String tt = dt.format(A14);
  String ss = String.format("%02d", dt.getNano() / 10000000);
  this.a16 = Secs2.ascii(tt + ss);
  ```
  末 2 位为**厘秒**——与历史评审 P1-1 撤销结论（E5-0301 p.59：A16 末 2 位厘秒）一致 ✅；
  懒缓存 `synchronized(this)`（`:125`、`:139`）无并发问题。
- **共享状态并发**：`GemSupport.java:26-29` DATAID/RPTID 计数器均 `AtomicLong` ✅；
  报告/链接/事件集合均 `CopyOnWriteArraySet`（`AbstractDynamicEventReportConfig.java:68,133,177`），
  单操作线程安全，复合操作见 P3-3。
- **整数溢出/字节序**：解析侧长度与 deviceId 掩码正确（上文）；数值 item 的越界防护由
  `Secs2ValueOutOfRangeException` + `innerSend` 包装兜底（间接证据）；各 `Secs2UintX/IntX`
  的具体范围校验代码本轮未读，**未核实**。

---

## 问题清单

冻结政策下，P2-1/P2-2 为对照标准确认的实现缺陷；其余为低危观察、配置建议或存疑项。

| 级别 | 编号 | 问题 | 证据 | 标准依据 | 建议 |
|---|---|---|---|---|---|
| P2 | P2-1 | **发起方 Deselect.req 无 T6 事务、无状态迁移**。`send()` 的 switch 只给 SELECT_REQ/LINKTEST_REQ 开 T6 应答等待，DESELECT_REQ 落 default：发出即返回 `Optional.empty()`，不等 Deselect.rsp、不校验 status、成功后也不迁 NOT_SELECTED。调用方无法感知 Deselect 失败（对端不回时不抛 T6），本端状态停留 SELECTED 直到断链 | `AbstractHsmsSsCommunicator.java:481-483`（case 仅 `SELECT_REQ:` `LINKTEST_REQ:`）；`:539-544` `default: { this.innerSend(msg); return Optional.empty(); }` | E37 整理版 §7.4 发起方流程（`04-E37…md:175-179`）："收到 Deselect.rsp 且状态 0 → 成功，进入 NOT SELECTED；…T6 超时 → 通信失败"；§9.2 "T6 到期…须断开 TCP" | 若解冻：DESELECT_REQ 并入 T6 分支，成功后 `removeSelectedConnection(this, NOT_SELECTED)`。冻结期内在文档"能力边界"注明：优雅下线须由调用方自行发送后断链，不保证 Deselect 事务语义 |
| P2 | P2-2 | **控制事务应答匹配不校验 SType**。等待 Select.rsp/Linktest.rsp 期间，任何 SystemBytes 撞号的**偶数 function 数据帧**都会被 `Pack.matches`（control 恒 true）当应答吞掉：Select 场景解析成 NOT_SELECT_RSP → 建链误判失败；Linktest 场景 filter 不中 → 误判断链。数据事务已有 S/F 校验（历史 P1-5），控制事务是残留盲区；16 位计数回绕（65536 条）使长会话撞号概率非零 | `HsmsSsReplyMessageManager.java:164-167` `if ( this.control ) { return true; }`；`:146-147` 控制 Pack 不带 SType；撞号来源 `AbstractHsmsSsCommunicator.java:205-208`（低 16 位自增） | E37 整理版 §8.1.4.6（`:242-243`）：Reply 系统字节须与对应 .req 相同（隐含"对应"须成立）；§7.2/§7.5 应答类型分别为 Select.rsp/Linktest.rsp | 控制 Pack 增加期望 SType（req SType+1）比对，一行级改动；与 P1-5 同族，建议随解冻窗口一并处理 |
| P3 | P3-1 | 报告/链接/事件"重定义"= `remove` + `add` 两步，非原子：并发读（如 S6F11 组包遍历）存在短暂看不到该定义的窗口；两个并发重定义同一 ID 时结果取决于交错顺序 | `AbstractDynamicEventReportConfig.java:95-96` `reports.remove(r); reports.add(r);`（links `:165-166`、events `:188-189` 同） | 无直接标准条文（E30 未规定实现并发语义）；属实现质量观察 | 复合操作加同一把锁，或换 `ConcurrentHashMap<ID,Def>` 的 `put` 原子覆盖 |
| P3 | P3-2 | Linktest 默认关闭（-1.0F），E37 实现建议"务必实现 Linktest，不要只依赖 TCP keepalive"。能力已实现，属默认配置建议（部署侧应显式开启） | `HsmsSsCommunicatorConfig.java:33` `TimeProperty.newInstance(-1.0F)` | E37 整理版 §10（`:364`） | 文档/出厂配置默认给 30–60s 周期；不改冻结代码 |
| P3 | P3-4 | SystemBytes 有效空间仅 16 位（高 2 字节被 sessionId/0 占用），65536 条回绕。数据路径已被 S/F 匹配缓解；残留风险集中体现为 P2-2 | `AbstractHsmsSsCommunicator.java:193-208` | E37 整理版 §8.1.4.6 唯一性要求（`:242`） | 随 P2-2 一并处理即可；或计数初值随机化拉开空间（历史评审 P1-5 建议的备选） |
| 存疑 | Q-1 | 主动端把 Select.rsp status=1（ACTIVED）当成功进入 SELECTED。E37 **通用**整理版说非 0 即失败，但整理版明确未覆盖 E37.1 增量；HSMS-SS 通行实现常接受 1。**无法用现有参考裁定**，需 E37.1 原文（pdf/ 不在库） | `AbstractHsmsSsActiveCommunicator.java:198-204` `case SUCCESS: case ACTIVED: { /* to next Loop */ break; }` | `04-E37…md:157` vs 同文件 `:68`（未覆盖 E37.1） | 取 E37.1 原文核对后再定级；现状对互通无害（对端回 1 说明链路已激活） |

**非缺陷的设计取舍（记录备查）**：接收队列有界丢最旧（`AbstractHsmsSsCommunicator.java:796-801`）
在极端刷包下可能丢弃待处理 primary——注释已论证阻塞读线程会自锁，取舍合理；
写超时借用 T6（`:651-652`）非标准定义但方向正确。

---

## 历史修复核实（本轮行号证据）

对照 `docs/reports/code-review-2026-10-20.md` 问题清单与修复记录，抽查以下各项，**全部在当前代码中确认**：

| 历史项 | 当前证据（本轮实读） | 结论 |
|---|---|---|
| P1-5 应答只认 secondary | `HsmsSsReplyMessageManager.java:99-101`（奇数 function 直接走接收路径）+ `:164-169`（数据 Pack 校验 S/F=primary+1）+ `:36`（exit 引用比对防回绕误删） | ✅ 已修（控制帧残留见 P2-2） |
| S9F7 非法数据应答（E5） | `AbstractHsmsSsCommunicator.java:804-811` catch Secs2Exception → `replyIllegalData(headbs)`；`:684-703` 三重守卫（奇数 F / 非 S9 / 选中连接） | ✅ 已修 |
| P0-3 迟到 secondary 静默丢弃 | runtime `PrimaryDispatcher.java:106-108` `if (function % 2 == 0) { return; }`，注释完整复述 E5 依据 | ✅ 已修 |
| P0-2 S6F11 补 DATAID | `S6F11Assembler.java:49-50` `public static final int DATAID = 0;`、`:117` `builder.uint4(DATAID),`，javadoc `:36-37` L[3] 说明 | ✅ 已修 |
| P0-1 S2F33 标准形状优先 | `HostCommandHandler.java:587` `private static Secs2 reportListOf(Secs2 body)`，javadoc `:575-580` 明确"先标准、后兼容"与清空全部抢判陷阱 | ✅ 已修（方法体逐行未复读，形状判定逻辑依据 javadoc + 历史回归用例名） |
| Deselect 双向处理 | 被动 SELECTED：`AbstractHsmsSsPassiveCommunicator.java:441-449`（rsp0 + NOT_SELECTED + 保 TCP）；NOT SELECTED：`:384-389`（status=1）；主动：`AbstractHsmsSsActiveCommunicator.java:315-322` | ✅ 已修（发起方事务缺口另立 P2-1） |
| T8 消息间豁免 | `AbstractHsmsSsCommunicator.java:723-729` 注释 + `readToBuffer(channel, lenBf, false)` | ✅ 已修 |
| 选中连接引用比对 + 原子降级 | `:116-153` 两段 javadoc + `removeSelectedConnection(c, targetState)` 同临界区实现 | ✅ 已修 |
| 游离控制应答 → Reject(3)、Reject 吸收防回环 | 被动 `:370-383`、`:450-462`；主动 `:298-313`（含"缺 break 落穿"修复注释） | ✅ 已修 |
| s10f4 复用 primary SystemBytes | `AbstractGemTerminal.java:104-106` | ✅ 已修 |
| 被动绑定失败回滚 | `AbstractHsmsSsPassiveCommunicator.java:48-60`（catch IOException → close → rethrow） | ✅ 已修 |
| P1-1 撤销（A16 末 2 位厘秒） | `AbstractClock.java:143-145` `dt.getNano() / 10000000` | ✅ 与撤销结论一致 |
| P2-8 撤销（Select 状态码 0/1/2/3） | `HsmsSsMessageSelectStatus.java:8-11` 与整理版状态码表（`04-E37…md:275-278`）逐值一致 | ✅ 无需改动 |
| 报告重定义覆盖旧值 | `AbstractDynamicEventReportConfig.java:92-96`（含坑位注释） | ✅ 已修（原子性观察另立 P3-1） |

---

## 测试覆盖评估

**协议层自有测试**（glob 实测 10 个文件，行数按任务简报 1679 行，未独立统计）：
`SecsCommunicatorConfigValidationTest`、`InvokeAnyCancellationTest`、`Secs2BytesParserDepthTest`、
`Secs2AsciiJsonEscapeTest`、`PassiveBindFailureTest`、`HsmsSsReplyMessageManagerTest`、
`HsmsSsMessageTypeTest`、`HsmsSsMessageTest`、`HsmsSsIntegrationTest`、`ActiveCircuitWaitPatternTest`。
选点准确（都打在历史缺陷/回归上），但相对约 1.98 万行主代码，**协议层单测密度确实偏薄**。

**端到端补偿**（secs-cli/src/test，glob 实测 26 个文件）：`SessionDynamicReportTest`、
`SessionS6F11Test`、`SessionRemoteCommandTest`、`SessionUnsupportedMessageTest`、
`SessionSelectedLinkSurvivesRogueHostTest`、`SessionTraceTest`、`SessionAlarmTest`、
`SessionTerminalMessageTest`、`SessionControlStateTest`、`SessionEquipmentConstantTest` 等，
以"设备被动端 + ACTIVE 客户端"真实 HSMS 链路覆盖 GEM 能力面。历史评审记录的
516 测试全绿与夹具稳定性修复（awaitPeer）说明该宿主是当前协议层事实上的主测试面。
**注意**：cli 测试经过 runtime 组装路径，天然带"夹具与实现同源"盲区（历史三个 P0 的根因），
标准形状金样本测试的建议仍然有效。

**高风险未覆盖路径**（按本轮代码审读推断；测试文件体未逐一阅读，覆盖判断以文件名与历史报告为据）：
1. **发起方 Deselect 事务**（P2-1）：无任何测试名指向主动发 Deselect.req 等 rsp/超时——与缺陷存在互为印证。
2. **控制帧 SystemBytes 撞号**（P2-2）：`HsmsSsReplyMessageManagerTest` 按历史报告只加了
   `collidingPrimaryIsNotConsumedAsReply` / `controlFrameIsNotTreatedAsReply`，未见"数据 secondary 撞控制事务"用例。
3. **S9F1 触发**（SessionID 不匹配）：协议层无校验点、无对应测试（缺口 G-1）。
4. **secs1 / secs1ontcpip（SECS-I、SECS-over-TCP）**：协议层测试清单中无对应文件，主代码存在但本轮未读——覆盖与符合性双缺口。
5. **T8 帧内停顿、超长/超短帧攻击帧**：reading() 有防护代码，但除 T7 静默对端用例（历史提及）外未见对应注入测试。
6. **gem 包 47 个类**：协议模块内无直接单测，全部依赖 cli E2E；ACK 枚举非法值解析（如 DRACK/HCACK 越界码）路径未验证。

---

## 置信度说明

**高置信（全文精读）**：`AbstractHsmsSsCommunicator`、`AbstractHsmsSsPassiveCommunicator`、
`AbstractHsmsSsActiveCommunicator`、`HsmsSsReplyMessageManager`、`HsmsSsMessage`、
`HsmsSsMessageType`、`HsmsSsMessageSelectStatus`、`HsmsSsCommunicatorConfig`、
`Secs2BytesParser`、`Secs2BytesPackBuilder`、`AbstractGemError`、`AbstractGemCommunication`、
`AbstractGemClock`、`AbstractGemTerminal`、`AbstractGemEventReport`、
`AbstractDynamicEventReportConfig`、`AbstractDynamicReport`、`GemSupport`；
E37 整理版关键章节、E5 整理版 S9 相关条目（grep 定位）、历史评审报告全文。
P2-1/P2-2 的缺陷判定基于上述实读代码，置信度高。

**中置信（定点抽查）**：`AbstractSecsCommunicator`（线程模型/生命周期段落）、
`AbstractClock`（A12/A16 输出段）、`PrimaryDispatcher`（分发入口段）、
`S6F11Assembler` / `HostCommandHandler`（grep 定位 + javadoc）。

**未核实（明确缺口）**：
- G-1：S9F1 自动触发点（收到 SessionID≠本地配置时）——协议层未见，runtime 未全读，不能断言"缺失"，只能断言"本轮未找到"。
- S2F41 HCACK 具体取值语义、S5F1 发送侧校验、S2F23 Trace 实现（runtime 方法体未读）。
- `secs1` / `secs1ontcpip` / SML 解析全部未读；SECS-I 多块、T1/T2/T4 符合性未核查。
- `Secs2Item` 格式码表与 `Secs2UintX/IntX` 数值范围校验代码未逐行复核（沿用历史评审核验结论 + 本轮间接证据）。
- Q-1 需 E37.1 英文原文裁决；`docs/reference/pdf/` 为版权文档不在版本库，本轮无法取得。
- 测试文件正文未阅读，覆盖评估基于文件清单、历史报告与缺陷互证；1679/19800 行数引自任务简报。
- 主代码文件总数（简报 214）未独立清点（根包 glob 截断于 200 条）。

*评审人：协议符合性专项（维度02）；只读分析，唯一写入为本文件。*
