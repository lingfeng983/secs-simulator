/**
 * 主机模拟域端点：S1F3 采集器、按会话的主机连接。
 *
 * <p>两套端点并存：`/host-sims/{id}/collector*`（实例 id 维度，多主机）与
 * `/sessions/{id}/host-sim*`（会话维度，一次一主机）。实例的创建/关闭/发送/压测
 * 走 v2（hostsV2.ts），这里只剩采集器与会话维度的端点。</p>
 */

import type {
  ApiClient, CollectorConfigInput, CollectorState,
} from './types';
import { apiUrl, parseCollectorResult, readJson, request } from './base';

type HostSimApi = Pick<
  ApiClient,
  | 'hostInstanceCollector'
  | 'setHostInstanceCollector'
  | 'hostInstanceCollectorCommand'
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
  async hostInstanceCollectorCommand(hostId, start, planId) {
    const query = planId === undefined || planId === ''
      ? ''
      : `?planId=${encodeURIComponent(planId)}`;
    const response = await request(
      apiUrl(`/api/v1/host-sims/${encodeURIComponent(hostId)}/collector/${start ? 'start' : 'stop'}${query}`),
      { method: 'POST' },
    );
    const text = await response.text();
    const parsed = parseCollectorResult(text);
    if (!response.ok) {
      throw new Error(parsed.error ?? text.slice(0, 200));
    }
    return parsed;
  },

};
