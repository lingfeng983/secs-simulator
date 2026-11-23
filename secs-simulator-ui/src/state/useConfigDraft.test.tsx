// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { useConfigDraft, type ConfigDraftState } from './useConfigDraft';
import { getDraftSnapshot } from './configDraftStore';
import { actAsync, actSync, flush, renderHook, type RenderHookResult } from '../test/renderHook';
import type { BackendController } from './useBackend';
import type {
  ConfigSectionApplyResult,
  ConfigSectionName,
  ConfigSectionSaveResult,
} from '../api';

/**
 * `useConfigDraft` 的行为测试。
 *
 * <p>这个 hook 承载了「按页保存、按段生效」的全部时序：只推进本页的段、
 * 三种 409 守卫各自的出口、结构段延迟到重启后生效、整份保存后刷新目录。
 * 这些都没法靠静态渲染测，只能真挂载一次。</p>
 */

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
/** 每个用例独立配置名：草稿仓库是模块级单例，共用名字会串味。 */
function nameOf(): string {
  seq += 1;
  return `UD${seq}.json`;
}

type Draft = RenderHookResult<ConfigDraftState>;

/** 改草稿：包进 act，否则 useSyncExternalStore 的更新落在测试作用域外。 */
function edit(hook: Draft, text: string): void {
  actSync(() => {
    hook.result.current.setText(text);
  });
}

function saveResult(patch: Partial<ConfigSectionSaveResult> = {}): ConfigSectionSaveResult {
  return {
    status: 200,
    problems: [],
    changed: [],
    effect: 'hot',
    error: '',
    detail: '',
    ...patch,
  };
}

function applyResult(patch: Partial<ConfigSectionApplyResult> = {}): ConfigSectionApplyResult {
  return {
    status: 200,
    effect: 'hot',
    changed: [],
    reset: [],
    preserved: [],
    plansKept: 0,
    pinsDropped: 0,
    error: '',
    detail: '',
    ...patch,
  };
}

interface FakeOptions {
  /** 会话表：默认一台已启动（OPEN）的会话。 */
  sessions?: { id: string; config: string; state: string }[];
  text?: string | null;
  saveSections?: (
    name: string,
    payload: unknown,
    modifiedMs: number | null,
    overwrite?: boolean,
  ) => Promise<ConfigSectionSaveResult>;
  applySections?: () => Promise<ConfigSectionApplyResult>;
  saveConfig?: () => Promise<{ status: number; problems: string[] }>;
}

/** 只实现 useConfigDraft 真正用到的那几个方法，其余按类型硬转。 */
function fakeBackend(options: FakeOptions = {}): {
  backend: BackendController;
  calls: {
    saveSections: ReturnType<typeof vi.fn>;
    applySections: ReturnType<typeof vi.fn>;
    saveConfig: ReturnType<typeof vi.fn>;
    refreshConfigs: ReturnType<typeof vi.fn>;
  };
} {
  const calls = {
    saveSections: vi.fn(options.saveSections ?? (async () => saveResult())),
    applySections: vi.fn(options.applySections ?? (async () => applyResult())),
    saveConfig: vi.fn(options.saveConfig ?? (async () => ({ status: 200, problems: [] }))),
    refreshConfigs: vi.fn(async () => undefined),
  };
  const text = options.text === undefined ? BASE : options.text;
  const backend = {
    sessions: options.sessions ?? [{ id: 'S1', config: 'unused.json', state: 'OPEN' }],
    readConfig: async () => (text === null ? null : { text, modifiedMs: 100 }),
    validateConfig: async () => ({ valid: true, problems: [] }),
    saveConfigSections: calls.saveSections,
    applyConfigSections: calls.applySections,
    saveConfig: calls.saveConfig,
    refreshConfigs: calls.refreshConfigs,
  } as unknown as BackendController;
  return { backend, calls };
}

describe('useConfigDraft · 分段保存', () => {
  it('保存成功只推进本页的段：别的页仍是脏的', async () => {
    const name = nameOf();
    const { backend, calls } = fakeBackend({
      saveSections: async () => saveResult({ changed: ['cycle'] }),
    });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['cycle']));
    await flush();
    expect(hook.result.current.status).toBe('ready');

    /* 同时改两个段：cycle 是这页的，context 是「晶圆属性」页的 */
    edit(hook, BASE.replace('180000', '240000').replace('"A"', '"B"'));
    expect(hook.result.current.dirty).toBe(true);

    await actAsync(() => hook.result.current.save());

    expect(calls.saveSections).toHaveBeenCalledTimes(1);
    const snapshot = getDraftSnapshot(name);
    /* cycle 的基线推到了新值；context 不归这页，基线没动 */
    expect(snapshot.current.cycle).toBe(snapshot.baseline.cycle);
    expect(snapshot.current.context).not.toBe(snapshot.baseline.context);
    expect(hook.result.current.dirty).toBe(false);
    hook.unmount();
  });

  it('本页没有改动就不发请求', async () => {
    const name = nameOf();
    const { backend, calls } = fakeBackend();
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['cycle']));
    await flush();

    await actAsync(() => hook.result.current.save());

    expect(calls.saveSections).not.toHaveBeenCalled();
    expect(hook.result.current.notice).toBe('本页没有改动，没有保存');
    hook.unmount();
  });
});

describe('useConfigDraft · 409 守卫', () => {
  it('ecv-overwrite 弹确认框，确认后带 confirmOverwrite 重发', async () => {
    const name = nameOf();
    let overwrite: boolean | undefined;
    const { backend, calls } = fakeBackend({
      saveSections: async (_n, _p, _m, confirmOverwrite) => {
        overwrite = confirmOverwrite;
        return confirmOverwrite === true
          ? saveResult({ changed: ['constants'] })
          : saveResult({ status: 409, error: 'ecv-overwrite', detail: '常量 501 被主机改过' });
      },
    });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['constants']));
    await flush();
    edit(hook, BASE.replace('400', '450'));

    await actAsync(() => hook.result.current.save());

    expect(hook.result.current.confirm?.kind).toBe('ecv-overwrite');
    expect(calls.saveSections).toHaveBeenCalledTimes(1);
    expect(overwrite).toBe(false);

    await actAsync(() => hook.result.current.confirmPending());

    expect(calls.saveSections).toHaveBeenCalledTimes(2);
    expect(overwrite).toBe(true);
    expect(hook.result.current.confirm).toBeNull();
    hook.unmount();
  });

  it('cycle-running-guard 只提示、不给"强行保存"的出口', async () => {
    const name = nameOf();
    const { backend, calls } = fakeBackend({
      saveSections: async () => saveResult({
        status: 409,
        error: 'cycle-running-guard',
        detail: 'SVID 1001 正被运行中的周期引用',
      }),
    });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['svids']));
    await flush();
    edit(hook, BASE.replace('TEMP_PV', 'TEMP2_PV'));

    await actAsync(() => hook.result.current.save());

    /* 后端给了原因就照原话说；没有确认框，也没有"强行保存"的第二次机会 */
    expect(hook.result.current.confirm).toBeNull();
    expect(hook.result.current.notice).toBe('没有保存：SVID 1001 正被运行中的周期引用');
    expect(calls.saveSections).toHaveBeenCalledTimes(1);
    hook.unmount();
  });

  it('cycle-running-guard 没带原因时给一句"先停止周期"', async () => {
    const name = nameOf();
    const { backend } = fakeBackend({
      saveSections: async () => saveResult({ status: 409, error: 'cycle-running-guard' }),
    });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['svids']));
    await flush();
    edit(hook, BASE.replace('TEMP_PV', 'TEMP3_PV'));

    await actAsync(() => hook.result.current.save());

    expect(hook.result.current.notice).toContain('先停止周期');
    hook.unmount();
  });

  it('revision-conflict 提示先重新载入', async () => {
    const name = nameOf();
    const { backend } = fakeBackend({
      saveSections: async () => saveResult({ status: 409, error: 'revision-conflict' }),
    });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['cycle']));
    await flush();
    edit(hook, BASE.replace('180000', '200000'));

    await actAsync(() => hook.result.current.save());

    expect(hook.result.current.confirm).toBeNull();
    expect(hook.result.current.notice).toContain('重新载入');
    hook.unmount();
  });
});

describe('useConfigDraft · 结构段延迟生效', () => {
  it('只改了结构段：不热应用，提示重启后生效', async () => {
    const name = nameOf();
    const { backend, calls } = fakeBackend({
      saveSections: async () => saveResult({ changed: ['structure'] }),
    });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['structure', 'throttle']));
    await flush();
    edit(hook, BASE.replace('"ETCH-01"', '"ETCH-02"'));

    await actAsync(() => hook.result.current.save());

    /* 一次 apply 都不该发：设备继续跑旧模型 */
    expect(calls.applySections).not.toHaveBeenCalled();
    expect(hook.result.current.notice).toBe('已保存：结构改动在设备重启后生效');
    hook.unmount();
  });

  it('结构 + 热段一起保存：只把热段发给设备，并补一句重启后生效', async () => {
    const name = nameOf();
    const { backend, calls } = fakeBackend({
      saveSections: async () => saveResult({ changed: ['structure', 'throttle'] }),
      applySections: async () => applyResult({ changed: ['throttle'] }),
    });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['structure', 'throttle']));
    await flush();
    edit(hook, BASE.replace('"ETCH-01"', '"ETCH-02"').replace('"maxPerSec": 50', '"maxPerSec": 80'));

    await actAsync(() => hook.result.current.save());

    expect(calls.applySections).toHaveBeenCalledTimes(1);
    expect(calls.applySections.mock.calls[0][1]).toEqual(['throttle'] satisfies ConfigSectionName[]);
    expect(hook.result.current.notice).toContain('结构改动在设备重启后生效');
    hook.unmount();
  });
});

describe('useConfigDraft · 整份保存', () => {
  it('不传 sections：走整份保存并刷新配置目录', async () => {
    const name = nameOf();
    const { backend, calls } = fakeBackend();
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name));
    await flush();
    edit(hook, BASE.replace('180000', '999000'));

    await actAsync(() => hook.result.current.save());

    expect(calls.saveConfig).toHaveBeenCalledTimes(1);
    expect(calls.saveSections).not.toHaveBeenCalled();
    expect(calls.refreshConfigs).toHaveBeenCalledTimes(1);
    const snapshot = getDraftSnapshot(name);
    expect(snapshot.baselineText).toBe(snapshot.text);
    hook.unmount();
  });

  it('409：提示被其他窗口改过', async () => {
    const name = nameOf();
    const { backend } = fakeBackend({ saveConfig: async () => ({ status: 409, problems: [] }) });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name));
    await flush();
    edit(hook, BASE.replace('180000', '111000'));

    await actAsync(() => hook.result.current.save());

    expect(hook.result.current.notice).toContain('其他窗口修改过');
    hook.unmount();
  });
});

describe('useConfigDraft · 重新载入', () => {
  it('有未保存改动时先弹确认，不直接覆盖草稿', async () => {
    const name = nameOf();
    const { backend } = fakeBackend();
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['cycle']));
    await flush();
    edit(hook, BASE.replace('180000', '300000'));

    await actAsync(() => hook.result.current.reload());

    expect(hook.result.current.confirm?.kind).toBe('reload-discard');
    expect(getDraftSnapshot(name).text).toContain('300000');

    /* 取消：草稿原样留着 */
    actSync(() => hook.result.current.cancelPending());
    expect(hook.result.current.confirm).toBeNull();
    expect(getDraftSnapshot(name).text).toContain('300000');
    hook.unmount();
  });

  it('没有改动就直接重读磁盘', async () => {
    const name = nameOf();
    const { backend } = fakeBackend();
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['cycle']));
    await flush();

    await actAsync(() => hook.result.current.reload());

    expect(hook.result.current.confirm).toBeNull();
    expect(hook.result.current.notice).toBe(`已重新载入 ${name}`);
    hook.unmount();
  });
});

describe('useConfigDraft · 读盘失败', () => {
  it('文件读不到时状态是 missing 并给出说明', async () => {
    const name = nameOf();
    const { backend } = fakeBackend({ text: null, sessions: [] });
    const hook = renderHook(() => useConfigDraft(backend, 'S1', name, ['cycle']));
    await flush();

    expect(hook.result.current.status).toBe('missing');
    expect(hook.result.current.notice).toContain('读不到');
    hook.unmount();
  });
});
