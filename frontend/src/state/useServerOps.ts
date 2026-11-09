import { useCallback, useMemo, useRef, useState } from 'react';
import type { ApiClient, BurstRequest, BurstResult, CollectorConfigInput, CollectorState, OverviewRow } from '../api';
import { createRequestSeq } from './requestSeq';

/**
 * 服务端聚合数据与后台任务：工作台 KPI、压测、S1F3 采集器。
 *
 * <p>从 useBackend 拆出。这一组都是"后端替我们算/跑"的只读或长任务，
 * 不参与本地状态机；压测的完成判定靠轮询，所以放在这里而不是 UI 里。</p>
 */
export interface ServerOps {
  /** 工作台聚合：每台设备的速率/计数一览。 */
  overview: OverviewRow[];
  refreshOverview: () => Promise<void>;
  burst: (sessionId: string, request: BurstRequest) => Promise<BurstResult>;
  collectorState: (sessionId: string) => Promise<CollectorState | null>;
  /** 下发采集器配置；返回错误文案或 null。 */
  saveCollector: (sessionId: string, config: CollectorConfigInput) => Promise<string | null>;
  collectorCommand: (sessionId: string, start: boolean) => Promise<string | null>;
}

export function useServerOps(client: ApiClient | null): ServerOps {
  const [overview, setOverview] = useState<OverviewRow[]>([]);
  const overviewSeqRef = useRef(createRequestSeq());

  /** 工作台聚合：失败时保留上一份，不让 KPI 闪成 0。 */
  const refreshOverview = useCallback(async () => {
    if (client === null) {
      return;
    }
    const token = overviewSeqRef.current.begin();
    try {
      const rows = await client.overview();
      if (!overviewSeqRef.current.isCurrent(token)) {
        return;
      }
      setOverview(rows);
    } catch (error) {
      /* 这里不占全局 backendError（health 轮询会把它清掉），但要留痕迹：
         老后端缺端点或瞬时失败时，保留上一份读数比清空更有用 */
      if (overviewSeqRef.current.isCurrent(token)) {
        console.warn('[overview] 拉取失败，保留上一份数据', error);
      }
    }
  }, [client]);

  /**
   * 服务端压测。
   *
   * <p>服务端把压测放到后台执行，POST 只回 202 受理；这里轮询 GET 进度直到
   * `running === false`，把最终统计返回给调用方。否则界面会把"受理"当成结果，
   * 显示"成功 0 / 失败 0"。</p>
   */
  const burst = useCallback(
    async (sessionId: string, request: BurstRequest): Promise<BurstResult> => {
      if (client === null) {
        throw new Error('未连接后端');
      }

      try {
        await client.burst(sessionId, request);
      } catch (error) {
        /* 409 = 该会话已有一个压测在跑：接着轮询那一轮，而不是报错 */
        if ((error as { status?: number }).status !== 409) {
          throw error;
        }
      }

      /* 服务端预算是 5 分钟；这里给 1 分钟底 + 请求本身的理论时长，最多 6 分钟 */
      const deadline = Date.now() + Math.min(
        6 * 60_000,
        60_000 + request.count * (request.intervalMs ?? 0),
      );

      for (;;) {
        await new Promise((resolve) => setTimeout(resolve, 500));
        const status = await client.burstStatus(sessionId);
        if (status.running !== true) {
          return {
            sent: status.sent ?? 0,
            failed: status.failed ?? 0,
            durationMs: status.durationMs ?? 0,
            latencyP50: status.latencyP50 ?? 0,
            latencyP95: status.latencyP95 ?? 0,
            latencyMax: status.latencyMax ?? 0,
          };
        }
        if (Date.now() > deadline) {
          throw new Error('压测仍未结束（服务端可能仍在执行，可稍后重试或查看服务端日志）');
        }
      }
    },
    [client],
  );

  const collectorState = useCallback(
    async (sessionId: string): Promise<CollectorState | null> => {
      if (client === null) {
        return null;
      }
      return client.collectorState(sessionId);
    },
    [client],
  );

  const saveCollector = useCallback(
    async (sessionId: string, config: CollectorConfigInput): Promise<string | null> => {
      if (client === null) {
        return '未连接后端';
      }
      return client.setCollector(sessionId, config);
    },
    [client],
  );

  const collectorCommand = useCallback(
    async (sessionId: string, start: boolean): Promise<string | null> => {
      if (client === null) {
        return '未连接后端';
      }
      return client.collectorCommand(sessionId, start);
    },
    [client],
  );

  return useMemo(
    () => ({ overview, refreshOverview, burst, collectorState, saveCollector, collectorCommand }),
    [overview, refreshOverview, burst, collectorState, saveCollector, collectorCommand],
  );
}
