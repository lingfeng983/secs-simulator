import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { DraftPreviewCard } from './DraftPreviewCard';
import { EventsForm } from '../editors/ConfigFormEditor';
import { isJsonObject, type JsonObject } from '../editors/templateConfig';

/**
 * 设备 · 配置区 · 事件计划（events.plans 定义）+ 草稿预览。
 *
 * <p>2026-11-16 拆分：工艺周期（cycle）独立成「工艺周期」页（CyclePanel），
 * 这里只管周期外的异常路径（events.plans）。事件只上报、不改值。</p>
 *
 * <p>2026-11-18：本页只剩"事件计划的列表 + 这张表算出来的预览"。
 * 预览按<b>草稿原文</b>（含未保存改动）做确定性重放，草稿一变（防抖 600ms）
 * 自动重算——只画事件计划这一袋标记，工艺周期的片内事件不在本页出现
 * （去「工艺周期」页看）。</p>
 */
export function PlanConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-plan-config"
      sections={['plans', 'throttle']}
      title="事件计划"
    >
      {({ root, edit, text }) => <PlanEditor root={root} edit={edit} text={text} backend={backend} />}
    </ConfigEditShell>
  );
}

export function PlanEditor({ root, edit, text, backend }: {
  root: JsonObject;
  edit: (mutate: (root: JsonObject) => void) => void;
  text: string;
  backend: PanelBaseProps['backend'];
}): JSX.Element {
  /* 预览与列表用同一份草稿原文：events.plans 有几条，就只能画几条触发线。
     运行态另开一栏，绝不混进草稿预览里。 */
  const events = isJsonObject(root.events) ? root.events : null;
  const draftPlans = events !== null && Array.isArray(events.plans)
    ? events.plans.filter(isJsonObject)
    : [];
  const planIds = draftPlans
    .map((plan) => (typeof plan.id === 'string' ? plan.id : ''))
    .filter((id) => id !== '');
  const runtimePlans = backend.eventPlans ?? [];
  return (
    <>
      <EventsForm root={root} edit={edit} />

      <section className="panel-section block">
        <div className="section-head">
          <h3>计划口径</h3>
        </div>
        <p className="hint">
          草稿 <b>{planIds.length}</b> 条（events.plans） · 当前运行 <b>{runtimePlans.length}</b> 条。
          下面的预览只画草稿里已有的计划；当前运行只是本次会话的启停状态，不写回配置。
        </p>
        {runtimePlans.length === 0 ? (
          <p className="hint">当前运行没有计划：设备可能还没启动，或这份配置还没有下发计划。</p>
        ) : (
          <table className="effects-table">
            <thead>
              <tr><th>计划（当前运行）</th><th>CEID</th><th>触发</th><th>状态</th></tr>
            </thead>
            <tbody>
              {runtimePlans.map((plan) => {
                const status = plan.status ?? (plan.enabled ? 'running' : 'stopped');
                return (
                  <tr key={plan.id}>
                    <td className="mono">{plan.id}</td>
                    <td>{plan.ceid}</td>
                    <td className="hint-inline">{plan.triggerType}</td>
                    <td>
                      <span className={`badge ${status === 'running' ? 'tone-ok' : status === 'waiting' ? 'tone-warn' : 'tone-muted'}`}>
                        {status === 'running' ? '运行中' : status === 'waiting' ? '等待上线' : '已停止'}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>

      {planIds.length === 0 ? (
        <section className="panel-section block draft-preview" aria-disabled="true" style={{ opacity: 0.6 }}>
          <div className="section-head"><h3>事件计划预览（按草稿）</h3></div>
          <p className="hint">
            草稿里还没有事件计划，预览折叠：没有可画的触发标记。先在上面添加一条计划，
            预览会自动出现；这里不画运行态或工艺周期的事件。
          </p>
        </section>
      ) : (
        <DraftPreviewCard
          backend={backend}
          root={root}
          text={text}
          marks="plans"
          planIds={planIds}
          title="事件计划预览（按草稿）"
          hint="不需要保存或启动设备：直接按当前草稿（含未保存改动）做一次确定性重放。横轴是模拟时间、纵轴是参数值，竖虚线是草稿里这些计划按触发规则会发送事件的时刻。这里只画上面列出的草稿计划，不画运行态或工艺周期的片内事件。默认只画第一个参数，需要对比时在「参数」里再勾选（多选，勾几条画几条）。"
          eventLabel="计划触发"
        />
      )}
    </>
  );
}
