import { useEffect, useMemo, useRef, useState } from 'react';
import { filterRows } from '../../shared/lib/table';
import { setPlansEnabled, type PlanBatchResult } from './eventBatch';
import type { EventPlanView } from '../../api';
import { Panel, PanelFootnote } from '../../shared/ui/Panel';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { HintTitle } from '../../shared/ui/FieldLabel';
import { EventPreview } from '../charts/EventPreview';
import { PlanExecutionTimeline } from './PlanExecutionTimeline';
import type { PreviewData } from '../../api';
import { usePlanExecutions } from '../../state/usePlanExecutions';
import type { PanelBaseProps } from './types';
import { saveHint } from '../../shared/lib/saveFile';

/** 事件编排的触发规则摘要。 */
export function triggerLabel(plan: EventPlanView): string {
  switch (plan.triggerType) {
    case 'at':
      return `一次性 @ ${plan.atMs ?? 0} ms`;
    case 'periodic':
      return `每 ${plan.periodMs ?? 0} ms${plan.jitterMs ? ` ± ${plan.jitterMs} ms` : ''}`;
    case 'onCondition':
      return `${plan.expression ?? ''}（连续 ${plan.debounceMs ?? 0} ms）`;
    case 'afterEvent':
      return `${plan.sourcePlan ?? '?'} 之后 ${plan.delayMs ?? 0} ms`;
    default:
      return plan.triggerType;
  }
}

/** 计划的运行态；后端没给 status 时按 enabled 兜底。 */
export type PlanStatus = 'stopped' | 'waiting' | 'running';

export function planStatusOf(plan: EventPlanView): PlanStatus {
  if (plan.status !== undefined) {
    return plan.status;
  }
  return plan.enabled ? 'running' : 'stopped';
}

/** 运行态徽标：停止灰、等待黄、运行绿。 */
const PLAN_STATUS_BADGE: Record<PlanStatus, { text: string; badge: string }> = {
  stopped: { text: '已停止', badge: 'badge-muted' },
  waiting: { text: '等待上线', badge: 'badge-warn' },
  running: { text: '运行中', badge: 'badge-ok' },
};

/** 等待原因说清楚：是设备没上线，还是主机把这个事件屏蔽了。 */
function planStatusHint(plan: EventPlanView): string {
  if (planStatusOf(plan) === 'stopped') {
    return '已停止：启动后按触发规则发送事件';
  }
  switch (plan.statusReason) {
    case 'offline':
      return '已启动，但设备不在线：严格 GEM 下只有 ONLINE 才能上报 S6F11。到设备清单点「上线」，或等主机发 S1F17';
    case 'ceid-disabled':
      return '已启动，但该事件被主机用 S2F37 屏蔽了：主机重新使能后自动恢复';
    default:
      return '正在按触发规则发送事件';
  }
}

/**
 * 事件执行面板（运行区）。
 *
 * <p>只做运行态操作：逐条/全部启停、手动触发、单步试跑、确定性预览。
 * 计划的定义（周期与触发规则）在配置区「事件计划」页维护；
 * 这里启停改的是运行时状态，不写回配置，重载后按配置恢复。
 * 累计次数 / 下次触发 / 最后状态来自执行台账，原来在执行时间轴的计划卡上，
 * 现在并入列表列。</p>
 */
export function EventsPanel({ backend, notifier, liveEvents }: PanelBaseProps): JSX.Element {
  const { eventPlans, catalog, activeSession } = backend;
  const enabled = eventPlans.filter((plan) => plan.enabled).length;
  /* 「已启动」和「运行中」是两件事：启动了但设备没在线，消息其实发不出去。
     运行态以后端为准；老后端不带 status 时按 enabled 兜底，别把界面弄空白。 */
  const running = eventPlans.filter((plan) => planStatusOf(plan) === 'running').length;

  /* 执行台账：累计次数 / 下次触发（3.3 起由后端 nextFireInMs 直出）/ 单步试跑 */
  const exec = usePlanExecutions(activeSession, liveEvents);
  const [stepping, setStepping] = useState('');

  const stats = useMemo(() => {
    const result = new Map<string, { count: number; lastAt: number; lastError: string; lastStatus: string }>();
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

  /* 单计划预览：只回放选中的这条计划，看它携带的效果 */
  const [planPreview, setPlanPreview] = useState<{ planId: string; data: PreviewData } | null>(null);
  const [planPreviewBusy, setPlanPreviewBusy] = useState(false);
  const [planPreviewWindowMs, setPlanPreviewWindowMs] = useState(120_000);

  const svidChoices = catalog?.svids ?? [];

  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [query, setQuery] = useState('');
  const [bulkBusy, setBulkBusy] = useState(false);
  const [progress, setProgress] = useState('');
  const [batchResult, setBatchResult] = useState<PlanBatchResult | null>(null);
  const batchLock = useRef(false);
  const generation = useRef(0);
  const sessionRef = useRef(activeSession);
  sessionRef.current = activeSession;

  useEffect(() => {
    setSelected(new Set());
    setQuery('');
    setOwnerFilter('');
    setBulkBusy(false);
    setProgress('');
    setBatchResult(null);
    batchLock.current = false;
    return () => { generation.current += 1; };
  }, [activeSession]);

  const applyEnabled = async (next: boolean, plans: readonly EventPlanView[], ids: ReadonlySet<string>) => {
    const client = backend.client;
    if (client === null || activeSession === '' || batchLock.current) return;
    const device = activeSession;
    const token = generation.current;
    const current = () => token === generation.current && sessionRef.current === device;
    batchLock.current = true;
    setBulkBusy(true);
    setBatchResult(null);
    try {
      const result = await setPlansEnabled(plans, ids, next, device,
        (sessionId, planId, enabled) => client.setEventEnabled(sessionId, planId, enabled),
        (done, total) => {
          if (current()) setProgress(`${next ? '启动' : '停止'} ${done}/${total}`);
        });
      if (!current()) return;
      setBatchResult(result);
      const summary = `成功 ${result.succeeded.length} 条，失败 ${result.failed.length} 条，跳过 ${result.skipped} 条`;
      if (result.failed.length > 0) notifier.error('计划操作部分失败', summary);
      else notifier.success('计划操作完成', summary);
      /* 刷新失败不影响已经完成的启停结果，只补一条提示，别把异常抛成未处理拒绝 */
      await backend.refreshAll().catch((error) => notifier.error('刷新计划状态失败', (error as Error).message));
    } finally {
      if (current()) {
        batchLock.current = false;
        setBulkBusy(false);
        setProgress('');
      }
    }
  };

  /* 归属过滤：多单元设备才需要；计划的归属由它引用的 CEID 决定 */
  const owners = useMemo(
    () => [...new Set(eventPlans.map((plan) => plan.owner ?? '').filter((owner) => owner !== ''))].sort(),
    [eventPlans],
  );
  const [ownerFilter, setOwnerFilter] = useState('');
  const visiblePlans = filterRows(
    ownerFilter === '' ? eventPlans : eventPlans.filter((plan) => plan.owner === ownerFilter),
    query,
    (plan) => `${plan.id} ${plan.ceid} ${plan.owner ?? ''} ${triggerLabel(plan)}`,
  );
  const selectedPlans = visiblePlans.filter((plan) => selected.has(plan.id));
  const allSelected = visiblePlans.length > 0 && selectedPlans.length === visiblePlans.length;
  const canStartSelected = selectedPlans.some((plan) => !plan.enabled);
  const canStopSelected = selectedPlans.some((plan) => plan.enabled);
  /* ONLINE 门控（2026-11-16 设计 §2.2）：启动方向要设备在线；禁用方向不受限 */
  const online = (backend.sessions.find((item) => item.id === activeSession)?.controlState ?? '')
    === 'ONLINE';

  /* 周期预览已归「工艺周期」页（2026-11-16 拆分 M4）：这里只留单计划预览。 */

  /**
   * 只看一条计划：排除其它计划的效果，回答"这个事件自己带来了什么变化"。
   */
  const loadPlanPreview = async (plan: EventPlanView) => {
    if (activeSession === '') {
      return;
    }
    setPlanPreviewBusy(true);
    try {
      const data = await backend.previewPlan(activeSession, plan.id, planPreviewWindowMs);
      if (data !== null) {
        setPlanPreview({ planId: plan.id, data });
      }
    } finally {
      setPlanPreviewBusy(false);
    }
  };

  /* 图例用符号名 */
  const svidLabels = useMemo(() => {
    const map = new Map<number, string>();
    for (const item of svidChoices) {
      map.set(item.svid, item.slot);
    }
    return map;
  }, [svidChoices]);

  const columns: Column<EventPlanView>[] = [
    {
      key: 'select', header: '选择', width: '46px', className: 'select-column',
      headerContent: (
        <input type="checkbox" aria-label="全选当前筛选结果" checked={allSelected}
          disabled={bulkBusy || visiblePlans.length === 0}
          ref={(element) => { if (element !== null) element.indeterminate = selectedPlans.length > 0 && !allSelected; }}
          onChange={() => setSelected(allSelected ? new Set() : new Set(visiblePlans.map((plan) => plan.id)))} />
      ),
      render: (plan) => (
        <input type="checkbox" aria-label={`选择计划 ${plan.id}`} checked={selected.has(plan.id)} disabled={bulkBusy}
          onChange={(event) => {
            const checked = event.target.checked;
            setSelected((previous) => {
              const next = new Set(previous);
              if (checked) next.add(plan.id);
              else next.delete(plan.id);
              return next;
            });
          }} />
      ),
    },
    { key: 'id', header: '计划', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'ceid', header: 'CEID', sortValue: (row) => row.ceid, render: (row) => row.ceid },
    {
      key: 'owner',
      header: '归属',
      sortValue: (row) => row.owner ?? '',
      render: (row) => (row.owner === undefined || row.owner === ''
        ? <span className="hint-inline">—</span>
        : <span className="badge tone-muted">{row.owner}</span>),
    },
    { key: 'trigger', header: '触发规则', sortValue: (row) => triggerLabel(row), render: (row) => triggerLabel(row) },
    {
      key: 'count',
      header: '累计次数',
      sortValue: (row) => stats.get(row.id)?.count ?? 0,
      render: (row) => {
        const item = stats.get(row.id);
        return (
          <span title={item?.lastError ? `最后错误：${item.lastError}` : undefined}>
            {item?.count ?? 0}
          </span>
        );
      },
    },
    {
      key: 'next',
      header: '下次事件',
      /* 3.3 起倒计时由后端直出（nextFireInMs = enabledAt + k×period - now）：
         会话时钟与浏览器时钟不同源，前端自己减会算错 */
      sortValue: (row) => (row.nextFireInMs !== undefined && row.nextFireInMs > 0
        ? row.nextFireInMs : Number.MAX_SAFE_INTEGER),
      render: (row) => {
        const remaining = row.nextFireInMs ?? 0;
        return (
          <span className="hint-inline">
            {remaining > 0 ? `${Math.ceil(remaining / 1000)} 秒` : '条件驱动 / 暂无数据'}
          </span>
        );
      },
    },
    {
      key: 'lastStatus',
      header: '最后状态',
      sortValue: (row) => stats.get(row.id)?.lastStatus ?? '',
      render: (row) => {
        const item = stats.get(row.id);
        if (item === undefined || item.lastStatus === '') {
          return <span className="hint-inline">—</span>;
        }
        return (
          <span
            className={`badge ${item.lastStatus === 'FAILED' ? 'tone-danger' : item.lastStatus === 'PARTIAL' ? 'tone-warn' : 'tone-info'}`}
            title={item.lastError !== '' ? item.lastError : undefined}
          >
            {item.lastStatus}
          </span>
        );
      },
    },
    {
      key: 'enabled',
      header: '状态',
      sortValue: (row) => ['stopped', 'waiting', 'running'].indexOf(planStatusOf(row)),
      render: (row) => {
        const status = planStatusOf(row);
        const badge = PLAN_STATUS_BADGE[status];
        return (
          <span className={`badge ${badge.badge}`} title={planStatusHint(row)}>
            {badge.text}
          </span>
        );
      },
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) => (
        <div className="row-actions">
          <button
            type="button"
            onClick={() => void loadPlanPreview(row)}
            disabled={activeSession === '' || planPreviewBusy}
            title="只回放这条计划：看它携带的参数效果与触发时刻"
          >
            预览
          </button>
          <button
            type="button"
            onClick={() => void runStep(row.id)}
            disabled={activeSession === '' || stepping !== ''}
            title="触发一次，记为试跑，不推进倒计时"
          >
            {stepping === row.id ? '试跑中…' : '单步试跑'}
          </button>
          <button
            type="button"
            onClick={() => void backend.trigger(activeSession, row.ceid)}
            disabled={activeSession === ''}
            title="手动触发一次"
          >
            触发
          </button>
          <button
            type="button"
            onClick={() => void applyEnabled(!row.enabled, [row], new Set([row.id]))}
            disabled={activeSession === '' || bulkBusy || (!row.enabled && !online)}
            title={!row.enabled && !online
              ? '设备离线：先上线再启动'
              : row.enabled
                ? '停止后不再触发;已排队的延迟事件会清掉'
                : '启动后立即按锚点重新计时;周期计划启动即触发一次'}
          >
            {row.enabled ? '停止' : '启动'}
          </button>
        </div>
      ),
    },
  ];

  return (
    <Panel
      id="panel-events"
      title="事件执行"
      headless
      actions={
        <>
          <button
            type="button"
            disabled={activeSession === '' || bulkBusy || eventPlans.length === 0 || !online}
            onClick={() => void applyEnabled(true, eventPlans, new Set(eventPlans.map((plan) => plan.id)))}
            title={online
              ? '启动全部计划：周期计划启动即触发一次（锚点 = 此刻）'
              : '设备离线：先上线再启动'}
          >
            {bulkBusy ? '处理中…' : '全部启动'}
          </button>
          <button
            type="button"
            disabled={activeSession === '' || bulkBusy || enabled === 0}
            onClick={() => void applyEnabled(false, eventPlans, new Set(eventPlans.map((plan) => plan.id)))}
            title="停止全部计划：已在队列里的延迟事件一并清除"
          >
            全部停止
          </button>
        </>
      }
    >
      <DataTable
        tableKey="events"
        columns={columns}
        rows={visiblePlans}
        rowKey={(row) => row.id}
        toolbar={
          <>
            <input type="search" className="table-search" value={query} disabled={bulkBusy}
              placeholder="搜索计划 / CEID / 归属 / 触发规则" aria-label="搜索计划 / CEID / 归属 / 触发规则"
              onChange={(event) => { setQuery(event.target.value); setSelected(new Set()); }} />
            {owners.length > 1 && (
              <label className="field">
                归属
                <select value={ownerFilter} disabled={bulkBusy}
                  onChange={(event) => { setOwnerFilter(event.target.value); setSelected(new Set()); }}
                  aria-label="按归属单元过滤计划">
                  <option value="">全部</option>
                  {owners.map((owner) => <option key={owner} value={owner}>{owner}</option>)}
                </select>
              </label>
            )}
          </>
        }
        toolbarEnd={
          <>
            <span className="table-selection" role="status">{bulkBusy ? progress : `已选 ${selectedPlans.length} 条`}</span>
            <button type="button" disabled={activeSession === '' || bulkBusy || !canStartSelected || !online}
              onClick={() => void applyEnabled(true, visiblePlans, selected)}>批量启动</button>
            <button type="button" disabled={activeSession === '' || bulkBusy || !canStopSelected}
              onClick={() => void applyEnabled(false, visiblePlans, selected)}>批量停止</button>
          </>
        }
        exportName="events"
        onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
        emptyText={activeSession === '' ? '暂无会话' : '该设备还没有事件计划——到配置区「事件计划」页定义'}
        exportColumns={[
          { header: '计划', value: (row) => row.id },
          { header: 'CEID', value: (row) => row.ceid },
          { header: '归属', value: (row) => row.owner ?? '' },
          { header: '触发规则', value: (row) => triggerLabel(row) },
          { header: '状态', value: (row) => PLAN_STATUS_BADGE[planStatusOf(row)].text },
        ]}
      />

      {batchResult !== null && (
        <div className="inline-note" role="status">
          成功 {batchResult.succeeded.length} 条，失败 {batchResult.failed.length} 条，跳过 {batchResult.skipped} 条
          {batchResult.failed.length > 0 && (
            <ul className="problems">
              {batchResult.failed.map((item) => <li key={item.id}>{item.id}：{item.reason}</li>)}
            </ul>
          )}
        </div>
      )}

      {/* 3.0 M4：执行明细（台账流；计划卡的信息并入上方列表列） */}
      <PlanExecutionTimeline
        deviceId={activeSession}
        liveEvents={liveEvents}
        notifier={notifier}
        exceptCycle
      />

      {/* 单计划预览：只回放这一条计划，效果归属清楚 */}
      {planPreview !== null && (
        <section className="panel-section block">
          <div className="section-head">
            <h2>
              <HintTitle text="只回放这一条计划：其它计划的效果不参与，曲线上每个跳变都由它带来。竖线的颜色按计划区分；全量视图（含工艺周期的片内事件）在配置区「工艺周期」页。">
                计划预览 · <span className="mono">{planPreview.planId}</span>
              </HintTitle>
            </h2>
            <div className="preview-controls">
              <label className="field">
                时间窗
                <select
                  value={planPreviewWindowMs}
                  onChange={(event) => setPlanPreviewWindowMs(Number(event.target.value))}
                  aria-label="计划预览时间窗"
                >
                  {[60_000, 120_000, 300_000, 600_000, 1_800_000].map((ms) => (
                    <option key={ms} value={ms}>{ms >= 60_000 ? `${ms / 60_000} 分钟` : `${ms / 1000} 秒`}</option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                disabled={planPreviewBusy}
                onClick={() => {
                  const plan = eventPlans.find((item) => item.id === planPreview.planId);
                  if (plan !== undefined) {
                    void loadPlanPreview(plan);
                  }
                }}
              >
                重算
              </button>
              <button type="button" onClick={() => setPlanPreview(null)}>收起</button>
            </div>
          </div>
          <EventPreview
            data={planPreview.data}
            height={92}
            labels={svidLabels}
            eventLabel="触发"
          />
        </section>
      )}

      <p className="hint">
        这里改的是<b>运行时状态</b>，不写回配置；重启设备会恢复配置里的编排。
      </p>

      <PanelFootnote
        items={[
          { label: '计划数', value: eventPlans.length },
          { label: '已启动', value: enabled },
          { label: '运行中', value: running, tone: running > 0 ? 'ok' : undefined },
        ]}
      />
    </Panel>
  );
}
