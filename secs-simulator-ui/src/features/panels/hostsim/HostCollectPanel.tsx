import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { DataTable, type Column } from '../../../shared/ui/DataTable';
import { Modal } from '../../../shared/ui/Modal';
import { VariableChart } from '../../charts/VariableChart';
import type {
  ApiClient,
  CatalogCeid,
  CatalogSvid,
  CollectorConfigInput,
  CollectorPlanState,
  CollectorState,
  HostV2,
  PreviewSeries,
} from '../../../api';
import { parseIdList, planId, useCollectPlans, type CollectPlan } from '../../../state/hostPlans';
import { ceidFromS6f11 } from '../../../shared/lib/smlValues';
import { hostOfTarget } from '../../../shared/lib/format';
import { requireClient, type PanelArgs } from './hostPanelShared';
import { collectorEngaged } from './hostRuntimeStatus';

/** 采集器状态（由 armed/running 派生）。 */
interface CollectorStatus {
  text: string;
  badge: string;
  hint: string;
}

function collectorStatus(state: CollectorPlanState | null): CollectorStatus {
  if (state === null || state.configured !== true) {
    return { text: '未配置', badge: 'badge-muted', hint: '还没有下发采集配置' };
  }
  if (state.running === true) {
    return { text: '采集中', badge: 'badge-ok', hint: '正在按周期轮询 S1F3' };
  }
  if (state.armed === true) {
    return { text: '待命', badge: 'badge-warn', hint: '配置已下发，等待设备上报启动 CEID' };
  }
  return { text: '未启动', badge: 'badge-muted', hint: '配置已下发但未启动采集' };
}

/** 本机设备目录（本机在线设备才读得到）。 */
interface LocalCatalog {
  svids: CatalogSvid[];
  ceids: CatalogCeid[];
}

const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1', '[::1]']);

async function readConfigDeviceId(client: ApiClient, configName: string): Promise<number | null> {
  const snapshot = await client.configText(configName);
  const parsed = JSON.parse(snapshot.text) as { session?: { deviceId?: unknown } };
  const deviceId = parsed.session?.deviceId;
  return typeof deviceId === 'number' ? deviceId : null;
}

/**
 * 读本机在线设备的目录（C-LOCAL-DEVICE-CATALOG@1）。
 *
 * <p>只有「目标地址在回环 + 存在 deviceId 与本主机一致的在线设备」才算本机目录；
 * 任一步读不到都返回 null，由弹窗回退手动输入。</p>
 */
async function readLocalCatalog(client: ApiClient, host: HostV2): Promise<LocalCatalog | null> {
  if (!LOOPBACK_HOSTS.has(hostOfTarget(host.target).toLowerCase())) {
    return null;
  }
  try {
    for (const summary of await client.sessions()) {
      const configName = summary.config ?? '';
      if (configName === '') {
        continue;
      }
      if ((await readConfigDeviceId(client, configName)) !== host.deviceId) {
        continue;
      }
      const catalog = await client.catalog(summary.id);
      return { svids: catalog.svids, ceids: catalog.ceids };
    }
  } catch {
    /* 配置原文或目录读不到：视为本机目录不可用 */
    return null;
  }
  return null;
}

function planConfig(plan: CollectPlan): CollectorConfigInput {
  return {
    /* 计划 id 就是采集器的身份：一台主机可以同时挂多份，靠它各采各的 */
    planId: plan.id,
    startCeid: Number(plan.startCeid) || 0,
    stopCeid: Number(plan.stopCeid) || 0,
    intervalMs: Number(plan.intervalMs) || 1000,
    svids: parseIdList(plan.svids).map((id) => ({ id })),
  };
}

interface BatchFailure {
  name: string;
  message: string;
}

interface BatchResult {
  succeeded: number;
  skipped: number;
  failed: BatchFailure[];
}

/** 已保存采集配置列表（DataTable）：配置区编辑/删除，运行区启停/状态/批量选择。 */
function CollectPlanTable({
  plans,
  mode,
  planState,
  onLoad,
  onDelete,
  onToggle,
  busy,
  selection,
  toolbarEnd,
}: {
  plans: readonly CollectPlan[];
  mode: 'config' | 'runtime';
  /** 取某条配置对应的那份采集器状态（一台主机可以同时挂多份）。 */
  planState: (plan: CollectPlan) => CollectorPlanState | null;
  onLoad: (plan: CollectPlan) => void;
  onDelete: (id: string) => void;
  onToggle: (plan: CollectPlan) => void;
  busy: boolean;
  selection?: {
    selected: ReadonlySet<string>;
    onToggle: (id: string) => void;
    onToggleAll: () => void;
  };
  /** 表格工具条右端的动作（新增配置 / 批量启停）。 */
  toolbarEnd?: ReactNode;
}): JSX.Element {
  const editing = mode === 'config';
  const allSelected = selection !== undefined && plans.length > 0
    && plans.every((plan) => selection.selected.has(plan.id));
  const columns: Column<CollectPlan>[] = [
    ...(selection === undefined
      ? []
      : [{
        key: 'select',
        header: '选择',
        width: '46px',
        className: 'select-column',
        headerContent: (
          <input
            type="checkbox"
            aria-label="全选采集配置"
            checked={allSelected}
            disabled={busy || plans.length === 0}
            ref={(element) => {
              if (element !== null) {
                element.indeterminate = selection.selected.size > 0 && !allSelected;
              }
            }}
            onChange={() => selection.onToggleAll()}
          />
        ),
        render: (row: CollectPlan) => (
          <input
            type="checkbox"
            aria-label={`选择配置 ${row.name}`}
            checked={selection.selected.has(row.id)}
            disabled={busy}
            onChange={() => selection.onToggle(row.id)}
          />
        ),
      } as Column<CollectPlan>]),
    { key: 'name', header: '配置', sortValue: (row) => row.name, render: (row) => row.name },
    ...(editing
      ? []
      : [{
        key: 'status',
        header: '状态',
        render: (row: CollectPlan) => {
          const status = collectorStatus(planState(row));
          return <span className={`badge ${status.badge}`} title={status.hint}>{status.text}</span>;
        },
      } as Column<CollectPlan>]),
    { key: 'startCeid', header: '启动 CEID', sortValue: (row) => Number(row.startCeid) || 0, render: (row) => <span className="mono">{row.startCeid}</span> },
    { key: 'stopCeid', header: '停止 CEID', sortValue: (row) => Number(row.stopCeid) || 0, render: (row) => <span className="mono">{row.stopCeid}</span> },
    { key: 'intervalMs', header: '周期', sortValue: (row) => Number(row.intervalMs) || 0, render: (row) => <span className="mono">{row.intervalMs}ms</span> },
    { key: 'svids', header: 'SVID', render: (row) => <span className="mono hint-inline">{row.svids}</span> },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          {editing ? (
            <>
              <button type="button" onClick={() => onLoad(row)} title="打开编辑弹窗改参数，保存后写回配置库">
                编辑
              </button>
              <button type="button" className="danger" onClick={() => onDelete(row.id)} title="删除配置（配置库同步删除）">
                删除
              </button>
            </>
          ) : (
            (() => {
              const engaged = collectorEngaged(planState(row));
              return (
                <button
                  type="button"
                  className={engaged ? 'danger' : 'primary'}
                  disabled={busy}
                  onClick={() => onToggle(row)}
                  title={engaged
                    ? '停止这一份采集（同一台主机上其它计划照常在采）'
                    : '下发该配置并启动采集'}
                >
                  {engaged ? '停止' : '启动'}
                </button>
              );
            })()
          )}
        </div>
      ),
    },
  ];
  return (
    <DataTable
      tableKey={`host-collect-plans-${mode}`}
      columns={columns}
      rows={plans}
      rowKey={(row) => row.id}
      searchPlaceholder="搜索配置 / CEID / SVID"
      exportName={`collect-plans-${mode}`}
      toolbarEnd={toolbarEnd}
      emptyText={editing
        ? '还没有保存的采集配置。点右上「新增配置」填好参数后保存。'
        : '还没有采集配置；到配置区「采集设置」新增后这里即可启停。'}
      exportColumns={[
        { header: '配置', value: (row) => row.name },
        ...(editing ? [] : [{ header: '状态', value: (row: CollectPlan) => collectorStatus(planState(row)).text }]),
        { header: '启动CEID', value: (row) => row.startCeid },
        { header: '停止CEID', value: (row) => row.stopCeid },
        { header: '周期ms', value: (row) => row.intervalMs },
        { header: 'SVID', value: (row) => row.svids },
      ]}
    />
  );
}

/** CEID 单选：选项文本为 `${ceid} · ${slot}`。 */
function CeidSelect({ value, onChange, ceids, ariaLabel }: {
  value: string;
  onChange: (next: string) => void;
  ceids: readonly CatalogCeid[];
  ariaLabel: string;
}): JSX.Element {
  const known = ceids.some((item) => String(item.ceid) === value);
  return (
    <select value={value} onChange={(event) => onChange(event.target.value)} aria-label={ariaLabel}>
      {value !== '' && !known && <option value={value}>{value}（当前值）</option>}
      {ceids.map((item) => (
        <option key={item.ceid} value={String(item.ceid)}>{`${item.ceid} · ${item.slot}`}</option>
      ))}
    </select>
  );
}

/** 采集配置字段（新增/编辑弹窗共用）：本机目录可用时用下拉，否则手动输入。 */
function CollectPlanFields({ form, update, catalog }: {
  form: CollectPlanForm;
  update: (patch: Partial<CollectPlanForm>) => void;
  catalog: LocalCatalog | null;
}): JSX.Element {
  const ceids = catalog?.ceids ?? [];
  const svids = catalog?.svids ?? [];
  const pickedSvids = parseIdList(form.svids).map(String);
  return (
    <>
      <div className="form-row">
        <label className="field">启动 CEID
          {ceids.length > 0
            ? (
              <CeidSelect
                value={form.startCeid}
                onChange={(next) => update({ startCeid: next })}
                ceids={ceids}
                ariaLabel="启动 CEID"
              />
            )
            : <input value={form.startCeid} onChange={(event) => update({ startCeid: event.target.value })} />}
        </label>
        <label className="field">停止 CEID
          {ceids.length > 0
            ? (
              <CeidSelect
                value={form.stopCeid}
                onChange={(next) => update({ stopCeid: next })}
                ceids={ceids}
                ariaLabel="停止 CEID"
              />
            )
            : <input value={form.stopCeid} onChange={(event) => update({ stopCeid: event.target.value })} />}
        </label>
        <label className="field">周期 ms
          <input value={form.intervalMs} onChange={(event) => update({ intervalMs: event.target.value })} />
        </label>
      </div>
      <label className="field field-wide">
        {svids.length > 0 ? '采集变量（可多选）' : '采集变量(逗号/空格分隔)'}
        {svids.length > 0
          ? (
            <select
              multiple
              size={Math.min(8, Math.max(4, svids.length))}
              value={pickedSvids}
              aria-label="采集变量（可多选）"
              onChange={(event) => {
                const picked = Array.from(event.target.selectedOptions, (option) => option.value);
                update({ svids: parseIdList(picked.join(',')).join(', ') });
              }}
            >
              {svids.map((item) => (
                <option key={item.svid} value={String(item.svid)}>{`${item.svid} · ${item.slot}`}</option>
              ))}
            </select>
          )
          : <input value={form.svids} onChange={(event) => update({ svids: event.target.value })} />}
      </label>
    </>
  );
}

interface CollectPlanForm {
  name: string;
  startCeid: string;
  stopCeid: string;
  intervalMs: string;
  svids: string;
}

const EMPTY_FORM: CollectPlanForm = {
  name: '', startCeid: '2001', stopCeid: '2002', intervalMs: '1000', svids: '1001, 1003',
};

/**
 * 数据采集（S1F3 轮询）。
 *
 * <p>配置区只管配置库：列表 + 新增/编辑弹窗 + 删除。运行区同一份列表提供
 * 状态列、单按钮启停与批量启停；数据曲线按配置折叠，点「查看曲线」才展示。
 * 新增/编辑弹窗在本机在线设备可用时用 SVID 多选与 CEID 单选，否则回退手动输入。</p>
 */
export function HostCollectPanel({ hostId, hosts, readOnly, client, goTo, messages = [] }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [plans, setPlans] = useCollectPlans(hostId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [state, setState] = useState<CollectorState | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<CollectPlanForm>(EMPTY_FORM);
  const [viewingPlanId, setViewingPlanId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [batch, setBatch] = useState<{ action: 'start' | 'stop'; done: number; total: number } | null>(null);
  const [batchResult, setBatchResult] = useState<BatchResult | null>(null);
  const [catalog, setCatalog] = useState<LocalCatalog | null>(null);
  const [catalogFailed, setCatalogFailed] = useState(false);
  const timerRef = useRef<number | null>(null);
  /** 目录只读一次；关闭/重开弹窗后旧请求的结果直接丢弃。 */
  const catalogSeq = useRef(0);
  /** 只处理本次打开页面之后到达的报文，避免历史补拉把采集器反复启动。 */
  const sinceRef = useRef(Date.now());
  const handledRef = useRef<Set<string>>(new Set());

  const loadState = async () => {
    if (client === null) {
      return;
    }
    try {
      const body = await client.hostInstanceCollector(hostId);
      setState(body);
    } catch {
      /* 实例不存在/后端不可达:保持现状 */
    }
  };

  useEffect(() => {
    /* 配置区只管方案定义：不下发、不采集，也就不需要运行态轮询。 */
    if (readOnly) {
      return;
    }
    void loadState();
    const timer = window.setInterval(() => void loadState(), 2_000);
    timerRef.current = timer;
    return () => {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hostId, client, readOnly]);

  /** 取某条配置对应的那份采集器状态（没下发过的配置没有状态）。 */
  const stateOf = (plan: CollectPlan): CollectorPlanState | null =>
    state?.collectors?.find((item) => item.planId === plan.id) ?? null;

  /** 启停某一份采集计划：只动这一份，同一台主机上的其它计划照常采。 */
  const command = async (planId: string, start: boolean) => {
    setBusy(true);
    setError('');
    try {
      await requireClient(client).hostInstanceCollectorCommand(hostId, start, planId);
      await loadState();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const startPlan = async (plan: CollectPlan) => {
    setBusy(true);
    setError('');
    try {
      const api = requireClient(client);
      await api.setHostInstanceCollector(hostId, planConfig(plan));
      await api.hostInstanceCollectorCommand(hostId, true, plan.id);
      await loadState();
      setNotice(`已下发并启动「${plan.name}」`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /** 批量启动：串行逐份下发 + 启动；已经启动的那几份自动跳过，不重复下发。 */
  const batchStart = async () => {
    if (client === null) {
      setError('后端未连接');
      return;
    }
    const targets = plans.filter((plan) => selected.has(plan.id));
    setError('');
    setBatchResult(null);
    setBatch({ action: 'start', done: 0, total: targets.length });
    const failed: BatchFailure[] = [];
    let skipped = 0;
    let succeeded = 0;
    let done = 0;
    for (const plan of targets) {
      if (collectorEngaged(stateOf(plan))) {
        skipped += 1;
        done += 1;
        setBatch({ action: 'start', done, total: targets.length });
        continue;
      }
      try {
        await client.setHostInstanceCollector(hostId, planConfig(plan));
        await client.hostInstanceCollectorCommand(hostId, true, plan.id);
        succeeded += 1;
      } catch (e) {
        failed.push({ name: plan.name, message: (e as Error).message });
      }
      done += 1;
      setBatch({ action: 'start', done, total: targets.length });
    }
    await loadState();
    setBatch(null);
    setBatchResult({ succeeded, skipped, failed });
    if (succeeded > 0) {
      setNotice(`已启动 ${succeeded} 份采集计划`);
    }
  };

  /** 批量停止：逐份停掉选中且正在采/待命的计划，其余跳过。 */
  const batchStop = async () => {
    if (client === null) {
      setError('后端未连接');
      return;
    }
    const targets = plans.filter((plan) => selected.has(plan.id));
    setError('');
    setBatchResult(null);
    setBatch({ action: 'stop', done: 0, total: targets.length });
    const failed: BatchFailure[] = [];
    let skipped = 0;
    let succeeded = 0;
    let done = 0;
    for (const plan of targets) {
      if (!collectorEngaged(stateOf(plan))) {
        skipped += 1;
        done += 1;
        setBatch({ action: 'stop', done, total: targets.length });
        continue;
      }
      try {
        /* 待命也算"已启动"：这时候停掉要能真的解除待命，否则下次启动 CEID 又会开跑 */
        await client.hostInstanceCollectorCommand(hostId, false, plan.id);
        succeeded += 1;
      } catch (e) {
        failed.push({ name: plan.name, message: (e as Error).message });
      }
      done += 1;
      setBatch({ action: 'stop', done, total: targets.length });
    }
    await loadState();
    setBatch(null);
    setBatchResult({ succeeded, skipped, failed });
    if (succeeded > 0) {
      setNotice(`已停止 ${succeeded} 份采集计划`);
    }
  };

  const toggleRow = (id: string) => {
    setSelected((previous) => {
      const next = new Set(previous);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const toggleAll = () => {
    setSelected((previous) => (plans.length > 0 && previous.size === plans.length
      ? new Set()
      : new Set(plans.map((plan) => plan.id))));
  };

  /** 弹窗打开时读一次本机目录；读不到就回退手动输入。 */
  const loadCatalog = async () => {
    const seq = catalogSeq.current + 1;
    catalogSeq.current = seq;
    setCatalog(null);
    setCatalogFailed(false);
    if (client === null || host === null) {
      setCatalogFailed(true);
      return;
    }
    const loaded = await readLocalCatalog(client, host);
    if (seq !== catalogSeq.current) {
      return;
    }
    if (loaded === null) {
      setCatalogFailed(true);
    } else {
      setCatalog(loaded);
    }
  };

  const closeModal = () => {
    /* 关闭即丢弃：让在途的目录读取结果失效 */
    catalogSeq.current += 1;
    setModalOpen(false);
  };

  const openCreate = () => {
    setForm(EMPTY_FORM);
    setEditingId(null);
    setModalOpen(true);
    void loadCatalog();
  };

  const openEdit = (plan: CollectPlan) => {
    setForm({
      name: plan.name, startCeid: plan.startCeid, stopCeid: plan.stopCeid,
      intervalMs: plan.intervalMs, svids: plan.svids,
    });
    setEditingId(plan.id);
    setModalOpen(true);
    void loadCatalog();
  };

  const saveModal = () => {
    const label = form.name.trim() || `CEID ${form.startCeid} · ${form.intervalMs}ms`;
    const body = {
      name: label,
      startCeid: form.startCeid, stopCeid: form.stopCeid,
      intervalMs: form.intervalMs, svids: form.svids,
    };
    setPlans((previous) => editingId !== null
      ? previous.map((plan) => (plan.id === editingId ? { ...plan, ...body } : plan))
      : [...previous, { id: planId(), createdAt: Date.now(), ...body }]);
    setModalOpen(false);
  };

  /* 需求 9 的前端兜底：设备推来启动/停止 CEID 的 S6F11 时，
     即使后端链路侧的判定漏掉，也由界面显式下发启停命令。
     只认本主机视角 + 本次打开之后的新报文，每条只处理一次；按计划各自匹配 CEID。 */
  useEffect(() => {
    if (client === null || !host?.open || readOnly) {
      return;
    }
    for (const message of messages) {
      if (message.at < sinceRef.current || message.stream !== 6 || message.function !== 11) {
        continue;
      }
      if (message.perspective !== 'host' || message.hostId !== hostId || message.direction !== 'out') {
        continue;
      }
      const key = `${message.seq ?? `${message.at}:${message.body.length}`}`;
      if (handledRef.current.has(key)) {
        continue;
      }
      handledRef.current.add(key);
      const ceid = ceidFromS6f11(message.body);
      if (ceid === null) {
        continue;
      }
      for (const plan of plans) {
        const planStart = Number(plan.startCeid) || 0;
        const planStop = Number(plan.stopCeid) || 0;
        const current = state?.collectors?.find((item) => item.planId === plan.id) ?? null;
        if (planStart > 0 && ceid === planStart && current?.running !== true) {
          setNotice(`收到启动 CEID ${ceid}，正在启动「${plan.name}」…`);
          void command(plan.id, true);
        } else if (planStop > 0 && ceid === planStop && current?.running === true) {
          setNotice(`收到停止 CEID ${ceid}，正在停止「${plan.name}」…`);
          void command(plan.id, false);
        }
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, client, hostId, host?.open, readOnly, plans, state]);

  /* 采集样本是逐点值(min=max=avg);时间轴用样本自带的 epoch 毫秒。 */
  const chartsFor = (plan: CollectPlan) => (stateOf(plan)?.series ?? []).map((item) => {
    const times = item.t ?? [];
    const from = times.length > 0 ? times[0] : 0;
    const to = times.length > 1 ? times[times.length - 1] : from + 1000;
    const chartSeries: PreviewSeries = {
      svid: item.svid,
      owner: item.name ?? `SVID ${item.svid}`,
      unit: item.unit ?? '',
      min: item.v,
      max: item.v,
      avg: item.v,
    };
    return {
      svid: item.svid,
      series: chartSeries,
      fromMs: from,
      bucketMs: Math.max(1, Math.round((to - from) / Math.max(1, item.v.length - 1))),
    };
  });


  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }

  const runningPlans = (state?.collectors ?? []).filter((item) => item.running === true);
  const armedPlans = (state?.collectors ?? []).filter((item) => item.running !== true && item.armed === true);
  const totalSamples = runningPlans.reduce((sum, item) => sum + (item.samples ?? 0), 0);
  const totalErrors = runningPlans.reduce((sum, item) => sum + (item.decodeErrors ?? 0), 0);
  const batchBusy = batch !== null;

  return (
    <Panel
      id="panel-host-collect"
      title={readOnly ? '采集设置（S1F3 配置库）' : '数据采集（S1F3 轮询）'}
      headless
    >
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {notice !== '' && <p className="inline-note">{notice}</p>}

      {/* 配置区只管配置库：S1F3 的下发与启停在运行区「数据采集」页 */}
      {readOnly && (
        <p className="hint">
          这里保存的是<b>采集配置库</b>（落 config.db，重启后端也不丢），保存本身<b>不会下发</b>。
          下发与启停在运行区「数据采集」页。
          {' · '}
          <button
            type="button"
            className="link-button"
            onClick={() => goTo?.(`host-page:${hostId}:runtime:collect`)}
          >
            去下发 →
          </button>
        </p>
      )}

      <CollectPlanTable
        mode={readOnly ? 'config' : 'runtime'}
        plans={plans}
        planState={stateOf}
        onLoad={openEdit}
        onDelete={(id) => setPlans((previous) => previous.filter((plan) => plan.id !== id))}
        onToggle={(plan) => {
          /* 只切这一份：A 在采也不妨碍 B 启动，已启动的那份不会给「启动」 */
          if (collectorEngaged(stateOf(plan))) {
            void command(plan.id, false);
          } else {
            void startPlan(plan);
          }
        }}
        busy={busy || batchBusy}
        selection={readOnly
          ? undefined
          : { selected, onToggle: toggleRow, onToggleAll: toggleAll }}
        /* 页面级动作下移到表格工具条右侧 */
        toolbarEnd={readOnly
          ? <button type="button" className="primary" onClick={openCreate}>新增配置</button>
          : (
            <>
              <span className="table-selection" role="status">
                {batch !== null
                  ? `${batch.action === 'start' ? '启动' : '停止'} ${batch.done}/${batch.total}`
                  : `已选 ${selected.size} 条`}
              </span>
              <button
                type="button"
                disabled={client === null || batchBusy || selected.size === 0}
                onClick={() => void batchStart()}
                title="按选中顺序逐份下发配置并启动；已经启动的计划自动跳过"
              >
                批量启动
              </button>
              <button
                type="button"
                disabled={client === null || batchBusy || selected.size === 0}
                onClick={() => void batchStop()}
                title="停止选中且在采的计划；未启动的自动跳过"
              >
                批量停止
              </button>
            </>
          )}
      />

      {!readOnly && batchResult !== null && (
        <div className="inline-note" role="status">
          成功 {batchResult.succeeded} 条 / 跳过 {batchResult.skipped} 条 / 失败 {batchResult.failed.length} 条
          {batchResult.failed.length > 0 && (
            <ul className="problems">
              {batchResult.failed.map((item, index) => (
                <li key={`${item.name}-${index}`}>{item.name}：{item.message}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      {!readOnly && (
        <div className="toolbar">
          <span className="hint-inline">
            {runningPlans.length > 0
              ? `采集中 ${runningPlans.length} 份 · ${totalSamples} 点 · 解码错误 ${totalErrors}`
              : armedPlans.length > 0
                ? `待命 ${armedPlans.length} 份 · 已下发配置，等待设备上报启动 CEID`
                : (state?.collectors ?? []).length > 0
                  ? '已配置 · 在方案列表点「启动」开跑（多份计划可同时采）'
                  : '未配置 · 在方案列表点「启动」下发配置'}
          </span>
        </div>
      )}

      {!readOnly && (
        <section className="panel-section block">
          <div className="section-head">
            <h2 style={{ margin: 0 }}>采集曲线</h2>
            <span className="hint-inline">按配置折叠，点「查看曲线」才展示</span>
          </div>
          {plans.length === 0 && <p className="hint">还没有采集配置；到配置区新增后这里按配置看曲线。</p>}
          {plans.map((plan) => {
            const open = viewingPlanId === plan.id;
            const planSvids = parseIdList(plan.svids);
            const planCharts = chartsFor(plan);
            return (
              <section key={plan.id} className="panel-section">
                <div className="section-head">
                  <h3>{plan.name} <span className="mono hint-inline">CEID {plan.startCeid}/{plan.stopCeid} · {plan.intervalMs}ms</span></h3>
                  <button type="button" onClick={() => setViewingPlanId(open ? null : plan.id)}>
                    {open ? '收起曲线' : '查看曲线'}
                  </button>
                </div>
                {open && (planCharts.length === 0
                  ? <p className="hint">还没有该配置的采集数据；「启动」并等几秒后再看。</p>
                  : (
                    <div className="collector-charts">
                      {planCharts.map((chart) => (
                        <div className="collector-chart collector-chart-large" key={chart.svid}>
                          <div className="collector-chart-head">
                            <span className="mono">{chart.svid}</span>
                            <span>{chart.series.owner}</span>
                            {(chart.series.unit ?? '') !== '' && <span className="hint-inline">{chart.series.unit}</span>}
                            <span className="spacer" />
                            <span className="hint-inline">
                              {chart.series.avg.length} 点 · 最近{' '}
                              {chart.series.avg.length > 0 ? chart.series.avg[chart.series.avg.length - 1].toFixed(2) : '—'}
                            </span>
                          </div>
                          <VariableChart
                            series={chart.series}
                            fromMs={chart.fromMs}
                            bucketMs={chart.bucketMs}
                            height={200}
                            eventLabel="事件"
                          />
                        </div>
                      ))}
                    </div>
                  ))}
              </section>
            );
          })}
        </section>
      )}

      <Modal
        open={modalOpen}
        title={editingId !== null ? '编辑采集配置' : '新增采集配置'}
        onClose={closeModal}
        footer={
          <>
            <button type="button" onClick={closeModal}>取消</button>
            <button type="button" className="primary" onClick={saveModal}>保存</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field field-wide">
            配置名称（留空自动生成）
            <input
              value={form.name}
              onChange={(event) => setForm((previous) => ({ ...previous, name: event.target.value }))}
              placeholder="如：温度+压力 1s"
            />
          </label>
        </div>
        {catalogFailed && (
          <p className="inline-note">无法读取本机设备目录，改为手动输入</p>
        )}
        <CollectPlanFields
          form={form}
          catalog={catalog}
          update={(patch) => setForm((previous) => ({ ...previous, ...patch }))}
        />
      </Modal>
    </Panel>
  );
}