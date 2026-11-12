import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ApiClient, BurstResult, HostTemplate, HostV2, SentMessageResult } from '../api';

/**
 * 3.0 主机域状态层（v2 独立命名空间）。
 *
 * <p>主机与设备无关：新建只填目标地址；连接/断开/使设置生效都是实例自己的
 * 动作。轮询节奏由调用方控制（详情页 2 秒、列表页 5 秒）。</p>
 */

export function useHostsV2(client: ApiClient | null) {
  const [hosts, setHosts] = useState<HostV2[]>([]);
  const [templates, setTemplates] = useState<HostTemplate[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    if (client === null) {
      return;
    }
    setLoading(true);
    try {
      const [list, tpl] = await Promise.all([client.hostsV2(), client.hostTemplatesV2()]);
      setHosts(list);
      setTemplates(tpl);
    } catch {
      /* 后端不可达时保留现有数据（SSE/下一次轮询会补） */
    } finally {
      setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const find = useCallback(
    (hostId: string) => hosts.find((host) => host.id === hostId) ?? null,
    [hosts],
  );

  const create = useCallback(
    async (request: { name?: string; host: string; port: number; deviceId?: number; autoReconnect?: boolean }) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      const hostId = await client.createHostV2(request);
      await refresh();
      return hostId;
    },
    [client, refresh],
  );

  const remove = useCallback(
    async (hostId: string) => {
      if (client === null) {
        return;
      }
      await client.deleteHostV2(hostId);
      await refresh();
    },
    [client, refresh],
  );

  const connect = useCallback(
    async (hostId: string) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      await client.connectHostV2(hostId);
      await refresh();
    },
    [client, refresh],
  );

  const disconnect = useCallback(
    async (hostId: string) => {
      if (client === null) {
        return;
      }
      await client.disconnectHostV2(hostId);
      await refresh();
    },
    [client, refresh],
  );

  const saveSettings = useCallback(
    async (hostId: string, request: { name: string; host: string; port: number; deviceId: number }) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      await client.saveHostV2(hostId, request);
      await refresh();
    },
    [client, refresh],
  );

  const apply = useCallback(
    async (hostId: string) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      await client.applyHostV2(hostId);
      await refresh();
    },
    [client, refresh],
  );

  const send = useCallback(
    async (hostId: string, sml: string): Promise<SentMessageResult> => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      return await client.sendHostV2(hostId, sml);
    },
    [client],
  );

  const saveTemplate = useCallback(
    async (request: { id?: string; name: string; host: string; port: number; deviceId?: number; note?: string }) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      await client.saveHostTemplateV2(request);
      await refresh();
    },
    [client, refresh],
  );

  const deleteTemplate = useCallback(
    async (id: string) => {
      if (client === null) {
        return;
      }
      await client.deleteHostTemplateV2(id);
      await refresh();
    },
    [client, refresh],
  );

  const stats = useMemo(() => ({
    total: hosts.length,
    connected: hosts.filter((host) => host.phase === 'CONNECTED').length,
    failed: hosts.filter((host) => host.phase === 'FAILED').length,
  }), [hosts]);

  return { hosts, templates, loading, stats, find, refresh, create, remove, connect, disconnect, apply, saveSettings, send, saveTemplate, deleteTemplate };
}

export type HostsV2State = ReturnType<typeof useHostsV2>;

/** 主机相位 → 徽标色调（设计 §3.3：四态）。 */
export function hostPhaseTone(phase: HostV2['phase']): 'ok' | 'warn' | 'danger' | 'muted' {
  switch (phase) {
    case 'CONNECTED':
      return 'ok';
    case 'CONNECTING':
      return 'warn';
    case 'FAILED':
      return 'danger';
    default:
      return 'muted';
  }
}

/** 主机相位 → 中文。 */
export function hostPhaseLabel(phase: HostV2['phase']): string {
  switch (phase) {
    case 'CONNECTED':
      return '已连接';
    case 'CONNECTING':
      return '连接中';
    case 'FAILED':
      return '连接失败';
    default:
      return '已断开';
  }
}
