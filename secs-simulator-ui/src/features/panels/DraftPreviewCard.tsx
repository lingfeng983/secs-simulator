import { useEffect, useMemo, useRef, useState } from 'react';
import type { PanelBaseProps } from './types';
import { collectSvids, type JsonObject } from '../editors/templateConfig';
import { EventPreview } from '../charts/EventPreview';
import { HintTitle } from '../../shared/ui/FieldLabel';
import type { PreviewData } from '../../api';

/** 预览时间窗候选（草稿是确定性重放，右端点相对模拟时钟 0）。 */
const WINDOWS: ReadonlyArray<{ label: string; ms: number }> = [
  { label: '30 秒', ms: 30_000 },
  { label: '60 秒', ms: 60_000 },
  { label: '2 分钟', ms: 120_000 },
  { label: '5 分钟', ms: 300_000 },
];

/** 图表布局：选中参数叠在一张图上，或每个参数一张小图。 */
export type PreviewLayout = 'combined' | 'split';

/**
 * 草稿预览卡（确定性重放，不需要保存或启动设备）。
 *
 * <p>2026-11-17 拆分：工艺周期与事件计划的标记在后端就分装两袋
 * （cycleEvents / events），本组件按 {@code marks} 只取属于自己的一袋——
 * 周期预览只画片内事件、事件计划预览只画计划触发，各算各的。</p>
 *
 * <p>参数选择：勾选要展示的 SVID（默认全部）；布局：合并一图 或
 * 按参数分开（每个参数一张小图，共用同一时间轴与标记）。</p>
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
  const [windowMs, setWindowMs] = useState(120_000);
  const [preview, setPreview] = useState<PreviewData | null>(null);
  const [invalid, setInvalid] = useState(false);
  const [busy, setBusy] = useState(false);
  const [auto, setAuto] = useState(true);
  const [selected, setSelected] = useState<Set<number> | null>(null);
  const [layout, setLayout] = useState<PreviewLayout>('combined');
  const seqRef = useRef(0);

  const loadPreview = async (draftText: string) => {
    const token = ++seqRef.current;
    setBusy(true);
    try {
      const data = backend.client === null
        ? null
        : await backend.client.previewConfigDraft(draftText, 0, windowMs, 120, true);
      /* 过期响应丢弃：草稿又变了或窗口已切 */
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
  };

  useEffect(() => {
    if (!auto || backend.client === null) {
      return;
    }
    const timer = window.setTimeout(() => void loadPreview(text), 600);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, windowMs, auto, backend.client]);

  const svidChoices = useMemo(() => collectSvids(root), [root]);

  const svidLabels = useMemo(() => {
    const map = new Map<number, string>();
    for (const choice of svidChoices) {
      map.set(choice.svid, choice.slot);
    }
    return map;
  }, [svidChoices]);

  /* 按页归属过滤标记：周期页只看片内事件、事件计划页只看计划触发 */
  const scoped = useMemo<PreviewData | null>(() => {
    if (preview === null) {
      return null;
    }
    const own = marks === 'cycle' ? (preview.cycleEvents ?? []) : (preview.events ?? []);
    return { ...preview, events: own };
  }, [preview, marks]);

  const shownSvids = selected === null
    ? svidChoices.map((choice) => choice.svid)
    : [...selected];

  const toggleSvid = (svid: number) => {
    setSelected((previous) => {
      const base = previous ?? new Set(svidChoices.map((choice) => choice.svid));
      const next = new Set(base);
      if (next.has(svid)) {
        next.delete(svid);
      } else {
        next.add(svid);
      }
      /* 全不选回退到「全部」语义之外的空态：空集显示提示而不是空图 */
      return next;
    });
  };

  const chart = (data: PreviewData, svids: readonly number[] | undefined, key: string) => (
    <EventPreview data={data} height={110} labels={svidLabels} svids={svids} eventLabel={eventLabel} key={key} />
  );

  const charts = () => {
    if (scoped === null) {
      return null;
    }
    if (layout === 'combined') {
      return chart(scoped, selected === null ? undefined : [...selected], 'combined');
    }
    const targets = selected === null ? svidChoices.map((choice) => choice.svid) : [...selected];
    if (targets.length === 0) {
      return null;
    }
    return targets.map((svid) => (
      <div key={svid} className="panel-section block">
        <div className="section-head">
          <h3 style={{ margin: 0 }}>
            {svidLabels.get(svid) ?? `SVID ${svid}`}
            <span className="mono hint-inline"> {svid}</span>
          </h3>
        </div>
        {chart(scoped, [svid], `svid-${svid}`)}
      </div>
    ));
  };

  const paramLabel = selected === null
    ? `全部 ${svidChoices.length} 个`
    : `已选 ${selected.size} / ${svidChoices.length} 个`;

  return (
    <section className="panel-section block draft-preview">
      <div className="section-head">
        <h3 style={{ margin: 0 }}>
          <HintTitle text={hint}>{title}</HintTitle>
        </h3>
        <div className="preview-controls">
          <details className="preview-picker">
            <summary title="勾选要在图上展示的参数">参数：{paramLabel}</summary>
            <div className="preview-picker-list">
              {svidChoices.length === 0 && <p className="hint">这份配置还没有可展示的变量。</p>}
              {svidChoices.map((choice) => {
                const checked = selected === null || selected.has(choice.svid);
                return (
                  <label key={choice.svid} className="check-inline">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleSvid(choice.svid)}
                    />
                    <span className="mono">{choice.svid}</span>
                    {choice.slot}
                  </label>
                );
              })}
              <div className="preview-picker-actions">
                <button type="button" onClick={() => setSelected(null)}>全选</button>
                <button type="button" onClick={() => setSelected(new Set())}>清空</button>
              </div>
            </div>
          </details>
          <label className="field">
            布局
            <select value={layout} onChange={(event) => setLayout(event.target.value as PreviewLayout)} aria-label="图表布局">
              <option value="combined">合并一图</option>
              <option value="split">按参数分开</option>
            </select>
          </label>
          <label className="field">
            时间窗
            <select value={windowMs} onChange={(event) => setWindowMs(Number(event.target.value))} aria-label="预览时间窗">
              {WINDOWS.map((item) => <option key={item.ms} value={item.ms}>{item.label}</option>)}
            </select>
          </label>
          <label className="check-inline">
            <input type="checkbox" checked={auto} onChange={(event) => setAuto(event.target.checked)} />
            自动刷新
          </label>
          <button type="button" disabled={busy} onClick={() => void loadPreview(text)}>
            {busy ? '计算中…' : '重新预览'}
          </button>
        </div>
      </div>

      {invalid ? (
        <p className="hint">草稿暂时编译不过（配置有错或还没写完），修好后预览会自动出现。</p>
      ) : scoped === null ? (
        <p className="hint">还没有预览。</p>
      ) : shownSvids.length === 0 ? (
        <p className="hint">在「参数」里勾选至少一个参数后再看图。</p>
      ) : layout === 'combined' ? (
        chart(scoped, selected === null ? undefined : [...selected], 'combined')
      ) : (
        <div className="preview-split-grid">
          {shownSvids.map((svid) => (
            <div key={svid} className="preview-split-cell">
              <div className="section-head">
                <h4 style={{ margin: 0 }}>
                  {svidLabels.get(svid) ?? `SVID ${svid}`}
                  <span className="mono hint-inline"> {svid}</span>
                </h4>
              </div>
              {chart(scoped, [svid], `svid-${svid}`)}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
