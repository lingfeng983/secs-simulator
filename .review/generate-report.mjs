import { writeFileSync } from 'node:fs';

const OUT = 'E:/ai/secs-simulator/.review/设计评审报告.html';

const pages = [
  {
    id: 'workbench', title: '设备清单（工作台）', route: '#/workbench', img: '00-workbench.png', sev: 'P1',
    layout: '整页一张表，筛选行 + 批量操作行 + 表格 + 底部翻页；卡片撑满剩余高度，空行留白很大但不刺眼。',
    func: '新建/批量重启/批量停止/批量删除/导入导出齐备；行内可直接切区域、启停设备。',
    fix: [
      '「区域」列做成行内下拉，行高只有 30px，很容易误触改错区域；建议改为徽标 + 编辑入口，或仅在编辑态出现下拉。',
      '批量按钮在未勾选时是纯文字且对比度极低（看起来像被禁用又像可点），建议统一为带边框的次级按钮 + 明确的禁用态。',
      '工具栏右侧「2 条」与两个无标签图标（导入/导出）挤在表格右上角，建议给图标加 tooltip 或收进「更多」菜单。',
      '表头每列都常显 ⇅ 排序图标，列一多就是噪声；建议仅 hover 时出现。',
    ],
  },
  {
    id: 'd-overview', title: '设备 · 配置区 / 概览', route: '#/device/{id}/config/overview', img: '13-line2-config-overview.png', sev: 'P0',
    layout: '一张 6 行的键值表贴在卡片顶部，下方约 70% 是纯空白。整页没有任何分区或摘要卡。',
    func: '只有会话 id / 设备名 / 配置文件 / 模板 / 单元数 / 大小与修改时间；没有连接参数、变量数、事件数、周期摘要，也没有「自动连接」开关（路由注释里写了有）。停止态下「单元数」显示「—」，但配置里明确有 1 个单元——数据源取自运行时会话而非配置。',
    fix: [
      '概览应回答「这台设备是什么、现在怎么样」：建议 2×2 摘要卡（连接：被动 5010 / 在线；结构：1 单元 · 3 SVID · 2 CEID · 2 RPT；周期：未配置；最近事件/报警），下方再放身份信息表。',
      '单元数、设备名、模板等静态信息一律从配置读，不要依赖会话；停止态显示真实值而不是「—」。',
      '把「自动连接」「模板」这类可改项放到概览并配保存按钮，或明确标注「只读 · 去连接页修改」。',
    ],
  },
  {
    id: 'd-connection', title: '设备 · 配置区 / 连接', route: '#/device/{id}/config/connection', img: '13-line2-config-connection.png', sev: 'P0',
    layout: '单列 13 个字段，标签右对齐 120px、输入框固定 280px，右侧约 55% 的卡片宽度完全空着；三个分组标题（HSMS 超时 / Trace 容量）只是加粗小字，没有视觉分隔。',
    func: '字段齐全（端口、绑定地址、deviceId、T3/T5/T6/T7/T8、Trace 容量），并有保存会重启会话的明确提示。',
    fix: [
      '改为两列或三列网格（短数字字段 2–3 列、长文本独占一行），一屏放完，避免无意义的纵向滚动。',
      '分组用 fieldset + 细分隔线或浅底区块承载，标题加层级；T3–T8 这类协议超时建议默认折叠在「高级」里。',
      '给每个超时字段补一句「默认值 / 单位 / 影响」的 hover 说明，工程师不必去翻文档。',
    ],
  },
  {
    id: 'd-structure', title: '设备 · 配置区 / 结构', route: '#/device/{id}/config/structure', img: '13-line2-config-structure.png', sev: 'P1',
    layout: '「设备全局」6 个号段字段排成 3+3，但标签居中浮在输入框上方且宽度不一（SVID 起点 / CEID 起点 / RPTID 起点…），左边缘参差；单元行把 unitId、单元名、复选框、删除按钮横排在一行，标签与值的归属靠位置猜。',
    func: '号段规则与单元定义完整；启动态会提示「结构改动需先停止设备」并禁用保存，边界处理是对的。',
    fix: [
      '标签统一左对齐、与输入框同宽栅格；6 个号段字段用 2 列 ×3 行，读起来是「一组规则」而不是 6 个散点。',
      '单元行改为卡片式（标题：CH1 · unitId 1，右侧删除；内部两列字段），多单元时可折叠。',
      '「报告自动带 UNIT_ID」是复选框但标签在右侧、与字段同排，建议改为开关并加说明。',
    ],
  },
  {
    id: 'd-constants', title: '设备 · 配置区 / 设备常量', route: '#/device/{id}/config/constants', img: '13-line2-config-constants.png', sev: 'P2',
    layout: '与变量配置页同构：搜索框 + 主按钮 + 表格 + 底部说明。表格 6 列，行高 30px，操作列三枚文字按钮。',
    func: '增删改查完整；「定义值」列说明 ECV 可被 S2F15 改写，语义清楚。',
    fix: [
      '搜索框固定 160px、与右侧主按钮之间隔了 700px，视觉上像两个不相干的元素；建议搜索框自适应或与按钮同组。',
      '操作列「编辑/预览/删除」字号 11px、间距 4px，误点风险高；建议图标按钮 + hover 才显示删除。',
    ],
  },
  {
    id: 'd-context', title: '设备 · 配置区 / 晶圆属性', route: '#/device/{id}/config/context', img: '13-line2-config-context.png', sev: 'P1',
    layout: '与常量页一致的表结构，5 列；「值定义」列是唯一的语义列，却只有 11px 灰字。',
    func: '支持按片 pattern 与每几片进一。但摘要把 cycle 来源统一显示为「按片 · LOT{03}」，丢掉了 every=25 的信息，LOTID 会被误读成每片换批号。',
    fix: [
      '摘要补全步进：「每 25 片 · LOT{03}」；WAFERID 显示「每片 · W{03}」。',
      '新建弹窗里给一段实时预览（W001 → W002 → W003…），这是最容易配错的一类字段。',
      '「报告里的写法」列（ctx.LOTID）是只读派生值，建议用等宽小字 + 复制按钮，弱化存在感。',
    ],
  },
  {
    id: 'd-svids', title: '设备 · 配置区 / 变量配置', route: '#/device/{id}/config/svids', img: '13-line2-config-svids.png', sev: 'P1',
    layout: '表格 7 列，行高 30px，操作列三枚 11px 文字按钮；「定义」列承载最复杂的信息（曲线 · sequence · 片内相位）却只有一行灰字。',
    func: '增删改查、预览曲线、值定义四选一都在；底部说明解释了绝对 id = 起点 + offset。',
    fix: [
      '「定义」列建议用可点开的摘要标签（常量 180 / 曲线 · 三段 / 表达式 …），点击直接进值定义区，而不是再进一次弹窗。',
      '把「单位」并入类型（F4 · °C），省一列；行内操作收成图标 + 悬浮显示。',
      '多单元设备建议顶部加单元切换（当前只能看到 CH1），与事件页保持同一套切换控件。',
    ],
  },
  {
    id: 'd-events', title: '设备 · 配置区 / 事件与报告', route: '#/device/{id}/config/events', img: '13-line2-config-events.png', sev: 'P1',
    layout: '三张堆叠卡片（全局 CEID / 单元报告 / 单元事件），每张都是「表头 + 行内输入框」的密集网格；行与行之间几乎没有分隔，删除按钮挂在网格右外侧、没有列名。',
    func: 'CEID ↔ RPT ↔ SVID/ECV/CTX 的引用关系都能配；报告顺序有说明。缺单元切换，多单元设备只能编辑第一个单元。',
    fix: [
      '三块之间用分区标题 + 序号（① 全局事件 ② 报告 ③ 事件）建立层级；行加斑马纹或分隔线，删除列补表头。',
      '顶部加单元切换（CH1 ▾），或在单元标题上做成可切换的标签页。',
      '此处用行内编辑、变量/常量/晶圆属性用弹窗编辑——两套模型并存；建议统一为「行内快改 + 弹窗精修」的同一套规则。',
      '1280 宽下「TEMP, PRESS, F…」「ecid.501（TEMP_…」已被截断，多选控件需要更宽的最小宽度或换行显示已选标签。',
    ],
  },
  {
    id: 'd-cycle', title: '设备 · 配置区 / 工艺周期', route: '#/device/{id}/config/cycle', img: '13-line2-config-cycle.png', sev: 'P1',
    layout: '标题区 + 参数行 + 预览图（约 840×180）+ 片内事件条。图表高度偏矮，下方「片内事件 (1)」是个 40px 高的空盒子，占了一大块却没有内容。',
    func: '没有 cycle 段时给出「按绝对时间跑一次」的解释与「插入 cycle 段」入口，空态引导是清楚的。',
    fix: [
      '预览图给到 280–320px 高，并支持拖拽调高；图表是这个页面的主角，不该比一张表单还矮。',
      '「片内事件」空态改成一行紧凑的提示 + 新增按钮，不要留空盒子。',
      '参数/时间窗/自动刷新三个控件与图表标题同一行，右侧再放「重新预览」，视觉上是一组工具条而不是散落控件。',
    ],
  },
  {
    id: 'd-plans', title: '设备 · 配置区 / 事件计划', route: '#/device/{id}/config/plans', img: '13-line2-config-plans.png', sev: 'P1',
    layout: '标题 + 一句说明 + 「插入 events 段」孤零零挂在右侧；下面是预览图与计划触发图例，页面上下割裂。',
    func: '列表区显示「还没有 events 段」，但下方预览却画出「计划到触发 (1) · beat」——列表与预览的数据源不一致，用户会以为配置已生效。',
    fix: [
      '统一数据源：草稿预览只画草稿里的计划；运行态另开一栏并标注「当前运行」。',
      '空态时把预览区折叠或置灰，避免「上面说没有、下面画出来」的自相矛盾。',
      '「插入 events 段」是主操作，应放在空态中央作为 CTA，而不是缩在标题右侧。',
    ],
  },
  {
    id: 'd-document', title: '设备 · 配置区 / 配置原文', route: '#/device/{id}/config/document', img: '13-line2-config-document.png', sev: 'P1',
    layout: '编辑器撑满整卡（约 450px 高），单行 JSON 横向溢出；「目录内配置 2」悬在编辑器下方、卡片之外，像一段遗留元素；保存/校验/重新载入被放到了卡片最底部。',
    func: '有格式化、行数统计、查看差异；但差异只对比「草稿 vs 磁盘」，版本之间无法 diff。',
    fix: [
      '保存/校验/重新载入统一到卡片右上角，与其他配置子页一致（当前全站只有这一页放在底部）。',
      'JSON 编辑器默认换行或提供「自动换行」开关；「目录内配置 2」并进工具栏或删掉。',
      '把版本历史与 diff 打通：在版本历史里点某版 → 与当前草稿并排对比 + 回滚。',
    ],
  },
  {
    id: 'd-revisions', title: '设备 · 配置区 / 版本历史', route: '#/device/{id}/config/revisions', img: '13-line2-config-revisions.png', sev: 'P1',
    layout: '单表 5 列，时间用等宽字体，校验值截断 12 位 + 省略号；页面只用了顶部 1/6。',
    func: '只能看，不能对比、不能回滚、不能展开看那次改了什么。「配置版本历史与回看」的承诺没有兑现。',
    fix: [
      '每行加「对比 / 回滚 / 下载」；点击行展开该版本的变更摘要（哪几个字段变了）。',
      '校验值给完整值 + 复制，或改为短 sha 的 tooltip。',
      '空态/少数据时页面不要撑满：内容区自适应高度，底部留白交给页面背景。',
    ],
  },
  {
    id: 'r-plans', title: '设备 · 运行区 / 事件执行', route: '#/device/{id}/runtime/plans', img: '11-device-runtime-plans.png', sev: 'P1',
    layout: '搜索 + 批量启停 + 表格；下面「执行明细」是一列时间线卡片，再下面「事件预览」图表，最后右下角还有一块统计卡。三块内容纵向堆叠，一屏放不下，图表被切在视口外。',
    func: '计划启停、单步试跑、触发、停止、执行台账（含 SVID before→after）都具备，是这个应用最有价值的一页。',
    fix: [
      '拆成「计划表 / 执行明细 / 预览」三个可折叠区，或把明细做成右侧抽屉，让计划表与预览同屏。',
      '「批量启动/批量停止」与右下角「全部启动/全部停止」是同一件事的两套入口，删掉一套。',
      '执行明细的状态色语义要统一：SENT 蓝、FAILED 红、SKIPPED 灰（当前 SKIPPED 用了绿色，与「成功」混淆）。',
    ],
  },
  {
    id: 'r-cycle', title: '设备 · 运行区 / 工艺周期', route: '#/device/{id}/runtime/cycle', img: '11-device-runtime-cycle.png', sev: 'P1',
    layout: '状态条 + 只读定义卡 + 预览图 + 执行明细，纵向四段；「执行明细」与「事件执行」页完全相同，属于重复内容。',
    func: '启停、当前片号/相位/进度、片内事件发送记录都有；只读定义来自已保存配置，边界清晰。',
    fix: [
      '执行明细只保留在一处（建议事件执行页），本页只留「片内事件发送记录」这一子集。',
      '状态条信息过少（停止 / 待启动 / 已发 0 条），建议补当前片号、相位进度条与周期时长。',
      '图表与定义卡之间缺少留白节奏，卡片间距一致但层级不清，建议定义卡降级为可折叠摘要。',
    ],
  },
  {
    id: 'r-manual', title: '设备 · 运行区 / 手动与压测', route: '#/device/{id}/runtime/manual', img: '83-manual-after-send.png', sev: 'P0',
    layout: 'SML 编辑器在顶部，压测与事件压测在中间，发送结果区被挤到「事件上报压测」下方，发送按钮在卡片右下角——离编辑器约 500px。',
    func: '发送结果区分了「未请求回复 / 超时 / 耗时」，思路正确；但字段缺失时直接渲染成「发送 line1  SundefinedFundefined」「耗时 undefined ms」，且与右下角 toast 重复播报同一结果。',
    fix: [
      '「发送 SML」紧贴编辑器右下；结果区紧跟编辑器（或做成编辑器下方的结果条），不要落在压测区之后。',
      '渲染前做字段兜底：S{stream}F{function} 缺失时显示「—」，耗时缺失显示「—」，不要把 undefined 直接抛给用户。',
      '报警区把「置位报警」做成主按钮、「清除报警」做成次级，危险方向反了；建议置位用警告色描边、清除用主色。',
    ],
  },
  {
    id: 'hosts', title: '主机清单', route: '#/hosts', img: '20-hosts.png', sev: 'P0',
    layout: '与设备清单同构：筛选 + 批量 + 表格，行高 40px；列宽分配合理，但「实例 ID」列显示 30 位 ULID，信息价值接近零却占了 240px。',
    func: '新建、连接/断开、详情、删除齐备。状态列用「链接 / 停止」，语义错误（应为「已连接 / 未连接」）；「设备 / 生效」列显示「v3 / v3」「v1 / —」两个裸版本号，没有列语义。',
    fix: [
      '状态文案统一：已连接 / 连接中 / 未连接 / 重连中；「停止」留给进程而不是链路。',
      '「设备 / 生效」改为「设置 v3 · 已生效」并配状态点，未生效时给出「使设置生效」的快捷入口。',
      '实例 ID 默认隐藏（详情页可见），或缩成 host_…A 的可复制短标识。',
    ],
  },
  {
    id: 'h-overview', title: '主机 · 配置区 / 概览', route: '#/hosts/{id}/config/overview', img: '21-host-config-overview.png', sev: 'P1',
    layout: '两个区块（名称 / 连接目标）各带一个右对齐保存按钮和一段右对齐说明；字段在左、按钮在最右，视线要横跨 800px。下方三张摘要卡（Trace / S1F3 方案 / 采集状态）是全页最有价值的部分。',
    func: '改名与改目标拆成两个保存动作，容易只改一半；「当前设置 v3 · 生效 v3 · 自动重连」是一行灰字，没有状态点。',
    fix: [
      '合并为一个「保存」按钮，或改成「名称」失焦即存、「连接目标」显式保存并提示会重建链路。',
      '说明文字移到字段下方（就近解释），不要贴到卡片右边。',
      '三张摘要卡前置到最上方，作为「这台主机现在有什么」的第一屏。',
    ],
  },
  {
    id: 'h-presets', title: '主机 · 配置区 / 报文模板', route: '#/hosts/{id}/config/presets', img: '21-host-config-presets.png', sev: 'P2',
    layout: '16 行表格，描述列用「粗体标题 + 灰色副标题」两行结构，可读性好；但行高被右侧「编辑/删除」按钮撑到 45px，表头不吸顶，滚动后列名会消失。',
    func: '预设可编辑、可删除，SFY 与回复都标了出来，是这一页的亮点。',
    fix: [
      '操作按钮改成图标（编辑/删除）并 hover 显示，把行高还给内容。',
      '表格容器内部滚动 + 表头 sticky，不要让整页跟着滚。',
      '分组列（链接与控制 / 状态查询…）目前只在编辑弹窗里出现，列表可加分组筛选。',
    ],
  },
  {
    id: 'h-trace', title: '主机 · 配置区 / Trace 设置', route: '#/hosts/{id}/config/trace', img: '21-host-config-trace.png', sev: 'P2',
    layout: '说明段 + 搜索 + 主按钮 + 一条 75px 高的虚线空态，下面 700px 全是空白。',
    func: '方案库与下发分离（配置区只管方案、运行区下发）是正确的信息架构；空态文案「点右上新增方案」与按钮实际位置（搜索行右端）不符。',
    fix: [
      '空态垂直居中于可用空间，并放一个主按钮 CTA，而不是让用户去右上角找。',
      '说明段里的「去下发 →」是行内链接，容易被当成正文；建议做成独立的次级按钮。',
      '方案为空时隐藏搜索框（搜无可搜）。',
    ],
  },
  {
    id: 'h-collect', title: '主机 · 配置区 / 采集设置', route: '#/hosts/{id}/config/collect', img: '21-host-config-collect.png', sev: 'P2',
    layout: '与 Trace 设置页完全同构，一致性良好；同样存在「空态只占顶部一条、下面大片空白」的问题。',
    func: '方案库 / 下发分离；空态提示清楚。',
    fix: [
      '与 Trace 设置页共用同一个「方案库」组件，减少两页各自演化的风险。',
      '空态建议给出示例方案（一键插入「温度+压力 1s」），降低首次配置成本。',
    ],
  },
  {
    id: 'h-messages', title: '主机 · 运行区 / 消息发送', route: '#/hosts/{id}/runtime/messages', img: '22-host-runtime-messages.png', sev: 'P1',
    layout: '预设 chips 一行 + 260px 高编辑器 + 左下「发送」。比设备侧「手动与压测」干净得多。',
    func: '能发预设报文；但发送后没有结果区（回复/超时/耗时），只能去右侧监控抽屉里找——与设备侧能力不对等。',
    fix: [
      '补一个与设备侧一致的结果区（回复 SML / 超时 / 耗时），这是「发出去没有」的唯一答案。',
      '与设备侧「手动与压测」抽成同一个 SendPanel 组件，避免两套交互长期分叉。',
      '预设 chips 目前是 6 个固定项，建议与「报文模板」库打通（模板里配好的都能一键填入）。',
    ],
  },
  {
    id: 'h-trace-run', title: '主机 · 运行区 / Trace 采集', route: '#/hosts/{id}/runtime/trace', img: '22-host-runtime-trace.png', sev: 'P1',
    layout: '上方方案卡（含虚线空态）+ 下方一条独立空态卡，两种空态样式并存；页面 80% 空白。',
    func: '启动/暂停/条数控制具备；空态重复提示两次（「还没有 Trace 方案」+「还没有 Trace 方案；到配置区新增后这里按方案看趋势」）。',
    fix: [
      '删掉第二块空态卡，只保留卡内虚线空态 + CTA。',
      '有数据时明确分区：左方案列表、右曲线（当前是上下堆叠，曲线会被推到视口外）。',
    ],
  },
  {
    id: 'h-collect-run', title: '主机 · 运行区 / 数据采集', route: '#/hosts/{id}/runtime/collect', img: '22-host-runtime-collect.png', sev: 'P1',
    layout: '同一页出现三种「没有配置」的表达：卡内虚线空态、「未配置：在方案列表点「启动」下发配置」灰字、「采集曲线」区块下的又一句空态。',
    func: '下发/启停/曲线具备；空态信息重复且互相矛盾（一处说去配置区新增，一处说在方案列表点启动）。',
    fix: [
      '收敛成一套空态组件（图标 + 一句原因 + 一个 CTA），全站复用。',
      '「采集曲线」在无数据时整块折叠，不要留标题 + 空句子。',
    ],
  },
  {
    id: 'host-templates', title: '独立工具 / 主机模板', route: '#/host-templates', img: '30-host-templates.png', sev: 'P2',
    layout: '5 列表格，模板名后跟「内置」小徽标；说明列占 500px，操作列三枚按钮挤在右侧 120px。',
    func: '套用新建、编辑、删除齐备；内置模板只读、保存即另存的规则在弹窗里说明。',
    fix: [
      '说明列限宽并允许两行截断 + tooltip，把宽度还给「Trace/采集方案」计数。',
      '「套用新建」是主操作，建议做成行首图标按钮或 hover 出现的主按钮，而不是与删除并排的普通文字。',
    ],
  },
  {
    id: 'templates', title: '独立工具 / 设备模板', route: '#/templates', img: '31-templates.png', sev: 'P1',
    layout: '整页只有搜索 + 主按钮 + 一条虚线空态，空态下方 500px 空白；空态里没有 CTA。',
    func: '模板编辑器（1240px 宽弹窗、多页签、导入导出、校验）设计完整；但「新建模板」依赖已存在的源模板（bare），一旦库里为空就直接弹「没有可复制的源模板」错误，用户无路可走。',
    fix: [
      '空态里放「新建模板」CTA；「从空白创建」应始终可用，不要强依赖源模板。',
      '错误 toast 要给出下一步（「先在模板库导入一份 JSON，或从内置 bare 复制」）。',
      '列表补「基于哪个模板」「变量/事件数量」两列，帮助判断该用哪个模板。',
    ],
  },
  {
    id: 'svid-templates', title: '独立工具 / 参数模板', route: '#/svid-templates', img: '32-svid-templates.png', sev: 'P2',
    layout: '4 列表格，其中「形式」列两行都是「—」，是一列空数据；说明列很宽，行高 40px。',
    func: '复制、预览、编辑、删除都在；内置条目只读、复制后可改的规则清楚。',
    fix: [
      '「形式」列要么填上（常量/曲线/表达式），要么删掉。',
      '给每行加一条极简缩略曲线（20×80 的 sparkline），这是参数模板最有辨识度的信息。',
    ],
  },
  {
    id: 'audit', title: '独立工具 / 审计', route: '#/system/audit', img: '33-audit.png', sev: 'P2',
    layout: '筛选行里塞了 3 个输入框 + 主色「筛选」按钮 + 次级「清除过滤/载入更早」，输入框宽度不一，占位文案被截断（「动作精确匹配如 config-…」）。',
    func: '有操作者/动作/目标/详情与角色徽标；缺时间范围筛选、缺导出（右上两个无标签图标疑似导出/刷新）。',
    fix: [
      '筛选区改成「关键词 + 时间范围 + 高级筛选」三段式，输入框统一宽度并给完整 placeholder（或短标签）。',
      '「筛选」不该是主色按钮（主色应留给「新建/保存」这类创建动作），改为默认按钮 + Enter 触发。',
      '补时间范围与导出 CSV，审计才具备事后追溯价值。',
    ],
  },
];

const modals = [
  {
    id: 'm-create-device', title: '新增设备', img: '40-modal-create-device.png', sev: 'P1',
    layout: '400px 宽单列弹窗，标签右对齐 60px，输入框满宽；两个分组标题（链接信息 / 区域分组）比字段标签更靠左，左边缘参差。',
    func: '模板、监听地址、端口、Device ID、连接模式、立即启动、区域都在一屏；「批量新建」是顶部一个复选框，切换后整个表单形态变化。',
    fix: [
      '「批量新建」改成弹窗顶部的分段控件（单个 / 批量），比复选框更符合「模式切换」的心智。',
      '监听地址、Device ID 这些协议字段补一行说明或默认值提示；端口冲突要能就地报错。',
      '分组标题与字段同左边缘，或加浅色分组底板。',
    ],
  },
  {
    id: 'm-delete', title: '删除确认（设备）', img: '43-modal-delete-confirm.png', sev: 'P2',
    layout: '居中小弹窗，标题 + 一段说明 + 右下取消/删除，删除为红色主按钮。',
    func: '文案说明了后果（端口停止、从清单消失、配置与版本历史保留），是合格的危险确认。',
    fix: ['建议补「输入设备名确认」或至少把设备名做成等宽高亮，降低误删概率。'],
  },
  {
    id: 'm-settings', title: '通用设置', img: '44-modal-settings.png', sev: 'P1',
    layout: '480px 宽，四段用细分隔线切分；滑杆右侧数值、分组标题只有 10px 灰字；「宽度上下限」用「最小/最大/最小/最大」两套重复表头，靠左侧的行标签区分归属。',
    func: '主题、密度、布局、主色、字号、间距、导航宽度、监控宽度、日志级别都可调，粒度很细。',
    fix: [
      '分组标题提升到 12–13px 并加粗，让四段结构一眼可见。',
      '「宽度上下限」改为两个小分组（左侧菜单 / 消息监控），每组一对 min–max 输入，去掉重复表头。',
      '滑杆旁的数字建议可直接编辑（当前只能拖），工程用户更想输精确值。',
    ],
  },
  {
    id: 'm-connection', title: '连接设置（后端地址）', img: '45-modal-connection.png', sev: 'P2',
    layout: '单字段弹窗，标签 + 输入框 + 帮助图标，底部「取消 / 保存并重载」。',
    func: '浏览器与桌面壳两种形态文案不同，桌面壳会显示当前模式；保存会整页重载。',
    fix: ['加「测试连接」按钮（当前只能保存后重载才知道通不通）；地址格式做即时校验。'],
  },
  {
    id: 'm-palette', title: '命令面板', img: '46-palette.png', sev: 'P1',
    layout: '480px 宽浮层，顶部搜索、分组标题 10px、结果行 32px、底部快捷键提示 10px。',
    func: '跳页面、找设备/主机、找 CEID/SVID、切主题密度都在；但同一台设备出现三行（配置区/运行区/打开设备），信息重复。',
    fix: [
      '同一对象只留一行，右侧用「配置区 / 运行区」两个小按钮承载分支。',
      '选中态对比度太低（浅青底 + 深字），键盘操作时几乎看不出焦点。',
      '结果行补图标与类型标签（设备/主机/动作），提高扫读速度。',
    ],
  },
  {
    id: 'm-shortcuts', title: '快捷键说明', img: '47-shortcut-help.png', sev: 'P2',
    layout: '360px 宽，左键帽右说明，7 行；键帽按 token 拆分，`1..9 / 0` 被拆成三个键帽。',
    func: '说明完整，与实现一致。',
    fix: ['组合键建议整串放一个键帽（Ctrl+K）；「1..9 / 0」这类范围用一个键帽 + 说明文字，不要拆成三个。'],
  },
  {
    id: 'm-monitor', title: '实时消息监控（右侧抽屉）', img: '48-monitor-drawer.png', sev: 'P0',
    layout: '420–620px 右侧抽屉，两行筛选控件 + 计数 chips + 空态一句话；抽屉没有标题，只有控件。',
    func: '区域/对象/方向/SxFy/内容过滤、暂停、清空、重置都在，能力完整。',
    fix: [
      '加一个「消息监控」标题栏 + 关闭按钮，当前只有一排筛选控件，用户不知道这块是什么。',
      '两行筛选压到一行（区域+对象+方向+搜索），暂停/清空收进「⋯」。',
      '抽屉展开时主内容被压到 980px，表格出现横向截断；建议抽屉覆盖而非挤压，或给表格加横向滚动提示。',
    ],
  },
  {
    id: 'm-svid', title: '新增变量（SVID）', img: '50b-svid-modal-clean.png', sev: 'P1',
    layout: '620px 宽单列，7 个字段 + 「值定义」区块；短数字字段（offset、小数位、min、max）也占满 500px 宽，行距 36px。',
    func: '所属/offset/槽位名/类型/单位/小数位/min/max + 值定义四选一；但值定义区两个下拉都没有标签，第二个下拉（「默认（运行期内置）」）含义不明。',
    fix: [
      'offset、小数位、min/max 改成两列短字段；类型选择补中文说明（F4 · 4 字节浮点）。',
      '值定义区补标签：「取值方式」+「未配置时的默认来源」，并给出当前生效值的实时预览。',
      'min/max 与其他中文标签统一大小写风格（当前是小写英文）。',
    ],
  },
  {
    id: 'm-svid-clip', title: '弹窗与监控抽屉的层级冲突', img: '50d-svid-modal-drawer-clip.png', sev: 'P0',
    layout: '监控抽屉展开时打开弹窗：弹窗居中于内容列（而非视口），底部按钮落在抽屉下方。',
    func: '实测弹窗「取消」按钮中心点的 elementFromPoint 命中 monitor-list，即按钮被抽屉盖住、点不到。弹窗 DOM 挂在 panel-body 内（未 portal 到 body），层级被抽屉所在层叠上下文压住。',
    fix: [
      'Modal 用 createPortal 挂到 document.body，并统一 z-index 阶梯：抽屉 45 < 遮罩 80 < 弹窗 90 < toast 100。',
      '抽屉展开时禁用「弹窗居中于内容列」的定位，改为视口居中。',
      '补一条 e2e：抽屉展开 → 打开任意弹窗 → 断言底部按钮可点击。',
    ],
  },
  {
    id: 'm-context', title: '新增晶圆属性', img: '51-modal-context-item.png', sev: 'P1',
    layout: '620px 宽；值定义区块里「按片 W03」小徽标 + 下拉 + 四个窄字段（模板 150px / 片号起点 90px / 每几片进一 90px）。',
    func: '支持按片 pattern 与步进；但没有生成结果预览，用户无法确认 W{03} 到底产出什么。',
    fix: [
      '下方加一行等宽预览：「W001 · W002 · W003 →（每 25 片进一）W026」，配错立刻可见。',
      '「写法 / 模板 / 片号起点 / 每几片进一」四个标签统一为「格式 / 起始片号 / 步进」这类直白说法。',
      '去掉不明所以的「按片 W03」徽标，或改成区块标题。',
    ],
  },
  {
    id: 'm-host', title: '新建主机', img: '60-modal-create-host.png', sev: 'P1',
    layout: '380px 宽，9 行，标签右对齐；字段很宽但内容很短（Device ID 0）。',
    func: '模板套用、快速链接设备、目标/端口/DeviceID、立即连接、区域都在；「快速链接设备」的「链接」用词错误。',
    fix: [
      '「快速链接设备」→「快速连接已有设备」；「批量链接/断开链接」同步改为连接。',
      '目标地址与端口合并为「目标 127.0.0.1:5000」一个字段（解析后回填），减少一次输入。',
      '名称给默认值（host-1），避免空名提交。',
    ],
  },
  {
    id: 'm-trace', title: '新增 Trace 方案', img: '61-modal-trace-plan.png', sev: 'P1',
    layout: '390px 宽；TRID / 采样周期 / 总样本数 三个输入框不等宽挤在一行，「每样本数」单独占一行成为孤行。',
    func: '字段齐全，但采集变量是手输「1001, 1003」——没有 SVID 选择器；帮助文案说 DSPER 是 hhmmss 六位数字，字段标签却写「采样周期 ms」，两者矛盾。',
    fix: [
      '采集变量改成多选（从设备目录 SVID 列表勾选），保留「高级：手输」入口。',
      '统一 DSPER 语义：要么标签写「采样周期（ms）· 自动换算为 hhmmss」，要么直接用 hhmmss 输入。',
      '四个数值字段用 2×2 网格，去掉孤行。',
    ],
  },
  {
    id: 'm-collect', title: '新增采集配置', img: '62-modal-collect-plan.png', sev: 'P1',
    layout: '390px 宽；顶部一条整宽浅灰条「无法读取本机设备目录，改为手动输入」，无图标、无标题，看起来像禁用输入框。',
    func: '启动/停止 CEID、周期、变量可配；CEID 与变量都是手输。',
    fix: [
      '把灰条改成带图标的内联提示（warning 色），并给「重试读取目录」入口。',
      '启动/停止 CEID 用目录下拉（含 CEID + 槽位名），变量用多选。',
      '「周期 ms」与其他数值字段对齐成 2 列。',
    ],
  },
  {
    id: 'm-preset', title: '编辑报文模板', img: '63-modal-preset-edit.png', sev: 'P1',
    layout: '640px 宽；名称/分组/回复 一行，但「描述」文本域只有约 92px 宽、2 行高，明显是栅格错位；SML 编辑器 300px 高，无行号无高亮。',
    func: '名称、分组、回复、描述、SML 都可改；分组是自由文本（应为受控选项），SML 无校验按钮。',
    fix: [
      '修描述字段宽度（应与其他字段同宽，2–3 行高）。',
      '分组改为下拉（复用已有分组值），避免同义分组被写成多个。',
      'SML 编辑器加行号、语法高亮与「校验 SML」按钮（解析失败要能就地报错）。',
    ],
  },
  {
    id: 'm-host-tpl', title: '新建主机模板', img: '70-modal-host-template.png', sev: 'P2',
    layout: '650px 宽；模板名与说明同行（说明仅 100px 宽），下面两个方案区块各带一个右对齐「新增」按钮。',
    func: '只存 Trace 与采集方案，语义正确；但一打开就显示红色「至少添加一份…才能保存」——用户还没做任何操作就先看到错误。',
    fix: [
      '错误提示改为「保存时」才出现；初始用中性说明「先添加一份方案」。',
      '说明输入框拉宽或换行；「新增 Trace 方案 / 新增采集方案」按钮与区块标题同侧对齐。',
    ],
  },
  {
    id: 'm-param-tpl', title: '新建参数模板', img: '72-modal-param-template.png', sev: 'P1',
    layout: '620px 宽；三行栅格里控件宽度差异很大（周期 60px、变换 330px、曲线下拉 130px），标签与控件不在同一列，读起来像散落的控件。',
    func: '曲线类型（sine/pwl/constant…）、最小值/最大值、周期/相位、变换表达式都能配；但没有任何曲线预览——一个「画曲线」的功能却看不见曲线。',
    fix: [
      '改成「基本（名称/说明）→ 曲线（类型 + 参数）→ 高级（变换）」三段，每段两列对齐。',
      '右侧加 160px 高的实时曲线预览，参数一改就重画。',
      '「曲线 sine 片内相位」与「曲线（随时间变化）」两个标签重复，删一个。',
    ],
  },
];

const sevLabel = { P0: 'P0 阻塞', P1: 'P1 重要', P2: 'P2 打磨' };

function card(item, kind) {
  return `
  <article class="card" id="${item.id}">
    <header class="card-head">
      <div>
        <h3>${item.title}</h3>
        ${item.route ? `<code>${item.route}</code>` : ''}
      </div>
      <span class="sev sev-${item.sev}">${sevLabel[item.sev]}</span>
    </header>
    <div class="card-body">
      <figure>
        <a href="screens/${item.img}" target="_blank" rel="noopener">
          <img src="screens/${item.img}" alt="${item.title} 截图" loading="lazy" />
        </a>
        <figcaption>点击图片查看原尺寸（1600×1000）</figcaption>
      </figure>
      <div class="notes">
        <p><b>布局</b>${item.layout}</p>
        <p><b>功能</b>${item.func}</p>
        <div class="fixes"><b>修改意见</b><ul>${item.fix.map((f) => `<li>${f}</li>`).join('')}</ul></div>
      </div>
    </div>
  </article>`;
}

const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>SECS 模拟器 · 界面设计评审报告</title>
<style>
  :root {
    --ink:#0c1420; --fg:#26313f; --muted:#5f6f85; --line:#e2e8f0; --line-soft:#eef2f7;
    --bg:#f4f7fa; --surface:#fff; --accent:#0f766e; --accent-soft:#e7f4f2;
    --p0:#b42318; --p0-bg:#fdecea; --p1:#b54708; --p1-bg:#fdf3e3; --p2:#475467; --p2-bg:#eef1f5;
  }
  * { box-sizing: border-box; }
  body {
    margin:0; background:var(--bg); color:var(--fg);
    font: 14px/1.7 -apple-system, "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  a { color: var(--accent); }
  code { font-family: ui-monospace, "Cascadia Mono", Consolas, monospace; font-size: 12px; color: var(--muted); }
  .wrap { display:grid; grid-template-columns: 236px minmax(0,1fr); max-width: 1440px; margin: 0 auto; }

  aside {
    position: sticky; top: 0; align-self: start; height: 100vh; overflow:auto;
    padding: 28px 18px 40px 24px; border-right: 1px solid var(--line); background: #fbfcfe;
  }
  aside .brand { font-weight: 700; font-size: 13px; letter-spacing:.02em; color: var(--ink); }
  aside .sub { font-size: 11.5px; color: var(--muted); margin: 2px 0 18px; }
  aside h4 { font-size: 11px; text-transform: uppercase; letter-spacing: .08em; color: var(--muted); margin: 18px 0 6px; }
  aside a { display:block; padding: 4px 8px; margin-left:-8px; border-radius:6px; color: var(--fg); text-decoration:none; font-size:12.5px; }
  aside a:hover { background: var(--accent-soft); color: var(--accent); }

  main { padding: 40px 48px 80px; min-width: 0; }

  .hero { border-bottom: 1px solid var(--line); padding-bottom: 28px; margin-bottom: 32px; }
  .kicker { font-size: 12px; letter-spacing:.14em; text-transform: uppercase; color: var(--accent); font-weight: 600; }
  h1 { font-size: 30px; line-height: 1.3; margin: 8px 0 10px; color: var(--ink); letter-spacing: -0.01em; }
  .lede { font-size: 15px; color: var(--muted); max-width: 74ch; margin: 0; }
  .meta { display:flex; gap: 22px; flex-wrap: wrap; margin-top: 18px; font-size: 12px; color: var(--muted); }
  .meta b { color: var(--fg); font-weight:600; }

  h2 { font-size: 20px; margin: 44px 0 14px; color: var(--ink); letter-spacing:-0.01em; }
  h2 .num { color: var(--accent); font-weight: 700; margin-right: 8px; }
  h3 { font-size: 15.5px; margin: 0; color: var(--ink); }
  p { margin: 0 0 10px; }

  .grid2 { display:grid; grid-template-columns: 1fr 1fr; gap: 14px; }
  .box { background: var(--surface); border:1px solid var(--line); border-radius: 10px; padding: 16px 18px; }
  .box h4 { margin:0 0 8px; font-size: 13px; color: var(--ink); }
  .box p { margin:0; font-size: 12.8px; color: var(--muted); }
  .box ul { margin: 6px 0 0; padding-left: 18px; font-size: 12.8px; color: var(--muted); }
  .box li { margin-bottom: 4px; }

  table { width:100%; border-collapse: collapse; background: var(--surface); border:1px solid var(--line); border-radius:10px; overflow:hidden; font-size: 12.8px; }
  th, td { text-align:left; padding: 9px 12px; border-bottom: 1px solid var(--line-soft); vertical-align: top; }
  th { background:#f7f9fc; font-weight:600; color: var(--muted); font-size: 11.5px; letter-spacing:.02em; }
  tr:last-child td { border-bottom:none; }
  td.num { font-variant-numeric: tabular-nums; white-space: nowrap; }

  .sev { display:inline-block; padding: 1px 8px; border-radius: 999px; font-size: 11px; font-weight:600; white-space:nowrap; }
  .sev-P0 { color: var(--p0); background: var(--p0-bg); }
  .sev-P1 { color: var(--p1); background: var(--p1-bg); }
  .sev-P2 { color: var(--p2); background: var(--p2-bg); }

  .card { background: var(--surface); border:1px solid var(--line); border-radius: 12px; margin: 16px 0 22px; overflow:hidden; scroll-margin-top: 20px; }
  .card-head { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; padding: 14px 18px; border-bottom:1px solid var(--line-soft); background:#fcfdff; }
  .card-head code { display:block; margin-top:2px; }
  .card-body { display:grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); gap: 20px; padding: 16px 18px 18px; }
  figure { margin:0; }
  figure a { display:block; }
  figure img { width:100%; height:auto; display:block; border:1px solid var(--line); border-radius:8px; background:#fff; box-shadow: 0 1px 2px rgba(16,24,40,.05); }
  figcaption { font-size: 11px; color: var(--muted); margin-top: 6px; }
  .notes p { font-size: 12.9px; margin: 0 0 9px; color: var(--fg); }
  .notes b { display:inline-block; min-width: 44px; margin-right: 6px; color: var(--accent); font-weight:600; font-size: 11.5px; letter-spacing:.04em; }
  .fixes { margin-top: 4px; }
  .fixes > b { color: var(--accent); font-size: 11.5px; letter-spacing:.04em; }
  .fixes ul { margin: 4px 0 0; padding-left: 16px; }
  .fixes li { font-size: 12.7px; color: var(--fg); margin-bottom: 5px; }
  .fixes li::marker { color: var(--accent); }

  .score { display:grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 18px 0 6px; }
  .score .box { display:flex; flex-direction: column; gap: 4px; }
  .score .v { font-size: 22px; font-weight: 700; color: var(--ink); font-variant-numeric: tabular-nums; }
  .score .l { font-size: 11.5px; color: var(--muted); }
  .bar { height: 4px; background: var(--line-soft); border-radius: 4px; overflow:hidden; margin-top: 6px; }
  .bar > i { display:block; height:100%; background: var(--accent); }

  .quote { border-left: 3px solid var(--accent); padding: 2px 0 2px 14px; color: var(--fg); font-size: 14px; margin: 14px 0; }

  .tag { display:inline-block; font-size: 11px; padding: 1px 7px; border-radius: 5px; background: var(--accent-soft); color: var(--accent); margin-right:6px; }

  footer { margin-top: 56px; padding-top: 18px; border-top: 1px solid var(--line); font-size: 11.5px; color: var(--muted); }

  @media (max-width: 1100px) {
    .wrap { grid-template-columns: 1fr; }
    aside { position: static; height:auto; border-right:none; border-bottom:1px solid var(--line); }
    main { padding: 24px 20px 60px; }
    .card-body { grid-template-columns: 1fr; }
    .grid2, .score { grid-template-columns: 1fr; }
  }
  @media print {
    aside { display:none; }
    .wrap { grid-template-columns: 1fr; }
    body { background:#fff; }
    .card { break-inside: avoid; }
  }
</style>
</head>
<body>
<div class="wrap">
<aside>
  <div class="brand">SECS 模拟器 · 界面评审</div>
  <div class="sub">v3.2.0 · 2026-11-18</div>
  <h4>结论</h4>
  <a href="#summary">总体判断与评分</a>
  <a href="#roles">三个视角的结论</a>
  <a href="#issues">问题清单（P0/P1/P2）</a>
  <a href="#plan">修改路线</a>
  <h4>逐页走查 · 27 页</h4>
  <a href="#sec-global">全局框架 / 设备清单</a>
  <a href="#sec-device-cfg">设备 · 配置区（11 页）</a>
  <a href="#sec-device-run">设备 · 运行区（3 页）</a>
  <a href="#sec-host">主机（8 页）</a>
  <a href="#sec-tools">独立工具（4 页）</a>
  <h4>弹窗走查 · 16 个</h4>
  <a href="#sec-modals">弹窗清单</a>
  <h4>附录</h4>
  <a href="#appendix">术语 / 规范 / 边界</a>
</aside>

<main>
  <div class="hero">
    <div class="kicker">Design &amp; Product Review</div>
    <h1>SECS 模拟器 Web 控制台：界面评审与修改意见</h1>
    <p class="lede">对当前应用的全部 27 个页面与 16 个弹窗做了逐屏走查，从产品负责人、设计师、产品经理三个视角，评估布局排版的合理性与功能完善度，并给出按优先级排序的修改意见。</p>
    <div class="meta">
      <span><b>走查范围</b> 27 页 / 16 弹窗</span>
      <span><b>视口</b> 1600×1000、1280×800</span>
      <span><b>环境</b> 构建产物 + mock 后端</span>
      <span><b>截图</b> 全部随报告附上</span>
    </div>
  </div>

  <h2 id="summary"><span class="num">01</span>总体判断</h2>
  <p class="quote">信息架构是这套界面最强的部分——「配置区 / 运行区」两段式把「定义什么」和「现在怎么样」彻底分开，配置区按领域切页、运行区只留要动手的页，这个骨架是专业的、可持续的。真正拖后腿的不是结构，而是<b>精细度</b>：表单页大面积空转的留白、按钮位置各页不一、术语在「连接/链接」之间摇摆、空态重复三遍、弹窗被抽屉盖住点不到。这些不是设计品味问题，是会直接影响交付质量的工程问题。</p>

  <div class="score">
    <div class="box"><span class="l">信息架构与导航</span><span class="v">8.0</span><div class="bar"><i style="width:80%"></i></div></div>
    <div class="box"><span class="l">页面布局与留白</span><span class="v">6.0</span><div class="bar"><i style="width:60%"></i></div></div>
    <div class="box"><span class="l">排版与视觉层级</span><span class="v">6.5</span><div class="bar"><i style="width:65%"></i></div></div>
    <div class="box"><span class="l">一致性（按钮/术语/空态）</span><span class="v">6.0</span><div class="bar"><i style="width:60%"></i></div></div>
    <div class="box"><span class="l">交互与流程闭环</span><span class="v">7.0</span><div class="bar"><i style="width:70%"></i></div></div>
    <div class="box"><span class="l">功能完整度</span><span class="v">7.0</span><div class="bar"><i style="width:70%"></i></div></div>
  </div>
  <p style="font-size:12.5px;color:var(--muted);margin-top:10px">综合 <b style="color:var(--ink)">6.8 / 10</b>。结论：<b style="color:var(--ink)">内部工程师可用，对外交付前需要一轮「一致性 + 空态 + 细节兜底」的打磨</b>。骨架不用动，问题集中在组件层与文案层。</p>

  <div class="grid2" style="margin-top:18px">
    <div class="box"><h4>做得好的三件事</h4><ul>
      <li>两段式信息架构 + 页签体系，多设备/多主机并行操作不迷路。</li>
      <li>危险操作的后果写清楚了（删除设备、保存连接参数会重启会话）。</li>
      <li>专业信息没有为了「简洁」被砍掉：执行台账带 SVID before→after、Trace/采集方案库与下发分离、报警风暴压测都在。</li>
    </ul></div>
    <div class="box"><h4>最该先修的三件事</h4><ul>
      <li><b>弹窗被监控抽屉遮挡、按钮点不到</b>（P0，功能性缺陷）。</li>
      <li><b>表单页单列排到底、右侧半屏空白</b>（P0，影响「专业感」的第一印象）。</li>
      <li><b>术语与状态文案混乱</b>：链接/连接、停止/未连接，主机状态显示「链接」（P0，语义错误）。</li>
    </ul></div>
  </div>

  <h2 id="roles"><span class="num">02</span>三个视角的结论</h2>

  <h3 style="margin-top:16px">作为产品负责人</h3>
  <p>产品的定位是清楚的：给设备/主机两侧做可观测、可编排、可压测的 SECS/GEM 沙盘。功能覆盖度对得起这个定位——SVID 值引擎、CEID/RPT、工艺周期、事件计划、Trace/采集、报警风暴、执行台账，一条链路是通的。我认可这套信息架构，也认可「配置区只定义、运行区只动手」的边界。</p>
  <p>但对外交付前，有三件事必须解决：<b>①</b> 概览页几乎没有信息量，客户第一眼看到的是 6 行键值表和半屏空白，无法建立「这台设备能干什么」的认知；<b>②</b> 版本历史只能看不能回滚/对比，等于承诺了「回看」却没兑现；<b>③</b> 报警只有置位/清除入口，没有「当前活动报警」清单——工作台上那个红色「1」点不进去，这是运营视角的硬缺口。</p>

  <h3>作为设计师</h3>
  <p>视觉语言是统一的（信号青 + 石墨侧栏 + 细线卡片），密度也符合工业软件的预期，这一点比多数同类工具做得好。问题在<b>空间分配</b>：大量表单页采用「单列 + 固定 280px 输入框」，13 个连接字段一路排到屏幕外，右侧半屏永久空转；反过来，图表页的图只有 180px 高，主角被压成配角。留白不是均匀的，而是「该紧的地方松、该松的地方紧」。</p>
  <p>其次是<b>层级</b>：全站用了 10 档以上字号（10 / 10.5 / 11 / 11.5 / 11.8 / 12 / 12.5 / 12.8 / 13 / 14 / 14.5 / 15 / 18px），正文 11.5px、说明 10.5px，长时间读数很吃力；分组标题（如「HSMS 超时」「文字与间距」）只有 10px 灰字，撑不起分组的作用。建议收敛到 5 档：11（辅助）/ 12（正文）/ 13（强调）/ 16（区块标题）/ 20（页面标题）。</p>

  <h3>作为产品经理</h3>
  <p>流程上最扎眼的是<b>同一件事有多个入口、同一个状态有多个说法</b>：批量启停既有工具栏「批量启动/停止」又有右下「全部启动/停止」；「执行明细」在事件执行和工艺周期两页重复；主机状态叫「链接」、动作叫「批量链接」、别处又叫「连接设置」。用户会在「到底哪个是权威入口」上消耗注意力。</p>
  <p>边界情况处理有亮点（启动态禁止改结构、发送结果区分「未请求回复/超时/耗时」），但<b>字段缺失时的兜底不足</b>：发送结果直接渲染出「SundefinedFundefined」「耗时 undefined ms」。这类问题在演示时非常致命。空态也需要收敛——数据采集运行页一屏出现了三种「没有配置」的表达。</p>

  <h2 id="issues"><span class="num">03</span>问题清单</h2>
  <p style="font-size:12.8px;color:var(--muted)">按影响面排序。<b>P0</b> = 影响可用性或语义正确性，交付前必修；<b>P1</b> = 明显影响效率或专业观感，建议本迭代内修；<b>P2</b> = 打磨项。</p>
  <table>
    <thead><tr><th style="width:64px">级别</th><th style="width:190px">问题</th><th>现象与影响</th><th style="width:150px">位置</th></tr></thead>
    <tbody>
      <tr><td><span class="sev sev-P0">P0</span></td><td>弹窗被监控抽屉遮挡，按钮点不到</td><td>抽屉展开时打开任意弹窗，弹窗底部按钮落在抽屉下层；实测「取消」中心点命中 monitor-list。Modal 未 portal 到 body，被父级层叠上下文压住。</td><td>全局 / 所有弹窗</td></tr>
      <tr><td><span class="sev sev-P0">P0</span></td><td>表单页单列到底、右侧半屏空白</td><td>连接页 13 个字段纵向排满，输入框固定 280px，右侧 55% 卡片宽度空着；需滚动才能看全一屏能放下的内容。</td><td>设备 · 连接</td></tr>
      <tr><td><span class="sev sev-P0">P0</span></td><td>概览页信息量过低且数据源不一致</td><td>6 行键值表 + 半屏空白；停止态「单元数」显示「—」，但配置里有 1 个单元（取自会话而非配置）。</td><td>设备 · 概览</td></tr>
      <tr><td><span class="sev sev-P0">P0</span></td><td>术语与状态文案混乱</td><td>连接/链接混用（批量链接、快速链接设备、断开链接）；主机状态 CONNECTED 显示「链接」、DISCONNECTED 显示「停止」。</td><td>主机清单 / 新建主机 / 侧栏</td></tr>
      <tr><td><span class="sev sev-P0">P0</span></td><td>发送结果位置错乱 + undefined 外泄</td><td>结果区落在「事件上报压测」下方，离编辑器约 500px；缺字段时渲染「SundefinedFundefined」「耗时 undefined ms」，且与 toast 重复播报。</td><td>设备 · 手动与压测</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>页面级操作位置不统一</td><td>所有配置子页「重新载入/校验/保存」在卡片右上，唯独「配置原文」在卡片最底部。</td><td>设备 · 配置原文</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>空态重复且样式不一</td><td>数据采集运行页一屏三种「没有配置」；Trace 运行页两块空态卡叠加；采集配置页空态只占顶部 75px，下面 700px 空白。</td><td>主机 · 运行区 / 配置区</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>字号阶梯过碎、正文偏小</td><td>10 档以上字号，正文 11.5px、说明 10.5px；分组标题 10px 灰字，撑不起层级。</td><td>全局</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>关键字段靠手输</td><td>Trace/采集方案里 SVID、CEID 都是手输数字；没有从设备目录选择的多选控件，极易配错。</td><td>Trace 设置 / 采集设置弹窗</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>列表与预览数据源不一致</td><td>事件计划页列表说「还没有 events 段」，下方预览却画出「计划到触发 (1) · beat」。</td><td>设备 · 事件计划</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>版本历史只读不可回滚/对比</td><td>5 列表格，无「对比 / 回滚 / 变更摘要」，与「版本历史与回看」的定位不符。</td><td>设备 · 版本历史</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>没有「当前活动报警」清单</td><td>工作台显示活动报警 1，但全站无处查看是哪条报警；只能去监控抽屉里翻 S5F1 报文。</td><td>设备 / 工作台</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>重复入口</td><td>批量启停 vs 全部启停；执行明细在事件执行与工艺周期两页重复；命令面板同一设备三行。</td><td>设备运行区 / 命令面板</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>主机消息发送缺结果回显</td><td>设备侧有回复/超时/耗时，主机侧没有，能力不对等。</td><td>主机 · 消息发送</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>编辑模型不统一</td><td>变量/常量/晶圆属性用弹窗，CEID/RPT 用行内编辑；同一页两种心智。</td><td>设备 · 事件与报告</td></tr>
      <tr><td><span class="sev sev-P1">P1</span></td><td>弹窗内栅格错位</td><td>报文模板「描述」文本域只有约 92px 宽；参数模板三行控件宽度差异大、标签不对齐。</td><td>报文模板 / 参数模板弹窗</td></tr>
      <tr><td><span class="sev sev-P2">P2</span></td><td>表格噪声</td><td>每列表头常显 ⇅；操作列三枚 11px 文字按钮；「形式」「实例 ID」等空数据列占宽。</td><td>多个列表页</td></tr>
      <tr><td><span class="sev sev-P2">P2</span></td><td>颜色语义冲突</td><td>「停止」用红色（与报警红同色系），正常停机看起来像故障。</td><td>侧栏 / 设备清单</td></tr>
      <tr><td><span class="sev sev-P2">P2</span></td><td>无标签图标</td><td>工具栏右侧导入/导出图标无文字、无 tooltip。</td><td>设备清单 / 审计</td></tr>
      <tr><td><span class="sev sev-P2">P2</span></td><td>1280 宽下工具栏换行错位</td><td>主按钮与批量按钮掉到第二行、居中悬空，与第一行左对齐的搜索框脱节。</td><td>设备清单</td></tr>
      <tr><td><span class="sev sev-P2">P2</span></td><td>摘要丢失步进信息</td><td>晶圆属性摘要「按片 · LOT{03}」丢掉了 every=25，会被读成每片换批号。</td><td>设备 · 晶圆属性</td></tr>
    </tbody>
  </table>

  <h2 id="plan"><span class="num">04</span>修改路线</h2>
  <div class="grid2">
    <div class="box"><h4>第一批 · 交付阻塞（1–2 天）</h4><ul>
      <li>Modal 改 createPortal + 统一 z-index 阶梯（45/80/90/100），补一条 e2e。</li>
      <li>连接页改 2–3 列栅格；分组加分隔；T3–T8 收进「高级」。</li>
      <li>概览页重做：4 张摘要卡 + 身份表，静态值一律读配置。</li>
      <li>术语统一：连接（不用链接）、已连接/未连接（不用链接/停止）。</li>
      <li>发送结果区上移到编辑器下方；所有字段渲染加「—」兜底。</li>
    </ul></div>
    <div class="box"><h4>第二批 · 一致性（3–5 天）</h4><ul>
      <li>抽一套空态组件（图标 + 原因 + CTA），替换全站 8 处空态。</li>
      <li>页面级操作条统一到卡片右上；配置原文页对齐。</li>
      <li>字号收敛到 5 档；分组标题升到 12–13px。</li>
      <li>表格操作列改图标 + hover 显示；排序图标改 hover 出现。</li>
      <li>批量启停/全部启停二选一；执行明细只留一处。</li>
    </ul></div>
    <div class="box"><h4>第三批 · 能力补齐（1–2 周）</h4><ul>
      <li>SVID/CEID 选择器（多选，来自设备目录），保留手输为高级。</li>
      <li>版本历史：对比 + 回滚 + 变更摘要。</li>
      <li>活动报警清单（设备页签 + 工作台可下钻）。</li>
      <li>主机消息发送补结果回显，与设备侧共用 SendPanel。</li>
      <li>晶圆属性/参数模板加实时预览。</li>
    </ul></div>
    <div class="box"><h4>规范沉淀（并行）</h4><ul>
      <li>术语表：连接/链路/会话、设备/单元、事件/报告、置位/清除。</li>
      <li>表单规范：标签左对齐、2 列栅格、短字段不占满宽、分组必带标题。</li>
      <li>按钮规范：一屏一个主色按钮；危险动作用描边红，不用实心主色。</li>
      <li>弹窗规范：宽度 400/620/920 三档，底部操作固定，长内容内部滚动。</li>
    </ul></div>
  </div>

  <h2 id="sec-global"><span class="num">05</span>逐页走查 · 全局框架</h2>
  ${card(pages[0], 'page')}

  <h2 id="sec-device-cfg"><span class="num">06</span>逐页走查 · 设备配置区（11 页）</h2>
  ${pages.slice(1, 12).map((p) => card(p)).join('')}

  <h2 id="sec-device-run"><span class="num">07</span>逐页走查 · 设备运行区（3 页）</h2>
  ${pages.slice(12, 15).map((p) => card(p)).join('')}

  <h2 id="sec-host"><span class="num">08</span>逐页走查 · 主机（8 页）</h2>
  ${pages.slice(15, 23).map((p) => card(p)).join('')}

  <h2 id="sec-tools"><span class="num">09</span>逐页走查 · 独立工具（4 页）</h2>
  ${pages.slice(23).map((p) => card(p)).join('')}

  <h2 id="sec-modals"><span class="num">10</span>弹窗走查（16 个）</h2>
  <p style="font-size:12.8px;color:var(--muted)">弹窗宽度目前有 360 / 380 / 390 / 400 / 480 / 620 / 640 / 650 / 920 / 1240px 多种，建议收敛到 400（表单）/ 620（多字段）/ 920（编辑器）三档。下列顺序按使用频次排列。</p>
  ${modals.map((m) => card(m, 'modal')).join('')}

  <h2 id="appendix"><span class="num">11</span>附录</h2>
  <div class="grid2">
    <div class="box"><h4>术语统一建议</h4><ul>
      <li>连接 / 已连接 / 未连接 / 重连中（停用「链接」「停止」描述链路）。</li>
      <li>设备 = 被模拟的机台；单元 = unit；会话 = 一次运行的实例。</li>
      <li>事件（CEID）/ 报告（RPT）/ 变量（SVID）/ 常量（ECV）/ 晶圆属性（CTX）。</li>
      <li>置位 / 清除（报警）；下发 / 启动 / 停止（Trace、采集）。</li>
    </ul></div>
    <div class="box"><h4>本次未覆盖 / 需确认</h4><ul>
      <li>深色主题未逐页走查（浅色为主，深色仅抽查全局框架）。</li>
      <li>走查数据来自 mock 后端，空态占比偏高；真实数据下的列表密度需再看一次。</li>
      <li>设备模板「没有可复制的源模板」在 mock 下必现，真实后端有内置 bare 模板，需确认空库时的兜底。</li>
      <li>桌面壳（Tauri）窗口控制按钮、系统托盘未在浏览器环境验证。</li>
      <li>事件计划页「列表为空但预览有计划」疑为 mock 数据不一致，需真实后端复核。</li>
    </ul></div>
  </div>

  <footer>
    走查方法：Vite 构建产物 + mock 后端（scripts/mock-ui-server.mjs）在 Chromium 中逐路由截图；交互类问题（弹窗遮挡）通过 elementFromPoint 命中测试确认。截图与原始记录见同目录 <code>screens/</code>、<code>pages.txt</code>、<code>modals3.txt</code>。
  </footer>
</main>
</div>
</body>
</html>`;

writeFileSync(OUT, html, 'utf8');
console.log('written', OUT, html.length);
