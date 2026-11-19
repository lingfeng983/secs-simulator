import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DeviceOpsPanel } from './DeviceOpsPanel';
import type { BackendController } from '../../state/useBackend';
import type { PanelBaseProps } from './types';

/**
 * 设备与会话面板的静态渲染测试。
 *
 * <p>锁住合并后的渲染契约:一张表里同时有"读数"(速率/计数)和"操作"
 * (勾选 → 批量按钮),初始未勾选时批量按钮全部 disabled,全选复选框在表头,
 * 页面底部不再挂统计块。
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
        /* 已连接但设备离线（COMMUNICATING）：严格 GEM 下不能上报，界面给「上线」 */
        controlState: 'COMMUNICATING',
        hasPeer: true,
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
      { id: 'dev-b', state: 'OPEN', controlState: 'COMMUNICATING', device: 'DEV-B', units: 2, lastError: '', counters: {} },
    ],
    ceid: 2001,
    setCeid: () => undefined,
    configList: [],
    configTemplates: vi.fn().mockResolvedValue([]),
    bulkCreateSessions: vi.fn().mockResolvedValue({ created: [], failed: [] }),
    createDevicesBatch: vi.fn().mockResolvedValue({ created: [], failed: [], conflicts: [] }),
    batchTrigger: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    batchReload: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    batchClose: vi.fn().mockResolvedValue({ ok: 2, fail: 0 }),
    createSession: vi.fn().mockResolvedValue(undefined),
    trigger: vi.fn().mockResolvedValue(undefined),
    reloadSession: vi.fn().mockResolvedValue(undefined),
    closeSession: vi.fn().mockResolvedValue(undefined),
    connectSession: vi.fn().mockResolvedValue(undefined),
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
    /* 一张表:读数(端口/速率)与操作(重启/启动·停止)同处一行 */
    /* 标题行已删：名字只留在无障碍树上，页面上不再占一行 */
    expect(html).toContain('aria-label="设备与会话"');
    expect(html).not.toContain('panel-head');
    expect((html.match(/<table/g) ?? []).length).toBe(1);
    expect(html).toContain('>5001<');
    expect(html).toContain('>5002<');
    /* 端口列只显示数字：冒号是 host:port 的一部分，单独成列时是噪音 */
    expect(html).not.toContain(':5001');
    expect(html).toContain('报文/分');
    expect(html).toContain('已上报');
    /* 会话列已删除：会话 id 不再单占一列，只留设备名 */
    expect(html).not.toContain('>会话</th>');
    /* 状态筛选改成四态下拉（启动 / 停止 / 离线 / 在线），不再是三个按钮 */
    expect(html).toContain('按状态筛选设备');
    expect(html).toContain('<option value="started">启动</option>');
    expect(html).toContain('<option value="stopped">停止</option>');
    expect(html).toContain('<option value="offline">离线</option>');
    expect(html).toContain('<option value="online">在线</option>');
    /* 表头与内容一律左对齐：列上不再有 align-right / align-center */
    expect(html).not.toContain('align-right');
    /* 低频列(OPEN 徽标/单元/S9Fx/最近事件)退到 tooltip 与 CSV 导出,不再占表头 */
    expect(html).not.toContain('>S9Fx</th>');
    expect(html).not.toContain('>最近事件</th>');
    expect(html).not.toContain('>单元</th>');
    /* 但信息没丢:四态徽标的 title 里带着会话/控制状态 */
    expect(html).toContain('（OPEN · 在线）');
    expect(html).toContain('重启');
    expect(html).toContain('停止');
    /* 上线/下线只在链路建起来之后才出现：离线那行给「上线」，在线那行给「下线」 */
    expect(html).toContain('>上线</button>');
    expect(html).toContain('>下线</button>');
    expect(html).not.toContain('>上报</button>');
    expect(html).toContain('>3</td>');
    /* 两台设备各占一行 */
    expect(html).toContain('选择 DEV-A');
    expect(html).toContain('选择 DEV-B');
    expect((html.match(/aria-label="选择 /g) ?? []).length).toBe(2);
    /* 表头本身就是"全选"：勾上=全选当前筛选结果，再点=清空 */
    expect(html).toContain('aria-label="全选 / 清空"');
    /* 工具条上的「全选 / 清空选择」两个按钮已撤掉（功能挪进表头），行内多了「删除」 */
    expect(html).not.toContain('>全选</button>');
    expect(html).not.toContain('>清空选择</button>');
    expect(html).toContain('>删除</button>');
  });

  it('renders batch actions in the table toolbar with zero selection', () => {
    const html = renderPanel(makeBackend());
    /* 页面级动作(新建/批量)都落在表格工具条这一行，标题行不再有按钮 */
    expect(html).toContain('新建设备');
    /* 没勾选时不显示"已选 N 台"，只留按钮本身 */
    expect(html).not.toContain('已选 0 台');
    expect(html).toContain('批量同步配置');
    expect(html).toContain('批量重启');
    expect(html).toContain('批量停止');
    expect(html).toContain('批量删除');
    expect(html).not.toContain('批量上报');
    expect(html).not.toContain('批量新建');
    expect((html.match(/disabled=""/g) ?? []).length).toBeGreaterThanOrEqual(4);
  });

  it('drops the summary footnote', () => {
    const html = renderPanel(makeBackend());
    /* 统计块整体撤掉：一页只剩"一行操作 + 一张表" */
    expect(html).not.toContain('panel-footnote');
    expect(html).not.toContain('会话数');
    expect(html).not.toContain('活动报警 1');
  });

  it('shows hints for sessions without overview data', () => {
    const backend = makeBackend();
    backend.overview = [] as never;
    const html = renderPanel(backend);
    /* 总览缺行时端口显示 0,不渲染 NaN 或空单元格 */
    expect(html).toContain('>0<');
  });

  it('offers start instead of stop once a device is closed', () => {
    const backend = makeBackend();
    /* 停止态那一行要能再启动，而且不能同时出现「停止」——两个相反动作并排会让人不敢点。 */
    backend.sessions = [
      { id: 'dev-a', state: 'CLOSED', controlState: 'OFFLINE', device: 'DEV-A', units: 1, lastError: '', counters: {} },
    ] as never;
    const html = renderPanel(backend);
    expect(html).toContain('>启动</button>');
    expect(html).not.toContain('>停止</button>');
    /* 「重启」位置常在，但停止的设备上是灰的：不能点，也不消失 */
    expect(html).toContain('>重启</button>');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>重启<\/button>/);
  });

  it('renders idle database devices as stopped rows without session actions', () => {
    const backend = makeBackend();
    /* 数据库里有、还没启动会话的设备：显示「停止」行 + 「启动」一个动作，
       设备名不可点进运行区；它也是一台设备，所以照样能勾、能删。 */
    backend.configList = [
      { name: 'demo-etch.json', sizeBytes: 10, modifiedMs: 1 },
    ] as never;
    const html = renderPanel(backend);
    expect(html).toContain('demo-etch');
    expect(html).toContain('按已保存的设备配置创建会话');
    /* 未启动的库内设备也有「重启」一格（灰的），位置与会话行一致 */
    expect((html.match(/>重启<\/button>/g) ?? []).length).toBe(3);
    /* 两个会话行 + 一台库内设备，都有勾选框 */
    expect((html.match(/aria-label="选择 /g) ?? []).length).toBe(3);
  });
});
