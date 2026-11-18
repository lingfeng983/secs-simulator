import { useCallback, useMemo, useSyncExternalStore } from 'react';
import type { ConfigSectionName } from '../api';
import { PAGE_SECTIONS, emptySectionCanonicals, sectionsFromText } from '../shared/lib/configSections';

/**
 * 配置草稿的共享仓库（2026-11-18 P0）。
 *
 * <p>此前每个配置页各自 {@code useConfigDraft}、各自在挂载时读一次盘：
 * 从「工艺周期」切到「事件计划」再切回来，前者的未保存改动就被重新读盘
 * 悄悄冲掉了——界面上的输入框还可能被 {@code useInputDraftCache} 回填，
 * 看着"还在"，实际早已不在草稿里。</p>
 *
 * <p>现在按<b>配置名</b>只留一份草稿：文本、磁盘基线、每段的规范值都在这里，
 * 页面只是它的视图。切页不再读盘，保存也只需要推进"本页那几段"的基线——
 * 别的页面未保存的改动仍然如实标成脏。</p>
 *
 * <p>按段基线（而不是一份 baselineText）是这次的关键：分段保存只写入了
 * 本页的段，如果直接把整份文本当新基线，其它页未保存的改动会被误标成
 * "已保存"。所以基线按段存，比较也按段做。</p>
 */

type SectionMap = Record<ConfigSectionName, string>;

/** 一份草稿的只读快照（每次变化换新对象，供 useSyncExternalStore 比较）。 */
export interface DraftSnapshot {
  status: 'idle' | 'loading' | 'ready' | 'missing';
  /** 当前草稿原文（含未保存改动） */
  text: string;
  /** 磁盘基线的原文（重新载入就是回到它；也用于解析失败时退回全文比较） */
  baselineText: string;
  modifiedMs: number | null;
  /** 每段的规范 JSON：当前草稿 / 磁盘基线 */
  current: SectionMap;
  baseline: SectionMap;
  /** 原文不是合法 JSON 时按段比较不可用，退回全文比较 */
  parseable: boolean;
  /** 读盘失败的说明（非空时页面要显示） */
  message: string;
}

const EMPTY_SECTIONS = emptySectionCanonicals();

function emptySnapshot(): DraftSnapshot {
  return {
    status: 'idle',
    text: '',
    baselineText: '',
    modifiedMs: null,
    current: EMPTY_SECTIONS,
    baseline: EMPTY_SECTIONS,
    parseable: false,
    message: '',
  };
}

interface Entry {
  snapshot: DraftSnapshot;
  listeners: Set<() => void>;
  loading: Promise<void> | null;
}

const entries = new Map<string, Entry>();

function entryOf(name: string): Entry {
  let entry = entries.get(name);
  if (entry === undefined) {
    entry = { snapshot: emptySnapshot(), listeners: new Set(), loading: null };
    entries.set(name, entry);
  }
  return entry;
}

function commit(name: string, patch: Partial<DraftSnapshot>): void {
  const entry = entryOf(name);
  entry.snapshot = { ...entry.snapshot, ...patch };
  for (const listener of entry.listeners) {
    listener();
  }
}

/** 订阅某份配置的草稿变化。 */
export function subscribeDraft(name: string, listener: () => void): () => void {
  const entry = entryOf(name);
  entry.listeners.add(listener);
  return () => {
    entry.listeners.delete(listener);
  };
}

/** 当前快照（同一状态返回同一对象，React 才不会无限重渲染）。 */
export function getDraftSnapshot(name: string): DraftSnapshot {
  if (name === '') {
    return EMPTY_NAMED;
  }
  return entryOf(name).snapshot;
}

const EMPTY_NAMED = emptySnapshot();

/**
 * 从磁盘载入一次草稿。
 *
 * <p>已经载入过就什么都不做——切页不该重新读盘，否则未保存的改动会消失。
 * {@code force} 只给「重新载入 / 保存后取新快照」用。</p>
 *
 * @param name    配置名
 * @param loader  读盘函数（返回 null 表示读不到）
 * @param options force = 强制重读
 */
export async function loadDraft(
  name: string,
  loader: () => Promise<{ text: string; modifiedMs: number | null } | null>,
  options: { force?: boolean } = {},
): Promise<void> {
  const entry = entryOf(name);
  if (options.force !== true && entry.snapshot.status !== 'idle') {
    return entry.loading ?? Promise.resolve();
  }
  const task = (async () => {
    commit(name, { status: 'loading', message: '' });
    try {
      const snapshot = await loader();
      if (snapshot === null) {
        commit(name, {
          status: 'missing',
          text: '',
          baselineText: '',
          modifiedMs: null,
          current: EMPTY_SECTIONS,
          baseline: EMPTY_SECTIONS,
          parseable: false,
          message: `读不到 ${name}，请确认文件是否存在`,
        });
        return;
      }
      const parsed = sectionsFromText(snapshot.text);
      commit(name, {
        status: 'ready',
        text: snapshot.text,
        baselineText: snapshot.text,
        modifiedMs: snapshot.modifiedMs,
        current: parsed.canonicals,
        baseline: parsed.canonicals,
        parseable: parsed.ok,
        message: '',
      });
    } finally {
      entry.loading = null;
    }
  })();
  entry.loading = task;
  return task;
}

/** 编辑草稿正文（每次都重算各段规范值，比较是纯字符串相等）。 */
export function setDraftText(name: string, text: string): void {
  const parsed = sectionsFromText(text);
  commit(name, { text, current: parsed.canonicals, parseable: parsed.ok });
}

/**
 * 某些段保存成功：只把这几段的基线推进到当前值。
 *
 * <p>其它段保持原基线，所以「别的页面还有未保存改动」这件事不会被
 * 一次本页保存抹掉。</p>
 */
export function markSectionsSaved(name: string, sections: readonly ConfigSectionName[]): void {
  const entry = entryOf(name);
  const baseline: SectionMap = { ...entry.snapshot.baseline };
  for (const section of sections) {
    baseline[section] = entry.snapshot.current[section];
  }
  commit(name, { baseline });
}

/** 整份保存成功（配置原文页）：所有段一起推进。 */
export function markAllSaved(name: string): void {
  const entry = entryOf(name);
  commit(name, { baseline: entry.snapshot.current, baselineText: entry.snapshot.text });
}

/** 保存后拿到新的 modifiedMs（下一次 If-Match 用它）；不动正文。 */
export function setDraftModifiedMs(name: string, modifiedMs: number | null): void {
  commit(name, { modifiedMs });
}

/** 订阅一份草稿（页面用）。 */
export function useDraftSnapshot(name: string): DraftSnapshot {
  const subscribe = useCallback((listener: () => void) => subscribeDraft(name, listener), [name]);
  const read = useCallback(() => getDraftSnapshot(name), [name]);
  return useSyncExternalStore(subscribe, read, read);
}

/**
 * 配置区各页签里哪些还有未保存改动（页签上点小圆点）。
 *
 * <p>一台设备可能同时改着好几页：只给"当前页有未保存改动"的提示不够——
 * 切走后那几页看不出还脏着。这里按页面声明的段算一遍，页签直接标出来。</p>
 */
export function useDirtyPages(configName: string): ReadonlySet<string> {
  const snapshot = useDraftSnapshot(configName);
  return useMemo(() => {
    const dirty = new Set<string>();
    if (configName === '' || snapshot.status !== 'ready') {
      return dirty;
    }
    for (const [page, sections] of Object.entries(PAGE_SECTIONS)) {
      if (sections.some((section) => snapshot.current[section] !== snapshot.baseline[section])) {
        dirty.add(page);
      }
    }
    return dirty;
  }, [configName, snapshot]);
}
