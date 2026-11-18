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

  /**
   * SSE {@code host-link} 的即时相位更新：只改这一台主机，不发请求。
   *
   * <p>相位是后端直出的枚举（{@code phase.name()}），payload 里给的就是它；
   * 自动重连会反复 CONNECTING→FAILED，每次收到都重拉整个列表既慢又会闪，
   * 所以就地改。{@code open}（实例是否打开）不随相位变，不用碰。</p>
   */
  const applyPhase = useCallback((hostId: string, phase: unknown) => {
    if (!isHostPhase(phase)) {
      return;
    }
    setHosts((previous) => {
      const index = previous.findIndex((host) => host.id === hostId);
      /* 找不到或相位没变：返回原数组，避免无谓重渲染 */
      if (index < 0 || previous[index].phase === phase) {
        return previous;
      }
      const next = [...previous];
      next[index] = { ...next[index], phase };
      return next;
    });
  }, []);

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
    apply, saveSettings, send, applyTemplate, saveTemplate, deleteTemplate, applyPhase,
  };
}

export type HostsV2State = ReturnType<typeof useHostsV2>;

/** 主机的四个链路相位（与后端 {@code HostSimulator.Phase} 同值域）。 */
const HOST_PHASES: readonly HostV2['phase'][] = ['DISCONNECTED', 'CONNECTING', 'CONNECTED', 'FAILED'];

/**
 * SSE payload 里来的相位是不是已知相位。
 *
 * <p>通知的 payload 是外部输入（JSON 解析结果），不校验就直接塞进状态，
 * 会让界面渲染出 `phase=undefined` 的徽标。</p>
 */
export function isHostPhase(value: unknown): value is HostV2['phase'] {
  return typeof value === 'string' && (HOST_PHASES as readonly string[]).includes(value);
}

/**
 * 主机相位 → 徽标色调：只有「已连接」是绿，其余都是中性灰。
 *
 * <p>CONNECTING / DISCONNECTED / FAILED 是连接过程的稳态或故障态，
 * 不该和报警红抢同一个视觉信号；故障信息由实例头动作按钮与错误行承担。
 * 调用方把返回值直接拼成 `tone-${tone}` / `conn-dot ${tone}`。</p>
 */
export function hostPhaseTone(phase: HostV2['phase']): 'ok' | 'muted' {
  return phase === 'CONNECTED' ? 'ok' : 'muted';
}

const HOST_PHASE_LABELS: Record<HostV2['phase'], string> = {
  CONNECTED: '已连接',
  CONNECTING: '连接中',
  DISCONNECTED: '未连接',
  FAILED: '重连中',
};

/**
 * 主机相位 → 中文（四态，各自对应一个真实相位，不把中间态塞进「停止」）。
 *
 * <p>CONNECTED=已连接、CONNECTING=连接中、DISCONNECTED=未连接、FAILED=重连中
 * （失败后由自动重连继续尝试）。"能不能发报文"看徽标色调与实例头动作按钮，
 * 这里只负责把当前相位说准。</p>
 */
export function hostPhaseLabel(phase: HostV2['phase']): string {
  return HOST_PHASE_LABELS[phase];
}
