/**
 * 与后端 HTTP API 的契约（纯类型）。
 *
 * <p>类型故意手写而不是生成，先保持零依赖。这里只放"形状"：
 * 视图/输入类型、`ApiClient` 接口与少量常量；请求实现见 {@code api/base.ts}
 * 与 {@code api/*.ts} 的域模块。</p>
 */

/** 后端 health 里带回的身份；内部工具不做权限门槛，界面不再按它禁用任何操作。 */
export type ApiRole = 'viewer' | 'operator' | 'admin';

/** 部署期覆盖：多台设备复用一份设备配置时，逐台不同的连接标识。 */
export interface SessionOverrides {
  sessionId?: string;
  bindHost?: string;
  port?: number;
  deviceId?: number;
}

/** 批量新建请求。 */
export interface SessionBulkParams extends SessionOverrides {
  config: string;
  /** 1..100 */
  count: number;
  /** 会话 id 前缀，第 i 台为 `${前缀}i`。 */
  sessionIdPrefix: string;
  /** 第 i 台端口 = portBase + i - 1；0 表示都用配置里的端口（会冲突，仅供 demo 会话）。 */
  portBase: number;
  /** 第 i 台 device-id = deviceIdBase + i - 1。 */
  deviceIdBase: number;
}

/** 批量新建结果。 */
export interface SessionBulkResult {
  created: string[];
  failed: { index: number; error: string }[];
}

export interface Counters {
  emitted?: number;
  rejected?: number;
  skipped?: number;
  wbitDropped?: number;
  traceWbitDropped?: number;
  terminalMessages?: number;
  alarmsSent?: number;
  alarmDropped?: number;
  activeAlarms?: number;
  traceFrames?: number;
  activeTraces?: number;
  hostReports?: number;
  hostLinks?: number;
  /** 设备回 S9Fx 的次数（主机发了设备不认识的报文）。 */
  s9Replies?: number;
}

export interface SessionSummary {
  id: string;
  state: string;
  controlState?: string;
  device: string;
  units: number;
  lastError: string;
  /** 装载时用的配置文件名;演示会话等无来源时为空串(配置页自动打开用)。 */
  config?: string;
  counters: Counters;
}

export interface Health {
  ok: boolean;
  version: string;
  mode: string;
  sessions?: number;
  uptimeMs?: number;
  /** 后端启用访问控制时返回；开放访问时是匿名身份。 */
  user?: string;
  role?: ApiRole;
}

/** SSE 推送的报文事件（与后端 MessageEvent 对应）。 */
export interface WireMessage {
  /** 后端单调序号；历史补拉与 SSE 用它去重（旧日志行可能没有）。 */
  seq?: number;
  sessionId: string;
  /** 主机模拟实例 id；空/缺省 = 这条报文属于设备会话本身。 */
  hostId?: string;
  /** 视角：device 设备侧（默认）/ host 主机侧；两侧各记一份，按视角各读各的。 */
  perspective?: 'device' | 'host';
  direction: 'in' | 'out';
  stream: number;
  function: number;
  wbit: boolean;
  body: string;
  at: number;
}

/** 审计记录。 */
export interface AuditEntryView {
  at: number;
  actor: string;
  role: string;
  action: string;
  target: string;
  detail: string;
}

/** 事件编排计划（运行时视图）。 */
export interface EventPlanView {
  id: string;
  ceid: number;
  /** 归属单元：由所引用 CEID 的归属反查（后端计算，只读）。 */
  owner?: string;
  enabled: boolean;
  /**
   * 运行态：{@code stopped} 未启用 / {@code waiting} 已启用但发不出去 /
   * {@code running} 正在发。
   *
   * <p>和 {@code enabled} 分开：计划启动了但设备没在线（严格 GEM 下不能上报）时
   * 是 {@code waiting}，界面不能显示成"运行中"。</p>
   */
  status?: 'stopped' | 'waiting' | 'running';
  /** 未运行的原因：offline / ceid-disabled。 */
  statusReason?: string;
  triggerType: string;
  atMs?: number;
  periodMs?: number;
  jitterMs?: number;
  expression?: string;
  debounceMs?: number;
  sourcePlan?: string;
  delayMs?: number;
  effects?: EventEffectView[];
}

/** 事件效果(schema v6;op 与字段的对应关系同后端 EventEffect)。 */
export interface EventEffectView {
  svid: number;
  op: 'set' | 'add' | 'pulse' | 'shift' | 'resume';
  value?: number;
  delta?: number;
  holdMs?: number;
  decayMs?: number;
}

/** 新增/更新编排计划的输入;triggerType 决定哪些参数生效。 */
export interface EventPlanInput {
  id: string;
  ceid: number;
  triggerType: 'at' | 'periodic' | 'onCondition' | 'afterEvent';
  atMs?: number;
  periodMs?: number;
  jitterMs?: number;
  expression?: string;
  debounceMs?: number;
  sourcePlan?: string;
  delayMs?: number;
  effects?: EventEffectInput[];
}

export type EventEffectInput = EventEffectView;

/** 新增/更新报告的输入。 */
export interface ReportInput {
  id: number;
  slot: string;
  svids: number[];
}

/** 新增/更新采集事件的输入(reports 是绑定的 RPTID 列表)。 */
export interface CeidInput {
  id: number;
  slot: string;
  reports: number[];
}

/** 新增/更新状态变量的输入。 */
export interface SvidInput {
  id: number;
  slot: string;
  type: string;
  owner?: string;
  unit?: string;
}

/** 手动发送 SML 的结果。 */
export interface SentMessageResult {
  ok: boolean;
  stream: number;
  function: number;
  wbit: boolean;
  /** 规范化后的发送内容（与请求原文可能只差空白）。 */
  sentSml: string;
  /** W 位发出但主机未在 T3 内回复。 */
  timedOut: boolean;
  replyStream?: number;
  replyFunction?: number;
  replyWbit?: boolean;
  replySml?: string | null;
  elapsedMs: number;
}

/** 报文历史：最多 limit 条，total 是过滤后的总数。 */
export interface MessagePage {
  messages: WireMessage[];
  total: number;
  limit: number;
  truncated: boolean;
}

/** 报文历史的服务端过滤项:before 是翻旧账的游标(只取早于该时间戳的),stream/function 过滤报文类型。 */
export interface MessageHistoryOptions {
  before?: number;
  stream?: number;
  function?: number;
}

/** 审计的服务端过滤项:action/actor 精确匹配,空串/undefined 表示全部;before 是翻旧账的游标。 */
export interface AuditFilters {
  action?: string;
  actor?: string;
  before?: number;
}

/** 曲线预览：分桶 min/max/avg。 */
export interface PreviewSeries {
  svid: number;
  owner?: string;
  unit?: string;
  min: number[];
  max: number[];
  avg: number[];
}

/** 预览窗口内的一条事件标记（schema v6:运行时=编排重放,草稿=模拟时间轴）。 */
export interface PreviewEventMark {
  at: number;
  ceid: number;
  planId?: string;
}

export interface PreviewData {
  from: number;
  to: number;
  buckets: number;
  bucketMs: number;
  /** 返回的序列；后端预览上限 32 条。 */
  series: PreviewSeries[];
  /** 会话里的 SVID 总数；老后端可能没有。 */
  svidTotal?: number;
  /** 是否只返回了前 32 条。 */
  truncated?: boolean;
  /** 窗口内的事件标记；老后端没有该字段。 */
  events?: PreviewEventMark[];
}

/** 工作台聚合里的一台设备（schema v6 overview 端点）。 */
export interface OverviewRow {
  id: string;
  state: string;
  controlState: string;
  hasPeer: boolean;
  device: string;
  units: number;
  port: number;
  counters: Record<string, number>;
  msgsPerMin: number;
  s6f11PerMin: number;
  lastEventAt: number;
  uptimeMs: number;
  hostSim?: boolean;
}

/** 服务端压测请求（schema v6 burst 端点）。 */
export interface BurstRequest {
  kind: 'message' | 'ceid' | 'alarm';
  count: number;
  intervalMs?: number;
  sml?: string;
  ceid?: number;
  alid?: number;
  text?: string;
  category?: number;
}

export interface BurstResult {
  sent: number;
  failed: number;
  durationMs: number;
  latencyP50: number;
  latencyP95: number;
  latencyMax: number;
}

/**
 * 压测进度/最近一次结果（GET /sessions/{id}/burst）。
 *
 * <p>压测在服务端后台执行，POST 只回 202 受理；这里带 `running` 供界面轮询。</p>
 */
export interface BurstStatus extends Partial<BurstResult> {
  ok: boolean;
  accepted?: boolean;
  running: boolean;
  aborted?: boolean;
  skipped?: number;
  error?: string;
}

/** 主机模拟 S1F3 采集器的配置。 */
export interface CollectorConfigInput {
  startCeid: number;
  stopCeid: number;
  intervalMs: number;
  svids: { id: number; name?: string; unit?: string }[];
}

/** 主机模拟 S1F3 采集器的状态与数据。 */
export interface CollectorState {
  configured: boolean;
  running: boolean;
  mode?: 'local' | 'remote';
  startCeid?: number;
  stopCeid?: number;
  intervalMs?: number;
  svids?: { id: number; name?: string; unit?: string }[];
  samples?: number;
  decodeErrors?: number;
  lastRttMs?: number;
  staleSvids?: number[];
  series?: { svid: number; name?: string; unit?: string; t: number[]; v: number[] }[];
}

/** 主机模拟连接目标;remote 为 true 时连这个地址而不是本机。 */
export interface HostSimTarget {
  host: string;
  port: number;
  deviceId: number;
}

/** 目录里的一个 CEID（绝对 ID + 语义）。 */
export interface CatalogCeid {
  ceid: number;
  owner: string;
  slot: string;
  reports: number[];
  /** 有效定义来自主机（S2F35）还是本地配置。 */
  source?: 'local' | 'host';
  /** 是否被 S2F37 禁用。禁用的事件直接丢弃、不缓存。 */
  enabled?: boolean;
}

/** 目录里的一个报告定义。 */
export interface CatalogReport {
  rptid: number;
  owner: string;
  slot: string;
  svids: number[];
  /** 有效 VID 列表来自主机（S2F33）还是本地配置。 */
  source?: 'local' | 'host';
}

/** 目录里的一个 SVID。 */
export interface CatalogSvid {
  svid: number;
  owner: string;
  slot: string;
  type: string;
  unit: string;
  /** 是否被「设定」钉住(设备页·状态变量);后端目录视图带回,刷新不丢。 */
  pinned?: boolean;
}

/**
 * 设备目录：把"绝对 ID → 语义"的映射摊开。
 *
 * 每个单元有独立的 CEID 号段，所以目录必须按会话取，不能跨设备复用。
 * 报告与事件的来源已按主机覆盖层算好（ADR-0006），看到的就是实际发出去的那份。
 */
export interface Catalog {
  ceids: CatalogCeid[];
  reports: CatalogReport[];
  svids: CatalogSvid[];
  /** 主机定义的报告数 / 链接数。 */
  hostReports?: number;
  hostLinks?: number;
  /** 主机是否把全部事件上报关掉了（S2F37）。 */
  hostAllDisabled?: boolean;
}

export const EMPTY_CATALOG: Catalog = { ceids: [], reports: [], svids: [] };

/** 配置目录里的一个配置文件。 */
export interface ConfigEntry {
  name: string;
  sizeBytes: number;
  modifiedMs: number;
  /** 新建时记录的模板 id；旧配置没有。 */
  template?: string;
}

/** 内置设备模板目录里的一项。 */
export interface ConfigTemplateEntry {
  id: string;
  label: string;
  description: string;
  /** builtin / custom */
  source?: string;
  sizeBytes?: number;
  modifiedMs?: number;
}

/** 一份配置的原文快照:modifiedMs 用于保存时的 If-Match 并发保护。 */
export interface ConfigTextSnapshot {
  text: string;
  modifiedMs: number | null;
}

/** 校验结果：valid 为假时 problems 里是具体哪里错了。 */
export interface ConfigProblems {
  valid: boolean;
  problems: string[];
}

/** 保存结果：状态码 + 问题清单（422 时非空）。 */
export interface ConfigSaveResult {
  status: number;
  problems: string[];
}

/**
 * 后端客户端接口。
 *
 * <p>实现按域拆在 {@code api/sessions.ts}、{@code api/configs.ts}、
 * {@code api/messages.ts}、{@code api/hostsim.ts}，由 {@code api/index.ts}
 * 的 {@code createClient()} 合并成这一个对象。</p>
 */
export interface ApiClient {
  health(): Promise<Health>;
  sessions(): Promise<SessionSummary[]>;
  /** 新建会话；config 为 demo 或配置目录下的文件名。 */
  createSession(config: string, overrides?: SessionOverrides): Promise<number>;
  bulkSessions(params: SessionBulkParams): Promise<SessionBulkResult>;
  /** 断开链路：端口释放、主机连接断开，但设备仍留在列表里可再连（3.0「关闭」语义）。 */
  closeSession(sessionId: string): Promise<number>;
  /** 重新建链（断开态的会话对象复用，模型/钉值/计划启用态原样恢复）。 */
  connectDevice(deviceId: string): Promise<number>;
  /**
   * 手动把设备置为在线 / 离线（GEM 里等价于主机发 S1F17 / S1F15）。
   *
   * <p>只改控制状态，不动链路：离线后 S6F11 / S6F1 立刻停发，在线后按计划恢复。
   * 链路没建立时后端回 409（没有主机就没有"在线"可言）。</p>
   */
  setDeviceOnline(deviceId: string, online: boolean): Promise<number>;
  /** 按配置重载会话。 */
  reloadSession(sessionId: string): Promise<number>;
  trigger(sessionId: string, ceid: number): Promise<number>;
  alarm(sessionId: string, alid: number, set: boolean, text: string, category: number): Promise<number>;
  /** SVID / RPTID / CEID 目录。 */
  catalog(sessionId: string): Promise<Catalog>;
  preview(sessionId: string, fromMs: number, toMs: number, buckets: number, svids?: number[], withEvents?: boolean, planId?: string): Promise<PreviewData>;
  /** 最近审计记录。 */
  audit(limit: number, filters?: AuditFilters): Promise<AuditEntryView[]>;
  /** 事件编排列表。 */
  events(sessionId: string): Promise<EventPlanView[]>;
  /** 运行时启用/禁用一个计划。 */
  setEventEnabled(sessionId: string, planId: string, enabled: boolean): Promise<number>;
  /** 运行时新增编排计划(不写回配置,重载后失效)。返回 HTTP 状态码。 */
  addEventPlan(sessionId: string, plan: EventPlanInput): Promise<number>;
  /** 运行时更新编排计划。 */
  updateEventPlan(sessionId: string, planId: string, plan: EventPlanInput): Promise<number>;
  /** 运行时删除编排计划。 */
  deleteEventPlan(sessionId: string, planId: string): Promise<number>;
  /** 运行时新增报告。 */
  addReport(sessionId: string, report: ReportInput): Promise<number>;
  /** 运行时更新报告。 */
  updateReport(sessionId: string, rptid: number, report: ReportInput): Promise<number>;
  /** 运行时删除报告。 */
  deleteReport(sessionId: string, rptid: number): Promise<number>;
  /** 运行时新增状态变量(默认恒 0 曲线,可用 setSvidValue 钉值)。 */
  addSvid(sessionId: string, svid: SvidInput): Promise<number>;
  /** 运行时更新状态变量。 */
  updateSvid(sessionId: string, svid: number, def: SvidInput): Promise<number>;
  /** 运行时删除状态变量。 */
  deleteSvid(sessionId: string, svid: number): Promise<number>;
  /** 运行时新增采集事件(事件设置页)。 */
  addCeid(sessionId: string, ceid: CeidInput): Promise<number>;
  /** 运行时更新采集事件(符号名/绑定报告)。 */
  updateCeid(sessionId: string, ceid: number, def: CeidInput): Promise<number>;
  /** 运行时删除采集事件。 */
  deleteCeid(sessionId: string, ceid: number): Promise<number>;
  /** 「设定」状态变量的值(钉住,覆盖曲线与事件效果)。 */
  setSvidValue(sessionId: string, svid: number, value: number): Promise<number>;
  /** 取消「设定」。 */
  clearSvidValue(sessionId: string, svid: number): Promise<number>;
  /** 手动发送一条 SML 报文。 */
  sendSml(sessionId: string, sml: string): Promise<SentMessageResult>;
  /** 最近报文历史；session/direction 为空串表示全部。 */
  messages(sessionId: string, direction: string, limit: number, opts?: MessageHistoryOptions): Promise<MessagePage>;
  /** 配置目录列表。 */
  configs(): Promise<ConfigEntry[]>;
  /** 新建一份配置；templateId 缺省是空骨架，其余取内置设备模板目录里的 id。 */
  createConfig(name: string, templateId?: string): Promise<number>;
  /** 内置设备模板目录（新建配置的选型列表）。 */
  configTemplates(): Promise<ConfigTemplateEntry[]>;
  /** 内置 + 自定义模板。 */
  templates(): Promise<ConfigTemplateEntry[]>;
  /** 模板原文。 */
  templateText(id: string): Promise<string | null>;
  /** 新建自定义模板。 */
  createTemplate(id: string, text: string): Promise<number>;
  /** 更新自定义模板。 */
  updateTemplate(id: string, text: string): Promise<number>;
  /** 删除自定义模板。 */
  deleteTemplate(id: string): Promise<number>;
  /** 读一份配置的原文(带 modifiedMs 快照,保存时经 If-Match 带回做并发保护)。 */
  configText(name: string): Promise<ConfigTextSnapshot>;
  /** 只校验不落盘。 */
  validateConfig(name: string, text: string): Promise<ConfigProblems>;
  /** 校验通过后落盘;expectedModifiedMs 非空时经 If-Match 做并发保护,被改过回 409。 */
  saveConfig(name: string, text: string, expectedModifiedMs?: number | null): Promise<ConfigSaveResult>;
  /** 取一次性 SSE 票据（30 秒、只能用一次）。 */
  ticket(): Promise<string>;
  eventsUrl(ticket: string): string;
  /** 当前日志级别（error/info/debug）。 */
  logLevel(): Promise<string>;
  /** 切换日志级别。 */
  setLogLevel(level: string): Promise<boolean>;
  /** 按实例 id 读采集器状态。 */
  hostInstanceCollector(hostId: string): Promise<CollectorState | null>;
  /** 按实例 id 下发采集器配置。 */
  setHostInstanceCollector(hostId: string, config: CollectorConfigInput): Promise<{ ok: boolean; error?: string }>;
  /** 按实例 id 手动启停采集器。 */
  hostInstanceCollectorCommand(hostId: string, start: boolean): Promise<{ ok: boolean; error?: string }>;

  /** 打开主机模拟器连接;target 给定时连远程 SECS（schema v6）。 */
  openHostSim(sessionId: string, target?: HostSimTarget): Promise<number>;
  /** 关闭主机模拟器连接。 */
  closeHostSim(sessionId: string): Promise<number>;
  /** 以主机角色发送 SML。 */
  sendHostSml(sessionId: string, sml: string): Promise<SentMessageResult>;
  /** 工作台聚合（schema v6）。 */
  overview(): Promise<OverviewRow[]>;
  /** 服务端压测（schema v6）。 */
  burst(sessionId: string, request: BurstRequest): Promise<BurstResult>;
  /** 压测进度/最近一次结果（服务端后台执行，POST 只回受理）。 */
  burstStatus(sessionId: string): Promise<BurstStatus>;
  /** 主机模拟 S1F3 采集器状态与数据（schema v6）。 */
  collectorState(sessionId: string): Promise<CollectorState | null>;
  /** 下发采集器配置;返回错误文案或 null(成功)。 */
  setCollector(sessionId: string, config: CollectorConfigInput): Promise<string | null>;
  /** 手动启停采集器。 */
  collectorCommand(sessionId: string, start: boolean): Promise<string | null>;
  /** v2:设备配置版本历史(config_revision 快照,新→旧)。 */
  configRevisions(name: string, limit?: number): Promise<Array<{
    revision: number; sha256: string; createdAt: number; createdBy: string; note: string;
  }>>;
}

/** 3.0 v2：主机独立命名空间（实现见 api/hostsV2.ts）。 */
export interface ApiClient {
  hostsV2(): Promise<import('./hostsV2').HostV2[]>;
  createHostV2(request: {
    name?: string;
    host: string;
    port: number;
    deviceId?: number;
    autoReconnect?: boolean;
  }): Promise<string>;
  deleteHostV2(hostId: string): Promise<boolean>;
  saveHostV2(hostId: string, request: {
    name: string;
    host: string;
    port: number;
    deviceId: number;
  }): Promise<void>;
  connectHostV2(hostId: string): Promise<void>;
  disconnectHostV2(hostId: string): Promise<void>;
  applyHostV2(hostId: string): Promise<void>;
  sendHostV2(hostId: string, sml: string): Promise<SentMessageResult>;
  hostTemplatesV2(): Promise<import('./hostsV2').HostTemplate[]>;
  saveHostTemplateV2(request: {
    id?: string;
    name: string;
    host: string;
    port: number;
    deviceId?: number;
    note?: string;
  }): Promise<void>;
  deleteHostTemplateV2(id: string): Promise<boolean>;
}
