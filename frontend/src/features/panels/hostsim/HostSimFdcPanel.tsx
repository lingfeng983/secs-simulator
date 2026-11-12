import { useEffect, useMemo, useState } from 'react';
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

/** 一个主机的 Trace 配置草稿（落 localStorage，切换主机时各自保留）。 */
interface FdcDraft {
  trid: number;
  dsper: number;
  totsmp: number;
  repgsz: number;
  /** null = 还没手动改过，默认取前 4 个 SVID（仅本机设备） */
  svids: number[] | null;
  /** 远程主机：整条 S2F23 手填（没有本地 catalog，SVID 只能是自由输入） */
  manualSml: string;
}

const DEFAULT_DRAFT: FdcDraft = {
  trid: 1,
  dsper: 1000,
  totsmp: 100,
  repgsz: 1,
  svids: null,
  manualSml: '',
};

/** 远程主机的默认报文骨架：TRID / DSPER / TOTSMP / REPGSZ / SVID 列表。 */
const MANUAL_TEMPLATE = [
  'S2F23 W',
  '<L',
  '  <U4 1>',
  '  <A "000001">',
  '  <U4 100>',
  '  <U4 1>',
  '  <L',
  '    <U4 1001>',
  '    <U4 1003>',
  '  >',
  '>.',
].join('\n');

/** 每个主机最近一次发送的结果。 */
interface SendState {
  at: number;
  ok: boolean;
  summary: string;
}

/**
 * ② FDC Trace 数据采集（S2F23 → S6F1）。
 *
 * <p>配置入口按主机类型分流，因为两类主机能"知道"的东西不一样：</p>
 * <ul>
 *   <li><b>本机设备</b>：有完整 catalog，用表单（TRID / 周期 / 样本 / 变量多选），
 *       变量不限数量——上限交给设备自己的 {@code session.trace} 配置，页面不再硬截断；</li>
 *   <li><b>远程 SECS</b>：对端不在本库，SVID 与格式都无从得知，只能手填整条 S2F23。</li>
 * </ul>
 *
 * <p>当前操作哪台主机由顶部选择条决定，不再用一张汇总表重复列一遍。</p>
 */
export function HostSimFdcPanel({
  backend,
  notifier,
  hostSims,
}: PanelBaseProps & { hostSims: HostSimCommands }): JSX.Element {
  /* 每台主机的 Trace 配置：落 localStorage，切页面/重开界面后"A 的配置还在 A 上" */
  const [drafts, setDrafts] = usePersistentState<Record<string, FdcDraft>>('secs-sim-fdc-drafts', {});
  const [draftText, setDraftText] = useState('');
  const [sending, setSending] = useState(false);
  const [lastResult, setLastResult] = useState<SentMessageResult | null>(null);
  const [sendError, setSendError] = useState('');

  const host = hostSims.selected;
  const openHosts = hostSims.hosts.filter((item) => item.open);
  const choices = backend.catalog?.svids ?? [];

  const draftOf = (hostId: string): FdcDraft => drafts[hostId] ?? DEFAULT_DRAFT;
  const draft = host === null ? DEFAULT_DRAFT : draftOf(host.id);

  /* 切主机时把上一台的结果清掉：否则会把 A 的回复挂在 B 的配置下面 */
  useEffect(() => {
    setLastResult(null);
    setSendError('');
    setDraftText('');
  }, [host?.id]);

  /** 远程主机没有 catalog：报文完全手填，空串时给骨架。 */
  const manualText = draft.manualSml === '' ? MANUAL_TEMPLATE : draft.manualSml;

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

  /** 当前主机要发出去的 SML：远程手填，本机按表单生成。 */
  const pendingSml = (): string => {
    if (host === null) {
      return '';
    }
    if (host.remote) {
      return manualText;
    }
    return buildS2F23(draft, effectiveSvids);
  };

  const canSend = host !== null && host.open
    && (host.remote ? manualText.trim() !== '' : effectiveSvids.length > 0);

  /** 发送 S2F23（当前主机）。 */
  const send = async (): Promise<void> => {
    if (host === null) {
      return;
    }
    if (!host.open) {
      notifier.error('主机未连接');
      return;
    }
    if (!host.remote && effectiveSvids.length === 0) {
      notifier.error('没有可采集的变量');
      return;
    }
    setSending(true);
    setSendError('');
    setLastResult(null);
    const sml = pendingSml();
    try {
      const raw = await hostSims.send(host.id, sml);
      const parsed = raw === null ? null : (JSON.parse(raw) as SentMessageResult);
      if (parsed !== null) {
        setLastResult(parsed);
        notifier.success(
          parsed.replySml != null ? `S2F23 → S${parsed.replyStream}F${parsed.replyFunction}` : 'S2F23 已发送',
          `主机 ${host.id} · 耗时 ${parsed.elapsedMs} ms`,
        );
        if (!host.remote) {
          const state = controlStateOf(host);
          if (state !== 'ONLINE' && state !== 'REMOTE') {
            notifier.warn(
              'S2F23 已被接受，但设备还没 ONLINE，S6F1 暂时不会上报',
              `当前 ${controlStateLabel(state)}；先发送 S1F17 上线（上方提供一键按钮）`,
            );
          }
        }
      }
    } catch (error) {
      setSendError((error as Error).message);
      notifier.error('发送 S2F23 失败', (error as Error).message);
    } finally {
      setSending(false);
    }
  };

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

  const control = host === null ? '' : controlStateOf(host);

  return (
    <Panel
      id="panel-hostsim-fdc"
      title="② S6F1 采集（S2F23 → S6F1）"
      description="设备按 DSPER 采样、REPGSZ 分条上报 S6F1。当前主机在顶部选择；本机设备用表单配置，远程主机手填 S2F23。"
    >
      <HostTargetPicker backend={backend} notifier={notifier} hostSims={hostSims} allowCreate={false} />

      {openHosts.length === 0 ? (
        <p className="hint">还没有已连接的主机：先到「主机实例」页新建一个。</p>
      ) : host === null || !host.open ? (
        <p className="hint">选一台已连接的主机来配置它的 Trace。</p>
      ) : (
        <>
          <div className="section-head">
            <h3 style={{ margin: 0 }}>编辑 {host.id} 的 Trace 配置</h3>
            <span className={`badge ${host.remote ? 'tone-info' : control === 'ONLINE' ? 'tone-ok' : control === 'COMMUNICATING' ? 'tone-warn' : 'tone-muted'}`}>
              {host.remote ? `远程 ${host.target}` : `${host.session} · ${controlStateLabel(control)}`}
            </span>
            <span className="spacer" />
            {!host.remote && control !== 'ONLINE' && (
              <button type="button" onClick={() => void bringOnline(host.id)} title="发送 S1F17，让设备进入 ONLINE 后 S6F1 才会真正上报">
                发送 S1F17 上线
              </button>
            )}
            {!host.remote && control === 'ONLINE' && (
              <button type="button" onClick={() => void takeOffline(host.id)} title="发送 S1F15，设备转为 OFFLINE（S6F1 停止上报）">
                发送 S1F15 下线
              </button>
            )}
          </div>

          {host.remote ? (
            <>
              <p className="hint">
                远程主机没有本地 catalog：TRID / DSPER / TOTSMP / REPGSZ 与 SVID 列表都在报文里手填，
                设备只按 S2F24 回 TIAACK。改完直接发送；解析错误会在「发送失败」里给出位置。
              </p>
              <textarea
                className="sml-editor sml-editor-tall"
                spellCheck={false}
                value={manualText}
                onChange={(event) => update({ manualSml: event.target.value })}
                aria-label="远程主机的 S2F23 报文"
              />
            </>
          ) : (
            <>
              {control !== 'ONLINE' && (
                <p className="problems">
                  设备当前是「{controlStateLabel(control)}」：S2F23 会正常回 S2F24，
                  但 <b>S6F1 只在 ONLINE 后才上报</b>，此刻下发不会看到帧。
                  点右上角「发送 S1F17 上线」即可。
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
                <span className="template-label">
                  采集变量（不限数量，已选 {effectiveSvids.length} 个）
                </span>
                <SvidCheckList
                  choices={choices}
                  selected={effectiveSvids}
                  onChange={(next) => update({ svids: next })}
                />
                <p className="hint">
                  上限由设备自己的 <code>session.trace.maxSvidCount</code> 决定（默认 10）：
                  勾多了 S2F24 会回 TIAACK=1，设备侧不会采。
                </p>
              </div>
            </>
          )}

          <div className="editor-foot">
            <span className="hint">
              {host.remote
                ? 'DSPER 是 hhmmss 六位数字（整秒）。TOTSMP=0 用于提前终止该 TRID。配置只属于当前主机。'
                : 'DSPER 是 hhmmss 六位数字（整秒）：周期 ms 会收敛到最近的整秒、最小 1 秒。TOTSMP=0 用于提前终止该 TRID。配置只属于当前主机。'}
            </span>
            <button
              type="button"
              className="primary"
              disabled={!canSend || sending}
              onClick={() => void send()}
              title="按当前配置生成 S2F23 并以当前主机发出（W 位等 S2F24）"
            >
              {sending ? '发送中…' : '生成并发送 S2F23'}
            </button>
            <button
              type="button"
              disabled={!canSend}
              onClick={() => setDraftText(pendingSml())}
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
