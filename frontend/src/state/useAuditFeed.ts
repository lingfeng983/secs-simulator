import { useCallback, useMemo, useRef, useState } from 'react';
import type { ApiClient, AuditEntryView } from '../api';
import type { Notifier } from './useToasts';
import { createRequestSeq } from './requestSeq';

/** 每页条数：轮询窗口与「载入更早」共用。 */
const AUDIT_PAGE = 200;

/** 审计条目的去重键：翻页合并时同一个动作可能从两页各来一次。 */
function auditEntryKey(entry: AuditEntryView): string {
  return `${entry.at}-${entry.actor}-${entry.action}-${entry.target}-${entry.detail}`;
}

/**
 * 审计域状态：最近窗口 + 服务端过滤 + 向更早翻页。
 *
 * <p>从 useBackend 拆出。轮询只刷新最近窗口，翻出来的旧页单独放，
 * 所以"过滤变化"和"轮询到点"互不覆盖；两者共用一个请求序号，
 * 后发先至的旧响应不能写回界面。</p>
 */
export interface AuditFeed {
  entries: AuditEntryView[];
  /** 「载入更早」翻出来的更旧页（轮询不刷新它，随过滤变化清空）。 */
  older: AuditEntryView[];
  /** 服务端过滤（action/actor 精确匹配，空串 = 全部）；轮询也带着它取数。 */
  filter: { action: string; actor: string };
  /** 最近一次拉取失败的原因；空串 = 正常。失败时保留上一份数据。 */
  error: string;
  refresh: () => Promise<void>;
  /** 设置过滤并立刻按新过滤取一次，同时清空翻页缓存。 */
  setFilter: (filter: { action?: string; actor?: string }) => Promise<void>;
  /** 以当前视图最早一条为游标向更早翻一页。 */
  loadOlder: () => Promise<void>;
}

export function useAuditFeed(client: ApiClient | null, notifier: Notifier): AuditFeed {
  const [entries, setEntries] = useState<AuditEntryView[]>([]);
  const [older, setOlder] = useState<AuditEntryView[]>([]);
  const [filter, setFilterState] = useState<{ action: string; actor: string }>({ action: '', actor: '' });
  const [error, setError] = useState('');

  const filterRef = useRef(filter);
  filterRef.current = filter;
  const seqRef = useRef(createRequestSeq());
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  const refresh = useCallback(async () => {
    if (client === null) {
      setEntries([]);
      setError('');
      return;
    }
    const token = seqRef.current.begin();
    try {
      const page = await client.audit(AUDIT_PAGE, filterRef.current);
      if (!seqRef.current.isCurrent(token)) {
        return;
      }
      setEntries(page);
      setError('');
    } catch (caught) {
      if (!seqRef.current.isCurrent(token)) {
        return;
      }
      /* 保留旧数据：后端短暂抖动时把表格闪空，会让人以为审计记录丢了 */
      setError((caught as Error).message);
    }
  }, [client]);

  const setFilter = useCallback(
    async (next: { action?: string; actor?: string }) => {
      /* 先同步更新 ref：否则紧接着触发的轮询还会带着旧过滤，
         它的响应会因为序号更新而胜出，界面就停在旧过滤结果上 */
      const merged = { ...filterRef.current, ...next };
      filterRef.current = merged;
      setFilterState(merged);
      setOlder([]);
      if (client === null) {
        return;
      }
      /* 用最新过滤直接取，不等下一次轮询；同时抢到新序号，
         让还在路上的旧过滤响应（以及旧轮询）自动作废 */
      const token = seqRef.current.begin();
      try {
        const page = await client.audit(AUDIT_PAGE, merged);
        if (!seqRef.current.isCurrent(token)) {
          return;
        }
        setEntries(page);
        setError('');
      } catch (caught) {
        if (!seqRef.current.isCurrent(token)) {
          return;
        }
        setError((caught as Error).message);
      }
    },
    [client],
  );

  const loadOlder = useCallback(async () => {
    if (client === null) {
      return;
    }
    const oldest = Math.min(
      ...[...entries, ...older].map((entry) => entry.at),
      Number.POSITIVE_INFINITY,
    );
    if (!Number.isFinite(oldest)) {
      return;
    }
    try {
      const page = await client.audit(AUDIT_PAGE, { ...filterRef.current, before: oldest });
      setOlder((previous) => {
        const seen = new Set(previous.map(auditEntryKey));
        const fresh = page.filter((entry) => !seen.has(auditEntryKey(entry)));
        return [...previous, ...fresh].sort((left, right) => right.at - left.at);
      });
    } catch (caught) {
      notifierRef.current.error('载入更早的审计失败', (caught as Error).message);
    }
  }, [client, entries, older]);

  return useMemo(
    () => ({ entries, older, filter, error, refresh, setFilter, loadOlder }),
    [entries, older, filter, error, refresh, setFilter, loadOlder],
  );
}
