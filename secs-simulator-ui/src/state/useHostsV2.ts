import { useCallback, useEffect, useState } from 'react';
import type { ApiClient, HostV2, SentMessageResult } from '../api';
import type { CollectPlan, TracePlan } from './hostPlans';
import {
  BUILTIN_HOST_TEMPLATES,
  HOST_TEMPLATE_PLACEHOLDER,
  decodeHostTemplate,
  encodeHostTemplate,
  instantiateTemplatePlans,
  isHostTemplateBundle,
  type HostTemplate,
} from './hostTemplates';

/**
 * 3.0 主机域状态层（v2 独立命名空间）。
 *
 * <p>主机与设备无关：新建只填目标地址；连接/断开/使设置生效都是实例自己的
 * 动作。轮询节奏由调用方控制（详情页 2 秒、列表页 5 秒）。</p>
 *
 * <p>主机模板是「方案包」：两份内置模板 + 后端自定义模板，套用时把方案
 * 复制进该主机的方案库，模板本身不含目标地址 / 端口 / Device ID。</p>
 */

export interface CreateHostRequest {
  name?: string;
  host: string;
  port: number;
  deviceId?: number;
  autoReconnect?: boolean;
  /** 「立即连接」开关：缺省 true；false 时只建实例，之后手动点「连接」。 */
  connect?: boolean;
  /** 可选：新建后立刻把这套方案复制进新主机的配置区。 */
  template?: HostTemplate;
}

export interface SaveHostTemplateRequest {
  /** 有 id = 更新已有自定义模板；没有 id = 新建。 */
  id?: string;
  name: string;
  note?: string;
  tracePlans: TracePlan[];
  collectPlans: CollectPlan[];
}

export function useHostsV2(client: ApiClient | null) {
  const [hosts, setHosts] = useState<HostV2[]>([]);
  /* 两份内置模板不依赖后端：接口没回来/后端不可达时也能直接套用 */
  const [templates, setTemplates] = useState<HostTemplate[]>([...BUILTIN_HOST_TEMPLATES]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    if (client === null) {
      return;
    }
    setLoading(true);
    try {
      const [list, rows] = await Promise.all([client.hostsV2(), client.hostTemplatesV2()]);
      setHosts(list);
      const builtinIds = new Set(BUILTIN_HOST_TEMPLATES.map((item) => item.id));
      setTemplates([
        ...BUILTIN_HOST_TEMPLATES,
        ...rows
          .filter((row) => !builtinIds.has(row.id) && isHostTemplateBundle(row))
          .map(decodeHostTemplate),
      ]);
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

  /** 把模板里的方案逐条复制进某台主机的方案库（换 id，互不覆盖）。 */
  const applyTemplate = useCallback(
    async (hostId: string, template: HostTemplate) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      const plans = instantiateTemplatePlans(template);
      for (const plan of plans.trace) {
        await client.saveHostPlanV2(hostId, 'trace', plan as unknown as Record<string, unknown>);
      }
      for (const plan of plans.collect) {
        await client.saveHostPlanV2(hostId, 'collect', plan as unknown as Record<string, unknown>);
      }
    },
    [client],
  );

  const create = useCallback(
    async (request: CreateHostRequest) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      const { template, ...payload } = request;
      const hostId = await client.createHostV2(payload);
      if (template !== undefined) {
        await applyTemplate(hostId, template);
      }
      await refresh();
      return hostId;
    },
    [client, refresh, applyTemplate],
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

  /**
   * 保存主机设置：返回「改了什么、要不要使设置生效」。
   *
   * <p>名称是热生效的（不重建链路），目标变化才需要操作员点「使设置生效」；
   * 页面据此显示「设置待生效」横幅，而不是每次保存都提示一遍。</p>
   */
  const saveSettings = useCallback(
    async (hostId: string, request: { name: string; host: string; port: number; deviceId: number }) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      const result = await client.saveHostV2(hostId, request);
      await refresh();
      return result;
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
    async (request: SaveHostTemplateRequest) => {
      if (client === null) {
        throw new Error('后端未就绪');
      }
      await client.saveHostTemplateV2({
        id: request.id,
        name: request.name,
        ...HOST_TEMPLATE_PLACEHOLDER,
        note: encodeHostTemplate({
          name: request.name,
          note: request.note ?? '',
          tracePlans: request.tracePlans,
          collectPlans: request.collectPlans,
        }),
      });
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

  return {
    hosts, templates, loading, find, refresh, create, remove, connect, disconnect,
    apply, saveSettings, send, applyTemplate, saveTemplate, deleteTemplate,
  };
}

export type HostsV2State = ReturnType<typeof useHostsV2>;

/** 主机相位 → 徽标色调：界面只区分「链接」（绿）与「停止」（红）。 */
export function hostPhaseTone(phase: HostV2['phase']): 'ok' | 'danger' {
  return phase === 'CONNECTED' ? 'ok' : 'danger';
}

/**
 * 主机相位 → 中文。
 *
 * <p>主机只有两种可操作状态：链路通（链接）与没通（停止）。
 * CONNECTING / FAILED 这类中间态在实例头的动作按钮与错误行里已经能看出来，
 * 状态徽标再拆成四种只会让"现在到底能不能发报文"更难判断。</p>
 */
export function hostPhaseLabel(phase: HostV2['phase']): string {
  return phase === 'CONNECTED' ? '链接' : '停止';
}
