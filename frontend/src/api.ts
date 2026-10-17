/** 与后端 HTTP API 的契约。类型故意手写而不是生成，先保持零依赖。 */

export interface Counters {
  emitted?: number;
  rejected?: number;
  skipped?: number;
  wbitDropped?: number;
  traceWbitDropped?: number;
  terminalMessages?: number;
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

export interface PreviewData {
  from: number;
  to: number;
  buckets: number;
  bucketMs: number;
  series: PreviewSeries[];
}

export interface ApiClient {
  health(): Promise<Health>;
  sessions(): Promise<SessionSummary[]>;
  trigger(sessionId: string, ceid: number): Promise<number>;
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
      const response = await fetch(
        `/api/v1/sessions/${encodeURIComponent(sessionId)}/ceid/${ceid}`,
        { method: 'POST', headers: auth },
      );
      if (response.status === 401) {
        throw new Error('401 未授权');
      }
      return response.status;
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
