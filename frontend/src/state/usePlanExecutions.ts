/**
 * 事件计划执行台账（3.0 M4）。
 *
 * <p>数据源是 {@code GET /api/v2/devices/{id}/runtime/executions}：
 * 3 秒轮询兜底（SSE 断线/票据失效时仍能追上），收到 {@code plan-fired}
 * 通知立即补拉一次。明细始终以台账为准，SSE 只当"该刷新了"的信号。</p>
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { apiUrl, readJson, request } from '../api/base';
import { saveHint, saveTextFile } from '../shared/lib/saveFile';
import { toCsv } from '../shared/lib/table';
import type { NoticeWire } from './useEventStream';

/** 一次执行的状态（与后端 plan_execution.status 一致）。 */
export type ExecutionStatus = 'SENT' | 'PARTIAL' | 'FAILED' | 'SKIPPED';

/** 台账里的一条报文摘要。 */
export interface ExecutionMessage {
  kind: string;
  stream?: number;
  function?: number;
  wbit?: boolean;
  error?: string;
  elapsedMs?: number;
}

/** 台账里的一条 SVID 变化证据。 */
export interface ExecutionEffect {
  svid: number;
  op: string;
  before: number | null;
  after: number | null;
}

/** 一条执行台账。 */
export interface ExecutionRow {
  id: number;
  deviceId: string;
  planId: string;
  ceid: number;
  dryRun: boolean;
  scheduledAt: number;
  firedAt: number;
  status: ExecutionStatus;
  messages: ExecutionMessage[];
  effects: ExecutionEffect[];
  /** 发送全失败时的回滚标记：rolled_back / rollback_skipped。 */
  rollback?: string;
  error: string;
}

interface WireRow {
  id: number;
  deviceId: string;
  planId: string;
  ceid: number;
  dryRun: boolean;
  scheduledAt: number;
  firedAt: number;
  status: string;
  messages: unknown;
  effects: unknown;
  error: string;
}

function asArray(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter((item) => typeof item === 'object' && item !== null) as Record<string, unknown>[] : [];
}

function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** 后端 effects 可能是数组，也可能是带 rollback 标记的对象（发送失败回滚时）。 */
function normalize(row: WireRow): ExecutionRow {
  const effectsRaw = row.effects;
  const wrapped = typeof effectsRaw === 'object' && effectsRaw !== null && !Array.isArray(effectsRaw)
    ? effectsRaw as Record<string, unknown> : null;
  const source = wrapped === null ? effectsRaw : wrapped.items;
  const effects: ExecutionEffect[] = asArray(source).map((item) => ({
    svid: asNumber(item.svid) ?? 0,
    op: typeof item.op === 'string' ? item.op : '',
    before: asNumber(item.before),
    after: asNumber(item.after),
  }));
  const messages: ExecutionMessage[] = asArray(row.messages).map((item) => ({
    kind: typeof item.kind === 'string' ? item.kind : '',
    stream: asNumber(item.stream) ?? undefined,
    function: asNumber(item.function) ?? undefined,
    wbit: item.wbit === true,
    error: typeof item.error === 'string' ? item.error : undefined,
    elapsedMs: asNumber(item.elapsedMs) ?? undefined,
  }));
  const status = (['SENT', 'PARTIAL', 'FAILED', 'SKIPPED'] as const).includes(row.status as ExecutionStatus)
    ? row.status as ExecutionStatus : 'FAILED';
  return {
    id: row.id,
    deviceId: row.deviceId,
    planId: row.planId,
    ceid: row.ceid,
    dryRun: row.dryRun === true,
    scheduledAt: row.scheduledAt,
    firedAt: row.firedAt,
    status,
    messages,
    effects,
    rollback: typeof wrapped?.rollback === 'string' ? wrapped.rollback : undefined,
    error: typeof row.error === 'string' ? row.error : '',
  };
}

/** 台账轮询与导出。 */
export function usePlanExecutions(deviceId: string, liveEvents: NoticeWire[] | undefined) {
  const [rows, setRows] = useState<ExecutionRow[]>([]);
  const [error, setError] = useState('');
  const [onlyFailures, setOnlyFailures] = useState(false);
  const [includeDryRun, setIncludeDryRun] = useState(false);
  const [limit, setLimit] = useState(100);

  const refresh = useCallback(async () => {
    if (deviceId === '') {
      setRows([]);
      return;
    }
    const query = new URLSearchParams({ limit: String(limit), dryRun: includeDryRun ? 'all' : '0' });
    try {
      const body = await readJson<{ items?: WireRow[] }>(
        await request(apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/runtime/executions?${query}`)),
      );
      setRows((body.items ?? []).map(normalize));
      setError('');
    } catch (cause) {
      setError((cause as Error).message);
    }
  }, [deviceId, includeDryRun, limit]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 3000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  /* plan-fired 是节流后的合并帧：只当刷新信号，明细仍从台账拉 */
  const lastNoticeAt = useRef(0);
  useEffect(() => {
    const fired = (liveEvents ?? []).filter((notice) => notice.type === 'plan-fired');
    if (fired.length === 0) {
      return;
    }
    const latest = Math.max(...fired.map((notice) => notice.at));
    if (latest > lastNoticeAt.current) {
      lastNoticeAt.current = latest;
      void refresh();
    }
  }, [liveEvents, refresh]);

  const step = useCallback(async (planId: string): Promise<boolean> => {
    if (deviceId === '') {
      return false;
    }
    try {
      const response = await request(
        apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/runtime/plans/${encodeURIComponent(planId)}/step`),
        { method: 'POST' },
      );
      if (!response.ok) {
        setError(`试跑失败：${response.status}`);
        return false;
      }
      await refresh();
      return true;
    } catch (cause) {
      setError((cause as Error).message);
      return false;
    }
  }, [deviceId, refresh]);

  const visible = useMemo(
    () => onlyFailures ? rows.filter((row) => row.status !== 'SENT') : rows,
    [onlyFailures, rows],
  );

  const exportCsv = useCallback(async (): Promise<string> => {
    if (visible.length === 0) {
      return '没有可导出的执行记录';
    }
    const csv = toCsv(visible, [
      { header: '时间', value: (row) => new Date(row.firedAt).toISOString() },
      { header: '计划', value: (row) => row.planId },
      { header: 'CEID', value: (row) => row.ceid },
      { header: '状态', value: (row) => row.status },
      { header: '试跑', value: (row) => (row.dryRun ? '是' : '否') },
      { header: '报文数', value: (row) => row.messages.length },
      { header: 'SVID变化', value: (row) => row.effects.map((effect) =>
        `${effect.svid} ${effect.before ?? '-'}→${effect.after ?? '-'}`).join(' | ') },
      { header: '回滚', value: (row) => row.rollback ?? '' },
      { header: '错误', value: (row) => row.error },
    ]);
    const result = await saveTextFile('plan-executions.csv', csv, 'text/csv;charset=utf-8');
    return result.saved ? (saveHint(result) || '已导出执行记录') : '';
  }, [visible]);

  return {
    rows: visible,
    allRows: rows,
    error,
    onlyFailures,
    setOnlyFailures,
    includeDryRun,
    setIncludeDryRun,
    limit,
    setLimit,
    refresh,
    step,
    exportCsv,
  };
}
