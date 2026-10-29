import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ApiClient, CollectorConfigInput, CollectorState, HostSimInstance } from '../api';

/**
 * 主机模拟的状态层（多主机）。
 *
 * <p>每个主机实例独立：各自的目标（本机设备 / 远程 SECS）、
 * 自己的采集器状态与数据。界面按实例 id 定位，不会串台。</p>
 */

/** 一个主机实例在界面上的状态：后端视图 + 采集器状态。 */
export interface HostSimView extends HostSimInstance {
  collector: CollectorState | null;
}

export interface HostSimCommands {
  /** 全部主机实例（含采集器状态）。 */
  hosts: HostSimView[];
  /** 正在刷新。 */
  loading: boolean;
  /** 当前选中的实例 id（为空表示没选）。 */
  selectedId: string;
  selected: HostSimView | null;
  select: (hostId: string) => void;
  /** 新建主机实例：session 为本机设备；host/port 给了就是远程。 */
  create: (request: {
    session: string;
    host?: string;
    port?: number;
    deviceId?: number;
  }) => Promise<boolean>;
  /** 断开一个主机实例。 */
  close: (hostId: string) => Promise<boolean>;
  /** 以主机角色发 SML。 */
  send: (hostId: string, sml: string) => Promise<string | null>;
  /** 下发采集器配置。 */
  saveCollector: (hostId: string, config: CollectorConfigInput) => Promise<string | null>;
  /** 手动启停采集器。 */
  collectorCommand: (hostId: string, start: boolean) => Promise<string | null>;
  /** 重新拉取全部实例。 */
  refresh: () => Promise<void>;
}

/**
 * 主机模拟命令。
 *
 * @param client API 客户端
 */
export function useHostSims(client: ApiClient | null): HostSimCommands {
  const [hosts, setHosts] = useState<HostSimView[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState('');

  /** 拉全部实例（含各自采集器状态）。 */
  const loadViews = useCallback(async (): Promise<HostSimView[]> => {
    if (client === null) {
      return [];
    }
    const list = await client.hostSims();
    /* 采集器状态按实例拉：多主机时各自的采集进度互相独立 */
    return Promise.all(list.map(async (host) => {
      try {
        const collector = await client.hostInstanceCollector(host.id);
        return { ...host, collector };
      } catch {
        return { ...host, collector: null };
      }
    }));
  }, [client]);

  const refresh = useCallback(async () => {
    if (client === null) {
      return;
    }
    setLoading(true);
    try {
      const views = await loadViews();
      setHosts(views);
      setSelectedId((previous) => {
        if (views.some((view) => view.id === previous)) {
          return previous;
        }
        return views.length > 0 ? views[0].id : '';
      });
    } catch {
      setHosts([]);
    } finally {
      setLoading(false);
    }
  }, [client, loadViews]);

  /* 首次进入拉一次；之后由调用方按需刷新 */
  useEffect(() => {
    void refresh();
  }, [refresh]);

  const create = useCallback(
    async (request: { session: string; host?: string; port?: number; deviceId?: number }): Promise<boolean> => {
      if (client === null) {
        return false;
      }
      /* 新建后自动选上新实例：否则"当前主机"还停在旧实例上，
         用户在下一个子页里发的报文会跑到另一台设备去。 */
      const before = new Set<string>();
      for (const view of hosts) {
        before.add(view.id);
      }
      await client.openHostInstance(request);
      const views = await loadViews();
      setHosts(views);
      const created = views.find((view) => !before.has(view.id));
      if (created !== undefined) {
        setSelectedId(created.id);
      }
      return true;
    },
    [client, hosts, loadViews],
  );

  const close = useCallback(
    async (hostId: string): Promise<boolean> => {
      if (client === null) {
        return false;
      }
      await client.closeHostInstance(hostId);
      await refresh();
      return true;
    },
    [client, refresh],
  );

  const send = useCallback(
    async (hostId: string, sml: string): Promise<string | null> => {
      if (client === null) {
        return null;
      }
      const result = await client.sendHostInstanceSml(hostId, sml);
      return JSON.stringify(result);
    },
    [client],
  );

  const saveCollector = useCallback(
    async (hostId: string, config: CollectorConfigInput): Promise<string | null> => {
      if (client === null) {
        return '后端未就绪';
      }
      try {
        const result = await client.setHostInstanceCollector(hostId, config);
        await refresh();
        return result.ok ? null : (result.error ?? '下发失败');
      } catch (error) {
        /* 抛出去会变成 unhandled rejection，面板只能显示"下发中…"然后什么都没有 */
        return (error as Error).message;
      }
    },
    [client, refresh],
  );

  const collectorCommand = useCallback(
    async (hostId: string, start: boolean): Promise<string | null> => {
      if (client === null) {
        return '后端未就绪';
      }
      try {
        const result = await client.hostInstanceCollectorCommand(hostId, start);
        await refresh();
        return result.ok ? null : (result.error ?? '操作失败');
      } catch (error) {
        return (error as Error).message;
      }
    },
    [client, refresh],
  );

  const selected = useMemo(
    () => hosts.find((view) => view.id === selectedId) ?? null,
    [hosts, selectedId],
  );

  return {
    hosts,
    loading,
    selectedId,
    selected,
    select: setSelectedId,
    create,
    close,
    send,
    saveCollector,
    collectorCommand,
    refresh,
  };
}
