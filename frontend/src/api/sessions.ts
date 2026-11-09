/**
 * 会话域端点：健康、会话增删改查、目录/预览、运行时编辑、手动发送、压测、总览。
 *
 * <p>都是"某台设备"上的操作；配置与主机模拟见 {@code configs.ts} / {@code hostsim.ts}。</p>
 */

import type {
  ApiClient,
  BurstResult,
  BurstStatus,
  Catalog,
  EventPlanView,
  Health,
  OverviewRow,
  PreviewData,
  SentMessageResult,
  SessionBulkResult,
  SessionSummary,
} from './types';
import { ApiError, apiUrl, commandStatus, LONG_REQUEST_TIMEOUT_MS, planToWire, readJson, request } from './base';

type SessionApi = Pick<
  ApiClient,
  | 'health'
  | 'sessions'
  | 'createSession'
  | 'bulkSessions'
  | 'closeSession'
  | 'reloadSession'
  | 'trigger'
  | 'alarm'
  | 'catalog'
  | 'preview'
  | 'events'
  | 'setEventEnabled'
  | 'addEventPlan'
  | 'updateEventPlan'
  | 'deleteEventPlan'
  | 'addReport'
  | 'updateReport'
  | 'deleteReport'
  | 'addSvid'
  | 'updateSvid'
  | 'deleteSvid'
  | 'addCeid'
  | 'updateCeid'
  | 'deleteCeid'
  | 'setSvidValue'
  | 'clearSvidValue'
  | 'sendSml'
  | 'overview'
  | 'burst'
  | 'burstStatus'
>;

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
    return JSON.parse(text) as SessionBulkResult;
  },
  async closeSession(sessionId) {
    return commandStatus(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}`), {
        method: 'DELETE',
      }),
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
        apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events/${encodeURIComponent(planId)}?${query}`),
        { method: 'POST' },
      ),
    );
  },
  async addEventPlan(sessionId, plan) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(planToWire(plan)),
    }));
  },
  async updateEventPlan(sessionId, planId, plan) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events/${encodeURIComponent(planId)}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(planToWire(plan)),
    }));
  },
  async deleteEventPlan(sessionId, planId) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events/${encodeURIComponent(planId)}`), {
      method: 'DELETE',
    }));
  },
  async addReport(sessionId, report) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reports`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(report),
    }));
  },
  async updateReport(sessionId, rptid, report) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/${rptid}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(report),
    }));
  },
  async deleteReport(sessionId, rptid) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/${rptid}`), {
      method: 'DELETE',
    }));
  },
  async addSvid(sessionId, def) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(def),
    }));
  },
  async updateSvid(sessionId, svid, def) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(def),
    }));
  },
  async deleteSvid(sessionId, svid) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}`), {
      method: 'DELETE',
    }));
  },
  async addCeid(sessionId, ceid) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/ceids`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(ceid),
    }));
  },
  async updateCeid(sessionId, ceid, def) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/ceids/${ceid}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(def),
    }));
  },
  async deleteCeid(sessionId, ceid) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/ceids/${ceid}`), {
      method: 'DELETE',
    }));
  },
  async setSvidValue(sessionId, svid, value) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}/value`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ value }),
    }));
  },
  async clearSvidValue(sessionId, svid) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/svids/${svid}/value`), {
      method: 'DELETE',
    }));
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
    return JSON.parse(text) as BurstResult;
  },
  async burstStatus(sessionId) {
    return readJson<BurstStatus>(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/burst`)),
    );
  },
};
