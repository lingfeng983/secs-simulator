/**
 * 会话域端点：健康、会话增删改查、目录/预览、计划启停、手动发送、压测、总览。
 *
 * <p>都是"某台设备"上的操作；配置与主机模拟见 {@code configs.ts} / {@code hostsim.ts}。</p>
 */

import type {
  ActiveAlarm,
  ApiClient,
  BurstResult,
  BurstStatus,
  Catalog,
  CycleRuntimeView,
  EventPlanView,
  Health,
  OverviewRow,
  PreviewData,
  SentMessageResult,
  SessionBulkResult,
  SessionSummary,
} from './types';
import { ApiError, apiUrl, commandStatus, LONG_REQUEST_TIMEOUT_MS, readJson, request } from './base';

type SessionApi = Pick<
  ApiClient,
  | 'health'
  | 'sessions'
  | 'createSession'
  | 'bulkSessions'
  | 'closeSession'
  | 'connectDevice'
  | 'setDeviceOnline'
  | 'reloadSession'
  | 'trigger'
  | 'alarm'
  | 'activeAlarms'
  | 'catalog'
  | 'preview'
  | 'events'
  | 'setEventEnabled'
  | 'cycle'
  | 'cycleStart'
  | 'cycleStop'
  | 'sendSml'
  | 'overview'
  | 'burst'
  | 'burstStatus'
>;

/**
 * 活动报警清单的地址（v2 设备运行时端点）。
 *
 * <p>传的是会话 id，与其它 v2 设备调用（setDeviceOnline 等）同源；
 * 后端 sessionOf 对会话 id / 设备 id / 配置名 stem 三种写法都能解析。</p>
 */
function activeAlarmsUrl(sessionId: string): string {
  return apiUrl(`/api/v2/devices/${encodeURIComponent(sessionId)}/runtime/alarms`);
}

export const sessionApi: SessionApi = {
  async health() {
    return readJson<Health>(await request(apiUrl('/api/v1/health')));
  },
  async sessions() {
    const body = await readJson<{ sessions: SessionSummary[] }>(
      await request(apiUrl('/api/v1/sessions')),
    );
    return body.sessions ?? [];
  },
  async createSession(config, overrides) {
    /* 失败原因全在服务端日志里，界面只报状态码与文案，不自己猜 */
    return commandStatus(
      await request(apiUrl('/api/v1/sessions'), {
        method: 'POST',
        body: JSON.stringify({ config, ...overrides }),
      }, LONG_REQUEST_TIMEOUT_MS),
    );
  },
  async bulkSessions(params) {
    /* 最多 100 台一起建，8 秒不够 */
    const response = await request(apiUrl('/api/v1/sessions/bulk'), {
      method: 'POST',
      body: JSON.stringify(params),
    }, LONG_REQUEST_TIMEOUT_MS);
    const text = await response.text();
    if (!response.ok && response.status !== 207) {
      throw new Error(`${response.status} ${text.slice(0, 200)}`);
    }
    try {
      return JSON.parse(text) as SessionBulkResult;
    } catch {
      throw new ApiError(
        response.status,
        'bad-json',
        `${response.status} 返回的不是 JSON：${text.slice(0, 120)}`,
      );
    }
  },
  async closeSession(sessionId) {
    /* 3.0 起这条 v1 端点的语义是「断开链路」：设备不消失，可再连。
       后端会带 Deprecation 头，迁移到 v2 的 link/disconnect 放在下一轮。 */
    return commandStatus(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}`), {
        method: 'DELETE',
      }),
    );
  },
  async connectDevice(deviceId) {
    /* 建链可能要等主机 SELECT（被动监听则只绑端口），8 秒容易误判 */
    return commandStatus(
      await request(apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/link/connect`), {
        method: 'POST',
      }, LONG_REQUEST_TIMEOUT_MS),
    );
  },
  async setDeviceOnline(deviceId, online) {
    /* GEM 的上线/下线：只动控制状态，链路与端口都不动。
       链路没建立时后端回 409（没有主机就没有"在线"可言），由调用方提示。 */
    return commandStatus(
      await request(
        apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/link/${online ? 'online' : 'offline'}`),
        { method: 'POST' },
      ),
    );
  },
  async reloadSession(sessionId) {
    /* 重载要重新编译配置并重建连接，8 秒容易误判 */
    return commandStatus(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reload`), {
        method: 'POST',
      }, LONG_REQUEST_TIMEOUT_MS),
    );
  },
  async trigger(sessionId, ceid) {
    return commandStatus(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/ceid/${ceid}`), {
        method: 'POST',
      }),
    );
  },
  async alarm(sessionId, alid, set, text, category) {
    const query = new URLSearchParams({
      set: String(set),
      text,
      category: String(category),
    });
    return commandStatus(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/alarm/${alid}?${query}`), {
        method: 'POST',
      }),
    );
  },
  async activeAlarms(sessionId) {
    /* v2 统一对象形状：{"alarms":[…]}（按 ALID 升序），不再兼容裸数组 */
    const body = await readJson<{ alarms: ActiveAlarm[] }>(
      await request(activeAlarmsUrl(sessionId)),
    );
    return body.alarms ?? [];
  },
  async catalog(sessionId) {
    const body = await readJson<Partial<Catalog>>(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/catalog`)),
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
  async preview(sessionId, fromMs, toMs, buckets, svids, withEvents, planId) {
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
    if (planId !== undefined && planId !== '') {
      query.set('plan', planId);
    }
    return readJson<PreviewData>(
      await request(apiUrl(`/api/v1/preview?${query}`), {}, LONG_REQUEST_TIMEOUT_MS),
    );
  },
  async events(sessionId) {
    const body = await readJson<{ events?: EventPlanView[] }>(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events`)),
    );
    return body.events ?? [];
  },
  async setEventEnabled(sessionId, planId, enabled) {
    const query = new URLSearchParams({ enabled: String(enabled) });
    return commandStatus(
      await request(
        apiUrl(`/api/v2/devices/${encodeURIComponent(sessionId)}/runtime/plans/${encodeURIComponent(planId)}/enabled?${query}`),
        { method: 'POST' },
      ),
    );
  },
  async cycle(deviceId: string): Promise<CycleRuntimeView | null> {
    const response = await request(
      apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/cycle`),
    );
    if (response.status === 404) {
      /* 设备没启动（没有运行态） */
      return null;
    }
    return readJson<CycleRuntimeView>(response);
  },
  async cycleStart(deviceId: string) {
    return commandStatus(
      await request(apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/cycle/start`), {
        method: 'POST',
      }),
    );
  },
  async cycleStop(deviceId: string) {
    return commandStatus(
      await request(apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/cycle/stop`), {
        method: 'POST',
      }),
    );
  },
  async sendSml(sessionId, sml) {
    /* W 位要等主机回复（T3），不能用 8 秒的短超时把正常等待判成失败 */
    const response = await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/message`), {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: sml,
    }, LONG_REQUEST_TIMEOUT_MS);
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
  async overview() {
    /* 不吞错:工作台拉不到聚合数据时要让调用方知道,而不是显示"没有设备" */
    const body = await readJson<{ sessions?: OverviewRow[] }>(
      await request(apiUrl('/api/v1/overview')),
    );
    return body.sessions ?? [];
  },
  async burst(sessionId, payload) {
    /* 压测在服务端后台执行：这里的响应只是"受理"，最终统计由 burstStatus 轮询取回 */
    const response = await request(
      apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/burst`),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(payload),
      },
      LONG_REQUEST_TIMEOUT_MS,
    );
    const text = await response.text();
    if (!response.ok) {
      throw new ApiError(response.status, '', `${response.status} ${text.slice(0, 200)}`);
    }
    try {
      return JSON.parse(text) as BurstResult;
    } catch {
      throw new ApiError(
        response.status,
        'bad-json',
        `${response.status} 返回的不是 JSON：${text.slice(0, 120)}`,
      );
    }
  },
  async burstStatus(sessionId) {
    return readJson<BurstStatus>(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/burst`)),
    );
  },
};
