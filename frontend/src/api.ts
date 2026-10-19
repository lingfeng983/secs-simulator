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
  /** 返回的序列；后端预览上限 32 条。 */
  series: PreviewSeries[];
  /** 会话里的 SVID 总数；老后端可能没有。 */
  svidTotal?: number;
  /** 是否只返回了前 32 条。 */
  truncated?: boolean;
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
  createSession(config: string): Promise<number>;
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
  preview(sessionId: string, fromMs: number, toMs: number, buckets: number): Promise<PreviewData>;
  /** 手动发送一条 SML 报文（operator 及以上）。 */
  sendSml(sessionId: string, sml: string): Promise<SentMessageResult>;
  /** 最近报文历史；session/direction 为空串表示全部。 */
  messages(sessionId: string, direction: string, limit: number): Promise<MessagePage>;
  /** 报文历史 JSON Lines，用于导出。 */
  messagesJsonl(sessionId: string, direction: string, limit: number): Promise<string>;
  /** 配置目录列表。 */
  configs(): Promise<ConfigEntry[]>;
  /** 新建一份配置（后端生成能直接编译的骨架）。 */
  createConfig(name: string): Promise<number>;
  /** 读一份配置的原始文本。 */
  configText(name: string): Promise<string>;
  /** 只校验不落盘。 */
  validateConfig(name: string, text: string): Promise<ConfigProblems>;
  /** 校验通过后落盘。 */
  saveConfig(name: string, text: string): Promise<ConfigSaveResult>;
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
    async createSession(config) {
      /* 失败原因全在服务端日志里，界面只报状态码与文案，不自己猜 */
      return command(
        await fetch('/api/v1/sessions', {
          method: 'POST',
          headers: auth,
          body: JSON.stringify({ config }),
        }),
      );
    },
    async closeSession(sessionId) {
      return command(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}`, {
          method: 'DELETE',
          headers: auth,
        }),
      );
    },
    async reloadSession(sessionId) {
      return command(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reload`, {
          method: 'POST',
          headers: auth,
        }),
      );
    },
    async trigger(sessionId, ceid) {
      return command(
        await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/ceid/${ceid}`, {
          method: 'POST',
          headers: auth,
        }),
      );
    },
    async sendSml(sessionId, sml) {
      const response = await fetch(`/api/v1/sessions/${encodeURIComponent(sessionId)}/message`, {
        method: 'POST',
        headers: { ...auth, 'Content-Type': 'text/plain; charset=utf-8' },
        body: sml,
      });
      if (response.status === 401) {
        throw new Error('401 未授权：token 无效或缺失');
      }
      if (response.status === 403) {
        throw new Error('403 权限不足：当前账号的角色不能执行此操作');
      }
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
    async messages(sessionId, direction, limit) {
      const query = new URLSearchParams({ limit: String(limit) });
      if (sessionId !== '') {
        query.set('session', sessionId);
      }
      if (direction !== '') {
        query.set('direction', direction);
      }
      const body = await json<Partial<MessagePage>>(
        await fetch(`/api/v1/messages?${query}`, { headers: auth }),
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
      const response = await fetch(`/api/v1/messages?${query}`, { headers: auth });
      if (response.status === 401) {
        throw new Error('401 未授权：token 无效或缺失');
      }
      if (response.status === 403) {
        throw new Error('403 权限不足：当前账号的角色不能执行此操作');
      }
      if (!response.ok) {
        throw new Error(`${response.status} ${(await response.text()).slice(0, 200)}`);
      }
      return response.text();
    },
    async configs() {
      const body = await json<{ configs?: ConfigEntry[] }>(
        await fetch('/api/v1/configs', { headers: auth }),
      );
      return body.configs ?? [];
    },
    async createConfig(name) {
      const response = await fetch('/api/v1/configs', {
        method: 'POST',
        headers: { ...auth, 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ name }),
      });
      return command(response);
    },
    async configText(name) {
      const body = await json<{ name?: string; text?: string }>(
        await fetch(`/api/v1/configs/${encodeURIComponent(name)}`, { headers: auth }),
      );
      return body.text ?? '';
    },
    async validateConfig(name, text) {
      const body = await json<ConfigProblems>(
        await fetch(`/api/v1/configs/${encodeURIComponent(name)}/validate`, {
          method: 'POST',
          headers: { ...auth, 'Content-Type': 'application/json; charset=utf-8' },
          body: text,
        }),
      );
      return { valid: body.valid ?? false, problems: body.problems ?? [] };
    },
    async saveConfig(name, text) {
      const response = await fetch(`/api/v1/configs/${encodeURIComponent(name)}`, {
        method: 'PUT',
        headers: { ...auth, 'Content-Type': 'application/json; charset=utf-8' },
        body: text,
      });

      if (response.status === 401) {
        throw new Error('401 未授权：token 无效或缺失');
      }
      if (response.status === 403) {
        throw new Error('403 权限不足：当前账号的角色不能执行此操作');
      }

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
      return `/api/v1/events?ticket=${encodeURIComponent(ticket)}`;
    },
  };
}
