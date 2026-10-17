# M6 验收报告 · 控制状态类报文

## 为什么先做这一批

Trace、模板、编排再强，主机也会在**第一秒**就卡住：几乎每个主机建立 HSMS 连接后
立刻发 S1F13（Establish Communications），等 S1F14；接着 S1F17（Request ON-LINE），等 S1F18。
没有这些应答，主机侧直接判定"设备不理我"，后面的能力一律用不上。

## 交付

会话把控制状态类报文**委托给协议层的 `Gem`**，不再自己拼：

| 主机请求 | 应答 | 委托调用 |
|---|---|---|
| S1F1 | S1F2（含 MDLN/SOFTREV） | `gem().s1f2(primary)` |
| S1F13 | S1F14（COMMACK + MDLN/SOFTREV） | `gem().s1f14(primary, COMMACK.OK)` |
| S1F15 | S1F16（OFLACK） | `gem().s1f16(primary)` |
| S1F17 | S1F18（ONLACK） | `gem().s1f18(primary, ONLACK.OK)` |
| S2F17 | S2F18（时间） | `gem().s2f18Now(primary)` |
| S2F31 | S2F32（TIACK） | `gem().s2f32(primary, TIACK.OK)` |

**为什么不自己实现**：这些应答的形状（COMMACK/ONLACK/TIACK 的字节位置、时间格式、
设备信息列表）都写在 E30/E5 里，协议层已经实现并测过。自己拼一遍只会引入偏差，
而且偏差是静默的——主机不会报错，只会"按自己的理解解析出垃圾"。

## 踩到的坑：S1F14 不是裸字节

E30 的 S1F14 报文体是：

```
<L[2] <B COMMACK> <L[2] <A MDLN> <A SOFTREV>>>
```

我第一版测试直接对报文体取 byte，报 `Not Secs2Byte`。
正确做法是先取 `L[2]` 的第 0 个元素。同理 S1F2 在设备侧回的是 `<L[2] MDLN SOFTREV>`
而不是空列表——比原来手写的空列表更接近真机。

## 实测

| 项 | 结果 |
|---|---|
| `mvn verify` | BUILD SUCCESS |
| 单元测试 | **119 个**（core 79 + app 10 + runtime 12 + cli 18），全绿 |
| 原生镜像 | `secs-sim.exe` **13.0 MB**，原生端到端 2 个用例 0.8 s |

### 用例（`SessionControlStateTest`）

| 用例 | 断言 |
|---|---|
| S1F13 | 回 S1F14；`L[2]`；COMMACK=0；设备信息含 MDLN + SOFTREV |
| S1F17 | 回 S1F18；ONLACK=0 |
| S1F15 | 回 S1F16；OFLACK=0 |
| S2F17 | 回 S2F18；内容是 ≥12 位全数字的时间串 |
| S2F31 | 回 S2F32；TIACK=0 |
| 控制流程后再 S1F1 | 仍回 S1F2，链路未被打断 |

## 还没做

- **S1F11/S1F12**（状态变量名列表）：协议层没有现成实现，需要按 SVID 定义自行组装；
  很多主机会先枚举再采集，这一条不补会影响"自动发现型"主机。
- **S2F33/S2F35/S2F37**（主机动态定义报告）：`Gem` 只回 ACK 码，不保存定义。
  要做到"主机下发的 RPTID 与我方一致"，需要在会话里维护一张动态报告表。
- **S5F1 报警**、**S10F3 终端消息**：未实现。
