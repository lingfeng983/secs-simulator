import { useEffect, useMemo, useRef, useState } from 'react';
import type { PanelBaseProps } from './types';
import { ConfigEditShell } from './ConfigEditShell';
import { EventsForm } from '../editors/ConfigFormEditor';
import { CycleForm } from '../editors/CycleForm';
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

/**
 * 设备 · 配置区 · 事件计划（events.plans 定义）+ 草稿周期预览。
 *
 * <p>三块都在这一页：<b>工艺周期</b>（cycle，按片重复）、<b>事件计划</b>
 * （events.plans，周期外的异常路径）、<b>草稿预览</b>。事件只上报、不改值。</p>
 *
 * <p>下方「事件周期预览」按<b>草稿原文</b>（含未保存改动）做确定性重放，
 * 草稿一变（防抖 600ms）自动重算——边配置边看"这份编排会画出什么曲线"，
 * 不需要先保存再开会话。</p>
 */
export function PlanConfigPanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-plan-config"
      title="事件计划与工艺周期"
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
  /* 预览：草稿变化防抖后自动重算 */
  const [windowMs, setWindowMs] = useState(120_000);
  const [preview, setPreview] = useState<PreviewData | null>(null);
  const [invalid, setInvalid] = useState(false);
  const [busy, setBusy] = useState(false);
  const [auto, setAuto] = useState(true);
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

  const svidLabels = useMemo(() => {
    const map = new Map<number, string>();
    for (const choice of collectSvids(root)) {
      map.set(choice.svid, choice.slot);
    }
    return map;
  }, [root]);

  return (
    <>
      <CycleForm root={root} edit={edit} />
      <EventsForm root={root} edit={edit} />

      <section className="panel-section block" id="plan-draft-preview">
        <div className="section-head">
          <h3 style={{ margin: 0 }}>
            <HintTitle text="不需要保存或启动设备：直接按当前草稿（含未保存改动）做一次确定性重放。横轴是模拟时间，竖线是各计划按触发规则会发送事件的时刻——边改周期与触发规则边看曲线变化。">
              事件周期预览（按草稿）
            </HintTitle>
          </h3>
          <div className="preview-controls">
            <label className="check-inline">
              <input type="checkbox" checked={auto} onChange={(event) => setAuto(event.target.checked)} />
              随草稿自动刷新
            </label>
            <label className="field">
              时间窗
              <select value={windowMs} onChange={(event) => setWindowMs(Number(event.target.value))} aria-label="预览时间窗">
                {WINDOWS.map((item) => <option key={item.ms} value={item.ms}>{item.label}</option>)}
              </select>
            </label>
            <button type="button" disabled={busy} onClick={() => void loadPreview(text)}>
              {busy ? '计算中…' : '重新预览'}
            </button>
          </div>
        </div>
        {preview === null ? (
          <p className="hint">
            {invalid
              ? '草稿暂时编译不过（配置有错或还没写完），修好后预览会自动出现。'
              : '还没有预览。'}
          </p>
        ) : (
          <EventPreview data={preview} height={110} labels={svidLabels} eventLabel="计划触发" />
        )}
      </section>
    </>
  );
}
