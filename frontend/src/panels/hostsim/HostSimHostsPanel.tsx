import { useState } from 'react';
import { Panel, PanelFootnote } from '../../ui/Panel';
import { DataTable, type Column } from '../../ui/DataTable';
import type { PanelBaseProps } from '../types';
import type { HostSimCommands, HostSimView } from '../../state/useHostSims';
import { HostTargetPicker } from './HostTargetPicker';

/**
 * 主机实例页。
 *
 * <p>所有主机实例的清单：谁连着哪台设备、连的是本机还是远程、开没开。
 * 新建主机在这里做（连本机设备或远程 SECS），其它三页只是"选一个实例来操作"。</p>
 */
export function HostSimHostsPanel({
  backend,
  notifier,
  hostSims,
}: PanelBaseProps & { hostSims: HostSimCommands }): JSX.Element {
  const [busy, setBusy] = useState(false);

  const columns: Column<HostSimView>[] = [
    {
      key: 'id',
      header: '实例',
      sortValue: (row) => row.id,
      render: (row) => <span className="mono">{row.id}</span>,
    },
    {
      key: 'state',
      header: '状态',
      sortValue: (row) => (row.open ? 1 : 0),
      render: (row) => (row.open
        ? <span className="badge tone-ok">已连接</span>
        : <span className="badge tone-muted">已断开</span>),
    },
    {
      key: 'mode',
      header: '类型',
      sortValue: (row) => row.mode,
      render: (row) => (row.remote
        ? <span className="badge tone-info">远程 SECS</span>
        : <span className="hint-inline">本机设备</span>),
    },
    {
      key: 'target',
      header: '目标',
      sortValue: (row) => row.target,
      render: (row) => (
        <span className="mono">
          {row.remote ? row.target : (row.session || '—')}
        </span>
      ),
    },
    {
      key: 'deviceId',
      header: 'Device ID',
      align: 'right',
      sortValue: (row) => row.deviceId,
      render: (row) => row.deviceId,
    },
    {
      key: 'collector',
      header: 'S1F3 采集',
      sortValue: (row) => (row.collector?.running ? 1 : 0),
      render: (row) => {
        const collector = row.collector;
        if (collector === null) {
          return <span className="hint-inline">—</span>;
        }
        if (collector.running) {
          return <span className="badge tone-ok">采集中</span>;
        }
        return collector.configured
          ? <span className="badge tone-warn">已配置</span>
          : <span className="hint-inline">未配置</span>;
      },
    },
    {
      key: 'error',
      header: '最后错误',
      sortValue: (row) => row.lastError ?? '',
      render: (row) => (row.lastError !== undefined && row.lastError !== ''
        ? <span className="danger">{row.lastError}</span>
        : <span className="hint-inline">—</span>),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button type="button" onClick={() => hostSims.select(row.id)} title="选为主机（后面三页按它操作）">
            选用
          </button>
          <button
            type="button"
            className="danger"
            disabled={busy}
            onClick={() => {
              setBusy(true);
              void hostSims
                .close(row.id)
                .then(() => notifier.success(`主机 ${row.id} 已断开`))
                .catch((error: Error) => notifier.error('断开主机失败', error.message))
                .finally(() => setBusy(false));
            }}
            title="断开这个主机实例"
          >
            断开
          </button>
        </div>
      ),
    },
  ];

  return (
    <Panel
      id="panel-hostsim-hosts"
      title="主机实例"
      description="一个主机 = 一条主机角色的 SECS 连接。可以同时开多个，各连各的：本机设备或远程 SECS。"
    >
      <HostTargetPicker backend={backend} notifier={notifier} hostSims={hostSims} />

      <DataTable
        tableKey="hostsim-hosts"
        columns={columns}
        rows={hostSims.hosts}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索实例 id / 目标"
        exportName="host-sims"
        onImport={async (rows) => {
          let done = 0;
          for (const row of rows) {
            const session = (row['会话'] ?? '').trim();
            if (session === '') {
              continue;
            }
            const deviceId = Number.parseInt(row['Device ID'] ?? '0', 10) || 0;
            const mode = (row['类型'] ?? 'local').trim();
            try {
              if (mode === 'remote') {
                const target = (row['目标'] ?? '').trim();
                const colon = target.lastIndexOf(':');
                if (colon <= 0) {
                  continue;
                }
                const host = target.slice(0, colon);
                const port = Number.parseInt(target.slice(colon + 1), 10);
                if (!Number.isFinite(port) || port < 1) {
                  continue;
                }
                await hostSims.create({ session, host, port, deviceId });
              } else {
                const occupied = hostSims.hosts.some((host) => host.open && host.session === session);
                if (occupied) {
                  continue;
                }
                await hostSims.create({ session, deviceId });
              }
              done += 1;
            } catch {
              /* 单行失败（端口占用/会话不存在）不阻断其余行 */
            }
          }
          notifier.success(`已导入 ${done} 个主机实例`);
        }}
        emptyText="还没有主机实例。先在上方选一台设备点「新建本机主机」，或连一台远程 SECS。"
        exportColumns={[
          { header: '实例', value: (row) => row.id },
          { header: '状态', value: (row) => (row.open ? '已连接' : '已断开') },
          { header: '类型', value: (row) => row.mode },
          { header: '目标', value: (row) => row.target },
          { header: '会话', value: (row) => row.session },
          { header: 'Device ID', value: (row) => row.deviceId },
        ]}
      />

      <PanelFootnote
        items={[
          { label: '实例数', value: hostSims.hosts.length },
          {
            label: '已连接',
            value: hostSims.hosts.filter((host) => host.open).length,
            tone: hostSims.hosts.some((host) => host.open) ? 'ok' : undefined,
          },
        ]}
      />
    </Panel>
  );
}
