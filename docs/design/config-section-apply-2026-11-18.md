# 配置「按页保存、按段生效」改造方案（2026-11-18）

> 目标：设备配置区每个页面单独保存、单独生效。连接页改配置 = 重启端口、运行状态全清；  
> 其余页面按依赖关系判定影响面，能不断链、不牵连其它运行态的，只让本页那一段生效。  
> 主机配置页同样按页保存、按页生效。

---

## 1. 现状（代码事实，不是推测）

### 1.1 前端

| 事项   | 现状                                                    | 位置                                                              |
| ---- | ----------------------------------------------------- | --------------------------------------------------------------- |
| 草稿粒度 | **每页一份**：每个配置页各自 `useConfigDraft`，挂载时读一次磁盘            | `state/useConfigDraft.ts`，`features/panels/ConfigEditShell.tsx` |
| 三个按钮 | 校验 / 保存 / 重新载入，作用对象都是**整份配置原文**                       | 同上                                                              |
| 跨页共享 | 不共享。切页 = 卸载 → 回来重新读盘，别页未保存改动看不到也带不走                   | `DevicePage` 只在当前子页渲染面板                                         |
| 脏标记  | `text !== baseline`，只代表当前页这份草稿                        | `useConfigDraft.dirty`                                          |
| 重新载入 | 结构化页**无二次确认**（配置原文页有）                                 | `ConfigEditShell` vs `ConfigPanel`                              |
| 输入回填 | `useInputDraftCache` 按路由回填控件值；值回到输入框但**不进草稿**，看起来"还在" | `state/useInputDraftCache.ts`                                   |

### 1.2 后端

| 事项        | 现状                                                                                                                                                             | 位置                                                            |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| 校验        | `POST /configs/{name}/validate`，整份文本 → `ConfigStore.validate` 整份编译；问题列表属于整份文件                                                                                  | `ConfigHandler.validateConfig`                                |
| 保存        | `PUT /configs/{name}`，整份文本 + `If-Match: modifiedMs` → `ConfigStore.save` 整份编译后原子写盘；`revision` 乐观锁 + 快照                                                         | `ConfigHandler.saveConfig`、`ConfigRepository.saveDocument`    |
| 保存后生效     | 前端保存成功即对所有使用该配置的会话调 `/sessions/{id}/reload`                                                                                                                    | `state/useConfigCommands.ts`、`useBackend.reloadSessionsUsing` |
| reload 语义 | 先清运行时编辑（钉值/计划启用态/目录编辑），再按新配置：`session` 段没变 → `applyHot` 全量原地替换（不断链）；变了 → 关旧开新（断链）                                                                              | `RuntimeApi.reloadSession`、`SessionLauncher.reload`           |
| 热生效范围     | 模型、编排表、限流、W 位、值来源、晶圆属性、ECV、cycle 配置一起换；**周期回 STOPPED、计划启用态清空（热重载即全停）**                                                                                         | `HotReloader`、`HsmsSessionRuntime.applyHot`                   |
| 模型替换副作用   | `SessionModelViews.refresh` → 重建 S6F11 组装器 + `tracePump.reset(model, values)`（进行中的 Trace 被重置）                                                                  | `SessionModelViews`、`TracePump`                               |
| 已有可复用件    | `ConfigRepository.saveDocument`（revision 锁 + 投影重算）、`updateConstants`（只换 constants 子树）、`EventScheduler.replaceSchedule`、`CycleRunner.reconfigure`、`HotReloader` | 见前述文件                                                         |
| 主机        | 配置页本来就分页保存：概览 `saveHostV2`（settingRevision+1，需「使设置生效」= `reconnect`）；Trace/采集方案库 `saveHostPlanV2`（下次下发/启动才用）                                                    | `HostV2Delegates`、`HostSimulator.apply()`                     |

### 1.3 结论

- 现在是「整份文件保存 + 整台设备重载」，**不是**当前页。
- 更糟的是草稿不共享：A 页改一半切到 B 页，A 的改动随卸载消失；B 页保存只写它自己那份磁盘基线 + B 的改动。
- 设备的 `session` 段已经决定「热生效 or 断链重建」（结构指纹）；我们要做的是把「整段全量热替换」拆成「按段最小替换」，并把保存粒度收到页面。

---

## 2. 设计口径

### 2.1 两个粒度分开定义

- **保存粒度** =<u> </u>页面拥有的配置片段（section）。一次保存只把这一片段写进文件。
- **生效粒度** = 该片段在运行态的最小影响集。能不动的运行态一律不动。

### 2.2 六条硬规则

1. **连接页（`session`）**：保存后必须重启端口 —— 断链、控制状态回 OFFLINE、计划启用态清空、周期停止归零、钉值/目录编辑清除、Trace 中断、计数与报警清空。这是唯一允许「全清」的页面。
2. **其余页面**：一律不断链；只重置语义上必须重置的那部分，并在保存前把「会重置什么」写在按钮旁。
3. **整份编译不放松**：写入前仍对整份配置做一次编译（计划→CEID、周期事件→CEID、报告→SVID 这些跨段引用只有整份编译才查得出）。
4. **失败不留半成品**：patch 与投影在同一事务；运行态应用失败时，文件已经落盘的部分要明确回报「哪个设备没生效」，不能假装成功。
5. **结构变更要求下线**（2026-11-18 确认；同日真机走查后收紧）：`structure`（增删单元、改 unitId、改名称）保存前必须确认设备**已停止**——判据是会话状态 `OPEN/OPENING`（端口开着），不是 GEM 的 `controlState == ONLINE`。理由：主机连上但没发 S1F17 时控制状态仍是 OFFLINE，那会儿放行保存会让后端整会话重建，对操作员等于静默重启设备。已启动 → 拒绝保存并提示「先停止设备再改结构」。未启动的设备不受限。
6. **被运行中周期引用的数据不许改**（2026-11-18 确认）：
   - 对象：`svids`（含值来源/曲线）与 `context`（晶圆属性）。
   - 条件：该 SVID / `ctx.<ID>` 被某个 CEID 的报告引用，且这个 CEID 出现在**当前正在运行的周期**的 `cycle.events[].ceid` 里。
   - 结果：拒绝保存（409 `cycle-running-guard`），提示「变量 X 被周期事件 CEID 2001 引用，先停止周期再改」。
   - 未被运行中周期引用的 SVID / 属性仍可热改；其它页签不受影响。

### 2.3 生效级别（界面要显示的文字）

| 级别          | 含义             | 断链 | 复位范围                    |
| ----------- | -------------- | -- | ----------------------- |
| `reconnect` | 重启端口           | 是  | 全部运行态                   |
| `hot`       | 立即热生效，运行态保留    | 否  | 无                       |
| `hot-reset` | 立即热生效，但相关运行态重置 | 否  | 该段依赖的运行时（Trace/钉值/目录编辑） |
| `deferred`  | 只存库，下次下发/启动才用  | 否  | 无（主机方案库）                |

---

## 3. 依赖分析（核心矩阵）

配置段来自真实配置（`session` / `equipment{name,idSpace,constants,units[]}` / `context` / `cycle` / `events{maxPerSec,s6f11Wbit,s6f1Wbit,plans}` / `template` / `templates`）。

运行时消费方缩写：  
**L**=链路(HSMS) · **M**=设备结构(units/号段；全停) · **CAT**=变量/报告/事件目录(换视图+编排，运行不断) · **V**=值来源+钉值层 · **C**=ECV 常量 · **X**=晶圆属性表 · **S**=计划编排器 · **CY**=周期运行器 · **T**=Trace · **R**=运行时目录编辑 · **N**=计数器/报警/终端消息

| 配置片段 | 消费方 | 改动的直接后果 | 可保留 | 必须重置 | 级别 / 前置条件 |
|---|---|---|---|---|---|
| `session.*`（id/port/bindHost/deviceId/passive/seed） | L | 监听端点或 Device ID 变化 | 台账历史 | 全部：S 启用态、CY、T、V 钉值、R、C/X 运行时写值、N | `reconnect` |
| `equipment.name` / `idSpace` / `units[]`（增删单元、unitId、名称） | M(+S 的 CEID 归属、T、R) | 模型结构变，报告/事件按名引用可能失效 | L、S 的启用态（CEID 未删的计划）、CY 的片号（若周期事件仍有效）、N | T（DVID 集合可能失效）、R（目录编辑基于旧模型）、引用失效的计划置禁用 | `hot-reset`；**前置：设备已停止（端口未开）** |
| `equipment.units[].svids`（含 `source`/`curve`、`template` 引用） | CAT,V | 变量集合与值来源重建；报告按名引用 | L、S 的启用态、CY 的运行、N | V（值来源重建；同 id 钉值迁移，删掉的 id 丢弃） | `hot`（换视图+保留态换表，周期/计划照跑）；**被运行中周期引用的 SVID 禁改**；删掉被引用的对象由整份编译 422 拦下 |
| `equipment.units[].reports` | CAT,S6F11 组装 | 报告条目变化，S6F11 内容随之变 | L、S 的启用态、CY、V、T、C、X、N | R（报告目录编辑，按新目录重校验） | `hot` |
| `equipment.units[].ceids` | CAT,S6F11,计划解析 | CEID 删除会让引用它的计划/周期事件失效 | 同上 | 引用被删 CEID 的计划置禁用并提示；周期事件失效由整份编译 422 拦下；R | `hot` |
| `events.plans` | S | 编排表替换 | L、M、V、CY、C、X、T、N | 仅新增/改动的计划回「禁用」；未改动的计划保留启用态与锚点；被删计划的挂起触发丢弃 | `hot` |
| `events.maxPerSec` / `s6f11Wbit` / `s6f1Wbit` | S | 限流与 W 位立即变 | 全部 | 无（不触发全停） | `hot` |
| `cycle.*` | CY | 周期配置替换 | L、M、V（值曲线）、S、T、C、X、N | CY 回 STOPPED、片号归零；**必须手动重新启动**（已确认） | `hot`（含周期停止） |
| `context[]`（晶圆属性） | X | 属性表替换 | 全部（含正在跑的周期） | 无；正在跑的周期下一片用新规则 | `hot`；**被运行中周期引用的属性禁改** |
| `equipment.constants`（ECV） | C | 常量值替换 | 全部 | 无；**保存即覆盖 S2F15 写入的值**（已确认） | `hot` |
| 根 `template` / `templates`（参数模板引用） | V | 值来源编译结果变 | L、S 的启用态、CY、N | V（重建值来源，钉值按 id 迁移） | `hot`；同 svids 的守卫 |
| 整份（`document` 页 / 导入 JSON） | 全部 | 等价于所有段都变 | — | 按「变化段集合」自动取并集；含 `session` 则 `reconnect`；含 `structure` 则要求下线 | 组合 |

**备注（需要在实现时写死并写进提示）**

- `cycle` 改配置一定停周期：值曲线的相位与片内事件偏移都按新配置解释，继续跑会让新旧相位混在一起。
- `structure`/`cycle` 停周期：结构全停重建；周期配置换了回 STOPPED。
- `svids`/`templates` 只重建值来源并迁移钉值，生命周期时钟换新但**周期与计划继续跑**（2026-11-18 真机验守卫 B 后收紧）。
- `context` 在周期运行中保存：按新规则从**当前片号**继续推导，不重置片号（LOT 起始号仍从配置的 start 起算）。
- `constants` 与 S2F15：主机写过的 ECV 已回写配置文件（`ConfigStore.writeConstants`），配置页保存会覆盖它；界面保存前必须显示「当前运行时值 vs 配置值」。

### 3.1 保存前守卫（后端权威，前端预检）

保存接口在整份编译之后、写盘之前跑三道守卫；任何一道不通过 → 409/422，文件不动。

**守卫 A：结构变更必须下线**

- 触发段：`structure`（含整份保存里 structure 有变化）。
- 判据：该配置对应的会话**端口开着**（`state == OPEN || OPENING`）→ 拒绝 `409 not-offline`。
- 提示：「设备已启动（<id>）：改单元结构前先停止设备」。
- 未启动（无会话）或已停止（`state == CLOSED`）的设备直接通过。
- 为什么不用 `controlState == ONLINE`：模拟器主机连上后默认仍是 OFF-LINE（要手动 S1F17 上线），那个判据在真机上几乎不会命中，等于没拦。

**守卫 B：运行中周期的 CEID 引用**（`svids` / `context` / `template`）

引用链（都是配置里现成的结构，不需要额外索引）：

```
svid 名 / ctx.<ID>  →  报告 reports[].svids[]  →  CEID ceids[].reports[]  →  cycle.events[].ceid
```

算法：

1. 用**旧配置**（磁盘当前版本）算出「被保护集合」：
   `{ svid 名 → Set<CEID> }`、`{ ctx 名 → Set<CEID> }`，只保留 `CEID ∈ cycle.events[].ceid` 的项。
2. 对比新旧切片，得到**被改动的项**（svid 按 id + 内容、ctx 按 id + 内容；新增项不算改动，删除/改值算）。
   `template` 段变更时，先展开成「引用了该模板的全部 SVID」，再按同一规则判定（模板改一个参数，可能连带十几个 SVID 的值来源）。
3. 对每个运行中且 `cycle.state == RUNNING` 的会话：改动项若命中该设备的被保护集合 → 拒绝，返回：

```json
{
  "ok": false,
  "error": "cycle-running-guard",
  "detail": [
    { "kind": "svid", "name": "TEMP_PV", "ceids": [2001, 2002] },
    { "kind": "context", "name": "LOTID", "ceids": [2001] }
  ]
}
```

前端提示：「TEMP_PV 被周期事件 CEID 2001/2002 引用，先停止周期再改；未被引用的变量可以先保存」。

**守卫 C：常量覆盖确认**

- 触发段：`constants`，且该设备运行时有 S2F15 写入值（`ConstantStore` 当前值与配置值不同）。
- 不拒绝，返回 `warnings: [{kind:"ecv-overwrite", ecid, runtime, config}]`；前端保存前弹一次确认（「将把运行时值 5 覆盖为配置值 3」）。

> 守卫只看**正在运行**的周期（`RUNNING`）。周期已停止 / 未启动 / 设备离线时，守卫 B 不生效。

---

## 4. 目标架构

```
┌────────────── 前端 ──────────────┐        ┌──────────────── 后端 ────────────────┐
│ configDraftStore（按配置名一份）  │        │ PUT /configs/{n}/sections/{section}  │
│  ├ text / baseline / revision     │  ───▶  │  ├ 锁内：读正文 → 替换该段            │
│  ├ dirtySections: Set<section>    │        │  ├ 整份编译（跨段引用）               │
│  └ 各页只读写自己的 section        │        │  ├ saveDocument（revision + 快照）    │
│                                    │        │  └ 返回 {revision, changed, effect}   │
│ ConfigEditShell(section)           │  ───▶  │ POST /v2/devices/{id}/apply-sections  │
│  ├ 校验本页（问题按段过滤）        │        │  └ 按段最小应用 → {reset, preserved}  │
│  ├ 保存本页（影响预览）            │        └──────────────────────────────────────┘
│  └ 其它页脏点提示                  │
└────────────────────────────────────┘
```

### 4.1 片段枚举（不用自由 path，防注入）

后端定义白名单枚举，前端与后端共用同一张表：

| section     | 对应配置节点                                                                                    | 提交体形状                                                 |
| ----------- | ----------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `session`   | `$.session`                                                                               | `{...}` 完整 session 对象                                 |
| `structure` | `$.equipment.name`、`$.equipment.idSpace`、`$.equipment.units[].{unitId,name,injectUnitId}` | `{name, idSpace, units:[{unitId,name,injectUnitId}]}` |
| `svids`     | `$.equipment.units[].svids`                                                               | `{units:[{unitId, svids:[...]}]}`                     |
| `reports`   | `$.equipment.units[].reports`                                                             | `{units:[{unitId, reports:[...]}]}`                   |
| `ceids`     | `$.equipment.units[].ceids`                                                               | `{units:[{unitId, ceids:[...]}]}`                     |
| `constants` | `$.equipment.constants`                                                                   | `[...]`                                               |
| `context`   | `$.context`                                                                               | `[...]`                                               |
| `cycle`     | `$.cycle`                                                                                 | `{...}` 或 `null`（删除周期段）                               |
| `plans`     | `$.events.plans`                                                                          | `[...]`                                               |
| `throttle`  | `$.events.maxPerSec`、`s6f11Wbit`、`s6f1Wbit`                                               | `{maxPerSec, s6f11Wbit, s6f1Wbit}`                    |
| `document`  | 整份                                                                                        | 纯文本（沿用现有 `PUT /configs/{name}`）                       |

页面归属：

| 页面    | section(s)                                                           |
| ----- | -------------------------------------------------------------------- |
| 概览    | `structure`（名称）+ `throttle`？（不，概览只留名称 → `structure` 的 name 子集，见 6.3） |
| 连接    | `session`                                                            |
| 结构    | `structure`                                                          |
| 变量配置  | `svids`                                                              |
| 晶圆属性  | `context`                                                            |
| 事件与报告 | `ceids` + `reports`（一页两段，一次保存两段）                                     |
| 工艺周期  | `cycle`                                                              |
| 事件计划  | `plans` + `throttle`（一页两段）                                           |
| 设备常量  | `constants`                                                          |
| 配置原文  | `document`                                                           |

> 一页多段时，保存接口接受 `sections[]`，后端逐段替换、合并成一次编译与一次写盘。

### 4.2 前端：单一草稿 store

新增 `state/configDraftStore.tsx`（Context + reducer），按 `configName` 存：

```ts
interface ConfigDraft {
  text: string;        // 当前草稿（含所有未保存改动）
  baseline: string;    // 磁盘基线
  revision: number;    // 保存用的乐观锁（后端返回的新 revision）
  modifiedMs: number;  // 兼容现有 If-Match
  loading: boolean;
  problems: ConfigProblem[]; // 带 path
  notice: string;
}
```

- 打开配置 → 读一次盘；同一配置的所有页面共享这一份，**切页不再丢草稿**。
- 每页通过 `useSectionDraft(configName, sections)` 拿到：本页 JSON 切片、`dirtyHere`、`dirtyElsewhere`、`problemsHere`、`save()`、`reload()`、`validate()`。
- 保存成功后：把本页 sections 的新值合并进 `baseline`，其余段仍保持 dirty。
- 收到 SSE `config-changed`（他人保存）：本页无改动 → 自动换基线；有改动 → 提示「磁盘已更新，本页草稿保留」。

### 4.3 后端：分段落盘 + 分段应用

**保存（文件）**

```
PUT /api/v1/configs/{name}/sections
If-Match: <modifiedMs>
Content-Type: application/json

{ "sections": { "cycle": { ... }, "throttle": { ... } } }
```

> 段名走白名单枚举（`session/structure/svids/reports/ceids/constants/context/cycle/plans/throttle`），
> 一页多段时一次提交、一次编译、一次写盘；`cycle: null` 表示删除该段。

处理步骤（全在一把配置锁内）：

1. 读当前正文 + revision，校验 `If-Match`（不匹配 → 409，带当前 revision）。
2. 逐段替换（`SectionPatcher`）：保留其余节点与键顺序；`cycle: null` 表示删除该段。
3. 整份编译（`ConfigStore.validate`），失败 → 422，问题带 path（`$.cycle.events[1].ceid 不存在`）。
4. `saveDocument(body, expectedRevision, actor="config-section", note="section:cycle")` → 新 revision + 快照。
5. 比较新旧配置的段哈希，返回实际变化的段（防止前端谎报）。

响应：

```json
{
  "ok": true,
  "revision": 43,
  "modifiedMs": 1794981445238,
  "changed": ["cycle"],
  "effect": "hot",
  "willReset": ["cycle-run"]
}
```

**生效（运行态）**

```
POST /api/v2/devices/{id}/apply-sections
{ "config": "ETCH01.json", "changed": ["cycle"], "revision": 43 }
```

- 服务端仍然自己算一遍 changed（不信任 body），只对 `config` 匹配的运行中会话应用。
- 若 `changed` 含 `session` → 走现有 `reload`（断链重建），返回 `effect: "reconnect"`。
- 否则走新的 `SectionApplier.apply(session, prepared, changed)`，返回：

```json
{ "effect": "hot", "reset": ["cycle-run"], "preserved": ["plans","pins","counters"] }
```

**校验（可选，单独按钮）**

```
POST /api/v1/configs/{name}/validate   // 沿用；body 传「基线 + 本页切片」的整份候选
```

后端返回 `problems[{path, message}]`；前端只把 `path` 属于本页 section 的列出来，另起一行「其它页还有 N 条问题」。

### 4.4 后端：`SectionApplier` 的最小应用动作

把现在的 `applyHot` 拆成可组合的五个动作，按 changed 集合调用：

| 动作                                                     | 做什么                                                                    | 保留什么                      |
| ------------------------------------------------------ | ---------------------------------------------------------------------- | ------------------------- |
| `applyModel(model)`                                    | 换 `this.model`、重建 S6F11 组装器、`tracePump.reset`、刷新 `knownCeid` 谓词、刷新目录基座 | 链路、计划启用态、周期（除非调用方要求停）、计数器 |
| `applyValues(values, keepPins)`                        | 重建值来源；同 id 钉值重新包进 `PinnedValueSource`；删掉的 id 丢弃并记一条提示                  | 钉值（同 id）、计划、计数器           |
| `applySchedulePreservingEnable(schedule, gate, wbits)` | `replaceSchedule` + 新增/改动计划加入 `disabledPlans`；未改动计划沿用启用态与锚点；被删计划清挂起    | 启用态（未改动）、周期、钉值            |
| `applyCycleConfig(cycle, clock)`                       | `CycleRunner.reconfigure` → STOPPED；**不换生命周期时钟**（值来源没变时）               | 计划、钉值、计数器                 |
| `applyContextConstants(context, ecidValues)`           | 换 `ContextTable` / `EcidValues`，重建组装器与 hostSide 取值器                    | 全部运行态                     |

配套改造：

- `HsmsSessionRuntime.applySections(Prepared, changed)`：新入口，内部按上表分发；现有 `applyHot` 保留给 `reload` 全量路径。

> **2026-11-18 真机验守卫 B 后二次收敛**：`applySections` 里的「模型变更 = 全停」收窄为只有 `structure`。
> 新增纯函数 `HsmsSessionRuntime.SectionEffects.of(changed)`（6 条单测）：`structure` → 全停 + 换视图；
> `svids/reports/ceids` → 换视图 + 重建值来源（钉值迁移）+ 保留态换表，**周期与计划继续跑**。
> `SectionApplyOutcome` 增加 `catalogChanged`；`resetJson/preservedJson` 只在结构变更时记 `plans` 被重置，
> 目录变更记「计划已保留」。真机复验：周期 RUNNING + 一条计划运行中时改未被引用的 SVID 单位 →
> 提示「事件计划已更新：1 条未改动的计划保持运行」，周期仍 RUNNING、计划仍运行中；同状态下改
> 被 CEID 2001/2002 引用的 TEMP_PV → 409「变量 TEMP_PV 正被周期事件 CEID 2001/2002 引用」。
- `EventScheduler`：新增 `replaceSchedulePreservingEnable(next, changedPlanIds)`；`knownCeid` 每次模型替换后重设。
- `CycleRunner`：新增 `reconfigureKeepingClock(cycle)`；`firedCount/waferIndex` 归零并由接口回报。
- `TraceManager/TracePump`：`reset` 只在 `structure` 变更时调用；目录变更（svids/reports/ceids）换视图但不动 Trace；其它段不动。若只是删了某个 SVID，可细化成「只中止引用该 SVID 的 TRID」。
- `RuntimeEdits`（运行时目录编辑）：仅 `structure` 变更时清空；目录变更保留编辑（按新目录重校验）；返回清空条数用于提示。
- 审计：`config-save-section`（name/section/revision/effect）+ `device-apply-section`（device/changed/reset）。


## 5. 接口一览（新增 / 变更）

| 方法 | 路径 | 说明 | 状态 |
|---|---|---|---|
| PUT | `/api/v1/configs/{name}/sections` | 分段保存（body 带 sections 映射） | 已实现 |
| POST | `/api/v2/devices/{id}/apply-sections` | 分段生效 | 已实现 |
| PUT | `/api/v1/configs/{name}` | 整份保存（配置原文页/导入） | 保留 |
| POST | `/api/v1/configs/{name}/validate` | 整份校验；问题补 `path` | 改 |
| GET | `/api/v1/configs/{name}/sections/meta` | 页面→段→生效级别/前置条件（前端不必硬编码） | 新增（可选） |
| GET | `/api/v2/hosts/{id}` | 补 `settingsChanged`（哪些字段待生效） | 改 |
| PUT | `/api/v2/hosts/{id}` | 返回 `{changed, needsReconnect}` | 改 |
| POST | `/api/v2/hosts/{id}/plans/{kind}/{planId}/apply` | 把方案立即重新下发/启动（可选） | 新增 |

错误码：

| 状态 | error | 场景 | 前端动作 |
|---|---|---|---|
| 409 | `revision-conflict` | `If-Match` 与当前 revision 不符 | 提示重新载入 |
| 409 | `not-offline` | 守卫 A | 提示先下线 |
| 409 | `cycle-running-guard` | 守卫 B，`detail` 列出变量与 CEID | 高亮被拒的项，提供「去停止周期」 |
| 422 | `config-invalid` | 整份编译失败，`problems[]` 带 `path` | 只展开本页问题 |
| 200 | — | 成功，带 `warnings[]` / `changed[]` / `effect` | 按 effect 提示 |

---

## 6. 前端改动清单

### 6.1 状态层

- `state/configDraftStore.tsx`（新）：按 configName 缓存草稿 + revision；提供 `read/patch/reload/saveSections/validate`。
- `state/useConfigDraft.ts`（改）：变成 store 的薄适配；签名加 `sections`；`dirty` 拆成 `dirtyHere/dirtyElsewhere`；`save()` 改调分段接口，成功后按返回的 `changed` 合并基线。
- `app/configSections.ts`（新）：页面 → sections、每段的 `label` / `effect` / `willReset` 文案（与后端 meta 对齐）。

### 6.2 外壳与页面

- `features/panels/ConfigEditShell.tsx`（改）：
  - 新增 `sections` 属性；
  - 工具条：`重新载入` / `校验本页` / `保存本页`；
  - 保存按钮旁常驻一行：「本页保存只写入 工艺周期；保存后周期会停止，计划与钉值保留」；
  - 其它页有未保存改动时显示 `其它页有 2 处未保存`（点击跳过去），避免误判已全部保存；
  - `重新载入` 有改动时二次确认（复用 `ConfigPanel` 的确认弹窗组件）；
  - 保存 409 → 提示重新载入；422 → 只高亮本页问题，其它页问题折叠。
- 各页面（`CyclePanel`、`PlanConfigPanel`、`SvidConfigPanel`、`ContextConfigPanel`、`ConstantsConfigPanel`、`CeidReportConfigPanel`、`ConnectionPanel`、`StructurePanel`）传 `sections`；`ConnectionPanel`/`StructurePanel` 目前各自实现工具条，需要并到同一个 `ConfigEditShell` 或抽 `ConfigEditActions`。
- `DevicePage` 子页签：给有未保存改动的页加小圆点（来自 store，不再依赖面板是否挂载）。
- `ConfigPanel`（配置原文）：保留整份保存，但保存前提示「整份保存会影响全部运行状态」。

### 6.3 概览页

概览只改设备名（`equipment.name`）。名称不影响链路，按 `structure.name` 单字段保存：

- 后端 `structure` 段 patch 时只替换 `name`，不动 units；
- 生效 = `hot`（模型里名字换掉，链路不断）。

---

## 7. 主机侧改造

### 7.1 现状与问题

- 概览：`saveHostV2` 把名称与目标一起存，`settingRevision+1`，必须手动「使设置生效」（= `reconnect()` 断开重连）。
- Trace 设置 / 采集设置：方案库直接写库，运行时用方案时才下发；保存本身无副作用，但界面没说清「正在跑的 TRID 不会变」。
- 报文模板：前端静态库，无保存动作。

### 7.2 目标

| 主机页面               | 保存                           | 生效              | 界面文案                                  |
| ------------------ | ---------------------------- | --------------- | ------------------------------------- |
| 概览·名称              | `PUT /v2/hosts/{id}`（只 name） | 立即（`hot`）       | 「名称已更新，不影响连接」                         |
| 概览·目标/端口/Device ID | 同上，返回 `needsReconnect: true` | **保持两步**：保存 → 手动「使设置生效」 | 「已保存，尚未生效；点『使设置生效』才断开重连（Trace/采集会停，计数保留）」 |
| Trace 设置           | `saveHostPlanV2`             | `deferred`：下次下发 | 「已保存；正在运行的 TRID 不受影响，需重新下发」+ 一键「立即下发」 |
| 采集设置               | `saveHostPlanV2`             | `deferred`：下次启动 | 「已保存；当前采集器不受影响，需重新启动」+ 一键「立即下发并启动」    |
| 报文模板               | 无保存                          | —               | 只读库                                   |

后端改动：

- `saveHostV2` 比较旧值，返回 `changed[]` 与 `needsReconnect`；`name` 单独变时不要求重连。
- 保存后 `settingRevision` +1、`appliedRevision` 不动；界面用两者之差显示「设置待生效」标记。
- `hostApplyV2` 支持 `scope=link|settings`（`link` 只重连，`settings` 重连并按最新设置恢复采集）。
- 可选：`POST /v2/hosts/{id}/plans/{kind}/{planId}/apply` 让「保存后立即下发」一次完成。

> 落地情况（2026-11-18 P3）：
> - `HostSimulator.rename(name)` 不涨 `settingRevision`；`saveSettings` 仍只负责目标。
> - `HostV2Delegates.saveSettingsV2` 先比对再落库，返回 `{changed, needsReconnect, settingRevision, appliedRevision}`；
>   `PUT /api/v2/hosts/{id}` 直接回这段 JSON（`needsReconnect = 目标变了且主机在线`）。
> - 概览页拆成「名称（保存名称，立即生效）」与「连接目标（保存目标 + 使设置生效）」两段；
>   `settingRevision > appliedRevision` 时显示绿色「待生效」横幅与一键生效按钮（离线时说明"下次连接生效"）。
> - Trace 设置 / 采集设置页加了「这里保存的是方案库，保存不会下发」的说明与「去下发 →」跳转；
>   「保存后一键下发」按计划仍留作后续可选项（运行区本来就有批量启停，先不重复造入口）。

---

## 8. 分阶段实施

### P0 草稿统一（前端，无接口变更）

1. `configDraftStore` + `useSectionDraft`，切页不丢草稿。
2. 工具条三级脏标记 + 「其它页有未保存」提示 + 重新载入确认。
3. 按钮文案加作用域（「保存本页（写整份文件）」），先把误解消掉。

验收：A 页改 → 切 B 页 → 回 A 页草稿仍在；B 页显示「其它页 1 处未保存」。

### P1 分段保存（前后端）

1. 后端 `SectionPatcher` + `ConfigStore.saveSections` + `PUT /sections`；`problems` 补 path。
2. 前端保存改调分段接口；成功后只推进本页基线。
3. 单测：每段 patch 后其余字节不变（除 JSON 格式化差异）；跨段引用错误能定位到段。

验收：在「工艺周期」保存不会带走「事件计划」未保存的改动；反之亦然。

> 进度（2026-11-18）：后端已实现——`ConfigSection` / `ConfigSectionPatcher` /
> `ConfigSectionGuards` / `ConfigStore.saveSections` / `RuntimeApi.saveSections` /
> `PUT /api/v1/configs/{name}/sections`，单测 `ConfigSectionPatcherTest` 9 条通过；
> 前端已接（2026-11-18 P2）：
> - 新增 `shared/lib/configSections.ts`：页面 → 段 → 提交片段的切片（形状与 `ConfigSectionPatcher` 对齐）。
> - `useConfigDraft` 按 `sections` 走分段保存，成功后调 `apply-sections`；把「周期已停止 / 保留了几条计划 / 钉值失效」写回页面提示。
> - `ConfigEditShell` 增加 `sections`（连接/结构/变量/常量/属性/事件与报告/周期/计划八页各自声明），并统一渲染确认框（常量覆盖、丢弃改动）。
> - 结构页按「设备已启动」（`state == OPEN/OPENING`）禁用保存并显示「设备已启动：结构改动需先停止设备」。
> - 连接页常驻提示「保存后会按新参数重启会话：端口重新监听、运行状态全清」；设备启动着点保存先弹确认（`save-restart`），取消即不落盘。
> - 「配置原文」页仍是整份 `PUT`（那是唯一一份"我就是要改全文"的入口）。

> P0（共享草稿）也已落地（2026-11-18）：
> - `state/configDraftStore.ts`：按**配置名**只留一份草稿（正文 + 每段规范基线与当前值 + modifiedMs），
>   订阅走 `useSyncExternalStore`；切页不再读盘，未保存改动不会因为切走一次消失。
> - 基线**按段**存：分段保存只推进本页那几段的基线，别页未保存的改动仍如实为脏。
> - 脏状态两级：页面显示「本页有未保存改动 / 其它页 N 段未保存」，配置区子页签给脏页点小圆点
>   （`PAGE_SECTIONS` 一份映射同时供面板与页签使用）。
> - 「重新载入」在任一段脏时弹确认（覆盖整份草稿）。
> - 单测：`configDraftStore.test` 10 条（不重复读盘、只推进本页基线、改回原值不脏、强制重载、订阅通知等）。

### P2 分段生效（后端为主）

1. 拆 `applyHot` → 五个动作；`applySections` 入口。
2. `EventScheduler.replaceSchedulePreservingEnable`；`CycleRunner.reconfigureKeepingClock`。
3. 模型段变更时清 `RuntimeEdits`、重置 Trace；值段变更时迁移钉值。
4. 守卫实现：A（structure 下线）、B（运行中周期引用的 svid/ctx 禁改）、C（ECV 覆盖确认）。
5. 返回 `{effect, reset, preserved, warnings}`，前端按结果显示 toast / 二次确认。
6. 连接段仍走 `reload`（断链 + 全清），并在 UI 明示。

验收（逐段实测）：改 `cycle` → 只停周期，计划启用态与钉值保留；改 `plans` → 只重置改动的计划，周期继续跑；改 `context`/`constants` → 曲线/计划/周期全不受影响；改 `svids` → 不断链，视图换、钉值按 id 迁移、周期与计划照跑；改 `session` → 断链重连、全清。

### P3 主机侧与协作

1. 主机概览拆分名称/目标；`needsReconnect` 只标「设置待生效」，保持「保存 + 手动使设置生效」两步。
2. Trace/采集方案保存后的「立即下发」入口。
3. 消费 SSE `config-changed`：多窗口/多页签下自动换基线或提示合并。
4. 审计与 revision 备注带 section。

---

## 9. 测试计划

| 层    | 用例                                                                                   |
| ---- | ------------------------------------------------------------------------------------ |
| 后端单测 | 每段 patch 的幂等性；`cycle:null` 删除；跨段编译失败定位；revision 冲突 409；`applySections` 对每段的 reset 集合 |
| 后端集成 | 会话在线 → 分段保存 → 断言计划启用态/钉值/句柄状态；连接段保存 → 断言端口重启与状态清空                                    |
| 前端单测 | store 的基线推进；`dirtyHere/dirtyElsewhere`；两页并发编辑同一配置；409/422 分支                         |
| E2E  | 双页签：A 改周期不保存 → B 改计划保存 → 回 A 草稿仍在且可保存；保存后运行区按预期重置；连接页保存后设备断链重连                       |

---

## 10. 风险与取舍

1. **JSON 格式化**：段替换若用 `JsonHub` 重建，整份会被重新 pretty-print，diff 变大。方案：优先实现「文本切片替换」（定位段的首尾字节，只换这一段），失败再退回重建；无论哪种，保存前都比对 sha 并在审计里记录。
2. **周期停不停**（2026-11-18 二次收敛）：只有 `cycle` 与 `structure` 停周期；`svids/reports/ceids` 的未引用项按口径「可热改」继续跑，整份编译负责拦下会打断引用的改动。若后续要无缝换相位，只改周期配置这一条路径即可。
3. **钉值迁移**：值来源重建时同 id 钉值保留，删掉的 id 丢弃；这一条必须回报给用户，否则会以为钉值自己丢了。
4. **S2F15 与常量页**：两边都会写 constants，已确认「配置保存覆盖运行时值」；保存前必须显示差异（守卫 C 的确认弹窗）。
5. **多设备共用一份配置**：保存会对所有使用该配置的会话应用同一段，返回逐设备结果；某台失败不影响其它台。
6. **revision 与 modifiedMs 双轨**：现有 If-Match 用 modifiedMs，仓储用 revision。新接口统一用 revision，旧接口继续支持 modifiedMs，内部换算。

---

## 11. 已确认口径（2026-11-18）

| # | 问题 | 结论 | 落点 |
|---|---|---|---|
| 1 | `cycle` 改配置后怎么跑 | **周期停止、需手动重新启动**；计划/钉值/计数保留 | `CycleRunner.reconfigureKeepingClock` → STOPPED；保存后 toast「周期已停止，请到运行区重新启动」 |
| 2 | ECV 与 S2F15 谁优先 | **配置保存覆盖主机写入值**；保存前显示「运行时值 → 配置值」确认 | `applyContextConstants` 直接换值；守卫 C |
| 3 | `context` / `svids` 在周期运行中保存 | **被运行中周期 CEID 引用的项禁止修改**；其余项可热改 | 守卫 B（`cycle-running-guard`）；前端置灰 + 说明原因 |
| 4 | `structure` 增删单元 | **设备必须先停止**（会话 `state` 不是 `OPEN/OPENING`）；未启动不受限 | 守卫 A（`not-offline`） |
| 5 | 主机概览改目标 | **保持两步**：保存 → 手动「使设置生效」 | `saveHostV2` 只标 `needsReconnect`；`hostApplyV2` 做重连 |

### 实现顺序上的影响

- 守卫 B 让「变量配置 / 晶圆属性」两页在周期运行时变成**部分只读**：前端用同一份 root JSON 本地算出引用链（`reports[].svids` → `ceids[].reports` → `cycle.events[].ceid`），逐项置灰并给 tooltip；后端仍是权威。
- 守卫 A 在保存按钮上先查 `backend.sessions[].state`（`OPEN/OPENING`），启动中直接禁用并提示「先停止设备」，避免白跑一次 409。
- 守卫 C 需要保存响应带 `warnings[]`；前端二次确认后带 `?confirmOverwrite=1` 再提交一次。
