import { useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { controlStateLabel, SessionsPanel } from '../panels/SessionsPanel';
import { DataTable, type Column } from '../ui/DataTable';
import type { OverviewRow } from '../api';

/**
 * 工作台（总览 + 会话管理）。
 *
 * <p>只做两件事:一张设备总览表——所有设备的速率/报警/状态一目了然,
 * 点行进设备页;以及管理现有设备——会话管理(新建/重载/上报/批量操作)直接嵌在下方。</p>
 */
export function WorkbenchPage({ backend, notifier, preferences, updatePreferences, goTo }: PanelBaseProps): JSX.Element {
  const [stateFilter, setStateFilter] = useState<'all' | 'online' | 'offline'>('all');

  const rows = backend.overview;

  const visible = useMemo(() => rows.filter((row) => {
    if (stateFilter === 'online' && row.controlState !== 'ONLINE') {
      return false;
    }
    if (stateFilter === 'offline' && row.controlState === 'ONLINE') {
      return false;
    }
    return true;
  }), [rows, stateFilter]);

  const totals = useMemo(() => {
    let msgs = 0;
    let events = 0;
    let alarms = 0;
    let online = 0;
    for (const row of rows) {
      msgs += row.msgsPerMin;
      events += row.s6f11PerMin;
      alarms += row.counters.activeAlarms ?? 0;
      if (row.controlState === 'ONLINE') {
        online += 1;
      }
    }
    return { msgs, events, alarms, online };
  }, [rows]);

  const columns: Column<OverviewRow>[] = [
    {
      key: 'device',
      header: '设备',
      sortValue: (row) => row.device,
      render: (row) => (
        <button type="button" className="link" onClick={() => goTo?.(`device:${row.id}`)} title="进入设备页">
          {row.device}
        </button>
      ),
    },
    {
      key: 'state',
      header: '状态',
      sortValue: (row) => row.controlState,
      render: (row) => (
        <span className={`badge tone-${row.controlState === 'ONLINE' ? 'ok' : row.controlState === 'COMMUNICATING' ? 'warn' : 'muted'}`}>
          {controlStateLabel(row.controlState)}
        </span>
      ),
    },
    { key: 'id', header: '会话', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'port', header: '端口', align: 'right', sortValue: (row) => row.port, render: (row) => <span className="mono">:{row.port}</span> },
    { key: 'units', header: '单元', align: 'right', sortValue: (row) => row.units },
    {
      key: 'hostSim',
      header: '角色',
      sortValue: (row) => (row.hostSim ? '主机模拟' : '设备'),
      render: (row) => (row.hostSim ? <span className="badge tone-info">主机模拟</span> : <span className="hint-inline">设备</span>),
    },
    { key: 'msgs', header: '报文/分钟', align: 'right', sortValue: (row) => row.msgsPerMin, render: (row) => row.msgsPerMin },
    { key: 'events', header: '事件/分钟', align: 'right', sortValue: (row) => row.s6f11PerMin, render: (row) => row.s6f11PerMin },
    {
      key: 'alarms',
      header: '活动报警',
      align: 'right',
      sortValue: (row) => row.counters.activeAlarms ?? 0,
      render: (row) => (
        <span className={(row.counters.activeAlarms ?? 0) > 0 ? 'danger' : ''}>{row.counters.activeAlarms ?? 0}</span>
      ),
    },
    {
      key: 'lastEvent',
      header: '最近事件',
      sortValue: (row) => row.lastEventAt,
      render: (row) => (row.lastEventAt > 0 ? new Date(row.lastEventAt).toLocaleTimeString() : '—'),
    },
  ];

  return (
    <div className="panel-stack">
      {/* KPI 行 */}
      <div className="kpi-grid">
        <div className="kpi-card">
          <span className="kpi-label">在线设备</span>
          <span className="kpi-value">{totals.online}/{rows.length}</span>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">报文 / 分钟</span>
          <span className="kpi-value">{totals.msgs}</span>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">事件 / 分钟</span>
          <span className="kpi-value">{totals.events}</span>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">活动报警</span>
          <span className={`kpi-value${totals.alarms > 0 ? ' danger' : ''}`}>{totals.alarms}</span>
        </div>
      </div>

      {/* 设备总览表:一行一台设备,点行进设备页 */}
      <DataTable
        tableKey="workbench-devices"
        columns={columns}
        rows={visible}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索设备 / 会话 / 端口"
        exportName="devices"
        toolbar={
          <div className="segmented" role="tablist">
            {([['all', '全部'], ['online', '在线'], ['offline', '离线']] as const).map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={stateFilter === value ? 'active' : ''}
                onClick={() => setStateFilter(value)}
              >
                {label}
              </button>
            ))}
          </div>
        }
        emptyText={rows.length === 0
          ? '还没有设备。在下方「会话管理」从一份配置开一台,或重启后端加 --demo-session 看两台内置演示设备。'
          : '没有匹配过滤条件的设备。'}
        exportColumns={[
          { header: '设备', value: (row) => row.device },
          { header: '状态', value: (row) => controlStateLabel(row.controlState) },
          { header: '会话', value: (row) => row.id },
          { header: '端口', value: (row) => row.port },
          { header: '单元', value: (row) => row.units },
          { header: '报文/分钟', value: (row) => row.msgsPerMin },
          { header: '事件/分钟', value: (row) => row.s6f11PerMin },
          { header: '活动报警', value: (row) => row.counters.activeAlarms ?? 0 },
          { header: '最近事件', value: (row) => (row.lastEventAt > 0 ? new Date(row.lastEventAt).toISOString() : '') },
        ]}
        onRowClick={(row) => goTo?.(`device:${row.id}`)}
      />

      {/* 会话管理:开设备/关设备、批量操作、手动上报 CEID */}
      <SessionsPanel
        backend={backend}
        notifier={notifier}
        preferences={preferences}
        updatePreferences={updatePreferences}
        goTo={goTo}
      />
    </div>
  );
}
