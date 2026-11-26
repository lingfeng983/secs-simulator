import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { EventCurveChart } from '../charts/EventCurveChart';
import { MultiSelect } from '../../shared/ui/MultiSelect';
import { HintTitle } from '../../shared/ui/FieldLabel';
import { useVisibilityInterval } from '../../state/useVisibilityInterval';
import type { PreviewData } from '../../api';

/** 图上可选的一个参数（配置草稿的 SVID、运行态目录的 SVID 都长这样）。 */
export interface PreviewParam {
  svid: number;
  slot: string;
}

/** 预览时间窗候选：左端点固定，右端点 = 时间窗长度。 */
export interface PreviewWindowOption {
  label: string;
  ms: number;
}

/** 预览窗口上限（后端 config-preview / preview 都远大于这个值，这里按最长 1 小时给）。 */
export const MAX_PREVIEW_WINDOW_MS = 3_600_000;

export const DEFAULT_PREVIEW_WINDOWS: readonly PreviewWindowOption[] = [
  { label: '30 秒', ms: 30_000 },
  { label: '60 秒', ms: 60_000 },
  { label: '2 分钟', ms: 120_000 },
  { label: '5 分钟', ms: 300_000 },
  { label: '10 分钟', ms: 600_000 },
  { label: '30 分钟', ms: 1_800_000 },
  { label: '1 小时', ms: 3_600_000 },
];

/**
 * 工艺周期的预览时间窗：多给一个「正好一个周期」的候选，并默认选它。
 *
 * <p>片内事件的重复规律跟着片长走，窗口对不上片长时，图上看不出
 * "每片都在同一相位发"。片长未知（没有 cycle 段）时退回默认 2 分钟。</p>
 */
export function cyclePreviewWindows(durationMs: number | undefined): {
  windows: readonly PreviewWindowOption[];
  defaultWindowMs: number;
} {
  if (durationMs === undefined || durationMs <= 0) {
    return { windows: DEFAULT_PREVIEW_WINDOWS, defaultWindowMs: 120_000 };
  }
  const ms = Math.min(MAX_PREVIEW_WINDOW_MS, Math.max(1_000, Math.round(durationMs)));
  if (DEFAULT_PREVIEW_WINDOWS.some((item) => item.ms === ms)) {
    return { windows: DEFAULT_PREVIEW_WINDOWS, defaultWindowMs: ms };
  }
  return {
    windows: [...DEFAULT_PREVIEW_WINDOWS, { label: `1 个周期（${(ms / 1000).toFixed(1)} 秒）`, ms }]
      .sort((left, right) => left.ms - right.ms),
    defaultWindowMs: ms,
  };
}

/**
 * 预览数据来源：怎么取一次、取到的东西跟着什么变。
 *
 * <p>配置区取的是"这份草稿重放出来会是什么样"，运行区取的是"当前这次运行
 * 过去 N 秒长什么样"——两边数据源完全不同，但图画和控件必须是同一套，
 * 所以把差异收进这个接口，外观交给 {@link PreviewCard}。</p>
 */
export interface PreviewSource {
  /** 取一次预览；返回 null 表示这次算不出来（草稿编译不过 / 会话不在）。 */
  fetch: (windowMs: number) => Promise<PreviewData | null>;
  /** 变了就重新取：草稿原文、会话 id、只看某条计划…… */
  key: string;
  /** 自动刷新间隔（毫秒）；不给 = 只在 key / 时间窗变化时重算（草稿就是这种）。 */
  refreshMs?: number;
}

export interface PreviewCardProps {
  title: string;
  /** 标题的悬浮说明：讲清这张图的口径（只画哪一袋事件）。 */
  hint: string;
  eventLabel: string;
  /**
   * 只画哪一袋事件：cycle = 工艺周期的片内事件；plans = 事件计划的触发。
   * 后端草稿预览给的本来就是两袋（cycleEvents / events），运行态预览把
   * 片内事件混在 events 里、planId 标记成 'cycle'——这里统一按口径过滤。
   */
  scope: 'cycle' | 'plans';
  /** 可选的参数（默认只画第一个）。 */
  params: readonly PreviewParam[];
  source: PreviewSource;
  windows?: readonly PreviewWindowOption[];
  defaultWindowMs?: number;
  /** 没有参数可选时的提示。 */
  emptyParamsText?: string;
  /** 算不出来时的提示（默认按"草稿编译不过"写）。 */
  invalidText?: string;
  /** 标题右侧的额外控件（运行区「只看这条计划」之类）。 */
  actions?: ReactNode;
  /**
   * 横轴刻度按"距窗口起点多久"读（运行区：右端 = 现在）；
   * 默认按时间窗内的绝对时刻（草稿预览从 0 起算）。
   */
  relativeTime?: boolean;
}

/**
 * 工艺参数 × 事件时刻的预览卡（配置区与运行区共用）。
 *
 * <p>一张图、默认只画一个参数：横轴模拟时间、纵轴参数值、竖虚线是事件的
 * 发送时刻；其余参数在「参数」下拉里勾选后叠上来（勾几条画几条）。
 * 图例可点：点一下隐藏那条曲线 / 那类竖线，再点回来。</p>
 *
 * <p>配置区传草稿数据源（改一次就重算一次），运行区传会话数据源
 * （按固定间隔刷新，跟着跑）——两边的按钮、下拉、图例、图都一样。</p>
 */
export function PreviewCard({
  title,
  hint,
  eventLabel,
  scope,
  params,
  source,
  windows = DEFAULT_PREVIEW_WINDOWS,
  defaultWindowMs = 120_000,
  emptyParamsText = '这份配置还没有可展示的变量。',
  invalidText = '草稿暂时编译不过（配置有错或还没写完），修好后预览会自动出现。',
  actions,
  relativeTime = false,
}: PreviewCardProps): JSX.Element {
  const [windowMs, setWindowMs] = useState(defaultWindowMs);
  const [preview, setPreview] = useState<PreviewData | null>(null);
  const [invalid, setInvalid] = useState(false);
  const [busy, setBusy] = useState(false);
  const [auto, setAuto] = useState(true);
  /* null = 还没动过选择器：默认只画第一个参数 */
  const [picked, setPicked] = useState<readonly number[] | null>(null);
  const seqRef = useRef(0);
  /* 用户自己选过时间窗之后就不再用默认值覆盖他 */
  const windowTouchedRef = useRef(false);

  /* 默认时间窗可能是"稍后才知道"的（运行区的片长要等配置读回来）：
     没被用户动过之前，跟着默认值走一次 */
  useEffect(() => {
    if (!windowTouchedRef.current && defaultWindowMs !== windowMs) {
      setWindowMs(defaultWindowMs);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaultWindowMs]);

  /* 时间窗被改成别的值时可能已经不在候选里（比如片长改了）：
     补一条"当前值"，免得下拉显示空白。 */
  const windowOptions = useMemo(() => {
    const list = [...windows];
    if (!list.some((item) => item.ms === windowMs)) {
      list.push({ label: `${(windowMs / 1000).toFixed(0)} 秒（当前）`, ms: windowMs });
      list.sort((left, right) => left.ms - right.ms);
    }
    return list;
  }, [windows, windowMs]);

  /* 参数被删改后：选择器里已经不存在的 SVID 不再画 */
  const shownSvids = useMemo<readonly number[]>(() => {
    if (picked === null) {
      const first = params[0];
      return first === undefined ? [] : [first.svid];
    }
    return picked.filter((svid) => params.some((item) => item.svid === svid));
  }, [picked, params]);

  const paramLabels = useMemo(() => {
    const map = new Map<number, string>();
    for (const item of params) {
      map.set(item.svid, item.slot);
    }
    return map;
  }, [params]);

  const choices = useMemo(
    () => params.map((item) => ({ value: item.svid, name: item.slot, meta: String(item.svid) })),
    [params],
  );

  /* 数据源放在 ref 里：它每次渲染都是新闭包，不能当 effect 依赖 */
  const sourceRef = useRef(source);
  sourceRef.current = source;

  const load = useCallback(async () => {
    const token = ++seqRef.current;
    setBusy(true);
    try {
      const data = await sourceRef.current.fetch(windowMs);
      /* 过期响应丢弃：草稿又变了 / 窗口已切 / 会话已换 */
      if (seqRef.current === token) {
        setPreview(data);
        setInvalid(data === null);
      }
    } catch {
      if (seqRef.current === token) {
        setPreview(null);
        setInvalid(true);
      }
    } finally {
      if (seqRef.current === token) {
        setBusy(false);
      }
    }
  }, [windowMs]);

  /* 数据源换了（草稿改了、切设备、切计划）或时间窗换了：重算一次。
     草稿是"边改边看"，晚 600ms 再算，避免每敲一个字都打一次后端。 */
  useEffect(() => {
    if (!auto) {
      return;
    }
    if (source.refreshMs !== undefined) {
      void load();
      return;
    }
    const timer = window.setTimeout(() => void load(), 600);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, auto, source.key, source.refreshMs]);

  /* 运行态：按固定节奏跟着跑（页面在后台时不打扰后端；恢复可见补跑一次，N6） */
  useVisibilityInterval(() => void load(), source.refreshMs ?? 5_000,
      auto && source.refreshMs !== undefined);

  /* 按口径过滤：周期页只看片内事件、事件计划页只看计划触发 */
  const scoped = useMemo<PreviewData | null>(() => {
    if (preview === null) {
      return null;
    }
    const events = preview.events ?? [];
    const own = scope === 'cycle'
      /* 草稿预览自带一袋 cycleEvents；运行态没有这一袋，片内事件混在 events
         里且 planId 固定是 'cycle'，退回按 planId 过滤 */
      ? (preview.cycleEvents ?? events.filter((event) => event.planId === 'cycle'))
      : events.filter((event) => event.planId !== 'cycle');
    return { ...preview, events: own };
  }, [preview, scope]);

  return (
    <section className="panel-section block draft-preview">
      <div className="section-head">
        <h3 style={{ margin: 0 }}>
          <HintTitle text={hint}>{title}</HintTitle>
        </h3>
        <div className="preview-controls">
          {actions}
          <MultiSelect
            label="参数"
            ariaLabel="选择图上展示的参数"
            options={choices}
            value={shownSvids}
            onChange={(next) => setPicked(next)}
            summaryLimit={2}
            emptyText={emptyParamsText}
          />
          <label className="field">
            时间窗
            <select
              value={windowMs}
              onChange={(event) => {
                windowTouchedRef.current = true;
                setWindowMs(Number(event.target.value));
              }}
              aria-label="预览时间窗"
            >
              {windowOptions.map((item) => <option key={item.ms} value={item.ms}>{item.label}</option>)}
            </select>
          </label>
          <label className="check-inline">
            <input type="checkbox" checked={auto} onChange={(event) => setAuto(event.target.checked)} />
            自动刷新
          </label>
          <button type="button" disabled={busy} onClick={() => void load()}>
            {busy ? '计算中…' : '重新预览'}
          </button>
        </div>
      </div>

      {invalid ? (
        <p className="hint">{invalidText}</p>
      ) : scoped === null ? (
        <p className="hint">还没有预览。</p>
      ) : params.length === 0 ? (
        <p className="hint">{emptyParamsText}</p>
      ) : shownSvids.length === 0 ? (
        <p className="hint">在「参数」里勾选至少一个参数后再看图。</p>
      ) : (
        <EventCurveChart
          data={scoped}
          svids={shownSvids}
          labels={paramLabels}
          eventLabel={eventLabel}
          /* 片内事件的 planId 统一是 'cycle'：按 CEID 归组才分得清是哪一条事件 */
          eventGrouping={scope === 'cycle' ? 'ceid' : 'plan'}
          relativeTime={relativeTime}
          height={190}
        />
      )}
    </section>
  );
}
