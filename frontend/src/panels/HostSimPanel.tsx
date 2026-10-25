import { useEffect, useState } from 'react';
import type { SentMessageResult } from '../api';
import { Panel } from '../ui/Panel';
import { NumberField } from '../ui/CommitFields';
import { PresetSelect } from '../ui/PresetSelect';
import { SmlResult } from '../ui/SmlResult';
import { CollectorCard } from './tools/CollectorCard';
import type { PanelBaseProps } from './types';

/**
 * 主机角色常用的 SML 预设：填进编辑框后可再改参数。
 *
 * <p>S2F33/35/37 是主机→设备方向的报文，只有主机角色能发；
 * 常用三个(S1F13/S2F33/S2F35)留在按钮上,启停事件收进「更多预设」下拉。</p>
 */
const HOST_PRESETS: ReadonlyArray<{ label: string; sml: string; hint: string }> = [
  { label: 'S1F13 建立通信', sml: 'S1F13 W', hint: 'Establish Communications Request' },
  {
    label: 'S2F33 定义报告',
    sml: 'S2F33 W\n<L\n  <U4 0>\n  <U4 3002>\n  <L\n    <L\n      <U4 3002>\n      <L <U4 1001> <U4 1004>>\n    >\n  >\n>.',
    hint: '把 1001/1004 绑进报告 3002',
  },
  {
    label: 'S2F35 链接事件',
    sml: 'S2F35 W\n<L\n  <U4 0>\n  <U4 2001>\n  <L\n    <L\n      <U4 2001>\n      <U4 3002>\n    >\n  >\n>.',
    hint: '事件 2001 触发时带报告 3002',
  },
];

/** S2F37 启停事件:CEED 0 停用 / 1 启用,CEID 列表可再改。 */
function s2f37Sml(ceed: number): string {
  return `S2F37 W\n<L\n  <BOOLEAN ${ceed}>\n  <L <U4 2001>>\n>.`;
}

/**
 * 主机模拟面板——三个分区的"主机侧工作台"。
 *
 * <p>以主机角色连到选中的设备(或远程 SECS),然后三件事分区做:</p>
 * <ol>
 *   <li>消息发送:自定义 SML + 预设(连接控制也在这里);</li>
 *   <li>FDC Trace 数据采集:S2F23 周期采样,设备按 REPGSZ 分条回 S6F1;</li>
 *   <li>S1F3 数据采集:主机主动轮询拉取,可编辑采集计划。</li>
 * </ol>
 * 设备只接受一个主机:真主机在线时连接会被拒(409),这是正确行为。</p>
 */
export function HostSimPanel({ backend, notifier, preferences, updatePreferences }: PanelBaseProps): JSX.Element {
  const [draft, setDraft] = useState('S1F13 W');
  const [busy, setBusy] = useState(false);
  const [opening, setOpening] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  /** FDC Trace 参数：DSPER 周期 / TOTSMP 总数 / REPGSZ 每条样本数。 */
  const [fdc, setFdc] = useState({ trid: 1, dsper: 1000, totsmp: 100, repgsz: 10, svids: [] as number[] });
  /** 连接目标:默认本机设备;远程手填 host/port/deviceId。 */
  const [remoteMode, setRemoteMode] = useState(false);
  const [remoteTarget, setRemoteTarget] = useState({ host: '', port: '5000', deviceId: '0' });

  const { sessions, activeSession, hostSimOpen, hostSimTarget, openHostSim, closeHostSim, sendHostSml } = backend;
  const disabled = activeSession === '';
  /** 采集卡需要的完整入参(会话已由页面钉住)。 */
  const propsForCollector = { backend, notifier, preferences, updatePreferences, sessionId: activeSession };

  useEffect(() => {
    setResult(null);
  }, [activeSession]);

  const onSend = async () => {
    if (disabled || !hostSimOpen || draft.trim() === '') {
      return;
    }
    setBusy(true);
    try {
      const sent = await sendHostSml(activeSession, draft);
      setResult(sent);
    } finally {
      setBusy(false);
    }
  };

  const onOpen = async () => {
    if (disabled || opening) {
      return;
    }
    if (remoteMode) {
      const port = Number.parseInt(remoteTarget.port, 10);
      const deviceId = Number.parseInt(remoteTarget.deviceId, 10);
      if (remoteTarget.host.trim() === '' || !Number.isFinite(port) || port < 1) {
        return;
      }
      setOpening(true);
      try {
        await openHostSim(activeSession, {
          host: remoteTarget.host.trim(),
          port,
          deviceId: Number.isFinite(deviceId) ? deviceId : 0,
        });
      } finally {
        setOpening(false);
      }
      return;
    }
    setOpening(true);
    try {
      await openHostSim(activeSession);
    } finally {
      setOpening(false);
    }
  };

  return (
    <Panel
      id="panel-hostsim"
      title="主机模拟"
      description="以主机角色连到设备后分三件事:消息发送、FDC Trace(S2F23→S6F1)与 S1F3 数据采集。"
      actions={
        hostSimOpen ? (
          <button
            type="button"
            className="danger"
            onClick={() => void closeHostSim(activeSession)}
            disabled={disabled}
            title="断开主机模拟连接"
          >
            断开连接
          </button>
        ) : (
          <button
            type="button"
            className="primary"
            onClick={() => void onOpen()}
            disabled={disabled || opening}
            title={remoteMode ? '以主机角色连到远程 SECS' : '以主机角色连到该设备（设备只接受一个主机）'}
          >
            {opening ? '连接中…' : remoteMode ? '连接远程 SECS' : '连接设备'}
          </button>
        )
      }
    >
      {disabled ? (
        <p className="hint">先在顶部选择一个会话。</p>
      ) : (
        <>
          {/* ① 消息发送:自定义 + 预设(连接目标也在这区) */}
          <section className="panel-section">
            <div className="section-head">
              <h2>① 消息发送（自定义 + 预设）</h2>
              {hostSimOpen && <span className="badge tone-ok">已连接 {hostSimTarget}</span>}
            </div>
            {!hostSimOpen && (
              <div className="form-row hostsim-target">
                <label className="check-inline">
                  <input
                    type="checkbox"
                    checked={remoteMode}
                    onChange={(event) => setRemoteMode(event.target.checked)}
                  />
                  连接其他 SECS（远程）
                </label>
                {remoteMode && (
                  <>
                    <label className="field">
                      主机
                      <input
                        value={remoteTarget.host}
                        onChange={(event) => setRemoteTarget({ ...remoteTarget, host: event.target.value })}
                        placeholder="如 192.168.1.50"
                      />
                    </label>
                    <label className="field">
                      端口
                      <input
                        value={remoteTarget.port}
                        onChange={(event) => setRemoteTarget({ ...remoteTarget, port: event.target.value })}
                        inputMode="numeric"
                      />
                    </label>
                    <label className="field">
                      Device ID
                      <input
                        value={remoteTarget.deviceId}
                        onChange={(event) => setRemoteTarget({ ...remoteTarget, deviceId: event.target.value })}
                        inputMode="numeric"
                      />
                    </label>
                  </>
                )}
              </div>
            )}
            <p className="hint">
              {hostSimOpen
                ? '已以主机角色连接。下面的报文都以主机方向发送。'
                : remoteMode
                  ? '远程模式:没有本机 catalog,S2F23/预设里的 SVID 需按远端设备手填。'
                  : '未连接。点右上「连接设备」以主机角色接入该会话的 HSMS 端口；设备已有真主机时会被拒绝。'}
            </p>
            <div className="preview-controls">
              {HOST_PRESETS.map((preset) => (
                <button
                  key={preset.label}
                  type="button"
                  disabled={!hostSimOpen}
                  title={preset.hint}
                  onClick={() => setDraft(preset.sml)}
                >
                  {preset.label}
                </button>
              ))}
              <PresetSelect
                disabled={!hostSimOpen}
                ariaLabel="更多主机预设"
                groups={[
                  {
                    label: 'S2F37 启停事件（CEID 列表可再改）',
                    items: [
                      { label: 'S2F37 停用事件', value: s2f37Sml(0) },
                      { label: 'S2F37 启用事件', value: s2f37Sml(1) },
                    ],
                  },
                ]}
                onPick={setDraft}
              />
              <button
                type="button"
                className="primary"
                disabled={!hostSimOpen || busy || draft.trim() === ''}
                onClick={() => void onSend()}
              >
                {busy ? '发送中…' : '发送（主机）'}
              </button>
            </div>
            <textarea
              className="sml-editor"
              spellCheck={false}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder={'S2F33 W\n<L\n  <U4 0>\n  <U4 3002>\n  <L\n    <L\n      <U4 3002>\n      <L <U4 1001> <U4 1004>>\n    >\n  >\n>.'}
              aria-label="主机角色 SML 报文内容"
            />
            {result !== null && (
              <SmlResult header={`发送 ${activeSession}(主机)  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`} result={result} peerLabel="设备" />
            )}
            <p className="hint">
              S2F33 定义报告（DATAID、RPTID、变量列表）、S2F35 链接事件（CEID→RPTID）、
              S2F37 启停事件（CEED、CEID 列表）。「事件设置」页的定义来源会变成"主机"，
              事件编排的启停状态也会跟着变。
            </p>
          </section>

          {/* ② FDC Trace 数据采集:设备主动推 S6F1 */}
          <section className="panel-section">
            <div className="section-head">
              <h2>② FDC Trace 数据采集（S2F23 → S6F1）</h2>
              <span className="hint-inline">设备按 DSPER 采样、REPGSZ 分条上报 S6F1</span>
            </div>
            <div className="form-row">
              <label className="field">
                TRID
                <NumberField value={fdc.trid} integer onCommit={(v) => setFdc({ ...fdc, trid: v })} />
              </label>
              <label className="field">
                采样周期 ms（DSPER）
                <NumberField value={fdc.dsper} integer onCommit={(v) => setFdc({ ...fdc, dsper: v })} />
              </label>
              <label className="field">
                总样本数（TOTSMP·0=终止）
                <NumberField value={fdc.totsmp} integer onCommit={(v) => setFdc({ ...fdc, totsmp: v })} />
              </label>
              <label className="field">
                每条 S6F1 样本数（REPGSZ）
                <NumberField value={fdc.repgsz} integer onCommit={(v) => setFdc({ ...fdc, repgsz: v })} />
              </label>
            </div>
            <div className="form-row">
              <span className="template-label">采集变量（最多 10 个）</span>
              {(backend.catalog?.svids ?? []).slice(0, 32).map((item) => (
                <label key={item.svid} className="check-inline">
                  <input
                    type="checkbox"
                    checked={fdc.svids.includes(item.svid)}
                    onChange={(event) => {
                      const next = event.target.checked
                        ? [...fdc.svids, item.svid]
                        : fdc.svids.filter((id) => id !== item.svid);
                      setFdc({ ...fdc, svids: next.slice(0, 10) });
                    }}
                  />
                  {item.svid}·{item.slot || item.owner}
                </label>
              ))}
            </div>
            <button
              type="button"
              className="primary"
              disabled={!hostSimOpen || fdc.svids.length === 0}
              title="生成 S2F23 并填入①的编辑框;再点「发送（主机）」下发"
              onClick={() => {
                const NL = String.fromCharCode(10);
                const list = fdc.svids.map((id) => '      <U4 ' + id + '>').join(NL);
                setDraft(
                  'S2F23 W' + NL +
                  '<L' + NL +
                  '  <U4 ' + fdc.trid + '>' + NL +
                  '  <A "' + dsperText(fdc.dsper) + '">' + NL +
                  '  <U4 ' + fdc.totsmp + '>' + NL +
                  '  <U4 ' + fdc.repgsz + '>' + NL +
                  '  <L' + NL +
                  list + NL +
                  '  >' + NL +
                  '>.',
                );
              }}
            >
              生成 S2F23（填入①的编辑框）
            </button>
            <p className="hint">
              DSPER 是 hhmmss 六位数字（整秒）：周期 ms 会收敛到最近的整秒、最小 1 秒。
              采样数据流到 S6F1,会话表的 S6F1 列显示已发帧数;TOTSMP=0 用于提前终止该 TRID。
            </p>
          </section>

          {/* ③ S1F3 数据采集:主机主动拉取(自带分区外壳) */}
          <CollectorCard {...propsForCollector} />
        </>
      )}
    </Panel>
  );
}

/**
 * DSPER 文本：hhmmss 六位数字（设备侧按整秒解析）。
 * 周期 ms 收敛到最近的整秒、至少 1 秒。
 */
function dsperText(dsperMs: number): string {
  const seconds = Math.max(1, Math.round(dsperMs / 1000));
  const hh = String(Math.floor(seconds / 3600)).padStart(2, '0');
  const mm = String(Math.floor((seconds % 3600) / 60)).padStart(2, '0');
  const ss = String(seconds % 60).padStart(2, '0');
  return `${hh}${mm}${ss}`;
}
