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

function makeBackend(sessions: unknown[] = []): BackendController {
  return {
    sessions,
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

function renderDevice(
  page: 'overview' | 'plans',
  zone: 'config' | 'runtime',
  sessions: unknown[] = [],
): string {
  const props: PanelBaseProps & { sessionId: string; zone: 'config' | 'runtime'; page: never } = {
    backend: makeBackend(sessions),
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

  it('配置导航按结构、常量、属性、变量的顺序展示', () => {
    const html = renderDevice('overview', 'config');
    const tabs = html.slice(html.indexOf('subtabs'), html.indexOf('tab-page'));
    const labels = ['概览', '链接配置', '结构配置', '设备常量', '晶圆属性', '变量配置', '事件与报告', '工艺周期', '事件计划', '全量配置', '版本历史'];
    let position = -1;
    for (const label of labels) {
      const next = tabs.indexOf(`>${label}<`);
      expect(next, label).toBeGreaterThan(position);
      position = next;
    }
  });

  it('未启动的设备也有运行区的全部页，不因状态少一页', () => {
    const html = renderDevice('plans', 'runtime');
    const tabs = html.slice(html.indexOf('subtabs'), html.indexOf('tab-page'));
    expect(tabs).toContain('事件执行');
    /* 「手动与压测」2026-11-20 拆成两页：消息发送 + 报警风暴 */
    expect(tabs).toContain('消息发送');
    expect(tabs).toContain('报警风暴');
    expect(tabs).not.toContain('手动与压测');
    /* 运行数据等启动后才有：在页面内容区里说明，而不是整页换掉 */
    expect(html).toContain('tab-page');
    expect(html).toContain('这台设备还没有启动');
    /* 配置区的子页照常可点，不是只剩配置区 */
    expect(html).toContain('配置区');
    expect(html).toContain('启动设备');
  });

  /* 2026-11-18 现场反馈：从清单点进来的地址 key 是配置文件名，启动后会话 id
     变成 ULID/legacy stem，页面却还在按 id 找自己——于是永远显示「启动设备」，
     运行区也拿不到数据。这里钉住"按配置来源反查会话"。 */
  it('设备已启动时按配置来源认回会话，不再显示「启动设备」', () => {
    const html = renderDevice('overview', 'config', [{
      id: 'dev-01',
      state: 'OPEN',
      controlState: 'OFFLINE',
      device: 'ETCH01',
      config: CONFIG_NAME,
      units: 1,
      counters: {},
    }]);
    expect(html).toContain('停止设备');
    expect(html).not.toContain('启动设备');
    /* 概览里的会话 id 用的是真实的那个，不是配置文件名 */
    expect(html).toContain('dev-01');
  });
});
