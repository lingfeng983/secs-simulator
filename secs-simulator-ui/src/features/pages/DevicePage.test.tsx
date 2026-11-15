import { renderToStaticMarkup } from 'react-dom/server';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { DevicePage } from './DevicePage';
import type { BackendController } from '../../state/useBackend';
import type { PanelBaseProps } from '../panels/types';

/**
 * 设备详情页的页面骨架契约。
 *
 * <p>三条不能回退的约定：页面上不再有标题行（设备名/状态/指标不再占一行）、
 * 设备名仍留在无障碍树上、以及"设备启没启动"不影响这台设备有哪些页——
 * 未启动的设备同样有配置区与运行区两套子页。</p>
 */
let restoreConsole: (() => void) | null = null;

beforeAll(() => {
  /* 设备页刻意用 useLayoutEffect 钉住当前会话；静态渲染跑不到它，
     React 会打一条告警。这里只静音这一条，其余 console.error 照常输出。 */
  const original = console.error;
  const spy = vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
    if (String(args[0]).includes('useLayoutEffect does nothing on the server')) {
      return;
    }
    (original as (...rest: unknown[]) => void)(...args);
  });
  restoreConsole = () => {
    spy.mockRestore();
  };
});

afterAll(() => {
  restoreConsole?.();
});

const CONFIG_NAME = 'tpl-furnace.json';

function makeBackend(): BackendController {
  return {
    sessions: [],
    configList: [{ name: CONFIG_NAME, sizeBytes: 785, modifiedMs: 1, template: 'furnace' }],
    activeSession: '',
    setActiveSession: vi.fn(),
    catalog: null,
    eventPlans: [],
    overview: [],
    client: null,
    createSession: vi.fn().mockResolvedValue(201),
    connectSession: vi.fn().mockResolvedValue(undefined),
    closeSession: vi.fn().mockResolvedValue(undefined),
    setDeviceOnline: vi.fn().mockResolvedValue(undefined),
  } as unknown as BackendController;
}

const notifier = {
  toasts: [],
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  dismiss: () => undefined,
  dismissAll: () => undefined,
};

function renderDevice(page: 'overview' | 'plans', zone: 'config' | 'runtime'): string {
  const props: PanelBaseProps & { sessionId: string; zone: 'config' | 'runtime'; page: never } = {
    backend: makeBackend(),
    notifier: notifier as never,
    preferences: {} as never,
    updatePreferences: () => undefined,
    goTo: () => undefined,
    sessionId: CONFIG_NAME,
    zone,
    page: page as never,
  };
  return renderToStaticMarkup(<DevicePage {...props} />);
}

describe('DevicePage 骨架', () => {
  it('不渲染标题行，但设备名仍在无障碍树上', () => {
    const html = renderDevice('overview', 'config');
    expect(html).not.toContain('instance-head');
    expect(html).not.toContain('instance-title');
    expect(html).not.toContain('设备状态');
    expect(html).not.toContain('累计上报');
    expect(html).toContain('aria-label="设备 tpl-furnace"');
    expect(html).toContain('data-device="tpl-furnace"');
    /* 配置区 / 运行区与生命周期动作还在一条操作栏里 */
    expect(html).toContain('zone-switch');
    expect(html).toContain('运行区');
    expect(html).toContain('启动设备');
  });

  it('未启动的设备也有运行区的两页，不因状态少一页', () => {
    const html = renderDevice('plans', 'runtime');
    const tabs = html.slice(html.indexOf('subtabs'), html.indexOf('tab-page'));
    expect(tabs).toContain('事件执行');
    expect(tabs).toContain('手动与压测');
    /* 运行数据等启动后才有：在页面内容区里说明，而不是整页换掉 */
    expect(html).toContain('tab-page');
    expect(html).toContain('这台设备还没有启动');
    /* 配置区的子页照常可点，不是只剩配置区 */
    expect(html).toContain('配置区');
    expect(html).toContain('启动设备');
  });
});
