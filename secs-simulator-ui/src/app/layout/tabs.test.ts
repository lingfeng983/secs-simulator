import { describe, expect, it } from 'vitest';
import { MAX_TABS, nextTabAfterClose, tabIdForRoute, tabKindOf, upsertTab, type AppTab } from './tabs';

const workbench: AppTab = { id: 'workbench', label: '设备清单', hash: '#/workbench' };

describe('顶部页面标签', () => {
  it('设备与主机按对象建标签，子页切换不新开标签', () => {
    expect(tabIdForRoute({ name: 'device', sessionId: 'line1', zone: 'config', page: 'overview' }))
      .toBe('device:line1');
    expect(tabIdForRoute({ name: 'device', sessionId: 'line1', zone: 'runtime', page: 'manual' }))
      .toBe('device:line1');
    expect(tabIdForRoute({ name: 'host', hostId: 'host-1', zone: 'runtime', page: 'trace' }))
      .toBe('host:host-1');
    expect(tabIdForRoute({ name: 'workbench' })).toBe('workbench');
  });

  it('标签按设备 / 主机 / 其他三类上色', () => {
    expect(tabKindOf('workbench')).toBe('device');
    expect(tabKindOf('device:line1')).toBe('device');
    expect(tabKindOf('hosts')).toBe('host');
    expect(tabKindOf('host:host-1')).toBe('host');
    expect(tabKindOf('templates')).toBe('other');
    expect(tabKindOf('svid-templates')).toBe('other');
    expect(tabKindOf('host-templates')).toBe('other');
    expect(tabKindOf('system:audit')).toBe('other');
  });

  it('同一对象只更新停留位置，不重复追加', () => {
    const first = upsertTab([workbench], { id: 'device:line1', label: 'LINE1', hash: '#/device/line1/config/overview' });
    const second = upsertTab(first, { id: 'device:line1', label: 'LINE1', hash: '#/device/line1/runtime/plans' });
    expect(second).toHaveLength(2);
    expect(second[1].hash).toBe('#/device/line1/runtime/plans');
  });

  it('内容没变时返回原数组，避免每次导航都写一遍偏好', () => {
    const tabs: AppTab[] = [workbench, { id: 'device:line1', label: 'LINE1', hash: '#/device/line1/config/overview' }];
    expect(upsertTab(tabs, { id: 'device:line1', label: 'LINE1', hash: '#/device/line1/config/overview' })).toBe(tabs);
  });

  it('超过上限时淘汰最久未用的非当前标签，工作台常驻', () => {
    const tabs: AppTab[] = [workbench];
    for (let i = 0; i < MAX_TABS; i += 1) {
      tabs.push({ id: `device:d${i}`, label: `D${i}`, hash: `#/device/d${i}/config/overview` });
    }
    const next = upsertTab(tabs, { id: 'device:new', label: 'NEW', hash: '#/device/new/config/overview' });
    expect(next).toHaveLength(MAX_TABS);
    expect(next.some((tab) => tab.id === 'workbench')).toBe(true);
    expect(next.some((tab) => tab.id === 'device:d0')).toBe(false);
    expect(next.some((tab) => tab.id === 'device:new')).toBe(true);
  });

  it('关标签后优先切到右邻居，其次左邻居', () => {
    const tabs: AppTab[] = [
      workbench,
      { id: 'a', label: 'A', hash: '#/a' },
      { id: 'b', label: 'B', hash: '#/b' },
    ];
    expect(nextTabAfterClose(tabs, 'a')?.id).toBe('b');
    expect(nextTabAfterClose(tabs, 'b')?.id).toBe('a');
    expect(nextTabAfterClose([workbench], 'workbench')).toBeNull();
  });
});
