# -*- coding: utf-8 -*-
"""App.tsx:goTo/路由分发/侧栏 props 接入 3.0 两段式路由。"""
path = 'src/app/App.tsx'
src = open(path, encoding='utf-8').read()

def rep(old, new):
    global src
    assert old in src, 'MISS: ' + old[:70].replace('\n', '\\n')
    src = src.replace(old, new, 1)

# ---- useHostsV2 实例(App 级:侧栏树 + 页面共用) ----
rep('''  const [route, navigate] = useRoute(preferences.activeTab);''',
'''  const [route, navigate] = useRoute(preferences.activeTab);
  const hostV2 = useHostsV2(client);''')

# ---- goTo:旧 id → 新路由 ----
rep('''      if (id.startsWith('device-tab:')) {
        const [, sessionId, tab] = id.split(':');
        if (sessionId !== undefined && DEVICE_PAGES.includes(tab as never)) {
          navigate({ name: 'device', sessionId, tab: tab as DevicePage });
        }
        return;
      }''',
'''      if (id.startsWith('device-tab:')) {
        /* 旧面板 id:映射进两段式 */
        const [, sessionId, tab] = id.split(':');
        const mapped: Record<string, [DeviceZone, DevicePage]> = {
          ceids: ['runtime', 'catalog'],
          reports: ['runtime', 'catalog'],
          events: ['runtime', 'plans'],
          alarms: ['runtime', 'manual'],
          svids: ['runtime', 'svids'],
          messages: ['runtime', 'messages'],
          send: ['runtime', 'manual'],
          connection: ['config', 'connection'],
          structure: ['config', 'structure'],
          config: ['config', 'document'],
        };
        const hit = mapped[tab] ?? ['runtime', 'catalog'];
        navigate({ name: 'device', sessionId, zone: hit[0], page: hit[1] });
        return;
      }
      if (id.startsWith('device-zone:')) {
        const [, sessionId, zone] = id.split(':');
        navigate({ name: 'device', sessionId, zone: zone as DeviceZone, page: zone === 'config' ? 'overview' : 'status' });
        return;
      }
      if (id.startsWith('device-page:')) {
        const [, sessionId, zone, page] = id.split(':');
        navigate({ name: 'device', sessionId, zone: zone as DeviceZone, page: page as DevicePage });
        return;
      }
      if (id.startsWith('host-open:')) {
        navigate({ name: 'host', hostId: id.slice('host-open:'.length), zone: 'config', page: 'overview' });
        return;
      }
      if (id.startsWith('host-zone:')) {
        const [, hostId, zone] = id.split(':');
        navigate({ name: 'host', hostId, zone: zone as HostZone, page: zone === 'config' ? 'overview' : 'status' });
        return;
      }
      if (id.startsWith('host-page:')) {
        const [, hostId, zone, page] = id.split(':');
        navigate({ name: 'host', hostId, zone: zone as HostZone, page: page as HostPage });
        return;
      }
      if (id === 'hosts') {
        navigate({ name: 'hosts' });
        return;
      }
      if (id === 'host-templates') {
        navigate({ name: 'host-templates' });
        return;
      }''')

rep('''      if (id.startsWith('hostsim-page:')) {
        const page = id.slice('hostsim-page:'.length);
        if (HOSTSIM_PAGES.includes(page as HostSimPage)) {
          navigate({ name: 'hostsim', page: page as HostSimPage });
        }
        return;
      }''',
'''      if (id.startsWith('hostsim-page:')) {
        /* 旧主机模拟子页 → 主机列表页(详情从实例树进) */
        navigate({ name: 'hosts' });
        return;
      }''')

# ---- 命令面板里设备/主机的入口 ----
rep('''      for (const tab of DEVICE_PAGES) {
        if (tab === 'ceids') {
          continue;
        }
        items.push({
          id: `device-tab:${session.id}:${tab}`,
          label: `${session.id} · ${DEVICE_PAGE_LABELS[tab]}`,
          group: '设备',
          keywords: [session.id, tab],
          run: () => goTo(`device-tab:${session.id}:${tab}`),
        });
      }''',
'''      items.push({
        id: `device-page:${session.id}:config`,
        label: `${session.id} · 配置区`,
        group: '设备',
        keywords: [session.id, 'config'],
        run: () => goTo(`device-open:${session.id}`),
      });
      items.push({
        id: `device-page:${session.id}:runtime`,
        label: `${session.id} · 运行区`,
        group: '设备',
        keywords: [session.id, 'runtime'],
        run: () => goTo(`device-tab:${session.id}:svids`),
      });''')

rep('''    for (const page of HOSTSIM_PAGES) {
      items.push({
        id: `hostsim-page:${page}`,
        label: `主机模拟 · ${HOSTSIM_PAGE_LABELS[page]}`,
        group: '主机模拟',
        keywords: ['hostsim', page],
        run: () => goTo(`hostsim-page:${page}`),
      });
    }''',
'''    for (const host of hostV2.hosts) {
      items.push({
        id: `host:${host.id}`,
        label: `打开主机 ${host.name}（${host.id}）`,
        group: '主机',
        hint: hostPhaseLabel(host.phase),
        keywords: ['host', host.id, host.name],
        run: () => goTo(`host-open:${host.id}`),
      });
    }''')

# ---- goTo default 分支里的 legacy 归位 ----
rep('''        case 'hostsim':
          navigate({ name: 'hostsim', page: 'hosts' });
          return;''',
'''        case 'hostsim':
          navigate({ name: 'hosts' });
          return;
        case 'host-templates':
          navigate({ name: 'host-templates' });
          return;''')

# ---- device-open id(命令面板里新加) ----
rep('''      if (id.startsWith('device:')) {
        navigate({ name: 'device', sessionId: id.slice('device:'.length), tab: 'ceids' });
        return;
      }''',
'''      if (id.startsWith('device:')) {
        navigate({ name: 'device', sessionId: id.slice('device:'.length), zone: 'runtime', page: 'catalog' });
        return;
      }
      if (id.startsWith('device-open:')) {
        navigate({ name: 'device', sessionId: id.slice('device-open:'.length), zone: 'config', page: 'overview' });
        return;
      }''')

# ---- routeKey ----
rep('''  const routeKey = route.name === 'device'
    ? `device:${route.sessionId}:${route.tab}`
    : route.name === 'hostsim'
      ? `hostsim:${route.page}`
      : route.name === 'system' ? `system:${route.page}` : route.name === 'templates' ? 'templates' : 'workbench';''',
'''  const routeKey = route.name === 'device'
    ? `device:${route.sessionId}:${route.zone}:${route.page}`
    : route.name === 'host'
      ? `host:${route.hostId}:${route.zone}:${route.page}`
      : route.name === 'hosts' ? 'hosts'
        : route.name === 'host-templates' ? 'host-templates'
          : route.name === 'system' ? `system:${route.page}`
            : route.name === 'templates' ? 'templates' : 'workbench';''')

# ---- 标题 ----
rep('''  const deviceName = route.name === 'device'
    ? backend.sessions.find((session) => session.id === route.sessionId)?.device ?? route.sessionId
    : '';
  const title = route.name === 'device'
    ? { group: '设备', label: deviceName, description: DEVICE_PAGE_DESCRIPTIONS[route.tab] }
    : route.name === 'hostsim'
      ? { group: '主机清单', label: '主机模拟', description: '以主机角色连任意设备地址:每台有独立的设置与收发记录。' }
      : routeTitle(route);''',
'''  const deviceName = route.name === 'device'
    ? backend.sessions.find((session) => session.id === route.sessionId)?.device ?? route.sessionId
    : '';
  const devicePageDescription = route.name === 'device'
    ? (route.zone === 'config' ? DEVICE_CONFIG_PAGE_LABELS : DEVICE_RUNTIME_PAGE_LABELS)[route.page as never]
    : '';
  const hostTitle = route.name === 'host'
    ? (() => {
      const host = hostV2.find(route.hostId);
      const label = host === null ? route.hostId : `${host.name} · ${host.id}`;
      const description = route.zone === 'config'
        ? HOST_CONFIG_PAGE_LABELS[route.page as never]
        : HOST_RUNTIME_PAGE_LABELS[route.page as never];
      return { group: '主机清单', label, description };
    })()
    : null;
  const title = route.name === 'device'
    ? { group: '设备清单', label: deviceName, description: devicePageDescription }
    : route.name === 'host' && hostTitle !== null
      ? hostTitle
      : routeTitle(route);''')

# ---- DEVICE_PAGE_DESCRIPTIONS 导入替换 ----
rep("import { DevicePage as DevicePageView, DEVICE_PAGE_DESCRIPTIONS } from '../features/pages/DevicePage';",
'''import { DevicePage as DevicePageView, DEVICE_PAGE_DESCRIPTIONS } from '../features/pages/DevicePage';
import {
  DEVICE_CONFIG_PAGES,
  DEVICE_CONFIG_PAGE_LABELS,
  DEVICE_RUNTIME_PAGES,
  DEVICE_RUNTIME_PAGE_LABELS,
  HOST_CONFIG_PAGE_LABELS,
  HOST_RUNTIME_PAGE_LABELS,
} from './router';''')

# ---- 路由渲染 ----
rep('''          {route.name === 'device' && (
            <DevicePageView {...panelProps} sessionId={route.sessionId} tab={route.tab} />
          )}
          {route.name === 'hostsim' && (
            <HostSimPageView {...panelProps} page={route.page} />
          )}
          {route.name === 'system' && <SystemPage {...panelProps} page={route.page} />}
      {route.name === 'templates' && <TemplatePage {...panelProps} />}''',
'''          {route.name === 'device' && (
            <DevicePageView {...panelProps} sessionId={route.sessionId} zone={route.zone} page={route.page} />
          )}
          {route.name === 'hosts' && <HostListPage {...panelProps} hosts={hostV2} />}
          {route.name === 'host' && (
            <HostDetailPage {...panelProps} hosts={hostV2} hostId={route.hostId} zone={route.zone} page={route.page} />
          )}
          {route.name === 'host-templates' && (
            <HostTemplatePage {...panelProps} hosts={hostV2} />
          )}
          {route.name === 'system' && <SystemPage {...panelProps} page={route.page} />}
      {route.name === 'templates' && <TemplatePage {...panelProps} />}''')

# ---- Sidebar props:hosts ----
rep('''      <Sidebar
        route={route}
        devices={sidebarDevices}''',
'''      <Sidebar
        route={route}
        devices={sidebarDevices}
        hostInstances={hostV2.hosts}
        onNavigateHost={(hostId) => goTo(`host-open:${hostId}`)}''')

# ---- 快捷键 NAV_ITEMS 引用不变;panelProps 不变 ----
open(path, 'w', encoding='utf-8', newline='\n').write(src)
print('app ok')
