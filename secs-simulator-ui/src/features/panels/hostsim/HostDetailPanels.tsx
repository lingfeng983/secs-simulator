import { useEffect, useMemo, useRef, useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { DataTable, type Column } from '../../../shared/ui/DataTable';
import { SmlResult } from '../../../shared/ui/SmlResult';
import { Modal } from '../../../shared/ui/Modal';
import { VariableChart } from '../../charts/VariableChart';
import type { PanelBaseProps } from '../types';
import type { HostPage, HostZone } from '../../../app/router';
import { HOST_PRESETS, PRESET_GROUPS } from './hostPresets';
import type { HostsV2State } from '../../../state/useHostsV2';
import type { ApiClient, CollectorState, PreviewSeries, SentMessageResult, WireMessage } from '../../../api';
import {
  DEFAULT_TRACE_DRAFT,
  parseIdList,
  planId,
  useCollectPlans,
  useHostSendDraft,
  useTracePlans,
  type CollectPlan,
  type TraceDraft,
  type TracePlan,
} from '../../../state/hostPlans';
import { ceidFromS6f11, traceFrameFromS6f1, type TraceFrame } from '../../../shared/lib/smlValues';

/**
 * 主机详情子页分发。
 *
 * <p>配置区 4 页 + 运行区 3 页（消息监控已移到右侧抽屉，实时状态并入实例头）。
 * 配置区的 Trace / 采集设置只管理方案与参数；下发、发送与曲线都在运行区。
 * Trace 运行页直接从实时 S6F1 报文里取参数值画趋势，按 TRID 分别选择要看的参数。</p>
 */
export function HostDetailPanels({
  hostId,
  zone,
  page,
  hosts,
  goTo,
  client,
  messages,
}: {
  hostId: string;
  zone: HostZone;
  page: HostPage;
  hosts: HostsV2State;
  goTo?: (id: string) => void;
  client: ApiClient | null;
  messages?: PanelBaseProps['messages'];
}): JSX.Element {
  const base = { hosts, goTo, hostId, client, messages };
  if (zone === 'config') {
    switch (page) {
      case 'overview':
        return <HostOverviewPanel {...base} />;
      case 'presets':
        return <HostPresetsPanel {...base} />;
      case 'trace':
        return <HostTracePanel {...base} readOnly key="trace-config" />;
      case 'collect':
        return <HostCollectPanel {...base} readOnly key="collect-config" />;
      default:
        return <HostOverviewPanel {...base} />;
    }
  }
  switch (page) {
    case 'messages':
      return <HostSendPanel {...base} />;
    case 'trace':
      return <HostTracePanel {...base} key="trace-runtime" />;
    case 'collect':
      return <HostCollectPanel {...base} key="collect-runtime" />;
    default:
      return <HostSendPanel {...base} />;
  }
}

type PanelArgs = {
  hostId: string;
  hosts: HostsV2State;
  goTo?: (id: string) => void;
  readOnly?: boolean;
  client: ApiClient | null;
  messages?: PanelBaseProps['messages'];
};

/* ---------- 配置区 ---------- */

/** 概览:名称/目标编辑 + 保存(设置版本 +1,需「使设置生效」) + 采集方案概览。 */
function HostOverviewPanel({ hostId, hosts, readOnly, client }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [name, setName] = useState('');
  const [host_, setHost_] = useState('');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [loadedFor, setLoadedFor] = useState('');
  const [tracePlans] = useTracePlans(hostId);
  const [collectPlans] = useCollectPlans(hostId);
  const [collector, setCollector] = useState<CollectorState | null>(null);

  useEffect(() => {
    if (host !== null && loadedFor !== host.id) {
      setLoadedFor(host.id);
      setName(host.name);
      setHost_(host.target.split(':')[0] ?? '');
      setPort(String(host.port));
      setDeviceId(String(host.deviceId));
    }
  }, [host, loadedFor]);

  /* 采集概览：S1F3 采集器的运行状态是活数据，2 秒刷一次。 */
  useEffect(() => {
    if (client === null) {
      return;
    }
    let cancelled = false;
    const load = () => {
      void client.hostInstanceCollector(hostId)
        .then((body) => { if (!cancelled) { setCollector(body); } })
        .catch(() => undefined);
    };
    load();
    const timer = window.setInterval(load, 2_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [client, hostId]);

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  const dirty = name !== host.name
    || host_ !== host.target.split(':')[0]
    || Number(port) !== host.port
    || Number(deviceId) !== host.deviceId;

  return (
    <div className="panel-stack">
      <Panel
        id="panel-host-overview"
        title="主机概览"
        description="实例的身份与目标;保存后设置版本 +1,需点「使设置生效」按最新设置重连。"
      >
        {error !== '' && <p className="inline-note danger">{error}</p>}
        <div className="form-row">
          <label className="field">
            名称
            <input value={name} disabled={readOnly}
              onChange={(event) => setName(event.target.value)} />
          </label>
          <label className="field">
            目标地址
            <input value={host_} disabled={readOnly}
              onChange={(event) => setHost_(event.target.value)} />
          </label>
        </div>
        <div className="form-row">
          <label className="field">
            端口
            <input type="number" min={1} max={65535} value={port} disabled={readOnly}
              onChange={(event) => setPort(event.target.value)} />
          </label>
          <label className="field">
            Device ID
            <input type="number" min={0} value={deviceId} disabled={readOnly}
              onChange={(event) => setDeviceId(event.target.value)} />
          </label>
        </div>
        <div className="editor-foot">
          <span className="hint">
            当前设置 v{host.settingRevision} · {host.open ? `生效 v${host.appliedRevision}` : '离线'}
            {host.autoReconnect ? ' · 自动重连开' : ''}
          </span>
          {!readOnly && (
            <button
              type="button"
              className="primary"
              disabled={busy || !dirty || host_.trim() === '' || !(Number(port) >= 1 && Number(port) <= 65535)}
              onClick={() => {
                setBusy(true);
                setError('');
                hosts.saveSettings(hostId, {
                  name: name.trim(), host: host_.trim(), port: Number(port), deviceId: Number(deviceId) || 0,
                })
                  .catch((e: Error) => setError(e.message))
                  .finally(() => setBusy(false));
              }}
            >
              保存设置
            </button>
          )}
        </div>
      </Panel>

      {/* 需求：概览页能一眼看到后面 Trace 采集 / S1F3 采集的配置与运行状态 */}
      <Panel
        id="panel-host-overview-collect"
        title="采集概览"
        description="配置区保存的 Trace / S1F3 采集方案，以及当前 S1F3 采集器的运行状态。运行区可直接点方案使用。"
      >
        <div className="overview-grid">
          <div className="overview-card">
            <h3>Trace 采集方案（S2F23 → S6F1）</h3>
            <span className="overview-value">{tracePlans.length}</span>
            <span className="hint-inline"> 份</span>
            {tracePlans.length === 0 ? (
              <p className="hint">还没有保存方案；到「配置区 · Trace 设置」保存后可一键调用。</p>
            ) : (
              <ul>
                {tracePlans.slice(0, 6).map((plan) => (
                  <li key={plan.id}>{plan.name} · TRID {plan.trid} · {parseIdList(plan.svids).length} 个变量</li>
                ))}
                {tracePlans.length > 6 && <li>…另有 {tracePlans.length - 6} 份</li>}
              </ul>
            )}
          </div>
          <div className="overview-card">
            <h3>S1F3 采集方案</h3>
            <span className="overview-value">{collectPlans.length}</span>
            <span className="hint-inline"> 份</span>
            {collectPlans.length === 0 ? (
              <p className="hint">还没有保存方案；到「配置区 · 采集设置」保存后可一键下发。</p>
            ) : (
              <ul>
                {collectPlans.slice(0, 6).map((plan) => (
                  <li key={plan.id}>{plan.name} · CEID {plan.startCeid}/{plan.stopCeid} · {parseIdList(plan.svids).length} 个变量</li>
                ))}
                {collectPlans.length > 6 && <li>…另有 {collectPlans.length - 6} 份</li>}
              </ul>
            )}
          </div>
          <div className="overview-card">
            <h3>S1F3 采集器当前状态</h3>
            <span className={`overview-value${collector?.running ? ' tone-ok' : ''}`}>
              {collector?.running ? '采集中' : collector?.configured ? '已配置 · 待启动' : '未配置'}
            </span>
            <ul>
              <li>启动 / 停止 CEID：{collector?.startCeid ?? '—'} / {collector?.stopCeid ?? '—'}</li>
              <li>采样周期：{collector?.intervalMs ?? '—'} ms</li>
              <li>样本点：{collector?.samples ?? 0} · 解码错误：{collector?.decodeErrors ?? 0}</li>
              <li>采集变量：{collector?.svids?.length ?? 0} 个</li>
            </ul>
          </div>
        </div>
      </Panel>
    </div>
  );
}

/** 连接页已删除：目标/端口/Device ID 直接在「概览」里改，连接动作在实例头。 */

/**
 * 报文模板：不只列 SxFy，逐条给出可复制的具体 SML 案例，
 * 并可一键填进运行区「消息发送」的编辑框。
 */
function HostPresetsPanel({ hostId }: PanelArgs): JSX.Element {
  const [, setSendDraft] = useHostSendDraft(hostId, 'send');
  const [copied, setCopied] = useState('');

  return (
    <Panel
      id="panel-host-presets"
      title="报文模板 · SML 案例"
      description={`${HOST_PRESETS.length} 条主机方向常用报文，按 ${PRESET_GROUPS.length} 组列出具体 SML 案例（可直接复制到运行区发送，或点「填入运行区发送」）。`}
    >
      {copied !== '' && <p className="inline-note">{copied}</p>}
      {PRESET_GROUPS.map((group) => (
        <section key={group.label} style={{ marginBottom: 14 }}>
          <h3 style={{ fontSize: 13, margin: '0 0 8px' }}>{group.label}</h3>
          <div className="preset-case-grid">
            {HOST_PRESETS.filter((preset) => preset.group === group.label).map((preset) => (
              <div className="preset-case" key={preset.label}>
                <h4>{preset.label} <span className="hint-inline">→ {preset.reply}</span></h4>
                <p>{preset.desc}</p>
                <pre>{preset.sml}</pre>
                <div className="toolbar">
                  <button type="button" onClick={() => { setSendDraft(preset.sml); setCopied(`已把「${preset.label}」填入运行区 · 消息发送`); }}>
                    填入运行区发送
                  </button>
                  <button type="button" onClick={() => {
                    void navigator.clipboard?.writeText(preset.sml)
                      .then(() => setCopied(`已复制「${preset.label}」的 SML`))
                      .catch(() => setCopied('复制失败，请手动选中上面文本框里的内容'));
                  }}>
                    复制 SML
                  </button>
                </div>
              </div>
            ))}
          </div>
        </section>
      ))}
    </Panel>
  );
}

/* ---------- 运行区 ---------- */

/** 实时状态页已下线：链路/版本/重试都收进实例头，报文收发在右侧抽屉。 */

/** 消息发送(单主机):预设 + SML 编辑 + 发送 + 压测。 */
function HostSendPanel({ hostId, hosts }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [storedDraft, setDraft] = useHostSendDraft(hostId, 'send');
  const draft = storedDraft === '' ? 'S1F13 W' : storedDraft;
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  const [error, setError] = useState('');

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  const send = async () => {
    setBusy(true);
    setError('');
    try {
      setResult(await hosts.send(hostId, draft));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel
      id="panel-host-send"
      title="消息发送"
      description="以本主机身份发一条 SML;收发明细在右侧「实时消息监控」。配置区·报文模板里有可直接填入的 SML 案例。"
    >
      {error !== '' && <p className="inline-note danger">{error}</p>}
      <div className="preview-controls">
        {HOST_PRESETS.filter((preset) => preset.quick).map((preset) => (
          <button key={preset.label} type="button" title={preset.desc}
            onClick={() => setDraft(preset.sml)}>
            {preset.label}
          </button>
        ))}
      </div>
      <textarea
        className="sml-editor"
        spellCheck={false}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        aria-label="本主机 SML 报文内容"
      />
      <div className="editor-foot">
        <span className="hint">带 W 的报文会等待设备回复;模板填入后仍可修改再发送。</span>
        <button type="button" className="primary" disabled={busy || !host.open || draft.trim() === ''}
          onClick={() => void send()}>
          {busy ? '发送中…' : '发送'}
        </button>
      </div>
      {result !== null && (
        <SmlResult
          header={`发送 ${host.id}  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`}
          result={result}
          peerLabel="设备"
        />
      )}
    </Panel>
  );
}

/* ---------- Trace 采集 ---------- */

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

/** 已保存方案列表（DataTable）：配置区编辑/删除，运行区启停。 */
function TracePlanTable({
  plans,
  mode,
  onLoad,
  onDelete,
  onStart,
  onStop,
  busy,
}: {
  plans: readonly TracePlan[];
  mode: 'config' | 'runtime';
  onLoad: (plan: TracePlan) => void;
  onDelete: (id: string) => void;
  onStart: (plan: TracePlan) => void;
  onStop: (plan: TracePlan) => void;
  busy: boolean;
}): JSX.Element {
  const editing = mode === 'config';
  const columns: Column<TracePlan>[] = [
    { key: 'name', header: '方案', sortValue: (row) => row.name, render: (row) => row.name },
    { key: 'trid', header: 'TRID', sortValue: (row) => row.trid, render: (row) => <span className="mono">{row.trid}</span> },
    { key: 'dsper', header: '采样周期', sortValue: (row) => row.dsper, render: (row) => <span className="mono">{row.dsper}ms</span> },
    { key: 'totsmp', header: '样本数', sortValue: (row) => row.totsmp, render: (row) => <span className="mono">{row.totsmp}</span> },
    { key: 'repgsz', header: 'REPGSZ', sortValue: (row) => row.repgsz, render: (row) => <span className="mono">{row.repgsz}</span> },
    { key: 'svids', header: 'SVID', render: (row) => <span className="mono hint-inline">{row.svids}</span> },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          {editing ? (
            <>
              <button type="button" onClick={() => onLoad(row)} title="载入到上方表单修改，再点「保存修改」写回">
                编辑
              </button>
              <button type="button" className="danger" onClick={() => onDelete(row.id)} title="删除方案（配置库同步删除）">
                删除
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => onStart(row)}
                title="载入方案并下发 S2F23 开始采样"
              >
                启动
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => onStop(row)}
                title="对该 TRID 下发 TOTSMP=0，提前终止采样"
              >
                停止
              </button>
            </>
          )}
        </div>
      ),
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
      emptyText={editing
        ? '还没有保存的 Trace 方案。填好上方参数与方案名称后点「保存为方案」。'
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

/**
 * Trace 设置 / 采集。
 *
 * <p>配置区负责把多份 S2F23 方案存起来（本机按 hostId 持久化），运行区
 * 顶部直接列出这些方案，点一下就载入并下发。运行区下方把设备推来的
 * S6F1 按 TRID 分组、按变量画趋势，每个 TRID 单独记一组要看的参数。</p>
 */
/** Trace 方案参数字段（新增/编辑弹窗共用）。 */
function TracePlanFields({ form, update }: {
  form: TraceDraft;
  update: (patch: Partial<TraceDraft>) => void;
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
      <label className="field field-wide">
        采集变量(逗号/空格分隔)
        <input value={form.svids} onChange={(event) => update({ svids: event.target.value })} />
      </label>
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
 * 提供启停（启动 = 下发 S2F23 开始采样，停止 = 该 TRID 下发 TOTSMP=0）；
 * 数据曲线按方案折叠，点「查看曲线」才计算绘制，默认不展示。</p>
 */
function HostTracePanel({ hostId, hosts, readOnly, client, messages = [] }: PanelArgs): JSX.Element {
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

  const updateForm = (patch: Partial<TracePlanForm>) => setForm((previous) => ({ ...previous, ...patch }));

  const openCreate = () => {
    setForm({ trid: '1', dsper: '1000', totsmp: '100', repgsz: '1', svids: '1001, 1003', name: '' });
    setEditingId(null);
    setModalOpen(true);
  };

  const openEdit = (plan: TracePlan) => {
    setForm({
      trid: String(plan.trid), dsper: String(plan.dsper), totsmp: String(plan.totsmp),
      repgsz: String(plan.repgsz), svids: plan.svids, name: plan.name,
    });
    setEditingId(plan.id);
    setModalOpen(true);
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
    setModalOpen(false);
  };

  const send = async (sml: string) => {
    setBusy(true);
    setError('');
    try {
      setResult(await requireClient(client).sendHostV2(hostId, sml));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const startPlan = (plan: TracePlan) => send(buildS2f23({
    trid: String(plan.trid), dsper: String(plan.dsper), totsmp: String(plan.totsmp),
    repgsz: String(plan.repgsz), svids: plan.svids,
  }));

  const stopPlan = (plan: TracePlan) => send(buildS2f23({
    /* TOTSMP=0 提前终止该 TRID 的采样 */
    trid: String(plan.trid), dsper: String(plan.dsper), totsmp: '0',
    repgsz: String(plan.repgsz), svids: plan.svids,
  }));

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

  const groups = useMemo(() => buildTraceGroups(frames, plans, DEFAULT_TRACE_DRAFT), [frames, plans]);

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }

  return (
    <div className="panel-stack">
      <Panel
        id="panel-host-trace"
        title={readOnly ? 'Trace 设置（S2F23 方案库）' : 'Trace 采集（S2F23 → S6F1）'}
        description={readOnly
          ? 'S2F23 方案库：新增 / 编辑 / 删除方案（DSPER 是 hhmmss 六位数字、整秒）。\n下发与趋势曲线在运行区「Trace 采集」。'
          : '设备按 DSPER 采样、REPGSZ 分条上报 S6F1。方案列表里启停；点「查看曲线」看对应 TRID 的趋势。'}
        actions={readOnly
          ? <button type="button" className="primary" onClick={openCreate}>新增方案</button>
          : undefined}
      >
        {error !== '' && <p className="inline-note danger">{error}</p>}
        {host.open && !readOnly && host.phase !== 'CONNECTED' && (
          <p className="problems">主机还没连接,先到实例头点「连接」。</p>
        )}

        <TracePlanTable
          mode={readOnly ? 'config' : 'runtime'}
          plans={plans}
          onLoad={openEdit}
          onDelete={(id) => setPlans((previous) => previous.filter((plan) => plan.id !== id))}
          onStart={startPlan}
          onStop={stopPlan}
          busy={busy}
        />

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
        <Panel
          id="panel-host-trace-charts"
          title="S6F1 参数趋势"
          description="按方案折叠；点「查看曲线」才解析绘制该 TRID 的趋势，默认不展示。"
        >
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
        onClose={() => setModalOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setModalOpen(false)}>取消</button>
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
        <TracePlanFields form={form} update={updateForm} />
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

/* ---------- S1F3 数据采集 ---------- */

/** 已保存的 S1F3 采集配置列表。 */
/** 已保存采集配置列表（DataTable）：配置区编辑/删除，运行区启停。 */
function CollectPlanTable({
  plans,
  mode,
  onLoad,
  onDelete,
  onStart,
  onStop,
  busy,
}: {
  plans: readonly CollectPlan[];
  mode: 'config' | 'runtime';
  onLoad: (plan: CollectPlan) => void;
  onDelete: (id: string) => void;
  onStart: (plan: CollectPlan) => void;
  onStop: () => void;
  busy: boolean;
}): JSX.Element {
  const editing = mode === 'config';
  const columns: Column<CollectPlan>[] = [
    { key: 'name', header: '配置', sortValue: (row) => row.name, render: (row) => row.name },
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
              <button type="button" onClick={() => onLoad(row)} title="载入到上方表单修改，再点「保存修改」写回">
                编辑
              </button>
              <button type="button" className="danger" onClick={() => onDelete(row.id)} title="删除配置（配置库同步删除）">
                删除
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => onStart(row)}
                title="下发该配置并启动采集"
              >
                启动
              </button>
              <button type="button" disabled={busy} onClick={onStop} title="停止采集">
                停止
              </button>
            </>
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
      emptyText={editing
        ? '还没有保存的采集配置。填好上方参数与配置名称后点「保存为配置」。'
        : '还没有采集配置；到配置区「采集设置」新增后这里即可启停。'}
      exportColumns={[
        { header: '配置', value: (row) => row.name },
        { header: '启动CEID', value: (row) => row.startCeid },
        { header: '停止CEID', value: (row) => row.stopCeid },
        { header: '周期ms', value: (row) => row.intervalMs },
        { header: 'SVID', value: (row) => row.svids },
      ]}
    />
  );
}

/** 数据采集(S1F3 轮询):多份配置 + 启停 CEID 兜底触发 + 可选参数曲线。 */
/** 采集配置字段（新增/编辑弹窗共用）。 */
function CollectPlanFields({ form, update }: {
  form: CollectPlanForm;
  update: (patch: Partial<CollectPlanForm>) => void;
}): JSX.Element {
  return (
    <>
      <div className="form-row">
        <label className="field">启动 CEID
          <input value={form.startCeid} onChange={(event) => update({ startCeid: event.target.value })} />
        </label>
        <label className="field">停止 CEID
          <input value={form.stopCeid} onChange={(event) => update({ stopCeid: event.target.value })} />
        </label>
        <label className="field">周期 ms
          <input value={form.intervalMs} onChange={(event) => update({ intervalMs: event.target.value })} />
        </label>
      </div>
      <label className="field field-wide">
        采集变量(逗号/空格分隔)
        <input value={form.svids} onChange={(event) => update({ svids: event.target.value })} />
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

/**
 * 数据采集（S1F3 轮询）。
 *
 * <p>配置区只管配置库：列表 + 新增/编辑弹窗 + 删除。运行区同一份列表提供
 * 启停（启动 = 下发该配置并开始采集，停止 = 停止采集）；数据曲线按配置
 * 折叠，点「查看曲线」才展示，默认不展示。</p>
 */
function HostCollectPanel({ hostId, hosts, readOnly, client, messages = [] }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [plans, setPlans] = useCollectPlans(hostId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [state, setState] = useState<CollectorState | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<CollectPlanForm>({
    name: '', startCeid: '2001', stopCeid: '2002', intervalMs: '1000', svids: '1001, 1003',
  });
  const [viewingPlanId, setViewingPlanId] = useState<string | null>(null);
  const timerRef = useRef<number | null>(null);
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

  const applyConfig = async (override?: CollectPlan) => {
    const source = override;
    if (source === undefined) {
      return;
    }
    setBusy(true);
    setError('');
    try {
      await requireClient(client).setHostInstanceCollector(hostId, {
        startCeid: Number(source.startCeid) || 0,
        stopCeid: Number(source.stopCeid) || 0,
        intervalMs: Number(source.intervalMs) || 1000,
        svids: parseIdList(source.svids).map((id) => ({ id })),
      });
      await loadState();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const command = async (start: boolean) => {
    setBusy(true);
    setError('');
    try {
      await requireClient(client).hostInstanceCollectorCommand(hostId, start);
      await loadState();
      setNotice(start ? '采集已启动' : '采集已停止');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const startPlan = async (plan: CollectPlan) => {
    await applyConfig(plan);
    await command(true);
  };

  const openCreate = () => {
    setForm({ name: '', startCeid: '2001', stopCeid: '2002', intervalMs: '1000', svids: '1001, 1003' });
    setEditingId(null);
    setModalOpen(true);
  };

  const openEdit = (plan: CollectPlan) => {
    setForm({
      name: plan.name, startCeid: plan.startCeid, stopCeid: plan.stopCeid,
      intervalMs: plan.intervalMs, svids: plan.svids,
    });
    setEditingId(plan.id);
    setModalOpen(true);
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
     只认本主机视角 + 本次打开之后的新报文，每条只处理一次。 */
  const startCeid = state?.startCeid ?? 0;
  const stopCeid = state?.stopCeid ?? 0;
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
      if (ceid === startCeid && startCeid > 0 && !state?.running) {
        setNotice(`收到启动 CEID ${ceid}，正在启动采集…`);
        void command(true);
      } else if (ceid === stopCeid && stopCeid > 0 && state?.running) {
        setNotice(`收到停止 CEID ${ceid}，正在停止采集…`);
        void command(false);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, client, hostId, host?.open, readOnly, startCeid, stopCeid, state?.running]);

  /* 采集样本是逐点值(min=max=avg);时间轴用样本自带的 epoch 毫秒。 */
  const charts = (state?.series ?? []).map((item) => {
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

  return (
    <Panel
      id="panel-host-collect"
      title={readOnly ? '采集设置（S1F3 配置库）' : '数据采集（S1F3 轮询）'}
      description={readOnly
        ? '采集配置库：新增 / 编辑 / 删除配置方案（启停 CEID / 周期 / SVID 清单）。\n下发、启停与数据曲线在运行区「数据采集」页。'
        : '配置列表里启停（启动 = 下发该配置并开始采集）；点「查看曲线」看该配置的 SVID 曲线，默认不展示。'}
      actions={readOnly
        ? <button type="button" className="primary" onClick={openCreate}>新增配置</button>
        : undefined}
    >
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {notice !== '' && <p className="inline-note">{notice}</p>}

      <CollectPlanTable
        mode={readOnly ? 'config' : 'runtime'}
        plans={plans}
        onLoad={openEdit}
        onDelete={(id) => setPlans((previous) => previous.filter((plan) => plan.id !== id))}
        onStart={(plan) => void startPlan(plan)}
        onStop={() => void command(false)}
        busy={busy}
      />

      {!readOnly && (
        <div className="toolbar">
          <span className="hint-inline">
            {state?.running
              ? `采集中 · ${state.samples ?? 0} 点 · 解码错误 ${state.decodeErrors ?? 0}`
              : state?.configured
                ? `已配置 · 等待启动 CEID ${startCeid}，或在方案列表点「启动」`
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
            const planCharts = charts.filter((chart) => planSvids.includes(chart.svid));
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
        onClose={() => setModalOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setModalOpen(false)}>取消</button>
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
        <CollectPlanFields
          form={form}
          update={(patch) => setForm((previous) => ({ ...previous, ...patch }))}
        />
      </Modal>
    </Panel>
  );
}

/** 手动操作页签已删除：连接/断开/使设置生效在实例头，单发报文用「消息发送」。 */

/** 需要直接发报文的子页共用：后端未就绪时在异步 send 里抛错，由各面板转成行内提示。 */
function requireClient(client: ApiClient | null): ApiClient {
  if (client === null) {
    throw new Error('后端未连接');
  }
  return client;
}
