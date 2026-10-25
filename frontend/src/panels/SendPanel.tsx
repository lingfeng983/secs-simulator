import { useState } from 'react';
import type { SentMessageResult } from '../api';
import { Panel } from '../ui/Panel';
import { PresetSelect } from '../ui/PresetSelect';
import { SmlResult } from '../ui/SmlResult';
import type { PanelBaseProps } from './types';

/**
 * 常用的远程命令（S2F41 RCMID 预设，E30 常用集）。
 *
 * <p>点一下填进编辑框再发，参数（如批次号）可直接改。</p>
 */
const REMOTE_COMMANDS: ReadonlyArray<{ label: string; rcmdid: number; note: string }> = [
  { label: 'START', rcmdid: 1, note: '开始处理' },
  { label: 'STOP', rcmdid: 2, note: '停止处理' },
  { label: 'PAUSE', rcmdid: 3, note: '暂停' },
  { label: 'RESUME', rcmdid: 4, note: '恢复' },
  { label: 'CANCEL', rcmdid: 5, note: '取消' },
];

const EXAMPLES: ReadonlyArray<{ label: string; sml: string }> = [
  { label: '示例 S1F1', sml: 'S1F1 W.' },
  {
    label: '示例 S5F1',
    sml: 'S5F1 W\n<L\n  <B 0x81>\n  <U2 1001>\n  <A "ON FIRE">\n>.',
  },
  { label: '示例 S2F41', sml: 'S2F41 W\n<L\n  <U4 1>\n  <A "SIM">\n>.' },
  { label: '时钟同步 S2F17', sml: 'S2F17 W.' },
];

/** 远程命令的 SML 骨架:填 RCMID 即可发送。 */
function remoteCommandSml(rcmdid: number): string {
  const NL = String.fromCharCode(10);
  return 'S2F41 W' + NL + '<L' + NL + '  <U4 ' + rcmdid + '>' + NL + '  <A "SIM">' + NL + '>.';
}

/**
 * 手动发送面板。
 *
 * <p>发出去之后，"发出去了但主机没回"和"根本没发出去"是两回事，
 * 必须分开说：结果区把回复内容、超时与否、耗时都列出来。
 * 常用预设留在按钮上,示例 S2F41 与五个远程命令收进下拉;
 * 批量重复发送走「压测」子页(服务端循环,消除 HTTP 往返失真),这里只做单发。</p>
 */
export function SendPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const [draft, setDraft] = useState('S1F1 W.');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  const [error, setError] = useState('');
  /* 自定义消息压测 */
  const [stressCount, setStressCount] = useState('100');
  const [stressInterval, setStressInterval] = useState('0');
  const [stressRunning, setStressRunning] = useState(false);
  const [stressResult, setStressResult] = useState<string | null>(null);

  const { activeSession } = backend;
  const disabled = activeSession === '';

  /** 把编辑框里的这条消息在服务端循环发送 N 次,统计成功率与延迟。 */
  const runStress = async () => {
    const count = Number.parseInt(stressCount, 10);
    if (draft.trim() === '') {
      notifier.error('SML 不能为空');
      return;
    }
    if (!Number.isFinite(count) || count < 1 || count > 5000) {
      notifier.error('次数要在 1..5000 之间');
      return;
    }
    setStressRunning(true);
    setStressResult(null);
    try {
      const stats = await backend.burst(activeSession, {
        kind: 'message',
        sml: draft,
        count,
        intervalMs: Number.parseInt(stressInterval, 10) || 0,
      });
      setStressResult(`压测完成:成功 ${stats.sent} / 失败 ${stats.failed} / 总耗时 ${stats.durationMs}ms / P50 ${stats.latencyP50}ms / P95 ${stats.latencyP95}ms`);
      notifier.info(`压测完成:成功 ${stats.sent} / 失败 ${stats.failed}`);
    } catch (burstError) {
      notifier.error(`压测失败: ${(burstError as Error).message}`);
    } finally {
      setStressRunning(false);
    }
  };

  const onSend = async () => {
    if (disabled || draft.trim() === '') {
      return;
    }
    setBusy(true);
    setError('');
    setResult(null);
    try {
      const sent = await backend.sendSml(activeSession, draft);
      /* sendSml 已经弹过提示了；这里再把结构化结果留下来给用户细看 */
      setResult(sent);
    } catch (sendError) {
      /* 控制器已经报过错，这里只把详情留在结果区 */
      setError((sendError as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel
      id="panel-send"
      title="手动发送"
      description="直接往选中的会话发一条 SML。报文头写 SxFy，可带 W 位。"
      actions={
        <>
          <button type="button" onClick={() => setDraft(EXAMPLES[0].sml)} disabled={disabled}>
            {EXAMPLES[0].label}
          </button>
          <button type="button" onClick={() => setDraft(EXAMPLES[1].sml)} disabled={disabled}>
            {EXAMPLES[1].label}
          </button>
          <button
            type="button"
            onClick={() => setDraft(EXAMPLES[3].sml)}
            disabled={disabled}
            title="让设备用主机时间对钟（S2F18 回当前时间）"
          >
            {EXAMPLES[3].label}
          </button>
          <PresetSelect
            disabled={disabled}
            ariaLabel="更多发送预设"
            groups={[
              {
                label: '示例',
                items: [{ label: EXAMPLES[2].label, value: EXAMPLES[2].sml }],
              },
              {
                label: '远程命令（S2F41，参数可再改）',
                items: REMOTE_COMMANDS.map((command) => ({
                  label: `${command.label}（RCMID ${command.rcmdid} · ${command.note}）`,
                  value: remoteCommandSml(command.rcmdid),
                })),
              },
            ]}
            onPick={setDraft}
          />
          <button
            type="button"
            className="primary"
            onClick={() => void onSend()}
            disabled={disabled || busy || draft.trim() === ''}
            title='发送到选中的会话（W 位会等待回复）'
          >
            {busy ? '发送中…' : '发送 SML'}
          </button>
        </>
      }
    >
      <textarea
        className="sml-editor"
        spellCheck={false}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={'S1F1 W.\n<L\n  <B 0x81>\n>.'}
        aria-label="SML 报文内容"
      />
      <p className="hint">
        报文头写 <code>SxFy</code>，可带 <code>W</code>；数据项支持
        <code>L/A/B/BOOLEAN/I1..I8/U1..U8/F4/F8</code>。W 位会等 T3，
        没有对端时服务端回 409。同一条消息要重复发送就用下面的压测。
      </p>

      <h3 className="sub-title">压测发送（服务端循环这条消息）</h3>
      <div className="toolbar">
        <label className="field">
          次数
          <input value={stressCount} onChange={(event) => setStressCount(event.target.value)} inputMode="numeric" />
        </label>
        <label className="field">
          间隔 ms
          <input value={stressInterval} onChange={(event) => setStressInterval(event.target.value)} inputMode="numeric" title="0 = 全速" />
        </label>
        <button
          type="button"
          className="primary"
          disabled={disabled || stressRunning || draft.trim() === ''}
          onClick={() => void runStress()}
          title="在服务端循环发送编辑框里这条 SML,消除 HTTP 往返失真"
        >
          {stressRunning ? '压测中…' : '压测发送'}
        </button>
      </div>
      {stressResult !== null && (
        <div className="burst-result">{stressResult}</div>
      )}
      {error !== '' && (
        <pre className="sml-result error-result">发送失败：{error}</pre>
      )}
      {result !== null && (
        <SmlResult header={`发送 ${activeSession}  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`} result={result} peerLabel="主机" />
      )}
    </Panel>
  );
}
