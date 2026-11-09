import { useMemo, useState } from 'react';
import type { EventEffectInput, EventPlanInput, EventPlanView } from '../../api';
import { Panel, PanelFootnote } from '../../shared/ui/Panel';
import { ConfirmModal } from '../../shared/ui/ConfirmModal';
import { Modal } from '../../shared/ui/Modal';
import { DataTable, type Column } from '../../shared/ui/DataTable';
import { EventPreview } from '../charts/EventPreview';
import type { PreviewData } from '../../api';
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

/** 触发类型选项(与后端 Trigger 的四种实现一一对应)。 */
const TRIGGER_TYPES = [
  ['periodic', '周期'],
  ['at', '一次性'],
  ['onCondition', '条件'],
  ['afterEvent', '事件链'],
] as const;

const OPS = ['set', 'add', 'shift', 'pulse', 'resume'] as const;

const EMPTY_PLAN: EventPlanInput = {
  id: '',
  ceid: 2001,
  triggerType: 'periodic',
  periodMs: 1000,
  jitterMs: 0,
  atMs: 0,
  expression: '',
  debounceMs: 0,
  sourcePlan: '',
  delayMs: 0,
  effects: [],
};

/**
 * 事件编排面板。
 *
 * <p>从「事件设置」页已定义的事件里选 CEID 做计划:<b>新增 / 编辑 / 删除</b>
 * (含效果)、启停、手动触发。这些改的是<b>运行时状态</b>,不写回配置;
 * 重载会话后按配置恢复。</p>
 */
export function EventsPanel({ backend, notifier }: PanelBaseProps): JSX.Element {
  const { eventPlans, catalog, activeSession } = backend;
  const enabled = eventPlans.filter((plan) => plan.enabled).length;

  /* 计划编辑 */
  const [planEditorOpen, setPlanEditorOpen] = useState(false);
  const [editingPlanId, setEditingPlanId] = useState<string | null>(null);
  const [planDraft, setPlanDraft] = useState<EventPlanInput>(EMPTY_PLAN);
  const [planBusy, setPlanBusy] = useState(false);
  const [deletePlanId, setDeletePlanId] = useState<string | null>(null);

  /* 事件周期预览:看一个完整周期里所有参数怎么变 */
  const [cyclePreview, setCyclePreview] = useState<PreviewData | null>(null);
  /* 默认 120s：60s 周期的计划在 30s 窗口里一个都看不到 */
  const [cycleWindowMs, setCycleWindowMs] = useState(120_000);
  const [cycleBusy, setCycleBusy] = useState(false);
  /* 单计划预览：只回放选中的这条计划，看它携带的效果 */
  const [planPreview, setPlanPreview] = useState<{ planId: string; data: PreviewData } | null>(null);
  const [planPreviewBusy, setPlanPreviewBusy] = useState(false);
  const [planPreviewWindowMs, setPlanPreviewWindowMs] = useState(120_000);

  const ceidChoices = catalog?.ceids ?? [];
  const svidChoices = catalog?.svids ?? [];

  /* 归属过滤：多单元设备才需要；计划的归属由它引用的 CEID 决定 */
  const owners = useMemo(
    () => [...new Set(eventPlans.map((plan) => plan.owner ?? '').filter((owner) => owner !== ''))].sort(),
    [eventPlans],
  );
  const [ownerFilter, setOwnerFilter] = useState('');
  const visiblePlans = ownerFilter === ''
    ? eventPlans
    : eventPlans.filter((plan) => plan.owner === ownerFilter);

  /** 编辑中的计划归属：跟随所选 CEID，计划本身不维护归属字段。 */
  const draftOwner = ceidChoices.find((choice) => choice.ceid === planDraft.ceid)?.owner ?? '';

  /**
   * 预览一个完整事件周期。
   *
   * <p>按当前编排做一次确定性模拟,画出所有变量在窗口内的变化,
   * 并把发送事件的时刻标在图上——这是"参数在事件作用下怎么变"的唯一
   * 可视化方式。</p>
   */
  const loadCyclePreview = async () => {
    if (activeSession === '') {
      return;
    }
    setCycleBusy(true);
    try {
      const data = await backend.previewAll(activeSession, cycleWindowMs);
      if (data !== null) {
        setCyclePreview(data);
      }
    } finally {
      setCycleBusy(false);
    }
  };

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
    { key: 'id', header: '计划', sortValue: (row) => row.id, render: (row) => <span className="mono">{row.id}</span> },
    { key: 'ceid', header: 'CEID', align: 'right', sortValue: (row) => row.ceid, render: (row) => row.ceid },
    {
      key: 'owner',
      header: '归属',
      sortValue: (row) => row.owner ?? '',
      render: (row) => (row.owner === undefined || row.owner === ''
        ? <span className="hint-inline">—</span>
        : <span className="badge tone-muted">{row.owner}</span>),
    },
    { key: 'trigger', header: '触发规则', sortValue: (row) => triggerLabel(row), render: (row) => triggerLabel(row) },
    { key: 'effects', header: '效果', sortValue: (row) => row.effects?.length ?? 0, render: (row) => (row.effects?.length ?? 0) > 0 ? `${row.effects!.length} 项` : '—' },
    {
      key: 'enabled',
      header: '状态',
      sortValue: (row) => (row.enabled ? 1 : 0),
      render: (row) => (
        <span className={`badge badge-${row.enabled ? 'ok' : 'muted'}`}>{row.enabled ? '启用' : '禁用'}</span>
      ),
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
            onClick={() => void backend.trigger(activeSession, row.ceid)}
            disabled={activeSession === ''}
            title="手动触发一次"
          >
            触发
          </button>
          <button
            type="button"
            onClick={() => void backend.toggleEvent(row)}
            disabled={activeSession === ''}
            title={row.enabled ? '禁用后不再触发;已排队的延迟事件会清掉' : '重新启用;禁用期间的事件不补发'}
          >
            {row.enabled ? '禁用' : '启用'}
          </button>
          <button type="button" onClick={() => openPlanEditor(row)} title="编辑计划(触发规则与效果)">
            编辑
          </button>
          <button type="button" className="danger" onClick={() => setDeletePlanId(row.id)} title="删除计划">
            删除
          </button>
        </div>
      ),
    },
  ];

  const openPlanEditor = (plan?: EventPlanView) => {
    if (plan === undefined) {
      setEditingPlanId(null);
      setPlanDraft({ ...EMPTY_PLAN, id: `plan-${Date.now()}` });
    } else {
      setEditingPlanId(plan.id);
      setPlanDraft({
        id: plan.id,
        ceid: plan.ceid,
        triggerType: (plan.triggerType as EventPlanInput['triggerType']) ?? 'periodic',
        atMs: plan.atMs ?? 0,
        periodMs: plan.periodMs ?? 1000,
        jitterMs: plan.jitterMs ?? 0,
        expression: plan.expression ?? '',
        debounceMs: plan.debounceMs ?? 0,
        sourcePlan: plan.sourcePlan ?? '',
        delayMs: plan.delayMs ?? 0,
        effects: (plan.effects ?? []).map((effect) => ({ ...effect })),
      });
    }
    setPlanEditorOpen(true);
  };

  const savePlan = async () => {
    if (planDraft.id.trim() === '') {
      notifier.error('计划标识不能为空');
      return;
    }
    setPlanBusy(true);
    try {
      const ok = editingPlanId === null
        ? await backend.addEventPlan({ ...planDraft, id: planDraft.id.trim() })
        : await backend.updateEventPlan(editingPlanId, { ...planDraft, id: editingPlanId });
      if (ok) {
        setPlanEditorOpen(false);
      }
    } finally {
      setPlanBusy(false);
    }
  };



  const svidHint = useMemo(
    () => new Map(svidChoices.map((choice) => [choice.svid, `${choice.owner}.${choice.slot}`])),
    [svidChoices],
  );


  return (
    <Panel
      id="panel-events"
      title="事件编排"
      description="引用「事件设置」里已定义的 CEID 做计划:增删改、启停、手动触发。运行时改动不写回配置。"
      actions={
        <>
          <button
            type="button"
            disabled={activeSession === '' || cycleBusy}
            onClick={() => void loadCyclePreview()}
            title="按当前编排做一次确定性模拟：看所有变量在一个周期内的变化，事件时刻标在图上"
          >
            {cycleBusy ? '模拟中…' : '事件周期预览'}
          </button>
          <button
            type="button"
            className="primary"
            disabled={activeSession === ''}
            onClick={() => openPlanEditor(undefined)}
            title="新增一条编排计划"
          >
            新增计划
          </button>
        </>
      }
    >
      <DataTable
        tableKey="events"
        columns={columns}
        rows={visiblePlans}
        rowKey={(row) => row.id}
        searchPlaceholder="搜索计划 / CEID / 归属 / 触发规则"
        toolbar={owners.length > 1 ? (
          <label className="field">
            归属
            <select
              value={ownerFilter}
              onChange={(event) => setOwnerFilter(event.target.value)}
              aria-label="按归属单元过滤计划"
            >
              <option value="">全部</option>
              {owners.map((owner) => <option key={owner} value={owner}>{owner}</option>)}
            </select>
          </label>
        ) : undefined}
        exportName="events"
        onExported={(result) => { const hint = saveHint(result); if (hint !== '') { notifier.success('已导出', hint); } }}
        emptyText={activeSession === '' ? '暂无会话' : '该设备还没有编排计划——点「新增计划」创建'}
        exportColumns={[
          { header: '计划', value: (row) => row.id },
          { header: 'CEID', value: (row) => row.ceid },
          { header: '归属', value: (row) => row.owner ?? '' },
          { header: '触发规则', value: (row) => triggerLabel(row) },
          { header: '状态', value: (row) => (row.enabled ? '启用' : '禁用') },
        ]}
      />
      {/* 单计划预览：只回放这一条计划，效果归属清楚 */}
      {planPreview !== null && (
        <section className="panel-section block">
          <div className="section-head">
            <h2>计划预览 · <span className="mono">{planPreview.planId}</span></h2>
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
          <p className="hint">
            只回放<b>这一条计划</b>：其它计划的效果不参与，曲线上每个跳变都由它带来。
            竖线的颜色按计划区分，与上方「事件周期预览」的全量视图一致。
          </p>
          <EventPreview
            data={planPreview.data}
            height={92}
            labels={svidLabels}
            eventLabel="触发"
          />
        </section>
      )}

      {/* 事件周期预览:所有参数在编排作用下怎么变 */}
      {cyclePreview !== null && (
        <section className="panel-section block">
          <div className="section-head">
            <h2>事件周期预览</h2>
            <div className="preview-controls">
              <label className="field">
                周期长度
                <select
                  value={cycleWindowMs}
                  onChange={(event) => setCycleWindowMs(Number(event.target.value))}
                  aria-label="事件周期长度"
                >
                  {[30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000].map((ms) => (
                    <option key={ms} value={ms}>{ms >= 60_000 ? `${ms / 60_000} 分钟` : `${ms / 1000} 秒`}</option>
                  ))}
                </select>
              </label>
              <button type="button" disabled={cycleBusy} onClick={() => void loadCyclePreview()}>
                {cycleBusy ? '模拟中…' : '重算'}
              </button>
              <button type="button" onClick={() => setCyclePreview(null)}>收起</button>
            </div>
          </div>
          <p className="hint">
            一次确定性模拟的结果：横轴是时间，纵轴是各状态变量的值。
            不同颜色的竖线代表不同计划/CEID 的触发时刻，颜色与下方图例一一对应——
            竖线之后的曲线变化就是该事件的效果（set / pulse / shift / add 依次叠加）。
            只想看某一条计划时，用列表行的「预览」按钮。
          </p>
          <EventPreview
            data={cyclePreview}
            height={92}
            labels={svidLabels}
            eventLabel="发送事件"
          />
        </section>
      )}

      <p className="hint">
        这里改的是<b>运行时状态</b>，不写回配置；重载会话会恢复配置里的编排。
      </p>

      <PanelFootnote
        items={[
          { label: '计划数', value: eventPlans.length },
          { label: '启用中', value: enabled, tone: enabled > 0 ? 'ok' : undefined },
        ]}
      />

      {/* 计划编辑弹窗 */}
      <Modal
        open={planEditorOpen}
        title={editingPlanId === null ? '新增编排计划' : `编辑计划 ${editingPlanId}`}
        width="min(64rem, 96vw)"
        onClose={() => setPlanEditorOpen(false)}
        footer={
          <>
            <button type="button" onClick={() => setPlanEditorOpen(false)}>取消</button>
            <button type="button" className="primary" disabled={planBusy} onClick={() => void savePlan()}>保存</button>
          </>
        }
      >
        <div className="form-row">
          <label className="field">
            计划标识
            <input
              value={planDraft.id}
              onChange={(event) => setPlanDraft({ ...planDraft, id: event.target.value })}
              disabled={editingPlanId !== null}
              placeholder="如 plan-1"
            />
          </label>
          <label className="field">
            CEID
            <select
              value={planDraft.ceid}
              onChange={(event) => setPlanDraft({ ...planDraft, ceid: Number(event.target.value) || 1 })}
            >
              {ceidChoices.length === 0 && <option value={planDraft.ceid}>{planDraft.ceid}（目录为空）</option>}
              {ceidChoices.map((choice) => (
                <option key={choice.ceid} value={choice.ceid}>
                  {choice.ceid} · {choice.owner === '' ? choice.slot : `${choice.owner}.${choice.slot}`}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            触发类型
            <select
              value={planDraft.triggerType}
              onChange={(event) => setPlanDraft({ ...planDraft, triggerType: event.target.value as EventPlanInput['triggerType'] })}
            >
              {TRIGGER_TYPES.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
        </div>
        <p className="hint">
          归属：{draftOwner === '' ? '所选 CEID 不在目录里（先到「事件」页定义）' : draftOwner}
          （由 CEID 决定，计划不单独维护归属）
        </p>
        <div className="form-row">
          {planDraft.triggerType === 'periodic' && (
            <>
              <label className="field">
                周期 ms
                <input
                  type="number"
                  min={1}
                  value={planDraft.periodMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, periodMs: Number(event.target.value) || 1 })}
                />
              </label>
              <label className="field">
                抖动 ms
                <input
                  type="number"
                  min={0}
                  value={planDraft.jitterMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, jitterMs: Number(event.target.value) || 0 })}
                />
              </label>
            </>
          )}
          {planDraft.triggerType === 'at' && (
            <label className="field">
              触发时刻 ms（相对仿真起点）
              <input
                type="number"
                min={0}
                value={planDraft.atMs}
                onChange={(event) => setPlanDraft({ ...planDraft, atMs: Number(event.target.value) || 0 })}
              />
            </label>
          )}
          {planDraft.triggerType === 'onCondition' && (
            <>
              <label className="field field-wide">
                条件表达式
                <input
                  title="变量支持 t 与 svid.<id>"
                  value={planDraft.expression}
                  onChange={(event) => setPlanDraft({ ...planDraft, expression: event.target.value })}
                  placeholder='如 svid.1001 > 80'
                />
              </label>
              <label className="field">
                去抖 ms
                <input
                  type="number"
                  min={0}
                  value={planDraft.debounceMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, debounceMs: Number(event.target.value) || 0 })}
                />
              </label>
            </>
          )}
          {planDraft.triggerType === 'afterEvent' && (
            <>
              <label className="field">
                源计划
                <input
                  value={planDraft.sourcePlan}
                  onChange={(event) => setPlanDraft({ ...planDraft, sourcePlan: event.target.value })}
                />
              </label>
              <label className="field">
                延迟 ms
                <input
                  type="number"
                  min={0}
                  value={planDraft.delayMs}
                  onChange={(event) => setPlanDraft({ ...planDraft, delayMs: Number(event.target.value) || 0 })}
                />
              </label>
            </>
          )}
        </div>

        {/* 效果编辑 */}
        <div className="section-head">
          <h4 style={{ margin: 0 }}>触发效果</h4>
          <button
            type="button"
            onClick={() => setPlanDraft({
              ...planDraft,
              effects: [...(planDraft.effects ?? []), { svid: svidChoices[0]?.svid ?? 1001, op: 'set', value: 0 }],
            })}
          >
            + 效果
          </button>
        </div>
        {(planDraft.effects ?? []).length === 0 && <p className="hint">无效果:只上报,不改变量的值。</p>}
        {(planDraft.effects ?? []).length > 0 && (
          <div className="effects-table-wrap">
            <table className="effects-table">
              <thead>
                <tr>
                  <th>SVID</th>
                  <th>类型</th>
                  <th>值 / 增量</th>
                  <th>保持 / 衰减</th>
                  <th aria-label="操作" />
                </tr>
              </thead>
              <tbody>
                {(planDraft.effects ?? []).map((effect, index) => {
                  const update = (patch: Partial<EventEffectInput>) => {
                    const effects = [...planDraft.effects!];
                    effects[index] = { ...effect, ...patch };
                    setPlanDraft({ ...planDraft, effects });
                  };
                  return (
                    <tr key={index}>
                      <td>
                        <input
                          className="input-mini mono"
                          type="number"
                          min={1}
                          value={effect.svid}
                          onChange={(event) => update({ svid: Number(event.target.value) || 1 })}
                          aria-label={`第 ${index + 1} 项 SVID`}
                        />
                        <span className="hint-inline">{svidHint.get(effect.svid) ?? ''}</span>
                      </td>
                      <td>
                        <select
                          value={effect.op}
                          onChange={(event) => update({ op: event.target.value as EventEffectInput['op'] })}
                          aria-label={`第 ${index + 1} 项类型`}
                        >
                          {OPS.map((op) => <option key={op} value={op}>{op}</option>)}
                        </select>
                      </td>
                      <td>
                        {(effect.op === 'set' || effect.op === 'pulse') ? (
                          <input
                            className="input-mini"
                            type="number"
                            value={effect.value ?? 0}
                            onChange={(event) => update({ value: Number(event.target.value) })}
                            aria-label={`第 ${index + 1} 项值`}
                          />
                        ) : effect.op === 'add' || effect.op === 'shift' ? (
                          <input
                            className="input-mini"
                            type="number"
                            value={effect.delta ?? 0}
                            onChange={(event) => update({ delta: Number(event.target.value) })}
                            aria-label={`第 ${index + 1} 项增量`}
                          />
                        ) : (
                          <span className="hint-inline">—</span>
                        )}
                      </td>
                      <td>
                        {effect.op === 'pulse' ? (
                          <input
                            className="input-mini"
                            type="number"
                            min={0}
                            value={effect.holdMs ?? 1000}
                            onChange={(event) => update({ holdMs: Number(event.target.value) || 0 })}
                            aria-label={`第 ${index + 1} 项保持时长`}
                          />
                        ) : effect.op === 'set' ? (
                          <input
                            className="input-mini"
                            type="number"
                            min={0}
                            value={effect.decayMs ?? 0}
                            onChange={(event) => update({ decayMs: Number(event.target.value) || undefined })}
                            aria-label={`第 ${index + 1} 项衰减时长`}
                          />
                        ) : (
                          <span className="hint-inline">—</span>
                        )}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="danger"
                          onClick={() => setPlanDraft({ ...planDraft, effects: planDraft.effects!.filter((_, i) => i !== index) })}
                          title="移除这条效果"
                        >
                          移除
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Modal>

      {/* 删除确认 */}
      <ConfirmModal
        open={deletePlanId !== null}
        title="删除编排计划"
        danger
        confirmLabel="删除"
        message={deletePlanId === null ? '' : `确定删除计划「${deletePlanId}」?其待触发队列与去抖状态一并清除。`}
        onClose={() => setDeletePlanId(null)}
        onConfirm={() => {
          if (deletePlanId !== null) {
            void backend.deleteEventPlan(deletePlanId);
          }
          setDeletePlanId(null);
        }}
      />
    </Panel>
  );
}
