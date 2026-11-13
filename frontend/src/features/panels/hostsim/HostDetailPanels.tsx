import { useEffect, useMemo, useRef, useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { DataTable } from '../../../shared/ui/DataTable';
import { NumberField } from '../../../shared/ui/CommitFields';
import { SmlResult } from '../../../shared/ui/SmlResult';
import { VariableChart } from '../../charts/VariableChart';
import type { PanelBaseProps } from '../types';
import type { HostPage, HostZone } from '../../../app/router';
import { HOST_PRESETS, PRESET_GROUPS } from './hostPresets';
import type { HostsV2State } from '../../../state/useHostsV2';
import type { ApiClient, PreviewSeries, SentMessageResult } from '../../../api';

/** 收发记录一次最多挂多少行 DOM（缓冲上限是 5000，全量渲染会卡住页面）。 */
const HOST_MONITOR_ROWS = 500;

/**
 * 主机详情子页分发（设计 §3.4.2 归位表）。
 *
 * <p>配置区 5 页 + 运行区 6 页。运行区的发送/压测/采集/记录全部
 * <b>只作用于本主机</b>（第三轮修订 13）；收发记录只看本主机并带「清理」。</p>
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
  /** 由页面经 PanelBaseProps.backend.client 正常传入，不做模块级注入。 */
  client: ApiClient | null;
  /** 报文缓冲（App 的 SSE 合流），收发记录页只用本主机的那部分。 */
  messages?: PanelBaseProps['messages'];
}): JSX.Element {
  const base = { hosts, goTo, hostId, client };
  if (zone === 'config') {
    switch (page) {
      case 'overview':
        return <HostOverviewPanel {...base} />;
      case 'connection':
        return <HostConnectionPanel {...base} />;
      case 'presets':
        return <HostPresetsPanel />;
      case 'trace':
        return <HostTracePanel {...base} readOnly />;
      case 'collect':
        return <HostCollectPanel {...base} />;
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
    case 'monitor':
      return <HostMonitorPanel {...base} messages={messages} />;
    case 'manual':
      return <HostManualPanel {...base} />;
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
};

/* ---------- 配置区 ---------- */

/** 概览:名称/目标编辑 + 保存(设置版本 +1,需「使设置生效」)。 */
function HostOverviewPanel({ hostId, hosts, readOnly }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [name, setName] = useState('');
  const [host_, setHost_] = useState('');
  const [port, setPort] = useState('5000');
  const [deviceId, setDeviceId] = useState('0');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [loadedFor, setLoadedFor] = useState('');

  useEffect(() => {
    if (host !== null && loadedFor !== host.id) {
      setLoadedFor(host.id);
      setName(host.name);
      setHost_(host.target.split(':')[0] ?? '');
      setPort(String(host.port));
      setDeviceId(String(host.deviceId));
    }
  }, [host, loadedFor]);

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  const dirty = name !== host.name
    || host_ !== host.target.split(':')[0]
    || Number(port) !== host.port
    || Number(deviceId) !== host.deviceId;

  return (
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
  );
}

/** 连接:目标表单 + 连接/断开动作(与概览同一份设置)。 */
function HostConnectionPanel(args: PanelArgs): JSX.Element {
  return (
    <>
      <HostOverviewPanel {...args} />
      <HostConnectionActions {...args} />
    </>
  );
}

function HostConnectionActions({ hostId, hosts }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  const act = (action: () => Promise<void>) => {
    setBusy(true);
    setError('');
    action().catch((e: Error) => setError(e.message)).finally(() => setBusy(false));
  };
  return (
    <Panel id="panel-host-connect" title="连接动作" description="断开只关连接,实例与设置保留;连接失败默认停下。">
      {error !== '' && <p className="inline-note danger">{error}</p>}
      <div className="toolbar">
        {host.open ? (
          <button type="button" disabled={busy} onClick={() => act(() => hosts.disconnect(hostId))}>
            断开连接
          </button>
        ) : (
          <button type="button" className="primary" disabled={busy}
            onClick={() => act(() => hosts.connect(hostId))}>
            {host.phase === 'FAILED' ? '重新连接' : '连接'}
          </button>
        )}
        <button type="button" disabled={busy}
          onClick={() => act(() => hosts.apply(hostId))}
          title="按最新设置重连并恢复采集">
          使设置生效
        </button>
        <span className="hint-inline">
          相位:{host.phase}{host.autoReconnect ? ` · 已重试 ${host.retryCount} 次` : ''}
        </span>
      </div>
    </Panel>
  );
}

/** 报文模板:16 条预设浏览(发送在运行区·消息发送)。 */
function HostPresetsPanel(): JSX.Element {
  return (
    <Panel
      id="panel-host-presets"
      title="报文模板"
      description={`主机方向常用报文 ${HOST_PRESETS.length} 条,分 ${PRESET_GROUPS.length} 组;发送入口在运行区·消息发送。`}
    >
      <div className="preset-guide-grid">
        {PRESET_GROUPS.map((group) => (
          <section key={group.label}>
            <h4>{group.label}</h4>
            <dl>
              {group.items.map((item) => (
                <div className="preset-guide-item" key={item.label}>
                  <dt>{item.label}</dt>
                </div>
              ))}
            </dl>
          </section>
        ))}
      </div>
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
          <div className="kpi-value">{host.phase}</div>
          <div className="kpi-label">链路相位</div>
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
      <p className="hint">断开只关连接;重新连接后按设置恢复,采集会自动恢复。</p>
    </section>
  );
}

/** 消息发送(单主机):预设 + SML 编辑 + 发送 + 压测。 */
function HostSendPanel({ hostId, hosts }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [draft, setDraft] = useState('S1F13 W');
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
      description="以本主机身份发一条 SML;收发明细在「收发记录」。其它主机请在各自详情页操作。"
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

/** Trace 采集(S2F23 → S6F1):表单生成 + 发送。 */
function HostTracePanel({ hostId, hosts, readOnly, client }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [trid, setTrid] = useState(1);
  const [dsper, setDsper] = useState(1000);
  const [totsmp, setTotsmp] = useState(100);
  const [repgsz, setRepgsz] = useState(1);
  const [svids, setSvids] = useState('1001, 1003');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  const [error, setError] = useState('');
  const [draft, setDraft] = useState('');

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  const buildS2f23 = (): string => {
    const NL = String.fromCharCode(10);
    const list = svids.split(/[\s,;]+/).filter((piece) => piece !== '')
      .map((piece) => '      <U4 ' + piece + '>')
      .join(NL);
    const total = Math.max(1, Math.round(dsper / 1000));
    const hh = String(Math.floor(total / 3600)).padStart(2, '0');
    const mm = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
    const ss = String(total % 60).padStart(2, '0');
    return 'S2F23 W' + NL
      + '<L' + NL
      + '  <U4 ' + trid + '>' + NL
      + '  <A "' + hh + mm + ss + '">' + NL
      + '  <U4 ' + totsmp + '>' + NL
      + '  <U4 ' + repgsz + '>' + NL
      + '  <L' + NL
      + list + NL
      + '  >' + NL
      + '>.';
  };
  const send = async () => {
    setBusy(true);
    setError('');
    try {
      setResult(await requireClient(client).sendHostV2(hostId, buildS2f23()));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel
      id="panel-host-trace"
      title={readOnly ? 'Trace 设置' : 'Trace 采集（S2F23 → S6F1）'}
      description="设备按 DSPER 采样、REPGSZ 分条上报 S6F1;DSPER 是 hhmmss 六位数字(整秒)。配置只属于本主机。"
    >
      {error !== '' && <p className="inline-note danger">{error}</p>}
      {host.open && !readOnly && host.phase !== 'CONNECTED' && (
        <p className="problems">主机还没连接,先到实例头点「连接」。</p>
      )}
      <div className="form-row">
        <label className="field">TRID
          <NumberField value={trid} integer onCommit={setTrid} />
        </label>
        <label className="field">采样周期 ms
          <NumberField value={dsper} integer onCommit={setDsper} />
        </label>
        <label className="field">总样本数
          <NumberField value={totsmp} integer onCommit={setTotsmp} />
        </label>
        <label className="field">每条样本数
          <NumberField value={repgsz} integer onCommit={setRepgsz} />
        </label>
      </div>
      <label className="field field-wide">
        采集变量(逗号/空格分隔)
        <input value={svids} onChange={(event) => setSvids(event.target.value)} />
      </label>
      <div className="editor-foot">
        <span className="hint">TOTSMP=0 可提前终止该 TRID。</span>
        <button type="button" onClick={() => setDraft(buildS2f23())}>生成 S2F23</button>
        {!readOnly && (
          <button type="button" className="primary" disabled={busy || !host.open}
            onClick={() => void send()}>
            {busy ? '发送中…' : '生成并发送'}
          </button>
        )}
      </div>
      {draft !== '' && <pre className="sml-result">{draft}</pre>}
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

/** 数据采集(S1F3 轮询):配置下发 + 手动启停 + 曲线。 */
function HostCollectPanel({ hostId, hosts, client }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const realClient = client;
  const [startCeid, setStartCeid] = useState('2001');
  const [stopCeid, setStopCeid] = useState('2002');
  const [intervalMs, setIntervalMs] = useState('1000');
  const [svids, setSvids] = useState('1001, 1003');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [state, setState] = useState<{
    configured?: boolean; running?: boolean; samples?: number; decodeErrors?: number;
    series?: { svid: number; name?: string; unit?: string; t: number[]; v: number[] }[];
  } | null>(null);
  const timerRef = useRef<number | null>(null);

  const loadState = async () => {
    if (realClient === null) {
      return;
    }
    try {
      const body = await realClient.hostInstanceCollector(hostId);
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
  }, [hostId, realClient]);

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }

  const apply = async () => {
    setBusy(true);
    setError('');
    try {
      const parsed = svids.split(/[\s,;]+/).filter((piece) => piece !== '').map(Number)
        .filter((value) => Number.isInteger(value) && value >= 1);
      await requireClient(realClient).setHostInstanceCollector(hostId, {
        startCeid: Number(startCeid) || 0,
        stopCeid: Number(stopCeid) || 0,
        intervalMs: Number(intervalMs) || 1000,
        svids: parsed.map((id) => ({ id })),
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
      await requireClient(realClient).hostInstanceCollectorCommand(hostId, start);
      await loadState();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /* 采集样本是逐点值(min=max=avg);时间轴用样本自带的 epoch 毫秒,timeOriginMs 保持 0。 */
  const charts = (state?.series ?? []).map((item) => {
    const times = item.t ?? [];
    const from = times.length > 0 ? times[0] : 0;
    const to = times.length > 1 ? times[times.length - 1] : from + 1000;
    const chartSeries: PreviewSeries = {
      svid: item.svid,
      owner: item.name ?? 'CH1',
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

  return (
    <Panel
      id="panel-host-collect"
      title="数据采集（S1F3 轮询）"
      description="主机侧按周期拉取 SVID,设备推 S6F11 触发启停;采集配置属于本主机。"
    >
      {error !== '' && <p className="inline-note danger">{error}</p>}
      <div className="form-row">
        <label className="field">启动 CEID
          <input value={startCeid} onChange={(event) => setStartCeid(event.target.value)} />
        </label>
        <label className="field">停止 CEID
          <input value={stopCeid} onChange={(event) => setStopCeid(event.target.value)} />
        </label>
        <label className="field">周期 ms
          <input value={intervalMs} onChange={(event) => setIntervalMs(event.target.value)} />
        </label>
      </div>
      <label className="field field-wide">
        采集变量(逗号/空格分隔)
        <input value={svids} onChange={(event) => setSvids(event.target.value)} />
      </label>
      <div className="toolbar">
        <button type="button" className="primary" disabled={busy} onClick={() => void apply()}>
          下发配置
        </button>
        <button type="button" disabled={busy || !state?.configured || state?.running}
          onClick={() => void command(true)}>
          手动开始
        </button>
        <button type="button" disabled={busy || !state?.running}
          onClick={() => void command(false)}>
          手动停止
        </button>
        <span className="hint-inline">
          {state?.running ? `采集中 · ${state.samples ?? 0} 点 · 解码错误 ${state.decodeErrors ?? 0}` : '未采集'}
        </span>
      </div>
      {charts.length === 0 ? (
        <p className="hint">还没有采集数据;下发配置并开始后,这里会画出每个变量的曲线。</p>
      ) : (
        <div className="collector-charts">
          {charts.map((chart) => (
            <div className="collector-chart collector-chart-large" key={chart.svid}>
              <div className="collector-chart-head">
                <span className="mono">{chart.svid}</span>
                <span>{chart.series.owner}</span>
                {(chart.series.unit ?? '') !== '' && (
                  <span className="hint-inline">{chart.series.unit}</span>
                )}
                <span className="spacer" />
                <span className="hint-inline">
                  {chart.series.avg.length} 点 · 最近{' '}
                  {chart.series.avg.length > 0
                    ? chart.series.avg[chart.series.avg.length - 1].toFixed(2)
                    : '—'}
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

/** 收发记录(只看本主机 + 过滤 + 清理,第三轮修订 13)。 */
function HostMonitorPanel({ hostId, hosts, messages = [] }: PanelArgs & { messages?: PanelBaseProps['messages'] }): JSX.Element {
  const host = hosts.find(hostId);
  const [direction, setDirection] = useState<'all' | 'sent' | 'received'>('all');
  const [sf, setSf] = useState('');
  const [keyword, setKeyword] = useState('');
  const [clearedSeq, setClearedSeq] = useState(-1);

  /* 全量缓冲里只留本主机 + host 视角的报文 */
  const mine = useMemo(
    () => messages.filter((message) => message.hostId === hostId && message.perspective !== 'device'),
    [messages, hostId],
  );
  const maxSeq = mine.length > 0 ? Math.max(...mine.map((message) => message.seq ?? 0)) : -1;

  const filtered = mine.filter((message) => {
    if ((message.seq ?? 0) <= clearedSeq) {
      return false;
    }
    if (direction === 'sent' && message.direction !== 'in') {
      return false;
    }
    if (direction === 'received' && message.direction !== 'out') {
      return false;
    }
    const sfKey = sf.trim().toLowerCase().replace(/\s+/g, '');
    if (sfKey !== '' && !`s${message.stream}f${message.function}`.startsWith(sfKey)) {
      return false;
    }
    if (keyword.trim() !== '' && !message.body.toLowerCase().includes(keyword.trim().toLowerCase())) {
      return false;
    }
    return true;
  });

  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }

  /* 只渲染最近 500 行。缓冲是 5000 条，一次压测就能把它填满：全量挂 DOM
     会让报文还在滚的时候页面直接卡住（滚动、输入框都在掉帧）。过滤与计数
     仍按全量算，只是"看得见的那部分"有上限，和「消息」面板的做法一致。 */
  const visible = filtered.length > HOST_MONITOR_ROWS
    ? filtered.slice(-HOST_MONITOR_ROWS)
    : filtered;

  return (
    <Panel
      id="panel-host-monitor"
      title={`收发记录 · ${host.id}`}
      description="只显示本主机的收发(方向/SxFy/内容过滤);「清理」打水位,清理后只显示新报文。其它主机在各自详情页查看。"
      actions={
        <button
          type="button"
          disabled={maxSeq < 0}
          onClick={() => setClearedSeq(maxSeq)}
          title="清掉当前显示的报文,之后只显示新收发的"
        >
          清理
        </button>
      }
    >
      <div className="toolbar monitor-filters">
        <label className="field">
          方向
          <select value={direction} onChange={(event) => setDirection(event.target.value as typeof direction)}
            aria-label="按方向过滤">
            <option value="all">收发都看</option>
            <option value="sent">只看主机发出</option>
            <option value="received">只看设备发来</option>
          </select>
        </label>
        <label className="field">
          SxFy
          <input value={sf} onChange={(event) => setSf(event.target.value)} placeholder="如 S6F1"
            aria-label="按 SxFy 过滤" />
        </label>
        <label className="field field-wide">
          内容
          <input value={keyword} onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜报文体里的 ID / 文本" aria-label="按报文内容过滤" />
        </label>
        <span className="spacer" />
        <button type="button" onClick={() => { setDirection('all'); setSf(''); setKeyword(''); }}>
          清空过滤
        </button>
      </div>

      <div className="effects-table-wrap">
        <table className="effects-table">
          <thead>
            <tr><th>时间</th><th>方向</th><th>SxFy</th><th>报文体</th></tr>
          </thead>
          <tbody>
            {visible.slice().reverse().map((message, index) => (
              <tr key={`${message.seq ?? index}`}>
                <td className="mono">{new Date(message.at).toLocaleTimeString()}</td>
                <td>{message.direction === 'in' ? '发 → 设备' : '收 ← 设备'}</td>
                <td className="mono">S{message.stream}F{message.function}{message.wbit ? ' W' : ''}</td>
                <td className="mono">{message.body.length > 120 ? message.body.slice(0, 120) + '…' : message.body}</td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr><td colSpan={4} className="hint-inline">当前条件下没有报文;放宽条件或等新报文进来。</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="hint">
        显示 {visible.length} / 合计 {mine.length} 条(仅本主机;界面缓冲最近 5000 条
        {visible.length < filtered.length ? `，为流畅只渲染最近 ${HOST_MONITOR_ROWS} 条` : ''})。
      </p>
    </Panel>
  );
}

/** 手动操作:连接/断开/使设置生效 + 快捷命令。 */
function HostManualPanel({ hostId, hosts, client }: PanelArgs): JSX.Element {
  const host = hosts.find(hostId);
  const [draft, setDraft] = useState('S1F3 W\n<L\n  <U4 1001>\n  <U4 1003>\n>.');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  if (host === null) {
    return <p className="hint">主机不存在。</p>;
  }
  const send = async () => {
    setBusy(true);
    try {
      setResult(await requireClient(client).sendHostV2(hostId, draft));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Panel
      id="panel-host-manual"
      title="手动操作"
      description="连接 / 断开 / 使设置生效,以及一次性手发 SML(不进周期采集)。"
    >
      <div className="toolbar">
        {host.open ? (
          <button type="button" disabled={busy}
            onClick={() => void hosts.disconnect(hostId).catch(() => undefined)}>
            断开连接
          </button>
        ) : (
          <button type="button" className="primary" disabled={busy}
            onClick={() => void hosts.connect(hostId).catch(() => undefined)}>
            {host.phase === 'FAILED' ? '重新连接' : '连接'}
          </button>
        )}
        <button type="button" disabled={busy}
          onClick={() => void hosts.apply(hostId).catch(() => undefined)}>
          按最新设置重连
        </button>
      </div>
      <textarea
        className="sml-editor"
        spellCheck={false}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        aria-label="手动 SML 报文内容"
      />
      <div className="editor-foot">
        <span className="hint">整报文 · 一次性发送,用来确认对端在不在。</span>
        <button type="button" className="primary" disabled={busy || !host.open}
          onClick={() => void send()}>
          发送
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

/** 需要直接发报文的子页共用：后端未就绪时在异步 send 里抛错，由各面板转成行内提示。 */
function requireClient(client: ApiClient | null): ApiClient {
  if (client === null) {
    throw new Error('后端未连接');
  }
  return client;
}
