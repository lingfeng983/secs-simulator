import { useCallback, useRef } from 'react';
import type { ApiClient, Health, PreviewData } from '../api';
import { downloadBlob, safeFilename } from '../lib/table';
import type { Notifier } from './useToasts';

/** 预览曲线默认桶数。 */
const PREVIEW_BUCKETS = 120;

/**
 * 曲线域操作：运行时预览、对比与二进制下载。
 *
 * <p>从 useBackend 拆出；报文域(历史/补拉/导出)已随全局报文页移除——
 * 报文只落盘服务端 JSONL,界面不再缓存。</p>
 */
export interface DataCommandsDeps {
  client: ApiClient | null;
  notifier: Notifier;
  setComparePreviewData: (data: PreviewData | null) => void;
  setCompareSessionId: (id: string) => void;
  setPreview: (data: PreviewData) => void;
  setPreviewSession: (id: string) => void;
  setHealth: (health: Health) => void;
  /** 当前选中会话;曲线下载要用。 */
  activeSession: string;
}


export function useDataCommands({
  client,
  notifier,
  setComparePreviewData,
  setCompareSessionId,
  setPreview,
  setPreviewSession,
  setHealth,
  activeSession,
}: DataCommandsDeps) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  const fetchPreview = useCallback(
    async (sessionId: string, fromMs: number, toMs: number) => {
      if (client === null) {
        return;
      }
      try {
        const data = await client.preview(sessionId, fromMs, toMs, PREVIEW_BUCKETS);
        setPreview(data);
        setPreviewSession(sessionId);
      } catch (error) {
        notifierRef.current.error('预览失败', (error as Error).message);
      }
    },
    [client],
  );

  const setComparePreview = useCallback(
    async (sessionId: string, fromMs: number, toMs: number) => {
      if (client === null) {
        return;
      }
      if (sessionId === '') {
        setComparePreviewData(null);
        setCompareSessionId('');
        return;
      }
      try {
        const data = await client.preview(sessionId, fromMs, toMs, PREVIEW_BUCKETS);
        setComparePreviewData(data);
        setCompareSessionId(sessionId);
      } catch (error) {
        notifierRef.current.error('拉取对比曲线失败', (error as Error).message);
      }
    },
    [client],
  );

  const downloadPreview = useCallback(
    async (windowMs: number) => {
      if (client === null || activeSession === '') {
        return;
      }
      try {
        const fresh = await client.health();
        setHealth(fresh);
        const to = Math.max(1, fresh.uptimeMs ?? 0);
        const from = Math.max(0, to - windowMs);
        const blob = await client.previewBinary(activeSession, from, to, PREVIEW_BUCKETS);
        downloadBlob(`preview-${safeFilename(activeSession)}-${Date.now()}.bin`, blob);
        notifierRef.current.success(`已下载 ${activeSession} 的二进制曲线（${blob.size} 字节）`);
      } catch (error) {
        notifierRef.current.error('下载二进制曲线失败', (error as Error).message);
      }
    },
    [client, activeSession],
  );

  return { fetchPreview, setComparePreview, downloadPreview };
}
