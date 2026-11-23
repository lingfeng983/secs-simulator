import { useEffect, useRef, useState } from 'react';
import type { PanelBaseProps } from './types';
import { Panel } from '../../shared/ui/Panel';
import { ConfigEditShell } from './ConfigEditShell';
import { DraftPreviewCard } from './DraftPreviewCard';
import { CycleForm } from '../editors/CycleForm';
import type { JsonObject } from '../editors/templateConfig';
import type { CycleRuntimeView } from '../../api';

/**
 * 设备 · 配置区 · 工艺周期（2026-11-16 计划生命周期重设计 §2.4/M4）。
 *
 * <p>这一页只管<b>定义</b>：节拍参数 + 片内事件，以及按这份草稿重放出来的
 * 预览图。周期的启停、片号/相位/进度与片内事件的发送台账都在运行区
 * 「工艺周期」页。2026-11-21：顶部不再有重新载入/校验，「+ 周期事件」与
 * 「保存」并排放在标题行右侧，整份周期定义配置完点一次保存。</p>
 *
 * <p>预览只取工艺周期的片内事件，与「事件计划」页的计划触发无关。</p>
 */
export function CyclePanel(props: PanelBaseProps & { sessionId?: string; configName?: string }): JSX.Element {
  const { backend, sessionId, configName } = props;
  return (
    <ConfigEditShell
      backend={backend}
      sessionId={sessionId}
      configName={configName}
      panelId="panel-cycle"
      sections={['cycle']}
      title="周期参数与片内事件"
      actions="none"
    >
      {({ root, edit, text, save, busy }) => (
        <CycleEditor root={root} edit={edit} text={text} backend={backend} onSave={() => save()} busy={busy} />
      )}
    </ConfigEditShell>
  );
}

/** 周期定义摘要（来自设备配置）：运行区没有会话时也能说清"这台设备的周期长什么样"。 */
export interface CycleDefinition {
  durationMs: number;
  jitterMs: number;
  count: number;
  /** 片内事件条数（一个周期内配置了几条）。 */
  events: number;
}

/** 运行卡：轮询 /api/v2/devices/{id}/cycle，启动/停止走 ONLINE 门控（§2.2）。 */
export function CycleRunCard({ backend, notifier, deviceId }: {
  backend: PanelBaseProps['backend'];
  notifier: PanelBaseProps['notifier'];
  deviceId: string;
}): JSX.Element {
  const [cycle, setCycle] = useState<CycleRuntimeView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const loadedRef = useRef(false);
  const loadingRef = useRef(false);

  useEffect(() => {
    if (deviceId === '') {
      setLoaded(true);
      loadedRef.current = true;
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const view = await backend.client?.cycle(deviceId) ?? null;
        if (!cancelled) {
          setCycle(view);
          setLoaded(true);
          loadedRef.current = true;
        }
      } catch {
        /* 评审 F2：单次轮询失败保留上一帧快照——一次超时不该把运行中的卡片
           打回「设备未启动」；真正的未启动由 client 的 404→null 表达 */
        if (!cancelled && !loadedRef.current) {
          setCycle(null);
          setLoaded(true);
          loadedRef.current = true;
        }
      }
    };
    const guardedLoad = async () => {
      /* 评审 F8：上一帧未返回时跳过本次轮询（8s 超时下避免并发叠加） */
      if (loadingRef.current) {
        return;
      }
      loadingRef.current = true;
      try {
        await load();
      } finally {
        loadingRef.current = false;
      }
    };
    void guardedLoad();
    const timer = window.setInterval(() => void guardedLoad(), 1_500);
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
        /* 409 有两种成因：设备离线（not-online）或配置里没有 cycle 段（no-cycle） */
        notifier.warn('无法启动', '设备离线（先上线再启动），或这份配置还没有 cycle 段');
      } else {
        notifier.error('启动失败', `HTTP ${status ?? '?'}`);
      }
    } catch (error) {
      /* 评审 F1：网络失败要有提示，不能静默 + unhandled rejection */
      notifier.error('启动失败', (error as Error).message);
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
    } catch (error) {
      notifier.error('停止失败', (error as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const stateLabel = cycle === null ? '' : cycle.state === 'RUNNING' ? '运行中' : cycle.state === 'FINISHED' ? '已完成' : '停止';
  const tone = cycle?.state === 'RUNNING' ? 'ok' : cycle?.state === 'FINISHED' ? 'info' : 'muted';
  /* 2026-11-20 用户反馈：周期没跑起来时，「停止 / 待启动（值冻结）/ 已发片内事件 0 条 /
     （设备离线：先上线再启动）」这一串只是在念"什么都没发生"，占版面还把按钮挤到
     视线外。所以只有真有事可说时才出状态行：跑起来了、跑完了、或者已经发过片内事件。
     停止态且一条没发过 = 不显示（此时「启动周期」可点、「停止周期」置灰，状态自明）。 */
  const showState = cycle !== null && cycle.hasCycle
    && (cycle.state === 'RUNNING' || cycle.state === 'FINISHED' || cycle.firedCount > 0);
  /* 状态徽标已经说了"运行中/已完成"，详情里不再重复那个词 */
  const stateText = cycle === null
    ? ''
    : cycle.state === 'RUNNING'
      ? `第 ${cycle.waferIndex + 1} 片 · 相位 ${(cycle.phaseMs / 1000).toFixed(1)} 秒 · 进度 ${cycle.waferIndex + 1}/${cycle.totalWafers} 片 · 已发片内事件 ${cycle.firedCount} 条`
      : cycle.state === 'FINISHED'
        ? `值冻结在末相位 · 共 ${cycle.totalWafers} 片 · 已发片内事件 ${cycle.firedCount} 条`
        : `已发片内事件 ${cycle.firedCount} 条`;

  return (
    <Panel
      id="panel-cycle-run"
      title="运行卡"
      headless
      /* 按钮落在内容下方（面板脚注行），不再和状态挤在同一行 */
      actions={
        <>
          <button
            type="button"
            className="primary"
            disabled={busy || !started || !online || cycle?.state === 'RUNNING'}
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
          这份配置还没有 cycle 段：到配置区「工艺周期」页插入一个周期并保存（需重启设备）后即可启动。
        </p>
      ) : showState ? (
        <p className="cycle-run-state">
          {cycle.state !== 'STOPPED' && <span className="state-pill" data-tone={tone}>{stateLabel}</span>}
          <span className="hint-inline">{stateText}</span>
        </p>
      ) : null}
    </Panel>
  );
}

/** 周期参数 + 片内事件（编辑走配置草稿）；周期预览只取片内事件标记。 */
function CycleEditor({ root, edit, text, backend, onSave, busy }: {
  root: JsonObject;
  edit: (mutate: (root: JsonObject) => void) => void;
  text: string;
  backend: PanelBaseProps['backend'];
  onSave: () => Promise<void> | void;
  busy: boolean;
}): JSX.Element {
  return (
    <>
      <CycleForm root={root} edit={edit} onSave={onSave} busy={busy} />
      <DraftPreviewCard
        backend={backend}
        root={root}
        text={text}
        marks="cycle"
        title="周期预览（按草稿）"
        hint="不需要保存或启动设备：按当前草稿（含未保存改动）做一次确定性重放。横轴是模拟时间、纵轴是参数值，竖虚线是片内事件的发送时刻——一个周期内每片按片内相位重放，所以竖线会按片长重复。这里只画工艺周期的片内事件，与「事件计划」页的计划触发无关。默认只画第一个参数，需要对比时在「参数」里再勾选（多选，勾几条画几条）。"
        eventLabel="片内事件"
      />
    </>
  );
}
