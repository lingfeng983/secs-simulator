import { describe, expect, it } from 'vitest';
import {
  MAX_TABS,
  canonicalizeDeviceTabs,
  deviceTabKey,
  deviceTabLabel,
  nextTabAfterClose,
  tabIdForRoute,
  tabKindOf,
  upsertTab,
  type AppTab,
} from './tabs';

const workbench: AppTab = { id: 'workbench', label: '设备清单', hash: '#/workbench' };

/* 一台正在跑的 ETCH01：会话 id 是 ULID，配置文件名是 ETCH01.json */
const sessions = [{ id: '01JABCDEF', device: 'ETCH01', config: 'ETCH01.json' }];
const configs = [{ name: 'ETCH01.json' }, { name: 'CVD02.json' }];

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

  it('同一台设备的三种写法（配置全名 / 去掉 .json / 会话 id）落在同一个页签', () => {
    expect(deviceTabKey('ETCH01.json', sessions, configs)).toBe('ETCH01.json');
    expect(deviceTabKey('ETCH01', sessions, configs)).toBe('ETCH01.json');
    expect(deviceTabKey('01JABCDEF', sessions, configs)).toBe('ETCH01.json');
    expect(tabIdForRoute({ name: 'device', sessionId: 'ETCH01', zone: 'config', page: 'cycle' }, sessions, configs))
      .toBe('device:ETCH01.json');
    expect(tabIdForRoute({ name: 'device', sessionId: '01JABCDEF', zone: 'runtime', page: 'plans' }, sessions, configs))
      .toBe('device:ETCH01.json');
  });

  it('设备没启动（配置库里有、会话表里没有）时也按配置名归一', () => {
    expect(deviceTabKey('CVD02', [], configs)).toBe('CVD02.json');
    expect(deviceTabKey('CVD02.json', [], configs)).toBe('CVD02.json');
    /* 两边都找不到：保留地址里的原值，不要把它抹成空串 */
    expect(deviceTabKey('ghost', [], configs)).toBe('ghost');
  });

  it('页签标题：跑着的设备用设备名，没启动的用配置名（去掉 .json）', () => {
    expect(deviceTabLabel('01JABCDEF', sessions, configs)).toBe('ETCH01');
    expect(deviceTabLabel('ETCH01.json', sessions, configs)).toBe('ETCH01');
    expect(deviceTabLabel('CVD02', [], configs)).toBe('CVD02');
  });

  it('把已存下来的重复页签合并成一个，保留第一次出现的位置', () => {
    const tabs: AppTab[] = [
      workbench,
      { id: 'device:ETCH01.json', label: 'ETCH01', hash: '#/device/ETCH01.json/config/cycle' },
      { id: 'device:01JABCDEF', label: 'ETCH01', hash: '#/device/01JABCDEF/runtime/plans' },
    ];
    const next = canonicalizeDeviceTabs(tabs, sessions, configs);
    expect(next).toHaveLength(2);
    expect(next[1].id).toBe('device:ETCH01.json');
    /* 位置取第一次出现，内容取最后一次停留 */
    expect(next[1].hash).toBe('#/device/01JABCDEF/runtime/plans');
  });

  it('当前标签的停留位置以路由为准，不会被另一个旧标签覆盖', () => {
    const tabs: AppTab[] = [
      { id: 'device:01JABCDEF', label: 'ETCH01', hash: '#/device/01JABCDEF/runtime/plans' },
      { id: 'device:ETCH01.json', label: 'ETCH01', hash: '#/device/ETCH01.json/config/cycle' },
    ];
    const next = canonicalizeDeviceTabs(
      tabs, sessions, configs, 'device:ETCH01.json', '#/device/ETCH01.json/config/cycle',
    );
    expect(next).toHaveLength(1);
    expect(next[0].hash).toBe('#/device/ETCH01.json/config/cycle');
  });

  it('当前标签排在前面时，后面的旧标签也覆盖不了它的位置', () => {
    const tabs: AppTab[] = [
      { id: 'device:01JABCDEF', label: 'ETCH01', hash: '#/device/01JABCDEF/config/cycle' },
      { id: 'device:ETCH01.json', label: 'ETCH01', hash: '#/device/ETCH01.json/runtime/plans' },
    ];
    const next = canonicalizeDeviceTabs(
      tabs, sessions, configs, 'device:ETCH01.json', '#/device/ETCH01.json/config/cycle',
    );
    expect(next).toHaveLength(1);
    expect(next[0].hash).toBe('#/device/ETCH01.json/config/cycle');
  });

  it('没有重复也没有旧写法时返回原数组，轮询不会反复写偏好', () => {
    const tabs: AppTab[] = [
      workbench,
      { id: 'device:ETCH01.json', label: 'ETCH01', hash: '#/device/ETCH01.json/config/cycle' },
    ];
    expect(canonicalizeDeviceTabs(tabs, sessions, configs)).toBe(tabs);
  });

  it('同一对象只更新停留位置，不重复追加', () => {    const first = upsertTab([workbench], { id: 'device:line1', label: 'LINE1', hash: '#/device/line1/config/overview' });
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
