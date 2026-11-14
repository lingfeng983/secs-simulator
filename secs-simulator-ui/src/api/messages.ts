/**
 * 观测域端点：SSE 票据、报文历史、审计、日志级别。
 *
 * <p>都是"看"的接口，不改变设备状态；写入类端点在各会话/配置域里。</p>
 */

import type { ApiClient, AuditEntryView, AuditFilters, MessageHistoryOptions, MessagePage } from './types';
import { apiUrl, readJson, request } from './base';

type MessageApi = Pick<
  ApiClient,
  | 'ticket'
  | 'eventsUrl'
  | 'messages'
  | 'audit'
  | 'logLevel'
  | 'setLogLevel'
>;

export const messageApi: MessageApi = {
  async ticket() {
    const body = await readJson<{ ticket: string }>(
      await request(apiUrl('/api/v1/ticket'), { method: 'POST' }),
    );
    return body.ticket;
  },
  eventsUrl(ticket) {
    return apiUrl(`/api/v1/events?ticket=${encodeURIComponent(ticket)}`);
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
    const body = await readJson<Partial<MessagePage>>(
      await request(apiUrl(`/api/v1/messages?${query}`)),
    );
    /* 缺字段时按"没有历史"兜底，而不是把 undefined 摊到界面上 */
    return {
      messages: body.messages ?? [],
      total: body.total ?? 0,
      limit: body.limit ?? limit,
      truncated: body.truncated ?? false,
    };
  },
  async audit(limit, filters?: AuditFilters) {
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
    const body = await readJson<{ audit?: AuditEntryView[] }>(
      await request(apiUrl(`/api/v1/audit?${query}`)),
    );
    return body.audit ?? [];
  },
  async logLevel() {
    const body = await readJson<{ level?: string }>(await request(apiUrl('/api/v1/log-level')));
    return body.level ?? 'info';
  },
  async setLogLevel(level) {
    /* 失败原因在服务端日志里，界面只要能拿到 true/false */
    const response = await request(apiUrl('/api/v1/log-level'), {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: level,
    });
    return response.ok;
  },
};
