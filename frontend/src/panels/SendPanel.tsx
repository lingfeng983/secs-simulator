import { useState } from 'react';
import type { SentMessageResult } from '../api';
import { Panel } from '../ui/Panel';
import type { PanelBaseProps } from './types';

/** 常用的示例报文；点一下就能改，不用手打。 */
const EXAMPLES: ReadonlyArray<{ label: string; sml: string }> = [
  { label: '示例 S1F1', sml: 'S1F1 W.' },
  {
    label: '示例 S5F1',
    sml: 'S5F1 W\n<L\n  <B 0x81>\n  <U2 1001>\n  <A "ON FIRE">\n>.',
  },
  { label: '示例 S2F41', sml: 'S2F41 W\n<L\n  <U4 1>\n  <A "SIM">\n>.' },
];

/**
 * 手动发送面板。
 *
 * <p>发出去之后，"发出去了但主机没回"和"根本没发出去"是两回事，
 * 必须分开说：结果区把回复内容、超时与否、耗时都列出来。</p>
 */
export function SendPanel({ backend }: PanelBaseProps): JSX.Element {
  const [draft, setDraft] = useState('S1F1 W.');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  const [error, setError] = useState('');

  const { activeSession, canOperate, operateHint, role } = backend;
  const disabled = !canOperate || activeSession === '';

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

  const lines: string[] = [];
  if (result !== null) {
    lines.push(`发送 ${activeSession}  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`);
    lines.push(result.sentSml === '' ? '（无 body）' : result.sentSml);
    if (result.replySml != null) {
      lines.push('', `回复 S${result.replyStream}F${result.replyFunction}${result.replyWbit ? ' W' : ''}`, result.replySml);
    } else if (result.timedOut) {
      lines.push('', 'W 位报文已发出，但主机未在 T3 内回复');
    } else {
      lines.push('', '未请求回复（W=0）');
    }
    lines.push('', `耗时 ${result.elapsedMs} ms`);
  }

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
          <button type="button" onClick={() => setDraft(EXAMPLES[2].sml)} disabled={disabled}>
            {EXAMPLES[2].label}
          </button>
          <button
            type="button"
            className="primary"
            onClick={() => void onSend()}
            disabled={disabled || busy || draft.trim() === ''}
            title={canOperate ? '发送到选中的会话（W 位会等待回复）' : operateHint}
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
        没有对端时服务端回 409。
        {canOperate ? '' : ` 当前${role === undefined ? '身份未确认' : '角色不足'}，发送已禁用。`}
      </p>
      {error !== '' && (
        <pre className="sml-result error-result">发送失败：{error}</pre>
      )}
      {result !== null && <pre className="sml-result">{lines.join('\n')}</pre>}
    </Panel>
  );
}
