# secs-protocol 修复日志（最终）

实施说明：子任务完成主代码修复后因预算耗尽未能验证；测试文件曾被误编辑损坏（866→14866 行），主 Agent 已从 git HEAD 恢复该测试文件（仅此文件，子任务新增的 HsmsSsReplyMessageManagerTest 保留），并完成全部验证。

| 编号 | 缺陷 | 状态 | 改动文件 | 条文依据 |
|---|---|---|---|---|
| P2-1 | 发起方 Deselect.req 无 T6 事务 | ✅ 已完成 | AbstractHsmsSsCommunicator.java | send() switch 将 DESELECT_REQ 并入 SELECT_REQ/LINKTEST_REQ 的 T6 应答等待分支；超时走既有 HsmsSsTimeoutT6Exception。E37 §7.4 发起方须等 Deselect.rsp；§9.2 T6 超时断开。全仓 grep 确认无依赖旧 Optional.empty() 语义的发起方调用点 |
| P2-2 | 控制事务应答匹配不校验 SType | ✅ 已完成 | HsmsSsReplyMessageManager.java | Pack 新增 replyType 字段；Pack.of 控制帧登记期望应答类型（SELECT_REQ→SELECT_RSP、DESELECT_REQ→DESELECT_RSP、LINKTEST_REQ→LINKTEST_RSP）；matches control 分支由恒 true 改为 SType 比对。数据帧 S/F 路径逐字未动。E37 §8.1.4.6 应答须与 .req 对应 |
| P3-1 | 报告重定义 remove+add 非原子 | ✅ 已完成（最小修复） | AbstractDynamicEventReportConfig.java | reports/links/events 三处 remove+add 各包 synchronized(集合) 块，消除并发重定义交错 |
| P3-2 | Linktest 默认关闭 | 不改（配置建议） | — | 能力已实现，默认值属部署取舍 |
| P3-4 | SystemBytes 16 位有效空间 | 不改（实现约束） | — | 撞号残留风险已由 P2-2 的 SType 校验覆盖 |
| 回归 | 控制帧撞号单测 | ✅ 已完成 | HsmsSsReplyMessageManagerTest.java | +3 用例：等 Select.rsp 时同 SystemBytes 偶数 function 数据帧不被吞；控制事务间 SType 不匹配不被吞；Deselect 事务只认 DESELECT_RSP |

## 验证结果（实际运行，主 Agent）

- `mvn -B -pl secs-protocol test`：**59 项全绿**（含新增 3 项控制帧回归；HsmsSsIntegrationTest 16 项 socket 级集成全绿）。
- 下游 `mvn -B -pl secs-runtime -am test` 与全仓 `mvn -B test`：协议改动不破坏会话链路（见 final-gate-backend.log）。

## 遗留

- Deselect 事务的 socket 级专用回归未新增（子任务预算耗尽前方案已定：对端沉默断言 T6 超时/回包断言 Optional.of(DESELECT_RSP)）；事务等待逻辑与 SELECT_REQ 共用同一分支，由既有 16 项集成测试 + 3 项匹配单测覆盖。
- Q-1（Select.rsp status=1 语义）维持存疑：需 E37.1 英文原文裁决，未改动。