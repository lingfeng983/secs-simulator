import { useEffect, useMemo, useRef, useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { SmlResult } from '../../../shared/ui/SmlResult';
import { VariableChart } from '../../charts/VariableChart';
import type { PanelBaseProps } from '../types';
import type { HostPage, HostZone } from '../../../app/router';
import { HOST_PRESETS, PRESET_GROUPS } from './hostPresets';
import type { HostsV2State } from '../../../state/useHostsV2';
import type { ApiClient, CollectorState, PreviewSeries, SentMessageResult, WireMessage } from '../../../api';
import {
  DEFAULT_COLLECT_DRAFT,
  DEFAULT_TRACE_DRAFT,
  parseIdList,
  planId,
  useCollectDraft,
  useCollectPlans,
  useCollectSelection,
  useHostSendDraft,
  useTraceDraft,
  useTracePlans,
  useTraceSelection,
  type CollectPlan,
  type TracePlan,
} from '../../../state/hostPlans';
import { ceidFromS6f11, traceFrameFromS6f1, type TraceFrame } from '../../../shared/lib/smlValues';

/**
 * 主机详情子页分发。
 *
 * <p>配置区 5 页 + 运行区 5 页（消息监控已移到右侧抽屉）。配置区的
 * Trace / 采集设置可以保存多份方案，运行区一键载入并下发；Trace 运行页
 * 直接从实时 S6F1 报文里取参数值画趋势，按 TRID 分别选择要看的参数。</p>
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
        return <HostTracePanel {...base} readOnly />;
      case 'collect':
        return <HostCollectPanel {...base} readOnly />;
      default:
        return <HostOverviewPanel {...base} />;
    }
  }
  switch (page) {
    case 'status':
      return <HostRuntimeStatusPanel {...base} />;
    case 'messages':
      return <HostSendPanel {...base} />;
    case 'trace':
      return <HostTracePanel {...base} />;
    case 'collect':
      return <HostCollectPanel {...base} />;
    default:
      return <HostRuntimeStatusPanel {...base} />;
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

/** 实时状态:相位/版本/重试。 */
function HostRuntimeStatusPanel({ hostId, hosts }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  return (
    <section className="panel-section">
      <div className="section-head">
        <h2>实时状态</h2>
      </div>
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{host.phase === 'CONNECTED' ? '链接' : '停止'}</div>
          <div className="kpi-label">链路状态</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{host.open ? `v${host.appliedRevision}` : '—'}</div>
          <div className="kpi-label">当前生效版本</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">v{host.settingRevision}</div>
          <div className="kpi-label">最新设置版本</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{host.autoReconnect ? host.retryCount : '—'}</div>
          <div className="kpi-label">重试次数</div>
        </div>
      </div>
      {host.lastError ? <p className="inline-note danger">{host.lastError}</p> : null}
      <p className="hint">断开只关连接;重新连接后按设置恢复,采集会自动恢复。报文收发在右侧「实时消息监控」查看。</p>
    </section>
  );
}

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

/** 已保存方案的列表行（配置区用：载入 / 删除）。 */
function TracePlanRows({
  plans,
  onLoad,
  onDelete,
}: {
  plans: readonly TracePlan[];
  onLoad: (plan: TracePlan) => void;
  onDelete: (id: string) => void;
}): JSX.Element {
  if (plans.length === 0) {
    return <p className="hint">还没有保存的 Trace 采集方案。填好下面的参数后点「保存为方案」，运行区就能一键使用。</p>;
  }
  return (
    <div className="plan-list">
      {plans.map((plan) => (
        <div className="plan-row" key={plan.id}>
          <span className="plan-name">{plan.name}</span>
          <span className="plan-meta mono">
            TRID {plan.trid} · {plan.dsper}ms · {plan.totsmp} 点 · REPGSZ {plan.repgsz} · SVID {plan.svids}
          </span>
          <span className="spacer" />
          <button type="button" onClick={() => onLoad(plan)}>载入</button>
          <button type="button" className="danger" onClick={() => onDelete(plan.id)}>删除</button>
        </div>
      ))}
    </div>
  );
}

/**
 * Trace 设置 / 采集。
 *
 * <p>配置区负责把多份 S2F23 方案存起来（本机按 hostId 持久化），运行区
 * 顶部直接列出这些方案，点一下就载入并下发。运行区下方把设备推来的
 * S6F1 按 TRID 分组、按变量画趋势，每个 TRID 单独记一组要看的参数。</p>
 */
function HostTracePanel({ hostId, hosts, readOnly, client, messages = [] }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [draft, setDraft] = useTraceDraft(hostId);
  const [plans, setPlans] = useTracePlans(hostId);
  const [selection, setSelection] = useTraceSelection(hostId);
  const [planName, setPlanName] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  const [error, setError] = useState('');
  const [preview, setPreview] = useState('');

  const update = (patch: Partial<typeof DEFAULT_TRACE_DRAFT>) => setDraft((previous) => ({ ...previous, ...patch }));

  const savePlan = () => {
    const label = planName.trim() || `TRID ${draft.trid} · ${draft.dsper}ms`;
    const next: TracePlan = {
      id: planId(), name: label,
      trid: Number(draft.trid) || 1,
      dsper: Number(draft.dsper) || 1000,
      totsmp: Number(draft.totsmp) || 0,
      repgsz: Number(draft.repgsz) || 1,
      svids: draft.svids,
      createdAt: Date.now(),
    };
    setPlans((previous) => [...previous, next]);
    setPlanName('');
  };

  const loadPlan = (plan: TracePlan) => {
    setDraft({
      trid: String(plan.trid), dsper: String(plan.dsper), totsmp: String(plan.totsmp),
      repgsz: String(plan.repgsz), svids: plan.svids,
    });
    setPreview(buildS2f23({
      trid: String(plan.trid), dsper: String(plan.dsper), totsmp: String(plan.totsmp),
      repgsz: String(plan.repgsz), svids: plan.svids,
    }));
  };

  const send = async (sml?: string) => {
    setBusy(true);
    setError('');
    try {
      const body = sml ?? buildS2f23(draft);
      setPreview(body);
      setResult(await requireClient(client).sendHostV2(hostId, body));
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

  const traceGroups = useMemo(() => buildTraceGroups(frames, plans, draft), [frames, plans, draft]);

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }

  return (
    <div className="panel-stack">
      <Panel
        id="panel-host-trace"
        title={readOnly ? 'Trace 设置（S2F23 方案库）' : 'Trace 采集（S2F23 → S6F1）'}
        description="设备按 DSPER 采样、REPGSZ 分条上报 S6F1;DSPER 是 hhmmss 六位数字(整秒)。可保存多份方案，运行区一键调用。"
      >
        {error !== '' && <p className="inline-note danger">{error}</p>}
        {host.open && !readOnly && host.phase !== 'CONNECTED' && (
          <p className="problems">主机还没连接,先到实例头点「连接」。</p>
        )}

        {!readOnly && plans.length > 0 && (
          <div className="plan-chips">
            <span className="hint-inline">一键使用已存方案：</span>
            {plans.map((plan) => (
              <button
                className="plan-chip"
                type="button"
                key={plan.id}
                disabled={busy || !host.open}
                title={`载入 ${plan.name} 并立即发送 S2F23`}
                onClick={() => {
                  loadPlan(plan);
                  void send(buildS2f23({
                    trid: String(plan.trid), dsper: String(plan.dsper), totsmp: String(plan.totsmp),
                    repgsz: String(plan.repgsz), svids: plan.svids,
                  }));
                }}
              >
                {plan.name} · TRID {plan.trid}
              </button>
            ))}
          </div>
        )}

        <div className="form-row">
          <label className="field">TRID
            <input type="number" min={1} value={draft.trid} onChange={(event) => update({ trid: event.target.value })} />
          </label>
          <label className="field">采样周期 ms
            <input type="number" min={1000} step={1000} value={draft.dsper} onChange={(event) => update({ dsper: event.target.value })} />
          </label>
          <label className="field">总样本数
            <input type="number" min={0} value={draft.totsmp} onChange={(event) => update({ totsmp: event.target.value })} />
          </label>
          <label className="field">每条样本数
            <input type="number" min={1} value={draft.repgsz} onChange={(event) => update({ repgsz: event.target.value })} />
          </label>
        </div>
        <label className="field field-wide">
          采集变量(逗号/空格分隔)
          <input value={draft.svids} onChange={(event) => update({ svids: event.target.value })} />
        </label>

        <div className="form-row" style={{ marginTop: 8 }}>
          <label className="field field-wide">
            方案名称（留空自动生成）
            <input value={planName} onChange={(event) => setPlanName(event.target.value)} placeholder="如：温度曲线 1s" />
          </label>
          <div className="toolbar" style={{ alignItems: 'flex-end' }}>
            <button type="button" onClick={savePlan}>保存为方案</button>
            <button type="button" onClick={() => setDraft({ ...DEFAULT_TRACE_DRAFT })}>恢复默认</button>
            <button type="button" onClick={() => setPreview(buildS2f23(draft))}>生成 S2F23</button>
            {!readOnly && (
              <button type="button" className="primary" disabled={busy || !host.open} onClick={() => void send()}>
                {busy ? '发送中…' : '生成并发送'}
              </button>
            )}
          </div>
        </div>

        <TracePlanRows
          plans={plans}
          onLoad={loadPlan}
          onDelete={(id) => setPlans((previous) => previous.filter((plan) => plan.id !== id))}
        />

        {preview !== '' && <pre className="sml-result">{preview}</pre>}
        {result !== null && (
          <SmlResult
            header={`发送 ${host.id}  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`}
            result={result}
            peerLabel="设备"
          />
        )}
        <p className="hint">TOTSMP=0 可提前终止该 TRID。已保存的方案按主机保存在本机，切页签/重启页面不会丢。</p>
      </Panel>

      {!readOnly && (
        <Panel
          id="panel-host-trace-charts"
          title="S6F1 参数趋势"
          description="实时解析本主机收到的 S6F1 报文体，按 TRID 分组画曲线。每个 Trace 计划可以单独勾选要看的参数。"
        >
          {traceGroups.length === 0 ? (
            <p className="hint">还没有收到 S6F1。下发一条 S2F23 并等设备上报后，这里会出现每个 TRID 的参数曲线。</p>
          ) : (
            traceGroups.map((group) => {
              const selected = selection[group.key] ?? group.defaultSelection;
              return (
                <section className="trace-group" key={group.key}>
                  <div className="section-head">
                    <h3>TRID {group.trid} <span className="hint-inline">{group.label} · {group.frames.length} 帧</span></h3>
                  </div>
                  <div className="param-picker">
                    <span className="hint-inline">要看的参数：</span>
                    {group.svids.map((svid) => (
                      <label className="check-inline" key={svid}>
                        <input
                          type="checkbox"
                          checked={selected.includes(svid)}
                          onChange={(event) => {
                            const next = event.target.checked
                              ? [...selected, svid]
                              : selected.filter((id) => id !== svid);
                            setSelection((previous) => ({ ...previous, [group.key]: next }));
                          }}
                        />
                        <span className="mono">{svid}</span>
                      </label>
                    ))}
                    <span className="spacer" />
                    <button type="button" onClick={() => setSelection((previous) => ({ ...previous, [group.key]: group.svids }))}>全选</button>
                    <button type="button" onClick={() => setSelection((previous) => ({ ...previous, [group.key]: [] }))}>全不选</button>
                  </div>
                  {selected.length === 0 ? (
                    <p className="hint">没有勾选参数；点上面的变量或「全选」后显示曲线。</p>
                  ) : (
                    <div className="collector-charts">
                      {group.series
                        .filter((item) => selected.includes(item.svid))
                        .map((item) => (
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
                  )}
                </section>
              );
            })
          )}
        </Panel>
      )}
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
function CollectPlanRows({
  plans,
  onLoad,
  onDelete,
}: {
  plans: readonly CollectPlan[];
  onLoad: (plan: CollectPlan) => void;
  onDelete: (id: string) => void;
}): JSX.Element {
  if (plans.length === 0) {
    return <p className="hint">还没有保存的采集配置。填好下面的参数后点「保存为配置」，运行区就能一键下发。</p>;
  }
  return (
    <div className="plan-list">
      {plans.map((plan) => (
        <div className="plan-row" key={plan.id}>
          <span className="plan-name">{plan.name}</span>
          <span className="plan-meta mono">
            CEID {plan.startCeid}/{plan.stopCeid} · {plan.intervalMs}ms · SVID {plan.svids}
          </span>
          <span className="spacer" />
          <button type="button" onClick={() => onLoad(plan)}>载入</button>
          <button type="button" className="danger" onClick={() => onDelete(plan.id)}>删除</button>
        </div>
      ))}
    </div>
  );
}

/** 数据采集(S1F3 轮询):多份配置 + 启停 CEID 兜底触发 + 可选参数曲线。 */
function HostCollectPanel({ hostId, hosts, readOnly, client, messages = [] }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [draft, setDraft] = useCollectDraft(hostId);
  const [plans, setPlans] = useCollectPlans(hostId);
  const [selection, setSelection] = useCollectSelection(hostId);
  const [planName, setPlanName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [state, setState] = useState<CollectorState | null>(null);
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
    void loadState();
    const timer = window.setInterval(() => void loadState(), 2_000);
    timerRef.current = timer;
    return () => {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hostId, client]);

  const update = (patch: Partial<typeof DEFAULT_COLLECT_DRAFT>) => setDraft((previous) => ({ ...previous, ...patch }));

  const applyConfig = async (override?: CollectPlan | typeof DEFAULT_COLLECT_DRAFT) => {
    const source = override ?? draft;
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
      setNotice('采集配置已下发，等待启动 CEID 触发或点「手动开始」。');
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

  const savePlan = () => {
    const label = planName.trim() || `CEID ${draft.startCeid} · ${draft.intervalMs}ms`;
    setPlans((previous) => [...previous, {
      id: planId(), name: label,
      startCeid: draft.startCeid, stopCeid: draft.stopCeid,
      intervalMs: draft.intervalMs, svids: draft.svids,
      createdAt: Date.now(),
    }]);
    setPlanName('');
  };

  const loadPlan = (plan: CollectPlan) => {
    setDraft({
      startCeid: plan.startCeid, stopCeid: plan.stopCeid,
      intervalMs: plan.intervalMs, svids: plan.svids,
    });
  };

  /* 需求 9 的前端兜底：设备推来启动/停止 CEID 的 S6F11 时，
     即使后端链路侧的判定漏掉，也由界面显式下发启停命令。
     只认本主机视角 + 本次打开之后的新报文，每条只处理一次。 */
  const startCeid = state?.startCeid ?? Number(draft.startCeid);
  const stopCeid = state?.stopCeid ?? Number(draft.stopCeid);
  useEffect(() => {
    if (client === null || !host?.open) {
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
      if (ceid === startCeid && !state?.running) {
        setNotice(`收到启动 CEID ${ceid}，正在启动采集…`);
        void command(true);
      } else if (ceid === stopCeid && state?.running) {
        setNotice(`收到停止 CEID ${ceid}，正在停止采集…`);
        void command(false);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, client, hostId, host?.open, startCeid, stopCeid, state?.running]);

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
  const allSvids = charts.map((chart) => chart.svid);
  const selectedSvids = selection.length === 0 ? allSvids.slice(0, 2) : selection.filter((id) => allSvids.includes(id));

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }

  return (
    <Panel
      id="panel-host-collect"
      title={readOnly ? '采集设置（S1F3 配置库）' : '数据采集（S1F3 轮询）'}
      description="主机侧按周期拉取 SVID,设备推 S6F11 触发启停。可保存多份配置，运行区一键下发；曲线只看勾选的参数。"
    >
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {notice !== '' && <p className="inline-note">{notice}</p>}

      {!readOnly && plans.length > 0 && (
        <div className="plan-chips">
          <span className="hint-inline">一键下发已存配置：</span>
          {plans.map((plan) => (
            <button
              className="plan-chip"
              type="button"
              key={plan.id}
              disabled={busy}
              title={`载入 ${plan.name} 并立即下发采集配置`}
              onClick={() => { loadPlan(plan); void applyConfig(plan); }}
            >
              {plan.name} · CEID {plan.startCeid}
            </button>
          ))}
        </div>
      )}

      <div className="form-row">
        <label className="field">启动 CEID
          <input value={draft.startCeid} onChange={(event) => update({ startCeid: event.target.value })} />
        </label>
        <label className="field">停止 CEID
          <input value={draft.stopCeid} onChange={(event) => update({ stopCeid: event.target.value })} />
        </label>
        <label className="field">周期 ms
          <input value={draft.intervalMs} onChange={(event) => update({ intervalMs: event.target.value })} />
        </label>
      </div>
      <label className="field field-wide">
        采集变量(逗号/空格分隔)
        <input value={draft.svids} onChange={(event) => update({ svids: event.target.value })} />
      </label>

      <div className="form-row" style={{ marginTop: 8 }}>
        <label className="field field-wide">
          配置名称（留空自动生成）
          <input value={planName} onChange={(event) => setPlanName(event.target.value)} placeholder="如：温度+压力 1s" />
        </label>
        <div className="toolbar" style={{ alignItems: 'flex-end' }}>
          <button type="button" onClick={savePlan}>保存为配置</button>
          <button type="button" onClick={() => setDraft({ ...DEFAULT_COLLECT_DRAFT })}>恢复默认</button>
          <button type="button" className="primary" disabled={busy} onClick={() => void applyConfig()}>
            下发配置
          </button>
        </div>
      </div>
      <CollectPlanRows
        plans={plans}
        onLoad={loadPlan}
        onDelete={(id) => setPlans((previous) => previous.filter((plan) => plan.id !== id))}
      />

      <div className="toolbar">
        <button type="button" disabled={busy || !state?.configured || state?.running} onClick={() => void command(true)}>
          手动开始
        </button>
        <button type="button" disabled={busy || !state?.running} onClick={() => void command(false)}>
          手动停止
        </button>
        <span className="hint-inline">
          {state?.running
            ? `采集中 · ${state.samples ?? 0} 点 · 解码错误 ${state.decodeErrors ?? 0}`
            : state?.configured
              ? `已配置 · 等待启动 CEID ${startCeid} 或点「手动开始」`
              : '未配置 · 先点「下发配置」'}
        </span>
      </div>

      <div className="param-picker">
        <span className="hint-inline">曲线要看的参数：</span>
        {allSvids.length === 0 && <span className="hint-inline">还没有采集数据</span>}
        {allSvids.map((svid) => (
          <label className="check-inline" key={svid}>
            <input
              type="checkbox"
              checked={selectedSvids.includes(svid)}
              onChange={(event) => {
                const next = event.target.checked
                  ? [...selectedSvids, svid]
                  : selectedSvids.filter((id) => id !== svid);
                setSelection(next);
              }}
            />
            <span className="mono">{svid}</span>
          </label>
        ))}
        {allSvids.length > 0 && (
          <>
            <span className="spacer" />
            <button type="button" onClick={() => setSelection(allSvids)}>全选</button>
            <button type="button" onClick={() => setSelection([])}>全不选</button>
          </>
        )}
      </div>

      {selectedSvids.length === 0 ? (
        <p className="hint">没有勾选参数；下发配置并开始采集后，勾选要看的 SVID 就会画出曲线。</p>
      ) : (
        <div className="collector-charts">
          {charts.filter((chart) => selectedSvids.includes(chart.svid)).map((chart) => (
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
      )}
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

/** 保留类型引用，避免旧代码把 WireMessage 换成 any 时悄悄漏掉过滤。 */
export type HostPanelMessage = WireMessage;
