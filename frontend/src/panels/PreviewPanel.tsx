import { useMemo, useState } from 'react';
import { Panel, PanelFootnote, StatCard } from '../ui/Panel';
import { DataTable, type Column } from '../ui/DataTable';
import { Sparkline, type ZoomRange } from '../charts/Sparkline';
import { UplotSeries } from '../charts/UplotSeries';
import { MultiAxisChart } from '../charts/MultiAxisChart';
import { computeAllStats, type SeriesStats } from '../lib/previewStats';
import { formatValue } from '../lib/format';
import type { PanelBaseProps } from './types';

/** 可选的时间窗：后端引擎时钟从进程启动起算，时间窗要相对"现在"而不是 0。 */
const WINDOWS: ReadonlyArray<{ label: string; ms: number }> = [
  { label: '最近 1 分钟', ms: 60_000 },
  { label: '最近 5 分钟', ms: 300_000 },
  { label: '最近 15 分钟', ms: 900_000 },
];

/**
 * 曲线预览面板。
 *
 * <p>三种渲染方式共享同一份数据：Canvas 轻量、uPlot 精确、多轴看量纲。
 * 切换只影响渲染，不重新请求后端。</p>
 *
 * <p>另外加了「窗口统计」表：图能看出形状，看不出数值量级，
 * 值班时先扫一眼统计表，再决定细看哪条线。</p>
 */
export function PreviewPanel({ backend, preferences, updatePreferences }: PanelBaseProps): JSX.Element {
  const { preview, previewSession, activeSession } = backend;
  const [zoom, setZoom] = useState<ZoomRange | null>(null);
  const [filter, setFilter] = useState('');
  const [busy, setBusy] = useState(false);

  const chartMode = preferences.chartMode;
  const windowMs = preferences.windowMs;

  const series = preview?.series ?? [];

  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (needle === '') {
      return series;
    }
    return series.filter((item) => {
      const haystack = `${item.svid} ${item.owner ?? ''} ${item.unit ?? ''}`.toLowerCase();
      return haystack.includes(needle);
    });
  }, [series, filter]);

  const stats = useMemo(() => computeAllStats(visible), [visible]);

  const onRefresh = async () => {
    setBusy(true);
    try {
      await backend.refreshPreview(windowMs);
      setZoom(null);
    } finally {
      setBusy(false);
    }
  };

  const statColumns: Column<SeriesStats>[] = [
    {
      key: 'svid',
      header: 'SVID',
      align: 'right',
      sortValue: (row) => row.svid,
      render: (row) => row.svid,
    },
    { key: 'owner', header: '归属', sortValue: (row) => row.owner ?? '', render: (row) => row.owner || '-' },
    { key: 'unit', header: '单位', sortValue: (row) => row.unit ?? '', render: (row) => row.unit || '-' },
    { key: 'min', header: '最小', align: 'right', sortValue: (row) => row.min, render: (row) => formatValue(row.min) },
    { key: 'max', header: '最大', align: 'right', sortValue: (row) => row.max, render: (row) => formatValue(row.max) },
    { key: 'avg', header: '均值', align: 'right', sortValue: (row) => row.avg, render: (row) => formatValue(row.avg) },
    { key: 'last', header: '末值', align: 'right', sortValue: (row) => row.last, render: (row) => formatValue(row.last) },
    {
      key: 'span',
      header: '峰峰',
      align: 'right',
      sortValue: (row) => row.span,
      render: (row) => formatValue(row.span),
    },
  ];

  return (
    <Panel
      id="panel-preview"
      title="曲线预览"
      description="按当前配置算出 min/max 包络 + avg 线。时间窗相对「现在」，不是相对进程启动。"
      actions={
        <>
          <label className="field">
            时间窗
            <select
              value={windowMs}
              onChange={(event) => updatePreferences({ windowMs: Number(event.target.value) })}
            >
              {WINDOWS.map((item) => (
                <option key={item.ms} value={item.ms}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            图表
            <select
              value={chartMode}
              onChange={(event) => {
                const value = event.target.value;
                updatePreferences({ chartMode: value === 'uplot' ? 'uplot' : value === 'multi' ? 'multi' : 'canvas' });
              }}
            >
              <option value="canvas">Canvas</option>
              <option value="uplot">uPlot</option>
              <option value="multi">多轴</option>
            </select>
          </label>
          <button
            type="button"
            className="primary"
            onClick={() => void onRefresh()}
            disabled={sessionsUnavailable(activeSession) || busy}
          >
            {busy ? '刷新中…' : '刷新曲线'}
          </button>
          <button
            type="button"
            onClick={() => void backend.downloadPreview(windowMs)}
            disabled={sessionsUnavailable(activeSession)}
            title="SCSP v1：固定头 + 每条序列的 min/max/avg 双精度数组"
          >
            下载二进制
          </button>
        </>
      }
      stats={
        preview !== null && preview.truncated === true ? (
          <StatCard
            label="被截断"
            value={`${preview.svidTotal ?? '?'} 个 SVID`}
            tone="warn"
            hint="只返回前若干条曲线"
          />
        ) : undefined
      }
    >
      {preview === null ? (
        <p className="hint">选好会话与时间窗后点「刷新曲线」，按当前配置算出 min/max 包络 + avg 线。</p>
      ) : (
        <>
          <div className="preview-meta">
            <input
              type="search"
              className="table-search"
              placeholder="按 SVID / 归属 / 单位过滤曲线"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              aria-label="过滤曲线"
            />
            <span className="hint">
              {series.length} 条曲线 · {preview.buckets} 桶 × {preview.bucketMs} ms
              {preview.from !== undefined ? ` · 窗口 ${Math.round((preview.to - preview.from) / 1000)} 秒` : ''}
            </span>
            {chartMode === 'canvas' && zoom !== null && (
              <>
                <span className="hint">
                  已放大：桶 {zoom.start}–{zoom.end}
                  （约 {Math.round(((zoom.end - zoom.start + 1) * preview.bucketMs) / 1000)} 秒）
                </span>
                <button type="button" onClick={() => setZoom(null)}>
                  重置缩放
                </button>
              </>
            )}
          </div>

          {preview.truncated === true && (
            <p className="hint">
              后端只返回前 {preview.series.length} 条曲线
              {preview.svidTotal !== undefined ? `（共 ${preview.svidTotal} 个 SVID）` : ''}。
              这是预览上限，不是曲线消失了。
            </p>
          )}

          {series.length === 0 ? (
            <p className="hint">该会话没有可预览的 SVID。</p>
          ) : visible.length === 0 ? (
            <p className="hint">没有匹配「{filter}」的曲线。</p>
          ) : (
            <>
              <p className="hint">
                {chartMode === 'uplot'
                  ? '拖动框选放大，双击复位；点击图例可隐藏/显示 min/max/avg。'
                  : chartMode === 'multi'
                    ? '按单位分配 y 轴，左右交替；只画 avg，点击图例可隐藏/显示单条 SVID。'
                    : '悬停看读数；在曲线上横向拖动选择区间放大；双击或点「重置缩放」恢复。'}
              </p>

              {chartMode === 'uplot' ? (
                <div className="uplots">
                  {visible.map((item) => (
                    <UplotSeries key={item.svid} series={item} fromMs={preview.from} bucketMs={preview.bucketMs} />
                  ))}
                </div>
              ) : chartMode === 'multi' ? (
                <MultiAxisChart series={visible} fromMs={preview.from} bucketMs={preview.bucketMs} />
              ) : (
                <div className="sparks">
                  {visible.map((item) => (
                    <Sparkline
                      key={item.svid}
                      series={item}
                      fromMs={preview.from}
                      bucketMs={preview.bucketMs}
                      zoom={zoom}
                      onZoom={setZoom}
                    />
                  ))}
                </div>
              )}

              <h3 className="sub-title">窗口统计</h3>
              <DataTable
                columns={statColumns}
                rows={stats}
                rowKey={(row) => String(row.svid)}
                exportName={`preview-stats-${previewSession || 'session'}`}
                emptyText="没有可统计的曲线"
                exportColumns={[
                  { header: 'SVID', value: (row) => row.svid },
                  { header: '归属', value: (row) => row.owner ?? '' },
                  { header: '单位', value: (row) => row.unit ?? '' },
                  { header: '最小', value: (row) => row.min },
                  { header: '最大', value: (row) => row.max },
                  { header: '均值', value: (row) => row.avg },
                  { header: '末值', value: (row) => row.last },
                  { header: '峰峰', value: (row) => row.span },
                ]}
              />
            </>
          )}
        </>
      )}
          <PanelFootnote
        items={
          preview === null
            ? []
            : [
                { label: '曲线', value: `${visible.length}/${series.length}` },
                { label: '桶', value: `${preview.buckets} × ${preview.bucketMs} ms` },
                {
                  label: '窗口',
                  value: `${Math.round((preview.to - preview.from) / 1000)} s${previewSession === '' ? '' : `（${previewSession}）`}`,
                },
              ]
        }
      />
    </Panel>
  );
}

/** 没有会话时禁用刷新。 */
function sessionsUnavailable(activeSession: string): boolean {
  return activeSession === '';
}
