import { useMemo, useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { NumberField } from '../../../shared/ui/CommitFields';
import { SmlResult } from '../../../shared/ui/SmlResult';
import { usePersistentState } from '../../../state/usePersistentState';
import { controlStateLabel } from '../../../shared/lib/controlState';
import type { SentMessageResult } from '../../../api';
import type { PanelBaseProps } from '../types';
import type { HostSimCommands } from '../../../state/useHostSims';
import { HostTargetPicker } from './HostTargetPicker';
import { SvidCheckList } from './SvidCheckList';

/** 一个主机的 Trace 配置草稿（不落盘，切换主机时各自保留）。 */
interface FdcDraft {
  trid: number;
  dsper: number;
  totsmp: number;
  repgsz: number;
  /** null = 还没手动改过，默认取前 4 个 SVID */
  svids: number[] | null;
}

const DEFAULT_DRAFT: FdcDraft = { trid: 1, dsper: 1000, totsmp: 100, repgsz: 1, svids: null };

/** 每个主机最近一次发送的结果。 */
interface SendState {
  at: number;
  ok: boolean;
  summary: string;
}

/**
 * ② FDC Trace 数据采集（S2F23 → S6F1）。
 *
 * <p>主机的新建/断开只在「主机实例」页；本页只做"给哪台主机配哪份 Trace"。
 * 每台主机有独立的 TRID / 周期 / 样本 / 变量配置，上方汇总表把它们分开列出，
 * 不会出现"A 的配置发给了 B"。</p>
 */
export function HostSimFdcPanel({
  backend,
  notifier,
  hostSims,
  messages = [],
}: PanelBaseProps & { hostSims: HostSimCommands }): JSX.Element {
  /* 每台主机的 Trace 配置：落 localStorage，切页面/重开界面后"A 的配置还在 A 上" */
  const [drafts, setDrafts] = usePersistentState<Record<string, FdcDraft>>('secs-sim-fdc-drafts', {});
  const [draftText, setDraftText] = useState('');
  const [sending, setSending] = useState(false);
  const [sendStates, setSendStates] = useState<Record<string, SendState>>({});
  const [lastResult, setLastResult] = useState<SentMessageResult | null>(null);
  const [sendError, setSendError] = useState('');

  const host = hostSims.selected;
  const openHosts = hostSims.hosts.filter((item) => item.open);
  const choices = backend.catalog?.svids ?? [];

  const draftOf = (hostId: string): FdcDraft => drafts[hostId] ?? DEFAULT_DRAFT;
  const draft = host === null ? DEFAULT_DRAFT : draftOf(host.id);

  const effectiveSvids = useMemo(
    () => (draft.svids !== null ? draft.svids : choices.slice(0, 4).map((item) => item.svid)),
    [draft.svids, choices],
  );

  const update = (patch: Partial<FdcDraft>) => {
    if (host === null) {
      return;
    }
    setDrafts((previous) => ({ ...previous, [host.id]: { ...draftOf(host.id), ...patch } }));
  };

  const buildS2F23 = (config: FdcDraft, svids: readonly number[]): string => {
    const NL = String.fromCharCode(10);
    const list = svids.map((id) => '      <U4 ' + id + '>').join(NL);
    return 'S2F23 W' + NL
      + '<L' + NL
      + '  <U4 ' + config.trid + '>' + NL
      + '  <A "' + dsperText(config.dsper) + '">' + NL
      + '  <U4 ' + config.totsmp + '>' + NL
      + '  <U4 ' + config.repgsz + '>' + NL
      + '  <L' + NL
      + list + NL
      + '  >' + NL
      + '>.';
  };

  /** 发送某台主机的 S2F23；汇总表的逐行发送也走这里。 */
  const sendFor = async (hostId: string): Promise<void> => {
    const target = hostSims.hosts.find((item) => item.id === hostId);
    if (target === undefined || !target.open) {
      notifier.error('主机未连接');
      return;
    }
    const config = draftOf(hostId);
    const svids = config.svids !== null ? config.svids : choices.slice(0, 4).map((item) => item.svid);
    if (svids.length === 0) {
      notifier.error('没有可采集的变量');
      return;
    }
    setSending(true);
    setSendError('');
    setLastResult(null);
    const sml = buildS2F23(config, svids);
    try {
      const raw = await hostSims.send(hostId, sml);
      const parsed = raw === null ? null : (JSON.parse(raw) as SentMessageResult);
      if (parsed !== null) {
        setLastResult(parsed);
        setSendStates((previous) => ({
          ...previous,
          [hostId]: {
            at: Date.now(),
            ok: true,
            summary: parsed.replySml != null
              ? `S2F23 → S${parsed.replyStream}F${parsed.replyFunction}`
              : parsed.timedOut ? '等待回复超时' : '已发送',
          },
        }));
        notifier.success(
          parsed.replySml != null ? `S2F23 → S${parsed.replyStream}F${parsed.replyFunction}` : 'S2F23 已发送',
          `主机 ${hostId} · 耗时 ${parsed.elapsedMs} ms`,
        );
        const state = controlStateOf(target);
        if (state !== 'ONLINE' && state !== 'REMOTE') {
          notifier.warn(
            'S2F23 已被接受，但设备还没 ONLINE，S6F1 暂时不会上报',
            `当前 ${controlStateLabel(state)}；先发送 S1F17 上线（上方提供一键按钮）`,
          );
        }
      }
    } catch (error) {
      setSendError((error as Error).message);
      setSendStates((previous) => ({
        ...previous,
        [hostId]: { at: Date.now(), ok: false, summary: '发送失败' },
      }));
      notifier.error('发送 S2F23 失败', (error as Error).message);
    } finally {
      setSending(false);
    }
  };

  const s6f1Count = (sessionId: string): number => messages.filter(
    (message) => message.sessionId === sessionId && message.stream === 6 && message.function === 1,
  ).length;

  /** 本机主机的设备控制状态：S6F1 只在 ONLINE 后上报（E30 主动上报门槛）。 */
  const controlStateOf = (hostItem: { session: string; remote: boolean }): string => {
    if (hostItem.remote) {
      return 'REMOTE';
    }
    return backend.sessions.find((session) => session.id === hostItem.session)?.controlState ?? 'OFFLINE';
  };

  /** 一键上线：COMMUNICATING 时发 S1F17，让 Trace 真正开始上报。 */
  const bringOnline = async (hostId: string): Promise<void> => {
    try {
      await hostSims.send(hostId, 'S1F17 W.');
      notifier.success('已发送 S1F17，设备进入 ONLINE');
    } catch (error) {
      notifier.error('发送 S1F17 失败', (error as Error).message);
    }
  };

  /** 一键下线：发 S1F15，设备回 S1F16 后停止主动上报（S6F1 也会停）。 */
  const takeOffline = async (hostId: string): Promise<void> => {
    try {
      await hostSims.send(hostId, 'S1F15 W.');
      notifier.success('已发送 S1F15，设备转为 OFFLINE');
    } catch (error) {
      notifier.error('发送 S1F15 失败', (error as Error).message);
    }
  };

  return (
    <Panel
      id="panel-hostsim-fdc"
      title="② S6F1 采集（S2F23 → S6F1）"
      description="设备按 DSPER 采样、REPGSZ 分条上报 S6F1。每台主机有独立的采集配置，上方汇总分开展示。"
    >
      <HostTargetPicker backend={backend} notifier={notifier} hostSims={hostSims} allowCreate={false} />

      {openHosts.length === 0 ? (
        <p className="hint">还没有已连接的主机：先到「主机实例」页新建一个。</p>
      ) : (
        <>
          <div className="effects-table-wrap">
            <table className="effects-table">
              <thead>
                <tr>
                  <th>主机</th>
                  <th>目标</th>
                  <th>控制</th>
                  <th>TRID</th>
                  <th>采样周期</th>
                  <th>样本 / 分组</th>
                  <th>采集变量</th>
                  <th>S6F1 帧</th>
                  <th>最近发送</th>
                  <th aria-label="操作" />
                </tr>
              </thead>
              <tbody>
                {openHosts.map((item) => {
                  const config = draftOf(item.id);
                  const svids = config.svids !== null
                    ? config.svids
                    : choices.slice(0, 4).map((choice) => choice.svid);
                  const sent = sendStates[item.id];
                  const control = controlStateOf(item);
                  return (
                    <tr key={item.id}>
                      <td className="mono">{item.id}</td>
                      <td className="mono">{item.remote ? item.target : item.session}</td>
                      <td>
                        <span className={`badge ${control === 'ONLINE' ? 'tone-ok' : control === 'COMMUNICATING' ? 'tone-warn' : control === 'REMOTE' ? 'tone-info' : 'tone-muted'}`}>
                          {control === 'REMOTE' ? '远程' : controlStateLabel(control)}
                        </span>
                      </td>
                      <td>{config.trid}</td>
                      <td>{config.dsper} ms</td>
                      <td>{config.totsmp} / {config.repgsz}</td>
                      <td>{svids.length} 个</td>
                      <td>{item.remote ? '—' : s6f1Count(item.session)}</td>
                      <td className="hint-inline">
                        {sent === undefined ? '—' : `${new Date(sent.at).toLocaleTimeString()} · ${sent.summary}`}
                      </td>
                      <td>
                        <div className="row-actions">
                          <button type="button" onClick={() => hostSims.select(item.id)}>选用</button>
                          {control !== 'ONLINE' && control !== 'REMOTE' && (
                            <button type="button" onClick={() => void bringOnline(item.id)} title="发送 S1F17，让设备进入 ONLINE 后 S6F1 才会真正上报">
                              上线
                            </button>
                          )}
                          {control === 'ONLINE' && (
                            <button type="button" onClick={() => void takeOffline(item.id)} title="发送 S1F15，设备转为 OFFLINE（S6F1 停止上报）">
                              下线
                            </button>
                          )}
                          <button
                            type="button"
                            disabled={sending}
                            onClick={() => void sendFor(item.id)}
                            title="用该主机自己的配置发送 S2F23"
                          >
                            发送
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {host === null || !host.open ? (
            <p className="hint">选一台主机来编辑它的 Trace 配置。</p>
          ) : (
            <>
              <div className="section-head">
                <h3 style={{ margin: 0 }}>编辑 {host.id} 的 Trace 配置</h3>
                <span className="hint-inline">{host.remote ? `远程 ${host.target}` : host.session}</span>
              </div>
              {!host.remote && controlStateOf(host) !== 'ONLINE' && (
                <p className="problems">
                  设备当前是「{controlStateLabel(controlStateOf(host))}」：S2F23 会正常回 S2F24，
                  但 <b>S6F1 只在 ONLINE 后才上报</b>，此刻下发不会看到帧。
                  <button type="button" onClick={() => void bringOnline(host.id)}>
                    发送 S1F17 上线
                  </button>
                </p>
              )}
              <p className="hint">
                首帧预计约 {Math.max(1, Math.round(draft.dsper / 1000) * Math.max(1, draft.repgsz))} 秒
                （REPGSZ × DSPER）；想更快看到上报，可把「每条样本数」调到 1。
              </p>
              <div className="form-row">
                <label className="field">
                  TRID
                  <NumberField value={draft.trid} integer onCommit={(value) => update({ trid: value })} />
                </label>
                <label className="field">
                  采样周期 ms
                  <NumberField value={draft.dsper} integer onCommit={(value) => update({ dsper: value })} title="DSPER（hhmmss 六位数字）" />
                </label>
                <label className="field">
                  总样本数
                  <NumberField value={draft.totsmp} integer onCommit={(value) => update({ totsmp: value })} title="TOTSMP·0=终止" />
                </label>
                <label className="field">
                  每条样本数
                  <NumberField value={draft.repgsz} integer onCommit={(value) => update({ repgsz: value })} title="REPGSZ：每条 S6F1 的样本数" />
                </label>
              </div>

              <div className="field-block">
                <span className="template-label">采集变量（最多 10 个）</span>
                <SvidCheckList
                  choices={choices}
                  selected={effectiveSvids}
                  onChange={(next) => update({ svids: next })}
                  max={10}
                />
              </div>

              <div className="editor-foot">
                <span className="hint">
                  DSPER 是 hhmmss 六位数字（整秒）：周期 ms 会收敛到最近的整秒、最小 1 秒。
                  TOTSMP=0 用于提前终止该 TRID。配置只属于当前主机。
                </span>
                <button
                  type="button"
                  className="primary"
                  disabled={effectiveSvids.length === 0 || sending}
                  onClick={() => void sendFor(host.id)}
                  title="按当前参数生成 S2F23 并以当前主机发出（W 位等 S2F24）"
                >
                  {sending ? '发送中…' : '生成并发送 S2F23'}
                </button>
                <button
                  type="button"
                  disabled={effectiveSvids.length === 0}
                  onClick={() => setDraftText(buildS2F23(draft, effectiveSvids))}
                  title="只生成 S2F23 文本，便于检查或复制"
                >
                  生成 S2F23
                </button>
              </div>

              {draftText !== '' && (
                <>
                  <div className="section-head">
                    <h3>生成的 S2F23</h3>
                    <button type="button" className="link" onClick={() => setDraftText('')}>收起</button>
                  </div>
                  <pre className="sml-result">{draftText}</pre>
                </>
              )}
              {lastResult !== null && (
                <SmlResult
                  header={`发送 ${host.id}  S${lastResult.stream}F${lastResult.function}${lastResult.wbit ? ' W' : ''}`}
                  result={lastResult}
                  peerLabel="设备"
                />
              )}
              {sendError !== '' && <pre className="sml-result error-result">发送失败：{sendError}</pre>}
            </>
          )}
        </>
      )}
    </Panel>
  );
}

/** DSPER 文本：ms 转 hhmmss。 */
function dsperText(ms: number): string {
  const total = Math.max(1, Math.round(ms / 1000));
  const hh = String(Math.floor(total / 3600)).padStart(2, '0');
  const mm = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
  const ss = String(total % 60).padStart(2, '0');
  return hh + mm + ss;
}
