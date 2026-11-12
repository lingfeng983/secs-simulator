import { useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { Modal } from '../../shared/ui/Modal';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { hostPhaseLabel, hostPhaseTone, type HostsV2State } from '../../state/useHostsV2';
import type { HostV2 } from '../../api';
import { HostTemplatePicker } from '../panels/hostsim/HostTemplatePicker';

/**
 * 主机列表页（设计 §3.4）：与工作台同构——概览卡 + 表格 + 批量操作 + 新建。
 * 新建只填「名称 + 目标地址 + 端口 + Device ID」，可从主机模板一键填入；
 * 可连续新建任意多台，多台可指向同一地址。
 */
export function HostListPage({
  hosts,
  goTo,
}: PanelBaseProps & { hosts: HostsV2State }): JSX.Element {
  const [createOpen, setCreateOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const columns: Column<HostV2>[] = [
    {
      key: 'id',
      header: '实例',
      sortValue: (row) => row.id,
      render: (row) => (
        <button type="button" className="link" onClick={() => goTo?.(`host-open:${row.id}`)}>
          <span className="mono">{row.id}</span>
        </button>
      ),
    },
    { key: 'name', header: '名称', sortValue: (row) => row.name },
    {
      key: 'phase',
      header: '状态',
      sortValue: (row) => row.phase,
      render: (row) => (
        <span className={`badge tone-${hostPhaseTone(row.phase)}`}>{hostPhaseLabel(row.phase)}</span>
      ),
    },
    {
      key: 'target',
      header: '目标',
      sortValue: (row) => row.target,
      render: (row) => <span className="mono">{row.target}</span>,
    },
    { key: 'deviceId', header: 'Device ID', align: 'right', sortValue: (row) => row.deviceId },
    {
      key: 'revision',
      header: '设置 / 生效',
      sortValue: (row) => row.settingRevision,
      render: (row) => (
        <span className="mono">
          v{row.settingRevision} / {row.open ? `v${row.appliedRevision}` : '—'}
        </span>
      ),
    },
    {
      key: 'error',
      header: '最后错误',
      sortValue: (row) => row.lastError ?? '',
      render: (row) => (row.lastError ? <span className="danger">{row.lastError}</span> : <span className="hint-inline">—</span>),
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          {row.open ? (
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                void hosts.disconnect(row.id).finally(() => setBusy(false));
              }}
            >
              断开
            </button>
          ) : (
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                void hosts.connect(row.id).catch((e: Error) => setError(e.message)).finally(() => setBusy(false));
              }}
            >
              连接
            </button>
          )}
          <button type="button" onClick={() => goTo?.(`host-open:${row.id}`)}>
            详情
          </button>
          <button
            type="button"
            className="danger"
            disabled={busy || row.open}
            title={row.open ? '先断开再删除' : '删除主机实例'}
            onClick={() => {
              setBusy(true);
              void hosts.remove(row.id).finally(() => setBusy(false));
            }}
          >
            删除
          </button>
        </div>
      ),
    },
  ];

  return (
    <div className="panel-stack">
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{hosts.stats.total}</div>
          <div className="kpi-label">主机 · 可继续新建</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value tone-ok">{hosts.stats.connected}</div>
          <div className="kpi-label">已连接 · 各自独立</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value tone-danger">{hosts.stats.failed}</div>
          <div className="kpi-label">连接失败 · 失败即停</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{hosts.templates.length}</div>
          <div className="kpi-label">可用模板</div>
        </div>
      </div>

      <Panel
        id="panel-hosts-v2"
        title="主机模拟"
        description="每台主机独立运行,可同时开启多台,连接任意设备地址。断开只关连接,主机仍留在列表里;连接失败默认停下,可点「连接」重试。"
        actions={
          <button type="button" className="primary" onClick={() => setCreateOpen(true)}>
            ＋ 新建主机
          </button>
        }
      >
        {error !== '' && <p className="inline-note danger">{error}</p>}
        <DataTable
          tableKey="hosts-v2"
          columns={columns}
          rows={hosts.hosts}
          rowKey={(row) => row.id}
          searchPlaceholder="搜索实例 id / 名称 / 目标"
          emptyText="还没有主机实例。点「＋ 新建主机」,从模板填入或手填目标地址。"
          exportColumns={[
            { header: '实例', value: (row) => row.id },
            { header: '名称', value: (row) => row.name },
            { header: '状态', value: (row) => hostPhaseLabel(row.phase) },
            { header: '目标', value: (row) => row.target },
            { header: 'Device ID', value: (row) => row.deviceId },
          ]}
        />
      </Panel>

      <CreateHostModal
        open={createOpen}
        templates={hosts.templates}
        busy={busy}
        onClose={() => setCreateOpen(false)}
        onCreate={async (request) => {
          setBusy(true);
          setError('');
          try {
            const hostId = await hosts.create(request);
            setCreateOpen(false);
            goTo?.(`host-open:${hostId}`);
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      />
    </div>
  );
}

/** 新建主机弹窗：模板一键填入 + 目标地址表单。 */
function CreateHostModal({
  open,
  templates,
  busy,
  onClose,
  onCreate,
}: {
  open: boolean;
  templates: HostsV2State['templates'];
  busy: boolean;
  onClose: () => void;
  onCreate: (request: { name?: string; host: string; port: number; deviceId?: number }) => void;
}): JSX.Element {
  const [name, setName] = useState('');
  const [host, setHost] = useState('');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');

  const valid = host.trim() !== '' && Number(port) >= 1 && Number(port) <= 65535;

  return (
    <Modal
      open={open}
      title="新建主机"
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={busy || !valid}
            onClick={() => onCreate({
              name: name.trim() === '' ? undefined : name.trim(),
              host: host.trim(),
              port: Number(port),
              deviceId: Number(deviceId) || 0,
            })}
          >
            创建
          </button>
        </>
      }
    >
      {templates.length > 0 && (
        <HostTemplatePicker
          templates={templates}
          onPick={(template) => {
            setName(template.name);
            setHost(template.host);
            setPort(String(template.port));
            setDeviceId(String(template.deviceId));
          }}
        />
      )}
      <div className="form-row">
        <label className="field">
          名称
          <input value={name} onChange={(event) => setName(event.target.value)} placeholder="可空,默认用实例 id" />
        </label>
        <label className="field">
          目标地址
          <input value={host} onChange={(event) => setHost(event.target.value)} placeholder="任意设备或第三方模拟器地址" />
        </label>
      </div>
      <div className="form-row">
        <label className="field">
          端口
          <input type="number" min={1} max={65535} value={port}
            onChange={(event) => setPort(event.target.value)} />
        </label>
        <label className="field">
          Device ID
          <input type="number" min={0} value={deviceId}
            onChange={(event) => setDeviceId(event.target.value)} />
        </label>
      </div>
      <p className="hint">
        目标可以是任意设备或第三方模拟器地址;与设备列表无关。可连续新建多台,多台可指向同一地址
        （先连上的保持「已连接」,后来者停在「连接失败 · 目标已有主机」,互不影响）。
      </p>
    </Modal>
  );
}
