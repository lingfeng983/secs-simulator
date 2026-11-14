import { useCallback, useMemo, useRef } from 'react';
import type { ApiClient, PreviewData } from '../api';
import { previewWindow } from '../shared/lib/previewWindow';
import type { Notifier } from './useToasts';

/**
 * 运行时只读预览：只回放某一条编排计划。
 *
 * <p>定义类编辑（计划 / 报告 / 事件 / 状态变量的增删改）已上收配置区，
 * 直接写配置草稿；运行区不再提供运行时目录编辑，因此原 runEdit 链
 * （add/update/delete × plans/ceids/reports/svids、钉值）整体移除。</p>
 */
export interface DeviceEditDeps {
  client: ApiClient | null;
  notifier: Notifier;
}

export function useDeviceEdits({ client, notifier }: DeviceEditDeps) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  /** 只回放某一条编排计划，用于「这个事件及其参数的周期预览」。 */
  const previewPlan = useCallback(
    async (sessionId: string, planId: string, windowMs: number): Promise<PreviewData | null> => {
      if (client === null || planId === '') {
        return null;
      }
      try {
        const fresh = await client.health();
        const { from, to } = previewWindow(fresh.uptimeMs, windowMs);
        return await client.preview(sessionId, from, to, 120, undefined, true, planId);
      } catch (error) {
        notifierRef.current.error('事件预览失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  return useMemo(
    () => ({
      previewPlan,
    }),
    [previewPlan],
  );
}
