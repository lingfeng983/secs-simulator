import { useCallback, useEffect, useMemo, useState } from 'react';
import type { PanelBaseProps } from '../types';
import type { CollectorConfigInput, CollectorState, PreviewSeries } from '../../api';
import { Sparkline } from '../../charts/Sparkline';
import { downloadText, safeFilename } from '../../lib/table';
import { CeidDatalist } from '../../ui/CeidPicker';

/** 每 SVID 曲线的重采样桶数(画图用,原始采样不均匀)。 */
const CHART_BUCKETS = 120;

/**
 * 主机模拟 S1F3 数据采集卡（schema v6 需求⑤）。
 *
 * <p>配置启停 CEID 与采集 SVID → 收到 startCeid 的 S6F11 开始按周期
 * 轮询 S1F3,收到 stopCeid 停止;数据实时画图,可导出 CSV。
 * 主机模拟未连接时给出引导。</p>
 */
export function CollectorCard({ backend, notifier, sessionId }: PanelBaseProps & { sessionId: string }): JSX.Element {
  const [startCeid, setStartCeid] = useState('');
  const [stopCeid, setStopCeid] = useState('');
  const [intervalMs, setIntervalMs] = useState('1000');
  const [svidText, setSvidText] = useState('');
  const [state, setState] = useState<CollectorState | null>(null);
  const [busy, setBusy] = useState(false);

  const svidChoices = backend.catalog?.svids ?? [];

  /* 初次加载:把后端已保存的配置回填表单 */
  useEffect(() => {
    let cancelled = false;
    void backend.collectorState(sessionId).then((value) => {
      if (cancelled || value === null || !value.configured) {
        return;
      }
      setState(value);
      setStartCeid(String(value.startCeid ?? ''));
      setStopCeid(String(value.stopCeid ?? ''));
      setIntervalMs(String(value.intervalMs ?? 1000));
      setSvidText((value.svids ?? []).map((item) => item.id).join(', '));
    });
    return () => {
      cancelled = true;
    };
    // 只在进入设备页时回填一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  /* 运行中每 2 秒拉一次数据;页面在后台时暂停,回前台立刻补一拍(与全局调度器同语义) */
  useEffect(() => {
    if (!state?.running && !state?.configured) {
      return;
    }
    let stopped = false;
    const poll = () => {
      if (typeof document !== 'undefined' && document.hidden) {
        return;
      }
      void backend.collectorState(sessionId).then((value) => {
        if (!stopped && value !== null) {
          setState(value);
        }
      });
    };
    const timer = window.setInterval(poll, 2000);
    const onVisible = () => {
      if (!document.hidden) {
        poll();
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [backend, sessionId, state?.running, state?.configured]);

  const parseSvids = useCallback((text: string): number[] => {
    return text
      .split(/[,\s]+/)
      .map((piece) => Number.parseInt(piece, 10))
      .filter((value) => Number.isFinite(value) && value > 0);
  }, []);

  const save = async () => {
    const svids = parseSvids(svidText);
    if (svids.length === 0) {
      notifier.error('至少要填一个采集 SVID');
      return;
    }
    const names = new Map(svidChoices.map((choice) => [choice.svid, choice]));
    const config: CollectorConfigInput = {
      startCeid: Number.parseInt(startCeid, 10),
      stopCeid: Number.parseInt(stopCeid, 10),
      intervalMs: Number.parseInt(intervalMs, 10) || 1000,
      svids: svids.map((id) => ({ id, name: names.get(id)?.slot ?? `SVID_${id}` })),
    };
    if (!Number.isFinite(config.startCeid) || !Number.isFinite(config.stopCeid)) {
      notifier.error('启停 CEID 必须是数字');
      return;
    }
    setBusy(true);
    try {
      const error = await backend.saveCollector(sessionId, config);
      if (error !== null) {
        notifier.error(`采集配置被拒: ${error}`);
        return;
      }
      notifier.info('采集配置已下发;收到启动 CEID 即开始采集');
      const fresh = await backend.collectorState(sessionId);
      setState(fresh);
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (start: boolean) => {
    setBusy(true);
    try {
      const error = await backend.collectorCommand(sessionId, start);
      if (error !== null) {
        notifier.error(error);
        return;
      }
      const fresh = await backend.collectorState(sessionId);
      setState(fresh);
    } finally {
      setBusy(false);
    }
  };

  const exportCsv = () => {
    const series = state?.series ?? [];
    if (series.length === 0) {
      notifier.info('还没有可导出的数据');
      return;
    }
    const lines: string[] = ['timestamp,svid,name,value'];
    for (const item of series) {
      for (let i = 0; i < item.t.length; i++) {
        lines.push(`${new Date(item.t[i]).toISOString()},${item.svid},${item.name ?? ''},${item.v[i]}`);
      }
    }
    downloadText(`collector-${safeFilename(sessionId)}.csv`, lines.join('\n'), 'text/csv;charset=utf-8');
  };

  const seriesList = state?.series ?? [];

  return (
    <section className="panel-section" id="panel-collector">
      <div className="section-head">
        <h2>S1F3 数据采集（主机侧拉取）</h2>
        <span className={`badge ${state?.running ? 'tone-ok' : 'tone-muted'}`}>
          {state?.running ? '采集中' : state?.configured ? '已就绪' : '未配置'}
        </span>
      </div>
      <p className="hint">
        收到「启动 CEID」的 S6F11 即按周期向设备发 S1F3 采集所选 SVID,收到「停止 CEID」自动结束;
        也可以手动启停调试。采集值按应答自带格式解码,远程设备零元数据可画图。
      </p>

      <div className="collector-form">
        <label className="field">
          启动 CEID
          <input
            value={startCeid}
            onChange={(event) => setStartCeid(event.target.value)}
            list="collector-ceid-choices"
            inputMode="numeric"
            placeholder="如 2001"
          />
        </label>
        <label className="field">
          停止 CEID
          <input
            value={stopCeid}
            onChange={(event) => setStopCeid(event.target.value)}
            list="collector-ceid-choices"
            inputMode="numeric"
            placeholder="如 2002"
          />
        </label>
        <label className="field">
          周期 ms
          <input
            value={intervalMs}
            onChange={(event) => setIntervalMs(event.target.value)}
            inputMode="numeric"
          />
        </label>
        <label className="field grow">
          采集 SVID(逗号分隔;本机设备可从目录点选)
          <input
            value={svidText}
            onChange={(event) => setSvidText(event.target.value)}
            placeholder="如 1001, 1003"
          />
        </label>
        <CeidDatalist id="collector-ceid-choices" ceids={backend.catalog?.ceids ?? []} />
        {svidChoices.length > 0 && (
          <datalist id="collector-svid-choices">
            {svidChoices.map((choice) => (
              <option key={choice.svid} value={choice.svid}>
                {choice.owner}.{choice.slot}
              </option>
            ))}
          </datalist>
        )}
      </div>

      {svidChoices.length > 0 && (
        <div className="chip-row">
          {svidChoices.slice(0, 24).map((choice) => (
            <button
              key={choice.svid}
              type="button"
              className="chip"
              title={`${choice.owner}.${choice.slot}`}
              onClick={() => {
                setSvidText((previous) => {
                  const current = parseSvids(previous);
                  if (current.includes(choice.svid)) {
                    return previous;
                  }
                  return current.length === 0 ? String(choice.svid) : `${previous}, ${choice.svid}`;
                });
              }}
            >
              +{choice.svid}
            </button>
          ))}
        </div>
      )}

      <div className="toolbar">
        <button type="button" className="primary" disabled={busy} onClick={() => void save()}>
          下发配置
        </button>
        <button type="button" disabled={busy || !state?.configured} onClick={() => void toggle(true)}>
          手动开始
        </button>
        <button type="button" disabled={busy || !state?.running} onClick={() => void toggle(false)}>
          手动停止
        </button>
        <button type="button" onClick={exportCsv}>
          导出 CSV
        </button>
        {state?.running && (
          <span className="hint-inline">
            已采 {state.samples ?? 0} 点 · 往返 {state.lastRttMs ?? 0}ms
            {(state.decodeErrors ?? 0) > 0 ? ` · 解码失败 ${state.decodeErrors}` : ''}
          </span>
        )}
        {(state?.staleSvids?.length ?? 0) > 0 && (
          <span className="danger">热重载后失效的 SVID: {(state?.staleSvids ?? []).join(', ')}</span>
        )}
      </div>

      {seriesList.length > 0 ? (
        <div className="collector-charts">
          {seriesList.map((item) => (
            <CollectorChart key={item.svid} item={item} />
          ))}
        </div>
      ) : (
        <p className="hint">
          尚未采集到数据。下发配置后「手动开始」调试,或等启动 CEID 的 S6F11 触发;采到数据后这里实时画图。
        </p>
      )}
    </section>
  );
}

function CollectorChart({ item }: { item: { svid: number; name?: string; unit?: string; t: number[]; v: number[] } }): JSX.Element {
  const data = useMemo<PreviewSeries>(() => {
    return resample(item.t, item.v, CHART_BUCKETS);
  }, [item]);

  const fromMs = item.t.length > 0 ? item.t[0] : 0;
  const toMs = item.t.length > 0 ? item.t[item.t.length - 1] + 1 : 1;
  const bucketMs = Math.max(1, (toMs - fromMs) / CHART_BUCKETS);

  return (
    <div className="collector-chart">
      <div className="collector-chart-head">
        <span className="mono">{item.svid}</span>
        <span>{(item.name ?? '') !== '' ? item.name : ''}</span>
        {(item.unit ?? '') !== '' && <span className="hint-inline">{item.unit}</span>}
      </div>
      <Sparkline
        series={{ svid: item.svid, owner: item.name, unit: item.unit, min: data.min, max: data.max, avg: data.avg }}
        fromMs={fromMs}
        bucketMs={bucketMs}
        height={72}
      />
    </div>
  );
}

/** 把不均匀的 (t, v) 采样重排成等宽桶的 min/max/avg(复用 Sparkline 的输入形状)。 */
function resample(t: number[], v: number[], buckets: number): PreviewSeries {
  const count = Math.max(1, Math.min(buckets, Math.max(1, v.length)));
  const from = t.length > 0 ? t[0] : 0;
  const to = t.length > 0 ? t[t.length - 1] + 1 : 1;
  const bucketMs = Math.max(1, (to - from) / count);
  const min = new Array<number>(count).fill(0);
  const max = new Array<number>(count).fill(0);
  const avg = new Array<number>(count).fill(0);
  const sums = new Array<number>(count).fill(0);
  const nums = new Array<number>(count).fill(0);

  for (let i = 0; i < t.length; i++) {
    const index = Math.min(count - 1, Math.max(0, Math.floor((t[i] - from) / bucketMs)));
    const value = v[i];
    if (nums[index] === 0) {
      min[index] = value;
      max[index] = value;
    } else {
      min[index] = Math.min(min[index], value);
      max[index] = Math.max(max[index], value);
    }
    sums[index] += value;
    nums[index] += 1;
  }
  for (let i = 0; i < count; i++) {
    avg[i] = nums[i] === 0 ? 0 : sums[i] / nums[i];
    if (nums[i] === 0) {
      min[i] = 0;
      max[i] = 0;
    }
  }
  return { svid: 0, min, max, avg };
}
