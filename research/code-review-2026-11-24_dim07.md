# 前端专项评审（维度 07）— secs-simulator-ui

评审日期：2026-11-24 ｜ 方式：只读静态评审（未运行构建/测试）
对象：`secs-simulator-ui/`（Vite + React 18 + TS）
参照：`docs/reports/frontend-analysis-2026-11-23.md`（下称"历史报告"，其第八节声称四批次改进已全部落地）、`docs/reports/release-notes-3.5.0.md`

---

## 现状概述

- `src/` 实测 206 个文件（含 35 个 `*.test.ts(x)` 与约 35 个 CSS；行数用逐文件 `^` 计数核得）。分层与 README 声明一致：`app/`（App.tsx 873 行 + router + layout/）、`api/`（base/types/sessions/configs/messages/hostsim/hostsV2/paramTemplates + index.ts 31 行）、`features/`（pages 9 / panels 44+ / editors 22 / charts 3）、`shared/`（ui 23 / lib 26）、`state/`（27 个 hook/仓库）、`styles/`（tokens/base/controls/panels/layout/responsive 分文件）。
- 3.5.0 声称的收尾（跳转修正、草稿统一、首屏减半）在代码中基本属实（见下节抽查）；但历史报告"中/低"档的 6 条问题（#10/#11/#12/#14/#16 的 useBackend 部分/#17 的采集器残留）**未修**，且本次发现若干新问题（批量同步绕过 If-Match、保存未启用配置时全量重载所有会话、组件内写死颜色等）。
- 首屏分包已落地：`app/App.tsx:44-58` 八个页面 `lazy(() => import(...))`，`App.tsx:755` `<Suspense fallback={<PageLoading />}>`。`vite.config.ts` 无 `manualChunks`（仅 react 插件 + dev 代理），分包完全靠路由级动态 import——与发布说明的口径一致；562→264 kB 的数值本次未重新构建测量。

## 历史修复抽查（逐项）

**1. 命令面板三处跳转 + CEID 命令删除 —— 属实。**
`app/App.tsx:431` `run: () => goTo(\`device-zone:${session.id}:config\`)`；`:438` 运行区同构；`:514` `goTo(\`device-page:${sessionId}:config:svids\`)`；`:498` 注释"CEID 那条命令已删：它只把值写进 `backend.ceid`…"。全 src grep `setCeid|backend\.ceid|填入 CEID` 仅命中该条注释，无代码残留。

**2. 旧深链兜底改 plans —— 属实。**
`app/router.ts:216` `const mapped = LEGACY_DEVICE_PAGE[legacy] ?? ['runtime', 'plans'];`；`goTo` 的旧映射表只剩 `svids`（`App.tsx:265-268`），`catalog` 别名已不存在。`app/router.test.ts` 存在（180 行），但新增用例名"falls unknown 2.x sub-page names…"未逐字核对（证据缺口，见末节）。

**3. 批量同步：takePort + 失败逐台回报 —— 属实。**
`features/panels/DeviceOpsPanel.tsx:139-147` `const takePort = (): number => { while (usedPorts.has(cursor)) { cursor += 1; } … }`，占用集含 `overview.map((row) => row.port)` 与 `Object.values(configPorts)`（:134-137）；:149 `const failed: { name: string; reason: string }[] = []`；:198 `if (done > 0)` 才报成功；:202-209 有失败时 warn 并 `return`（弹框不关）。**但引入新问题**：:186 `await backend.saveConfig(targetConfig, text, null)` —— If-Match 传 null，见问题清单 N1。

**4. ConfigPanel 并入 useConfigDraft —— 属实。**
`features/panels/ConfigPanel.tsx:9` `import { useConfigDraft } from '../../state/useConfigDraft';`；:85 `const draft = useConfigDraft(backend, activeSessionId, targetConfig === '' ? undefined : targetConfig);`（注释 :83 "不传 sections = 整份保存"）；:130 用 `draft.baselineText` 做 diff。自管 draft/baseline/If-Match 已不在该文件（全文件 371 行，grep 无 `loadedMs`）。

**5. 死代码清理（uplot/chartMode/dirtyElsewhere/openHostSim 等）—— 大体属实，一处口径不符。**
全 src grep `uplot|chartMode|dirtyElsewhere|openHostSim|closeHostSim|sendHostSml` **0 命中**。但历史报告写"连同 `api/hostsim.ts`…一起清掉"，实际 `api/hostsim.ts` 仍存在（93 行）：三个连接类方法确已删除，采集器端点（实例维度 + 会话维度 `/api/v1/sessions/{id}/host-sim/collector*`）保留且仍被 `state/useServerOps.ts` 装配进控制器——其中会话维度三个方法在 UI 已无调用方（grep `backend\.collectorState|backend\.saveCollector|backend\.collectorCommand` 0 命中），属清理不彻底（问题 N3）。

**6. 明细轮询降频 + 输入缓存防抖 —— 属实。**
`state/useBackend.ts:52` `sessionDetailIdle: 20_000`，:355 `due.detail = now + (sessionDetailHot ? INTERVAL.sessionDetail : INTERVAL.sessionDetailIdle)`；与报告声明的"不清空 activeSession、只降频"差异一致。`state/useInputDraftCache.ts:216` 改回初始值删条目（`value: initial === value ? '' : value`），:220 `flushTimer.current = window.setTimeout(flush, FLUSH_DELAY_MS)`，:236-237 `pagehide`/`visibilitychange` 补写，:238-245 清理完整。

**7. hook 测试补齐 —— 属实（文件层面）。**
`state/useConfigDraft.test.tsx`（358 行）、`state/useEventStream.test.tsx`（318 行）、`src/test/renderHook.tsx` 均存在。注意 renderHook 实为 87 行，报告写"30 行"，属描述性偏差，不影响结论；具体用例数（13/12）未逐条核对。## 逐项核查

### 1. 超大文件（>800 行，行数来自逐文件计数）

| 文件 | 行数 |
|---|---:|
| `features/panels/hostsim/HostCollectPanel.tsx` | 880 |
| `app/App.tsx` | 873 |
| `features/editors/paramTemplateSimulation.ts` | 897 |
| `features/panels/hostsim/HostTracePanel.tsx` | 849 |

紧随其后：`DeviceOpsPanel.tsx` 770、`CeidReportConfigPanel.tsx` 767、`api/types.ts` 705、`HostListPage.tsx` 666、`DevicePage.tsx` 664、`ConfigFormEditor.tsx` 662。
评估：纯函数文件（paramTemplateSimulation.ts）大而无妨且有单测；但 `HostCollectPanel`/`HostTracePanel` 是"取数 + 定时器 + 表单 + 表格"混在一个组件里的典型（HostCollectPanel.tsx:423 组件内自建 2s 轮询），`App.tsx` 873 行仍承担 SSE 装配、报文缓冲、命令面板构造、goTo 映射四类职责，是最值得先拆的两个方向。

### 2. 状态与数据流

- **单一调度器**：`state/useBackend.ts:373` `const timer = window.setInterval(() => void tick(), 1000);`，:345 `document.hidden` 时跳过，:383-388 `visibilitychange` 注册/注销、:386 `clearInterval` 配对完整。过期响应有双保险：`requestSeq`（:241-244）与 `activeSessionRef` 比对（:284）。
- **SSE**：`state/useEventStream.ts:125` 每次（重）连 `await client.ticket()`；:212-216 error 时 `source.close()` + `scheduleReconnect()`（2s，:52）；:231-237 cleanup 置 `disposed`、`clearTimeout(retryTimer)`、`stream?.close()`。暂停缓冲上限 5000 丢最旧（:167-169）。回调经 ref 传递避免反复拆建（:76-77）。设计干净，未见泄漏。
- **App 报文合并**：`app/App.tsx:197-202` 150ms `setTimeout` 合并，:205-210 卸载时 `clearTimeout`；待合并队列上限 4000（:191-192），liveMessages 上限 5000（:70）。
- **定时器清理抽查（grep setInterval/setTimeout 全部 24 处）**：成对清理的有 useBackend:373、useEventStream:112、MessageMonitorDrawer:142（:143 return clear）、useInputDraftCache:281-285（三次回填定时器统一 clear）、useToasts:71。**未见清理/不可取消的**：
  - `shared/ui/CommandPalette.tsx:119` `window.setTimeout(() => inputRef.current?.focus(), 0);`（无 clear；有 optional-chain 兜底，风险低）
  - `shared/ui/JsonEditor.tsx:98` `window.setTimeout(() => {`（grep 只见创建，未读到对应清理，需人工复核）
  - `state/useServerOps.ts:75-91` burst 完成判定 `for(;;) { await new Promise((resolve) => setTimeout(resolve, 500)); … }`——**无 AbortSignal、无卸载取消**，最长 6 分钟（:70-73）；组件卸载后循环照跑并持续打后端。
- **组件级轮询绕开调度器**：`features/pages/DevicePage.tsx:391`（5s）、`features/pages/HostDetailPage.tsx:47`（2s）、`features/panels/CyclePanel.tsx:97`（1.5s）、`CycleRuntimePanel.tsx:132`、`PreviewCard.tsx:214/224`、`hostsim/HostOverviewPanel.tsx:52`（2s）、`hostsim/HostCollectPanel.tsx:423`（2s）、`state/usePlanExecutions.ts:118`（3s）。grep 确认这些 setInterval 均在 useEffect 内；是否检查 `document.hidden` 未逐个读取（useBackend 的"后台整体暂停"承诺只覆盖中央调度器，不覆盖这批）。

### 3. API 层

- `api/base.ts:107-127`：统一超时（默认 8s / 长操作 60s，:78-81），`runtimeApiFailure` 短路（:113-114），TimeoutError 归一化为中文 408（:122-123）；调用方自带 signal 时不覆盖（:117）——**但也意味着自带 signal 的请求没有超时兜底**。
- `readJson`（:130-165）：非 2xx 提取 `{error}` 码；200+HTML 识别 `/^<(!doctype\s+html|html)/i`（:156）并给"后端地址指错了，或后端没启动"文案；空 body 返回 `undefined as T`（:148-149，类型上撒了个小谎，调用方按非空类型消费时有隐患）。
- **v1/v2 混用现状（grep `/api/v[0-9]`）**：运行态操作已迁 v2——`api/sessions.ts:137` `/api/v2/devices/{id}/runtime/ceid/{ceid}`、:150 `runtime/alarm/{alid}`、:207 `runtime/plans/{id}/enabled`、:111/:121 link/connect、online/offline、:214-231 cycle*；主机域全在 `hostsV2.ts`（`/api/v2/hosts*`、`/api/v2/host-templates`）；参数模板 `/api/v2/param-templates`。**仍走 v1**：health/sessions CRUD/catalog/events/message/burst/overview、configs、ticket/events(SSE)/messages/audit/log-level、以及 `hostsim.ts` 的两套采集器端点。未发现同一能力 v1/v2 双调用打架的情况；`grep '/api/v1/sessions.*(ceid|alarm)'` 无命中，迁移是干净的。后端是否仍保留 v1 端点未核（本次未读 Java 侧，见置信度）。

### 4. 性能

- 路由懒加载已落地（8 个 lazy + Suspense，见概述）；`vite.config.ts` 无 manualChunks，vendor(react) 与外壳同块。
- **大列表无虚拟化**：`MessageMonitorDrawer.tsx:9` `const MAX_ROWS = 400;`，:199 `filtered.slice(-MAX_ROWS)`——用"截断到 400 行"代替虚拟化，5000 条缓冲里更早的报文在 UI 上不可见（有计数但看不到行）。全 src grep `virtual|overscan` 无虚拟化实现。DataTable（455 行）同样未见虚拟化。
- **每次渲染 O(n) 开销**：`MessageMonitorDrawer.tsx:151` `const maxAt = source.length > 0 ? Math.max(...source.map((message) => message.at)) : 0;` 在渲染体内对至多 5000 条做 spread + map，且在 useMemo 之外；:200-211 recv/sent 计数循环也在渲染体内。150ms 合并帧下高吞吐时这是热点。
- 图表：charts/ 三个手写 SVG 组件（EventCurveChart 589 行内含 canvas fillStyle，:400），颜色经 `read('--accent', '#0f766e')` 从 CSS token 读取、hex 仅为兜底——符合 tokens 声明；渲染节流细节未读。

### 5. 样式纪律（tsx 内 hex 抽查）

grep `#[0-9a-fA-F]{3,8}` 命中 15 处，分三类：
- **合规**：charts 的 token 兜底值（VariableChart.tsx:59-63、EventCurveChart.tsx:77-81）、`AppMark.tsx:32` logo `stroke="#fff"`（品牌图形可豁免）、DevicePage.tsx:128 是注释里的 React 错误号。
- **违规**：`features/panels/PlanExecutionTimeline.tsx:153` `style={{ color: '#b42318' }}`、:160 `borderLeftColor: row.status === 'FAILED' ? '#d92d20' : '#2563eb'`、:170 又一处 `'#b42318'`——三处写死语义色（危险红/信息蓝），暗色主题下不会跟随 tokens；:40 `var(--line, #d9dee7)` 属兜底可接受。该组件整体用内联 style 而非 classes，是全站少见的例外。

### 6. 可访问性抽验

`shared/ui/Modal.tsx:106-108` `role="dialog" aria-modal="true" aria-label={title}`；Esc/焦点圈定委托 `Overlay`（:99，注释 :28-29"Esc 关闭、点遮罩关闭、锁背景滚动、焦点留在对话框里"）；:49-53 body overflow 锁/还原配对。Overlay.tsx（98 行）内部焦点陷阱实现本次未逐行读，历史报告已背书，抽验到接口层为止。resize 手柄带 `role="separator"` + aria-label（:122-123）。

### 7. 测试

- 单测：glob 实数 **35 个** `*.test.ts(x)`（shared/lib 11、panels 6、editors 6、state 5、ui 3、api/app/pages 各 1-2），与历史报告门禁"35 文件/258 项"的文件数吻合；项数未运行核对。`useBackend` 仍无单测（历史 #16 三个 hook 只补了两个）。
- E2E：`playwright.config.ts` testDir `./scripts/e2e`，webServer 用 `npm run build && preview`，API 全拦截（mock），单 worker Chromium。目录实为 **5 个 spec**（app/device/host/interaction/ui-polish）+ fixtures.ts；另有 `scripts/e2e-real/app.spec.ts` 真机套件。任务描述中"36 条"与历史报告"约 75 项"均未能从文件清单直接验证（需运行或逐条数 test()，本次未做）。config 注释 :7 仍写"能渲染 uPlot"——uplot 已删，注释过期。## 问题清单

标注：〔历史#n〕= 历史报告已提过且未修；〔新〕= 本次新发现。

### P1（正确性/数据安全）

- **N1〔新〕批量同步绕过 If-Match 并发保护**。`DeviceOpsPanel.tsx:186` `const saved = await backend.saveConfig(targetConfig, text, null);`——第三参 expectedModifiedMs 传 null，目标配置若被另一窗口改过会被静默整份覆盖。3.5.0 刚把"两页互顶 409"收敛成统一草稿保护，批量同步却成了绕过该保护的后门。建议：同步前读各目标快照带 modifiedMs 提交，409 计入 failed 列表（失败回报机制已就绪，改动很小）。
- **N2〔新〕保存无人使用的配置会重载全部会话**。`useBackend.ts:434-437` `const matched = configName === '' ? [] : sessions.filter((session) => session.config === configName); const targets = matched.length > 0 ? matched : sessions;`——注释说"老版本 sessions JSON 没有 config 字段时退回全部重载"，但"没有任何会话用这份配置"（新建配置、未启动设备的配置）同样落进 fallback，把**所有运行中设备**都 reload 一遍。应按 `sessions.every(s => s.config === undefined)` 区分"字段缺失"与"确实没人用"。
- **N3〔新/历史#17 余尾〕burst 轮询不可取消**。`useServerOps.ts:75-91` 无 signal 的 `for(;;)` + 500ms sleep，最长 6 分钟；发起压测的面板卸载/切页后循环继续打 `burstStatus`，超时后还会 throw 到已卸载组件的 catch。建议传入 AbortSignal 或挂到组件生命周期。

### P2（性能/一致性）

- **N4〔新〕消息监控渲染热点**：`MessageMonitorDrawer.tsx:151` 渲染体内 `Math.max(...source.map(...))`（至多 5000 元素 spread）+ :200-211 每次渲染全量计数循环；150ms 合并帧高吞吐时叠加。建议并入 useMemo 或增量维护。
- **N5〔新〕大列表以截断代虚拟化**：`MessageMonitorDrawer.tsx:199` 只渲染最后 400 行（MAX_ROWS=400），缓冲 5000 条中其余不可见也不可回滚查看；DataTable 亦无虚拟化。设备多、报文密时体验受限。可接受为权衡，但应引入窗口化渲染或"载入更早"。
- **N6〔新〕组件级轮询绕开中央调度器**：8 处组件内 setInterval（DevicePage:391、HostDetailPage:47、CyclePanel:97、CycleRuntimePanel:132、PreviewCard:224、HostOverviewPanel:52、HostCollectPanel:423、usePlanExecutions:118），不享受 useBackend 的 `document.hidden` 暂停与到期合并；后台标签页仍按 1.5-5s 打后端。建议提供共享的 usePolledResource（带 hidden 暂停）。
- **N7〔新〕样式违规**：`PlanExecutionTimeline.tsx:153/160/170` 三处内联写死 `#b42318`/`#d92d20`/`#2563eb`，违反"组件不写死颜色、tokens 唯一来源"；暗色主题下不随动。改 semantic token（--danger/--info）。
- **〔历史#5〕activeSession 离页不清空**：已按报告声明改为降频（useBackend.ts:52/355），属"有意保留的差异"，非回归；工作台仍每 20s 拉上一台设备明细，可接受。

### P3（卫生/尾巴）

- **〔历史#10〕`as never` 仍在**：`App.tsx:558/568/569` `[route.page as never]`，page/zone 耦合仍靠断言绕过。
- **〔历史#11〕window.prompt 仍在**：`DeviceOpsPanel.tsx:594` `const name = window.prompt('新区域名称')?.trim();`。
- **〔历史#12〕过期注释仍在**：`useBackend.ts:43` `/** 报警 / 终端消息 / 目录 / 事件：2 秒。 */`（实际只剩目录+事件）；:367 overview 仍复用 `INTERVAL.audit`。
- **〔历史#14〕api/types.ts 仍单文件**：705 行（原 718），未拆。
- **〔历史#16 部分〕useBackend 仍无单测**：state/ 下测试仅 useConfigDraft/useEventStream/configDraftStore/hostTemplates/requestSeq。
- **N8〔新〕会话维度采集器封装成死代码**：`api/hostsim.ts:62-92` `collectorState/setCollector/collectorCommand`（`/api/v1/sessions/{id}/host-sim/collector*`）经 useServerOps 挂上控制器（BackendController:190-194），但全 src 无 `backend.collectorState|saveCollector|collectorCommand` 调用。与历史#17 同类的迁移尾巴。
- **N9〔新〕小项**：`playwright.config.ts:7` 注释仍说"渲染 uPlot"（依赖已删）；`base.ts:148-149` 空 body `undefined as T` 类型不实；`CommandPalette.tsx:119`、`JsonEditor.tsx:98` 的 setTimeout 未见 clear（前者有 `?.` 兜底、后者需复核）；`base.ts:117` 调用方自带 signal 时无超时兜底（语义上可辩护，记录备查）。

## 亮点

1. **SSE 实现教科书级**：一次性票据每次重连重取、error 主动 close 防浏览器自动重连带旧票、回调走 ref、暂停缓冲有上限、disposed 标志贯穿异步链（useEventStream.ts 全文），且有 318 行专项测试。
2. **过期响应双保险**：requestSeq 序号 + activeSessionRef 比对（useBackend.ts:241-244/284），切设备不挂旧数据；useLayoutEffect 绘前清明细（:410-413）注释把"为什么"写透了。
3. **错误文案面向下一步**：200+HTML、408 超时、壳内后端未启动短路（base.ts:113/122/156-162），全是可行动的提示。
4. **收尾质量高**：抽查 7 项修复 6 项完全属实、1 项口径偏差（hostsim.ts 保留采集器端点有注释解释），历史报告第八节可信度高。
5. 首屏分包用最小手段（8 个 lazy）达成，未过度工程化 manualChunks。

## 测试覆盖评估

- **单测 35 文件**：纯函数层（shared/lib 11 个）覆盖扎实；3.5.0 补的两个最复杂 hook（草稿/SSE）方向正确。**缺口**：useBackend（轮询调度、reloadSessionsUsing 的 fallback——恰是 N2 藏身处）、useServerOps（burst 轮询）、useInputDraftCache（防抖/回填时序）无测试；组件测试多为 renderToStaticMarkup 静态渲染，交互路径靠 E2E。
- **Mock E2E 5 spec**（app/device/host/interaction/ui-polish）+ 真机 e2e-real 1 spec：API 全拦截、跑真实构建产物，能抓路由/渲染/文案回归；但 mock 契约与真实后端 v1/v2 演进的一致性无守护（本次静态评审也未核后端）。用例条数（任务说 36、历史报告说 75）未验证。
- 建议优先补：reloadSessionsUsing 的"无人使用该配置"分支、批量同步 409 分支（配合 N1 修复）、burst 取消。

## 置信度说明

- **高置信**（逐字读到源码）：历史修复抽查 1/3/4/5/6 项、SSE/调度器/清理配对、v1/v2 端点清单、N1/N2/N3/N4/N7、超大文件行数（逐文件计数）、单测文件数 35、E2E 文件清单。
- **中置信**（grep 全量命中但未读上下文全文）：定时器清理配对中 CyclePanel/PreviewCard/HostCollectPanel 等 8 处组件轮询的 cleanup 与 hidden 行为；JsonEditor:98 定时器是否清理；`ceid` 相关 grep 覆盖 src 全目录但未含 scripts/。
- **未验证/证据缺口**：① 562→264 kB 体积与 258/75 项测试通过数——未运行构建与测试，只能确认机制存在；② router.test.ts 新增用例名与 useConfigDraft/useEventStream 的 13/12 项数——未逐条读测试体；③ Overlay 焦点陷阱实现、charts 渲染节流——未逐行读；④ 后端 v1 端点存续与契约对齐——本次范围仅前端；⑤ 任务给的"178 文件 34688 行 / e2e 36 条"与实测（src 206 文件含测试与 CSS；e2e 5 spec）口径不一致，未强行对齐。
- 工具预算内共 33 次调用（调查 30 + 写入 3），未运行任何代码，未改动 `secs-simulator-ui/` 任何文件。