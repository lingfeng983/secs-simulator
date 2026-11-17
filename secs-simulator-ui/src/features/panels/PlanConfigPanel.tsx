import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { DraftPreviewCard } from './DraftPreviewCard';
import { EventsForm } from '../editors/ConfigFormEditor';
import { collectSvids, type JsonObject } from '../editors/templateConfig';

/** 预览时间窗候选（草稿是确定性重放，右端点相对模拟时钟 0）。 */
const WINDOWS: ReadonlyArray<{ label: string; ms: number }> = [
  { label: '30 秒', ms: 30_000 },
  { label: '60 秒', ms: 60_000 },
  { label: '2 分钟', ms: 120_000 },
  { label: '5 分钟', ms: 300_000 },
];

/**
 * 设备 · 配置区 · 事件计划（events.plans 定义）+ 草稿预览。
 *
 * <p>2026-11-16 拆分：工艺周期（cycle）独立成「工艺周期」页（CyclePanel），
 * 这里只管周期外的异常路径（events.plans）。事件只上报、不改值。</p>
 *
 * <p>下方草稿预览按<b>草稿原文</b>（含未保存改动）做确定性重放，草稿一变
 * （防抖 600ms）自动重算——只画事件计划这一袋标记。</p>
 */
export function PlanConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-plan-config"
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
  return (
    <>
      <EventsForm root={root} edit={edit} />
      <DraftPreviewCard
        backend={backend}
        root={root}
        text={text}
        marks="plans"
        title="事件计划预览（按草稿）"
        hint="不需要保存或启动设备：直接按当前草稿（含未保存改动）做一次确定性重放。横轴是模拟时间，竖线是各计划按触发规则会发送事件的时刻——边改周期与触发规则边看曲线变化。只显示事件计划的触发时刻（工艺周期的片内事件在「工艺周期」页）。"
        eventLabel="计划触发"
      />
    </>
  );
}
