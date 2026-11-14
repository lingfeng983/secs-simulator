/**
 * 事件计划执行时间轴（3.0 M4，设计 §3.2-5）。
 *
 * <p>「计划卡」回答"启用之后确实在跑吗"（累计 / 最后错误 / 下次触发 / 单步试跑），
 * 「时间轴」回答"这一次到底发了什么"（状态、报文条数、SVID before→after、回滚标记），
 * 迷你曲线把事件竖线叠在 SVID 变化上。明细一律来自台账，SSE 只触发刷新。</p>
 */

import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import type { EventPlanView } from '../../api';
import type { NoticeWire } from '../../state/useEventStream';
import type { Notifier } from '../../state/useToasts';
import { usePlanExecutions, type ExecutionRow } from '../../state/usePlanExecutions';

interface Props {
  deviceId: string;
  plans: EventPlanView[];
  liveEvents?: NoticeWire[];
  notifier: Notifier;
}

interface PlanStats {
  count: number;
  lastAt: number;
  lastError: string;
  lastStatus: string;
}

const STATUS_TONE: Record<string, string> = {
  SENT: 'tone-info',
  PARTIAL: 'tone-warn',
  FAILED: 'tone-danger',
  SKIPPED: 'tone-muted',
};

const cardStyle: CSSProperties = {
  border: '1px solid var(--line, #d9dee7)', borderRadius: 8, padding: '10px 12px',
  minWidth: 190, flex: '1 1 190px', background: 'var(--surface, #fff)',
};

const rowStyle: CSSProperties = {
  borderLeft: '3px solid var(--line, #d9dee7)', padding: '6px 0 6px 12px',
  marginBottom: 6, fontSize: 13,
};

/** 从台账里挑出现次数最多的 SVID，画 before→after 折线并叠事件竖线。 */
function SvidSparkline({ rows }: { rows: ExecutionRow[] }): JSX.Element | null {
  const chart = useMemo(() => {
    const counts = new Map<number, number>();
    for (const row of rows) {
      for (const effect of row.effects) {
        counts.set(effect.svid, (counts.get(effect.svid) ?? 0) + 1);
      }
    }
    const svid = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
    if (svid === undefined) {
      return null;
    }
    const points = rows
      .filter((row) => row.effects.some((effect) => effect.svid === svid && effect.after !== null))
      .map((row) => ({
        at: row.firedAt,
        value: row.effects.find((effect) => effect.svid === svid)?.after ?? 0,
        failed: row.status === 'FAILED' || row.status === 'PARTIAL',
      }))
      .sort((a, b) => a.at - b.at);
    if (points.length < 2) {
      return null;
    }
    const min = Math.min(...points.map((point) => point.value));
    const max = Math.max(...points.map((point) => point.value));
    const span = max - min || 1;
    const first = points[0].at;
    const last = points[points.length - 1].at;
    const x = (at: number) => 24 + ((at - first) / (last - first || 1)) * 550;
    const y = (value: number) => 70 - ((value - min) / span) * 52;
    return {
      svid, min, max,
      path: points.map((point, index) => `${index === 0 ? 'M' : 'L'}${x(point.at).toFixed(1)},${y(point.value).toFixed(1)}`).join(' '),
      lines: points.filter((point) => point.failed).map((point) => x(point.at)),
    };
  }, [rows]);

  if (chart === null) {
    return <p className="hint">执行记录里还没有可绘制的 SVID 变化（效果为空或样本不足）。</p>;
  }
  return (
    <div className="panel-section" style={{ marginTop: 4 }}>
      <div className="section-head" style={{ marginBottom: 4 }}>
        <h3 style={{ margin: 0, fontSize: 14 }}>
          SVID <span className="mono">{chart.svid}</span> 变化 · 事件竖线为失败/部分成功
        </h3>
        <span className="hint">
          {chart.min} ~ {chart.max}
        </span>
      </div>
      <svg viewBox="0 0 600 84" width="100%" height="84" role="img" aria-label="SVID 变化曲线与事件竖线">
        <line x1="24" y1="74" x2="580" y2="74" stroke="var(--line, #d9dee7)" strokeWidth="1" />
        {chart.lines.map((position, index) => (
          <line key={index} x1={position} y1="8" x2={position} y2="74" stroke="#d92d20" strokeWidth="1" strokeDasharray="3 3" />
        ))}
        <path d={chart.path} fill="none" stroke="#2563eb" strokeWidth="2" />
      </svg>
    </div>
  );
}

/** 执行时间轴组件。 */
export function PlanExecutionTimeline({ deviceId, plans, liveEvents, notifier }: Props): JSX.Element {
  const exec = usePlanExecutions(deviceId, liveEvents);
  const [now, setNow] = useState(Date.now());
  const [stepping, setStepping] = useState('');

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const stats = useMemo(() => {
    const result = new Map<string, PlanStats>();
    for (const row of exec.allRows) {
      const current = result.get(row.planId) ?? { count: 0, lastAt: 0, lastError: '', lastStatus: '' };
      if (row.firedAt >= current.lastAt) {
        current.lastAt = row.firedAt;
        current.lastError = row.error;
        current.lastStatus = row.status;
      }
      current.count += 1;
      result.set(row.planId, current);
    }
    return result;
  }, [exec.allRows]);

  const runStep = async (planId: string) => {
    setStepping(planId);
    try {
      const ok = await exec.step(planId);
      if (ok) {
        notifier.success('已试跑一次', '记为试跑，不计入触发次数');
      } else {
        notifier.error('试跑失败', exec.error);
      }
    } finally {
      setStepping('');
    }
  };

  const runExport = async () => {
    const hint = await exec.exportCsv();
    if (hint !== '' && hint !== '没有可导出的执行记录') {
      notifier.success('已导出执行记录', hint);
    } else {
      notifier.error('暂无可导出的记录');
    }
  };

  return (
    <section className="panel-section block" id="plan-executions">
      <div className="section-head">
        <h2 style={{ margin: 0 }}>执行时间轴</h2>
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
        </div>
      </div>

      {exec.error !== '' && <p className="hint" style={{ color: '#b42318' }}>执行记录读取失败：{exec.error}</p>}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, margin: '10px 0' }}>
        {plans.length === 0 && <p className="hint">该设备还没有编排计划。</p>}
        {plans.map((plan) => {
          const item = stats.get(plan.id);
          const period = plan.triggerType === 'periodic' ? (plan.periodMs ?? 0) : 0;
          const nextIn = period > 0 && item && item.lastAt > 0
            ? Math.max(0, item.lastAt + period - now) : null;
          return (
            <div key={plan.id} style={cardStyle}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                <strong className="mono">{plan.id}</strong>
                <span className={`badge ${plan.enabled ? 'tone-info' : 'tone-muted'}`}>
                  {plan.enabled ? '已启用' : '已停用'}
                </span>
              </div>
              <div className="hint" style={{ marginTop: 6 }}>
                CEID {plan.ceid} · 累计 {item?.count ?? 0} 次
              </div>
              <div className="hint">
                下次（按周期估算）：{nextIn === null ? '条件驱动 / 暂无数据' : `${Math.ceil(nextIn / 1000)} 秒`}
              </div>
              <div className="hint" style={{ color: item?.lastError ? '#b42318' : undefined }}>
                最后状态：{item?.lastStatus ?? '—'}{item?.lastError ? ` · ${item.lastError}` : ''}
              </div>
              <button
                type="button"
                style={{ marginTop: 8 }}
                disabled={deviceId === '' || stepping !== ''}
                onClick={() => void runStep(plan.id)}
                title="触发一次，记为试跑，不推进倒计时"
              >
                {stepping === plan.id ? '试跑中…' : '单步试跑'}
              </button>
            </div>
          );
        })}
      </div>

      <SvidSparkline rows={exec.allRows} />

      <div style={{ marginTop: 10, maxHeight: 320, overflowY: 'auto' }}>
        {exec.rows.length === 0 && <p className="hint">暂无执行记录。启用计划并等待触发，或点「单步试跑」。</p>}
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
