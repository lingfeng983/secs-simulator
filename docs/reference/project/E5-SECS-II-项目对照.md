# SEMI E5（SECS-II）项目对照手册

> **本文定位**：这不是标准整理，而是**标准条款 ↔ 本项目代码**的对照表。
> 回答"消息编解码怎么写、SML 解析边界在哪、ACK 码值与实现的差异在哪"。
> 标准正文请读 [../standards/02-E5-SECS-II-中文整理.md](../standards/02-E5-SECS-II-中文整理.md)。
>
> **重要说明**
>
> 本文是**原创技术参考整理**，不是 SEMI E5 标准的官方译文，也不能替代标准原文。
> SEMI E5 是 SEMI 的**付费版权文档**，不提供合法免费全文下载；正式合规实现必须
> 以购买的标准原文为准。
>
> 官方获取渠道（本文编写时价格，可能有调整）：
>
> - [SEMI E5 官方商店（Member $286 / Non-Member $466）](https://store-us.semi.org/products/e00500-semi-e5-specification-for-semi-equipment-communications-standard-2-message-content-secs-ii)
> - [SEMIViews 在线订阅](https://www.semi.org/en/blogs/semi-standards-update-major-revisions-task-force-expansion-and-responding-to-growing-industry-requirements)
> - 参考解读（免费）：[PEER Group – SEMI E5](https://www.peergroup.com/definition-of-standard/semi-e5)、[Cimetrix – SECS-II](https://www.cimetrix.com/secs-ii)、[Einnosys – SECS-II](https://www.einnosys.com/semi-e5-secs-ii-protocol-standard)
>
> 本文中的报文结构、格式码、ACK 码值属于行业公开的技术事实（多数开源实现与设备
> 手册均有记载）；ACK 非零码的具体措辞各版本可能略有差异，标注为"通行取值"。
>
> **更新基线**：2026-10-20（对应 `master @ 68883db`）。代码位置与 ⚠️ 标记可能滞后于最新提交，
> 引用前请以实际代码为准。

---

## 1. 在 SECS 协议族中的位置

| 标准 | 作用 | 本项目实现 |
|---|---|---|
| SEMI E4 (SECS-I) | 串行链路上的报文传输（块、校验和、T1–T4） | `secs-protocol/.../secs1/` |
| SEMI E37 / E37.1 (HSMS / HSMS-SS) | TCP/IP 链路上的报文传输、连接与 Select 状态机、T5–T8 | `secs-protocol/.../hsmsss/` |
| **SEMI E5 (SECS-II)** | **消息内容**：Stream/Function 语义、数据项编码、消息体结构 | `secs-protocol/.../secs2/` |
| SEMI E30 (GEM) | 基于 E5 的**设备通用行为模型**（状态机、事件、报警、远程命令等） | 会话层 `HsmsSessionRuntime`（见 E30 手册） |

一句话：**E5 定义"报文长什么样"，E30 定义"什么时候该发什么报文"。**

---

## 2. 消息模型

### 2.1 HSMS 承载时的 10 字节头

| 偏移 | 长度 | 字段 | 说明 |
|---|---|---|---|
| 0–1 | 2B | Session ID / Device ID | 设备侧通常等于 DeviceID；控制消息用 0xFFFF |
| 2 | 1B | Header Byte 2 | bit7 = **W 位**（0x80），低 7 位 = **Stream**（1–127） |
| 3 | 1B | Header Byte 3 | **Function**（0–255）；**奇数 = primary，偶数 = secondary**（bit0 即奇偶） |
| 4 | 1B | PType | 0 = SECS-II |
| 5 | 1B | SType | 0 = 数据消息；1–9 为控制消息（见 E37.1） |
| 6–9 | 4B | System Bytes | 事务标识：secondary 必须与对应 primary 相同 |

要点：

- **W 位只存在于消息头**，数据项层没有 W 位。
- **primary 必须由对端用同 SystemBytes 的 secondary 应答**；W=0 时对端不必应答。
- **对 secondary 不得再回 S9Fx**：S9 族只针对无法识别的 primary；迟到的 secondary 应静默丢弃。
- SystemBytes 由双方各自生成，**同一时刻的方向无法从报文本身区分**——等待应答时若只按
  SystemBytes 匹配，可能把对端恰好同号的 primary 误吞（本项目 `HsmsSsReplyMessageManager`
  即此实现，见审核 P1-5）。

### 2.2 事务时序

```
Host                          Equipment
 |-- primary (odd F, W=1) ------->|
 |<-- secondary (even F, W=0) ----|   正常：SystemBytes 相同
 |                                |
 |   T3 内无应答 → 主机侧判超时；设备侧不发任何"超时通知"
```

T3 = 应答超时（默认 45 s，可配置）。超时后迟到的 secondary **只能丢弃**。

### 2.3 消息体记号约定

本文用类似 SML 的写法描述结构：
`L[n]{...}` 表示 n 个元素的 List，`A` 字符串，`B` 字节，`BOOLEAN` 布尔，
`U1/U2/U4/U8`、`I1/I2/I4/I8`、`F4/F8` 数值。

---

## 3. 数据项（Item）编码

### 3.1 格式码表（已与本项目 `Secs2Item` 交叉核对）

| 类型 | 6bit 格式码 | 说明 |
|---|---|---|
| L（List） | 0x00 | 元素个数即长度（个数，不是字节数） |
| B（Binary） | 0x20 | 字节串 |
| BOOLEAN | 0x24 | 每值 1 字节，0x00/0x01 |
| A（ASCII） | 0x40 | 7bit ASCII |
| J（JIS-8） | 0x44 | 少用 |
| UNICODE | 0x48 | 2 字节/字符，少用 |
| I8 / I1 / I2 / I4 | 0x60 / 0x64 / 0x68 / 0x70 | 有符号，大端 |
| F8 / F4 | 0x80 / 0x90 | IEEE-754，大端 |
| U8 / U1 / U2 / U4 | 0xA0 / 0xA4 / 0xA8 / 0xB0 | 无符号，大端 |

### 3.2 Item Header 字节

```
bit 7..2 = 格式码（上表）
bit 1..0 = 长度字段占用的字节数（1/2/3）
```

因此实际首字节是"格式码 | 长度字节数"：

| 数据项 | 1 字节长度 | 2 字节长度 | 3 字节长度 |
|---|---|---|---|
| `A "X"` | 0x41 | 0x42 | 0x43 |
| `U4 0` | 0xB1 | 0xB2 | 0xB3 |
| `B 0x00` | 0x21 | 0x22 | 0x23 |

长度是**内容字节数**（List 是元素个数）。最大长度 3 字节长度字段 → 理论 16 MB；
HSMS 实际受链路/实现对消息总长的限制（本项目发送 64 KiB 分块、接收上限 8 MiB）。

---

## 4. 常用消息速查

> 方向约定：H = Host 方向（主机发起），E = Equipment 方向（设备发起）。
> 结构为常见标准形状；"本项目"列指出实现位置或差异。

### 4.1 Stream 1（设备状态 / 通信建立）

| 消息 | 方向 | W | 体结构 | 本项目 |
|---|---|---|---|---|
| S1F1 Are You There | H→E | 1 | 空 | ✅ `onPrimary` → `gem().s1f2` |
| S1F2 On Line Data | E→H | 0 | `L[2]{A MDLN, A SOFTREV}` | ✅（W=0 时不回，符合语义） |
| S1F3 Selected Equipment Status Request | H→E | 1 | `L[n]{U4 SVID}`（空列表=全部） | ✅ `replyS1F4` |
| S1F4 Selected Equipment Status Data | E→H | 0 | `L[n]{V}`，**顺序与个数必须与 S1F3 一一对应** | ✅ 顺序保持；未知 SVID 回 `U4 0`（P2-1） |
| S1F11 Status Variable Namelist Request | H→E | 1 | `L[n]{U4 SVID}`（空=全部） | ✅ `replyS1F12` |
| S1F12 Status Variable Namelist Reply | E→H | 0 | `L[n]{L[3]{U4 SVID, A SVNAME, A UNITS}}` | ✅ |
| S1F13 Establish Communications Request | H→E | 1 | 空 或 `L[2]{A MDLN, A SOFTREV}` | ✅ → `s1f14(COMMACK.OK)` |
| S1F14 Establish Communications Acknowledge | E→H | 0 | `L[2]{B COMMACK, L[2]{A MDLN, A SOFTREV}}`（严格多模型时为 `L[n]{L[2]{...}}`） | ✅ 单模型形状（多数主机接受） |
| S1F15 Request OFF-LINE | H→E | 1 | 空 | ✅ → `s1f16`（总是 OFLACK=0） |
| S1F16 OFF-LINE Acknowledge | E→H | 0 | `B OFLACK` | ✅ |
| S1F17 Request ON-LINE | H→E | 1 | 空 | ✅ → `s1f18(ONLACK.OK)` |
| S1F18 ON-LINE Acknowledge | E→H | 0 | `B ONLACK` | ✅（不区分"已在线"，P1-2 相关） |

### 4.2 Stream 2（设备控制 / 数据定义）

| 消息 | 方向 | W | 体结构 | 本项目 |
|---|---|---|---|---|
| S2F13 Equipment Constant Request | H→E | 1 | `L[n]{U4 ECID}` | ❌ 未实现（P1-4，回 S9F5） |
| S2F14 Equipment Constant Data | E→H | 0 | `L[n]{V ECV}`，顺序对应 | ❌ |
| S2F15 New Equipment Constant Send | H→E | 1 | `L[n]{L[2]{U4 ECID, V ECV}}` | ❌ |
| S2F16 New Equipment Constant Acknowledge | E→H | 0 | `B ACKC7` | ❌ |
| S2F17 Date and Time Request | H→E | 1 | 空 | ✅ → `s2f18Now` |
| S2F18 Date and Time Data | E→H | 0 | `A` 时间（见 §7；默认 A16） | ⚠️ A16 末两位填了百分秒而非时区（P1-1） |
| S2F23 Trace Initialize Send | H→E | 1 | `L[5]{U4 TRID, A DSPER, U4 TOTSMP, U4 REPGSZ, L[n]{U4 SVID}}` | ✅ `handleTraceInitialize` |
| S2F24 Trace Initialize Acknowledge | E→H | 0 | `B TIAACK` | ✅（0–5 码见 §6） |
| S2F31 Date and Time Set Request | H→E | 1 | `A` 时间 | ✅ → `s2f32(TIACK.OK)`（不校验格式、不真正改时钟，P2-3） |
| S2F32 Date and Time Set Acknowledge | E→H | 0 | `B TIACK` | ✅ |
| S2F33 Define Report | H→E | 1 | `L[2]{U4 DATAID, L[n]{L[2]{U4 RPTID, L[m]{U4 VID}}}}`；`L[0]` = 清空全部 | ✅ P0-1 已修：`reportListOf` 标准形状优先、非标形状兼容 |
| S2F34 Define Report Acknowledge | E→H | 0 | `B DRACK` | ✅（形状对，但触发路径错） |
| S2F35 Link Event Report | H→E | 1 | `L[2]{U4 DATAID, L[n]{L[2]{U4 CEID, L[m]{U4 RPTID}}}}`；空 = 解除全部 | ✅ 兼容带/不带 DATAID 两种形状 |
| S2F36 Link Event Report Acknowledge | E→H | 0 | `B LRACK` | ✅ |
| S2F37 Enable/Disable Event Report | H→E | 1 | `L[2]{BOOLEAN CEED, L[n]{U4 CEID}}`；空 CEID 列表 = 全部 | ✅（B[1] 形式的 CEED 会被拒，P2-2） |
| S2F38 Enable/Disable Event Report Acknowledge | E→H | 0 | `B ERACK` | ✅ |
| S2F41 Host Command | H→E | 1 | `L[2]{A RCMD, L[n]{L[2]{A CPNAME, V CPVAL}}}` | ❌ 未实现（P1-3，回 S9F5） |
| S2F42 Host Command Acknowledge | E→H | 0 | `L[2]{B HCACK, L[n]{L[2]{A CPNAME, V CPACK}}}` | ❌ |

### 4.3 Stream 5（报警）

| 消息 | 方向 | W | 体结构 | 本项目 |
|---|---|---|---|---|
| S5F1 Alarm Report Send | E→H | 1 | `L[3]{B ALCD, U4 ALID, A ALTX}` | ✅ `emitAlarm` |
| S5F2 Alarm Report Acknowledge | H→E | 0 | `B ACKC5` | 等待应答（ACKC5 业务处理未做，P2 类） |
| S5F3 Enable/Disable Alarm Send | H→E | 1 | `L[2]{B ALED, U4 ALID}` | ❌ 未实现 |
| S5F4 Enable/Disable Alarm Acknowledge | E→H | 0 | `B ACKC5` | ❌ |
| S5F5 List Alarm Request | H→E | 1 | `L[n]{U4 ALID}` | ❌ |
| S5F6 List Alarm Data | E→H | 0 | `L[n]{L[3]{B ALCD, U4 ALID, A ALTX}}` | ❌ |
| S5F7 List Enabled Alarm Request | H→E | 1 | `L[0]` | ❌ |
| S5F8 List Enabled Alarm Data | E→H | 0 | `L[n]{U4 ALID}` | ❌ |

### 4.4 Stream 6（事件 / Trace）

| 消息 | 方向 | W | 体结构 | 本项目 |
|---|---|---|---|---|
| S6F1 Trace Data Send | E→H | 0/1 | `L[4]{U4 TRID, U4 SMPLN, A STIME, L[n]{V}}`（同一样本内按 SVID 顺序扁平排列） | ✅ `TraceManager`（STIME 同 P1-1） |
| S6F2 Trace Data Acknowledge | H→E | 0 | `B ACKC6` | 等待应答（W 可选） |
| S6F11 Event Report Send | E→H | 0/1 | **`L[3]{U4 DATAID, U4 CEID, L[n]{L[2]{U4 RPTID, L[m]{V}}}}`** | ✅ P0-2 已修：DATAID 固定 0，结构为 `L[3]` |
| S6F12 Event Report Acknowledge | H→E | 0 | `B ACKC6` | ✅ P0-3 已修：迟到 secondary 静默丢弃 |

### 4.5 Stream 9（错误消息）

S9Fx 的体一律为 **`B[10]`**：引起问题的消息的 10 字节头。S9Fx 本身是 primary，
**不得对 secondary 发送**；收到 S9Fx 时不得再回 S9Fx（防死循环）。

| 消息 | 含义 | 触发条件 |
|---|---|---|
| S9F1 | Unrecognized Device ID | 收到的 DeviceID 与本机不符 |
| S9F3 | Unrecognized Stream | Stream 不认识 |
| S9F5 | Unrecognized Function | Stream 认识但 Function 未实现 |
| S9F7 | Illegal Data | 数据项解码失败/非法结构 |
| S9F9 | Transaction Timer Timeout | 等 secondary 超 T3 |
| S9F11 | Data Too Long | 报文超过实现上限 |
| S9F13 | Conversation Timeout | T3 后收到的 secondary（**应静默丢弃，不应回 S9**） |

本项目：S9F3/S9F5 分类正确、S9 自身不回；**已过滤 secondary**（P0-3 已修：偶数 function 在 `onPrimary` 入口丢弃）。

### 4.6 Stream 10（终端服务）

| 消息 | 方向 | W | 体结构 | 本项目 |
|---|---|---|---|---|
| S10F1 Terminal Request | E→H | 1 | `L[2]{B TID, A TEXT}` | ❌ |
| S10F2 Terminal Request Acknowledge | H→E | 0 | `B ACKC10` | ❌ |
| S10F3 Terminal Display, Single | H→E | 1 | `L[2]{B TID, A TEXT}` | ✅ 保存最近 20 条 |
| S10F4 Terminal Display, Single Acknowledge | E→H | 0 | `B ACKC10` | ✅（显式 `send(primary,10,4,...)`） |
| S10F5 Terminal Display, Multi | H→E | 1 | `L[2]{B TID, L[n]{A TEXT}}` | ❌ |
| S10F6 Terminal Display, Multi Acknowledge | E→H | 0 | `B ACKC10` | ❌ |

---

## 5. SML 文本速览

```text
S1F13 W.
<L[2]
  <A "MDLN-01">
  <A "1.0.0">
>

S2F33 W
<L[2]
  <U4 0>
  <L[1]
    <L[2]
      <U4 3001>
      <L[2] <U4 1001> <U4 1003>>
    >
  >
>
```

本项目 `SmlParser` 支持受控子集（L/A/B/BOOLEAN/I*/U*/F4/F8、`<L[n]>`、`W`），
深度上限 32、条目上限 1 万，见 `secs-runtime/.../sml/SmlParser.java`。

---

## 6. 常用 ACK 码值（行业通行取值）

> 非零码的措辞各标准版本/厂商实现略有差异；0 恒为"接受"。正式实现请核对原文。

| ACK | 0 | 1 | 2 | 3 | 4 |
|---|---|---|---|---|---|
| COMMACK (S1F14) | 接受 | 拒绝（通信已建立/条件不符） | 拒绝（资源不足） | — | — |
| ONLACK (S1F18) | 接受 | 拒绝（通信未建立） | 拒绝（已在线） | — | — |
| OFLACK (S1F16) | 接受 | 拒绝（已离线/忙） | — | — | — |
| TIACK (S2F32) | 接受 | 拒绝（格式错误） | — | — | — |
| DRACK (S2F34) | 接受 | 拒绝（空间不足） | 拒绝（格式非法） | 拒绝（RPTID 已定义） | 拒绝（VID 不存在） |
| LRACK (S2F36) | 接受 | 拒绝（空间不足） | 拒绝（格式非法） | 拒绝（CEID 未定义） | 拒绝（RPTID 未定义） |
| ERACK (S2F38) | 接受 | 拒绝（格式非法/原因不明） | — | — | — |
| ACKC7 (S2F16) | 接受 | 拒绝（当前无法执行） | 拒绝（参数非法） | 拒绝（值越界） | 拒绝（语义错误） |
| ACKC5 (S5F2/S5F4) | 接受 | 拒绝 | — | — | — |
| ACKC6 (S6F12/S6F2) | 接受 | 拒绝 | — | — | — |
| ACKC10 (S10F4/6/F2) | 已显示 | 不显示（文本过长等） | 不显示（其他） | — | — |
| HCACK (S2F42) | 确认 | 无效命令 | 当前无法执行 | 参数非法 | 需初始化 |
| TIAACK (S2F24) | 接受 | SVID 超限 | 已有 Trace 占用 | DSPER 非法 | SVID 未定义 / REPGSZ 非法（5） |

---

## 7. 时间格式

E5/E30 通用时间格式（S2F18、S2F31、S6F1 STIME 等）：

| 形式 | 布局 | 说明 |
|---|---|---|
| **A16**（E5 定义为可选，新实现应支持） | `YYYYMMDDhhmmsscc` | 14 位日期时间 + 2 位**厘秒**（`00`–`99`） |
| A12（传统） | `YYMMDDhhmmss` | 两位年 |
| U4 | Unix 秒 | 部分实现支持 |

**厘秒不是时区**。E5 6.6 对 `TIME` 的定义原文：

> If 16 bytes the format is YYYYMMDDhhmmsscc … cc = Centisecond 00 to 99

E30 §4.10 进一步解释厘秒的**用途**：不是提供更精确的钟点，而是**区分几乎同时发生的事件**
（同一秒内多个事件按发生顺序分配厘秒）；无法解析到亚秒的设备可以恒填 `00`。
E30 还要求供应商在文档中说明时间的分辨率与厘秒的分配方式。

> **修订记录（2026-10-21）**：本文旧版把 A16 末 2 位写成"时区偏移 `±ZZ`"，并据此把
> 代码实现判为错误（对应 10-20 评审的 P1-1）。核对 E5-0301 原文第 59 页 `TIME` 条目与
> E30-1103 第 61–62 页 §4.10 后确认：**末 2 位是厘秒，旧描述有误，实现本身符合标准**。
> `AbstractClock.toAscii16()` 追加的是 `nano / 10_000_000`（厘秒），`TraceManager` 的
> STIME 用 `yyyyMMddHHmmssSS`（毫秒两位，同属亚秒位，但精度口径与"厘秒"略有出入，
> 第三方主机若严格按 cc 语义校验，建议统一为厘秒）。
>
> 需要注意的**真正跨时区问题**不在 A16：A12/A16 都不携带时区，跨时区产线若需绝对时间，
> 应由主机通过 S2F31 与设备时钟对齐（E30 §4.10 的场景），而不是指望时间戳自带偏移。

---

## 8. 与本项目的对照

| 主题 | 实现位置 | 状态 |
|---|---|---|
| 数据项编解码 | `secs-protocol/.../secs2/` | ✅ 格式码表全部正确 |
| SML 解析/渲染 | `secs-runtime/.../sml/` | ✅ 受控子集，有界 |
| 消息分发 | `secs-runtime/.../session/HsmsSessionRuntime.java` | ✅ P0-1、P0-3 已修 |
| S6F11 组包 | `.../S6F11Assembler.java` | ✅ P0-2 已修 |
| Trace | `.../TraceManager.java` | ✅ STIME 为 `yyyyMMddHHmmssSS`；如需严格厘秒口径见 §7 修订记录 |
| 动态报告 | `HostReportTable` + 会话层 | 语义自洽（ADR-0006）；形状问题 P0-1 已修 |

完整问题清单与修复顺序见 [全量评审 2026-10-20../../reports/code-review-2026-10-20.md)。
其中 P1-1（时间格式）经本轮核对标准原文后**不成立**，理由见本文 §7 的修订记录。
