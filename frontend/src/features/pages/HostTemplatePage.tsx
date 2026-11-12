import { useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import type { HostTemplate } from '../../api';
import type { HostsV2State } from '../../state/useHostsV2';

/**
 * 独立工具 · 主机模板（第三轮修订 12）：预存目标地址/端口/Device ID 的
 * 主机实例模板；新建主机时可一键填入，也可以在这里直接删除。
 */
export function HostTemplatePage({
  hosts,
  notifier,
}: PanelBaseProps & { hosts: HostsV2State }): JSX.Element {
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<HostTemplate | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const columns: Column<HostTemplate>[] = [
    { key: 'name', header: '模板名', sortValue: (row) => row.name },
    {
      key: 'target',
      header: '目标',
      sortValue: (row) => row.host,
      render: (row) => <span className="mono">{row.host}:{row.port}</span>,
    },
    { key: 'deviceId', header: 'Device ID', align: 'right', sortValue: (row) => row.deviceId },
    { key: 'note', header: '说明', sortValue: (row) => row.note ?? '' },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            onClick={() => {
              setBusy(true);
              hosts.create({ name: row.name, host: row.host, port: row.port, deviceId: row.deviceId })
                .then(() => notifier.success(`已按模板「${row.name}」新建主机`))
                .catch((e: Error) => notifier.error('新建主机失败', e.message))
                .finally(() => setBusy(false));
            }}
            disabled={busy}
            title="按模板直接新建一台主机"
          >
            套用新建
          </button>
          <button type="button" onClick={() => setEditing(row)}>编辑</button>
          <button type="button" className="danger" onClick={() => setDeleteId(row.id)}>删除</button>
        </div>
      ),
    },
  ];

  return (
    <div className="panel-stack">
      <Panel
        id="panel-host-templates"
        title="主机模板"
        description="预存目标地址 / 端口 / Device ID 的主机实例模板;新建主机时可一键填入。模板只保存目标信息,与设备列表无关。"
        actions={
          <button type="button" className="primary" onClick={() => setCreateOpen(true)}>
            ＋ 新建模板
          </button>
        }
      >
        {error !== '' && <p className="inline-note danger">{error}</p>}
        <DataTable
          tableKey="host-templates"
          columns={columns}
          rows={hosts.templates}
          rowKey={(row) => row.id}
          searchPlaceholder="搜索模板名 / 目标"
          emptyText="还没有主机模板。点「＋ 新建模板」把常用目标存成模板。"
        />
      </Panel>

      <HostTemplateForm
        open={createOpen || editing !== null}
        editing={editing}
        busy={busy}
        onClose={() => {
          setCreateOpen(false);
          setEditing(null);
        }}
        onSubmit={async (request) => {
          setBusy(true);
          setError('');
          try {
            await hosts.saveTemplate(request);
            setCreateOpen(false);
            setEditing(null);
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      />

      <ConfirmModal
        open={deleteId !== null}
        title="删除模板"
        danger
        confirmLabel="删除"
        message={deleteId === null ? '' : `确定删除模板 ${deleteId}？已用模板建的主机不受影响。`}
        onClose={() => setDeleteId(null)}
        onConfirm={() => {
          if (deleteId !== null) {
            void hosts.deleteTemplate(deleteId);
          }
          setDeleteId(null);
        }}
      />
    </div>
  );
}

/** 新建/编辑模板表单。 */
function HostTemplateForm({
  open,
  editing,
  busy,
  onClose,
  onSubmit,
}: {
  open: boolean;
  editing: HostTemplate | null;
  busy: boolean;
  onClose: () => void;
  onSubmit: (request: { id?: string; name: string; host: string; port: number; deviceId?: number; note?: string }) => void;
}): JSX.Element {
  const [name, setName] = useState(editing?.name ?? '');
  const [host, setHost] = useState(editing?.host ?? '');
  const [port, setPort] = useState(String(editing?.port ?? 5000));
  const [deviceId, setDeviceId] = useState(String(editing?.deviceId ?? 0));
  const [note, setNote] = useState(editing?.note ?? '');
  const key = editing?.id ?? 'new';
  // 编辑目标切换时重置表单
  const [lastKey, setLastKey] = useState(key);
  if (key !== lastKey) {
    setLastKey(key);
    setName(editing?.name ?? '');
    setHost(editing?.host ?? '');
    setPort(String(editing?.port ?? 5000));
    setDeviceId(String(editing?.deviceId ?? 0));
    setNote(editing?.note ?? '');
  }

  const valid = name.trim() !== '' && host.trim() !== '' && Number(port) >= 1 && Number(port) <= 65535;

  return (
    <Modal
      open={open}
      title={editing === null ? '新建主机模板' : `编辑模板 ${editing.name}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary"
            disabled={busy || !valid}
            onClick={() => onSubmit({
              id: editing?.id,
              name: name.trim(),
              host: host.trim(),
              port: Number(port),
              deviceId: Number(deviceId) || 0,
              note: note.trim() === '' ? undefined : note.trim(),
            })}
          >
            保存模板
          </button>
        </>
      }
    >
      <div className="form-row">
        <label className="field">
          模板名
          <input value={name} onChange={(event) => setName(event.target.value)} placeholder="例如 产线 B 目标" />
        </label>
        <label className="field">
          目标地址
          <input value={host} onChange={(event) => setHost(event.target.value)} placeholder="任意设备地址" />
        </label>
      </div>
      <div className="form-row">
        <label className="field">
          端口
          <input type="number" min={1} max={65535} value={port} onChange={(event) => setPort(event.target.value)} />
        </label>
        <label className="field">
          Device ID
          <input type="number" min={0} value={deviceId} onChange={(event) => setDeviceId(event.target.value)} />
        </label>
      </div>
      <label className="field field-wide">
        说明
        <input value={note} onChange={(event) => setNote(event.target.value)} placeholder="可选" />
      </label>
    </Modal>
  );
}
