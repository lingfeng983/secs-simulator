# SEMI E37-0298 HSMS 高速 SECS 消息服务（中文参考版）

> **原标准**：SEMI E37-0298《High-Speed SECS Message Services (HSMS) — Generic Services》，© SEMI 1995, 1998  
> **覆盖范围**：第 1–4 章目的/范围/引用/术语；第 5 章连接与 Select 状态图；第 6 章 TCP/IP 使用与主动/被动模式；第 7 章 Select/Deselect/Linktest/Separate/Reject 五个过程；第 8 章消息格式、10 字节头、SType 表、System Bytes 规则、状态码表、十六进制样例；第 9 章定时器；第 10 章实现建议  
> **未覆盖**：E37.1（HSMS-SS）与 E37.2（HSMS-GS）附属标准的增量约束（本文件只整理 E37 通用部分）；状态图的图形版式（改为文本缩进表示）  
> **整理基线**：原版 PDF 24 页；本文为原创中文整理，不构成官方译文，正式引用以英文原文为准。  

## 目录

- [1 目的](#1-目的)
- [2 范围](#2-范围)
- [3 引用文档](#3-引用文档)
- [4 术语（节选）](#4-术语节选)
- [5 HSMS 概览与状态图](#5-hsms-概览与状态图)
  - [5.2 状态描述](#52-状态描述)
  - [5.3 状态转换表（摘要）](#53-状态转换表摘要)
- [6 TCP/IP 的使用](#6-tcpip-的使用)
  - [6.2 网络编址](#62-网络编址)
  - [6.3 建立 TCP/IP 连接](#63-建立-tcpip-连接)
  - [6.4 终止连接](#64-终止连接)
- [7 HSMS 消息交换过程](#7-hsms-消息交换过程)
  - [7.2 Select 过程（建立 HSMS 通信）](#72-select-过程建立-hsms-通信)
  - [7.3 数据过程（Data）](#73-数据过程data)
  - [7.4 Deselect 过程（优雅结束）](#74-deselect-过程优雅结束)
  - [7.5 Linktest 过程（链路测试）](#75-linktest-过程链路测试)
  - [7.6 Separate 过程（单方终止）](#76-separate-过程单方终止)
  - [7.7 Reject 过程（拒绝）](#77-reject-过程拒绝)
- [8 HSMS 消息格式](#8-hsms-消息格式)
  - [8.1.2 总体格式](#812-总体格式)
  - [8.1.4 消息头（10 字节）](#814-消息头10-字节)
  - [SType 取值表](#stype-取值表)
  - [8.1.4.6 System Bytes 规则](#8146-system-bytes-规则)
  - [8.2 各消息格式汇总（PType=0）](#82-各消息格式汇总ptype0)
  - [Select Status 码](#select-status-码)
  - [Deselect Status 码](#deselect-status-码)
  - [Reject 原因码](#reject-原因码)
  - [报文示例（十六进制）](#报文示例十六进制)
- [9 特别注意事项（Special Considerations）](#9-特别注意事项special-considerations)
  - [9.2 定时器参数](#92-定时器参数)
  - [其他要点](#其他要点)
- [10 实现建议（源自标准附录与工程实践）](#10-实现建议源自标准附录与工程实践)

---

## 1 目的

HSMS 让不同厂商的实现无需相互了解即可连接并互操作。

- HSMS 是 **SEMI E4（SECS-I）的替代方案**，适用于需要更高通信速度、或简单点对点拓扑不够用的场合；不需要这些特性时仍可使用 SECS-I。
- 也是 **SEMI E13（SECS Message Services，基于 OSI）的替代方案**，适用于倾向 TCP/IP 的场合。
- HSMS 可由附属标准（如 E37.1 HSMS-SS、E37.2 HSMS-GS）进一步约束其在特定领域的使用。

## 2 范围

HSMS 定义适用于半导体工厂内计算机之间交换消息的通信接口。

## 3 引用文档

- SEMI E4（SECS-I）、SEMI E5（SECS-II）
- IETF RFC 791（IP）、RFC 792（ICMP）、RFC 793（TCP）、RFC 1120、RFC 1340
- IEEE POSIX P1003.12（协议无关接口 PII）

## 4 术语（节选）

| 术语 | 含义 |
|---|---|
| entity（实体） | 与 TCP/IP 连接端点关联的应用程序（HSMS 用"实体"取代"主机/设备"的抽象称呼） |
| connection（连接） | TCP/IP LAN 上两个实体间建立的逻辑链路 |
| session（会话） | 两个实体间为交换 HSMS 消息而建立的关系 |
| session ID | 标识特定会话的 16 位无符号整数 |
| control message（控制消息） | 管理 HSMS 会话的消息 |
| data message（数据消息） | 在 HSMS 会话内传递应用数据的消息（分 Primary / Reply） |
| header（消息头） | 每条 HSMS 消息前的 10 字节数据元素 |
| message length（消息长度） | 4 字节无符号整数，指示消息字节数 |
| published port（公布端口） | 实体用于接收连接请求的 IP 地址 + TCP 端口号 |
| primary message | Function 为奇数的 HSMS 数据消息，数据事务的第一条消息 |
| reply | Function 为偶数的数据消息，Primary 的对应应答 |
| transaction（事务） | Primary 及其 Reply（若需要）；或控制消息 .req 与对应 .rsp |
| confirmed service | 需要响应方回消息表示完成的服务 |
| T3 | 应答超时 |
| T5 | 连接间隔超时（防止过于频繁的 TCP 连接活动） |
| T6 | 控制事务超时 |
| T7 | 连接空闲超时（TCP 建连后多久未被用于 HSMS 即判失败） |
| T8 | 网络字符间超时（一条完整消息中相邻两字节到达间隔上限） |

---

## 5 HSMS 概览与状态图

HSMS 在 TCP/IP 环境下提供与 SECS-I 平行的通信流程：

1. **获得通信链路**：SECS-I 中是 RS-232 线缆；HSMS 中是标准 TCP/IP 连接（connect 过程）。
2. **确立应用协议约定**：HSMS 增加 **Select 过程**——通过消息交换确认双方同意将此 TCP/IP 连接专用于 HSMS 通信。
3. **交换数据**：正常用途——交换 SECS-II 编码消息以控制半导体设备/工艺，直到一方下线（维护等）。
4. **正式结束通信**：SECS-I 无正式要求；HSMS 用 **Deselect**（双方确认，"双边"）或 **Separate**（单方，"单边"）过程结束。
5. **断开链路**：SECS-I 靠拔线；HSMS 用 TCP 的 release/disconnect 逻辑断开。

另有两个诊断性过程：
- **Linktest**：确认连接完好（心跳）。
- **Reject**：收到不兼容/非法消息时指示错误（如配置错误导致两个使用不兼容附属标准的实体相连，或软件 bug 发错消息类型）。

### 5.2 状态描述

```
NOT CONNECTED ──TCP建连──► CONNECTED ──Select成功──► SELECTED（正常工作态）
       ▲                      │ NOT SELECTED              │
       └──────TCP断开─────────┴──────Deselect/Separate────┘
```

| 状态 | 说明 |
|---|---|
| **NOT CONNECTED** | 就绪可监听/发起 TCP 连接，但尚未建立或已全部终止 |
| **CONNECTED — NOT SELECTED** | TCP 连接已建立，但尚未建立 HSMS 会话（或会话已结束） |
| **CONNECTED — SELECTED** | 至少一个 HSMS 会话已建立，**唯一允许交换数据消息的正常工作态** |

### 5.3 状态转换表（摘要）

| # | 当前态 | 触发 | 新态 | 说明 |
|---|---|---|---|---|
| 1 | NOT CONNECTED | 本地准备 | NOT CONNECTED | 视采用主动/被动模式而定 |
| 2 | NOT CONNECTED | TCP 连接建立 | CONNECTED-NOT SELECTED | — |
| 3 | CONNECTED | TCP 断开 | NOT CONNECTED | 见 6.4 |
| 4 | NOT SELECTED | Select 成功 | SELECTED | 允许数据消息交换 |
| 5 | SELECTED | Deselect/Separate 成功 | NOT SELECTED | 随后通常立即断开 TCP（转 3） |
| 6 | NOT SELECTED | T7 超时 | 断开 | 见 9.2.2 |

---

## 6 TCP/IP 的使用

### 6.2 网络编址
- **IP 地址**：安装时可配置，实现不得固定 IP。典型如 192.9.200.1。
- **TCP 端口号**：应允许配置到所用 TCP/IP 实现的全部范围。**典型端口号 5000**。

### 6.3 建立 TCP/IP 连接
由于常用 API 很少支持 RFC 793 的双方同时发起连接，HSMS 将实体限制为两种模式之一：

- **被动模式（Passive）**：本地实体监听并接受远端发起的连接。过程：① 获取连接端点并绑定到公布端口；② 监听连接请求；③ 收到请求后确认并接受，进入 CONNECTED 态。
- **主动模式（Active）**：本地实体发起连接。过程：① 获取端点；② 向被动模式远端的公布端口发起连接；③ 等待对方确认接受，进入 CONNECTED 态。
  - 失败重试须遵守 **T5 连接间隔超时**。

### 6.4 终止连接
连接终止是建立的逆过程。本地视角 TCP 连接可随时断开，但 **HSMS 仅允许在 CONNECTED-NOT SELECTED 子状态终止连接**。任一实体都可发起终止，进入 NOT CONNECTED 态。

---

## 7 HSMS 消息交换过程

所有 HSMS 过程的消息都通过已建立的 TCP/IP 连接以标准优先级流式收发；**不支持 TCP "Urgent"（紧急）数据**。

### 7.2 Select 过程（建立 HSMS 通信）
用 Select.req / Select.rsp 控制事务在 TCP 连接上建立 HSMS 通信。HSMS 允许在 CONNECTED 态任意时刻 Select（附属标准可进一步限制在 NOT SELECTED 子状态）。

**发起方**：
1. 发送 Select.req；
2. 收到 Select.rsp 且 Select Status = 0 → 成功，进入 SELECTED 态；
3. 收到非 0 状态 → 失败，无状态迁移；
4. T6 超时仍未收到 Select.rsp → 通信失败。

**响应方**：
1. 收到 Select.req；
2. 能接受 → 回 Select.rsp（状态 0），进入 SELECTED 态；
3. 不能接受 → 回非 0 状态，无状态迁移。

双方可能同时发起相同 SessionID 的 Select（同时 Select），各自接受对方的请求并回 .rsp。

### 7.3 数据过程（Data）
- 只要连接处于 **SELECTED** 态，任一实体都可发起数据消息；在非 SELECTED 态收到数据消息将触发 Reject。
- 数据事务两类：① 期待应答的 Primary + Reply；② 不期待应答的 Primary。
- 具体过程由应用层标准（E5、E30 等）决定；消息类型由第 8 节格式中的字段判定，正常类型为 SECS-II 文本。T3 应答超时见"特别注意事项"。

### 7.4 Deselect 过程（优雅结束）
在 SELECTED 态使用，为断开 TCP 连接前提供优雅的 HSMS 结束。

**发起方**：
1. 发送 Deselect.req；
2. 收到 Deselect.rsp 且状态 0 → 成功，进入 NOT SELECTED；
3. 非 0 状态 → 失败，无状态变化；
4. T6 超时 → 通信失败。

**响应方**：在 SELECTED 态且允许 → 回 0；否则（不在 SELECTED 或本地条件不允许）回非 0。

### 7.5 Linktest 过程（链路测试）
在 CONNECTED 态任意时刻有效。

- 发起方：发 Linktest.req；T6 内收到 Linktest.rsp 即成功；超时即通信失败。
- 响应方：收到 Linktest.req 即回 Linktest.rsp。

### 7.6 Separate 过程（单方终止）
在 SELECTED 态使用，**突然终止** HSMS 通信；响应方**不回任何响应**且无论本地状态如何都必须终止通信。

- 发起方：发送 Separate.req，即进入 NOT SELECTED（发起方即完成）。
- 响应方：在 SELECTED 态则完成终止；不在 SELECTED 态则忽略。

### 7.7 Reject 过程（拒绝）
对"格式合法但语境不当"的 HSMS 消息的响应，为分布式应用开发提供诊断信息。

- 响应方（收到不当消息的一方）回 Reject.req。
- **必须**在下列情况使用：在 NOT SELECTED 态收到数据消息；收到的消息 SType/PType 本实体未定义。
- 收到 Reject 通常意味着系统配置错误或软件 bug。

---

## 8 HSMS 消息格式

### 8.1.2 总体格式

| 字段 | 字节数 | 说明 |
|---|---|---|
| Message Length | 4 | 无符号整数，MSB 在前；= 消息头(10) + 消息文本长度；最小 10 |
| Message Header | 10 | 见下 |
| Message Text | 0–n | 格式由 PType 进一步规定 |

### 8.1.4 消息头（10 字节）

| 字节 | 字段 | 说明 |
|---|---|---|
| 0–1 | Session ID（Device ID） | 字节 0 为 MSB。用于把控制消息（Select/Deselect）与后续数据消息按引用关联 |
| 2 | Header Byte 2 | 控制消息：0 或状态码；PType=0 的数据消息：**W-Bit（位 7）+ SECS Stream（位 6–0）** |
| 3 | Header Byte 3 | 控制消息：0 或状态码；PType=0 的数据消息：**SECS Function** |
| 4 | PType | 表示层类型；**0 = SECS-II 编码**；1–127 保留给附属标准 |
| 5 | SType | 会话类型；**0 = 数据消息**，其余为控制消息（见下表） |
| 6–9 | System Bytes | 唯一标识事务 |

### SType 取值表

| SType | 消息类型 |
|---|---|
| 0 | 数据消息（Data Message） |
| 1 | Select.req |
| 2 | Select.rsp |
| 3 | Deselect.req |
| 4 | Deselect.rsp |
| 5 | Linktest.req |
| 6 | Linktest.rsp |
| 7 | Reject.req |
| 9 | Separate.req |
| 8, 10 | 未使用 |
| 11–127 | 保留给附属标准 |

### 8.1.4.6 System Bytes 规则
- Primary / Select.req / Deselect.req / Linktest.req 的系统字节须与同端所有未闭合事务、以及最近完成的事务相区别（唯一）。
- Reply / Select.rsp / Deselect.rsp / Linktest.rsp 的系统字节必须与对应 .req / Primary 相同。

### 8.2 各消息格式汇总（PType=0）

| 消息类型 | SessionID（字节 0–1） | 字节 2 | 字节 3 | PType | SType | 系统字节 | 文本 |
|---|---|---|---|---|---|---|---|
| **数据消息** | 由附属标准规定* | W-bit + Stream | Function | 0 | 0 | Primary 唯一；Reply 同 Primary | SECS-II 文本 |
| **Select.req** | 由附属标准规定* | 0 | 0 | 0 | 1 | 唯一 | 无 |
| **Select.rsp** | 同 .req | 0 | Select Status | 0 | 2 | 同 .req | 无 |
| **Deselect.req** | 同先前 Select.req* | 0 | 0 | 0 | 3 | 唯一 | 无 |
| **Deselect.rsp** | 同 .req | 0 | Deselect Status | 0 | 4 | 同 .req | 无 |
| **Linktest.req** | **0xFFFF** | 0 | 0 | 0 | 5 | 唯一 | 无 |
| **Linktest.rsp** | **0xFFFF** | 0 | 0 | 0 | 6 | 同 .req | 无 |
| **Reject.req** | 同被拒消息 | 被拒消息的 PType 或 SType | 原因码 | 0 | 7 | 同被拒消息 | 无 |
| **Separate.req** | 由附属标准规定* | 0 | 0 | 0 | 9 | 唯一 | 无 |

\* 表示可由附属标准进一步规定。

**数据消息的 Header Byte 2 位布局**：

```
位:  7        6 ... 0
    W-Bit    Stream（7 位无符号整数）
```

- W-Bit=1：Primary 期待 Reply；W-Bit=0：不期待。**Reply 消息恒置 0**。
- Header Byte 3 的 Function：最低位（位 0）=1 表示 Primary，=0 表示 Reply。

### Select Status 码

| 值 | 含义 |
|---|---|
| 0 | 通信已建立，Select 成功 |
| 1 | 通信已激活（此前已 Select 过） |
| 2 | 连接未就绪 |
| 3 | 连接数耗尽（实体仅能同时服务一个 TCP/IP 连接） |
| 4–127 | 保留给附属标准 |
| 128–255 | 保留给本地实体自定义原因 |

### Deselect Status 码

| 值 | 含义 |
|---|---|
| 0 | 通信已结束，Deselect 成功 |
| 1 | 通信未建立（尚未 Select 或已 Deselect） |
| 2 | 通信忙（会话仍被使用，暂不能优雅交还；如必须终止，最后手段是用 Separate） |
| 3–127 | 保留给附属标准 |
| 128–255 | 保留给本地自定义 |

### Reject 原因码

| 值 | 含义 |
|---|---|
| 1 | SType 不支持 |
| 2 | PType 不支持 |
| 3 | 事务未开启（收到无对应请求的响应类控制消息，即偶数 SType 无 .req 对应） |
| 4 | 实体未 Selected（非 SELECTED 态收到数据消息） |

### 报文示例（十六进制）

**S1F13（W=1，系统字节=5，SessionID=1）**：
```
00 00 00 0A   长度 = 10（仅头）
00 01         Session ID = 1
81            W=1, Stream=1
0D            Function = 13
00            PType = 0
00            SType = 0
00 00 00 05   System Bytes = 5
```

**Linktest.req（系统字节=10）**：
```
00 00 00 0A   长度 = 10
FF FF         Session ID = 0xFFFF
00 00         字节 2、3 = 0
00            PType = 0
05            SType = 5（Linktest.req）
00 00 00 0A   System Bytes = 10
```

**Select.req（SessionID=1，系统字节=1）**：
```
00 00 00 0A   长度 = 10
00 01         Session ID = 1
00 00         字节 2、3 = 0
00            PType = 0
01            SType = 1（Select.req）
00 00 00 01   System Bytes = 1
```

---

## 9 特别注意事项（Special Considerations）

### 9.2 定时器参数

HSMS 定时器：

| 定时器 | 含义 | 典型值 |
|---|---|---|
| T3 | 应答超时：发出 Primary 后等待 Reply 的时间上限 | 45 s |
| T5 | 连接间隔：断开/失败连接后到再次发起连接的最小间隔 | 10 s |
| T6 | 控制事务超时：Select/Deselect/Linktest 等控制事务保持开启的上限 | 5 s |
| T7 | 连接空闲超时：TCP 建连后未收到 Select 的等待上限（超时断开连接，回到监听） | 10 s |
| T8 | 网络字符间超时：完整消息相邻字节到达间隔上限（超时丢弃残缺消息并断开连接） | 5 s |

### 其他要点
- **T3 到期**：事务失败，SECS-II 层可发 S9F9。
- **T5 到期**：在主动模式重连前必须等待的最小时间。
- **T6 到期**（Select/Deselect/Linktest 无响应）：通信失败，须断开 TCP 连接。
- **T7**：被动实体建连后对方迟迟不 Select，防资源耗尽。
- **T8**：检测 TCP 流中的假死/残缺消息。
- 对同一被动实体的**并发多次连接请求**：实体可只服务一个连接，对多余请求回 Select Status=3（连接耗尽）后关闭。
- **消息分帧**：TCP 是字节流协议，接收方必须先读 4 字节长度再按长度读剩余字节，处理分片到达的消息。
- **System Bytes 管理**：双方各自维护计数器，应答必须原样回带请求的系统字节。

---

## 10 实现建议（源自标准附录与工程实践）

- **务必实现 Linktest**，不要只依赖 TCP keepalive（OS 级 keepalive 超时太长、不可靠）。
- **System Bytes 必须唯一**（建议自增计数器，回绕于 0xFFFFFFFF）。
- **处理分片读**：按 4 字节长度前缀循环 `readExact`。
- **实现 T7**：防止被废弃的连接占用资源。
- **记录所有控制消息日志**：调试连接问题必备。
- 设备侧通常配置为 **Passive（被动）**，EAP/主机侧为 Active（主动）；双方都配主动则永远连不上——这是现场最常见错误。
- 优雅关闭：先 Deselect 再断 TCP；Separate 仅作最后手段。

---

## 附：原文对照

页码为 `pdf/SEMI E37-0298 HSMS Generic.pdf` 的实际页序（共 24 页）。

| 本文节 | 原文节 | 起始页 |
|---|---|---|
| 1 目的 / 2 范围 / 3 引用文档 / 4 术语 | 1 Purpose、2 Scope、3 Referenced Documents、4 Terminology | 1 |
| 5 HSMS 概览与状态图 | 5 HSMS Overview | 3 |
| 6 TCP/IP 的使用 | 6 Use of TCP/IP | 5 |
| 7 消息交换过程 | 7 HSMS Message Exchange | 6 |
| 8 消息格式 | 8 HSMS Message Format | 10 |
| 9 特别注意事项（定时器） | 9 Special Considerations | 15 |
| 10 实现建议 | 原文附录 A + 工程实践 | — |
