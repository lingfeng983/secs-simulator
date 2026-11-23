# 前端代码与页面功能分析

分析对象：`secs-simulator-ui`（React 18 + TypeScript + Vite，3.4.0）
分析日期：2026-11-23

---

## 一、规模与构成

| 目录 | 文件 | 行数 | 职责 |
|---|---:|---:|---|
| `api/` | 10 | 2,115 | 域客户端（sessions / configs / messages / hostsim / hostsV2 / paramTemplates）+ 统一请求基础设施 |
| `app/` | 12 | 3,070 | 入口、hash 路由、外壳、侧栏 / 页签 / 标题栏、命令面板接线 |
| `state/` | 27 | 4,656 | 后端控制器与 7 个域 hook、配置草稿仓库、SSE、偏好、持久化 |
| `features/pages/` | 9 | 3,202 | 页面级容器（工作台、设备、主机、模板、审计） |
| `features/panels/` | 44 | 10,334 | 面板（含 `hostsim/` 12 个主机侧面板） |
| `features/editors/` | 22 | 4,764 | 配置表单、模板编辑、JSON 高亮、参数模板模拟 |
| `features/charts/` | 3 | 1,320 | 手写 SVG 曲线（预览 / 单变量 / 事件曲线） |
| `shared/ui` | 23 | 2,466 | 表格、弹窗、字段、命令面板、Toast 等基础件 |
| `shared/lib` | 26 | 2,156 | 纯函数（表格、diff、高亮、状态映射、文件保存） |
| `styles/` | 27 | 6,362 | tokens + 布局 + 面板 + 控件 + 响应式 |

- 运行时依赖只有 `react` / `react-dom`（`uplot` 仅 import 了 CSS，见问题 8）。
- 单测 33 个文件 / 232 项；Mock E2E 5 个 spec / 约 75 项；另有真机 E2E（`e2e-real`，需 `E2E_REAL_URL`）。
- 构建产物单 chunk 564 KB（gzip 190 KB），Vite 已给体积警告。

---

## 二、分层与数据流

```
App.tsx（外壳：路由 / 页签 / 侧栏 / 抽屉 / 命令面板 / 快捷键）
  ├─ useRoute()            hash 路由 + 2.x 旧深链迁移
  ├─ useBackend(client)    轮询调度 + 域 hook 装配 → BackendController
  │    ├─ useSessionCommands / useConfigCommands / useDeviceOps
  │    ├─ useDeviceEdits / useAuditFeed / useServerOps / useLogLevel
  │    └─ configDraftStore（按配置名共享的草稿仓库）
  ├─ useEventStream()      SSE：报文 + 7 类类型化通知
  └─ usePreferences()      主题 / 密度 / 宽度 / 页签 / 抽屉宽度（localStorage）
pages/ → panels/ → editors/ → charts/
              ↕
        shared/ui + shared/lib
```

四个值得记下的机制：

1. **单一轮询调度器**：`useBackend` 里只有一个 1 秒 tick，各资源按自己的到期时间刷新（会话 2s、目录/事件 2s、配置 5s、审计 5s、总览 5s），页面隐藏时整体暂停、回到前台立刻补一次。相比多个定时器各打各的，请求不再互相撞车。
2. **SSE 一次性票据**：每次（重）连都换票，断线 2 秒重连；报文先攒 150 ms 再合并进状态（避免高吞吐时整页每帧重渲染），缓冲上限 5000 条。
3. **配置草稿按「配置名」共享**：草稿文本 + 磁盘基线 + 每段规范值都在 `configDraftStore` 里，页面只是它的视图。切页不重新读盘，保存只推进「本页那几段」的基线——这是「按段保存」能成立的前提。
4. **输入草稿缓存**：`useInputDraftCache` 在 DOM 层按「路由 key + 控件标识」把输入值写进 localStorage，页面重新挂载后回填（三次：0 / 200 / 700 ms），避免上百个受控组件逐个改造。

---

## 三、页面功能清单

### 3.1 全局外壳

| 区域 | 能力 |
|---|---|
| 顶栏 | 刷新、命令面板、连接设置（桌面壳可在「本机 / 远程」间切换）、通用设置、主题切换、消息监控开关、快捷键表 |
| 侧栏 | 三组导航（设备 / 主机 / 独立工具）；设备树按区域分组，含未启动设备；可拖宽、可折叠成图标栏，宽度持久化 |
| 页签栏 | 一个设备 / 一台主机 / 一个独立页各一个标签；子页切换只更新该标签地址；右键「关闭其他 / 左侧 / 右侧 / 全部」；工作台常驻 |
| 命令面板 | Ctrl+K：跳页面、打开设备/主机、刷新、切主题/密度、收起侧栏、查看快捷键、设备目录里的 CEID / SVID |
| 快捷键 | Ctrl+K、1–9/0 跳导航、R 刷新、`/` 聚焦搜索、`[` 收起侧栏、`?` 帮助、Esc 关浮层（浮层打开时只放行 Esc） |
| 右侧抽屉 | 消息监控：先选区域再选对象（设备/主机/全部渠道），按时间窗过滤，5000 条缓冲 + DOM 行数上限 |
| 全局提示 | 后端不可达横幅（含「重试」「修改地址」）、Toast |

### 3.2 工作台（`#/workbench`）

一页一张表：**一行一台设备**，读数与操作同行。

- 列：设备、区域、端口、状态（四态）、控制状态、角色（设备/主机模拟）、单元数、报文/分钟、事件/分钟、已上报、限流丢弃、禁用跳过、S6F1、S9Fx、活动报警。
- 工具条：状态筛选、区域筛选（可现场新建区域）、搜索（设备名/会话 id/中文状态）。
- 行内动作：启动 / 停止（先确认）/ 重启 / 上线 / 下线 / 删除（先确认）/ 打开设备页。
- 批量：全选、批量重启、批量停止、批量删除、**批量同步配置**（以第一台为源，复制配置，只改会话 id / 端口 / Device ID / 名称）。
- 新建：单台（连接信息 + 模板 + 区域 + 是否立即启动）；**批量**（前缀 + 起始序号 + 台数 + 起始端口，一台一份配置、Device ID 恒 0、重名整批拒绝、本地先查重）。
- 数据进出：CSV 导出（19 列）/ 导入（按「配置」列建会话，逐行统计成功/失败/跳过）。

### 3.3 设备页（`#/device/{id}/{zone}/{page}`）

操作栏只有三件事：切配置区/运行区、看监听端点（地址:端口 | DeviceID）、启动/停止/上线/下线。页面不按设备状态分家——未启动的设备同样有全部页签，只是运行区显示空态。

**配置区（11 页）**

| 页 | 管什么 | 保存方式 | 提交的段 |
|---|---|---|---|
| 概览 | 四张摘要卡（连接 / 结构 / 周期 / 最近活动）+ 身份信息表；静态事实读配置原文（5 s 轮询），运行态读会话 | 只读 | — |
| 链接配置 | HSMS 参数 13 字段：模式、绑定地址、端口、Device ID、T3–T8、Trace 容量（高级项已平铺） | 表单末尾「保存」，启动中先确认重建会话 | `session` |
| 结构配置 | 设备全局（号段规划 + 事件发送三项）+ 单元卡片 | 全局点「保存」；单元新增/编辑/删除即存 | `structure` + `throttle` |
| 设备常量 | ECV 表（S2F13 查询 / S2F15 写入的初始值） | 弹窗确认即存 | `constants` |
| 晶圆属性 | LOTID / RCPID / PPID / WAFERID | 弹窗确认即存 | `context` |
| 变量配置 | 全局 + 各单元 SVID 合并一张表；值来源、范围、从参数模板库填入 | 弹窗确认即存 | `svids` |
| 事件与报告 | 三张表：全局 CEID / 报告（RPT）/ 事件（CEID），按「所属单元」列合并 | 弹窗确认即存 | `ceids` / `reports` |
| 工艺周期 | 片长、片数、每片噪声、片内事件 + 按草稿重放的预览图 | 标题行「保存」 | `cycle` |
| 事件计划 | `events.plans` 列表 + 按草稿确定性重放的触发预览 | 弹窗确认即存 | `plans` |
| 全量配置 | JSON 编辑器：高亮、格式化、校验、保存、导入/导出、差异 | 工具条「保存」 | 整份 |
| 版本历史 | revision 元数据（版本/时间/操作者/备注/SHA）、草稿 vs 磁盘逐行 diff、回滚限制说明 | 只读 | — |

保存后的生效口径写在页面上：`session` 重建会话；`structure` 落盘但不热应用、重启设备后生效；其余段热应用。

**运行区（4 页）**

| 页 | 管什么 |
|---|---|
| 事件执行 | 计划启停、单步试跑（dry-run）、执行台账（SENT/PARTIAL/FAILED/SKIPPED、报文条数、耗时，可只看失败、导出 CSV） |
| 工艺周期 | 运行卡（启停 / 片号 / 相位 / 进度 / 已发片内事件）+ 只读周期配置 + 预览 + 片内事件台账 |
| 消息发送 | 手动 SML（含 S2F41 常用预设）+ 服务端压测发送 |
| 报警风暴 | 活动报警快照 + 增量（S5F1/S5F2）、置位/清除、风暴压测（ALID 范围、次数、间隔） |

### 3.4 主机

| 页 | 能力 |
|---|---|
| 主机清单 `#/hosts` | 与设备清单同构：表格 + 搜索 + 批量连接/断开/删除 + 新建（可从模板套用 Trace/采集方案，可关掉「立即连接」） |
| 主机详情 | 实例头：连接 / 断开 / 使设置生效 / 相位 / 重试次数；配置区 4 页（概览、报文模板 16 条、Trace 设置、采集设置），运行区 3 页（消息发送、Trace 采集、S1F3 数据采集） |
| 主机模板 | 模板只装方案（Trace / 采集），不含目标地址与 Device ID；内置两份只读，改动另存为自定义 |

### 3.5 独立工具

- **设备模板**：列表 + 弹窗编辑器（多页签，与设备配置区同构的组件复用）；内置模板不可覆盖，改动另存。
- **参数模板**：SVID 变化逻辑库（所有设备共享），带模拟预览。
- **审计**：动作 / 操作者过滤、载入更早（游标翻页）、健康信息。

---

## 四、值得肯定的设计

1. **两段式信息架构**：配置区回答「这台设备是什么」，运行区回答「现在正在发生什么」。定义类编辑全部上收配置区，运行区只留要动手的页，避免「改配置时顺手把周期停了」。
2. **一页一能力 + 一页一张表**：工作台把原来的「设备总览」「会话」两张表合并，读数与操作同一行，不再需要跨表对 id。
3. **按段保存**：每页声明自己拥有哪几段，保存只提交这些段，脏状态按段算基线——切页不会冲掉别页的未保存改动。
4. **错误文案写给「下一步」**：`readJson` 能识别「200 但拿到 HTML」并直接说「后端地址指错了或后端没启动」；409 与 400 分开解释（名字被占 vs 参数写错）；保存落盘但设备没生效时，提示必须留在页面上。
5. **不引图表库**：曲线是自己写的 SVG（min/max 包络 + avg 线、缩放平移、事件标记），包体只多几千行源码，不背一个图表依赖。
6. **可访问性**：浮层有焦点陷阱、Esc 关闭、关闭后焦点归还；表格/按钮普遍带 `aria-label`；设备页根节点挂 `role="region"` + `aria-label`，读屏与自动化都能认。
7. **降级与兜底**：桌面壳内后端起不来时不再退回同源（那会把 HTML 当 JSON），而是记下原因让每条请求带着它失败。

---

## 五、问题清单

### 高

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 1 | 命令面板三处跳转与文案不符：`· 配置区` 实际打开运行区；`查看 SVID` 落到「事件与报告」；`填入 CEID` 跳工作台，而 `backend.ceid` 已无任何界面消费 | `app/App.tsx:407-413, 478-504`、`goTo` 的 `mapped` 表 | 用户按文案操作得到别的页面；CEID 是写进没人读的状态 |
| 2 | 2.x 旧深链兜底值是已不存在的 `catalog` 页 | `app/router.ts:213` | `#/device/x/随便什么` → 运行区 `catalog` 一个分支都匹配不上 → 只剩页签、内容空白 |
| 3 | `ConfigEditShell` 的动作组整块是死代码：8 个调用方全部传 `actions="none"`，默认分支（重新载入/校验/保存）永不渲染 | `features/panels/ConfigEditShell.tsx:95-120` | 连带 `useConfigDraft.validate()` / `reload()` 在界面上不可达，只有 `confirmPending` 还走内部逻辑 |
| 4 | 批量同步配置静默失败 | `features/panels/DeviceOpsPanel.tsx:141-166` | 校验不通过或抛异常只 `continue`，最后仍报「已同步 N 台」；端口用 `syncPortBase + index` 硬算，可能与未参与同步的设备撞端口 |

### 中

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 5 | `activeSession` 离开设备页后不清空，明细轮询照跑 | `state/useBackend.ts:336-339` | 在工作台停留时仍每 2 秒拉「上一台设备」的 catalog + events，纯浪费 |
| 6 | 输入草稿缓存每次键入都全量读写 localStorage | `state/useInputDraftCache.ts:162-175` | 每次 `input` 事件 `JSON.parse` + `JSON.stringify` 整个 store（含 JSON 编辑器整段文本），同步阻塞主线程 |
| 7 | 两套配置草稿互不同步 | `features/panels/ConfigPanel.tsx:18-60`（自管 `draft`/`baseline`/`loadedMs`）vs `state/useConfigDraft.ts` + `configDraftStore` | 「全量配置」页的未保存改动不在共享仓库里：切到「结构配置」看不到它，反之亦然；两边各用自己的 `modifiedMs` 做 If-Match，后保存的一方会拿到 409，用户感知是「我明明刚改过」。If-Match、校验、脏状态逻辑也各写了一遍 |
| 8 | `chartMode` 偏好与 `uplot` 依赖残留 | `state/usePreferences.ts:62,93`、`app/main.tsx:6` | 偏好里的 `uplot` 选项没有任何效果；`uplot` 只被 import 了 CSS，白背一个依赖与样式 |
| 9 | `dirtyElsewhere` 计算但无人消费 | `state/useConfigDraft.ts:55,128,370` | 页签小圆点替代了那条提示后，这段按段比较的逻辑空转 |
| 10 | `as never` 下标掩盖了 page 与 zone 的耦合 | `app/App.tsx:545, 555-556` | 路由类型本身是「zone + page」联合，这里用断言绕过了类型检查，页名写错不会报错 |
| 11 | 新建区域用 `window.prompt` | `features/panels/DeviceOpsPanel.tsx:552` | 与全站自定义弹窗风格不一致，且无法做校验/取消语义 |
| 12 | `INTERVAL.sessionDetail` 注释与实现都已过期 | `state/useBackend.ts:44-45, 349` | 注释说「报警/终端消息/目录/事件」，实际只剩目录与事件；overview 复用了 `INTERVAL.audit` |

### 低

| # | 问题 | 位置 |
|---|---|---|
| 13 | 文件头 JSDoc 还写着 `#/device/{id}/{page}` 的老路由 | `app/App.tsx:82-89` |
| 14 | 全部接口类型塞在一个 718 行文件里 | `api/types.ts` |
| 15 | 单 chunk 564 KB，无路由级代码分割 | `vite` 构建输出 |
| 16 | 最复杂的三个 hook 没有单测（`useBackend` / `useConfigDraft` / `useEventStream`） | `state/` |
| 17 | `openHostSim` / `closeHostSim` / `sendHostSml` 仍挂在控制器上，UI 已无调用方（主机域已迁 v2） | `state/useDeviceOps.ts:97-146` |

---

## 六、改进建议

**批次一 · 正确性（先做，改动都很小）**

1. 修命令面板三处跳转：配置区改走 `device-zone:{id}:config`；SVID 改走 `device-page:{id}:config:svids`；CEID 要么接到运行区「事件执行」的筛选/触发输入，要么直接删掉这条命令与 `ceid` 全局状态。
2. `parseHash` 的旧深链兜底从 `['runtime','catalog']` 改成 `['runtime','plans']`，并给这个分支补一条单测。
3. 批量同步配置：把「校验不通过 / 异常」的原因收集起来逐台回报（不要只报成功数），端口分配改成「从起始端口开始找第一个空闲端口」，避免与未同步设备冲突。

**批次二 · 收敛死代码**

4. `ConfigEditShell` 的动作组要么删掉（承认「校验/重新载入」只属于全量配置页），要么给结构化页恢复一个「校验」入口——现在 `useConfigDraft.validate` 存在却没人能点。
5. `ConfigPanel` 并入 `useConfigDraft`（整份保存分支已经在那里实现）：删掉重复的 If-Match / 校验逻辑，同时让「全量配置」的改动对结构化页可见，反之亦然。
6. 删 `uplot` 依赖与 CSS import、`chartMode` 偏好、`dirtyElsewhere`、`openHostSim/closeHostSim/sendHostSml`。

**批次三 · 性能**

7. 明细轮询按页面需要：只有路由落在设备页（或运行区）时才拉 `catalog` + `events`，离开时清空 `activeSession`。
8. `useInputDraftCache` 改防抖（300 ms）+ 只存「与初始值不同」的字段，并把 JSON 编辑器排除在缓存之外（它已有草稿仓库）。
9. 路由级 `React.lazy` + 分包：主机侧面板、模板编辑器、图表三块单独切出去，首屏能砍掉一半。

**批次四 · 测试**

10. 给 `useConfigDraft` 补：按段保存只推进本页基线、409 守卫三种分支、结构段延迟生效；给 `useEventStream` 补：票据重取、暂停缓冲上限、重连后补拉。

---

## 七、结论

这套前端在「领域复杂度」上是清醒的：配置/运行两段式、按段保存、草稿仓库、SSE + 轮询的混合、错误文案落到「下一步」，都是踩过坑之后收敛出来的结构，不是随手堆的页面。代码量约 3.7 万行，页面职责基本一页一件事。

主要问题不在架构，而在**改版留下的尾巴**：命令面板与路由的迁移映射没跟上页面重构（三处跳转错位、一个失效的兜底页名），按段保存上线后旧的动作组与旧草稿实现变成了死代码，`uplot` 与 `chartMode` 是上一版图表的残骸。这些都不难修，但会持续误导用户与后续维护者——建议按批次一、二先清一轮，再谈分包与测试补齐。

---

## 八、实施记录（2026-11-23）

四个批次已全部落地，按批次逐条对照：

**批次一 · 正确性**

| 项 | 落地位置 | 说明 |
| --- | --- | --- |
| 命令面板三处跳转 | `app/App.tsx` | 「配置区」改走 `device-zone:{id}:config`；SVID 改走 `device-page:{id}:config:svids`；CEID 命令整条删除，`backend.ceid` / `setCeid` 一并移除（`state/useBackend.ts`、`DeviceOpsPanel.test.tsx` 同步收口） |
| 旧深链兜底 | `app/router.ts` | `LEGACY_DEVICE_PAGE[legacy] ?? ['runtime','plans']`；`goTo` 里的 `catalog` 别名表一并删除 |
| 批量同步回报 | `features/panels/DeviceOpsPanel.tsx` | 新增 `takePort()` 从 `syncPortBase` 起找第一个空闲端口（overview 端口 + 各设备配置端口都算占用）；失败逐台收集「名字 + 原因」，`done > 0` 才报成功，有失败时保留弹框 |
| 单测 | `app/router.test.ts` | 新增 `falls unknown 2.x sub-page names to the runtime home` |

**批次二 · 收敛死代码**

- `ConfigEditShell` 删除 `ShellActions` 类型、`actions` 参数与整块动作组渲染；8 个配置页的 `actions="none"` 传参一并移除。保存入口本来就在各页自己手里（行内动作确认后直接 `save()`），删掉的是没人能点的按钮。
- `ConfigPanel` 整文件改写为 `useConfigDraft` 的视图（不传 `sections` = 整份保存），删掉自管的 `draft/baseline/loadedMs`、If-Match、`onValidate`/`onSave`/`reloadConfig` 约 200 行。副作用正是报告希望的那条：**「全量配置」与结构化页现在共用同一份草稿和同一条基线**，不再互相拿到 409。
- `useConfigDraft` 补 `status`（`idle/loading/ready/missing`）与 `baselineText`，`saveWhole` 成功后补 `refreshConfigs()`。
- 删 `uplot` 依赖（`package.json` + lock + `main.tsx` 的 CSS import）、`chartMode` 偏好、`dirtyElsewhere`、`openHostSim`/`closeHostSim`/`sendHostSml`（连同 `api/hostsim.ts`、`api/types.ts`、`HostSimTarget` 一起清掉——会话维度的旧端点已无调用方）。

**批次三 · 性能**

- 明细轮询按页面需要：`useBackend` 新增 `options.sessionDetailHot`，设备页 2 秒、其余页面 20 秒。
  *与报告的差异*：没有在离开设备页时清空 `activeSession`，也没有完全停掉轮询。原因是命令面板的「查看 SVID」从 `backend.catalog` 取条目、`goTo` 的兜底分支与多处面板都读 `activeSession`——停掉或清空会造成「在工作台按 Ctrl+K 找不到 SVID」「SVID 命令跳回工作台」这类回归。降频拿到九成的收益，行为零变化。
- `useInputDraftCache` 改 300 ms 防抖写入（`pending` 攒着，`pagehide`/`visibilitychange`/换页时补一次），只存与初始值不同的字段（改回初始值就删条目），`JsonEditor` 的 textarea 加 `data-draft-skip` 排除在缓存外。
- 路由级 `React.lazy`：8 个页面各自成块，`<main>` 外包 `Suspense`。**首屏 562 kB → 264 kB（gzip 190 → 87 kB），减少 53%**；`PlanConfigPanel`(75 kB)、`DevicePage`(55 kB)、`HostDetailPage`(54 kB) 等都改成按需下载。

**批次四 · 测试**

- 新增 `src/test/renderHook.tsx`（30 行挂载器：`createRoot` + `React.act` + 三个推进器），配套新增 devDependency `jsdom`——项目原有测试全是 `renderToStaticMarkup`，碰不到 effect。
- `state/useConfigDraft.test.tsx`（13 项）：分段保存只推进本页基线、无改动不发请求、`ecv-overwrite` 确认后带 `confirmOverwrite` 重发、`cycle-running-guard` 两种文案、`revision-conflict`、结构段不热应用、结构+热段只发热段、整份保存后刷新目录、整份保存 409、`reload` 有脏先确认、无脏直接重读、读不到文件时 `missing`。
- `state/useEventStream.test.tsx`（12 项）：票据每次重连都重取、旧流真的关掉、首次 open 不补拉 / 重连后 open 才补拉、取票失败只提示一次、报文逐条 flush、坏报文只提示、暂停攒着恢复一次吐出、暂停缓冲上限 5000（丢最旧）、七类通知全注册、会话域带 `sessionId`、主机/配置类规整成空串、无 client 时 idle。

**门禁结果**

| 门禁 | 结果 |
| --- | --- |
| `npx tsc --noEmit` | 通过 |
| `scripts/check-ui-words.ps1` | 143 文件 / 7230 段文案通过 |
| `npm test` | 35 文件 / 258 项通过（原 33 / 233） |
| `npx playwright test` | 75 项通过（两处断言随文案统一而更新：整份保存的成功提示改看右下角 toast；重新载入的确认框改用草稿统一文案「放弃未保存的改动？」） |
| `mvn -B test` | 9 模块 BUILD SUCCESS，86 项通过 |

