import { useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import type { PreviewEventMark, PreviewSeries } from '../api';
import { formatValue } from '../lib/format';
import { eventColorIndexes, eventColorVar, eventKey } from '../lib/eventColors';
import { useThemeVersion } from '../state/useThemeVersion';

/** 预览图里选中的桶区间；跨所有 SVID 共享，便于对齐读数。 */
export interface ZoomRange {
  start: number;
  end: number;
}

export interface SparklineProps {
  series: PreviewSeries;
  fromMs: number;
  bucketMs: number;
  /** 缩放区间;不传即整窗(采集卡等简单场景用)。 */
  zoom?: ZoomRange | null;
  /** 缩放回调;不传则禁用拖动缩放。 */
  onZoom?: (zoom: ZoomRange | null) => void;
  /** 窗口内的事件标记(v6):在对应桶画竖线,悬停可读 CEID。 */
  events?: PreviewEventMark[];
  /** 画布高度(px);默认 72。 */
  height?: number;
  /** 是否在左侧打印 SVID/归属;外层标题已经写了就关掉,免得同一行出现两次。 */
  showId?: boolean;
}

export function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

/** 曲线用色:全部来自 CSS 令牌,深浅两套自动跟随。 */
interface ChartPalette {
  accent: string;
  grid: string;
  cursor: string;
  event: string;
}

function readChartPalette(canvas: HTMLCanvasElement): ChartPalette {
  const styles = getComputedStyle(canvas);
  const read = (name: string, fallback: string): string => {
    const value = styles.getPropertyValue(name).trim();
    return value === '' ? fallback : value;
  };
  return {
    accent: read('--accent', '#0f766e'),
    grid: read('--line', '#dde4ed'),
    cursor: read('--muted', '#5f6f85'),
    event: read('--warn', '#b45309'),
  };
}

/** 把缩放区间夹到合法范围；桶数不足时退化成单点。 */
export function clampZoom(zoom: ZoomRange | null, count: number): { start: number; end: number } {
  if (count <= 1) {
    return { start: 0, end: 0 };
  }
  const start = clamp(zoom?.start ?? 0, 0, count - 1);
  const end = clamp(zoom?.end ?? count - 1, start + 1, count - 1);
  return { start, end };
}

/** 秒数：超过一分钟用 m+s。 */
export function formatSeconds(seconds: number): string {
  if (seconds >= 60) {
    const minutes = Math.floor(seconds / 60);
    return `${minutes}m${(seconds - minutes * 60).toFixed(1)}s`;
  }
  return `${seconds.toFixed(2)}s`;
}

/**
 * 单条 SVID 的曲线：min/max 包络 + avg 线。
 *
 * <p>悬停读数与拖动放大都在浏览器里做：预览数据已经按桶取回，
 * 放大只是换一个可见桶区间，不需要再打一次后端。</p>
 *
 * <p>画布按设备像素比放大再由 CSS 缩回去：
 * 直接用 CSS 尺寸当画布尺寸的话，高分屏上线条是糊的。</p>
 */
export function Sparkline({ series, fromMs, bucketMs, zoom = null, onZoom, events, height = 72, showId = true }: SparklineProps): JSX.Element {
  const ref = useRef<HTMLCanvasElement | null>(null);
  /* 主题切换时重画:曲线取的是 CSS 令牌,不重画就会停在旧配色上 */
  const themeVersion = useThemeVersion();
  const dragAnchor = useRef<number | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [drag, setDrag] = useState<ZoomRange | null>(null);

  const count = series.avg.length;
  const visible = clampZoom(zoom, count);
  const viewStart = visible.start;
  const viewEnd = visible.end;

  /* 事件标记映射到桶下标:窗口外丢弃；颜色按计划/CEID 区分 */
  const eventColors = useMemo(() => eventColorIndexes(events ?? []), [events]);
  const eventBuckets = useMemo(() => {
    if (events === undefined || events.length === 0) {
      return [] as { bucket: number; ceid: number; colorVar: string }[];
    }
    return events
      .filter((event) => event.at >= fromMs && event.at <= fromMs + bucketMs * count)
      .map((event) => ({
        bucket: Math.min(count - 1, Math.max(0, Math.floor((event.at - fromMs) / bucketMs))),
        ceid: event.ceid,
        colorVar: eventColorVar(eventColors.get(eventKey(event)) ?? 1),
      }));
  }, [events, eventColors, fromMs, bucketMs, count]);

  useEffect(() => {
    const canvas = ref.current;
    const context = canvas?.getContext('2d');
    if (canvas == null || context == null) {
      return;
    }

    /* 按显示尺寸 × 设备像素比设置画布，CSS 再缩回显示尺寸 */
    const rect = canvas.getBoundingClientRect();
    const ratio = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    const displayWidth = Math.max(1, Math.round(rect.width || 760));
    const displayHeight = height;
    const pixelWidth = Math.round(displayWidth * ratio);
    const pixelHeight = Math.round(displayHeight * ratio);
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
      canvas.width = pixelWidth;
      canvas.height = pixelHeight;
    }
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, displayWidth, displayHeight);

    if (count === 0) {
      return;
    }

    let low = Number.POSITIVE_INFINITY;
    let high = Number.NEGATIVE_INFINITY;
    for (let index = viewStart; index <= viewEnd; index += 1) {
      low = Math.min(low, series.min[index]);
      high = Math.max(high, series.max[index]);
    }
    if (!Number.isFinite(low) || !Number.isFinite(high)) {
      return;
    }
    if (!(high > low)) {
      high = low + 1;
    }

    const span = Math.max(1, viewEnd - viewStart);
    const x = (index: number) => ((index - viewStart) / span) * (displayWidth - 1);
    const y = (value: number) => displayHeight - 2 - ((value - low) / (high - low)) * (displayHeight - 4);

    /* 取色一律走 CSS 令牌:曲线要跟界面同一套颜色,而不是写死一个蓝 */
    const palette = readChartPalette(canvas);

    /* 三条水平参考线：没有刻度也要让人看出"高/中/低" */
    context.globalAlpha = 0.75;
    context.strokeStyle = palette.grid;
    context.lineWidth = 1;
    for (let line = 0; line <= 3; line += 1) {
      const at = 2 + (line / 3) * (displayHeight - 4);
      context.beginPath();
      context.moveTo(0, at);
      context.lineTo(displayWidth, at);
      context.stroke();
    }
    context.globalAlpha = 1;

    context.beginPath();
    for (let index = viewStart; index <= viewEnd; index += 1) {
      const value = series.max[index];
      if (index === viewStart) {
        context.moveTo(x(index), y(value));
      } else {
        context.lineTo(x(index), y(value));
      }
    }
    for (let index = viewEnd; index >= viewStart; index -= 1) {
      context.lineTo(x(index), y(series.min[index]));
    }
    context.closePath();
    context.globalAlpha = 0.16;
    context.fillStyle = palette.accent;
    context.fill();
    context.globalAlpha = 1;

    context.beginPath();
    for (let index = viewStart; index <= viewEnd; index += 1) {
      const value = series.avg[index];
      if (index === viewStart) {
        context.moveTo(x(index), y(value));
      } else {
        context.lineTo(x(index), y(value));
      }
    }
    context.strokeStyle = palette.accent;
    context.lineWidth = 1.5;
    context.lineJoin = 'round';
    context.stroke();

    /* 事件标记(v6):每个计划一种颜色的虚线竖线 + 顶部小三角。
       同图多计划时，颜色是唯一的归属线索。 */
    if (eventBuckets.length > 0) {
      context.save();
      context.globalAlpha = 0.85;
      context.setLineDash([3, 3]);
      context.lineWidth = 1;
      const styles = getComputedStyle(canvas);
      const resolved = new Map<string, string>();
      const colorOf = (name: string): string => {
        const cached = resolved.get(name);
        if (cached !== undefined) {
          return cached;
        }
        const value = styles.getPropertyValue(name).trim();
        const color = value === '' ? palette.event : value;
        resolved.set(name, color);
        return color;
      };
      for (const marker of eventBuckets) {
        if (marker.bucket < viewStart || marker.bucket > viewEnd) {
          continue;
        }
        const color = colorOf(marker.colorVar);
        context.strokeStyle = color;
        context.fillStyle = color;
        const atX = x(marker.bucket);
        context.beginPath();
        context.moveTo(atX, 0);
        context.lineTo(atX, displayHeight);
        context.stroke();
        context.setLineDash([]);
        context.beginPath();
        context.moveTo(atX - 3, 0);
        context.lineTo(atX + 3, 0);
        context.lineTo(atX, 5);
        context.closePath();
        context.fill();
        context.setLineDash([3, 3]);
      }
      context.restore();
      context.globalAlpha = 1;
    }

    if (drag !== null) {
      const from = Math.min(drag.start, drag.end);
      const to = Math.max(drag.start, drag.end);
      const left = clamp(x(from), 0, displayWidth);
      const right = clamp(x(to), 0, displayWidth);
      context.globalAlpha = 0.12;
      context.fillStyle = palette.accent;
      context.fillRect(left, 0, Math.max(1, right - left), displayHeight);
      context.globalAlpha = 1;
    }

    if (hover !== null) {
      const index = clamp(hover, viewStart, viewEnd);
      const atX = x(index);
      context.strokeStyle = palette.cursor;
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(atX, 0);
      context.lineTo(atX, displayHeight);
      context.stroke();

      context.fillStyle = palette.accent;
      context.beginPath();
      context.arc(atX, y(series.avg[index]), 3, 0, Math.PI * 2);
      context.fill();
    }
  }, [series, hover, drag, viewStart, viewEnd, count, eventBuckets, height, themeVersion]);

  const bucketAt = (event: ReactPointerEvent<HTMLCanvasElement>): number | null => {
    const canvas = ref.current;
    if (canvas === null) {
      return null;
    }
    const rect = canvas.getBoundingClientRect();
    if (rect.width === 0) {
      return null;
    }
    const ratio = clamp((event.clientX - rect.left) / rect.width, 0, 1);
    return Math.round(viewStart + ratio * Math.max(0, viewEnd - viewStart));
  };

  const onPointerDown = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const index = bucketAt(event);
    if (index === null) {
      return;
    }
    event.currentTarget.setPointerCapture(event.pointerId);
    dragAnchor.current = index;
    setDrag({ start: index, end: index });
    setHover(index);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const index = bucketAt(event);
    if (index === null) {
      return;
    }
    setHover(index);
    if (dragAnchor.current !== null) {
      setDrag({ start: dragAnchor.current, end: index });
    }
  };

  const finishDrag = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const anchor = dragAnchor.current;
    const index = bucketAt(event);
    dragAnchor.current = null;
    setDrag(null);

    /* 拖动距离太短当成误触，不触发缩放 */
    if (anchor !== null && index !== null && Math.abs(index - anchor) >= 3) {
      onZoom?.({ start: Math.min(anchor, index), end: Math.max(anchor, index) });
    }
  };

  const hovered = hover === null ? null : clamp(hover, viewStart, viewEnd);
  const readout =
    hovered === null
      ? '悬停读数'
      : `${formatSeconds((fromMs + hovered * bucketMs) / 1000)}  min ${formatValue(series.min[hovered])}  max ${formatValue(series.max[hovered])}  avg ${formatValue(series.avg[hovered])}`;

  return (
    <div className={`spark${showId ? '' : ' spark-no-id'}`}>
      {showId && (
        <span className="spark-id" title={`SVID ${series.svid}`}>
          {series.svid}
          {series.owner !== undefined && series.owner !== '' && (
            <span className="spark-owner"> {series.owner}</span>
          )}
        </span>
      )}
      <canvas
        ref={ref}
        height={height}
        title="悬停看读数；横向拖动选择区间放大；双击重置缩放"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={finishDrag}
        onPointerCancel={() => {
          dragAnchor.current = null;
          setDrag(null);
        }}
        onPointerLeave={() => {
          if (dragAnchor.current === null) {
            setHover(null);
          }
        }}
        onDoubleClick={() => onZoom?.(null)}
      />
      <span className="spark-readout">{readout}</span>
    </div>
  );
}
