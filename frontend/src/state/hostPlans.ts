import { usePersistentState } from './usePersistentState';

/**
 * 主机域的「配置区 → 运行区」共享草稿与已保存采集计划。
 *
 * <p>全部按 hostId 分桶落在 localStorage：配置区保存的多份 Trace / S1F3
 * 采集配置，运行区一键载入即可发送；表单草稿、要画哪几个参数也在同一个
 * 命名空间里，切页签/切区域后不会丢。</p>
 */

/** 一条已保存的 Trace 采集计划（对应一条 S2F23）。 */
export interface TracePlan {
  id: string;
  name: string;
  trid: number;
  dsper: number;
  totsmp: number;
  repgsz: number;
  /** 逗号/空格分隔的 SVID 文本，保持用户原始填写形态。 */
  svids: string;
  createdAt: number;
}

/** 一条已保存的 S1F3 采集配置。 */
export interface CollectPlan {
  id: string;
  name: string;
  startCeid: string;
  stopCeid: string;
  intervalMs: string;
  svids: string;
  createdAt: number;
}

/** Trace 表单草稿（配置区与运行区共用）。 */
export interface TraceDraft {
  trid: string;
  dsper: string;
  totsmp: string;
  repgsz: string;
  svids: string;
}

/** S1F3 采集表单草稿（配置区与运行区共用）。 */
export interface CollectDraft {
  startCeid: string;
  stopCeid: string;
  intervalMs: string;
  svids: string;
}

export const DEFAULT_TRACE_DRAFT: TraceDraft = {
  trid: '1', dsper: '1000', totsmp: '100', repgsz: '1', svids: '1001, 1003',
};

export const DEFAULT_COLLECT_DRAFT: CollectDraft = {
  startCeid: '2001', stopCeid: '2002', intervalMs: '1000', svids: '1001, 1003',
};

function hostKey(hostId: string, kind: string): string {
  return `secs.host.${hostId}.${kind}`;
}

/** 跨标签页也安全的轻量 id（不依赖 crypto.randomUUID 的可用性）。 */
export function planId(): string {
  const cryptoObj = globalThis.crypto as Crypto | undefined;
  if (cryptoObj !== undefined && typeof cryptoObj.randomUUID === 'function') {
    return cryptoObj.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}`;
}

export function useTracePlans(hostId: string) {
  return usePersistentState<TracePlan[]>(hostKey(hostId, 'trace-plans'), []);
}

export function useCollectPlans(hostId: string) {
  return usePersistentState<CollectPlan[]>(hostKey(hostId, 'collect-plans'), []);
}

export function useTraceDraft(hostId: string) {
  return usePersistentState<TraceDraft>(hostKey(hostId, 'trace-draft'), DEFAULT_TRACE_DRAFT);
}

export function useCollectDraft(hostId: string) {
  return usePersistentState<CollectDraft>(hostKey(hostId, 'collect-draft'), DEFAULT_COLLECT_DRAFT);
}

/** 主机「消息发送 / 手动操作」的 SML 草稿：报文模板页可以一键填入。 */
export function useHostSendDraft(hostId: string, kind: 'send' | 'manual' = 'send') {
  return usePersistentState<string>(hostKey(hostId, `${kind}-draft`), '');
}

/** S1F3 采集曲线里勾选要看的 SVID；空数组 = 按默认全选处理。 */
export function useCollectSelection(hostId: string) {
  return usePersistentState<number[]>(hostKey(hostId, 'collect-selection'), []);
}

/**
 * Trace 曲线里每个 TRID 单独记一组要看的 SVID：
 * 不同 Trace 采集计划可以分别调整，不互相覆盖。
 */
export function useTraceSelection(hostId: string) {
  return usePersistentState<Record<string, number[]>>(hostKey(hostId, 'trace-selection'), {});
}

/** 把「1, 2;3」这类输入解析成去重后的正整数列表。 */
export function parseIdList(text: string): number[] {
  const result: number[] = [];
  for (const piece of text.split(/[\s,;]+/)) {
    if (piece === '') {
      continue;
    }
    const value = Number(piece);
    if (Number.isInteger(value) && value >= 1 && !result.includes(value)) {
      result.push(value);
    }
  }
  return result;
}
