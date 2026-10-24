import { useEffect, useState } from 'react';
import type { SentMessageResult } from '../api';
import { Panel } from '../ui/Panel';
import { highlightSml } from '../lib/smlHighlight';
import type { PanelBaseProps } from './types';

/**
 * 主机角色常用的 SML 预设：填进编辑框后可再改参数。
 *
 * <p>S2F33/35/37 是主机→设备方向的报文，只有主机角色能发；
 * 这里给了可直接用的骨架（报告 3002 绑 TEMP/PRESS、事件 2001 链 3002、启停 2001）。</p>
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
  {
    label: 'S2F37 停用事件',
    sml: 'S2F37 W\n<L\n  <BOOLEAN 0>\n  <L <U4 2001>>\n>.',
    hint: 'CEED=0 停用 2001（改 1 为启用）',
  },
];

/**
 * 主机模拟面板。
 *
 * <p>以主机角色连到选中的设备会话（loopback），补上 S2F33/35/37
 * 这类"只有主机能发"的报文的界面入口。设备只接受一个主机：
 * 真主机在线时连接会被拒（409），这是正确行为。</p>
 */
export function HostSimPanel({ backend }: PanelBaseProps): JSX.Element {
  const [draft, setDraft] = useState('S1F13 W');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);

  const { sessions, activeSession, hostSimOpen, hostSimTarget, openHostSim, closeHostSim, sendHostSml } = backend;
  const disabled = activeSession === '';

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

  const lines: string[] = [];
  if (result !== null) {
    lines.push(`发送 ${activeSession}(主机)  S${result.stream}F${result.function}${result.wbit ? ' W' : ''}`);
    lines.push(result.sentSml === '' ? '（无 body）' : result.sentSml);
    if (result.replySml != null) {
      lines.push('', `回复 S${result.replyStream}F${result.replyFunction}${result.replyWbit ? ' W' : ''}`, result.replySml);
    } else if (result.timedOut) {
      lines.push('', 'W 位报文已发出，但设备未在 T3 内回复');
    } else {
      lines.push('', '未请求回复（W=0）');
    }
    lines.push('', `耗时 ${result.elapsedMs} ms`);
  }

  return (
    <Panel
      id="panel-hostsim"
      title="主机模拟"
      description="以主机角色连到选中的设备，发 S2F33/35/37 这类只有主机能发的报文。"
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
            onClick={() => void openHostSim(activeSession)}
            disabled={disabled}
            title="以主机角色连到该设备（设备只接受一个主机）"
          >
            连接设备
          </button>
        )
      }
    >
      {disabled ? (
        <p className="hint">先在顶部选择一个会话。</p>
      ) : (
        <>
          <p className="hint">
            {hostSimOpen
              ? `已连接 ${hostSimTarget}（主机角色）。`
              : '未连接。点「连接设备」以主机角色接入该会话的 HSMS 端口；设备已有真主机时会被拒绝。'}
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
            <pre
              className="sml-result"
              /* 高亮输出已整体转义，不可能注入 */
              dangerouslySetInnerHTML={{ __html: highlightSml(lines.join('\n')) }}
            />
          )}
          <p className="hint">
            S2F33 定义报告（DATAID、RPTID、变量列表）、S2F35 链接事件（CEID→RPTID）、
            S2F37 启停事件（CEED、CEID 列表）。设备侧的「设备目录」会显示来源变成"主机"，
            事件编排的启停状态也会跟着变。
          </p>
        </>
      )}
    </Panel>
  );
}
