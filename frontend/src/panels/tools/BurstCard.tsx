import { useState } from 'react';
import type { PanelBaseProps } from '../types';
import type { BurstRequest } from '../../api';
import { CeidChips, CeidDatalist } from '../../ui/CeidPicker';
import { downloadText, safeFilename } from '../../lib/table';

/** 一次压测的完整留档:请求参数 + 服务端统计。 */
interface BurstRun {
  request: BurstRequest;
  stats: { sent: number; failed: number; durationMs: number; latencyP50: number; latencyP95: number; latencyMax: number };
}

/**
 * 服务端压测卡（schema v6 需求③的"自定义消息压测 / 特定事件压测"）。
 *
 * <p>把同一条 SML / 同一个 CEID / 同一个报警在<b>服务端</b>循环发送:
 * 之前是前端 20-worker 的 HTTP 循环,往返开销会吃掉真实吞吐;
 * 下沉到后端后测出来的是会话自身的处理能力。</p>
 */
export function BurstCard({ backend, notifier, sessionId, catalogHint }: PanelBaseProps & { sessionId: string; catalogHint?: boolean }): JSX.Element {
  const [kind, setKind] = useState<BurstRequest['kind']>('message');
  const [count, setCount] = useState('100');
  const [intervalMs, setIntervalMs] = useState('0');
  const [sml, setSml] = useState('S1F3 W\n  <L U4 1001 >');
  const [ceid, setCeid] = useState('');
  const [alid, setAlid] = useState('');
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<BurstRun | null>(null);

  const ceidChoices = backend.catalog?.ceids ?? [];

  const run = async () => {
    const parsedCount = Number.parseInt(count, 10);
    if (!Number.isFinite(parsedCount) || parsedCount < 1 || parsedCount > 5000) {
      notifier.error('次数要在 1..5000 之间');
      return;
    }
    const request: BurstRequest = {
      kind,
      count: parsedCount,
      intervalMs: Number.parseInt(intervalMs, 10) || 0,
    };
    if (kind === 'message') {
      if (sml.trim() === '') {
        notifier.error('SML 不能为空');
        return;
      }
      request.sml = sml;
    } else if (kind === 'ceid') {
      const parsed = Number.parseInt(ceid, 10);
      if (!Number.isFinite(parsed) || parsed < 1) {
        notifier.error('CEID 必须是正整数');
        return;
      }
      request.ceid = parsed;
    } else {
      const parsed = Number.parseInt(alid, 10);
      if (!Number.isFinite(parsed) || parsed < 1) {
        notifier.error('ALID 必须是正整数');
        return;
      }
      request.alid = parsed;
    }

    setRunning(true);
    setResult(null);
    try {
      const stats = await backend.burst(sessionId, request);
      setResult({ request, stats });
      notifier.info(`压测完成:成功 ${stats.sent} / 失败 ${stats.failed}`);
    } catch (error) {
      notifier.error(`压测失败: ${(error as Error).message}`);
    } finally {
      setRunning(false);
    }
  };

  const exportResult = () => {
    if (result === null) {
      return;
    }
    const kindLabel = { message: '自定义消息', ceid: '特定事件', alarm: '报警风暴' }[result.request.kind];
    const rows: (string | number)[][] = [
      ['压测类型', '次数', '间隔ms', '成功', '失败', '总耗时ms', 'P50ms', 'P95ms', 'Maxms'],
      [
        kindLabel,
        result.request.count,
        result.request.intervalMs ?? 0,
        result.stats.sent,
        result.stats.failed,
        result.stats.durationMs,
        result.stats.latencyP50,
        result.stats.latencyP95,
        result.stats.latencyMax,
      ],
    ];
    const csv = rows.map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(',')).join('\r\n');
    downloadText(`burst-${safeFilename(sessionId)}-${Date.now()}.csv`, `﻿${csv}`, 'text/csv;charset=utf-8');
  };

  return (
    <section className="panel-section" id="panel-burst">
      <div className="section-head">
        <h2>压测（服务端循环）</h2>
      </div>
      <p className="hint">
        在后端循环发送,消除 HTTP 往返失真。消息压测按当前设备直发 SML;
        事件压测走手动上报路径(不受 events.maxPerSec 限制);报警压测交替置位/清除。
      </p>

      <div className="toolbar">
        <div className="segmented" role="tablist">
          {([['message', '自定义消息'], ['ceid', '特定事件'], ['alarm', '报警风暴']] as const).map(([value, label]) => (
            <button
              key={value}
              type="button"
              className={kind === value ? 'active' : ''}
              onClick={() => setKind(value)}
            >
              {label}
            </button>
          ))}
        </div>
        <label className="field">
          次数
          <input value={count} onChange={(event) => setCount(event.target.value)} inputMode="numeric" />
        </label>
        <label className="field">
          间隔 ms
          <input value={intervalMs} onChange={(event) => setIntervalMs(event.target.value)} inputMode="numeric" title="0 = 全速" />
        </label>
        <button type="button" className="primary" disabled={running} onClick={() => void run()}>
          {running ? '压测中…' : '开始压测'}
        </button>
      </div>

      {kind === 'message' && (
        <label className="field">
          SML(每次循环发送同一条)
          <textarea
            value={sml}
            onChange={(event) => setSml(event.target.value)}
            rows={3}
            spellCheck={false}
          />
        </label>
      )}
      {kind === 'ceid' && (
        <div className="toolbar">
          <label className="field">
            CEID
            <input
              value={ceid}
              onChange={(event) => setCeid(event.target.value)}
              list="burst-ceid-choices"
              inputMode="numeric"
              placeholder="如 2001"
            />
          </label>
          <CeidDatalist id="burst-ceid-choices" ceids={ceidChoices} />
          <CeidChips ceids={ceidChoices} onPick={(value) => setCeid(String(value))} />
        </div>
      )}
      {kind === 'alarm' && (
        <div className="toolbar">
          <label className="field">
            ALID
            <input value={alid} onChange={(event) => setAlid(event.target.value)} inputMode="numeric" placeholder="如 501" />
          </label>
          <span className="hint-inline">交替置位/清除,便于观察报警风暴对主机的影响</span>
        </div>
      )}

      {result !== null && (
        <div className="burst-result">
          <span>成功 <b>{result.stats.sent}</b></span>
          <span className={result.stats.failed > 0 ? 'danger' : ''}>失败 <b>{result.stats.failed}</b></span>
          <span>耗时 {result.stats.durationMs}ms</span>
          <span>P50 {result.stats.latencyP50}ms</span>
          <span>P95 {result.stats.latencyP95}ms</span>
          <span>Max {result.stats.latencyMax}ms</span>
          <button type="button" onClick={exportResult} title="把本次压测参数与统计导出为 CSV">
            导出 CSV
          </button>
        </div>
      )}
    </section>
  );
}
