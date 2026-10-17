/** 与后端 HTTP API 的契约。类型故意手写而不是生成，先保持零依赖。 */

export interface Counters {
  emitted?: number;
  rejected?: number;
  skipped?: number;
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

export interface ApiClient {
  health(): Promise<Health>;
  sessions(): Promise<SessionSummary[]>;
  trigger(sessionId: string, ceid: number): Promise<number>;
  eventsUrl(): string;
}

const TOKEN_KEY = 'secs-sim-token';

export function readToken(): string {
  const fromUrl = new URLSearchParams(window.location.search).get('token');
  if (fromUrl !== null && fromUrl !== '') {
    window.sessionStorage.setItem(TOKEN_KEY, fromUrl);
    return fromUrl;
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
      return json<Health>(await fetch('/api/v1/health'));
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
    eventsUrl() {
      return `/api/v1/events?token=${encodeURIComponent(token)}`;
    },
  };
}
