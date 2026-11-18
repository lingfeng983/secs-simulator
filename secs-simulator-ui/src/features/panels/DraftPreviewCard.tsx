import { useMemo } from 'react';
import type { PanelBaseProps } from './types';
import { collectSvids, isJsonObject, numberField, type JsonObject } from '../editors/templateConfig';
import { PreviewCard, cyclePreviewWindows } from './PreviewCard';

/**
 * 草稿预览卡（确定性重放，不需要保存或启动设备）。
 *
 * <p>看相与运行区的预览卡是同一个组件（{@link PreviewCard}）：一张图、
 * 默认只画第一个参数，其余参数在下拉里勾选后叠上来。这里只负责"草稿"
 * 这一侧的事——变量从草稿原文里取、时间窗跟着片长走、数据源是 config-preview。</p>
 *
 * <p>口径按页分开：工艺周期页只画片内事件（{@code cycleEvents}），
 * 事件计划页只画计划触发（{@code events}）。</p>
 */
export function DraftPreviewCard({ backend, root, text, marks, title, hint, eventLabel }: {
  backend: PanelBaseProps['backend'];
  root: JsonObject;
  text: string;
  /** 取哪一袋标记：cycle = 片内事件；plans = 事件计划触发。 */
  marks: 'cycle' | 'plans';
  title: string;
  hint: string;
  eventLabel: string;
}): JSX.Element {
  const params = useMemo(() => collectSvids(root), [root]);

  /* 工艺周期页：时间窗默认"正好一个周期"，跟着草稿里的片长走 */
  const cycleWindow = useMemo(
    () => cyclePreviewWindows(marks === 'cycle' ? cycleDurationOf(root) : undefined),
    [marks, root],
  );

  const client = backend.client;

  return (
    <PreviewCard
      title={title}
      hint={hint}
      eventLabel={eventLabel}
      scope={marks}
      params={params}
      windows={cycleWindow.windows}
      defaultWindowMs={cycleWindow.defaultWindowMs}
      emptyParamsText="这份配置还没有可展示的变量：先到「变量配置」页定义 SVID。"
      source={{
        key: text,
        fetch: async (windowMs) => (client === null
          ? null
          : client.previewConfigDraft(text, 0, windowMs, 120, true)),
      }}
    />
  );
}

/** 草稿里的片长（毫秒）；没有 cycle 段或没写片长时返回 undefined。 */
function cycleDurationOf(root: JsonObject): number | undefined {
  const cycle = root.cycle;
  if (!isJsonObject(cycle)) {
    return undefined;
  }
  const durationMs = numberField(cycle, 'durationMs') ?? 0;
  return durationMs > 0 ? durationMs : undefined;
}
