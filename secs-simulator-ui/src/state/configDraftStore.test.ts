import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  getDraftSnapshot,
  loadDraft,
  markAllSaved,
  markSectionsSaved,
  setDraftText,
  subscribeDraft,
} from './configDraftStore';
import { changedSections } from '../shared/lib/configSections';

/** 一份最小但结构完整的配置（带 cycle 与 plans，便于验证按段基线）。 */
const BASE = JSON.stringify({
  session: { id: 'D1', port: 5000 },
  equipment: {
    name: 'ETCH-01',
    idSpace: { svidBase: 1000, ceidBase: 2000 },
    units: [{ unitId: 1, name: 'CH1', svids: [{ offset: 1, slot: 'TEMP_PV' }], reports: [], ceids: [] }],
    constants: [{ id: 501, value: 400 }],
  },
  events: { maxPerSec: 50, s6f11Wbit: true, s6f1Wbit: false, plans: [{ id: 'p1', ceid: 2001 }] },
  context: [{ id: 'LOTID', value: 'A' }],
  cycle: { durationMs: 180000, count: 25, events: [] },
}, null, 2);

let seq = 0;
/** 每个用例用独立配置名，避免共享仓库的模块级状态互相影响。 */
function nameOf(): string {
  seq += 1;
  return `T${seq}.json`;
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('configDraftStore', () => {
  it('载入后各段基线等于当前值：不脏', async () => {
    const name = nameOf();
    await loadDraft(name, async () => ({ text: BASE, modifiedMs: 1 }));
    const snap = getDraftSnapshot(name);
    expect(snap.status).toBe('ready');
    expect(snap.modifiedMs).toBe(1);
    expect(changedSections(snap.current, snap.baseline)).toEqual([]);
  });

  it('切页再进来不重新读盘：草稿留着', async () => {
    const name = nameOf();
    const loader = vi.fn(async () => ({ text: BASE, modifiedMs: 1 }));
    await loadDraft(name, loader);
    setDraftText(name, BASE.replace('180000', '240000'));

    /* 模拟另一页/回到本页时的再次挂载 */
    await loadDraft(name, loader);
    expect(loader).toHaveBeenCalledTimes(1);
    expect(getDraftSnapshot(name).text).toContain('240000');
  });

  it('只保存本页的段：别页的脏保留', async () => {
    const name = nameOf();
    await loadDraft(name, async () => ({ text: BASE, modifiedMs: 1 }));
    /* 同时改两段：周期（本页）与计划（另一页） */
    const both = JSON.parse(BASE) as Record<string, unknown>;
    (both.cycle as Record<string, unknown>).durationMs = 240000;
    (both.events as Record<string, unknown>).plans = [{ id: 'p1', ceid: 2001 }, { id: 'p2', ceid: 2002 }];
    setDraftText(name, JSON.stringify(both, null, 2));

    let snap = getDraftSnapshot(name);
    expect(changedSections(snap.current, snap.baseline)).toEqual(['cycle', 'plans']);

    /* 保存「工艺周期」页：只推进 cycle */
    markSectionsSaved(name, ['cycle']);
    snap = getDraftSnapshot(name);
    expect(changedSections(snap.current, snap.baseline)).toEqual(['plans']);
    /* 页签上「事件计划」还应为脏 */
    expect(changedSections(snap.current, snap.baseline, ['plans', 'throttle'])).toEqual(['plans']);
  });

  it('改回原值就不脏了（不是"改过就一直脏"）', async () => {
    const name = nameOf();
    await loadDraft(name, async () => ({ text: BASE, modifiedMs: 1 }));
    setDraftText(name, BASE.replace('180000', '240000'));
    expect(changedSections(getDraftSnapshot(name).current, getDraftSnapshot(name).baseline)).toEqual(['cycle']);
    setDraftText(name, BASE);
    expect(changedSections(getDraftSnapshot(name).current, getDraftSnapshot(name).baseline)).toEqual([]);
  });

  it('整份保存后所有段一起清脏', async () => {
    const name = nameOf();
    await loadDraft(name, async () => ({ text: BASE, modifiedMs: 1 }));
    setDraftText(name, BASE.replace('180000', '240000').replace('"p1"', '"p9"'));
    markAllSaved(name);
    const snap = getDraftSnapshot(name);
    expect(changedSections(snap.current, snap.baseline)).toEqual([]);
    expect(snap.baselineText).toBe(snap.text);
  });

  it('强制重新载入回到磁盘内容', async () => {
    const name = nameOf();
    await loadDraft(name, async () => ({ text: BASE, modifiedMs: 1 }));
    setDraftText(name, BASE.replace('180000', '240000'));
    await loadDraft(name, async () => ({ text: BASE, modifiedMs: 2 }), { force: true });
    const snap = getDraftSnapshot(name);
    expect(snap.text).toBe(BASE);
    expect(snap.modifiedMs).toBe(2);
    expect(changedSections(snap.current, snap.baseline)).toEqual([]);
  });

  it('读不到配置时进 missing，并给出提示', async () => {
    const name = nameOf();
    await loadDraft(name, async () => null);
    const snap = getDraftSnapshot(name);
    expect(snap.status).toBe('missing');
    expect(snap.message).toContain('读不到');
  });

  it('原文不是合法 JSON 时标记 parseable=false，退回全文比较', async () => {
    const name = nameOf();
    await loadDraft(name, async () => ({ text: '{bad json', modifiedMs: 1 }));
    const snap = getDraftSnapshot(name);
    expect(snap.status).toBe('ready');
    expect(snap.parseable).toBe(false);
  });

  it('订阅者能收到变化通知', async () => {
    const name = nameOf();
    await loadDraft(name, async () => ({ text: BASE, modifiedMs: 1 }));
    const listener = vi.fn();
    const unsubscribe = subscribeDraft(name, listener);
    setDraftText(name, BASE.replace('180000', '190000'));
    expect(listener).toHaveBeenCalled();
    const before = listener.mock.calls.length;
    unsubscribe();
    setDraftText(name, BASE);
    expect(listener.mock.calls.length).toBe(before);
  });

  it('空配置名返回同一份空快照（不建条目）', () => {
    expect(getDraftSnapshot('')).toBe(getDraftSnapshot(''));
    expect(getDraftSnapshot('').status).toBe('idle');
  });
});
