import { useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { SmlResult } from '../../../shared/ui/SmlResult';
import type { SentMessageResult } from '../../../api';
import { useHostSendDraft } from '../../../state/hostPlans';
import { HOST_PRESETS } from './hostPresets';
import type { PanelArgs } from './hostPanelShared';

/** 消息发送(单主机):预设 + SML 编辑 + 发送 + 压测。 */
export function HostSendPanel({ hostId, hosts }: PanelArgs): JSX.Element {
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
    <Panel id="panel-host-send" title="消息发送" headless>
      {error !== '' && <p className="inline-note danger">{error}</p>}
      <p className="hint">以本主机身份发一条 SML；收发明细在右侧「实时消息监控」，配置区·报文模板里有可直接填入的 SML 案例。</p>
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