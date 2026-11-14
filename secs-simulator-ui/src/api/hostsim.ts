/**
 * 主机模拟域端点：S1F3 采集器、按会话的主机连接。
 *
 * <p>两套端点并存：`/host-sims/{id}/collector*`（实例 id 维度，多主机）与
 * `/sessions/{id}/host-sim*`（会话维度，一次一主机）。实例的创建/关闭/发送/压测
 * 走 v2（hostsV2.ts），这里只剩采集器与会话维度的端点。</p>
 */

import type {
  ApiClient, CollectorConfigInput, CollectorState, SentMessageResult,
} from './types';
import { apiUrl, commandStatus, LONG_REQUEST_TIMEOUT_MS, parseCollectorResult, readJson, request } from './base';

type HostSimApi = Pick<
  ApiClient,
  | 'hostInstanceCollector'
  | 'setHostInstanceCollector'
  | 'hostInstanceCollectorCommand'
  | 'openHostSim'
  | 'closeHostSim'
  | 'sendHostSml'
  | 'collectorState'
  | 'setCollector'
  | 'collectorCommand'
>;

export const hostSimApi: HostSimApi = {
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
