# 3.5.2 发布说明

发布日期：2026-11-26
类型：缺陷修复版（收口升级计划 UP-01～UP-06；含一项配置行为变更，升级前请读第三节）

3.5.1 把工程门禁修到可信，这一版把门禁暴露出的正确性缺口补上：晶圆属性的值来源不再
静默编码成空字段，分段保存与心跳不再交错出混合快照，压测取消、控制状态迁移、主机上限
这些高风险点都有了专项测试。同时让分层门禁自己也能自证有效——此前 ArchUnit 有几条规则
在 `verify` 阶段可能空转全绿。

## 一、配置行为变更：晶圆属性类型与值来源必须相容

`context` 段的每一条属性现在在编译期校验类型与 `source` 的相容性，错误码
`CONTEXT_SOURCE_TYPE_MISMATCH`，信息里带属性名：

| 属性类型 | 允许的值来源 |
|---|---|
| `ASCII` | `Text`、`CycleText`、`Const`（按既有规则格式化成字符串） |
| 数值型（`U1`–`U8`、`I1`–`I8`、`F4`、`F8`、`BOOLEAN`） | `Const`、`CurveSource`、`ExprSource`、`CycleNumber` |

以前把 `CurveSource` 配给 ASCII 属性，报文里只会得到一个空 ASCII 字段，几乎无法从现场
现象倒推到配置；现在保存/校验时直接拒绝。`SourceEvaluator.text()` 的空串兜底仍保留，
但正常配置不会再走到那条路径。

## 二、并发与一致性

- **分段保存纳入心跳锁**：`applySections` 与 `applyHot`、`pumpNow` 共用 `pumpLock`，
  锁顺序固定为 `lifecycleLock → pumpLock`。多步替换（视图 → 值来源 → 编排 → 数据 → 周期）
  对心跳原子可见，不再出现“新编排 + 旧值来源”的混合快照。
- **压测取消**：为节奏函数补专项测试，60 秒间隔下取消在亚秒内生效，越过总预算立即停。
- **控制状态**：`ControlStateHolder.change` 的原子 check-then-set 有并发回归——100 条线程
  同时迁移到同一状态时只发一次通知。
- **主机实例上限**：64 台边界有专项测试，超限先拒绝且不写持久行，避免留下装不回来的孤儿实例。
- **重载失败回滚**：新增包内故障注入，验证「关旧之后注册失败」时用 `lastGood` 快照把旧配置拉回。
- **S2F15 并发写**：8 个写者基于同一 revision 提交，恰好一个成功、其余显式冲突，正文与版本号只前进一次。

## 三、升级须知

| 变化 | 影响 | 建议 |
|---|---|---|
| 晶圆属性类型与 source 不相容现在报错 | 此前能保存的部分配置会被拒绝 | 按第一节矩阵调整 source，或改用匹配的 ValueType |
| 分段保存与心跳互斥 | 同一会话上的分段保存可能短暂等待心跳 | 属预期，换来的是状态一致 |

配置文档格式、数据库 schema、HTTP API 形状均不变；除第一条外无行为变化。

## 四、门禁自身的加固

- ArchUnit 哨兵按包设下限，覆盖 core/app/api/runtime/store/protocol/json；移除所有
  `allowEmptyShould(true)`，规则目标为空时直接失败。`cli` 不在 runtime 的 classpath 上，
  原先那条反射守门恒空，已移到 `secs-cli` 模块并补类数哨兵。
- `scripts/package.ps1` 的 `-SkipJars` 也做产物新鲜度告警（此前只有 native 与前端有）。
- CI action 升到 Node 24 运行时版本：`checkout@v5`、`setup-java@v5`、`setup-node@v5`、
  `setup-python@v6`、`upload-artifact@v6`。

完整任务清单与验收标准见[后续升级计划](upgrade-plan-2026-11-26.md)。

## 五、门禁结果

| 检查 | 结果 |
|---|---|
| `npx tsc --noEmit` | 通过 |
| 界面文案门禁 | 通过：144 个文件 / 7250 段文案 |
| Vitest | 35 个文件 / 258 项通过 |
| Playwright（Mock E2E） | 75 项通过 |
| `mvn -B test`（后端全模块） | BUILD SUCCESS，818 项通过 |
| `mvn -B -Pnative verify` | 9 个模块 BUILD SUCCESS，`NativeRuntimeIT` 3/3 无跳过 |
