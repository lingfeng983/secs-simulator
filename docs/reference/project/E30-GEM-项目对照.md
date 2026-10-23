# SEMI E30（GEM）项目对照手册

> **本文定位**：这不是标准整理，而是**标准能力 ↔ 本项目代码**的对照表。
> 回答"12 项 GEM 能力各自实现到什么程度、缺口在哪"。
> 标准正文请读 [../standards/03-E30-GEM-中文整理.md](../standards/03-E30-GEM-中文整理.md)。
>
> **重要说明**
>
> 本文是**原创技术参考整理**，不是 SEMI E30 标准的官方译文，也不能替代标准原文。
> SEMI E30 是 SEMI 的**付费版权文档**，不提供合法免费全文下载；正式合规实现必须
> 以购买的标准原文为准。
>
> 官方获取渠道（本文编写时价格，可能有调整）：
>
> - [SEMI E30 官方商店（Member $286 / Non-Member $380）](https://store-us.semi.org/products/e03000-semi-e30-specification-for-the-generic-model-for-communications-and-control-of-manufacturing-equipment-gem)
> - [SEMIViews 在线订阅](https://www.semi.org/en/blogs/semi-standards-update-major-revisions-task-force-expansion-and-responding-to-growing-industry-requirements)
> - 免费解读：[Cimetrix《Intro to SEMI Standards: GEM》白皮书](https://www.cimetrix.com/hubfs/docs/Whitepapers/Intro-SEMI-Standards-GEM-and-PV2.pdf)、[PEER Group – SEMI E30](https://www.peergroup.com/definition-of-standard/semi-e30)、[Einnosys – GEM 指南](https://www.einnosys.com/semi-e30-specification-for-the-generic-model-for-communications-and-control-of-manufacturing-equipment-gem)、[SemiEngineering SECS/GEM eBook](https://semiengineering.com/features-and-benefits-of-the-secs-gem-standard-ebook)
>
> 本文中的状态机、消息序列、ACK 码值属于行业公开的技术事实（多数开源实现与设备
> 手册均有记载）；非零 ACK 码的具体措辞各版本略有差异，标注为"通行取值"。
>
> **更新基线**：2026-10-20（对应 `master @ 68883db`）。能力状态随功能迭代变化，引用前请以实际代码为准。

---

## 1. GEM 是什么

GEM（Generic Model for Communications and Control of Manufacturing Equipment）建立在
SECS-II（E5）之上，规定**设备侧应该具备哪些行为**：

- 用哪些 E5 消息、什么时候发、对方不回时怎么办；
- 通信/控制状态机与状态转换；
- 事件报告（Event Report）、报警（Alarm）、远程命令（Remote Command）、
  设备常量（Equipment Constant）、终端服务（Terminal Services）、Trace 等标准能力。

GEM **不规定**具体用哪些 CEID/SVID/ECID/报警号——这些是每台设备自己的"数据字典"，
由设备厂商与主机在接口规格（Interface Spec）里约定。

相关但独立的标准：E40（Process Job）、E87（Carrier Management）、E90（Substrate
Tracking）、E116（EDA）——**不属于 E30 核心**，模拟器通常不需要。

---

## 2. 状态模型

### 2.1 通信状态（Communication State Model）

| 状态 | 含义 |
|---|---|
| Communication Disabled | 未完成 S1F13/S1F14 握手 |
| Communication Enabled | 握手完成，可以交换事件/命令等 GEM 消息 |

**关键约束**：握手完成前，设备只应处理/发送建立通信所必需的消息（S1F1/S1F2、
S1F13/S1F14、S9Fx 等），**不得主动发 S6F11 事件**。

### 2.2 控制状态（Control State Model）

```
                    S1F15 / 操作员
        ┌───────────────────────────────┐
        ▼                               │
   ┌─────────┐                    ┌──────────┐
   │ OFFLINE │                    │  ONLINE  │
   └─────────┘                    └──────────┘
        │                               │
        │ S1F17 / 操作员                 │ 设备面板/操作方式
        └──────────────────────────────►│
                                        ├── ONLINE LOCAL（本地控制）
                                        └── ONLINE REMOTE（主机控制）
```

- **OFFLINE**：设备不受主机控制。主机仍可查询（S1F3 等），但**不得发事件**，
  控制类命令应被拒绝。
- **ONLINE**：主机可以控制设备；其中 **REMOTE** 子状态下远程命令（S2F41）才应生效，
  LOCAL 子状态由设备本地操作员控制。
- 进入 ONLINE 的动作：主机发 **S1F17**（回 S1F18 + ONLACK），或设备面板切换。
- 退回 OFFLINE：主机发 **S1F15**（回 S1F16 + OFLACK），或设备面板切换、通信中断。

### 2.3 处理状态（Processing State Model）

E30 允许设备自定义（空闲/运行中/暂停/故障等），通过事件与状态变量表达；
核心 GEM 不规定固定状态名与消息序列。E40/E87 才定义作业/载具层面的标准状态。

---

## 3. 建立通信（典型上电序列）

```text
Host                                   Equipment
 |-- S1F1 (W) --------------------------->|
 |<-- S1F2 L{MDLN, SOFTREV} --------------|     链路自检（可选）
 |                                        |
 |-- S1F13 (W) L{MDLN, SOFTREV} / 空 ----->|
 |<-- S1F14 L{COMMACK, L{MDLN, SOFTREV}} -|     COMMACK=0 建立成功
 |                                        |
 |                                        |--- S6F11 "Establish Communications" 事件（CEID 由设备定义）
 |                                        |
 |-- S1F17 (W) --------------------------->|     请求联机
 |<-- S1F18 B ONLACK ---------------------|     ONLACK=0 成功
```

要点：

- S1F13 的 W 位：主机通常置 1；**W=0 时设备不必回 S1F14**（本项目协议层即如此实现）。
- COMMACK=0 表示接受；非 0 表示拒绝（已建立/条件不符/资源不足，见 §11）。
- 设备发起建立通信是**非标准做法**——E30 规定由主机发起 S1F13。
- 建立通信事件是否发送、CEID 编号，均由设备接口规格约定。

---

## 4. 控制状态消息

| 消息 | 方向 | 体 | 说明 |
|---|---|---|---|
| S1F15 Request OFF-LINE | H→E | 空 | 请求离线 |
| S1F16 OFF-LINE Acknowledge | E→H | `B OFLACK` | 0=接受；1=拒绝（已离线/忙） |
| S1F17 Request ON-LINE | H→E | 空 | 请求联机 |
| S1F18 ON-LINE Acknowledge | E→H | `B ONLACK` | 0=接受；1=拒绝（通信未建立）；2=拒绝（已在线） |

**行为约束（ONLINE/OFFLINE 的差异）**：

| 行为 | ONLINE | OFFLINE |
|---|---|---|
| 主动发 S6F11 事件 | ✅ | ❌ |
| 主动发 S5F1 报警 | ✅（报警仍可报，但有争议） | ❌ |
| 响应 S1F3 查询 | ✅ | ✅ |
| 执行 S2F41 远程命令 | ✅（REMOTE 时） | ⚠️ 回 S2F42（HCACK 正确），不做设备动作 |
| 响应 S1F13 | ✅ | ✅ |

> 本项目当前只把控制状态用于界面显示，发送逻辑未受其约束（审核 P1-2）。

---

## 5. 事件报告（Event Reporting）

### 5.1 概念关系

```
CEID（事件）──链接──► RPTID（报告）──定义──► VID/SVID 列表（值，按顺序）
S6F11 报文体：DATAID + CEID + 若干报告（每条 = RPTID + 按定义顺序的值）
```

- **VID**：变量 ID（通常等于 SVID）。
- **RPTID**：报告 ID，光有 VID 列表还不够，必须被 CEID 链接才会随事件发出。
- **CEID**：事件 ID。
- **DATAID**：数据组 ID，通常填 0；**S6F11 体必须包含它**。

### 5.2 主机定义流程（典型）

```text
1) S2F33 定义报告：DATAID + [RPTID → VID 列表]
   ← S2F34 DRACK=0
2) S2F35 链接事件：DATAID + [CEID → RPTID 列表]
   ← S2F36 LRACK=0
3) S2F37 使能事件：CEED=TRUE + [CEID]（空列表=全部）
   ← S2F38 ERACK=0
4) 事件发生时：设备发 S6F11，主机回 S6F12（ACKC6）
```

### 5.3 报文形状（标准）

```text
S2F33 W  <L[2] <U4 DATAID> <L[n] <L[2] <U4 RPTID> <L[m] <U4 VID>>>>
S2F34    <B DRACK>
S2F35 W  <L[2] <U4 DATAID> <L[n] <L[2] <U4 CEID>  <L[m] <U4 RPTID>>>>
S2F36    <B LRACK>
S2F37 W  <L[2] <BOOLEAN CEED> <L[n] <U4 CEID>>>
S2F38    <B ERACK>

S6F11 W  <L[3]
           <U4 DATAID>
           <U4 CEID>
           <L[n]
             <L[2] <U4 RPTID> <L[m] <V 值…>>
           >
         >
S6F12    <B ACKC6>
```

**空列表语义**：S2F33 空报告列表 = 删除全部主机定义；S2F35 空链接列表 = 解除全部链接；
S2F37 空 CEID 列表 = 作用于全部事件。删除后设备是否回落到本地默认定义，属于实现约定
（本项目 ADR-0006 选择"回落到本地配置"）。

### 5.4 本项目与标准的差异

10-20 评审列出的三个 P0（S6F11 缺 DATAID、S2F33 解析取错元素、迟到 secondary 被当 primary
回 S9F5）**已于 2026-10-22 全部修复**，并配了按标准形状构造的回归用例
（夹具不再复用被实现的 builder 快捷方式，避免"夹具与实现同源"的盲区）：

| 审核项 | 修复 |
|---|---|
| P0-2 | S6F11 改为 `L[3]{U4 DATAID=0, U4 CEID, L{报告}}`（`S6F11Assembler.DATAID`） |
| P0-1 | `handleDefineReport` 用 `reportListOf` 按形状判定：标准形状（第 1 元素非列表 = DATAID）优先，省略 DATAID 的非标形状兼容；S2F35 同规则 |
| P0-3 | `onPrimary` 入口丢弃偶数 function 的到达报文（E5：S9Fx 只对 primary） |

P1-3（S2F41/F42）与 P1-4（S2F13–F16）均已实现，详见
[全量评审 2026-10-20](../../code-review-2026-10-20.md) §四。

---

## 6. 状态变量（Status Variables）

| 消息 | 方向 | 体 | 说明 |
|---|---|---|---|
| S1F3 | H→E | `L[n]{SVID}` | 请求状态变量值；空列表常表示"全部" |
| S1F4 | E→H | `L[n]{V}` | 顺序、个数必须与请求一致 |
| S1F11 | H→E | `L[n]{SVID}` | 请求变量名字与单位 |
| S1F12 | E→H | `L[n]{L[3]{SVID, NAME, UNITS}}` | 自动发现型主机的入口 |

**一致性要求**：S1F11 报出的 SVID 集合、S1F4 的取值顺序、S6F11 报告内的 VID 顺序，
三者必须来自同一份"变量目录"，否则主机侧会错位。本项目 `ResolvedEquipment` 是
唯一真相（目录接口 `/catalog` 也投影同一份数据）。

---

## 7. 设备常量（Equipment Constants）

| 消息 | 方向 | 体 | 说明 |
|---|---|---|---|
| S2F13 | H→E | `L[n]{U4 ECID}` | 读常量 |
| S2F14 | E→H | `L[n]{V ECV}` | 保持顺序 |
| S2F15 | H→E | `L[n]{L[2]{U4 ECID, V ECV}}` | 写常量 |
| S2F16 | E→H | `B ACKC7` | 0=接受（码表见 §11） |

调机流程通常伴随一组 ECV 写入。**本项目已实现（P1-4，2026-10-23）**：
常量在 `equipment.constants` 声明，S2F15 写入后立即写回配置文件并原子替换，
重载/重启后保留；未 ONLINE 回 EAC=3，未知 ECID 回 1，越界回 2，**整批不生效**。

---

## 8. 时间服务

| 消息 | 方向 | 体 | 说明 |
|---|---|---|---|
| S2F17 | H→E | 空 | 请求时间 |
| S2F18 | E→H | 时间（A16 主流） | 见下方格式 |
| S2F31 | H→E | 时间 | 设置时间 |
| S2F32 | E→H | `B TIACK` | 0=接受；1=拒绝（格式错误） |

**时间格式（A16）**：`CCYYMMDDhhmmss±ZZ`

- 前 14 位：年(4)月(2)日(2)时(2)分(2)秒(2)
- 后 2 位：**时区偏移**，如 `+09`、`-05`
- 传统 A12：`YYMMDDhhmmss`；部分实现支持 U4（Unix 秒）

**易错点**：后两位填百分秒而不是时区。本项目当前即此实现，跨时区主机会解析错
（审核 P1-1）。S6F1 的 STIME 使用同一格式。

---

## 9. 报警（Alarms）

### 9.1 S5F1 / S5F2

```text
S5F1 W  <L[3] <B ALCD> <U4 ALID> <A ALTX>>
S5F2    <B ACKC5>          ; 0=接受，1=拒绝
```

**ALCD 位定义**：

| 位 | 含义 |
|---|---|
| bit 7 (0x80) | 1 = 报警置位；0 = 报警清除 |
| bit 6–3 | 保留（0） |
| bit 2–0 | 类别码（按位组合；常见约定 1=人身安全、2=设备安全、4=过程/产品，具体以接口规格为准） |

- **ALID**：报警 ID，1..65535（0 保留）。
- **ALTX**：报警文本，**ASCII**（不能放中文；ALTX 编码失败会让报文发不出去）。
- 置位与清除使用**同一 ALID**，靠 ALCD bit7 区分。
- 主机回 S5F2 的 ACKC5 非 0 时，设备应考虑重发或降级（本项目仅记录，未做业务处理）。

### 9.2 报警使能/查询（可选能力）

| 消息 | 说明 |
|---|---|
| S5F3/F4 | 使能/禁用某个报警的上报（ALED + ALID） |
| S5F5/F6 | 列出报警定义（ALCD/ALID/ALTX） |
| S5F7/F8 | 列出当前使能的报警 |

多数设备只实现 S5F1/F2；本项目未实现 S5F3–F8。

---

## 10. 远程命令（Remote Commands）

```text
S2F41 W  <L[2]
           <A RCMD>
           <L[n] <L[2] <A CPNAME> <V CPVAL>>>
         >
S2F42    <L[2]
           <B HCACK>
           <L[n] <L[2] <A CPNAME> <V CPACK>>>
         >
```

- **RCMD**：命令名（START / STOP / PAUSE / RESUME / ABORT / PP-SELECT / …，
  具体集合由接口规格约定）。
- **CPNAME/CPVAL**：命令参数（如 PPID、槽位号）。
- **HCACK**：整体结果；参数级确认放在第二个列表（CPACK 可为空）。
- 仅在 **ONLINE REMOTE** 且初始化完成时执行；否则 HCACK=2（当前无法执行）或 4（需初始化）。

**本项目（2026-10-22 更新）**：已实现 S2F41/F42（`handleRemoteCommand`）。
按状态回 HCACK：未建立通信/离线 → `4`（需初始化）；ONLINE → `0`（接受）；
RCMD 非法 → `1`；CPACK 恒为空列表。**能力边界**：模拟器不做真实设备动作，
只保证结构正确、不再落 S9F5 —— 主机上线探测"命令支持"能走通，
真实 START/STOP 副作用需下游给出命令集定义后另接。

---

## 11. 常用 ACK 码值（行业通行取值）

| ACK | 0 | 1 | 2 | 3 | 4 |
|---|---|---|---|---|---|
| COMMACK | 接受 | 拒绝（条件不符/已建立） | 拒绝（资源不足） | — | — |
| ONLACK | 接受 | 拒绝（通信未建立） | 拒绝（已在线） | — | — |
| OFLACK | 接受 | 拒绝（已离线/忙） | — | — | — |
| TIACK | 接受 | 拒绝（格式错误） | — | — | — |
| DRACK | 接受 | 空间不足 | 格式非法 | RPTID 已定义 | VID 不存在 |
| LRACK | 接受 | 空间不足 | 格式非法 | CEID 未定义 | RPTID 未定义 |
| ERACK | 接受 | 格式非法/拒绝 | — | — | — |
| ACKC7 | 接受 | 当前无法执行 | 参数非法 | 值越界 | 语义错误 |
| ACKC5 | 接受 | 拒绝 | — | — | — |
| ACKC6 | 接受 | 拒绝 | — | — | — |
| ACKC10 | 已显示 | 不显示 | — | — | — |
| HCACK | 确认 | 无效命令 | 当前无法执行 | 参数非法 | 需初始化 |
| TIAACK | 接受 | SVID 超限 | 已有 Trace | DSPER 非法 | SVID 未定义（5=REPGSZ 非法） |

> 非零码的具体措辞各版本/厂商略有差异，正式实现以购买的标准原文为准。

---

## 12. 终端服务（Terminal Services）

| 消息 | 方向 | 体 | 说明 |
|---|---|---|---|
| S10F1/F2 | E→H | `L[2]{B TID, A TEXT}` / `B ACKC10` | 设备请主机显示 |
| S10F3/F4 | H→E | `L[2]{B TID, A TEXT}` / `B ACKC10` | 主机要求设备显示（单条） |
| S10F5/F6 | H→E | `L[2]{B TID, L[n]{A TEXT}}` / `B ACKC10` | 多条文本 |

本项目实现 S10F3/F4（保存最近 20 条），并用 `send(primary, 10, 4, ...)` 显式回
Secondary，规避了协议层 `Gem.s10f4` 发新 Primary 的缺陷。

---

## 13. Trace（S6F1/S6F2、S2F23/S2F24）

```text
S2F23 W  <L[5] <U4 TRID> <A DSPER> <U4 TOTSMP> <U4 REPGSZ> <L[n] <U4 SVID>>>
S2F24    <B TIAACK>
S6F1     <L[4] <U4 TRID> <U4 SMPLN> <A STIME> <L[n] 值…>>
S6F2     <B ACKC6>
```

- **DSPER**：采样周期，A 格式（常见 `hhmmss` / `hh:mm:ss`）。
- **TOTSMP**：总样本数；**TOTSMP=0 表示终止该 TRID**。
- **REPGSZ**：每条 S6F1 携带的样本数（样本按 SVID 顺序扁平排列）。
- **SMPLN**：本条报文中最后一个样本的样本号。
- **STIME**：采样时间，A16（同 §8 时间格式）。

---

## 14. Spooling（离线缓存，可选能力）

主机不可达期间缓存事件、恢复后补发，使用 S2F43–S2F48 系列（初始化/查询/清除）。
本项目未实现；断链期间事件直接丢弃（会话层在重连后从当前时刻重新计时，不会补发）。
若现场要求"断链不丢事件"，需在此处扩展。

---

## 15. 能力对照表

| 能力 | 标准消息 | 本项目 | 备注 |
|---|---|---|---|
| 链路自检 | S1F1/F2 | ✅ | |
| 建立通信 | S1F13/F14 | ✅ | COMMACK=0 |
| 控制状态 | S1F15–F18 | ✅ | 已约束主动上报：仅 ONLINE 发 S6F11/S6F1（P1-2 已修） |
| 状态变量 | S1F3/F4 | ✅ | 空列表=全部；未知 SVID 回 0（P2-1） |
| 变量目录 | S1F11/F12 | ✅ | |
| 设备常量 | S2F13–F16 | ✅ | P1-4 已实现：可写并落盘（EAC 0/1/2/3，整批不生效） |
| 时间 | S2F17/F18、S2F31/F32 | ✅ | P1-1 经核对原文不成立（见 E5 §7）；S2F31 不校验/不改时钟（P2-3） |
| 事件报告 | S2F33–F38、S6F11/F12 | ✅ | 形状问题 P0-1/P0-2/P0-3 已修 |
| 报警 | S5F1/F2 | ✅ | ALCD 类别允许 0（P2-4）；ALTX 非 ASCII 失败（P2-5） |
| 报警定义/使能 | S5F3–F8 | ❌ | |
| 远程命令 | S2F41/F42 | ✅ | 结构正确 + HCACK；无真实动作（P1-3 结构面已解决） |
| 终端服务 | S10F1–F6 | 部分 | 仅 F3/F4 |
| Trace | S2F23/F24、S6F1/F2 | ✅ | 1 条 Trace、每条 ≤10 SVID |
| Spooling | S2F43–F48 | ❌ | 不补发 |

扩展标准（E40 作业、E87 载具、E90 基板追踪、E116 EDA）均未实现，属于本项目
声明的能力边界。

---

## 16. 相关文档

| 文档 | 用途 |
|---|---|
| [参考资料总索引](../README.md) | 目录结构与阅读路径入口 |
| [E30 GEM 标准整理版](../standards/03-E30-GEM-中文整理.md) | 状态模型、能力、合规要求的标准原文整理 |
| [E5 项目对照](E5-SECS-II-项目对照.md) | 报文结构与数据项编码的实现对照 |
| [E5 标准整理版](../standards/02-E5-SECS-II-中文整理.md) | 消息逐条定义（第 8 章含 S6F11 等 GEM 核心消息） |
| [全量评审 2026-10-20](../../code-review-2026-10-20.md) | P0/P1 问题与修复顺序 |
| `docs/openapi.yaml`、`docs/schema-v5.md` | 项目接口与配置规格 |
