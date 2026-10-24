import { useRef, useState } from 'react';
import type { SentMessageResult } from '../api';
import { Panel } from '../ui/Panel';
import { NumberField } from '../ui/CommitFields';
import { highlightSml } from '../lib/smlHighlight';
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

/** 一轮压测的汇总。 */
interface StressSummary {
  total: number;
  sent: number;
  failed: number;
  timedOut: number;
  /** 成功请求的往返耗时（ms）。 */
  latencies: number[];
  wallMs: number;
}

/**
 * 手动发送面板。
 *
 * <p>发出去之后，"发出去了但主机没回"和"根本没发出去"是两回事，
 * 必须分开说：结果区把回复内容、超时与否、耗时都列出来。
 * 压测把同一件事重复 N 次（可并发），量的是链路的吞吐与往返延迟。</p>
 */
export function SendPanel({ backend }: PanelBaseProps): JSX.Element {
  const [draft, setDraft] = useState('S1F1 W.');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SentMessageResult | null>(null);
  const [error, setError] = useState('');

  const [stressTotal, setStressTotal] = useState(50);
  const [stressConcurrency, setStressConcurrency] = useState(1);
  const [stressInterval, setStressInterval] = useState(0);
  const [stressRunning, setStressRunning] = useState(false);
  const [stressProgress, setStressProgress] = useState({ done: 0, total: 0 });
  const [stressSummary, setStressSummary] = useState<StressSummary | null>(null);
  const stopRef = useRef(false);

  const { activeSession } = backend;
  const disabled = activeSession === '';

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

  /* ---------- 压测 ---------- */

  const runStress = async () => {
    if (disabled || stressRunning || draft.trim() === '') {
      return;
    }
    const total = Math.min(1000, Math.max(1, Math.round(stressTotal)));
    const concurrency = Math.min(20, Math.max(1, Math.round(stressConcurrency)));
    const interval = Math.max(0, Math.round(stressInterval));
    const text = draft;

    stopRef.current = false;
    setStressRunning(true);
    setStressSummary(null);
    setStressProgress({ done: 0, total });

    const summary: StressSummary = {
      total,
      sent: 0,
      failed: 0,
      timedOut: 0,
      latencies: [],
      wallMs: 0,
    };
    const started = performance.now();
    let nextIndex = 0;

    const one = async (): Promise<void> => {
      const t0 = performance.now();
      try {
        const sent = await backend.sendSml(activeSession, text);
        if (sent !== null && sent.timedOut) {
          summary.timedOut += 1;
        }
        if (sent !== null) {
          summary.sent += 1;
          summary.latencies.push(performance.now() - t0);
        } else {
          summary.failed += 1;
        }
      } catch {
        summary.failed += 1;
      }
      summary.wallMs = performance.now() - started;
    };

    /* 并发 worker：各领各的序号；单并发时按间隔顺次发 */
    const worker = async (workerIndex: number): Promise<void> => {
      for (;;) {
        if (stopRef.current) {
          return;
        }
        const index = nextIndex;
        if (index >= total) {
          return;
        }
        nextIndex += 1;
        await one();
        setStressProgress({ done: Math.min(nextIndex, total), total });
        if (interval > 0) {
          await new Promise((resolve) => setTimeout(resolve, interval));
        }
        void workerIndex;
      }
    };

    const workers = Array.from({ length: Math.min(concurrency, total) }, (_, i) => worker(i));
    await Promise.all(workers);
    summary.wallMs = performance.now() - started;

    setStressProgress({ done: summary.sent + summary.failed, total });
    setStressSummary(summary);
    setStressRunning(false);
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

  const stressStats = (() => {
    if (stressSummary === null) {
      return null;
    }
    const sorted = [...stressSummary.latencies].sort((a, b) => a - b);
    const avg = sorted.length > 0 ? sorted.reduce((a, b) => a + b, 0) / sorted.length : 0;
    const p95 = sorted.length > 0 ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))] : 0;
    const throughput = stressSummary.wallMs > 0 ? (stressSummary.sent / stressSummary.wallMs) * 1000 : 0;
    return { min: sorted[0] ?? 0, max: sorted[sorted.length - 1] ?? 0, avg, p95, throughput };
  })();

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
        没有对端时服务端回 409。压测复用上面这份报文内容。
      </p>
      {error !== '' && (
        <pre className="sml-result error-result">发送失败：{error}</pre>
      )}
      {result !== null && (
        <pre
          className="sml-result"
          /* 高亮输出已整体转义，不可能注入 */
          dangerouslySetInnerHTML={{ __html: highlightSml(lines.join('\n')) }}
        />
      )}

      <h3 className="sub-title">压测</h3>
      <div className="form-row">
        <label className="field">
          次数（1–1000）
          <NumberField value={stressTotal} integer onCommit={setStressTotal} />
        </label>
        <label className="field">
          并发（1–20）
          <NumberField value={stressConcurrency} integer onCommit={setStressConcurrency} />
        </label>
        <label className="field">
          每轮间隔 ms
          <NumberField value={stressInterval} integer onCommit={setStressInterval} />
        </label>
        {stressRunning ? (
          <button
            type="button"
            className="danger"
            onClick={() => {
              stopRef.current = true;
            }}
          >
            停止
          </button>
        ) : (
          <button
            type="button"
            className="primary"
            disabled={disabled || draft.trim() === ''}
            onClick={() => void runStress()}
            title='重复发送同一份报文，统计成功率与往返延迟'
          >
            开始压测
          </button>
        )}
      </div>
      {stressRunning && (
        <p className="hint">
          进行中：{stressProgress.done} / {stressProgress.total}（发送 {activeSession}）
        </p>
      )}
      {stressSummary !== null && stressStats !== null && (
        <pre className="sml-result">{[
          `压测完成：共 ${stressSummary.total}，成功 ${stressSummary.sent}，失败 ${stressSummary.failed}，超时 ${stressSummary.timedOut}`,
          `往返延迟：平均 ${stressStats.avg.toFixed(1)} ms，最小 ${stressStats.min.toFixed(1)} ms，最大 ${stressStats.max.toFixed(1)} ms，P95 ${stressStats.p95.toFixed(1)} ms`,
          `吞吐：${stressStats.throughput.toFixed(1)} 条/秒（墙钟 ${(stressSummary.wallMs / 1000).toFixed(1)} s）`,
        ].join('\n')}</pre>
      )}
    </Panel>
  );
}
