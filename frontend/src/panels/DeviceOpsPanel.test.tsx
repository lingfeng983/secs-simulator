import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DeviceOpsPanel } from './DeviceOpsPanel';
import type { BackendController } from '../state/useBackend';
import type { PanelBaseProps } from './types';

/**
 * 设备与会话面板的静态渲染测试。
 *
 * <p>锁住合并后的渲染契约:一张表里同时有"读数"(速率/计数)和"操作"
 * (勾选 → 批量按钮),初始未勾选时批量按钮全部 disabled,脚注统计来自数据。
 * 交互行为(勾选 → 批量执行 → 确认弹窗)由真实后端 + 内置浏览器验证。</p>
 */
function makeBackend(overrides: Partial<BackendController> = {}): BackendController {
  return {
    overview: [
      {
        id: 'dev-a',
        state: 'OPEN',
        controlState: 'ONLINE',
        hasPeer: true,
        device: 'DEV-A',
        units: 1,
        port: 5001,
        counters: { emitted: 3, activeAlarms: 0 },
        msgsPerMin: 6,
        s6f11PerMin: 2,
        lastEventAt: 0,
        uptimeMs: 60_000,
        hostSim: false,
      },
      {
        id: 'dev-b',
        state: 'OPEN',
        controlState: 'OFFLINE',
        hasPeer: false,
        device: 'DEV-B',
        units: 2,
        port: 5002,
        counters: { emitted: 0, activeAlarms: 1 },
        msgsPerMin: 0,
        s6f11PerMin: 0,
        lastEventAt: 0,
        uptimeMs: 0,
        hostSim: false,
      },
    ],
    sessions: [
      { id: 'dev-a', state: 'OPEN', controlState: 'ONLINE', device: 'DEV-A', units: 1, lastError: '', counters: { emitted: 3, s9Replies: 2 } },
      { id: 'dev-b', state: 'OPEN', controlState: 'OFFLINE', device: 'DEV-B', units: 2, lastError: '', counters: {} },
    ],
    ceid: 2001,
    setCeid: () => undefined,
    configList: [],
    configTemplates: vi.fn().mockResolvedValue([]),
    bulkCreateSessions: vi.fn().mockResolvedValue({ created: [], failed: [] }),
    batchTrigger: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    batchReload: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    batchClose: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    createSession: vi.fn().mockResolvedValue(undefined),
    trigger: vi.fn().mockResolvedValue(undefined),
    reloadSession: vi.fn().mockResolvedValue(undefined),
    closeSession: vi.fn().mockResolvedValue(undefined),
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

function renderPanel(backend: BackendController): string {
  const props: PanelBaseProps = {
    backend,
    notifier: notifier as never,
    preferences: {} as never,
    updatePreferences: () => undefined,
    goTo: () => undefined,
  };
  return renderToStaticMarkup(<DeviceOpsPanel {...props} />);
}

describe('DeviceOpsPanel 渲染契约', () => {
  it('merges overview and session data into one table', () => {
    const html = renderPanel(makeBackend());
    /* 一张表:读数(端口/速率)与操作(上报/重载/关闭)同处一行 */
    expect(html).toContain('设备与会话');
    expect((html.match(/<table/g) ?? []).length).toBe(1);
    expect(html).toContain('>5001<');
    expect(html).toContain('>5002<');
    /* 端口列只显示数字：冒号是 host:port 的一部分，单独成列时是噪音 */
    expect(html).not.toContain(':5001');
    expect(html).toContain('报文/分');
    expect(html).toContain('已上报');
    /* 低频列(OPEN 徽标/单元/S9Fx/最近事件)退到 tooltip 与 CSV 导出,不再占表头 */
    expect(html).not.toContain('>S9Fx</th>');
    expect(html).not.toContain('>最近事件</th>');
    expect(html).not.toContain('>单元</th>');
    /* 但信息没丢:控制徽标的 title 里带着状态与单元数 */
    expect(html).toContain('OPEN · 1 个单元');
    expect(html).toContain('重载');
    expect(html).toContain('关闭');
    expect(html).not.toContain('>上报</button>');
    expect(html).toContain('>3</td>');
    /* 两台设备各占一行 */
    expect(html).toContain('选择 dev-a');
    expect(html).toContain('选择 dev-b');
    expect((html.match(/aria-label="选择 /g) ?? []).length).toBe(2);
  });

  it('renders batch toolbar with zero selection and disabled buttons', () => {
    const html = renderPanel(makeBackend());
    expect(html).toContain('已选 0 台');
    expect(html).toContain('批量同步配置');
    expect(html).toContain('批量重载');
    expect(html).toContain('批量关闭');
    expect(html).not.toContain('批量上报');
    expect(html).not.toContain('批量新建');
    expect((html.match(/disabled=""/g) ?? []).length).toBeGreaterThanOrEqual(3);
  });

  it('renders footnote counts including alarm totals', () => {
    const html = renderPanel(makeBackend());
    expect(html).toContain('会话数 2');
    expect(html).toContain('在线 1');
    expect(html).toContain('活动报警 1');
  });

  it('shows hints for sessions without overview data', () => {
    const backend = makeBackend();
    backend.overview = [] as never;
    const html = renderPanel(backend);
    /* 总览缺行时端口显示 0,不渲染 NaN 或空单元格 */
    expect(html).toContain('>0<');
  });
});
