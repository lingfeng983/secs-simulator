import { useCallback, useRef } from 'react';
import type { ApiClient, Health, PreviewData } from '../api';
import { MAX_MESSAGES, mergeMessages, type MessageEntry } from '../messageHistory';
import { downloadBlob, safeFilename } from '../lib/table';
import type { Notifier } from './useToasts';

/** 预览曲线默认桶数。 */
const PREVIEW_BUCKETS = 120;

/**
 * 报文与曲线域操作：历史载入、重连补拉、导出、预览拉取与对比。
 *
 * <p>从 useBackend 拆出；报文合并管线与预览状态归调用方。</p>
 */
export interface DataCommandsDeps {
  client: ApiClient | null;
  notifier: Notifier;
  setMessages: (updater: (previous: MessageEntry[]) => MessageEntry[]) => void;
  setMessageTotal: (total: number) => void;
  nextMessageKey: { current: number };
  setComparePreviewData: (data: PreviewData | null) => void;
  setCompareSessionId: (id: string) => void;
  setPreview: (data: PreviewData) => void;
  setPreviewSession: (id: string) => void;
  setHealth: (health: Health) => void;
  /** 当前选中会话;历史载入与曲线下载要用。 */
  activeSession: string;
}


export function useDataCommands({
  client,
  notifier,
  setMessages,
  setMessageTotal,
  nextMessageKey,
  setComparePreviewData,
  setCompareSessionId,
  setPreview,
  setPreviewSession,
  setHealth,
  activeSession,
}: DataCommandsDeps) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  /**
   * 重连后补拉断线期间的增量：取最近 1000 条历史，
   * 只收时间戳比面板里最新一条新的。
   *
   * <p>SSE 断线期间后端不补发，这条补拉把缺口接上；
   * 历史被轮转掉时拿不全，提示里说明。</p>
   */
  const backfillAfterReconnect = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      const page = await client.messages('', '', 1000);
      setMessages((previous) => {
        if (previous.length === 0) {
          /* 面板本来就是空的：整页并入 */
          const merged = mergeMessages(previous, page.messages, nextMessageKey.current, MAX_MESSAGES);
          nextMessageKey.current = merged.nextKey;
          return merged.entries;
        }
        const newestAt = previous.reduce((max, item) => Math.max(max, item.msg.at), 0);
        const fresh = page.messages.filter((msg) => msg.at > newestAt);
        if (fresh.length === 0) {
          return previous;
        }
        const merged = mergeMessages(previous, fresh, nextMessageKey.current, MAX_MESSAGES);
        nextMessageKey.current = merged.nextKey;
        return merged.entries;
      });
      setMessageTotal(page.total);
      if (page.truncated) {
        notifierRef.current.info('重连补拉被截断：断线过久时中间可能有缺口，可手动「载入历史」');
      }
    } catch {
      /* 补拉失败不影响实时流；手动「载入历史」仍是兜底 */
      notifierRef.current.warn('重连后补拉增量失败，可手动「载入历史」补齐');
    }
  }, [client]);

  const loadHistory = useCallback(async (session = '', direction = '', before = 0) => {
    if (client === null) {
      return;
    }
    try {
      const page = await client.messages(session, direction, 1000, before > 0 ? { before } : undefined);
      setMessages((previous) => {
        const merged = mergeMessages(previous, page.messages, nextMessageKey.current, MAX_MESSAGES);
        nextMessageKey.current = merged.nextKey;
        return merged.entries;
      });
      setMessageTotal(page.total);
      notifierRef.current.success(
        page.truncated
          ? `已载入最近 ${page.messages.length} 条历史（共 ${page.total} 条）`
          : `已载入历史 ${page.messages.length} 条`,
      );
    } catch (error) {
      notifierRef.current.error('载入历史失败', (error as Error).message);
    }
  }, [client]);

  const exportMessages = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      const text = await client.messagesJsonl('', '', 5000);
      if (text.trim() === '') {
        notifierRef.current.info('没有可导出的报文');
        return;
      }
      downloadBlob(
        `messages-${new Date().toISOString().replace(/[:.]/g, '-')}.jsonl`,
        new Blob([text], { type: 'application/x-ndjson;charset=utf-8' }),
      );
      notifierRef.current.success(`已导出报文历史 ${text.trim().split('\n').length} 行`);
    } catch (error) {
      notifierRef.current.error('导出报文失败', (error as Error).message);
    }
  }, [client]);

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

  return { backfillAfterReconnect, loadHistory, exportMessages, fetchPreview, setComparePreview, downloadPreview };
}
