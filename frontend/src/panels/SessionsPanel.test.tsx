import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { SessionsPanel } from './SessionsPanel';
import type { BackendController } from '../state/useBackend';
import type { PanelBaseProps } from './types';

/**
 * 会话面板的静态渲染测试。
 *
 * <p>项目没有 DOM 环境（renderToStaticMarkup 风格），交互行为
 * （勾选 → 批量按钮启用 → 确认弹窗）由真实后端 + 内置浏览器验证；
 * 这里锁住渲染契约：批量工具条的存在、初始禁用状态、脚注统计。</p>
 */
function makeBackend(overrides: Partial<BackendController> = {}): BackendController {
  return {
    sessions: [
      { id: 'dev-a', state: 'OPEN', controlState: 'ONLINE', device: 'DEV-A', units: 1, lastError: '', counters: { emitted: 3, s9Replies: 2 } },
      { id: 'dev-b', state: 'OPEN', controlState: 'OFFLINE', device: 'DEV-B', units: 1, lastError: '', counters: {} },
    ],
    ceid: 2001,
    setCeid: () => undefined,
    configList: [],
    bulkCreateSessions: vi.fn().mockResolvedValue({ created: [], failed: [] }),
    batchTrigger: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    batchReload: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    batchClose: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    createSession: vi.fn().mockResolvedValue(undefined),
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
  };
  return renderToStaticMarkup(<SessionsPanel {...props} />);
}

describe('SessionsPanel 渲染契约', () => {
  it('renders batch toolbar with zero selection and disabled buttons', () => {
    const html = renderPanel(makeBackend());
    expect(html).toContain('已选 0 台');
    expect(html).toContain('批量上报');
    expect(html).toContain('批量重载');
    expect(html).toContain('批量关闭');
    expect(html).toContain('批量新建');
    /* 初始未勾选:批量操作按钮全部 disabled(数量为 0 写进了 title) */
    expect(html).toContain('已选 0 台');
    expect(html).toContain('对选中的 0 台逐台触发');
    expect((html.match(/disabled=""/g) ?? []).length).toBeGreaterThanOrEqual(5);
  });

  it('renders per-session action buttons and selection checkboxes', () => {
    const html = renderPanel(makeBackend());
    expect(html).toContain('选择 dev-a');
    expect(html).toContain('选择 dev-b');
    expect((html.match(/aria-label="选择 /g) ?? []).length).toBe(2);
  });

  it('renders footnote counts from controller data', () => {
    const html = renderPanel(makeBackend());
    expect(html).toContain('会话数 2');
    expect(html).toContain('在线 1');
  });
});
