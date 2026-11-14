/**
 * 事件计划执行明细（3.0 M4，设计 §3.2-5）。
 *
 * <p>回答"这一次到底发了什么"（状态、报文条数、SVID before→after、回滚标记）。
 * 计划卡的累计/下次触发/单步试跑已并入「事件执行」列表的列与操作栏，
 * 这里只保留执行明细流。明细一律来自台账，SSE 只触发刷新。</p>
 */

import { useState, type CSSProperties } from 'react';
import type { NoticeWire } from '../../state/useEventStream';
import type { Notifier } from '../../state/useToasts';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { usePlanExecutions } from '../../state/usePlanExecutions';

interface Props {
  deviceId: string;
  liveEvents?: NoticeWire[];
  notifier: Notifier;
}

const STATUS_TONE: Record<string, string> = {
  SENT: 'tone-info',
  PARTIAL: 'tone-warn',
  FAILED: 'tone-danger',
  SKIPPED: 'tone-muted',
};

const rowStyle: CSSProperties = {
  borderLeft: '3px solid var(--line, #d9dee7)', padding: '6px 0 6px 12px',
  marginBottom: 6, fontSize: 13,
};

/** 执行明细组件。 */
export function PlanExecutionTimeline({ deviceId, liveEvents, notifier }: Props): JSX.Element {
  const exec = usePlanExecutions(deviceId, liveEvents);
  const [clearOpen, setClearOpen] = useState(false);
  const [clearing, setClearing] = useState(false);

  const runExport = async () => {
    const hint = await exec.exportCsv();
    if (hint !== '' && hint !== '没有可导出的执行记录') {
      notifier.success('已导出执行记录', hint);
    } else {
      notifier.error('暂无可导出的记录');
    }
  };

  const runClear = async () => {
    setClearing(true);
    try {
      const ok = await exec.clear();
      if (ok) {
        notifier.success('已清理历史事件', '该设备的历史执行记录已清空');
        setClearOpen(false);
      } else {
        notifier.error('清理失败', exec.error);
      }
    } finally {
      setClearing(false);
    }
  };

  return (
    <section className="panel-section block" id="plan-executions">
      <div className="section-head">
        <h2 style={{ margin: 0 }}>执行明细</h2>
        <div className="preview-controls">
          <label className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
            <input
              type="checkbox"
              checked={exec.onlyFailures}
              onChange={(event) => exec.setOnlyFailures(event.target.checked)}
            />
            只看失败
          </label>
          <label className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
            <input
              type="checkbox"
              checked={exec.includeDryRun}
              onChange={(event) => exec.setIncludeDryRun(event.target.checked)}
            />
            含试跑
          </label>
          <label className="field">
            条数
            <select value={exec.limit} onChange={(event) => exec.setLimit(Number(event.target.value))} aria-label="执行记录条数">
              {[50, 100, 200, 500].map((value) => <option key={value} value={value}>{value}</option>)}
            </select>
          </label>
          <button type="button" onClick={() => void exec.refresh()}>刷新</button>
          <button type="button" onClick={() => void runExport()}>导出 CSV</button>
          <button
            type="button"
            className="danger"
            disabled={deviceId === '' || clearing || exec.allRows.length === 0}
            onClick={() => setClearOpen(true)}
            title="清空该设备的全部历史执行记录，不影响配置与计划"
          >
            清理历史
          </button>
        </div>
      </div>

      <ConfirmModal
        open={clearOpen}
        title="清理历史事件"
        danger
        confirmLabel="清理"
        message={deviceId === '' ? '' : `确定清理设备「${deviceId}」的全部历史执行记录？只删运行记录，配置与计划不受影响，且不可恢复。`}
        onClose={() => setClearOpen(false)}
        onConfirm={() => void runClear()}
      />

      {exec.error !== '' && <p className="hint" style={{ color: '#b42318' }}>执行记录读取失败：{exec.error}</p>}

      <div style={{ marginTop: 10, maxHeight: 320, overflowY: 'auto' }}>
        {exec.rows.length === 0 && <p className="hint">暂无执行记录。启用计划并等待触发，或点列表里的「单步试跑」。</p>}
        {exec.rows.map((row) => (
          <div
            key={row.id}
            style={{ ...rowStyle, borderLeftColor: row.status === 'FAILED' ? '#d92d20' : '#2563eb' }}
          >
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
              <span className="mono hint-inline">{new Date(row.firedAt).toLocaleTimeString()}</span>
              <span className={`badge ${STATUS_TONE[row.status] ?? 'tone-muted'}`}>{row.status}</span>
              <strong className="mono">{row.planId}</strong>
              <span className="hint-inline">CEID {row.ceid}</span>
              <span className="hint-inline">报文 {row.messages.length} 条</span>
              {row.dryRun && <span className="badge tone-warn">试跑</span>}
              {row.rollback && <span className="badge tone-warn">{row.rollback}</span>}
            </div>
            {row.effects.length > 0 && (
              <div className="hint" style={{ marginTop: 3 }}>
                {row.effects.map((effect, index) => (
                  <span key={index} className="mono" style={{ marginRight: 12 }}>
                    SVID {effect.svid} {effect.op} {effect.before ?? '—'}→{effect.after ?? '—'}
                  </span>
                ))}
              </div>
            )}
            {row.error !== '' && <div className="hint" style={{ color: '#b42318' }}>{row.error}</div>}
          </div>
        ))}
      </div>
    </section>
  );
}
