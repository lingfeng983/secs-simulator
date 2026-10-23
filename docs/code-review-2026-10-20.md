# 全量代码评审 · 2026-10-20（协议符合性专项）

评审基线：`master @ 68883db`，工作区干净。

**与 2026-10-17 评审的差异**：上一轮以代码质量/HTTP 安全为主；本轮按用户要求做
**协议符合性专项**——把会话层、协议层逐条对照 SEMI E4 / E5 / E30 / E37.1 核验，
并复核 10-17 之后新增的功能（报警、终端、动态报告、目录、手动 SML、审计）。
上一轮记录的 20 项修复抽查全部落实（SSE 独立池、一次性票据、ETag、路径穿越、
常时比较、配置文件名约束等）。

总体结论：**架构与安全面依旧健康，但协议层有 3 个会导致真实主机互通失败的问题
（P0）**：S2F33 解析形状错、S6F11 缺 DATAID、默认配置下主机应答被误当 Primary
回 S9F5。三个都是"测试夹具自己发的形状和实现同源"造成的盲区——夹具没按标准形状
发报文，测试全绿也不能证明能对接真主机。

---

## 一、问题清单

### P0（真实主机对接会失败）

| # | 位置 | 问题 |
|---|---|---|
| **P0-1** | `HsmsSessionRuntime.java:1429` | **S2F33 解析取错元素，标准形状必失败**。`handleDefineReport` 取 `primary.secs2().get(0)` 当报告列表；E5 规定 S2F33 体是 `L[2]{DATAID, L[n]{L[2]{RPTID, L[m] VID}}}`，标准主机发来时 `get(0)` 是 DATAID 标量，后续 `reportList.get(i)` 抛 `Secs2IrregalDataFormatException`（`AbstractSecs2.java:91`）→ catch → **DRACK=1**，动态报告定义完全不可用。测试夹具（`SessionDynamicReportTest.java:192`）发的是 `L{L{报告列表}, L{}}` 的非标形状，掩盖了问题。对照 S2F35（`:1457`）已有 `body.size() >= 2 ? body.get(1) : body.get(0)` 的兼容写法，S2F33 应同样处理（并校验取到的确是 List）。 |
| **P0-2** | `S6F11Assembler.java:106` | **S6F11 报文体缺 DATAID**。`builder.list(builder.uint4(ceid), builder.list(reports))` 生成 `L[2]{CEID, L{报告}}`；E30 规定是 `L[3]{DATAID U4, CEID U4, L{报告}}`。按标准 L3 解析的主机要么报长度错、要么把 CEID 当 DATAID、报告列表当 CEID——**事件整体错位**。javadoc 里写的结构（`S6F11Assembler.java:24-31`）也自证是 L2。相关测试断言（`SessionDynamicReportTest` 的 `nextS6F11().get(1).get(0)`）同样按 L2 写，需一起改。 |
| **P0-3** | `HsmsSessionRuntime.java:1144`（`onPrimary`） | **默认配置（W=0）下，主机应答被误当 Primary 回 S9F5**。证据链：发送端 W=0 不登记应答管理器（`AbstractHsmsSsCommunicator.java:460-493`）→ 主机回的 S6F12/S6F2 无匹配 → 进接收队列（`:700` `replyMgr.put` 返回非空）→ `selectedTask` DATA 分派（`AbstractHsmsSsPassiveCommunicator.java:378`）→ `onPrimary` 无奇偶判断 → `replyUnsupported`（`:1239`）对 stream=6 回 **S9F5**。E5：S9F5 只能对无法识别的 **primary** 发送；迟到的 secondary 应静默丢弃。后果：默认 `s6f11Wbit=false` 时，任何按惯例回 S6F12 的主机都会收到一条伪 S9F5（SystemBytes 与 S6F12 相同的新 primary），严格的主机会走异常处理甚至断链。协议层冻结不动的话，**会话层一行修复**：`onPrimary` 开头 `if (primary.getFunction() % 2 == 0) { return; }`。 |

### P1（协议语义偏差 / 能力缺口）

| # | 位置 | 问题 |
|---|---|---|
| P1-1 | `AbstractClock.java:137-149`、`TraceManager.java:48` | **A16 时间戳末 2 位填了百分秒而不是时区偏移**。S2F18 默认 ClockType=A16（`AbstractGemConfig.java:63`），生成 `"yyyyMMddHHmmss"+百分秒`；S6F1 的 STIME 同样（`yyyyMMddHHmmssSS`）。E30 的 16 字符时间格式是 `CCYYMMDDHHMMSS±ZZ`（末 2 位是**时区**）。标准主机把 "00".."99" 当偏移解析，时间错位最多 ±99 小时或直接解析失败。对海外主机/跨时区产线必错；单机自闭环看不出来。 |
| P1-2 | `HsmsSessionRuntime.java:647/954/1592` | **控制状态不约束发送**。`pumpEvents/pumpTraces/emitCeid` 只判 `communicatable`（TCP SELECTED），不判 `controlState`。两个偏差：① S1F15 OFFLINE 后仍持续发 S6F11/S6F1（E30：offline 不得发 unsolicited）；② HSMS 建链后、S1F13 通信建立前就开始发事件（E30：未建立通信只允许 S1F13/S9Fx 等少数消息）。`controlState` 字段存在但只用于界面显示。 |
| P1-3 | `HsmsSessionRuntime.java:1239` | **S2F41/F42 远程命令未实现**（落 S9F5）。GEM 核心能力（START/STOP/PP-SELECT），多数主机上线流程会发。建议至少支持空命令集 + HCACK 结构正确的 S2F42，或文档明示边界。 |
| P1-4 | 同上 | **S2F13/F14、S2F15/F16（ECV 读写）未实现**（落 S9F5）。E30 设备常量是主机调机必备；与 SVID 目录接口同构，实现成本低。 |
| P1-5 | `AbstractHsmsSsCommunicator.java:159-181`、`HsmsSsReplyMessageManager.java:87` | **SystemBytes 生成与匹配均不区分方向**。生成侧：16 位自增 + sessionId 前缀，长会话 65536 条后回绕；匹配侧：`replyMgr.put` 只比 systemBytes、不校验 function 奇偶——若主机 primary 恰好与我们等待中的 W 位请求 SystemBytes 相同，会被**误当应答吞掉**（主机消息凭空消失）。概率低但排查极难。缓解：W 等待注册时连带校验到达消息 function 为偶数（协议层一行）；或 systemBytes 用随机初始值拉开空间。 |

### P2（健壮性 / 代码质量）

| # | 位置 | 问题 |
|---|---|---|
| P2-1 | `HsmsSessionRuntime.java:1677-1686`、`S6F11Assembler.java:151` | 未知 SVID 一律回 `U4 0`。与主机按 SVID 表解析的类型可能不符（解析成垃圾值）。业界确有回 0 的做法，建议至少在 S1F11 名字列表里可区分（如名字留空已是现状），文档注明。 |
| P2-2 | `HsmsSessionRuntime.java:1487` | S2F37 的 CEED 若主机按 B[1] 发（少数实现），`getBoolean` 抛异常 → ERACK=1。E30 定义为 Boolean，行为可接受；可选做 B/BOOLEAN 双兼容。 |
| P2-3 | `HsmsSessionRuntime.java:1178` | S2F31 时间不校验格式，恒回 TIACK=0。E30：格式非法应回 TIACK=1。 |
| P2-4 | `HsmsSessionRuntime.java:730` | ALCD category 允许 0。E30 报警类别是 bit 组合（1 安全/2 控制/4 故障等），0 无定义。建议收紧为 1..7。 |
| P2-5 | `HsmsSessionRuntime.java:738-741` | `emitAlarm` 的 ALTX 含非 ASCII（中文）时，编码错误发生在异步发送线程，HTTP 已回 202 但主机收不到、仅 `lastError` 有记录。入口应校验可编码并回 400。 |
| P2-6 | `HsmsSessionRuntime.java:462` | `message.body().size() < 0` 恒 false 的死代码（意图应是 `isEmpty()` 分支）。 |
| P2-7 | `HttpApiServer.java:491` | SSE 票据 map 无数量上限，过期清理仅在签发时触发。低风险（登录即需 viewer 凭据），可在清理时顺带限制单账号未用票据数。 |
| P2-8 | `HsmsSsMessageSelectStatus.java:8-11` | 枚举 `SUCCESS/ACTIVED/NOT_READY/ALREADY_USED`（0/1/2/3）与 E37.1 措辞的对应建议对照原文核对一次：使用处"已有另一连接占住回 3、已选中再收 Select.req 回 1"（`AbstractHsmsSsPassiveCommunicator.java:315-328/381-384`）与常见实现（already-selected 回 1）方向相反的印象需要原文确认。值域本身无冲突，暂列存疑。 |

### 复核 10-17 已修项（抽查通过）

SSE 独立线程池（`HttpApiServer.java:331`）、一次性票据原子消费（`:524-530`）、
ETag/304/nosniff、静态 fallback 仅无扩展名路径、preview 参数非法 400、
`MessageDigest.isEqual` 常时比较 + 全账号遍历（`AccessControl.java:170-185`）、
401/403 分离并审计 403、server 模式强制凭据（`Main.java:84-89`）、
配置文件名防穿越（`ConfigStore.requireFileName`，与 `SessionLauncher` 共用）、
原子写盘 + 先校验后落盘、`lastFireMs` volatile、UTF-8 自检文件清理。

---

## 二、核验通过项（协议符合性）

- HSMS 10 字节头编解码：SessionID 2B / W-bit 在头字节高位 / Stream / Function / PType / SType / SystemBytes；deviceId 0..65535 构造期校验；回复沿用 primary 头（`AbstractHsmsSsCommunicator.java:244-272`）✅
- SType 值：SELECT.req=1 / rsp=2、DESELECT=3/4、LINKTEST=5/6、REJECT=7、SEPARATE=9（`HsmsSsMessageType.java`）；Reject 引用原消息的 PType/SType 字节（`createRejectRequest`）✅
- 定时器默认值与语义：T3=45s（数据应答）、T5=10s（主动端重连退避，`AbstractHsmsSsActiveCommunicator.java:46`）、T6=5s（控制事务）、T7=10s（被动端未选中，`AbstractHsmsSsPassiveCommunicator.java:229`）、T8=6s（消息内字符间隔，消息间不套 T8——回归已修）✅
- 被动端状态机：NOT_SELECTED 收 DATA 回 Reject(4)、LINKTEST 应答、SEPARATE 即断、已选中收 Select.req 再应答 ✅
- SECS-II 格式码表全对：L=0x00、B=0x20、BOOLEAN=0x24、A=0x40、J=0x44、I8=0x60、I1=0x64、I2=0x68、I4=0x70、F8=0x80、F4=0x90、U8=0xA0、U1=0xA4、U2=0xA8、U4=0xB0；W 位只在消息头、item 层无 W 位 ✅
- Gem 应答形状：S1F2=`L{MDLN,SOFTREV}`、S1F14=`L{COMMACK,L{MDLN,SOFTREV}}`、S1F16 OFLACK、S1F18 ONLACK、S2F32 TIACK、S9Fx=`B[10]`（E5 即裸头字节）✅
- S10F3=`L{TID B, TEXT A}` → S10F4 ACKC10，且显式 `send(primary,10,4,...)` 规避协议层 `s10f4` 发新 primary 的缺陷（已知风险，规避正确）✅
- S5F1=`L[3]{ALCD B, ALID U4, ALTX A}` + W 等 S5F2；ALCD bit7 置位 ✅
- S6F1=`L[4]{TRID U4, SMPLN U4, STIME A16, L{值按样本×SVID 扁平}}`；S2F24 TIAACK 0-5、DSPER/REPGSZ/TOTSMP 校验、TOTSMP=0 终止 ✅（STIME 的 A16 内容格式见 P1-1）
- S1F3 空列表=全部 SVID、顺序保持；S1F11 空列表=全部；S2F37 空列表=全部 ✅
- 主机动态报告覆盖层语义（S2F33/35 空列表清空回退本地、主机定义不注入 UNIT_ID）自洽且实现与 ADR-0006 一致 ✅（S2F33 形状除外，见 P0-1）
- 未知 Stream/Function 回 S9F3/S9F5、S9 自身不回（防死循环）✅（secondary 误判除外，见 P0-3）
- 安全面：鉴权/票据/SSE 背压/路径穿越/请求体分级上限（4K/64K/256K）/TLS（JDK 25 默认禁老协议，keystore 密码不落日志）✅
- 前端：无 `dangerouslySetInnerHTML`/`eval`；token 走 `#hash` 读后即抹、存 sessionStorage；SSE 断线重连 + 票据刷新 + 明示不补发 ✅

---

## 三、建议修复顺序

1. **P0-3（一行）**：`onPrimary` 丢弃偶数 function 的到达消息。
2. **P0-2**：S6F11 补 DATAID（U4，可用 0），同步改 `S6F11Assembler`、S6F11 相关测试断言、前端如有按位解析的展示。
3. **P0-1**：`handleDefineReport` 按 S2F35 的写法兼容 `L{DATAID,L{...}}` 与 `L{L{...}}`，并把**标准形状**加进测试夹具（夹具同时保留非标形状用例防回退）。
4. **P1-1**：时间格式——`TraceManager` 的 STIME 与 `AbstractGemConfig.clockType` 侧，改为 E30 的 `±TZ` 结尾（冻结层不便动的话，TraceManager 先改，S2F18 通过配置默认 A12 规避并在文档注明）。
5. **P1-2**：`pumpEvents/pumpTraces/emitCeid` 增加 `controlState` 门槛（COMMUNICATING/ONLINE 才发 unsolicited）。
6. **P1-3/P1-4**：S2F41/F42、S2F13/F15 按需排期，或先在 README「能力边界」明示。
7. **P1-5**：协议层 `replyMgr` 匹配加 function 奇偶校验（改动极小，收益是消除一类极难排查的丢消息）。
8. P2 随批清理。

> 测试方法论备注：本轮三个 P0 全部源于"夹具与实现同源"。建议补一类
> **标准形状金样本测试**：直接用 E5/E30 文档中的报文结构字面量构造主机侧报文
> （而不是复用项目的 builder 快捷方式），并在 CI 里保留。

---

*评审方法：逐文件精读会话层（HsmsSessionRuntime/S6F11Assembler/TraceManager/
HostReportTable/Secs2Mapper/Read）+ 协议层关键路径（HSMS 状态机与收发管线、
Secs2 编解码、Gem 应答形状）+ HTTP/安全面全量 + 前端与打包抽查；
对照标准为 SEMI E4（SECS-I）、E5（SECS-II）、E30（GEM）、E37.1（HSMS-SS）。*

---

## 四、修复记录（2026-10-22）

### 已修

| 项 | 修复位置 | 做法 | 回归用例 |
|---|---|---|---|
| **P0-1** | `HsmsSessionRuntime.reportListOf` / `linkListOf` | 抽出形状判定：**先按标准形状判**（第 1 元素非列表 = DATAID，报告/链接列表在第 2 元素），失败再回退到省略 DATAID 的非标形状。不能反过来先看 `第 2 元素是 L[0]`——标准形状的"清空全部"正是这个形状，抢判会把 `DATAID=0` 当成"报告 0"写进覆盖层 | `SessionDynamicReportTest#acceptsBothStandardAndLegacyS2F33Shapes`、`#malformedS2F33IsRejectedWithDrackOne`；夹具 `defineReport/linkEvent` 已改发标准形状 |
| **P0-2** | `S6F11Assembler.build` | 改为 `L[3]{U4 DATAID=0, U4 CEID, L{报告}}`，导出常量 `DATAID`；javadoc 同步 | `SessionS6F11Test`（断言 `L[3]` 与 DATAID=0）、`SessionDynamicReportTest`、`SessionAfterEventTest`、`SessionOnConditionTest` 的取值下标一并改为 `get(1)`/`get(2)` |
| **P0-3** | `HsmsSessionRuntime.onPrimary` | 入口 `if (function % 2 == 0) return;`——E5 规定 S9Fx 只对 primary 发送，迟到 secondary 静默丢弃 | `SessionUnsupportedMessageTest#secondaryIsSilentlyDroppedInsteadOfS9F5`、`#lateRepliesToOurRequestsAreNotTreatedAsPrimary` |
| **P1-2** | `HsmsSessionRuntime.canSendUnsolicited` | 计划驱动的 `pumpEvents` / `pumpTraces` 增加控制状态门槛：只有 `ONLINE` 才发 S6F11/S6F1。手动 `emitCeid` 是操作员显式动作，保留不受限 | `SessionControlStateTest#scheduledReportsWaitForOnline`（COMMUNICATING → 不发；S1F13 → 仍不发；S1F17 → 发；S1F15 → 停发） |

**P1-1 撤销**：核对 E5-0301 p.59 与 E30-1103 §4.10 后确认 A16 末 2 位是**厘秒**不是时区偏移，
原评审结论不成立（详见 `docs/reference/project/E5-SECS-II-项目对照.md` §7 修订记录）。
可选收尾：`TraceManager` 的 STIME 用毫秒两位，若要严格 cc 口径可统一（非缺陷）。

| **P1-5** | `HsmsSsReplyMessageManager.put` | 只有 secondary（偶数 function）能当应答消费；控制帧 `getStream()/getFunction()` 为 -1，走原路径不受影响。消除"主机 primary 与等待中的 W 位请求 SystemBytes 撞号被吞掉"的丢消息风险 | `HsmsSsReplyMessageManagerTest#collidingPrimaryIsNotConsumedAsReply`、`#controlFrameIsNotTreatedAsReply`；夹具 `dataMessage` 由 S1F1（primary）改为 S1F2（secondary） |

**P1-5 的排查教训（重要）**：第一版实现后 `HsmsSsIntegrationTest` 出现 4 例 120s 超时，
当时判断"与改动无因果关系"并回滚。第二次排查时发现**基线本身在当时的运行环境下就不稳定**，
真正原因是本地残留的测试进程占用端口/通道——干净环境下同一改动的集成测试 **连跑 3 次全绿（14.5s/次）**。
教训：Windows 上被 `timeout` 强杀的 Maven/JVM 会留下孤儿进程，后续"120s 超时"应先确认环境干净，
再归因到代码。另外，当时用作"样张"的 `S-1F-1 type=SELECT_REQ` 其实是控制帧的正常表现
（控制帧没有 stream/function），不该被当成异常信号——诊断输出必须连同预期语义一起读。

| **P1-3** | `HsmsSessionRuntime.handleRemoteCommand` | 新增 S2F41 → S2F42。按 E30 执行条件回 HCACK：未建通信/离线 `4`、ONLINE `0`、RCMD 非法 `1`；CPACK 为空列表。**不做真实设备动作**（无命令集定义），但不再落 S9F5 | `SessionRemoteCommandTest` 6 例（ONLINE 接受 / 通信前 4 / S1F13 后未 ONLINE 仍 4 / 离线拒绝 / 形状非法 1 / 不落 S9F5） |

### P2 批（2026-10-23）

| 项 | 修复 | 用例 |
|---|---|---|
| P2-1 | S1F11 未定义 SVID 的名字由空串改为 `UNKNOWN`，与"有定义但没命名"可区分 | `SessionSvNamelistTest#namelistReturnsNamesAndUnits` |
| P2-2 | S2F37 的 CEED 兼容 `B[1]`（非 0 即真）与 BOOLEAN 两种形态 | `SessionDynamicReportTest#acceptsBinaryCeedInS2F37` |
| P2-3 | S2F31 校验 TIME 形状（A12/A16 + 全数字），非法回 `TIACK=1`，不再恒回 0 | `SessionControlStateTest#invalidTimeSetIsRejected`、`#a12TimeSetIsAccepted` |
| P2-4 | ALCD 类别收紧为 1..7（0 在 E30 无定义） | `SessionAlarmTest#categoryZeroIsRejected` |
| P2-5 | ALTX 非 ASCII 在入口即拒绝（原本异步线程编码失败、HTTP 已回 202） | `SessionAlarmTest#nonAsciiAlarmTextIsRejectedAtEntry` |
| P2-6 | 删掉 `size() < 0` 死条件，改为 `body.isEmpty()` | 由既有 SML 相关用例覆盖 |
| P2-7 | SSE 票据单账号未消费上限 8 张，超出淘汰最旧 | `HttpApiServerAuthTest#ticketIssuanceIsBoundedPerUser` |
| P2-8 | `HsmsSsMessageSelectStatus` 与 E37.1 措辞核对：**P2-8 的疑点不成立**，本项目"已选中再收 Select.req 回 1（ACTIVED）"与常见实现一致，无需改动 | 既有 `HsmsSsIntegrationTest` 覆盖 |

**实现中踩到的一个坑（已写进代码注释）**：`Secs2Ascii.size()` 返回的是**字符数**而不是元素数。
S2F31 校验里原本写 `body.size() != 1` 判"体里只有一个元素"，对 A16 恒为 false，
导致合法时间被误判为非法——是既有用例 `timeSetIsAcknowledged` 把它抓出来的。

### 已修复：cli 测试套件的既有稳定性问题（2026-10-23）

清理 P2 时发现 `secs-cli` 全量测试**偶发失败**，失败点每次不同
（`SessionManualSendTest` / `SessionControlStateTest` / `SessionAfterEventTest` / `SessionOnConditionTest`）。

**先确认归属**：把运行时与测试改动**全部 stash 回干净基线**后复跑，同样的分散性失败依旧出现
（如 `delayedEventsInOrder` 期望 2001 实得 0、`S1F15 后应回到 OFFLINE` 实得 ONLINE）。
所以这是仓库既有的测试夹具问题，与协议/会话改动无关；
单独跑任何一个测试类都稳定通过。

**根因**：这些用例都是"设备侧起 HSMS 被动端 + ACTIVE 客户端连上去"。
`openAndWaitUntilCommunicating()` 只保证**客户端**走完 connect → Select.req → Select.rsp；
设备侧把连接加入选中集合、回写 `communicatable` 是另一线程的事。同一 JVM 内串行跑 70+ 个
这样的用例时窗口被放大：客户端说"连上了"，设备侧 `hasPeer()` 还是 false，
于是发给设备的报文被拒、或设备侧状态迁移还没发生就被断言。

**修复**：新增 `SessionTestSupport.awaitPeer(session)`（轮询 `hasPeer()`，5s 上限并带
`lastError` 诊断），在 14 个测试类的夹具里 `openAndWaitUntilCommunicating()` 之后统一调用，
共 22 处。

**验证**：修复前 `secs-cli` 套件约 1/3 概率失败；修复后**连跑 4 次全绿**，
全量 `mvn clean verify -DskipITs` **连跑 3 次全绿**。

> 过程记录：批量插入时按"文件内第一个 session 变量"推断作用域，
> `SessionManualSendTest` 里有两段会话（`session` / `session2`），第二段被错插成等 `session`，
> 表现为稳定失败而不是偶发。已改为 `session2`。**批量改测试代码同样要认真核对作用域**，
> "全部失败"和"偶发失败"是完全不同的信号。

### P1-4 已实现（2026-10-23，用户确认语义为"可写 + 落盘保留"）

| 项 | 实现 |
|---|---|
| 模型 | `EquipmentConstantDef(id, name, type, value, unit)`；`EquipmentSpec.constants` 可选段，构造期校验 ECID 唯一 |
| 配置 | `equipment.constants` 读写；旧配置无此段照常工作，不升 schema |
| S2F13/F14 | 空列表=全部（ECID 升序）；指定列表按请求顺序一一对应，未知 ECID 回 `U4 0` |
| S2F15/F16 | EAC：`0` 接受 / `1` 未知 ECID / `2` 越界或形状非法 / `3` 未 ONLINE |
| 落盘 | 成功后经 `ConstantSaver` 回调写回配置文件：读原文 → 只换 `equipment.constants` → 重新序列化 → 原子替换 |
| 一致性 | **先落盘再改内存**：写盘失败时内存保持旧值，避免"设备显示新值、重启变回旧值" |
| 权限 | 走原有 HSMS 通道（主机身份），HTTP 侧无新增写入口 |
| 测试 | `SessionEquipmentConstantTest` 8 例，含"写入后重新解析同一文件仍在"与"其它配置段不被冲掉" |

**为什么整批不生效**：主机发一批常量时通常期待"要么都成、要么都不成"；
部分写入会让设备停在"主机以为写成了、其实只写了一半"的状态，比直接拒绝更难排查。

### 并发缺陷已修：`pumpNow` 缺少整体互斥（2026-10-23）

排查全量套件偶发失败时发现的**真实产品缺陷**（不只是测试问题）：

`pumpNow()` 同时被心跳线程（每 100ms）和外部线程（测试、确定性步进）调用，
而它推进的状态 `lastFireMs` / 条件去抖状态 / 延迟队列彼此关联。
分开加锁挡不住交错，典型症状是"到点却没发"：心跳线程先推进了去抖状态，
外部线程再来时看到已触发，于是谁都没发。

修复：`pumpNow` 整体加锁（`pumpLock`）。锁内只做状态推进与投递，
不做等待式 IO——S6F11 的 W 位等待走独立 ACK 队列，不会把心跳卡在 T3 上。

**同批修的时序问题（2026-10-23，用户确认边界为"只改等待结果类"）**

1. **手动时钟仍启动真实时间心跳**（`startTicker`）：`ManualEngineClock` 意味着"时间由调用方推进"，
   再起 100ms 真实心跳会与调用方的 `pumpNow` 抢着推进——调用方把时钟拨到 t=1s，
   心跳先跑完触发，调用方再 `pumpNow` 就什么都不做，表现为"拨到点却没发"。
   修复：手动时钟下不启动 ticker，确定性由时钟保证而不是靠调度碰运气。
2. **控制状态先应答后迁移**（S1F13/F15/F17）：主机收到应答时状态尚未生效，
   存在"主机已按 ONLINE 行动、设备还在 COMMUNICATING"的窗口。
   修复：先迁状态，再回应答。
3. **`SseStallTest` 读到首字节就断言 `activeStreams()==1`**：读到首字节只说明响应头已发出，
   服务端登记流是另一步。改为等待就绪（用例意图"卡死会被看门狗踢掉"不变）。

按约定**未改动**的用例：`HsmsSsIntegrationTest#silentPreSelectPeerIsDroppedByT7`、
`SseStallTest` 的看门狗段、`SessionDynamicReportTest#drain` 的"确认没有更多消息"。
这些是**故意验证超时或"确实不出现"**，改成事件驱动会改变测试意图。

**验证**：修复前全量套件约 1/6 概率偶发失败（失败点随机分散）；
修复后 `mvn -o clean verify -DskipITs` **连跑 6 次全绿**。

### P1-4 已实现（2026-10-23，用户确认语义为"可写 + 落盘保留"）

| 项 | 实现 |
|---|---|
| 模型 | `EquipmentConstantDef(id, name, type, value, unit)`；`EquipmentSpec.constants` 可选段，构造期校验 ECID 唯一 |
| 配置 | `equipment.constants` 读写；旧配置无此段照常工作，不升 schema |
| S2F13/F14 | 空列表=全部（ECID 升序）；指定列表按请求顺序一一对应，未知 ECID 回 `U4 0` |
| S2F15/F16 | EAC：`0` 接受 / `1` 未知 ECID / `2` 越界或形状非法 / `3` 未 ONLINE |
| 落盘 | 成功后经 `ConstantSaver` 回调写回配置文件：读原文 → 只换 `equipment.constants` → 重新序列化 → 原子替换 |
| 一致性 | **先落盘再改内存**：写盘失败时内存保持旧值，避免"设备显示新值、重启变回旧值" |
| 权限 | 走原有 HSMS 通道（主机身份），HTTP 侧无新增写入口 |
| 测试 | `SessionEquipmentConstantTest` 8 例，含"写入后重新解析同一文件仍在"与"其它配置段不被冲掉" |

**为什么整批不生效**：主机发一批常量时通常期待"要么都成、要么都不成"；
部分写入会让设备停在"主机以为写成了、其实只写了一半"的状态，比直接拒绝更难排查。

### 并发缺陷已修：`pumpNow` 缺少整体互斥（2026-10-23）

排查全量套件偶发失败时发现的**真实产品缺陷**（不只是测试问题）：

`pumpNow()` 同时被心跳线程（每 100ms）和外部线程（测试、确定性步进）调用，
而它推进的状态 `lastFireMs` / 条件去抖状态 / 延迟队列彼此关联。
分开加锁挡不住交错，典型症状是"到点却没发"：心跳线程先推进了去抖状态，
外部线程再来时看到已触发，于是谁都没发。

修复：`pumpNow` 整体加锁（`pumpLock`）。锁内只做状态推进与投递，
不做等待式 IO——S6F11 的 W 位等待走独立 ACK 队列，不会把心跳卡在 T3 上。

**残留问题（未解决，需单独立项）**：全量套件仍有约 1/6 概率的偶发失败，
失败点随机落在不同模块的不同用例
（`SessionOnConditionTest`、`SessionControlStateTest`、`SseStallTest`、
`HsmsSsIntegrationTest#silentPreSelectPeerIsDroppedByT7`）。
已确认：
- 单独跑任何失败用例都是稳定通过（5–6 次连跑全绿）
- 把我的改动全部 stash 回干净基线，同样的分散性失败依旧出现
- 加 `pumpNow` 互斥后失败率未显著下降

说明这是测试执行环境级的时序不稳，而非某个业务路径的逻辑错误。
下一步建议：逐个用例注入"确定性等待"（把 `poll(3s)` 类的赌时序改成事件驱动），
而不是继续加锁——需要单独一轮，不宜与协议改动混在一起。

### 验证

`mvn -B -o clean verify -DskipITs`（GraalVM 25.0.4）：**BUILD SUCCESS**，后端 **516 个测试全绿**
（json 110 / protocol 48 / core 90 / app 32 / api-http 106 / runtime 56 / cli 74）；
前端 Vitest 22 例全绿。`HsmsSsIntegrationTest` 单独连跑 3 次稳定通过。
