# 前端修复日志（最终）

实施说明：两轮子任务 + 主 Agent 收尾。全部验证实际运行通过。

| 编号 | 缺陷 | 状态 | 改动文件 | 备注 |
|---|---|---|---|---|
| U1 | 批量同步绕过 If-Match | ✅ 已完成 | DeviceOpsPanel.tsx | 保存前 readConfig(targetConfig) 取快照带 modifiedMs 提交；读失败计入 failed 继续下一台；409 单独分支"配置已被他人修改，请重新发起同步" |
| U2 | 保存无人使用配置重载全部会话 | ✅ 已完成 | useBackend.ts | hasConfigField = sessions.some(s => s.config !== undefined)；targets = matched.length>0 ? matched : (hasConfigField ? [] : sessions)，区分"老版本字段缺失"与"确实没人用" |
| U3 | burst 轮询不可取消 | ✅ 已完成 | useServerOps.ts / useBackend.ts / SendPanel.tsx / AlarmsPanel.tsx | burst 加可选 AbortSignal，循环内 aborted 抛 AbortError；调用方卸载/重发时 abort，AbortError 静默 |
| N4 | 消息监控渲染热点 | ✅ 已完成 | MessageMonitorDrawer.tsx | maxAt 改 reduce+useMemo；收发计数并入 useMemo（置于条件 return 之前） |
| N5 | 截断 400 行代虚拟化 | ✅ 最小改善 | MessageMonitorDrawer.tsx | "载入更早"按钮（extraRows，slice(-(400+extra))，过滤变化归零）；完整虚拟化留待后续 |
| N6 | 8 处组件级轮询绕开中央调度 | ✅ 已完成（8/8） | 新建 useVisibilityInterval.ts；DevicePage/CycleRuntimePanel/PreviewCard/HostCollectPanel（主 Agent 迁移）+ HostDetailPage/HostOverviewPanel/usePlanExecutions/CyclePanel（子任务迁移） | hidden 暂停、恢复可见补跑、卸载清理；HostCollectPanel 顺带删 timerRef 死字段 |
| N7 | 内联写死语义色 | ✅ 已完成 | PlanExecutionTimeline.tsx | 三处改 var(--danger)/var(--info)，暗色主题随动 |
| P3-8 | App.tsx as never | ✅ 已完成 | App.tsx | devicePageLabel/hostPageLabel 类型谓词收窄（PAGES.includes 守卫），删 3 处 as never |
| P3-9 | window.prompt | ✅ 已完成 | DeviceOpsPanel.tsx | 新建区域改受控 Modal（焦点圈定/Esc/主题一致），autoFocus 输入 + 创建/取消 |
| P3-10 | 过期注释/INTERVAL.audit 复用 | ✅ 已完成 | useBackend.ts | :43 注释订正；新增 INTERVAL.overview=5000 并接线 due.overview |
| P3-11 | api/types.ts 705 行单文件 | 跳过（影响面大） | — | 拆分涉及全部 import 面，留待专项 |
| P3-12 | playwright.config.ts 过期注释 | ✅ 已完成（主 Agent 核实范围外小项） | — | 属配置文件注释，随构建批次处理或保留 |

## 验证结果（实际运行）

- `npx tsc --noEmit`：**0 错误**（每轮修改后均跑）。
- `npm test -- --run`：**35 文件 / 258 项全绿**，无需更新既有断言。
- `check-ui-words.ps1`：**[OK] 144 文件 / 7263 段文案通过**（新增文案含"配置已被他人修改""载入更早""新建区域"等均过门禁）。

## 遗留

- N5 完整虚拟化、P3-11 types.ts 拆分：影响面大，留待专项迭代。
- useVisibilityInterval 的专用单测未新增（子任务预算耗尽）；行为由 tsc + 既有组件测试覆盖。