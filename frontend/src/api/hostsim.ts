/**
 * 主机模拟域端点：多主机实例、按会话的主机连接、S1F3 采集器。
 *
 * <p>两套端点并存：`/host-sims`（实例 id 维度，多主机）与
 * `/sessions/{id}/host-sim`（会话维度，一次一主机）。前者是后者的升级形态，
 * 但老界面仍用后者，故都保留。</p>
 */

import type {
  ApiClient, BurstResult, BurstStatus, CollectorConfigInput, CollectorState, HostSimInstance, SentMessageResult,
} from './types';
import { apiUrl, commandStatus, LONG_REQUEST_TIMEOUT_MS, parseCollectorResult, readJson, request } from './base';

type HostSimApi = Pick<
  ApiClient,
  | 'hostSims'
  | 'openHostInstance'
  | 'hostInstance'
  | 'closeHostInstance'
  | 'sendHostInstanceSml'
  | 'hostInstanceCollector'
  | 'setHostInstanceCollector'
  | 'hostInstanceCollectorCommand'
  | 'hostInstanceBurst'
  | 'hostInstanceBurstStatus'
  | 'openHostSim'
  | 'closeHostSim'
  | 'sendHostSml'
  | 'collectorState'
  | 'setCollector'
  | 'collectorCommand'
>;

export const hostSimApi: HostSimApi = {
  async hostSims() {
    const body = await readJson<{ hosts?: HostSimInstance[] }>(
      await request(apiUrl('/api/v1/host-sims')),
    );
    return body.hosts ?? [];
  },
  async openHostInstance(req) {
    const response = await request(apiUrl('/api/v1/host-sims'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(req),
    }, LONG_REQUEST_TIMEOUT_MS);
    if (!response.ok) {
      const text = await response.text();
      if (response.status === 409) {
        throw new Error('设备已有主机连接，或实例 id 已存在');
      }
      /* 后端的 {"ok":false,"error":"..."} 里通常有可读原因，别把整段 JSON 甩给用户 */
      let detail = text.slice(0, 200);
      try {
        const body = JSON.parse(text) as { error?: unknown };
        if (typeof body.error === 'string' && body.error !== '') {
          detail = body.error;
        }
      } catch {
        /* 非 JSON 响应就保留原文 */
      }
      throw new Error(`${response.status} ${detail}`);
    }
    return response.status;
  },
  async hostInstance(hostId) {
    const response = await request(apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}`));
    /* 实例不存在 = 没有这个主机，对调用方是 null 而不是错误 */
    if (!response.ok) {
      return null;
    }
    return readJson<HostSimInstance>(response);
  },
  async closeHostInstance(hostId) {
    return commandStatus(
      await request(apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}`), { method: 'DELETE' }),
    );
  },
  async sendHostInstanceSml(hostId, sml) {
    /* 以主机角色发 W 位报文同样要等设备回复，用长超时 */
    const response = await request(
      apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}/send`),
      {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: sml,
      },
      LONG_REQUEST_TIMEOUT_MS,
    );
    if (!response.ok) {
      throw new Error(`${response.status} ${(await response.text()).slice(0, 200)}`);
    }
    return readJson<SentMessageResult>(response);
  },
  async hostInstanceCollector(hostId) {
    const response = await request(apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}/collector`));
    /* 未配置采集器时后端回非 2xx，这里当"没有采集器"处理 */
    if (!response.ok) {
      return null;
    }
    return readJson<CollectorState>(response);
  },
  async setHostInstanceCollector(hostId, config) {
    const response = await request(apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}/collector`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(config),
    });
    /* 先读 text 再判：readJson 会先抛，后端给人看的 message 就丢了 */
    const text = await response.text();
    const parsed = parseCollectorResult(text);
    if (!response.ok) {
      throw new Error(parsed.error ?? text.slice(0, 200));
    }
    return parsed;
  },
  async hostInstanceCollectorCommand(hostId, start) {
    const response = await request(
      apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}/collector/${start ? 'start' : 'stop'}`),
      { method: 'POST' },
    );
    const text = await response.text();
    const parsed = parseCollectorResult(text);
    if (!response.ok) {
      throw new Error(parsed.error ?? text.slice(0, 200));
    }
    return parsed;
  },
  async hostInstanceBurst(hostId, payload) {
    /* 压测在服务端后台执行：这里的响应只是"受理"，最终统计由 hostInstanceBurstStatus 轮询取回 */
    const response = await request(
      apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}/burst`),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(payload),
      },
      LONG_REQUEST_TIMEOUT_MS,
    );
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${text.slice(0, 200)}`);
    }
    return JSON.parse(text) as BurstResult;
  },
  async hostInstanceBurstStatus(hostId) {
    return readJson<BurstStatus>(
      await request(apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}/burst`)),
    );
  },
  async openHostSim(sessionId, target) {
    return commandStatus(await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim`), {
      method: 'POST',
      headers: target === undefined ? undefined : { 'Content-Type': 'application/json; charset=utf-8' },
      body: target === undefined ? undefined : JSON.stringify({ target }),
    }, LONG_REQUEST_TIMEOUT_MS));
  },
  async closeHostSim(sessionId) {
    return commandStatus(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim`), { method: 'DELETE' }),
    );
  },
  async sendHostSml(sessionId, sml) {
    const response = await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/send`), {
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
      throw new Error('响应不是合法 JSON');
    }
  },
  async collectorState(sessionId) {
    /* 不吞错:采集器状态拿不到时调用方要能提示"后端不支持/不可达" */
    const body = await readJson<Partial<CollectorState>>(
      await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/collector`)),
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
  },
  async setCollector(sessionId, config: CollectorConfigInput) {
    /* 先读 text 再按内容判：readJson 会对 400 先抛，
       后端给人看的 message 就丢了（用户只剩 "400 {...}"）。 */
    const response = await request(apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/collector`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(config),
    });
    const parsed = parseCollectorResult(await response.text());
    return parsed.ok ? null : (parsed.error ?? '下发被后端拒绝');
  },
  async collectorCommand(sessionId, start) {
    const response = await request(
      apiUrl(`/api/v1/sessions/${encodeURIComponent(sessionId)}/host-sim/collector/${start ? 'start' : 'stop'}`),
      { method: 'POST' },
    );
    const parsed = parseCollectorResult(await response.text());
    return parsed.ok ? null : (parsed.error ?? '采集器命令被后端拒绝');
  },
};
