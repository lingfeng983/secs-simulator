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

/** 主机实例模板。 */
export interface HostTemplate {
  id: string;
  name: string;
  host: string;
  port: number;
  deviceId: number;
  note?: string;
}

type HostsV2Api = Pick<
  ApiClient,
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
>;

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
    return response.ok;
  },
  async saveHostV2(hostId, req) {
    const body = await readJson<{ ok?: boolean; error?: string }>(
      await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(req),
      }),
    );
    if (!body.ok) {
      throw new Error(body.error ?? '保存设置失败');
    }
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
    await request(apiUrl(`/api/v2/hosts/${encodeURIComponent(hostId)}/disconnect`), { method: 'POST' });
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
    const body = await readJson<{ templates?: HostTemplate[] }>(
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
    return response.ok;
  },
};
