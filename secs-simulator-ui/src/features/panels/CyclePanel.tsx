import { useEffect, useMemo, useRef, useState } from 'react';
import type { PanelBaseProps } from './types';
import { Panel } from '../../shared/ui/Panel';
import { ConfigEditShell } from './ConfigEditShell';
import { CycleForm } from '../editors/CycleForm';
import { collectSvids, type JsonObject } from '../editors/templateConfig';
import { EventPreview } from '../charts/EventPreview';
import { HintTitle } from '../../shared/ui/FieldLabel';
import type { CycleRuntimeView, PreviewData } from '../../api';

/** 预览时间窗候选（草稿是确定性重放，右端点相对模拟时钟 0）。 */
const WINDOWS: ReadonlyArray<{ label: string; ms: number }> = [
  { label: '30 秒', ms: 30_000 },
  { label: '60 秒', ms: 60_000 },
  { label: '2 分钟', ms: 120_000 },
  { label: '5 分钟', ms: 300_000 },
];

/**
 * 设备 · 配置区 · 工艺周期（2026-11-16 计划生命周期重设计 §2.4/M4）。
 *
 * <p>cycle 从「事件计划」页拆出来独立成页：节拍参数 + 片内事件（写配置草稿）、
 * 运行卡（启动/停止 + 状态/片号/相位/进度，ONLINE 门控同计划启停）、
 * 周期预览（按草稿确定性重放）。事件编排的异常路径仍在「事件计划」页。</p>
 */
export function CyclePanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, notifier, sessionId, configName } = props;
  const deviceId = sessionId ?? backend.activeSession;
  return (
    <div className="panel-stack">
      <CycleRunCard backend={backend} notifier={notifier} deviceId={deviceId} />
      <ConfigEditShell
        backend={backend}
        sessionId={sessionId}
        configName={configName}
        panelId="panel-cycle"
        title="周期参数与片内事件"
      >
        {({ root, edit, text }) => <CycleEditor root={root} edit={edit} text={text} backend={backend} />}
      </ConfigEditShell>
    </div>
  );
}

/** 运行卡：轮询 /api/v2/devices/{id}/cycle，启动/停止走 ONLINE 门控（§2.2）。 */
function CycleRunCard({ backend, notifier, deviceId }: {
  backend: PanelBaseProps['backend'];
  notifier: PanelBaseProps['notifier'];
  deviceId: string;
}): JSX.Element {
  const [cycle, setCycle] = useState<CycleRuntimeView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (deviceId === '') {
      setLoaded(true);
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const view = await backend.client?.cycle(deviceId) ?? null;
        if (!cancelled) {
          setCycle(view);
          setLoaded(true);
        }
      } catch {
        if (!cancelled) {
          setCycle(null);
          setLoaded(true);
        }
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 1_500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId, backend.client]);

  const session = backend.sessions.find((item) => item.id === deviceId);
  const online = session !== undefined && session.controlState === 'ONLINE';
  const started = cycle !== null && cycle.hasCycle;

  const start = async () => {
    if (deviceId === '') {
      return;
    }
    setBusy(true);
    try {
      const status = await backend.client?.cycleStart(deviceId);
      if (status === 200) {
        notifier.success('工艺周期已启动', '锚点 = 此刻：值与片内事件从第 0 片起跑');
      } else if (status === 409) {
        notifier.warn('无法启动', '设备离线：先上线再启动');
      } else {
        notifier.error('启动失败', `HTTP ${status ?? '?'}`);
      }
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    if (deviceId === '') {
      return;
    }
    setBusy(true);
    try {
      const status = await backend.client?.cycleStop(deviceId);
      if (status === 200) {
        notifier.info('工艺周期已停止', '值冻结在进入停止时的表现；重启从第 0 片重来');
      } else {
        notifier.error('停止失败', `HTTP ${status ?? '?'}`);
      }
    } finally {
      setBusy(false);
    }
  };

  const stateLabel = cycle === null ? '' : cycle.state === 'RUNNING' ? '运行中' : cycle.state === 'FINISHED' ? '已完成' : '停止';
  const tone = cycle?.state === 'RUNNING' ? 'ok' : cycle?.state === 'FINISHED' ? 'info' : 'muted';
  const valueState = cycle === null || !cycle.hasCycle
    ? ''
    : cycle.state === 'RUNNING'
      ? `运行中 · 第 ${cycle.waferIndex + 1} 片 · 相位 ${(cycle.phaseMs / 1000).toFixed(1)} 秒`
      : cycle.state === 'FINISHED' ? '已完成（值冻结在末相位）' : '待启动（值冻结）';

  return (
    <Panel
      id="panel-cycle-run"
      title="运行卡"
      headless
      actions={
        <>
          <button
            type="button"
            className="primary"
            disabled={busy || !started || !online}
            onClick={() => void start()}
            title={online
              ? '启动周期：锚点 = 此刻，值与片内事件从第 0 片起跑'
              : '设备离线：先上线再启动'}
          >
            {busy ? '处理中…' : '启动周期'}
          </button>
          <button
            type="button"
            disabled={busy || cycle?.state !== 'RUNNING'}
            onClick={() => void stop()}
            title="停止周期：值冻结在当前表现；重启从第 0 片重来"
          >
            停止周期
          </button>
        </>
      }
    >
      {!loaded ? (
        <p className="hint">运行态读取中…</p>
      ) : deviceId === '' || cycle === null ? (
        <p className="hint">设备未启动：启动设备后这里显示节拍与片内事件的运行态。</p>
      ) : !cycle.hasCycle ? (
        <p className="hint">
          这份配置还没有 cycle 段：在下方「周期参数」里插入一个周期并保存（需重启设备）后即可启动。
        </p>
      ) : (
        <p className="cycle-run-state">
          <span className="state-pill" data-tone={tone}>{stateLabel}</span>
          <span className="hint-inline">{valueState}</span>
          <span className="hint-inline">
            进度 {cycle.waferIndex + 1}/{cycle.totalWafers} 片
          </span>
          <span className="hint-inline">已发片内事件 {cycle.firedCount} 条</span>
          {!online && <span className="hint-inline">（设备离线：先上线再启动）</span>}
        </p>
      )}
    </Panel>
  );
}

/** 周期参数 + 片内事件（编辑走配置草稿）+ 周期预览（按草稿确定性重放）。 */
function CycleEditor({ root, edit, text, backend }: {
  root: JsonObject;
  edit: (mutate: (root: JsonObject) => void) => void;
  text: string;
  backend: PanelBaseProps['backend'];
}): JSX.Element {
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

      <section className="panel-section block" id="cycle-draft-preview">
        <div className="section-head">
          <h3 style={{ margin: 0 }}>
            <HintTitle text="不需要保存或启动设备：按当前草稿（含未保存改动）做一次确定性重放。横轴是模拟时间，竖线是各片按「如果现在启动」会发送片内事件的时刻——曲线与实跑同锚（M3 预览对齐）。">
              周期预览（按草稿）
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
          <EventPreview data={preview} height={110} labels={svidLabels} eventLabel="片内事件" />
        )}
      </section>
    </>
  );
}
