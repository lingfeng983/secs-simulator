import { useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import { Panel } from '../../shared/ui/Panel';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { hostPhaseLabel, hostPhaseTone, type HostsV2State } from '../../state/useHostsV2';
import type { HostV2 } from '../../api';
import { HostTemplatePicker } from '../panels/hostsim/HostTemplatePicker';
import type { HostTemplate } from '../../state/hostTemplates';
import { DEFAULT_REGION } from '../../state/useRegions';
import { deviceStatus } from '../../shared/lib/deviceStatus';
import { readDeviceEndpoint } from '../../state/deviceEndpoint';

/**
 * 主机清单页（设计 §3.4）：与设备清单同构——区域筛选 + 表格 + 新建。
 * 新建只填「名称 + 目标地址 + 端口 + Device ID」（都是实例自己的设置）；
 * 主机模板只提供 Trace / 采集方案，选中后复制进新主机的方案库。
 * 可连续新建任意多台，多台可指向同一地址。
 */
export function HostListPage({
  backend,
  hosts,
  goTo,
  notifier,
  regions,
}: PanelBaseProps & { hosts: HostsV2State }): JSX.Element {
  const [createOpen, setCreateOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState('');
  const [error, setError] = useState('');
  const [regionFilter, setRegionFilter] = useState('');
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  /** 待确认的删除目标：删主机不可撤销，一律先问一次。 */
  const [deleteTarget, setDeleteTarget] = useState<{ ids: readonly string[]; label: string } | null>(null);

  /* 「快速链接设备」的可选项：已经启动的设备（端口在监听）。
     选一台就能把它的地址/端口/Device ID 一次填好，名称用 host-设备名。 */
  const quickDevices = useMemo(
    () => backend.sessions
      .filter((session) => deviceStatus(session) !== 'stopped')
      .map((session) => ({
        id: session.id,
        label: `${session.device === '' ? session.id : session.device}（${session.id}）`,
      })),
    [backend.sessions],
  );

  /** 读出一台已启动设备的端点：配置没读到就用总览里的端口兜底。 */
  const pickQuickDevice = async (sessionId: string): Promise<{
    name: string; host: string; port: number | null; deviceId: number;
  } | null> => {
    const session = backend.sessions.find((item) => item.id === sessionId);
    if (session === undefined) {
      return null;
    }
    const configName = session.config ?? '';
    const modifiedMs = backend.configList.find((entry) => entry.name === configName)?.modifiedMs ?? 0;
    const endpoint = configName === '' ? null : await readDeviceEndpoint(backend, configName, modifiedMs);
    const port = endpoint?.port ?? backend.overview.find((row) => row.id === sessionId)?.port ?? null;
    /* 绑定到 0.0.0.0 的设备，主机要从回环地址连：0.0.0.0 不是可连接的目标 */
    const host = endpoint === null || endpoint.host === '' || endpoint.host === '0.0.0.0'
      ? '127.0.0.1'
      : endpoint.host;
    return {
      name: `host-${session.device === '' ? session.id : session.device}`,
      host,
      port,
      deviceId: endpoint?.deviceId ?? 0,
    };
  };

  const visible = useMemo(
    () => hosts.hosts.filter((host) => (
      regionFilter === '' || (regions?.hostRegion(host.id) ?? DEFAULT_REGION) === regionFilter
    )),
    [hosts.hosts, regionFilter, regions],
  );

  /* 勾选只认当前看得见的行：筛选一变，勾中的隐藏行不该继续参与批量动作 */
  const selectedRows = useMemo(
    () => visible.filter((host) => selected.has(host.id)),
    [visible, selected],
  );
  const allSelected = visible.length > 0 && selectedRows.length === visible.length;
  const someSelected = selectedRows.length > 0 && !allSelected;

  /** 逐台跑一遍批量动作，边跑边报进度；单台失败只记账不中断。 */
  const runBatch = async (label: string, targets: readonly HostV2[], step: (host: HostV2) => Promise<void>) => {
    if (targets.length === 0) {
      notifier.info(`选中的主机没有需要${label}的`);
      return;
    }
    setBusy(true);
    let done = 0;
    let failed = 0;
    try {
      for (let index = 0; index < targets.length; index += 1) {
        setProgress(`${label} ${index + 1}/${targets.length}`);
        try {
          await step(targets[index]);
          done += 1;
        } catch (e) {
          failed += 1;
          setError((e as Error).message);
        }
      }
      if (done === 0) {
        notifier.warn(`没有一台主机${label}成功`, failed > 0 ? `${failed} 台失败，见上方提示` : '');
      } else {
        notifier.success(`已${label} ${done} 台主机`, failed > 0 ? `${failed} 台失败` : '');
      }
      setSelected(new Set());
    } finally {
      setBusy(false);
      setProgress('');
    }
  };

  const columns: Column<HostV2>[] = [
    {
      key: 'select',
      header: '选择',
      width: '46px',
      className: 'select-column',
      /* 表头本身就是"全选"：勾上=全选当前筛选结果，再点=清空 */
      headerContent: (
        <input
          type="checkbox"
          checked={allSelected}
          ref={(element) => {
            if (element !== null) {
              element.indeterminate = someSelected;
            }
          }}
          onChange={() => setSelected(allSelected ? new Set() : new Set(visible.map((host) => host.id)))}
          onClick={(event) => event.stopPropagation()}
          aria-label="全选 / 清空"
          title="勾选=全选当前筛选结果，再点=清空"
        />
      ),
      render: (row) => (
        <input
          type="checkbox"
          checked={selected.has(row.id)}
          onClick={(event) => event.stopPropagation()}
          onChange={(event) => {
            const next = new Set(selected);
            if (event.target.checked) {
              next.add(row.id);
            } else {
              next.delete(row.id);
            }
            setSelected(next);
          }}
          aria-label={`选择 ${row.id}`}
        />
      ),
    },
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
      key: 'region',
      header: '区域',
      sortValue: (row) => regions?.hostRegion(row.id) ?? DEFAULT_REGION,
      render: (row) => {
        if (regions === undefined) {
          return <span className="hint-inline">{DEFAULT_REGION}</span>;
        }
        return (
          <select
            className="region-cell"
            value={regions.hostRegion(row.id)}
            aria-label={`${row.name} 的区域`}
            title="修改这台主机所属的区域，左侧菜单会跟着重新归类"
            onClick={(event) => event.stopPropagation()}
            onChange={(event) => regions.setHostRegion(row.id, event.target.value)}
          >
            {regions.names.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        );
      },
    },
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
    { key: 'deviceId', header: 'Device ID', sortValue: (row) => row.deviceId },
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
            disabled={busy}
            title={row.open ? '删除会先断开链接' : '删除主机实例'}
            onClick={() => setDeleteTarget({ ids: [row.id], label: `主机「${row.name}」` })}
          >
            删除
          </button>
        </div>
      ),
    },
  ];

  return (
    <div className="panel-stack">
      {/* 标题行与顶部统计卡都已删除：一页只剩"一行操作 + 一张表" */}
      <Panel id="panel-hosts-v2" title="主机清单" headless>
        {error !== '' && <p className="inline-note danger">{error}</p>}
        <DataTable
          tableKey="hosts-v2"
          columns={columns}
          rows={visible}
          rowKey={(row) => row.id}
          searchPlaceholder="搜索实例 id / 名称 / 目标"
          emptyText="还没有主机实例。点「＋ 新建主机」填目标地址，可选模板套用方案。"
          toolbar={
            <>
              <label className="field region-filter">
                <span>区域</span>
                <select
                  value={regionFilter}
                  aria-label="按区域筛选主机"
                  onChange={(event) => setRegionFilter(event.target.value)}
                >
                  <option value="">全部区域</option>
                  {(regions?.names ?? [DEFAULT_REGION]).map((name) => (
                    <option key={name} value={name}>{name}</option>
                  ))}
                </select>
              </label>
            </>
          }
          /* 动作靠右：筛选是"改看什么"，按钮是"改做什么"，两件事分开站 */
          toolbarEnd={
            <>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => setCreateOpen(true)}
                title="新建一台主机：填写目标地址与端口，可选主机模板套用方案"
              >
                ＋ 新建主机
              </button>
              <button
                type="button"
                disabled={selectedRows.length === 0 || busy}
                title="对选中的主机逐台建立链接（已经连上的跳过）"
                onClick={() => void runBatch('链接', selectedRows.filter((row) => !row.open),
                  (row) => hosts.connect(row.id))}
              >
                批量链接
              </button>
              <button
                type="button"
                disabled={selectedRows.length === 0 || busy}
                title="对选中的主机逐台断开链接（没连上的跳过）"
                onClick={() => void runBatch('断开', selectedRows.filter((row) => row.open),
                  (row) => hosts.disconnect(row.id))}
              >
                批量断开
              </button>
              <button
                type="button"
                className="danger"
                disabled={selectedRows.length === 0 || busy}
                title="删除选中的主机实例：连着的先断开再删"
                onClick={() => setDeleteTarget({
                  ids: selectedRows.map((row) => row.id),
                  label: `选中的 ${selectedRows.length} 台主机`,
                })}
              >
                批量删除
              </button>
              {/* 选中数量与批量进度：只在有勾选或正在批量时出现，不占常驻版面 */}
              {busy && progress !== '' ? (
                <span className="table-selection">{progress}</span>
              ) : selectedRows.length > 0 ? (
                <span className="table-selection">已选 {selectedRows.length} 台</span>
              ) : null}
            </>
          }
          exportColumns={[
            { header: '实例', value: (row) => row.id },
            { header: '名称', value: (row) => row.name },
            { header: '区域', value: (row) => regions?.hostRegion(row.id) ?? DEFAULT_REGION },
            { header: '状态', value: (row) => hostPhaseLabel(row.phase) },
            { header: '目标', value: (row) => row.target },
            { header: 'Device ID', value: (row) => row.deviceId },
          ]}
        />
      </Panel>

      <CreateHostModal
        open={createOpen}
        templates={hosts.templates}
        quickDevices={quickDevices}
        onPickDevice={pickQuickDevice}
        regionNames={regions?.names ?? [DEFAULT_REGION]}
        busy={busy}
        onClose={() => setCreateOpen(false)}
        onCreate={async ({ region, newRegion, ...request }) => {
          setBusy(true);
          setError('');
          try {
            const hostId = await hosts.create(request);
            const target = newRegion.trim() === '' ? region : newRegion.trim();
            if (target !== '') {
              regions?.addRegion(target);
              regions?.setHostRegion(hostId, target);
            }
            setCreateOpen(false);
            goTo?.(`host-open:${hostId}`);
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      />

      {/* 删除确认：删主机不可撤销，单个和批量都先问一次 */}
      <ConfirmModal
        open={deleteTarget !== null}
        title="删除主机"
        danger
        confirmLabel="删除"
        message={deleteTarget === null ? '' : `确定删除${deleteTarget.label}？连着的会先断开再删；主机上的方案与设置一并消失，不可恢复。`}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (deleteTarget === null) {
            return;
          }
          const ids = deleteTarget.ids;
          setDeleteTarget(null);
          void runBatch(
            '删除',
            hosts.hosts.filter((host) => ids.includes(host.id)),
            async (row) => {
              /* 后端不接受删除还连着的主机：先断开再删 */
              if (row.open) {
                await hosts.disconnect(row.id);
              }
              await hosts.remove(row.id);
            },
          );
        }}
      />
    </div>
  );
}

/** 新建主机弹窗：实例目标信息 + 快速链接设备 + 可选的主机模板方案包。 */
function CreateHostModal({
  open,
  templates,
  quickDevices,
  onPickDevice,
  regionNames,
  busy,
  onClose,
  onCreate,
}: {
  open: boolean;
  templates: HostsV2State['templates'];
  /** 可一键接管的设备：已经启动（端口在监听）的那些。 */
  quickDevices: readonly { id: string; label: string }[];
  /** 读出一台设备的地址/端口/Device ID；读不到返回 null。 */
  onPickDevice: (sessionId: string) => Promise<{
    name: string; host: string; port: number | null; deviceId: number;
  } | null>;
  regionNames: readonly string[];
  busy: boolean;
  onClose: () => void;
  onCreate: (request: {
    name?: string; host: string; port: number; deviceId?: number; template?: HostTemplate;
    region: string; newRegion: string;
  }) => void;
}): JSX.Element {
  const [name, setName] = useState('host');
  const [host, setHost] = useState('127.0.0.1');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');
  const [templateId, setTemplateId] = useState('');
  const [region, setRegion] = useState(DEFAULT_REGION);
  const [newRegion, setNewRegion] = useState('');
  const [quickId, setQuickId] = useState('');
  const [picking, setPicking] = useState(false);
  const selectedTemplate = templates.find((item) => item.id === templateId);

  /** 选一台设备：把名称/目标/端口/Device ID 一次填好，之后仍可手改。 */
  const applyQuickDevice = async (sessionId: string) => {
    setQuickId(sessionId);
    if (sessionId === '') {
      return;
    }
    setPicking(true);
    try {
      const picked = await onPickDevice(sessionId);
      if (picked === null) {
        return;
      }
      setName(picked.name);
      setHost(picked.host);
      setPort(picked.port === null ? '' : String(picked.port));
      setDeviceId(String(picked.deviceId));
    } finally {
      setPicking(false);
    }
  };

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
              template: selectedTemplate,
              region,
              newRegion,
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
          value={templateId}
          onChange={(template) => setTemplateId(template?.id ?? '')}
        />
      )}
      <div className="session-form">
        {/* 一线操作：已经启动的设备直接拿来当目标，省掉抄地址/端口/Device ID */}
        <div className="form-row">
          <label className="field">
            快速链接设备
            <select
              value={quickId}
              disabled={picking}
              aria-label="快速链接设备"
              onChange={(event) => void applyQuickDevice(event.target.value)}
            >
              <option value="">
                {quickDevices.length === 0 ? '— 没有已启动的设备 —' : '— 手动填写 —'}
              </option>
              {quickDevices.map((device) => (
                <option key={device.id} value={device.id}>{device.label}</option>
              ))}
            </select>
          </label>
        </div>
        <p className="hint">
          选一台已经启动的设备：目标地址、端口、Device ID 按它的配置填好，名称用
          <span className="mono"> host-设备名</span>；填完仍可手改。
        </p>
        <div className="session-fields">
          <label className="session-field">
            <span className="session-field-text">
              <span className="session-field-name">名称</span>
              <span className="session-field-help">实例列表里显示的名字;多台主机各起一个好认的。</span>
            </span>
            <input value={name} onChange={(event) => setName(event.target.value)} />
          </label>
          <label className="session-field">
            <span className="session-field-text">
              <span className="session-field-name">目标地址</span>
              <span className="session-field-help">设备或第三方模拟器的 IP / 主机名;清空则禁用创建。</span>
            </span>
            <input value={host} onChange={(event) => setHost(event.target.value)} />
          </label>
          <label className="session-field">
            <span className="session-field-text">
              <span className="session-field-name">端口</span>
              <span className="session-field-help">目标设备的 HSMS 端口(1–65535)。</span>
            </span>
            <input type="number" min={1} max={65535} value={port}
              onChange={(event) => setPort(event.target.value)} />
          </label>
          <label className="session-field">
            <span className="session-field-text">
              <span className="session-field-name">Device ID</span>
              <span className="session-field-help">目标设备的 HSMS Device ID(0–32767),两侧必须一致。</span>
            </span>
            <input type="number" min={0} max={32767} value={deviceId}
              onChange={(event) => setDeviceId(event.target.value)} />
          </label>
        </div>
        <div className="form-row">
          <label className="field">
            所属区域
            <select value={region} onChange={(event) => setRegion(event.target.value)} aria-label="所属区域">
              {regionNames.map((item) => <option key={item} value={item}>{item}</option>)}
            </select>
          </label>
          <label className="field">
            新建区域（可选）
            <input
              value={newRegion}
              onChange={(event) => setNewRegion(event.target.value)}
              placeholder="填了就建新区域并归入"
              aria-label="新建区域"
            />
          </label>
        </div>
        <p className="hint">
          目标可以是任意设备或第三方模拟器地址;与设备列表无关。可连续新建多台,多台可指向同一地址
          （先连上的保持「已连接」,后来者停在「连接失败 · 目标已有主机」,互不影响）。
        </p>
      </div>
    </Modal>
  );
}
