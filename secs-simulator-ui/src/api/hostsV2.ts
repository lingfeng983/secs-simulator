/**
 * 3.0 v2 主机域端点：/api/v2/hosts（与设备无关的独立实例）+ /api/v2/host-templates。
 *
 * <p>主机只填目标地址即可创建（设计 §2.5），连接/断开/使设置生效都是
 * 实例自己的动作；每实例的收发记录走 perspective=host。</p>
 */

import type { ApiClient, SentMessageResult } from './types';
import { apiUrl, readJson, request } from './base';

/** 一个主机实例（v2：与设备无关）。 */
export interface HostV2 {
  id: string;
  name: string;
  phase: 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'FAILED';
  open: boolean;
  target: string;
  port: number;
  deviceId: number;
  autoReconnect: boolean;
  retryCount: number;
  nextRetryAt: number;
  settingRevision: number;
  appliedRevision: number;
  /** v1 兼容字段（恒为 target/true/空串）。 */
  session?: string;
  mode?: string;
  remote?: boolean;
  lastError?: string;
}

/**
 * 保存主机设置的结果。
 *
 * <p>{@code changed} 说明这次到底动了什么：只有 {@code target}（地址/端口/Device ID）
 * 才需要「使设置生效」；{@code name} 是热生效的，保存完就完了。</p>
 */
export interface HostSaveResult {
  changed: Array<'name' | 'target'>;
  /** 目标变了且主机在线：需要点「使设置生效」重建链路 */
  needsReconnect: boolean;
  settingRevision: number;
  appliedRevision: number;
}

/**
 * 后端 host_template 的一行（旧端点形状）。
 *
 * <p>目标地址 / 端口 / Device ID 是主机实例自己的设置，不属于模板；
 * 界面不再展示这三个字段，界面用的「方案包模板」见 state/hostTemplates.ts。
 * 后端字段仍留着只是为了让现有端点继续可用，方案包编码在 note 里。</p>
 */
export interface HostTemplateRow {
  id: string;
  name: string;
  host: string;
  port: number;
  deviceId: number;
  note?: string;
}

type HostsV2Api = Pick<  ApiClient,
  | 'hostsV2'
  | 'createHostV2'
  | 'deleteHostV2'
  | 'saveHostV2'
  | 'connectHostV2'
  | 'disconnectHostV2'
  | 'applyHostV2'
  | 'sendHostV2'
  | 'hostTemplatesV2'
  | 'saveHostTemplateV2'
  | 'deleteHostTemplateV2'
  | 'listHostPlansV2'
  | 'saveHostPlanV2'
  | 'deleteHostPlanV2'
>;

/**
 * 删除 / 断开这类"无返回正文"的端点：非 2xx 必须抛错。
 *
 * <p>以前直接返回 {@code response.ok}，409/500 被当成成功——界面提示
 * "已删除 3 台"，刷新后行还在，而且没有任何原因提示。这里把后端给的
 * {@code error} 带出来，让调用方能原样展示。</p>
 */
async function assertOk(response: Response, fallback: string): Promise<void> {
  if (response.ok) {
    return;
  }
  let detail = '';
  try {
    const body = await readJson<{ error?: string }>(response);
    detail = typeof body.error === 'string' ? body.error : '';
  } catch {
    /* 响应不是 JSON（代理返回的 HTML 错误页等）：只报状态码 */
  }
  throw new Error(detail === '' ? `${fallback}（HTTP ${response.status}）` : `${fallback}：${detail}`);
}

export const hostsV2Api: HostsV2Api = {
  async hostsV2() {
    const body = await readJson<{ hosts?: HostV2[] }>(await request(apiUrl('/api/v2/hosts')));
    return body.hosts ?? [];
  },
  async createHostV2(req) {
    const body = await readJson<{ ok?: boolean; hostId?: string; error?: string }>(
      await request(apiUrl('/api/v2/hosts'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(req),
      }, 30_000),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '新建主机失败');
    }
    return body.hostId ?? '';
  },
  async deleteHostV2(hostId) {
    const response = await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}`), { method: 'DELETE' });
    await assertOk(response, '删除主机失败');
    return true;
  },
  async saveHostV2(hostId, req) {
    const body = await readJson<{
      ok?: boolean; error?: string;
      changed?: Array<'name' | 'target'>;
      needsReconnect?: boolean;
      settingRevision?: number;
      appliedRevision?: number;
    }>(
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(req),
      }),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '保存设置失败');
    }
    return {
      changed: body.changed ?? [],
      needsReconnect: body.needsReconnect === true,
      settingRevision: body.settingRevision ?? 0,
      appliedRevision: body.appliedRevision ?? 0,
    };
  },
  async connectHostV2(hostId) {
    const body = await readJson<{ ok?: boolean; error?: string }>(
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/connect`),
        { method: 'POST' }, 30_000),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '连接失败');
    }
  },
  async disconnectHostV2(hostId) {
    const response = await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/disconnect`), { method: 'POST' });
    await assertOk(response, '断开主机失败');
  },
  async applyHostV2(hostId) {
    const body = await readJson<{ ok?: boolean; error?: string }>(
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/apply`),
        { method: 'POST' }, 30_000),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '使设置生效失败');
    }
  },
  async sendHostV2(hostId, sml) {
    return await readJson<SentMessageResult>(
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/send`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ sml }),
      }, 60_000),
    );
  },
  async hostTemplatesV2() {
    const body = await readJson<{ templates?: HostTemplateRow[] }>(
      await request(apiUrl('/api/v2/host-templates')),
    );
    return body.templates ?? [];
  },
  async saveHostTemplateV2(req) {
    const body = await readJson<{ ok?: boolean; error?: string }>(
      await request(apiUrl('/api/v2/host-templates'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(req),
      }),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '保存模板失败');
    }
  },
  async deleteHostTemplateV2(id) {
    const response = await request(apiUrl(`/api/v2/host-templates/${encodeURIComponent(id)}`), { method: 'DELETE' });
    await assertOk(response, '删除模板失败');
    return true;
  },
  async listHostPlansV2(hostId, kind) {
    const body = await readJson<Record<string, unknown>[]>(
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/plans?kind=${kind}`)),
    );
    return Array.isArray(body) ? body : [];
  },
  async saveHostPlanV2(hostId, kind, plan) {
    const body = await readJson<{ ok?: boolean; error?: string }>(
      /* kind 同时放查询串和请求体：后端读请求体，走查用的假后端读查询串 */
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/plans?kind=${kind}`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ ...plan, kind }),
      }),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '保存方案失败');
    }
  },
  async deleteHostPlanV2(hostId, kind, planId) {
    const body = await readJson<{ ok?: boolean; error?: string }>(
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/plans/${encodeURIComponent(planId)}?kind=${kind}`),
        { method: 'DELETE' }),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '删除方案失败');
    }
  },
};
