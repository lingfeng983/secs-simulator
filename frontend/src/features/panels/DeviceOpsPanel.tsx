import { useEffect, useMemo, useRef, useState } from 'react';
import type { ConfigTemplateEntry, SessionBulkResult, SessionSummary } from '../../api';
import { Panel, PanelFootnote } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { Modal } from '../../shared/ui/Modal';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { NumberField, TextField } from '../../shared/ui/CommitFields';
import { formatCount } from '../../shared/lib/format';
import { controlStateLabel, controlStateTone } from '../../shared/lib/controlState';
import type { PanelBaseProps } from './types';
import { saveHint } from '../../shared/lib/saveFile';

/** 合并行:会话摘要打底,速率/端口/角色来自 /overview,同一台设备只占一行。 */
interface DeviceRow extends SessionSummary {
  port: number;
  hostSim: boolean;
  msgsPerMin: number;
  s6f11PerMin: number;
  lastEventAt: number;
  activeAlarms: number;
}

/**
 * 设备与会话(工作台主体)。
 *
 * <p>原来"设备总览"和"会话"是两张表:上面看速率,下面做操作,
 * 同一台设备出现两次,眼睛要在两张表之间对 id。合并后一行一台设备——
 * 状态/速率/计数是这张行的"读数",勾选与右侧动作是"操作",
 * 点行进设备页。批量工具条常驻在表格下方,勾选后才可用。</p>
 */
export function DeviceOpsPanel({ backend, notifier, goTo }: PanelBaseProps): JSX.Element {
  const [newConfig, setNewConfig] = useState('demo');
  const [stateFilter, setStateFilter] = useState<'all' | 'online' | 'offline'>('all');
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkConfig, setBulkConfig] = useState('');
  const [bulkCount, setBulkCount] = useState(5);
  const [bulkPrefix, setBulkPrefix] = useState('dev-');
  const [bulkPortBase, setBulkPortBase] = useState(5100);
  const [bulkDeviceBase, setBulkDeviceBase] = useState(100);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchProgress, setBatchProgress] = useState<string>('');
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<SessionBulkResult | null>(null);
  const [configTemplates, setConfigTemplates] = useState<ConfigTemplateEntry[]>([]);
  const [bulkTplId, setBulkTplId] = useState('etch');
  const [bulkTplName, setBulkTplName] = useState('');
  /** 待确认的断开目标:null = 不弹;label 是确认文案里的人话描述。 */
  const [closeTarget, setCloseTarget] = useState<{ ids: readonly string[]; label: string } | null>(null);
  const [bulkTplBusy, setBulkTplBusy] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);
  const [syncPortBase, setSyncPortBase] = useState(5100);
  const [syncDeviceBase, setSyncDeviceBase] = useState(100);
  const [newTemplateId, setNewTemplateId] = useState('bare');
  const [templateList, setTemplateList] = useState<ConfigTemplateEntry[]>([]);
  /* 连续新建时轮询还没刷新，用本地已分配端口兜底，避免两台设备抢同一端口 */
  const allocatedPorts = useRef<Set<number>>(new Set());
  const { sessions, overview, configList, bulkCreateSessions, batchReload, batchClose } = backend;

  /* 模板目录：工作台新建设备只选模板，不再手填配置名 */
  useEffect(() => {
    if (backend.client === null || backend.client === undefined) {
      return;
    }
    void backend.client.templates().then((list) => {
      setTemplateList(list);
      if (list.length > 0) {
        setNewTemplateId((previous) => (list.some((item) => item.id === previous) ? previous : list[0].id));
      }
    });
  }, [backend.client]);

  const online = sessions.filter((item) => item.controlState === 'ONLINE').length;

  /* /overview 按 id 并进会话行:计数以会话为准,速率/端口/角色以总览为准 */
  const overviewById = useMemo(() => new Map((overview ?? []).map((row) => [row.id, row])), [overview]);
  const rows = useMemo<DeviceRow[]>(() => sessions.map((session) => {
    const info = overviewById.get(session.id);
    return {
      ...session,
      controlState: session.controlState ?? info?.controlState,
      device: info?.device ?? session.device,
      units: info?.units ?? session.units,
      port: info?.port ?? 0,
      hostSim: info?.hostSim ?? false,
      msgsPerMin: info?.msgsPerMin ?? 0,
      s6f11PerMin: info?.s6f11PerMin ?? 0,
      lastEventAt: info?.lastEventAt ?? 0,
      activeAlarms: info?.counters.activeAlarms ?? 0,
    };
  }), [sessions, overviewById]);

  /* "在线"包含 COMMUNICATING：主机已连上、设备只是还没收到 S1F17。
     只看 OFFLINE 才叫离线，否则主机遇模拟器刚连上时列表会自相矛盾。 */
  const connected = (state: string | undefined) => state === 'ONLINE' || state === 'COMMUNICATING';
  const visible = useMemo(() => rows.filter((row) => {
    if (stateFilter === 'online' && !connected(row.controlState)) {
      return false;
    }
    if (stateFilter === 'offline' && connected(row.controlState)) {
      return false;
    }
    return true;
  }), [rows, stateFilter]);

  /* 批量新建预检:会话 id 冲突可以提前算——这是最常见的重复创建问题 */
  const bulkIds = new Set(sessions.map((item) => item.id));
  const plannedIds = Array.from(
    { length: Math.max(0, Math.min(100, Math.round(bulkCount))) },
    (_, i) => `${bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim()}${i + 1}`,
  );
  const idConflicts = plannedIds.filter((id) => bulkIds.has(id));

  const columns: Column<DeviceRow>[] = [
    {
      key: 'select',
      header: '选择',
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
      key: 'device',
      header: '设备',
      sortValue: (row) => row.device,
      render: (row) => (
        <button
          type="button"
          className="link"
          onClick={(event) => {
            event.stopPropagation();
            goTo?.(`device:${row.id}`);
          }}
          title="进入设备页"
        >
          {row.device}
        </button>
      ),
    },
    {
      key: 'id',
      header: '会话',
      sortValue: (row) => row.id,
      render: (row) => <span className="mono">{row.id}</span>,
    },
    {
      key: 'port',
      header: '端口',
      align: 'right',
      sortValue: (row) => row.port,
      /* 端口列只放数字：冒号是"host:port"写法的一部分，单独一列时是噪音 */
      render: (row) => <span className="mono">{row.port}</span>,
    },
    {
      key: 'control',
      header: '控制',
      sortValue: (row) => row.controlState ?? '',
      render: (row) => (
        <span
          className={`badge tone-${controlStateTone(row.controlState)}`}
          title={`${row.state} · ${row.units} 个单元`}
        >
          {controlStateLabel(row.controlState)}
        </span>
      ),
    },
    {
      key: 'role',
      header: '角色',
      sortValue: (row) => (row.hostSim ? '主机模拟' : '设备'),
      render: (row) => (row.hostSim ? <span className="badge tone-info">主机模拟</span> : <span className="hint-inline">设备</span>),
    },
    {
      key: 'msgs',
      header: '报文/分',
      align: 'right',
      sortValue: (row) => row.msgsPerMin,
      render: (row) => (row.msgsPerMin > 0 ? <strong>{row.msgsPerMin}</strong> : <span className="hint-inline">0</span>),
    },
    {
      key: 'events',
      header: '事件/分',
      align: 'right',
      sortValue: (row) => row.s6f11PerMin,
      render: (row) => (row.s6f11PerMin > 0 ? <strong>{row.s6f11PerMin}</strong> : <span className="hint-inline">0</span>),
    },
    {
      key: 'emitted',
      header: '已上报',
      align: 'right',
      sortValue: (row) => row.counters.emitted ?? 0,
      render: (row) => formatCount(row.counters.emitted),
    },
    {
      key: 'alarms',
      header: '活动报警',
      align: 'right',
      sortValue: (row) => row.activeAlarms,
      render: (row) => (row.activeAlarms > 0
        ? <span className="badge tone-danger">{row.activeAlarms}</span>
        : <span className="hint-inline">0</span>),
    },
    {
      key: 'actions',
      header: '',
      render: (row) => {
        /* 3.0：关闭 = 断开链路（设备保留），所以断开态要能一键连回去。
           「连接」与「断开」互斥，同一行不会同时出现两个相反的动作。 */
        const disconnected = row.state === 'CLOSED';
        return (
          <div className="row-actions" onClick={(event) => event.stopPropagation()}>
            <button
              type="button"
              onClick={() => void backend.reloadSession(row.id)}
              title="按配置文件重新装载(配置有问题时旧会话不受影响)"
            >
              重载
            </button>
            {disconnected ? (
              <button
                type="button"
                onClick={() => void backend.connectSession(row.id)}
                title="按当前配置重新建链(模型/设定值/事件启停原样恢复)"
              >
                连接
              </button>
            ) : (
              <button
                type="button"
                className="danger"
                onClick={() => setCloseTarget({ ids: [row.id], label: row.id })}
                title="断开链路:端口立即释放,设备保留在列表里"
              >
                断开
              </button>
            )}
          </div>
        );
      },
    },
  ];

  /** 以第一个选中的设备为源，把其余选中设备的配置同步成一致；
      只保留各自的会话 id、端口、设备号与设备名，其余段原样复制。 */
  const syncSelected = async () => {
    const ids = [...selected];
    if (ids.length < 2) {
      notifier.error('至少选择两台设备');
      return;
    }
    const source = sessions.find((item) => item.id === ids[0]);
    const sourceConfig = source?.config ?? '';
    if (source === undefined || sourceConfig === '') {
      notifier.error('第一台设备没有配置文件');
      return;
    }
    const snapshot = await backend.readConfig(sourceConfig);
    if (snapshot === null) {
      notifier.error('读取源配置失败');
      return;
    }
    setBatchBusy(true);
    let done = 0;
    try {
      for (let index = 1; index < ids.length; index += 1) {
        const target = sessions.find((item) => item.id === ids[index]);
        const targetConfig = target?.config ?? '';
        if (target === undefined || targetConfig === '' || targetConfig === sourceConfig) {
          continue;
        }
        setBatchProgress(`同步 ${index}/${ids.length - 1}`);
        try {
          const root = JSON.parse(snapshot.text) as Record<string, unknown>;
          const session = root.session !== null && typeof root.session === 'object'
            ? root.session as Record<string, unknown>
            : {};
          session.id = target.id;
          session.port = syncPortBase + index;
          session.deviceId = syncDeviceBase + index;
          root.session = session;
          const equipment = root.equipment !== null && typeof root.equipment === 'object'
            ? root.equipment as Record<string, unknown>
            : {};
          equipment.name = target.device !== '' ? target.device : target.id;
          root.equipment = equipment;
          const text = JSON.stringify(root, null, 2);
          const validation = await backend.validateConfig(targetConfig, text);
          if (validation === null || !validation.valid) {
            continue;
          }
          const saved = await backend.saveConfig(targetConfig, text, null);
          if (saved !== null && saved.status === 200) {
            done += 1;
          }
        } catch {
          /* 单台失败继续下一台 */
        }
      }
      notifier.success(`已同步 ${done} 台设备`, `源配置：${source.id}；只改了会话 id/端口/设备号/名称`);
      setSyncOpen(false);
    } finally {
      setBatchBusy(false);
      setBatchProgress('');
    }
  };

  /** 只选模板新建设备：自动生成唯一设备名/端口/设备号，配置文件名为<设备名>.json。 */
  const createFromTemplate = async () => {
    if (backend.client === null || backend.client === undefined) {
      return;
    }
    if (newTemplateId === '') {
      notifier.error('请选择设备模板');
      return;
    }
    const base = newTemplateId.trim().replace(/[^A-Za-z0-9._-]/g, '-');
    /* 以服务端最新目录为准，避免连续点击时 state 还没刷新导致同名冲突 */
    const freshConfigs = await backend.client.configs();
    const existing = new Set([
      ...freshConfigs.map((entry) => entry.name),
      ...configList.map((entry) => entry.name),
      ...sessions.map((session) => session.config),
    ]);
    let sequence = 1;
    let name = '';
    do {
      name = `${base}-${String(sequence).padStart(2, '0')}.json`;
      sequence += 1;
    } while (existing.has(name));
    const sessionId = name.replace(/\.json$/i, '');
    const usedPorts = new Set(overview.map((row) => row.port));
    for (const port of allocatedPorts.current) {
      usedPorts.add(port);
    }
    /* 轮询可能还没反映刚建的会话：把配置文件里的端口也读进来，避免连续新建时撞端口 */
    for (const entry of freshConfigs) {
      try {
        const snapshot = await backend.readConfig(entry.name);
        if (snapshot !== null) {
          const config = JSON.parse(snapshot.text) as { session?: { port?: number } };
          if (typeof config.session?.port === 'number') {
            usedPorts.add(config.session.port);
          }
        }
      } catch {
        /* 坏配置跳过 */
      }
    }
    let port = 5000;
    while (usedPorts.has(port)) {
      port += 1;
    }
    allocatedPorts.current.add(port);
    const deviceId = 10 + sessions.length + 1;
    setBatchBusy(true);
    setBatchProgress('新建设备…');
    try {
      const status = await backend.client.createConfig(name, newTemplateId);
      if (status !== 201 && status !== 200) {
        notifier.error('按模板创建设备失败', `HTTP ${status}`);
        return;
      }
      const snapshot = await backend.readConfig(name);
      if (snapshot !== null) {
        try {
          const root = JSON.parse(snapshot.text) as Record<string, unknown>;
          const session = root.session !== null && typeof root.session === 'object'
            ? root.session as Record<string, unknown>
            : {};
          session.id = sessionId;
          session.port = port;
          session.deviceId = deviceId;
          root.session = session;
          await backend.saveConfig(name, JSON.stringify(root, null, 2), null);
        } catch {
          /* 模板原文不是合法 JSON 时由后续启动报错 */
        }
      }
      await backend.createSession(name);
      await backend.refreshConfigs();
      notifier.success(`已按模板 ${newTemplateId} 新建设备 ${sessionId}`, `端口 ${port}，Device ID ${deviceId}`);
    } finally {
      setBatchBusy(false);
      setBatchProgress('');
    }
  };

  return (
    <Panel
      id="panel-sessions"
      title="设备与会话"
      description="一行一台设备:状态、速率与计数是读数,勾选后可批量操作,点行进设备页。"
      actions={
        <>
          <label className="field field-wide">
            设备模板
            <select
              value={newTemplateId}
              onChange={(event) => setNewTemplateId(event.target.value)}
              aria-label="新建设备使用的模板"
            >
              {templateList.length === 0 && <option value="">（还没有模板）</option>}
              {templateList.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}（{item.id}）{item.source === 'custom' ? ' · 自定义' : ''}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="primary"
            onClick={() => void createFromTemplate()}
            disabled={batchBusy || newTemplateId === '' || templateList.length === 0}
            title="只选模板即可：自动生成唯一设备名、端口与 Device ID"
          >
            新建设备
          </button>
          <button type="button" onClick={() => goTo?.('templates')} title="没有合适的模板？去模板管理新建">
            管理模板
          </button>
        </>
      }
    >
      {templateList.length === 0 && (
        <p className="problems">
          还没有设备模板。先在「管理模板」里新建一个，再回来这里选模板新建设备。
        </p>
      )}
      <DataTable
        tableKey="sessions"
        columns={columns}
        rows={visible}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索会话 id / 设备 / 状态"
        exportName="sessions"
        onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
        onImport={async (imported) => {
          /* `backend.sessions` 是本次导入开始前的快照，且 createSession 内部把
             「配置不存在/端口占用」都当提示发出去后正常返回——所以既不能用闭包
             里的列表判重，也不能用"没抛异常"当成功。两条都改掉：本地维护一份
             已占用集合，成功与否只看 201。 */
          const taken = new Set(backend.sessions.map((session) => session.config));
          let done = 0;
          let skipped = 0;
          let failed = 0;
          for (const row of imported) {
            const config = (row['配置'] ?? '').trim();
            if (config === '' || taken.has(config)) {
              skipped += 1;
              continue;
            }
            const status = await backend.createSession(config);
            if (status === 201) {
              taken.add(config);
              done += 1;
            } else {
              failed += 1;
            }
          }
          const tail = [
            failed > 0 ? `${failed} 行失败` : '',
            skipped > 0 ? `${skipped} 行跳过` : '',
          ].filter((part) => part !== '').join('，');
          if (done === 0 && failed > 0) {
            notifier.error('导入没有创建任何会话', tail);
          } else if (done === 0) {
            /* 全被跳过（没有「配置」列、或同名配置已存在）也不能报"已导入 0 个"，
               那读起来像成功 */
            notifier.warn('没有可导入的行', tail === '' ? '每行都需要一列「配置」' : tail);
          } else {
            notifier.success(`已按配置导入 ${done} 个会话`, tail);
          }
        }}
        emptyText="暂无会话(用 --demo-session / --config 启动,或在右上角新建)"
        defaultSort={{ key: 'id', direction: 'asc' }}
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
        exportColumns={[
          { header: '会话', value: (row) => row.id },
          { header: '设备', value: (row) => row.device },
          { header: '配置', value: (row) => row.config },
          { header: '端口', value: (row) => row.port },
          { header: '控制', value: (row) => controlStateLabel(row.controlState) },
          { header: '状态', value: (row) => row.state },
          { header: '角色', value: (row) => (row.hostSim ? '主机模拟' : '设备') },
          { header: '单元', value: (row) => row.units },
          { header: '报文/分钟', value: (row) => row.msgsPerMin },
          { header: '事件/分钟', value: (row) => row.s6f11PerMin },
          { header: '已上报', value: (row) => row.counters.emitted ?? 0 },
          { header: '限流丢弃', value: (row) => row.counters.rejected ?? 0 },
          { header: '禁用跳过', value: (row) => row.counters.skipped ?? 0 },
          { header: 'S6F1', value: (row) => row.counters.traceFrames ?? 0 },
          { header: 'S9Fx', value: (row) => row.counters.s9Replies ?? 0 },
          { header: '活动报警', value: (row) => row.activeAlarms },
        ]}
        onRowClick={(row) => goTo?.(`device:${row.id}`)}
      />

      {/* 批量工具条:勾选后才可用 */}
      <div className="batch-bar" data-active={selected.size > 0 || batchBusy ? 'true' : undefined}>
        <span className="table-count">
          {batchBusy && batchProgress !== '' ? batchProgress : `已选 ${selected.size} 台`}
        </span>
        <button
          type="button"
          /* 全选的前提是"有行可选"，不是"已经选了东西"：之前一进来按钮就是灰的 */
          disabled={rows.length === 0 || batchBusy}
          onClick={() => setSelected(new Set(sessions.map((item) => item.id)))}
        >
          全选
        </button>
        <button type="button" disabled={selected.size === 0} onClick={() => setSelected(new Set())}>
          清空选择
        </button>
        <span className="spacer" />
        <button
          type="button"
          className="primary"
          disabled={selected.size < 2 || batchBusy}
          title="以选中的第一台为原始配置，把其余设备的配置同步为一致，只改会话 id/端口/设备号/名称"
          onClick={() => setSyncOpen(true)}
        >
          批量同步配置
        </button>
        <button
          type="button"
          disabled={selected.size === 0 || batchBusy}
          title="对选中的设备逐台重载(配置有问题时旧会话不受影响;主机会断连)"
          onClick={() => {
            setBatchBusy(true);
            void batchReload(selected, (done, total) => setBatchProgress(`重载 ${done}/${total}`))
              .finally(() => { setBatchBusy(false); setBatchProgress(''); });
          }}
        >
          批量重载
        </button>
        <button
          type="button"
          className="danger"
          disabled={selected.size === 0 || batchBusy}
          title="断开选中的设备链路(端口立即释放;设备保留,可再连)"
          onClick={() => setCloseTarget({ ids: [...selected], label: `选中的 ${selected.size} 台设备` })}
        >
          批量断开
        </button>
      </div>

      <p className="hint">
        配置只认 <code>--work-dir</code> 下的文件名。批量操作对勾选的设备逐台执行；
        「批量同步配置」以第一台为原始配置，其余设备只改会话 id、端口、设备号与名称，其余段完全一致。
      </p>

      <Modal
        open={syncOpen}
        title="批量同步配置"
        onClose={() => setSyncOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setSyncOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={batchBusy} onClick={() => void syncSelected()}>
              {batchBusy ? '同步中…' : '开始同步'}
            </button>
          </>
        }
      >
        <p className="hint">
          以选中的第一台为原始配置，把设备/单元/SVID/报告/事件/模板复制到其余选中设备；
          只改各自的会话 id、端口、设备号与设备名，其余配置保持一致。
        </p>
        <p className="hint">
          原始设备：<code>{[...selected][0] ?? '—'}</code>；目标 {Math.max(0, selected.size - 1)} 台。
        </p>
        <div className="form-row">
          <label className="field">
            起始端口
            <input
              type="number"
              min={1}
              value={syncPortBase}
              onChange={(event) => setSyncPortBase(Number(event.target.value) || 5100)}
            />
          </label>
          <label className="field">
            起始 Device ID
            <input
              type="number"
              min={0}
              value={syncDeviceBase}
              onChange={(event) => setSyncDeviceBase(Number(event.target.value) || 0)}
            />
          </label>
        </div>
      </Modal>

      <ConfirmModal
        open={closeTarget !== null}
        title="断开连接"
        danger
        confirmLabel="断开"
        message={closeTarget === null ? '' : `确定断开${closeTarget.label}？端口会立即释放、主机连接断开，设备仍保留在列表里，可随时再连。`}
        onClose={() => setCloseTarget(null)}
        onConfirm={() => {
          if (closeTarget === null) {
            return;
          }
          const ids = closeTarget.ids;
          setCloseTarget(null);
          if (ids.length === 1) {
            void backend.closeSession(ids[0]);
            return;
          }
          setBatchBusy(true);
          void batchClose(new Set(ids), (done, total) => setBatchProgress(`断开 ${done}/${total}`))
            .finally(() => {
              setSelected(new Set());
              setBatchBusy(false);
              setBatchProgress('');
            });
        }}
      />
      <PanelFootnote
        items={[
          { label: '会话数', value: sessions.length },
          { label: '在线', value: online, tone: online > 0 ? 'ok' : undefined },
          {
            label: '活动报警',
            value: rows.reduce((sum, row) => sum + row.activeAlarms, 0),
            tone: rows.some((row) => row.activeAlarms > 0) ? 'danger' : undefined,
          },
        ]}
      />

      <Modal
        open={bulkOpen}
        title="批量新建设备"
        onClose={() => setBulkOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setBulkOpen(false)}>
              关闭
            </button>
            <button
              type="button"
              className="primary"
              disabled={bulkBusy || bulkConfig === '' || bulkCount < 1 || idConflicts.length > 0}
              onClick={() => {
                setBulkBusy(true);
                setBulkResult(null);
                void backend
                  .bulkCreateSessions({
                    config: bulkConfig,
                    count: Math.min(100, Math.max(1, Math.round(bulkCount))),
                    sessionIdPrefix: bulkPrefix.trim() === '' ? 'dev-' : bulkPrefix.trim(),
                    portBase: Math.max(0, Math.round(bulkPortBase)),
                    deviceIdBase: Math.max(0, Math.round(bulkDeviceBase)),
                  })
                  .then((result) => {
                    setBulkResult(result);
                    setBulkBusy(false);
                  });
              }}
            >
              {bulkBusy ? '创建中…' : '开始创建'}
            </button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            设备配置模板
            <select value={bulkConfig} onChange={(event) => setBulkConfig(event.target.value)}>
              <option value="">（选择配置）</option>
              {configList.map((entry) => (
                <option key={entry.name} value={entry.name}>
                  {entry.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            数量（1–100）
            <NumberField value={bulkCount} integer onCommit={setBulkCount} />
          </label>
          <label className="field">
            会话 id 前缀
            <TextField value={bulkPrefix} onCommit={setBulkPrefix} />
          </label>
          <label className="field">
            起始端口
            <NumberField value={bulkPortBase} integer onCommit={setBulkPortBase} />
          </label>
          <label className="field">
            device-id 起始
            <NumberField value={bulkDeviceBase} integer onCommit={setBulkDeviceBase} />
          </label>
        </div>
        <div className="form-row">
          <label className="field">
            从内置设备模板建一份
            <select value={bulkTplId} onChange={(event) => setBulkTplId(event.target.value)}>
              {configTemplates.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            新配置名
            <TextField value={bulkTplName} onCommit={setBulkTplName} placeholder="如 etch-line.json" />
          </label>
          <button
            type="button"
            disabled={bulkTplBusy || bulkTplName.trim() === '' || !bulkTplName.trim().toLowerCase().endsWith('.json')}
            title="新建一份配置文件并选为下面的设备配置模板"
            onClick={() => {
              setBulkTplBusy(true);
              void backend
                .createConfig(bulkTplName.trim(), bulkTplId)
                .then((status) => {
                  if (status === 201) {
                    setBulkConfig(bulkTplName.trim());
                    setBulkTplName('');
                  }
                })
                .finally(() => setBulkTplBusy(false));
            }}
          >
            {bulkTplBusy ? '创建中…' : '建并选用'}
          </button>
        </div>
        <p className="hint">
          第 i 台:会话 id = 前缀 + i,端口 = 起始端口 + i − 1,device-id = 起始 + i − 1。
          设备本体(单元、变量、事件、模板)全部来自所选配置模板;这些连接标识只在运行期覆盖,
          不写回模板文件。端口从 0 或留空表示全部沿用模板端口(多台会因端口冲突而失败)。
        </p>
        {idConflicts.length > 0 && bulkResult === null && (
          <p className="problems">
            会话 id 已存在:{idConflicts.slice(0, 5).join('、')}
            {idConflicts.length > 5 ? ` 等 ${idConflicts.length} 个` : ''}(先关闭或换前缀)
          </p>
        )}
        {bulkResult !== null && (
          <div className="template-card">
            <strong>已创建 {bulkResult.created.length} 台</strong>
            {bulkResult.created.length > 0 && (
              <p className="hint mono">{bulkResult.created.join('、')}</p>
            )}
            {bulkResult.failed.length > 0 && (
              <ul className="problems">
                {bulkResult.failed.map((item) => (
                  <li key={item.index}>
                    第 {item.index} 台:{item.error === 'conflict' ? '会话 id 已存在' : item.error === 'failed' ? '端口被占或配置编译失败' : item.error}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </Modal>
    </Panel>
  );
}
