/** 与后端 HTTP API 的契约。类型故意手写而不是生成，先保持零依赖。 */

/** 角色与后端 ApiRole 一一对应，顺序即能力递增（改顺序等于给账号降级/升级）。 */
export type ApiRole = 'viewer' | 'operator' | 'admin';

const ROLE_ORDER: ReadonlyArray<ApiRole> = ['viewer', 'operator', 'admin'];

/**
 * 当前身份是否至少具备 required 的能力。
 *
 * <p>角色未知（还没连上、或后端版本较老不返回 role）一律按<b>没有权限</b>处理：
 * 宁可少显示一个按钮，也不要让人点了才吃一个 403。
 * 后端仍然是最终裁决者，这里只是不让人做无谓的尝试。
 * </p>
 */
export function hasRole(actual: ApiRole | undefined, required: ApiRole): boolean {
  if (actual === undefined) {
    return false;
  }
  return ROLE_ORDER.indexOf(actual) >= ROLE_ORDER.indexOf(required);
}

/** 角色的中文标签，给界面用。 */
export function roleLabel(role: ApiRole | undefined): string {
  switch (role) {
    case 'viewer':
      return '只读';
    case 'operator':
      return '操作员';
    case 'admin':
      return '管理员';
    default:
      return '未知';
  }
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
}

export interface SessionSummary {
  id: string;
  state: string;
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
  /** 带凭据时后端才会返回；免鉴权的健康检查里没有。 */
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

/** 曲线预览：分桶 min/max/avg。 */
export interface PreviewSeries {
  svid: number;
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

export interface PreviewData {
  from: number;
  to: number;
  buckets: number;
  bucketMs: number;
  series: PreviewSeries[];
}

/** 目录里的一个 CEID（绝对 ID + 语义）。 */
export interface CatalogCeid {
  ceid: number;
  owner: string;
  slot: string;
  reports: number[];
}

/** 目录里的一个报告定义。 */
export interface CatalogReport {
  rptid: number;
  owner: string;
  slot: string;
  svids: number[];
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
 */
export interface Catalog {
  ceids: CatalogCeid[];
  reports: CatalogReport[];
  svids: CatalogSvid[];
}

export const EMPTY_CATALOG: Catalog = { ceids: [], reports: [], svids: [] };

export interface ApiClient {
  health(): Promise<Health>;
  sessions(): Promise<SessionSummary[]>;
  trigger(sessionId: string, ceid: number): Promise<number>;
  alarm(sessionId: string, alid: number, set: boolean, text: string, category: number): Promise<number>;
  alarms(sessionId: string): Promise<AlarmState[]>;
  terminalMessages(sessionId: string): Promise<TerminalMessage[]>;
  /** SVID / RPTID / CEID 目录。 */
  catalog(sessionId: string): Promise<Catalog>;
  preview(sessionId: string, fromMs: number, toMs: number, buckets: number): Promise<PreviewData>;
  /** 取一次性 SSE 票据（30 秒、只能用一次）。 */
  ticket(): Promise<string>;
  eventsUrl(ticket: string): string;
}

const TOKEN_KEY = 'secs-sim-token';

/**
 * 读取访问令牌。
 *
 * 优先 hash（打包脚本用 `#token=` 打开浏览器，hash 不会发给服务端，也不进服务端日志），
 * 兼容旧的 `?token=`；读到后立刻从地址栏抹掉，避免留在历史里。
 */
export function readToken(): string {
  const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
  const fromUrl =
    new URLSearchParams(hash).get('token') ?? new URLSearchParams(window.location.search).get('token');
  const token = (fromUrl ?? '').trim();

  if (token !== '') {
    window.sessionStorage.setItem(TOKEN_KEY, token);
    window.history.replaceState({}, '', window.location.pathname);
    return token;
  }
  return window.sessionStorage.getItem(TOKEN_KEY) ?? '';
}

export function saveToken(token: string): void {
  window.sessionStorage.setItem(TOKEN_KEY, token);
}

export function createClient(token: string): ApiClient {
  const auth = { Authorization: `Bearer ${token}` };

  async function json<T>(response: Response): Promise<T> {
    if (response.status === 401) {
      throw new Error('401 未授权：token 无效或缺失');
    }
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`${response.status} ${text.slice(0, 200)}`);
    }
    return (await response.json()) as T;
  }

  /**
   * 命令类端点的状态码处理。
   *
   * <p>401 与 403 要分开报：前者是"你的口令不对"，后者是"你的角色不够"，
   * 混成一句"HTTP 4xx"的话，现场根本没法判断该换口令还是该找管理员。
   * </p>
   */
  async function command(response: Response): Promise<number> {
    if (response.status === 401) {
      throw new Error('401 未授权：token 无效或缺失');
    }
    if (response.status === 403) {
      throw new Error('403 权限不足：当前账号的角色不能执行此操作');
    }
    return response.status;
  }

  return {
    async health() {
      /* 带 token 才返回版本/形态；不带也能拿到 ok 与 uptimeMs */
      return json<Health>(await fetch('/api/v1/health', { headers: auth }));
    },
    async sessions() {
      const body = await json<{ sessions: SessionSummary[] }>(
        await fetch('/api/v1/sessions', { headers: auth }),
      );
      return body.sessions ?? [];
    },
    async trigger(sessionId, ceid) {
      return command(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/ceid/${ceid}`, {
          method: 'POST',
          headers: auth,
        }),
      );
    },
    async alarm(sessionId, alid, set, text, category) {
      const query = new URLSearchParams({
        set: String(set),
        text,
        category: String(category),
      });
      return command(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/alarm/${alid}?${query}`, {
          method: 'POST',
          headers: auth,
        }),
      );
    },
    async alarms(sessionId) {
      return json<AlarmState[]>(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/alarms`, { headers: auth }),
      );
    },
    async terminalMessages(sessionId) {
      return json<TerminalMessage[]>(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/terminal`, { headers: auth }),
      );
    },
    async catalog(sessionId) {
      const body = await json<Partial<Catalog>>(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/catalog`, { headers: auth }),
      );
      /* 三个数组都要兜底：老后端没有 catalog 端点时缺字段，缺了就当空目录，
         总比整个面板崩掉强 */
      return {
        ceids: body.ceids ?? [],
        reports: body.reports ?? [],
        svids: body.svids ?? [],
      };
    },
    async preview(sessionId, fromMs, toMs, buckets) {
      return json<PreviewData>(
        await fetch(
          `/api/v1/preview?session=${encodeURIComponent(sessionId)}&from=${fromMs}&to=${toMs}&buckets=${buckets}`,
          { headers: auth },
        ),
      );
    },
    async ticket() {
      const body = await json<{ ticket: string }>(
        await fetch('/api/v1/ticket', { method: 'POST', headers: auth }),
      );
      return body.ticket;
    },
    eventsUrl(ticket) {
      return `/api/v1/events?ticket=${encodeURIComponent(ticket)}`;
    },
  };
}
