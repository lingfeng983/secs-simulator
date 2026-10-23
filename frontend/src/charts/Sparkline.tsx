import { useEffect, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import type { PreviewSeries } from '../api';
import { formatValue } from '../lib/format';

/** 预览图里选中的桶区间；跨所有 SVID 共享，便于对齐读数。 */
export interface ZoomRange {
  start: number;
  end: number;
}

export interface SparklineProps {
  series: PreviewSeries;
  fromMs: number;
  bucketMs: number;
  zoom: ZoomRange | null;
  onZoom: (zoom: ZoomRange | null) => void;
}

export function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
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
export function Sparkline({ series, fromMs, bucketMs, zoom, onZoom }: SparklineProps): JSX.Element {
  const ref = useRef<HTMLCanvasElement | null>(null);
  const dragAnchor = useRef<number | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [drag, setDrag] = useState<ZoomRange | null>(null);

  const count = series.avg.length;
  const visible = clampZoom(zoom, count);
  const viewStart = visible.start;
  const viewEnd = visible.end;

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
    const displayHeight = 72;
    const width = Math.round(displayWidth * ratio);
    const height = Math.round(displayHeight * ratio);
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
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

    /* 三条水平参考线：没有刻度也要让人看出"高/中/低" */
    context.strokeStyle = 'rgba(128, 128, 128, 0.22)';
    context.lineWidth = 1;
    for (let line = 0; line <= 3; line += 1) {
      const at = 2 + (line / 3) * (displayHeight - 4);
      context.beginPath();
      context.moveTo(0, at);
      context.lineTo(displayWidth, at);
      context.stroke();
    }

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
    context.fillStyle = 'rgba(68, 147, 248, 0.22)';
    context.fill();

    context.beginPath();
    for (let index = viewStart; index <= viewEnd; index += 1) {
      const value = series.avg[index];
      if (index === viewStart) {
        context.moveTo(x(index), y(value));
      } else {
        context.lineTo(x(index), y(value));
      }
    }
    context.strokeStyle = '#4493f8';
    context.lineWidth = 1.5;
    context.stroke();

    if (drag !== null) {
      const from = Math.min(drag.start, drag.end);
      const to = Math.max(drag.start, drag.end);
      const left = clamp(x(from), 0, displayWidth);
      const right = clamp(x(to), 0, displayWidth);
      context.fillStyle = 'rgba(68, 147, 248, 0.16)';
      context.fillRect(left, 0, Math.max(1, right - left), displayHeight);
    }

    if (hover !== null) {
      const index = clamp(hover, viewStart, viewEnd);
      const atX = x(index);
      context.strokeStyle = 'rgba(128, 128, 128, 0.75)';
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(atX, 0);
      context.lineTo(atX, displayHeight);
      context.stroke();

      context.fillStyle = '#4493f8';
      context.beginPath();
      context.arc(atX, y(series.avg[index]), 3, 0, Math.PI * 2);
      context.fill();
    }
  }, [series, hover, drag, viewStart, viewEnd, count]);

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
      onZoom({ start: Math.min(anchor, index), end: Math.max(anchor, index) });
    }
  };

  const hovered = hover === null ? null : clamp(hover, viewStart, viewEnd);
  const readout =
    hovered === null
      ? '悬停读数'
      : `${formatSeconds((fromMs + hovered * bucketMs) / 1000)}  min ${formatValue(series.min[hovered])}  max ${formatValue(series.max[hovered])}  avg ${formatValue(series.avg[hovered])}`;

  return (
    <div className="spark">
      <span className="spark-id" title={`SVID ${series.svid}`}>
        {series.svid}
        {series.owner !== undefined && series.owner !== '' && (
          <span className="spark-owner"> {series.owner}</span>
        )}
      </span>
      <canvas
        ref={ref}
        height={72}
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
        onDoubleClick={() => onZoom(null)}
      />
      <span className="spark-readout">{readout}</span>
    </div>
  );
}
