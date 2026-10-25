/** 与后端 HTTP API 的契约。类型故意手写而不是生成，先保持零依赖。 */

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
  sessionId: string;
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
  enabled: boolean;
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

export interface AlarmState {
  alid: number;
  text: string;
  category: number;
  atMillis: number;
}

export interface TerminalMessage {
  tid: number;
  text: string;
  atMillis: number;
}

/** 预览窗口内的一条事件标记（schema v6:运行时=编排重放,草稿=模拟时间线）。 */
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
}

/** 内置设备模板目录里的一项。 */
export interface ConfigTemplateEntry {
  id: string;
  label: string;
  description: string;
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

export interface ApiClient {
  health(): Promise<Health>;
  sessions(): Promise<SessionSummary[]>;
  /** 新建会话；config 为 demo 或配置目录下的文件名。 */
  createSession(config: string, overrides?: SessionOverrides): Promise<number>;
  bulkSessions(params: SessionBulkParams): Promise<SessionBulkResult>;
  /** 关闭并移除会话。 */
  closeSession(sessionId: string): Promise<number>;
  /** 按配置重载会话。 */
  reloadSession(sessionId: string): Promise<number>;
  trigger(sessionId: string, ceid: number): Promise<number>;
  alarm(sessionId: string, alid: number, set: boolean, text: string, category: number): Promise<number>;
  alarms(sessionId: string): Promise<AlarmState[]>;
  terminalMessages(sessionId: string): Promise<TerminalMessage[]>;
  /** SVID / RPTID / CEID 目录。 */
  catalog(sessionId: string): Promise<Catalog>;
  preview(sessionId: string, fromMs: number, toMs: number, buckets: number, svids?: number[], withEvents?: boolean): Promise<PreviewData>;
  /** 二进制曲线预览（SCSP v1，小端）。 */
  previewBinary(sessionId: string, fromMs: number, toMs: number, buckets: number): Promise<Blob>;
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
  /** 「设定」状态变量的值(钉住,覆盖曲线与事件效果)。 */
  setSvidValue(sessionId: string, svid: number, value: number): Promise<number>;
  /** 取消「设定」。 */
  clearSvidValue(sessionId: string, svid: number): Promise<number>;
  /** 手动发送一条 SML 报文。 */
  sendSml(sessionId: string, sml: string): Promise<SentMessageResult>;
  /** 最近报文历史；session/direction 为空串表示全部。 */
  messages(sessionId: string, direction: string, limit: number, opts?: MessageHistoryOptions): Promise<MessagePage>;
  /** 报文历史 JSON Lines，用于导出。 */
  messagesJsonl(sessionId: string, direction: string, limit: number): Promise<string>;
  /** 配置目录列表。 */
  configs(): Promise<ConfigEntry[]>;
  /** 新建一份配置；templateId 缺省是空骨架，其余取内置设备模板目录里的 id。 */
  createConfig(name: string, templateId?: string): Promise<number>;
  /** 内置设备模板目录（新建配置的选型列表）。 */
  configTemplates(): Promise<ConfigTemplateEntry[]>;
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
  /** 主机模拟器状态；会话不存在返回 null。 */
  hostSimState(sessionId: string): Promise<{ open: boolean; target?: string; mode?: string; remote?: boolean } | null>;
  /** 打开主机模拟器连接;target 给定时连远程 SECS（schema v6）。 */
  openHostSim(sessionId: string, target?: HostSimTarget): Promise<number>;
  /** 关闭主机模拟器连接。 */
  closeHostSim(sessionId: string): Promise<number>;
  /** 以主机角色发送 SML。 */
  sendHostSml(sessionId: string, sml: string): Promise<SentMessageResult>;
  /** 按配置草稿文本算曲线预览（不落盘、不需要会话）。 */
  configPreview(
    text: string,
    fromMs: number,
    toMs: number,
    buckets: number,
    svids?: number[],
    withEvents?: boolean,
  ): Promise<PreviewData>;
  /** 工作台聚合（schema v6）。 */
  overview(): Promise<OverviewRow[]>;
  /** 服务端压测（schema v6）。 */
  burst(sessionId: string, request: BurstRequest): Promise<BurstResult>;
  /** 主机模拟 S1F3 采集器状态与数据（schema v6）。 */
  collectorState(sessionId: string): Promise<CollectorState | null>;
  /** 下发采集器配置;返回错误文案或 null(成功)。 */
  setCollector(sessionId: string, config: CollectorConfigInput): Promise<string | null>;
  /** 手动启停采集器。 */
  collectorCommand(sessionId: string, start: boolean): Promise<string | null>;
}

/** 后端地址的本地存储键；空串 = 同源（界面由后端托管时的默认）。 */
const API_BASE_KEY = 'secs-sim-api-base';

/** 当前使用的后端地址；空串表示同源。 */
export function readApiBase(): string {
  return window.localStorage.getItem(API_BASE_KEY) ?? '';
}

/**
 * 设置后端地址。
 *
 * <p>远程运行时后端的场景：完整安装包里的界面可以指向另一台机器上的后端，
 * 远程改配置、看曲线、发报文。写入后刷新页面生效。</p>
 */
export function saveApiBase(base: string): void {
  const trimmed = base.trim().replace(/\/+$/, '');
  if (trimmed === '') {
    window.localStorage.removeItem(API_BASE_KEY);
  } else {
    window.localStorage.setItem(API_BASE_KEY, trimmed);
  }
}

/** 把事件计划输入转成后端 wire 形状(trigger 打包成对象)。 */
function planToWire(plan: EventPlanInput): Record<string, unknown> {
  const trigger: Record<string, unknown> = { type: plan.triggerType };
  if (plan.triggerType === 'at') {
    trigger.atMs = plan.atMs ?? 0;
  } else if (plan.triggerType === 'periodic') {
    trigger.periodMs = plan.periodMs ?? 1000;
    trigger.jitterMs = plan.jitterMs ?? 0;
  } else if (plan.triggerType === 'onCondition') {
    trigger.expression = plan.expression ?? '';
    trigger.debounceMs = plan.debounceMs ?? 0;
  } else {
    trigger.sourcePlan = plan.sourcePlan ?? '';
    trigger.delayMs = plan.delayMs ?? 0;
  }
  return {
    id: plan.id,
    ceid: plan.ceid,
    trigger,
    effects: plan.effects ?? [],
  };
}

/** 拼出 API 地址：本地模式同源，远程模式带后端地址。 */
function apiUrl(path: string): string {
  const base = readApiBase();
  return base === '' ? path : `${base}${path}`;
}

/** 后端返回 {ok:false,error:"code"} 时的结构化错误;message 保持可读。 */
export class ApiError extends Error {
  readonly status: number;
  /** 后端的错误码字符串(如 forbidden / not-found);非包络响应为 ''。 */
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export function createClient(): ApiClient {
  async function json<T>(response: Response): Promise<T> {
    if (!response.ok) {
      const text = await response.text();
      let code = '';
      try {
        const body = JSON.parse(text) as { error?: unknown };
        if (typeof body.error === 'string') {
          code = body.error;
        }
      } catch {
        /* 非 JSON 响应(网关错误页等)没有错误码 */
      }
      throw new ApiError(response.status, code, `${response.status} ${text.slice(0, 200)}`);
    }
    return (await response.json()) as T;
  }

  /** 命令类端点：界面只关心状态码，失败原因在响应体或服务端日志里。 */
  async function command(response: Response): Promise<number> {
    return response.status;
  }

  return {
    async health() {
      return json<Health>(await fetch(apiUrl('/api/v1/health')));
    },
    async sessions() {
      const body = await json<{ sessions: SessionSummary[] }>(
        await fetch(apiUrl('/api/v1/sessions')),
      );
      return body.sessions ?? [];
    },
    async createSession(config, overrides) {
      /* 失败原因全在服务端日志里，界面只报状态码与文案，不自己猜 */
      return command(
        await fetch(apiUrl('/api/v1/sessions'), {
          method: 'POST',
          body: JSON.stringify({ config, ...overrides }),
        }),
      );
    },
    async bulkSessions(params) {
      const response = await fetch(apiUrl('/api/v1/sessions/bulk'), {
        method: 'POST',
        body: JSON.stringify(params),
      });
      const text = await response.text();
      if (!response.ok && response.status !== 207) {
        throw new Error(`${response.status} ${text.slice(0, 200)}`);
      }
      return JSON.parse(text) as SessionBulkResult;
    },
    async closeSession(sessionId) {
      return command(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}`), {
          method: 'DELETE',
        }),
      );
    },
    async reloadSession(sessionId) {
      return command(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reload`), {
          method: 'POST',
        }),
      );
    },
    async trigger(sessionId, ceid) {
      return command(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/ceid/${ceid}`), {
          method: 'POST',
        }),
      );
    },
    async audit(limit, filters) {
      const query = new URLSearchParams({ limit: String(limit) });
      if (filters?.action) {
        query.set('action', filters.action);
      }
      if (filters?.actor) {
        query.set('actor', filters.actor);
      }
      if (filters?.before !== undefined && filters.before > 0) {
        query.set('before', String(filters.before));
      }
      const body = await json<{ audit?: AuditEntryView[] }>(
        await fetch(apiUrl(`/api/v1/audit?${query}`)),
      );
      return body.audit ?? [];
    },
    async events(sessionId) {
      const body = await json<{ events?: EventPlanView[] }>(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events`)),
      );
      return body.events ?? [];
    },
    async setEventEnabled(sessionId, planId, enabled) {
      const query = new URLSearchParams({ enabled: String(enabled) });
      return command(
        await fetch(
          apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events/${encodeURIComponent(planId)}?${query}`),
          { method: 'POST' },
        ),
      );
    },
    async addEventPlan(sessionId, plan) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(planToWire(plan)),
      }));
    },
    async updateEventPlan(sessionId, planId, plan) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events/${encodeURIComponent(planId)}`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(planToWire(plan)),
      }));
    },
    async deleteEventPlan(sessionId, planId) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events/${encodeURIComponent(planId)}`), {
        method: 'DELETE',
      }));
    },
    async addReport(sessionId, report) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reports`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(report),
      }));
    },
    async updateReport(sessionId, rptid, report) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/${rptid}`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(report),
      }));
    },
    async deleteReport(sessionId, rptid) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/${rptid}`), {
        method: 'DELETE',
      }));
    },
    async addSvid(sessionId, def) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(def),
      }));
    },
    async updateSvid(sessionId, svid, def) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(def),
      }));
    },
    async deleteSvid(sessionId, svid) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}`), {
        method: 'DELETE',
      }));
    },
    async setSvidValue(sessionId, svid, value) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}/value`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ value }),
      }));
    },
    async clearSvidValue(sessionId, svid) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}/value`), {
        method: 'DELETE',
      }));
    },
    async sendSml(sessionId, sml) {
      const response = await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/message`), {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: sml,
      });
      const text = await response.text();
      if (!response.ok) {
        throw new Error(`${response.status} ${text.slice(0, 300)}`);
      }
      try {
        return JSON.parse(text) as SentMessageResult;
      } catch {
        throw new Error('发送响应不是合法 JSON');
      }
    },
    async alarm(sessionId, alid, set, text, category) {
      const query = new URLSearchParams({
        set: String(set),
        text,
        category: String(category),
      });
      return command(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/alarm/${alid}?${query}`), {
          method: 'POST',
        }),
      );
    },
    async alarms(sessionId) {
      return json<AlarmState[]>(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/alarms`)),
      );
    },
    async terminalMessages(sessionId) {
      return json<TerminalMessage[]>(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/terminal`)),
      );
    },
    async catalog(sessionId) {
      const body = await json<Partial<Catalog>>(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/catalog`)),
      );
      /* 每个字段都要兜底：老后端没有 catalog 端点时缺字段，缺了就当默认值，
         总比整个面板崩掉强 */
      return {
        ceids: body.ceids ?? [],
        reports: body.reports ?? [],
        svids: body.svids ?? [],
        hostReports: body.hostReports ?? 0,
        hostLinks: body.hostLinks ?? 0,
        hostAllDisabled: body.hostAllDisabled ?? false,
      };
    },
    async preview(sessionId, fromMs, toMs, buckets, svids, withEvents) {
      const query = new URLSearchParams({
        session: sessionId,
        from: String(fromMs),
        to: String(toMs),
        buckets: String(buckets),
      });
      if (svids && svids.length > 0) {
        query.set('svids', svids.join(','));
      }
      if (withEvents === false) {
        query.set('withEvents', 'false');
      }
      return json<PreviewData>(
        await fetch(apiUrl(`/api/v1/preview?${query}`)),
      );
    },
    async previewBinary(sessionId, fromMs, toMs, buckets) {
      const response = await fetch(
        apiUrl(`/api/v1/preview.bin?session=${encodeURIComponent(sessionId)}&from=${fromMs}&to=${toMs}&buckets=${buckets}`),
      );
      if (!response.ok) {
        throw new Error(`${response.status} ${(await response.text()).slice(0, 200)}`);
      }
      return response.blob();
    },
    async ticket() {
      const body = await json<{ ticket: string }>(
        await fetch(apiUrl('/api/v1/ticket'), { method: 'POST' }),
      );
      return body.ticket;
    },
    async messages(sessionId, direction, limit, opts) {
      const query = new URLSearchParams({ limit: String(limit) });
      if (sessionId !== '') {
        query.set('session', sessionId);
      }
      if (direction !== '') {
        query.set('direction', direction);
      }
      if (opts?.before !== undefined && opts.before > 0) {
        query.set('before', String(opts.before));
      }
      if (opts?.stream !== undefined) {
        query.set('stream', String(opts.stream));
      }
      if (opts?.function !== undefined) {
        query.set('function', String(opts.function));
      }
      const body = await json<Partial<MessagePage>>(
        await fetch(apiUrl(`/api/v1/messages?${query}`)),
      );
      return {
        messages: body.messages ?? [],
        total: body.total ?? 0,
        limit: body.limit ?? limit,
        truncated: body.truncated ?? false,
      };
    },
    async messagesJsonl(sessionId, direction, limit) {
      const query = new URLSearchParams({ limit: String(limit), format: 'jsonl' });
      if (sessionId !== '') {
        query.set('session', sessionId);
      }
      if (direction !== '') {
        query.set('direction', direction);
      }
      const response = await fetch(apiUrl(`/api/v1/messages?${query}`));
      if (!response.ok) {
        throw new Error(`${response.status} ${(await response.text()).slice(0, 200)}`);
      }
      return response.text();
    },
    async configs() {
      const body = await json<{ configs?: ConfigEntry[] }>(
        await fetch(apiUrl('/api/v1/configs')),
      );
      return body.configs ?? [];
    },
    async createConfig(name, templateId) {
      const response = await fetch(apiUrl('/api/v1/configs'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(templateId ? { name, template: templateId } : { name }),
      });
      return command(response);
    },
    async configTemplates() {
      const body = await json<{ templates?: ConfigTemplateEntry[] }>(
        await fetch(apiUrl('/api/v1/config-templates')),
      );
      return body.templates ?? [];
    },
    async configText(name) {
      const body = await json<{ name?: string; text?: string; modifiedMs?: number }>(
        await fetch(apiUrl(`/api/v1/configs/${encodeURIComponent(name)}`)),
      );
      return { text: body.text ?? '', modifiedMs: typeof body.modifiedMs === 'number' ? body.modifiedMs : null };
    },
    async validateConfig(name, text) {
      const body = await json<ConfigProblems>(
        await fetch(apiUrl(`/api/v1/configs/${encodeURIComponent(name)}/validate`), {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain; charset=utf-8' },
          body: text,
        }),
      );
      return { valid: body.valid ?? false, problems: body.problems ?? [] };
    },
    async saveConfig(name, text, expectedModifiedMs) {
      const headers: Record<string, string> = { 'Content-Type': 'text/plain; charset=utf-8' };
      if (expectedModifiedMs != null) {
        headers['If-Match'] = String(expectedModifiedMs);
      }
      const response = await fetch(apiUrl(`/api/v1/configs/${encodeURIComponent(name)}`), {
        method: 'PUT',
        headers,
        body: text,
      });

      /* 失败响应也带 problems，尽量把它带回来；解析不了就当没有 */
      let problems: string[] = [];
      try {
        const body = (await response.json()) as { problems?: string[] };
        problems = body.problems ?? [];
      } catch {
        /* 非 JSON 响应（网关错误页等）没有可带回来的问题 */
      }
      return { status: response.status, problems };
    },
    eventsUrl(ticket) {
      return apiUrl(`/api/v1/events?ticket=${encodeURIComponent(ticket)}`);
    },
    async logLevel() {
      const body = await json<{ level?: string }>(await fetch(apiUrl('/api/v1/log-level')));
      return body.level ?? 'info';
    },
    async setLogLevel(level) {
      const response = await fetch(apiUrl('/api/v1/log-level'), {
        method: 'PUT',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: level,
      });
      return response.ok;
    },
    async hostSimState(sessionId) {
      try {
        const body = await json<{ open?: boolean; target?: string; mode?: string; remote?: boolean }>(
          await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim`)),
        );
        return { open: body.open ?? false, target: body.target, mode: body.mode, remote: body.remote };
      } catch {
        return null;
      }
    },
    async openHostSim(sessionId, target) {
      return command(
        await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim`), {
          method: 'POST',
          headers: target === undefined ? undefined : { 'Content-Type': 'application/json; charset=utf-8' },
          body: target === undefined ? undefined : JSON.stringify({ target }),
        }),
      );
    },
    async overview() {
      try {
        const body = await json<{ sessions?: OverviewRow[] }>(
          await fetch(apiUrl('/api/v1/overview')),
        );
        return body.sessions ?? [];
      } catch {
        /* 老后端没有该端点:回空,工作台退化为会话列表 */
        return [];
      }
    },
    async burst(sessionId, request) {
      const response = await fetch(
        apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/burst`),
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json; charset=utf-8' },
          body: JSON.stringify(request),
        },
      );
      const text = await response.text();
      if (!response.ok) {
        throw new Error(`${response.status} ${text.slice(0, 200)}`);
      }
      return JSON.parse(text) as BurstResult;
    },
    async collectorState(sessionId) {
      try {
        const body = await json<Partial<CollectorState>>(
          await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/collector`)),
        );
        return {
          configured: body.configured ?? false,
          running: body.running ?? false,
          mode: body.mode,
          startCeid: body.startCeid,
          stopCeid: body.stopCeid,
          intervalMs: body.intervalMs,
          svids: body.svids ?? [],
          samples: body.samples ?? 0,
          decodeErrors: body.decodeErrors ?? 0,
          lastRttMs: body.lastRttMs,
          staleSvids: body.staleSvids ?? [],
          series: body.series ?? [],
        };
      } catch {
        return null;
      }
    },
    async setCollector(sessionId, config) {
      const response = await fetch(
        apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/collector`),
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json; charset=utf-8' },
          body: JSON.stringify(config),
        },
      );
      const body = (await response.json()) as { ok?: boolean; error?: string };
      return body.ok ? null : body.error ?? `${response.status}`;
    },
    async collectorCommand(sessionId, start) {
      const response = await fetch(
        apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/collector/${start ? 'start' : 'stop'}`),
        { method: 'POST' },
      );
      const body = (await response.json()) as { ok?: boolean; error?: string };
      return body.ok ? null : body.error ?? `${response.status}`;
    },
    async closeHostSim(sessionId) {
      return command(await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim`), { method: 'DELETE' }));
    },
    async configPreview(text, fromMs, toMs, buckets, svids, withEvents) {
      const query = new URLSearchParams({ from: String(fromMs), to: String(toMs), buckets: String(buckets) });
      if (svids && svids.length > 0) {
        query.set('svids', svids.join(','));
      }
      if (withEvents === false) {
        query.set('withEvents', 'false');
      }
      const response = await fetch(apiUrl(`/api/v1/config-preview?${query}`), {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: text,
      });
      if (!response.ok) {
        throw new Error(response.status === 422 ? '422 配置编译不过，先看校验问题' : `${response.status}`);
      }
      return (await response.json()) as PreviewData;
    },
    async sendHostSml(sessionId, sml) {
      const response = await fetch(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/send`), {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: sml,
      });
      const text = await response.text();
      if (!response.ok) {
        throw new Error(`${response.status} ${text.slice(0, 300)}`);
      }
      try {
        return JSON.parse(text) as SentMessageResult;
      } catch {
        throw new Error('响应不是合法 JSON');
      }
    },
  };
}
