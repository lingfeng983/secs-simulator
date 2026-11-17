import type { PanelBaseProps } from './types';
import { CycleRunCard } from './CyclePanel';
import { PlanExecutionTimeline } from './PlanExecutionTimeline';

/**
 * 设备 · 运行区 · 工艺周期（2026-11-17 运行区补齐）：
 * 运行卡（启停/片号/相位/已发事件）+ 片内事件台账（planId=cycle 过滤）。
 *
 * <p>节拍参数与片内事件的定义在配置区「工艺周期」页；本页只管这次运行——
 * 启动/停止走 ONLINE 门控（§2.2），全停事件（下线/钉值/S2F15 写常量）会把
 * 周期打回停止，需要手动重新启动。</p>
 */
export function CycleRuntimePanel({ backend, notifier, liveEvents }: PanelBaseProps): JSX.Element {
  const deviceId = backend.activeSession;
  return (
    <div className="panel-stack">
      <CycleRunCard backend={backend} notifier={notifier} deviceId={deviceId} />
      <PlanExecutionTimeline deviceId={deviceId} liveEvents={liveEvents} notifier={notifier} planId="cycle" />
    </div>
  );
}
