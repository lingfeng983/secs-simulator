# 重设计方案:设备工作台 + 事件驱动 SVID + 单设备全景页

> 2026-10-25 · 回应七点需求:①SVID 变化趋势随 S6F11 事件变化 ②总工作台监控所有设备(过滤/事件流/报文/统计) ③点击设备进入该设备的配置+监控+工具页 ④单设备配置分段编辑 + 每 SVID 独立模板/曲线 + 当前时间轴曲线预览 ⑤主机模拟:收到设定 CEID 的 S6F11 启动/停止 S1F3 数据采集,可配置采集 SVID,采集结果图像化 ⑥项目原生内置两个默认设备配置 ⑦主机模拟默认连本机 SECS,可选连其他 SECS——远程时无 SVID 目录,SVID/S2F23 等配置全部手填

---

## 0. 现状与差距

| 需求 | 现状 | 差距 |
|---|---|---|
| ① SVID 随事件变化 | SVID 值 = f(seed, t) 时间纯函数(`ValueStore`/`GraphValueSource`),与 S6F11 无因果;唯一联动是反向的 OnCondition(值→事件) | 缺"事件→值"的正向联动 |
| ② 总工作台 | `OverviewPanel` 是全局 KPI + 7 条报文 + 9 条事件的聚合视图,会话表只有 4 列 | 设备卡片墙、按设备过滤的事件流/报文流、统计图 |
| ③ 单设备页 | 12 个平铺面板靠全局会话选择器关联;监控(Preview/Events/Alarms)、配置(Config)、工具(Send/HostSim)分散 | 需要以设备为主体的聚合页 |
| ④ 分段配置 + 每 SVID 模板 | 表单覆盖 session/equipment(units)/events,templates 走三层模型(curves→composites→instances,实例绑定 SVID);曲线预览是 ConfigPanel 独立分段 | 每 SVID 直配曲线/模板 + 单 SVID 预览(当前时间轴 + 事件标记) |

**一条贯穿性约束**:本模拟器的根基是"值的确定性"(时间纯函数 + seed 派生噪声),草稿预览、重放分桶都靠它。事件驱动值变化的设计必须保持可确定性模拟,否则草稿预览功能报废。

---

## 1. 核心概念重设计

### 1.1 事件效果 Plan Effects(需求 ①)

`events.plans[]` 增加可选 `effects` 数组——**以事件为中心声明"这个事件发生时,哪些 SVID 怎么变"**:

```jsonc
{
  "id": "p-wafer-start",
  "ceid": 101,
  "trigger": { "type": "periodic", "everyMs": 60000 },
  "effects": [
    { "svid": 401, "op": "pulse", "value": 85, "holdMs": 5000 },    // 瞬时跳变,保持后回落
    { "svid": 402, "op": "shift", "delta": 12 },                     // 水平抬升,持续生效
    { "svid": 403, "op": "set",   "value": 3, "decayMs": 30000 },   // 阶跃后线性衰减回基线
    { "svid": 404, "op": "resume" }                                  // 清除该 SVID 全部活动效果
  ]
}
```

首批 5 种 op(`set` / `add` / `pulse` / `shift` / `resume`),仅数值与 BOOLEAN 型 SVID;`pulse` 带 `holdMs`,`set` 可选 `decayMs`。

**运行时机制**:
- 新增 `EventEffectStore`(每会话):记录已施加效果 `{svid, tApplied, params}`,每 SVID 有界环形(64 条);
- `ValueSourceHandle` 外包一层 `EffectLayer`:`valueAt(svid,t) = base(t)` 经活动效果修正后输出。S6F11 组装、S1F3 应答、Trace、预览共用同一 ValueSource,自动全部一致;
- 效果施加入口在 `emitCeid`(即所有触发路径——periodic/onCondition/手动 HTTP——统一生效);手动触发的效果进入运行时状态,天然被记录;
- 热重载 `applyHot` 时清空 EffectStore(旧模型的效果随旧配置失效)。

**确定性模拟**:at/periodic/onCondition/afterEvent 四种自动触发在给定 seed 下全部确定,因此 `config-preview` 可以先模拟窗口内的事件时间线上限(如 1000 条),再叠加 effects 进曲线分桶——**草稿(未保存)状态下就能预览"事件导致曲线跳变"**,这是需求 ①④ 的交汇点。手动/主机触发不参与草稿模拟。

联动闭环:值→(OnCondition)→事件→(effects)→值,可以编排"温度超阈值→报警事件→工艺参数突变"这类剧本。

### 1.2 曲线预览:当前时间轴 + 事件标记(需求 ①④)

- `PreviewData` 增加 `events: [{at, ceid, planId}]`(窗口内实际/模拟发生的事件);
- 前端 Sparkline/uPlot 画事件竖线,悬停显示 CEID/plan;
- 时间轴语义保持"最近 N 分钟、右端点 = 现在"(现有 uptime 倒推逻辑);草稿预览的模拟时钟 0..windowMs 在展示层映射为 now-N..now;
- 过滤:`GET /api/v1/preview?svids=401,402`;草稿 `POST /api/v1/config-preview` 加 `svids` 与 `withEvents` 参数——单 SVID 预览即由此驱动。

### 1.3 模板粒度下沉到 SVID(需求 ④)

三层模板模型**保留为高级模式**(复用+参数化),新增**直配**路径:

```jsonc
// equipment.units[].svids[] 与 globalSvids[] 每个 SVID 可选:
{ "slot": 1, "type": "F8", "min": 0, "max": 100,
  "curve": { "type": "sine", "periodMs": 60000, "amplitude": 20, "center": 50 } }  // 内联曲线
// 或
{ "slot": 2, "template": "chamber-temp", "params": { "base": 45 } }                // 引用模板+参数
```

- **单写者规则扩展**:同一 SVID 的值来源在 `curve` / `template` / `templates.instances` 三者中只能有一种,编译器一次性报错;
- 都不配时 `DefaultCurves` 随机游走兜底,行为不变;
- UI 上三种来源统一呈现为"这个 SVID 的值来源"卡片,instances 编辑器保留。

### 1.4 Schema:v6 增量演进

- `SCHEMA_VERSION=6`、`MIN` 保持 4;v5 文档不加任何新字段即合法 v6(effects、curve、template 全是可选字段);
- 文档:在 `docs/schema-v5.md` 增补"v6"章节,或新建 schema-v6.md(实施时定,倾向前者+user-guide 更新)。

### 1.5 主机模拟:S1F3 事件触发采集器(需求 ⑤)

**语义**:主机模拟器运行期间监听设备发来的 S6F11——收到 `startCeid` 即开始按 `intervalMs` 周期向设备发 S1F3(采集 `svids`),收到 `stopCeid` 即停止;采集值实时入库供前端画图。这是**主机侧拉取(S1F3 轮询)**,与 FDC Trace 的**设备侧推送(S2F23→S6F1)**机制互补,可同时运行对比两条链路。

配置与数据是**运行时工具状态,不进配置文件**(与主机模拟本身一致):

```jsonc
{
  "startCeid": 101,      // 收到该 CEID 的 S6F11 → 启动采集
  "stopCeid": 102,       // 收到该 CEID 的 S6F11 → 停止(必须 ≠ startCeid)
  "intervalMs": 1000,    // S1F3 轮询周期,下限 200
  "svids": [             // 采集目标;本机模式从 catalog 勾选,远程模式手填(见 1.7)
    { "id": 401, "name": "TEMP_PV", "unit": "C" }
  ]
}
```

**机制**(HostSimulator 扩展,与设备同 JVM,可直接读编译模型):
- **触发识别**:HostSimulator 已自动应答设备的 W 位 primary(S6F11→S6F12);在应答前挂钩子,从 S6F11 body 的 `L[3]` 第 2 项解析 CEID 比对 start/stop(结构化 SecsItem 访问,无需文本解析);
- **采集循环**:启动后单线程定时器每 intervalMs 发 S1F3 W;S1F4 应答解码——本机模式按 `ResolvedSvid` 声明的类型,远程模式按应答项自带格式码(见 1.7)——标量值带时间戳追加到每 SVID 有界环形(3600 样本 ≈ 1h@1Hz);数组类型(应答为嵌套 L)首版取首元素或记 NaN 并累计 decodeErrors;
- **手动启停**:除 CEID 触发外提供 API 手动 start/stop,便于调试;
- **生命周期**:采集器状态与数据随 HostSimulator 实例存在,关闭主机模拟即丢弃;UI 记忆上次配置,重开后自动重填。

**API**:

| 端点 | 功能 |
|---|---|
| `PUT /api/v1/sessions/{id}/host-sim/collector` | 下发采集配置(校验 start≠stop、SVID 在 catalog 中、intervalMs ≥ 200) |
| `POST .../host-sim/collector/start` / `.../stop` | 手动启停(调试用;正常流程由 CEID 事件驱动) |
| `GET .../host-sim/collector` | `{running, startedAt, samples, svids, series:[{svid, t[], v[]}], decodeErrors, lastRttMs}` |

**展示**(设备页·工具,新增"S1F3 数据采集"卡):
- 配置区:start/stop CEID(datalist 来自 catalog)、周期、SVID 多选(复用 FDC Trace 的勾选 UI,候选即当前设备配置的 SVID);
- 状态区:running 徽标、已采样本数、最近一次 S1F3 往返延迟、解码错误数;
- 图表区:复用现有 charts 组件(uPlot 多序列、按单位分轴),前端每 2s 轮询增量追加点;可勾选显示哪些 SVID;CSV 导出。

### 1.6 内置两个默认设备配置(需求 ⑥)

**定位**:不是"新建配置时的模板"(那是现有 `DeviceTemplates` 三选一),而是**开箱即用、随包分发的两份完整 JSON 配置**——首次启动即出现在配置列表与工作台里,同时充当 v6 新特性的"活文档"(比 schema 文档直观)。

| 文件 | 剧本 | 覆盖的特性 |
|---|---|---|
| `demo-etch.json`(刻蚀机,主剧本) | 双腔体,工艺事件密集 | periodic 事件带 effects(pulse/shift/set+decay)、OnCondition 闭环(温度超阈值→报警事件→参数突变)、每 SVID 直配曲线/模板混用 |
| `demo-cvd.json`(CVD,主机剧本) | 多曲线平稳采集 | 面向主机模拟:清晰的报告结构、一对适合做 S1F3 采集启停的 CEID、FDC Trace 友好的高频曲线 |

**承载与播种**:
- 两份 JSON 作为 **classpath 资源**打包(`secs-runtime` resources `defaults/`;GraalVM 原生镜像需把资源加入 include 列表)——用 JSON 而非 Java 构造(现有 `DemoEquipment.java` 是硬编码),用户可直接读、拷走改;
- `Main` 启动时播种:workDir 下不存在对应文件则原子写入(复用 `ConfigStore.writeAtomically`);**幂等不覆盖**——文件已存在(含用户改过的)一律不动;删除文件后重启即恢复默认;
- 端口约定:`demo-etch` 用 `--hsms-port`(默认 5000)、`demo-cvd` 用其 +1;指定了 `--hsms-port` 时播种按该值覆写,保证两台 passive 会话端口必不冲突;deviceId 分别取 `--device-id` 与其 +1;
- **`--demo-session` 吸收收编**:由"开一台 Java 硬编码演示设备"改为"打开播种后的两份默认配置"(会话 id 取各自 `session.id`);`launcher` 的 `demo` 名字保留为别名指向这两个文件;`DemoEquipment.java` 硬编码路径废弃;
- 与总工作台(需求 ②)联动:免令牌 + `--demo-session` 启动即见两台设备卡片,快速上手路径缩短为"启动 → 打开页面"。

### 1.7 主机模拟目标可选:默认本机 SECS,可连远程 SECS(需求 ⑦)

**现状**:HostSimulator 的连接目标写死为同进程设备会话(`runtime.hostTarget()` = 该会话的 bindHost:port:deviceId)。

**设计**:开启主机模拟时可选目标——

```jsonc
// POST /api/v1/sessions/{id}/host-sim
{ "target": null }                                                    // 缺省/null = 本机设备(现状行为)
{ "target": { "host": "192.168.1.50", "port": 5000, "deviceId": 0 } } // 远程 SECS
```

- URL 里的 session id 仍只作为**工具实例的宿主**(每设备页一个主机模拟实例,沿用现有 hostSims Map 键),不要求目标与本机会话相关;远程模式跳过"设备已有真主机→409"检查(远端是否已有主机不可知,冲突由 HSMS 连接行为自然暴露);
- 通信建立等待(`openAndWaitUntilCommunicating`)与断线自动重连(已有能力)复用;状态接口返回 `{target, mode, state: connecting|communicating|reconnecting|closed}` 等;
- 采集器的 CEID 触发逻辑(1.5)与本机/远程无关——S6F11 body 解析是通用的,远程设备的 S6F11 照样能触发启停。

**远程模式的 catalog 缺失与降级规则**——所有依赖本地 catalog 的输入转手动填写:

| 输入 | 本机模式(默认) | 远程模式 |
|---|---|---|
| S1F3 采集 SVID(1.5) | 从 catalog 勾选,自动带 name/unit | 手填 SVID 号 + 可选名称/单位 |
| 采集器 start/stop CEID | datalist 来自 catalog | 手填数字 |
| S2F23 FDC 编排 | SVID 勾选;TRID/DSPER/TOTSMP/REPGSZ 本就手填 | SVID 手填,其余不变 |
| SML 编辑框 datalist 提示 | 来自 catalog | 无提示,纯手写 |

**解码策略(采集器)**:远程模式**按 S1F4 应答项自带的线上格式码解码标量**(U1/U2/U4/I1/I2/I4/F4/F8/BOOLEAN…),不依赖本地类型声明——S1F4 应答与请求按位置对应、格式码在报文里,天然可用,零元数据即可画图;名称/单位只影响展示。嵌套 L 仍记 decodeErrors。

**UI(设备页·工具的主机模拟卡)**:目标切换"本机 / 远程",选远程展开 host / port / deviceId 三个输入;SVID 输入控件随模式自动切换(勾选列表 ↔ chips 手动添加,回车录入 SVID 号);S6F1/S6F11 收包计数以主机模拟自身收到的报文为准(远程时设备侧 counters 不可见)。

---

## 2. 后端 API 增量(全部新增,不动现有契约)

| 端点 | 功能 |
|---|---|
| `GET /api/v1/overview` | 工作台聚合:每会话 `{state, controlState, hasPeer, device, counters, rates{msgsPerMin, s6f11PerMin}, lastEventAt, activeAlarms, hostSim, uptime}` + 全局 `{version, mode, uptime, sseClients}`。速率用 60s 滑动环(`RateTracker`,挂 runtime 计数点) |
| `GET /api/v1/events`(SSE) | 现在只推 `message`;**增加事件类型**:`event: s6f11`(sessionId/ceid/planId/at/值摘要)、`event: alarm`、`event: session`(状态迁移)。EventSource 按类型分发,工作台/事件流实时化,减少轮询 |
| `POST /api/v1/sessions/{id}/burst` | 后端压测:`{kind: "message"\|"ceid"\|"alarm", payload, count, rate, concurrent}` → `{sent, failed, latencyP50/95/max, durationMs}`。把现在前端 20-worker HTTP 循环(SendPanel.runStress)下沉到服务端,消除往返失真;ceid 压测即"特定事件压测"(走 emitCeid 路径,可带 `bypassLimiter` 开关) |
| `POST /api/v1/sessions/{id}/host-sim` | 现有端点增加可选 `target` 参数:缺省连本机,传 `{host,port,deviceId}` 连远程 SECS;远程模式跳过 409 检查、catalog 相关输入全部转手填(详见 1.7) |
| `PUT/POST/GET /api/v1/sessions/{id}/host-sim/collector` | 主机模拟 S1F3 采集器:下发配置 / 手动启停 / 状态与数据(详见 1.5) |
| `GET /api/v1/preview` | 加 `svids` 过滤参数;`PreviewData.events[]` 事件标记 |
| `POST /api/v1/config-preview` | 同样支持 `svids` 过滤 + 模拟事件时间线 + effects 叠加 |

**配置分段编辑不需要新端点**:配置始终是整文件校验+原子保存,分段编辑是前端对文档子树的读写,保存仍走 `PUT /api/v1/configs/{name}`,热生效链路不变。

**结构注意**:HttpApiServer 已 2159 行单文件。新 handler 按功能域拆小类(OverviewHandler/BurstHandler/…) + 路由注册表方式接入,不再往单文件堆;`ApiContext` 接口按域拆分 default 方法组(纯转发边界保持)。

---

## 3. 前端页面重设计

### 3.1 信息架构:两层结构 + 轻量 hash 路由

```
#/workbench                 工作台(默认)
#/device/{id}/monitor       设备页 · 监控(默认)
#/device/{id}/config        设备页 · 配置
#/device/{id}/tools         设备页 · 工具
#/system/{audit|console}    系统页(全局)
```

- 自写 ~60 行 hash 路由(零依赖,项目惯例);刷新/深链可恢复;`activeTab` 偏好迁移到路由;
- 未提交的 Sidebar/Topbar/navigation.ts 骨架保留,侧栏改为两组:工作台 + 设备列表(来自 /sessions,动态);
- 现有 12 面板收编:

| 现面板 | 去向 |
|---|---|
| overview | 工作台首页(升级为下述完整形态) |
| sessions | 并入工作台(卡墙 + 批量操作进工作台工具栏) |
| alarms / events / catalog / preview | 设备页·监控(报警区/事件流/目录抽屉/曲线) |
| messages | 工作台全局报文流 + 设备页(自动按设备过滤) |
| config | 设备页·配置;"配置列表/新建/内置设备模板"留工作台工具栏 |
| send / hostsim | 设备页·工具 |
| audit / console | 系统页 |

### 3.2 工作台(需求 ②)

- **设备卡墙**:每设备一卡——连接/控制态徽标、消息速率与事件速率 sparkline(来自 overview.rates)、活动报警数、hostSim 状态、最近一条事件;按状态/关键字/端口过滤、可排序;
- **事件流**(全设备):SSE `s6f11` 实时滚动,按设备 chip 过滤,支持暂停(复用现有缓冲机制);
- **最近报文**(全设备):现报文 feed + 设备/方向过滤器;
- **统计区**:每分钟事件数(按设备堆叠柱)、报文吞吐、报警分布;数据源 = overview 轮询 + SSE 本地聚合,不新增存储;
- 点击设备卡 → 进入 `#/device/{id}/monitor`。

### 3.3 设备页(需求 ③④)

- **监控 Tab(默认)**:关键 SVID 曲线(多选,当前时间轴 + 事件标记)、S6F11 事件流、报文流、活动报警、终端消息、14 项计数器;
- **配置 Tab**:
  - 左侧分段树:session / equipment(globalSvids + 每 unit 独立折叠卡)/ events(plans)/ templates;
  - 顶部保留 form / json / diff / preview 四视图切换与校验/保存/重载(现有能力平移,整文件保存);
  - **每个 SVID 行内展开"值来源"卡**:内联 curve | 引用 template+params | 默认;下方即该 SVID 的**单变量曲线预览**(config-preview withEvents,当前时间轴 + 事件标记,边改参数边重画——草稿预览不落盘,天然支持);
  - events.plan 编辑器增加 effects 子编辑器(选 SVID → 选 op → 参数,配 mini 预览);
- **工具 Tab**:手动发送 SML(现 SendPanel 平移)、自定义消息压测(burst kind=message)、特定事件压测(kind=ceid)、报警风暴(kind=alarm)、主机模拟(HostSimPanel 整体迁入,含 FDC Trace 编排;目标可选本机/远程,远程模式 SVID/CEID 全手填,见 1.7)+ 新增 **S1F3 数据采集**卡(CEID 触发启停、SVID 勾选/手填、uPlot 实时曲线、CSV 导出,见 1.5)。

### 3.4 状态与数据流

- 新建 `useLiveStream`:SSE 按事件类型分发为 `{messages, s6f11Events, sessionStates}` 三个切片,视图按需订阅;
- 轮询按页面分级:工作台 2s overview;设备监控 2s;配置页仅保存时交互;
- preferences 增加设备级视图记忆(曲线选择、时间窗、工具参数)。

---

## 4. 实施阶段(建议 6 个 PR)

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0** | 提交当前工作区(新布局骨架/OverviewPanel/Icon/App.css 重写等未提交内容),形成基线 | git 干净 |
| **P1** | schema v6 + 编译器:effects、svids.curve/template、单写者扩展校验;SessionConfigJson/EquipmentCompiler/TemplateConfig/templateConfig.ts 配套 + 单测;产出两份默认设备配置 JSON(1.6,兼作编译器验证载体) | 编译错误用例全绿;v5 样例零改动可加载;两份默认配置通过编译校验 |
| **P2** | 运行时:EffectLayer/EventEffectStore、config-preview 事件模拟、preview svids 过滤+事件标记、SSE 类型化、overview、burst、HostSimulator S1F3 采集器(1.5)+ 目标可选本机/远程(1.7)、默认配置播种 + `--demo-session` 开两台(1.6) | 用 demo-etch 手动触发 CEID,曲线可见跳变;SSE 收到 s6f11 事件;主机模拟下手动触发 start CEID 后开始出数、stop CEID 后停止;主机模拟可连第二台本机默认设备(以远程模式连 demo-cvd 端口)验证远程路径;首启 workDir 出现两份默认配置且 `--demo-session` 开出两台 |
| **P3** | 前端骨架:hash 路由 + 工作台(卡墙/过滤/事件流/统计),旧面板收编 | e2e mock 更新(panelId 映射同步!) |
| **P4** | 设备页:监控/配置分段编辑器/每 SVID 值来源+曲线预览/工具页迁移(含 S1F3 采集卡与实时曲线、主机模拟远程目标 UI) | e2e mock 覆盖三 Tab;采集卡在 mock 下能画曲线 |
| **P5** | 文档:schema-v5 增补 v6 章节、user-guide、openapi.yaml 新端点 | — |

---

## 5. 风险与决策点

1. **preview 的"历史重放"语义边界**:effects 引入后,`/api/v1/preview` 重放只覆盖自动计划;手动/主机触发产生的效果只存在于运行时状态、不可从时间纯函数重放。文档必须写清"预览 = 配置的确定性影子,不是运行时录像"。
2. **OnCondition 与 effects 的相互依赖**:条件表达式的值含效果修正后,条件触发链可能自激(效果改变值 → 触发条件 → 再施效果)。需要在编译期检测"plan 的 OnCondition 引用了本 plan effects 写的 SVID"并告警(允许但提示),文档给出 debounce 建议。
3. **SSE 多事件类型**对 openapi.yaml 与既有消费者是增量,`message` 事件字段不动,兼容无虞。
4. **路由与快捷键并存**:数字快捷键与 Ctrl+K 命令面板改为导航到路由目标;设备类命令(如"跳到某设备")需先有设备列表数据。
5. **burst 的限流语义**:message 压测绕过 events.maxPerSec(它不产事件),ceid 压测默认受 S6F11 限流、可显式 bypass——防止"压测把限流计数打爆导致正常事件被拒"的误判。
6. **S1F3 采集器的解码与节奏边界**:S1F4 对数组类型返回嵌套 L,首版仅承诺标量类型,嵌套记 decodeErrors 跳过;采集过密会挤占设备的 W 位 ACK 队列,故 intervalMs 下限 200ms 且 UI 提示;采集的 SVID 若随后续热重载被删除,对应序列停止追加并在状态里标记 stale。
7. **远程主机模拟的语义边界**:对远程 SECS 的 S1F3/S2F23 行为完全取决于对端实现(类型、应答格式、是否支持 trace),首版按标准语义发送、异常显性报错不强解析;主机模拟无法感知远端是否已有真主机,双主机冲突风险由用户自行保证(工具定位,user-guide 提示);远程模式下"本机会话的 controlState/counters"与主机模拟状态无关,UI 必须分开呈现,避免误读。
