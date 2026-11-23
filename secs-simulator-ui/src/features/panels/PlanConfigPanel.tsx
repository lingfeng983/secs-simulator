import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { DraftPreviewCard } from './DraftPreviewCard';
import { EventsForm } from '../editors/ConfigFormEditor';
import { isJsonObject, type JsonObject } from '../editors/templateConfig';

/**
 * 设备 · 配置区 · 事件计划（events.plans 定义）+ 草稿预览。
 *
 * <p>2026-11-16 拆分：工艺周期（cycle）独立成「工艺周期」页（CyclePanel），
 * 这里只管周期外的异常路径（events.plans）。事件只上报、不改值。
 * 2026-11-20：「事件发送」三项搬到「结构配置」页的设备全局里；新建/编辑弹窗
 * 确认、删除都直接保存落盘（plans 段）。2026-11-21：「计划口径」（当前运行表格）
 * 整段删除——运行态到运行区「事件执行」页看，本页只剩计划列表与预览。</p>
 */
export function PlanConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-plan-config"
      sections={['plans']}
      title="事件计划"
      actions="none"
    >
      {({ root, edit, text, save }) => (
        <PlanEditor root={root} edit={edit} text={text} backend={backend} onMutated={() => void save()} />
      )}
    </ConfigEditShell>
  );
}

export function PlanEditor({ root, edit, text, backend, onMutated }: {
  root: JsonObject;
  edit: (mutate: (root: JsonObject) => void) => void;
  text: string;
  backend: PanelBaseProps['backend'];
  /** 每次改动落进草稿后回调（设备页传 save）。 */
  onMutated?: () => void;
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
  return (
    <>
      <EventsForm root={root} edit={edit} onMutated={onMutated} />

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
          hint="不需要启动设备：直接按当前已保存的计划做一次确定性重放。横轴是模拟时间、纵轴是参数值，竖虚线是这些计划按触发规则会发送事件的时刻。这里只画上面列出的计划，不画运行态或工艺周期的片内事件。默认只画第一个参数，需要对比时在「参数」里再勾选（多选，勾几条画几条）。"
          eventLabel="计划触发"
        />
      )}
    </>
  );
}
