import { useCallback, useRef } from 'react';
import type { ApiClient, Health, PreviewData } from '../api';
import { previewWindow } from '../lib/previewWindow';
import type { Notifier } from './useToasts';

/** 预览曲线默认桶数。 */
const PREVIEW_BUCKETS = 120;

/**
 * 曲线域操作：整台设备的运行时预览。
 *
 * <p>从 useBackend 拆出；对比曲线、二进制下载、草稿预览一直没有消费方，
 * 已随本次死代码清理删除（服务端仍有对应端点）。</p>
 */
export interface DataCommandsDeps {
  client: ApiClient | null;
  notifier: Notifier;
  setHealth: (health: Health) => void;
}

export function useDataCommands({ client, notifier, setHealth }: DataCommandsDeps) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  /**
   * 拉整台设备的曲线：所有 SVID + 事件标记。
   *
   * <p>不传 SVID 过滤 = 全部变量；后端返回的 events 与曲线同源，
   * 竖线位置就是事件真正发生的时刻。</p>
   *
   * <p>窗口与 previewSvid 一致：右端点是进程 uptime，左端点是
   * uptime - windowMs。原来写 0..windowMs 只覆盖"开局 N 秒"，
   * 跑久一点之后周期 60s 的计划全落在窗口外。</p>
   */
  const previewAll = useCallback(
    async (sessionId: string, windowMs: number): Promise<PreviewData | null> => {
      if (client === null || sessionId === '') {
        return null;
      }
      try {
        const fresh = await client.health();
        setHealth(fresh);
        const { from, to } = previewWindow(fresh.uptimeMs, windowMs);
        return await client.preview(sessionId, from, to, PREVIEW_BUCKETS, undefined, true);
      } catch (error) {
        notifierRef.current.error('预览失败', (error as Error).message);
        return null;
      }
    },
    [client, setHealth],
  );

  return { previewAll };
}
