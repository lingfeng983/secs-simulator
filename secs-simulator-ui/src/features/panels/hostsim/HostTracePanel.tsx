import { useMemo, useRef, useState, type ReactNode } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { DataTable, type Column } from '../../../shared/ui/DataTable';
import { SmlResult } from '../../../shared/ui/SmlResult';
import { Modal } from '../../../shared/ui/Modal';
import { VariableChart } from '../../charts/VariableChart';
import type { ApiClient, Catalog, CatalogSvid, HostV2, SentMessageResult } from '../../../api';
import {
  DEFAULT_TRACE_DRAFT,
  parseIdList,
  planId,
  useTracePlans,
  type TraceDraft,
  type TracePlan,
} from '../../../state/hostPlans';
import { traceFrameFromS6f1, type TraceFrame } from '../../../shared/lib/smlValues';
import { requireClient, type PanelArgs } from './hostPanelShared';
import { traceStarted, type ManualSwitch } from './hostRuntimeStatus';

/** 状态列判定窗口：最近 60 秒内收到同 TRID 的 S6F1 视为「采集中」。 */
const RUNNING_WINDOW_MS = 60_000;

/** 视为「本机」的主机目标地址。 */
const LOOPBACK_TARGETS = new Set(['127.0.0.1', 'localhost', '::1', '[::1]']);

/** 本机设备目录读取结果：catalog 为 null 表示回退手动输入，error 非空时在弹窗内提示。 */
interface LocalCatalog {
  catalog: Catalog | null;
  error: string;
}

/** 批量启停里一条失败明细。 */
interface BatchFailure {
  name: string;
  message: string;
}

interface BatchSummary {
  okCount: number;
  skipped: number;
  failures: BatchFailure[];
}

/** 生成一条 S2F23 的 SML（TRID / DSPER / TOTSMP / REPGSZ / SVID 列表）。 */
function buildS2f23(draft: { trid: string; dsper: string; totsmp: string; repgsz: string; svids: string }): string {
  const NL = String.fromCharCode(10);
  const list = parseIdList(draft.svids).map((svid) => `      <U4 ${svid}>`).join(NL);
  const total = Math.max(1, Math.round((Number(draft.dsper) || 1000) / 1000));
  const hh = String(Math.floor(total / 3600)).padStart(2, '0');
  const mm = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
  const ss = String(total % 60).padStart(2, '0');
  return 'S2F23 W' + NL
    + '<L' + NL
    + `  <U4 ${Number(draft.trid) || 1}>` + NL
    + `  <A "${hh}${mm}${ss}">` + NL
    + `  <U4 ${Number(draft.totsmp) || 0}>` + NL
    + `  <U4 ${Number(draft.repgsz) || 1}>` + NL
    + '  <L' + NL
    + list + NL
    + '  >' + NL
    + '>.';
}

/** 启动 / 停止一条方案对应的 S2F23：停止 = 同一 TRID 的 TOTSMP=0。 */
function planSml(plan: TracePlan, totsmp: number): string {
  return buildS2f23({
    trid: String(plan.trid), dsper: String(plan.dsper), totsmp: String(totsmp),
    repgsz: String(plan.repgsz), svids: plan.svids,
  });
}

/**
 * 按 C-LOCAL-DEVICE-CATALOG@1 读本机在线设备目录。
 *
 * <p>主机连回环地址、且本机存在同 Device ID 的设备会话时才读目录；
 * 任一步失败都按"非本机可用目录"处理，由弹窗回退手动输入。</p>
 */
async function readLocalDeviceCatalog(client: ApiClient, host: HostV2): Promise<LocalCatalog> {
  const silentFallback: LocalCatalog = { catalog: null, error: '' };
  if (!LOOPBACK_TARGETS.has(host.target.trim())) {
    return silentFallback;
  }
  try {
    for (const session of await client.sessions()) {
      const configName = session.config?.trim() ?? '';
      if (configName === '') {
        continue;
      }
      const snapshot = await client.configText(configName);
      const parsed = JSON.parse(snapshot.text) as { session?: { deviceId?: unknown } };
      if (Number(parsed.session?.deviceId) !== host.deviceId) {
        continue;
      }
      return { catalog: await client.catalog(session.id), error: '' };
    }
  } catch {
    return { catalog: null, error: '无法读取本机设备目录，改为手动输入' };
  }
  return silentFallback;
}

/** 已保存方案列表（DataTable）：配置区编辑/删除，运行区状态/多选/启停。 */
function TracePlanTable({
  plans,
  mode,
  engagedTrids,
  selected,
  onToggleSelect,
  onToggleAll,
  onLoad,
  onDelete,
  onToggleRun,
  busy,
  toolbarEnd,
}: {
  plans: readonly TracePlan[];
  mode: 'config' | 'runtime';
  /** 已启动的 TRID（报文里看到的 + 刚点过启动的）：有它就只给「停止」。 */
  engagedTrids: ReadonlySet<number>;
  selected: ReadonlySet<string>;
  onToggleSelect: (id: string) => void;
  onToggleAll: () => void;
  onLoad: (plan: TracePlan) => void;
  onDelete: (id: string) => void;
  onToggleRun: (plan: TracePlan) => void;
  busy: boolean;
  /** 表格工具条右端的动作（新增方案、批量启停这类页面级动作落在这里）。 */
  toolbarEnd?: ReactNode;
}): JSX.Element {
  const editing = mode === 'config';
  const allSelected = plans.length > 0 && plans.every((plan) => selected.has(plan.id));
  const columns: Column<TracePlan>[] = [
    ...(editing ? [] : [{
      key: 'select',
      header: '选择',
      width: '52px',
      headerContent: (
        <input
          type="checkbox"
          checked={allSelected}
          onChange={onToggleAll}
          aria-label="全选方案"
          title="全选 / 取消全选"
        />
      ),
      render: (row: TracePlan) => (
        <input
          type="checkbox"
          checked={selected.has(row.id)}
          onChange={() => onToggleSelect(row.id)}
          aria-label={`选择方案 ${row.name}`}
        />
      ),
    }]),
    { key: 'name', header: '方案', sortValue: (row) => row.name, render: (row) => row.name },
    ...(editing ? [] : [{
      key: 'status',
      header: '状态',
      sortValue: (row: TracePlan) => (engagedTrids.has(row.trid) ? '采集中' : '未启动'),
      render: (row: TracePlan) => (
        <span className={engagedTrids.has(row.trid) ? 'mono' : 'hint-inline'}>
          {engagedTrids.has(row.trid) ? '采集中' : '未启动'}
        </span>
      ),
    }]),
    { key: 'trid', header: 'TRID', sortValue: (row) => row.trid, render: (row) => <span className="mono">{row.trid}</span> },
    { key: 'dsper', header: '采样周期', sortValue: (row) => row.dsper, render: (row) => <span className="mono">{row.dsper}ms</span> },
    { key: 'totsmp', header: '样本数', sortValue: (row) => row.totsmp, render: (row) => <span className="mono">{row.totsmp}</span> },
    { key: 'repgsz', header: 'REPGSZ', sortValue: (row) => row.repgsz, render: (row) => <span className="mono">{row.repgsz}</span> },
    { key: 'svids', header: 'SVID', render: (row) => <span className="mono hint-inline">{row.svids}</span> },
    {
      key: 'actions',
      header: '操作',
      render: (row) => {
        if (editing) {
          return (
            <div className="row-actions">
              <button type="button" onClick={() => onLoad(row)} title="打开编辑弹窗改参数，保存后写回方案库">
                编辑
              </button>
              <button type="button" className="danger" onClick={() => onDelete(row.id)} title="删除方案（配置库同步删除）">
                删除
              </button>
            </div>
          );
        }
        const running = engagedTrids.has(row.trid);
        return (
          <div className="row-actions">
            <button
              type="button"
              className={running ? '' : 'primary'}
              disabled={busy}
              onClick={() => onToggleRun(row)}
              title={running
                ? '对该 TRID 下发 TOTSMP=0，提前终止采样'
                : '向设备下发 S2F23 开始采样'}
            >
              {running ? '停止' : '启动'}
            </button>
          </div>
        );
      },
    },
  ];
  return (
    <DataTable
      tableKey={`host-trace-plans-${mode}`}
      columns={columns}
      rows={plans}
      rowKey={(row) => row.id}
      searchPlaceholder="搜索方案 / TRID / SVID"
      exportName={`trace-plans-${mode}`}
      toolbarEnd={toolbarEnd}
      emptyText={editing
        ? '还没有保存的 Trace 方案。点右上「新增方案」填好参数后保存。'
        : '还没有 Trace 方案；到配置区「Trace 设置」新增后这里即可启停。'}
      exportColumns={[
        { header: '方案', value: (row) => row.name },
        { header: 'TRID', value: (row) => String(row.trid) },
        { header: '采样周期ms', value: (row) => String(row.dsper) },
        { header: '样本数', value: (row) => String(row.totsmp) },
        { header: 'SVID', value: (row) => row.svids },
      ]}
    />
  );
}

/** 本机设备目录里的 SVID 下拉多选；选择结果仍写回逗号分隔的 SVID 文本。 */
function SvidMultiSelect({ choices, value, onChange }: {
  choices: readonly CatalogSvid[];
  value: string;
  onChange: (next: string) => void;
}): JSX.Element {
  const picked = new Set(parseIdList(value));
  return (
    <select
      multiple
      size={Math.min(10, Math.max(4, choices.length))}
      style={{ minWidth: '18rem' }}
      value={choices.filter((item) => picked.has(item.svid)).map((item) => String(item.svid))}
      onChange={(event) => {
        const next = Array.from(event.target.selectedOptions).map((option) => Number(option.value));
        onChange(next.join(', '));
      }}
    >
      {choices.map((item) => (
        <option key={item.svid} value={item.svid}>{item.svid} · {item.slot}</option>
      ))}
    </select>
  );
}

/** Trace 方案参数字段（新增/编辑弹窗共用）。 */
function TracePlanFields({ form, update, svidChoices, catalogError }: {
  form: TraceDraft;
  update: (patch: Partial<TraceDraft>) => void;
  /** 本机设备目录可用时的 SVID 选项；null = 回退手动输入。 */
  svidChoices: readonly CatalogSvid[] | null;
  catalogError: string;
}): JSX.Element {
  return (
    <>
      <div className="form-row">
        <label className="field">TRID
          <input type="number" min={1} value={form.trid} onChange={(event) => update({ trid: event.target.value })} />
        </label>
        <label className="field">采样周期 ms
          <input type="number" min={1000} step={1000} value={form.dsper} onChange={(event) => update({ dsper: event.target.value })} />
        </label>
        <label className="field">总样本数
          <input type="number" min={0} value={form.totsmp} onChange={(event) => update({ totsmp: event.target.value })} />
        </label>
        <label className="field">每条样本数
          <input type="number" min={1} value={form.repgsz} onChange={(event) => update({ repgsz: event.target.value })} />
        </label>
      </div>
      {svidChoices === null
        ? (
          <label className="field field-wide">
            采集变量(逗号/空格分隔)
            <input value={form.svids} onChange={(event) => update({ svids: event.target.value })} />
          </label>
        )
        : (
          <label className="field-block">
            采集变量（可多选，来自本机设备目录）
            <SvidMultiSelect
              choices={svidChoices}
              value={form.svids}
              onChange={(next) => update({ svids: next })}
            />
          </label>
        )}
      {svidChoices === null && catalogError !== '' && (
        <p className="inline-note danger">{catalogError}</p>
      )}
    </>
  );
}

interface TracePlanForm extends TraceDraft {
  name: string;
}

/**
 * Trace 设置 / 采集。
 *
 * <p>配置区只管方案库：列表 + 新增/编辑弹窗 + 删除。运行区同一份方案列表
 * 提供状态列与启停（启动 = 下发 S2F23 开始采样，停止 = 该 TRID 下发 TOTSMP=0），
 * 并支持勾选多行批量启停；数据曲线按方案折叠，点「查看曲线」才计算绘制。</p>
 */
export function HostTracePanel({ hostId, hosts, readOnly, client, messages = [] }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [plans, setPlans] = useTracePlans(hostId);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  const [error, setError] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<TracePlanForm>({
    trid: '1', dsper: '1000', totsmp: '100', repgsz: '1', svids: '1001, 1003', name: '',
  });
  const [viewingTrid, setViewingTrid] = useState<number | null>(null);
  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set<string>());
  const [progress, setProgress] = useState('');
  const [batch, setBatch] = useState<BatchSummary | null>(null);
  const [lookup, setLookup] = useState<LocalCatalog | null>(null);
  /**
   * 用户亲手点过的启停（TRID → 启动时刻 / 停止时刻）。
   *
   * <p>启动后到首帧到达有几十到几百毫秒，只看报文的话这段时间按钮还是「启动」，
   * 手快就会再点一次 —— 同一 TRID 重复下发 S2F23 等于把采样重置一遍。
   * 反过来，点「停止」之后如果还靠 60 秒窗口判断，按钮又会显示「停止」一分钟。
   * 记下手点的意图，按钮立刻切成另一态：操作列永远只有"启动 ↔ 停止"两态。</p>
   */
  const [manual, setManual] = useState<ReadonlyMap<number, ManualSwitch>>(
    () => new Map<number, ManualSwitch>(),
  );
  /** 弹窗每次打开只读一次目录；关闭后旧请求的结果直接丢弃。 */
  const catalogSeq = useRef(0);

  const updateForm = (patch: Partial<TracePlanForm>) => setForm((previous) => ({ ...previous, ...patch }));

  const loadCatalog = () => {
    catalogSeq.current += 1;
    const seq = catalogSeq.current;
    setLookup(null);
    if (client === null || host === null) {
      setLookup({ catalog: null, error: '' });
      return;
    }
    void readLocalDeviceCatalog(client, host).then((next) => {
      if (catalogSeq.current === seq) {
        setLookup(next);
      }
    });
  };

  const closeModal = () => {
    catalogSeq.current += 1;
    setLookup(null);
    setModalOpen(false);
  };

  const openCreate = () => {
    setForm({ trid: '1', dsper: '1000', totsmp: '100', repgsz: '1', svids: '1001, 1003', name: '' });
    setEditingId(null);
    setModalOpen(true);
    loadCatalog();
  };

  const openEdit = (plan: TracePlan) => {
    setForm({
      trid: String(plan.trid), dsper: String(plan.dsper), totsmp: String(plan.totsmp),
      repgsz: String(plan.repgsz), svids: plan.svids, name: plan.name,
    });
    setEditingId(plan.id);
    setModalOpen(true);
    loadCatalog();
  };

  const saveModal = () => {
    const label = form.name.trim() || `TRID ${form.trid} · ${form.dsper}ms`;
    const body = {
      name: label,
      trid: Number(form.trid) || 1,
      dsper: Number(form.dsper) || 1000,
      totsmp: Number(form.totsmp) || 0,
      repgsz: Number(form.repgsz) || 1,
      svids: form.svids,
    };
    setPlans((previous) => editingId !== null
      ? previous.map((plan) => (plan.id === editingId ? { ...plan, ...body } : plan))
      : [...previous, { id: planId(), createdAt: Date.now(), ...body }]);
    closeModal();
  };

  /**
   * 发一条 SML；成功时顺带记下"这条 TRID 现在是启动还是停止"。
   *
   * <p>点击反馈必须立刻可见（按钮变「停止」），但只有真正发出去才记，
   * 失败时保持原状——否则界面会说"已启动"而设备那边根本没收到 S2F23。</p>
   */
  const send = async (sml: string, trids: readonly number[] = [], started = false) => {
    setBusy(true);
    setError('');
    try {
      setResult(await requireClient(client).sendHostV2(hostId, sml));
      if (trids.length > 0) {
        markManual(trids, started);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /* 只认本主机收到的 S6F1：主机视角 direction=out 表示设备发来 */
  const frames = useMemo(() => {
    const result: TraceFrame[] = [];
    for (const message of messages) {
      if (message.stream !== 6 || message.function !== 1) {
        continue;
      }
      if (message.perspective !== 'host' || message.hostId !== hostId || message.direction !== 'out') {
        continue;
      }
      const frame = traceFrameFromS6f1(message.body, message.at);
      if (frame !== null) {
        result.push(frame);
      }
    }
    return result;
  }, [messages, hostId]);

  /* 状态判定：最近 60 秒内有同 TRID 的 S6F1 = 采集中，否则未启动 */
  const runningTrids = useMemo(() => {
    const now = Date.now();
    const running = new Set<number>();
    for (const frame of frames) {
      if (now - frame.at <= RUNNING_WINDOW_MS) {
        running.add(frame.trid);
      }
    }
    return running;
  }, [frames]);

  /**
   * 按钮用「是否已启动」：把用户亲手点过的启停和报文里看到的合起来算。
   *
   * <p>只认报文的话，点「启动」到首帧到达这几百毫秒里按钮还是「启动」（手快就会重复下发），
   * 点「停止」之后又会被 60 秒窗口按着显示「停止」一分钟。手点过的意图优先，
   * 报文只作为"没手点过时"的依据。</p>
   */
  const engagedTrids = useMemo(() => {
    const engaged = new Set<number>();
    const planOf = (trid: number): TracePlan | undefined =>
      [...plans].reverse().find((item) => item.trid === trid);
    const known = new Set<number>();
    for (const plan of plans) {
      known.add(plan.trid);
      if (traceStarted(plan.trid, plan.totsmp, frames, manual.get(plan.trid))) {
        engaged.add(plan.trid);
      }
    }
    /* 报文里出现、但方案库里没有的 TRID：沿用窗口判断，别把它藏起来 */
    for (const trid of runningTrids) {
      if (!known.has(trid) && traceStarted(trid, planOf(trid)?.totsmp ?? 0, frames, manual.get(trid))) {
        engaged.add(trid);
      }
    }
    return engaged;
  }, [plans, frames, manual, runningTrids]);

  /** 记下"这一批 TRID 是用户点的启动/停止"；只有真正发出去成功才记。 */
  const markManual = (trids: readonly number[], started: boolean) => {
    setManual((previous) => {
      const next = new Map(previous);
      const at = Date.now();
      for (const trid of trids) {
        const current = next.get(trid) ?? { started: 0, stopped: 0 };
        next.set(trid, started
          ? { started: at, stopped: 0 }
          : { ...current, stopped: at });
      }
      return next;
    });
  };

  const groups = useMemo(() => buildTraceGroups(frames, plans, DEFAULT_TRACE_DRAFT), [frames, plans]);

  const selectedPlans = plans.filter((plan) => selected.has(plan.id));

  const toggleSelect = (id: string) => {
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
    setSelected((previous) => (plans.every((plan) => previous.has(plan.id))
      ? new Set<string>()
      : new Set(plans.map((plan) => plan.id))));
  };

  const toggleRun = (plan: TracePlan) => {
    const running = engagedTrids.has(plan.trid);
    void send(planSml(plan, running ? 0 : plan.totsmp), [plan.trid], !running);
  };

  /** 批量启停：串行下发，已处于目标状态者跳过，单项失败只记录不中断。 */
  const runBatch = async (start: boolean) => {
    const targets = plans.filter((plan) => selected.has(plan.id));
    if (targets.length === 0) {
      return;
    }
    setBusy(true);
    setError('');
    setBatch(null);
    let okCount = 0;
    let skipped = 0;
    const failures: BatchFailure[] = [];
    const action = start ? '启动' : '停止';
    try {
      const activeClient = requireClient(client);
      for (let index = 0; index < targets.length; index += 1) {
        const plan = targets[index];
        setProgress(`${action} ${index + 1}/${targets.length}`);
        if (engagedTrids.has(plan.trid) === start) {
          skipped += 1;
          continue;
        }
        try {
          setResult(await activeClient.sendHostV2(hostId, planSml(plan, start ? plan.totsmp : 0)));
          markManual([plan.trid], start);
          okCount += 1;
        } catch (e) {
          failures.push({ name: plan.name, message: (e as Error).message });
        }
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setProgress('');
      setBusy(false);
      setBatch({ okCount, skipped, failures });
    }
  };

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }

  /* 目录还没读回来时按"不可用"渲染手动输入，避免弹窗先闪一下控件 */
  const svidChoices = lookup?.catalog?.svids ?? null;

  const batchBar = (
    <span className="row-actions">
      <span className="hint-inline">
        {progress !== '' ? progress : `已选 ${selectedPlans.length} 条`}
      </span>
      <button
        type="button"
        className="primary"
        disabled={busy || selectedPlans.length === 0}
        onClick={() => void runBatch(true)}
        title="对选中方案逐条下发 S2F23 开始采样（已采集中者跳过）"
      >
        批量启动
      </button>
      <button
        type="button"
        disabled={busy || selectedPlans.length === 0}
        onClick={() => void runBatch(false)}
        title="对选中方案逐条下发 TOTSMP=0（未启动者跳过）"
      >
        批量停止
      </button>
    </span>
  );

  return (
    <div className="panel-stack">
      <Panel
        id="panel-host-trace"
        title={readOnly ? 'Trace 设置（S2F23 方案库）' : 'Trace 采集（S2F23 → S6F1）'}
        headless
      >
        {error !== '' && <p className="inline-note danger">{error}</p>}
        {host.open && !readOnly && host.phase !== 'CONNECTED' && (
          <p className="problems">主机还没连接,先到实例头点「连接」。</p>
        )}

        <TracePlanTable
          mode={readOnly ? 'config' : 'runtime'}
          plans={plans}
          engagedTrids={engagedTrids}
          selected={selected}
          onToggleSelect={toggleSelect}
          onToggleAll={toggleAll}
          onLoad={openEdit}
          onDelete={(id) => setPlans((previous) => previous.filter((plan) => plan.id !== id))}
          onToggleRun={toggleRun}
          busy={busy}
          /* 页面级动作下移到表格工具条右侧：一页只剩"一行操作 + 一张表" */
          toolbarEnd={readOnly
            ? <button type="button" className="primary" onClick={openCreate}>新增方案</button>
            : batchBar}
        />

        {batch !== null && (
          <>
            <p className="inline-note">
              {`成功 ${batch.okCount} / 跳过 ${batch.skipped} / 失败 ${batch.failures.length}`}
            </p>
            {batch.failures.length > 0 && (
              <ul className="hint">
                {batch.failures.map((item, index) => (
                  <li key={`${item.name}-${index}`}>{item.name}：{item.message}</li>
                ))}
              </ul>
            )}
          </>
        )}

        {!readOnly && result !== null && (
          <SmlResult
            header={`发送 ${host.id}  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`}
            result={result}
            peerLabel="设备"
          />
        )}
        <p className="hint">TOTSMP=0 可提前终止该 TRID。方案按主机落配置库（config.db），重启后端、换机器都不丢。</p>
      </Panel>

      {!readOnly && (
        <Panel id="panel-host-trace-charts" title="S6F1 参数趋势" headless>
          {plans.length === 0 && <p className="hint">还没有 Trace 方案；到配置区新增后这里按方案看趋势。</p>}
          {plans.map((plan) => {
            const open = viewingTrid === plan.trid;
            const group = groups.find((item) => item.trid === plan.trid);
            return (
              <section key={plan.id} className="panel-section">
                <div className="section-head">
                  <h3>{plan.name} <span className="mono hint-inline">TRID {plan.trid} · {plan.dsper}ms</span></h3>
                  <button type="button" onClick={() => setViewingTrid(open ? null : plan.trid)}>
                    {open ? '收起曲线' : '查看曲线'}
                  </button>
                </div>
                {open && (group === undefined
                  ? <p className="hint">该 TRID 还没有收到 S6F1 报文；先「启动」并等设备上报。</p>
                  : (
                    <div className="collector-charts">
                      {group.series.map((item) => (
                        <div className="collector-chart collector-chart-large" key={item.svid}>
                          <div className="collector-chart-head">
                            <span className="mono">{item.svid}</span>
                            <span>{item.label}</span>
                            <span className="spacer" />
                            <span className="hint-inline">
                              {item.v.length} 点 · 最近 {item.v.length > 0 ? item.v[item.v.length - 1].toFixed(2) : '—'}
                            </span>
                          </div>
                          <VariableChart
                            series={{ svid: item.svid, owner: item.label, unit: '', min: item.v, max: item.v, avg: item.v }}
                            fromMs={item.t.length > 0 ? item.t[0] : Date.now()}
                            bucketMs={item.bucketMs}
                            height={170}
                            eventLabel="事件"
                          />
                        </div>
                      ))}
                    </div>
                  ))}
              </section>
            );
          })}
        </Panel>
      )}

      <Modal
        open={modalOpen}
        title={editingId !== null ? '编辑 Trace 方案' : '新增 Trace 方案'}
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
            方案名称（留空自动生成）
            <input
              value={form.name}
              onChange={(event) => updateForm({ name: event.target.value })}
              placeholder="如：温度曲线 1s"
            />
          </label>
        </div>
        <TracePlanFields
          form={form}
          update={updateForm}
          svidChoices={svidChoices}
          catalogError={lookup?.error ?? ''}
        />
        <p className="hint">DSPER 是 hhmmss 六位数字（整秒）；TOTSMP=0 表示提前终止该 TRID。</p>
      </Modal>
    </div>
  );
}

interface TraceChartSeries {
  svid: number;
  label: string;
  t: number[];
  v: number[];
  bucketMs: number;
}

interface TraceChartGroup {
  key: string;
  trid: number;
  label: string;
  frames: TraceFrame[];
  svids: number[];
  defaultSelection: number[];
  series: TraceChartSeries[];
}

/** 把实时 S6F1 帧按 TRID 拆成可画图的序列；SVID 顺序优先取已保存方案。 */
function buildTraceGroups(
  frames: readonly TraceFrame[],
  plans: readonly TracePlan[],
  draft: typeof DEFAULT_TRACE_DRAFT,
): TraceChartGroup[] {
  const byTrid = new Map<number, TraceFrame[]>();
  for (const frame of frames) {
    const list = byTrid.get(frame.trid) ?? [];
    list.push(frame);
    byTrid.set(frame.trid, list);
  }
  const groups: TraceChartGroup[] = [];
  for (const [trid, list] of byTrid) {
    const plan = [...plans].reverse().find((item) => item.trid === trid);
    const svids = parseIdList(plan?.svids ?? (Number(draft.trid) === trid ? draft.svids : ''));
    /* 没找到方案时按 SVID 序号占位，仍能看图 */
    const maxLen = Math.max(...list.map((frame) => frame.values.length), 0);
    const effectiveSvids = svids.length > 0
      ? svids
      : Array.from({ length: Math.min(maxLen, 10) }, (_, index) => index + 1);
    if (effectiveSvids.length === 0) {
      continue;
    }
    const count = effectiveSvids.length;
    const repgszGuess = Math.max(1, Math.round(maxLen / count));
    const dsper = plan?.dsper || Number(draft.dsper) || 1000;
    const buckets = effectiveSvids.map(() => ({ t: [] as number[], v: [] as number[] }));
    for (const frame of list) {
      const chunks = Math.max(1, Math.ceil(frame.values.length / count));
      for (let chunk = 0; chunk < chunks; chunk += 1) {
        const at = frame.at - (chunks - 1 - chunk) * Math.max(1, dsper);
        for (let index = 0; index < count; index += 1) {
          const value = frame.values[chunk * count + index];
          if (value === undefined || !Number.isFinite(value)) {
            continue;
          }
          buckets[index].t.push(at);
          buckets[index].v.push(value);
        }
      }
    }
    const allTimes = list.map((frame) => frame.at);
    const from = allTimes.length > 0 ? allTimes[0] : 0;
    const to = allTimes.length > 1 ? allTimes[allTimes.length - 1] : from + dsper;
    const totalPoints = Math.max(...buckets.map((bucket) => bucket.v.length), 1);
    const bucketMs = Math.max(1, Math.round((to - from) / Math.max(1, totalPoints - 1)) / Math.max(1, repgszGuess));
    groups.push({
      key: String(trid),
      trid,
      label: plan?.name ?? (Number(draft.trid) === trid ? '当前草稿' : '未命名计划'),
      frames: list,
      svids: effectiveSvids,
      defaultSelection: effectiveSvids.slice(0, 2),
      series: effectiveSvids.map((svid, index) => ({
        svid,
        label: `SVID ${svid}`,
        t: buckets[index].t,
        v: buckets[index].v,
        bucketMs,
      })),
    });
  }
  return groups;
}