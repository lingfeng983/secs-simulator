import { useEffect, useRef } from 'react';
import uPlot from 'uplot';
import type { PreviewSeries } from '../api';

export interface UplotSeriesProps {
  series: PreviewSeries;
  fromMs: number;
  bucketMs: number;
}

/**
 * uPlot 版本的单条曲线。
 *
 * <p>Canvas 版本适合轻量总览；uPlot 提供图例开关、框选缩放、双击复位和光标读数。
 * 两条路径共享同一份 {@link PreviewData}，切换只影响渲染，不重新请求后端。</p>
 */
export function UplotSeries({ series, fromMs, bucketMs }: UplotSeriesProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const plotRef = useRef<uPlot | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) {
      return;
    }

    const x = series.avg.map((_, index) => (fromMs + index * bucketMs) / 1000);
    const data: uPlot.AlignedData = [x, series.min, series.max, series.avg];
    const plot = new uPlot(
      {
        width: Math.max(320, host.clientWidth || 760),
        height: 220,
        padding: [12, 12, 4, 4],
        cursor: {
          drag: { x: true, y: false, setScale: true },
          points: { show: false },
        },
        legend: { show: true, live: true },
        scales: { x: { time: false } },
        series: [
          { label: 't(s)' },
          { label: 'min', stroke: 'rgba(68, 147, 248, 0.35)', width: 1, points: { show: false } },
          { label: 'max', stroke: 'rgba(68, 147, 248, 0.35)', width: 1, points: { show: false } },
          { label: 'avg', stroke: '#4493f8', width: 1.8, points: { show: false } },
        ],
        bands: [{ series: [1, 2], fill: 'rgba(68, 147, 248, 0.18)' }],
        axes: [
          { stroke: '#8b949e', grid: { stroke: 'rgba(128, 128, 128, 0.18)' } },
          { stroke: '#8b949e', grid: { stroke: 'rgba(128, 128, 128, 0.18)' } },
        ],
      },
      data,
      host,
    );
    plotRef.current = plot;

    const resize = () => {
      const width = host.clientWidth;
      if (width > 0 && plotRef.current !== null) {
        plotRef.current.setSize({ width, height: 220 });
      }
    };
    resize();

    let observer: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(resize);
      observer.observe(host);
    }

    return () => {
      observer?.disconnect();
      plot.destroy();
      plotRef.current = null;
    };
  }, [series, fromMs, bucketMs]);

  return (
    <div className="uplot-block">
      <div className="uplot-title">
        SVID {series.svid}
        {series.owner !== undefined && series.owner !== '' ? ` · ${series.owner}` : ''}
        {series.unit !== undefined && series.unit !== '' ? ` (${series.unit})` : ''}
      </div>
      <div className="uplot-host" ref={hostRef} />
    </div>
  );
}
