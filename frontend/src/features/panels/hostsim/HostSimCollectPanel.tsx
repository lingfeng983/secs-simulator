import { useEffect, useMemo, useRef, useState } from 'react';
import { Panel, PanelFootnote } from '../../../shared/ui/Panel';
import { CeidDatalist } from '../../../shared/ui/CeidPicker';
import { VariableChart } from '../../charts/VariableChart';
import { downloadText, parseCsv, safeFilename } from '../../../shared/lib/table';
import type { PanelBaseProps } from '../types';
import type { HostSimCommands } from '../../../state/useHostSims';
import { HostTargetPicker } from './HostTargetPicker';
import { SvidCheckList } from './SvidCheckList';
import type { PreviewSeries } from '../../../api';

/** 每 SVID 曲线的重采样桶数（画图用，原始采样不均匀）。 */
const CHART_BUCKETS = 240;

/** 远程主机的默认 S1F3 骨架（手动发送用）。 */
const MANUAL_S1F3_TEMPLATE = 'S1F3 W\n<L\n  <U4 1001>\n  <U4 1003>\n>.';

/**
 * 解析手填的 SVID 列表：逗号 / 空格 / 换行 / 分号都当分隔符。
 *
 * <p>远程主机没有 catalog，SVID 只能自由输入；这里只做"把一串数字拆开"，
 * 非法片段直接丢弃并在调用方提示（不要因为一个笔误整份配置下发失败）。</p>
 */
export function parseSvidList(text: string): number[] {
  const seen = new Set<number>();
  const result: number[] = [];
  for (const piece of text.split(/[\s,;，、]+/)) {
    if (piece === '') {
      continue;
    }
    const value = Number(piece);
    if (!Number.isInteger(value) || value < 1 || seen.has(value)) {
      continue;
    }
    seen.add(value);
    result.push(value);
  }
  return result;
}

/**
 * ③ S1F3 数据采集（主机侧拉取）。
 *
 * <p>配置启停 CEID 与采集 SVID → 收到 startCeid 的 S6F11 开始按周期
 * 轮询 S1F3，收到 stopCeid 停止；数据实时画图，可导出 CSV。
 * 采集器属于<b>主机实例</b>，所以多主机时各自独立。</p>
 *
 * <p>配置入口按主机类型分流：本机设备有 catalog，用多选（不限数量）；
 * 远程主机没有 catalog，SVID 列表手填，另可手动发一条整报文 S1F3 验证对端。</p>
 */
export function HostSimCollectPanel({
  backend,
  notifier,
  hostSims,
}: PanelBaseProps & { hostSims: HostSimCommands }): JSX.Element {
  const [startCeid, setStartCeid] = useState('');
  const [stopCeid, setStopCeid] = useState('');
  const [intervalMs, setIntervalMs] = useState('1000');
  const [selectedSvids, setSelectedSvids] = useState<number[]>([]);
  /* 远程主机：SVID 自由输入（切换主机时按 host 回填） */
  const [manualSvids, setManualSvids] = useState('');
  /* 远程主机：手动发一条整报文 S1F3（验证对端/临时查询） */
  const [manualSml, setManualSml] = useState('');
  const [manualReply, setManualReply] = useState('');
  const [manualBusy, setManualBusy] = useState(false);
  const [busy, setBusy] = useState(false);
  const [imported, setImported] = useState<{ name: string; series: PreviewSeries[] } | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);

  const host = hostSims.selected;
  const state = host?.collector ?? null;
  const svidChoices = backend.catalog?.svids ?? [];
  const openHosts = hostSims.hosts.filter((item) => item.open);
  /* 只要任意一台主机在采集，汇总表就要持续刷新 */
  const anyActive = openHosts.some((item) => item.collector?.running || item.collector?.configured);

  const remote = host?.remote === true;

  /* 切主机时回填该主机的采集配置 */
  useEffect(() => {
    const collector = host?.collector;
    setManualReply('');
    if (collector === undefined || collector === null || !collector.configured) {
      setStartCeid('');
      setStopCeid('');
      setIntervalMs('1000');
      setSelectedSvids([]);
      setManualSvids('');
      return;
    }
    setStartCeid(String(collector.startCeid ?? ''));
    setStopCeid(String(collector.stopCeid ?? ''));
    setIntervalMs(String(collector.intervalMs ?? 1000));
    setSelectedSvids((collector.svids ?? []).map((item) => item.id));
    setManualSvids((collector.svids ?? []).map((item) => item.id).join(', '));
  }, [host?.id, host?.collector?.configured, host?.collector?.startCeid, host?.collector?.stopCeid, host?.collector?.intervalMs]);

  /* 运行中每 2 秒刷一次采集数据（所有主机共用一次刷新）。
     依赖 refresh 函数本身(稳定的 useCallback),不依赖 hostSims 对象——
     对象每次渲染都是新的,拿它当依赖会变成"每帧刷新一次"的死循环。 */
  const refresh = hostSims.refresh;
  useEffect(() => {
    if (!anyActive) {
      return;
    }
    const timer = window.setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) {
        return;
      }
      void refresh();
    }, 2000);
    return () => window.clearInterval(timer);
  }, [anyActive, refresh]);

  /** 当前要下发的 SVID 列表：远程手填，本机多选。 */
  const effectiveSvids = useMemo(
    () => (remote ? parseSvidList(manualSvids) : selectedSvids),
    [remote, manualSvids, selectedSvids],
  );

  const save = async () => {
    if (host === null) {
      return;
    }
    if (effectiveSvids.length === 0) {
      notifier.error(remote ? '先填要采集的 SVID' : '至少选择一个采集 SVID');
      return;
    }
    setBusy(true);
    try {
      const error = await hostSims.saveCollector(host.id, {
        startCeid: Number.parseInt(startCeid, 10) || 0,
        stopCeid: Number.parseInt(stopCeid, 10) || 0,
        intervalMs: Number.parseInt(intervalMs, 10) || 1000,
        svids: effectiveSvids.map((id) => {
          const choice = svidChoices.find((item) => item.svid === id);
          return remote ? { id } : { id, name: choice?.slot, unit: choice?.unit };
        }),
      });
      if (error === null) {
        notifier.success('采集配置已下发');
      } else {
        notifier.error('下发失败', error);
      }
    } finally {
      setBusy(false);
    }
  };

  /** 手动启停：失败原因要弹出来，不然按钮点下去像没反应。 */
  const command = async (hostId: string, start: boolean) => {
    setBusy(true);
    try {
      const error = await hostSims.collectorCommand(hostId, start);
      if (error === null) {
        notifier.success(start ? '采集已开始' : '采集已停止');
      } else {
        notifier.error(start ? '开始采集失败' : '停止采集失败', error);
      }
    } finally {
      setBusy(false);
    }
  };

  /** 远程主机：手动发一条整报文 S1F3（不进周期采集，只做一次性验证/查询）。 */
  const sendManual = async () => {
    if (host === null) {
      return;
    }
    setManualBusy(true);
    setManualReply('');
    try {
      const raw = await hostSims.send(host.id, manualSml === '' ? MANUAL_S1F3_TEMPLATE : manualSml);
      const parsed = raw === null ? null : JSON.parse(raw) as {
        replySml?: string | null;
        replyStream?: number;
        replyFunction?: number;
        timedOut?: boolean;
        elapsedMs?: number;
      };
      if (parsed === null) {
        setManualReply('（无结果）');
      } else if (parsed.replySml != null) {
        setManualReply(`S${parsed.replyStream}F${parsed.replyFunction} · ${parsed.elapsedMs} ms\n${parsed.replySml}`);
      } else if (parsed.timedOut) {
        setManualReply('已发出，但设备未在 T3 内回复');
      } else {
        setManualReply('已发出（未带 W，不等回复）');
      }
    } catch (error) {
      setManualReply(`发送失败：${(error as Error).message}`);
    } finally {
      setManualBusy(false);
    }
  };

  /**
   * CSV 单元格：逗号/引号/换行用引号包裹；以 = + - @ 开头的前置单引号，
   * 避免在 Excel/WPS 里被当成公式执行（变量名与单位来自配置，可控但不可信）。
   */
  const csvCell = (value: string | number): string => {
    let text = String(value ?? '');
    if (/^[=+\-@]/.test(text)) {
      text = `'${text}`;
    }
    return /[",\n\r]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
  };

  const exportCsv = () => {
    if (host === null || state?.series === undefined) {
      return;
    }
    const lines = ['svid,name,unit,t,value'];
    for (const item of state.series ?? []) {
      const t = item.t ?? [];
      const v = item.v ?? [];
      for (let i = 0; i < Math.min(t.length, v.length); i += 1) {
        lines.push([item.svid, item.name ?? '', item.unit ?? '', t[i], v[i]].map(csvCell).join(','));
      }
    }
    /* 带 BOM，Excel 打开中文才不乱码 */
    downloadText(`s1f3-${safeFilename(host.id)}.csv`, `\uFEFF${lines.join('\n')}`, 'text/csv;charset=utf-8');
  };

  const series: PreviewSeries[] = (state?.series ?? []).map((item) => {
    const t = item.t ?? [];
    const v = item.v ?? [];
    const buckets = Math.min(CHART_BUCKETS, Math.max(1, v.length));
    const min: number[] = [];
    const max: number[] = [];
    const avg: number[] = [];
    for (let i = 0; i < buckets; i += 1) {
      const from = Math.floor((i * v.length) / buckets);
      const to = Math.max(from + 1, Math.floor(((i + 1) * v.length) / buckets));
      const slice = v.slice(from, to);
      min.push(Math.min(...slice));
      max.push(Math.max(...slice));
      avg.push(slice.reduce((sum, x) => sum + x, 0) / Math.max(1, slice.length));
    }
    const fromMs = t.length > 0 ? t[0] : 0;
    const toMs = t.length > 0 ? t[t.length - 1] : fromMs + 1000;
    return {
      svid: item.svid,
      owner: item.name ?? '',
      unit: item.unit ?? '',
      min,
      max,
      avg,
      __from: fromMs,
      __to: toMs,
    } as PreviewSeries & { __from: number; __to: number };
  });

  /** 导入导出的 CSV（svid,name,unit,t,v），离线查看/对比。 */
  const importCsv = async (file: File) => {
    const table = parseCsv(await file.text());
    if (table.length < 2) {
      notifier.error('CSV 没有数据行');
      return;
    }
    const headers = table[0].map((header) => header.trim());
    const index = {
      svid: headers.indexOf('svid'),
      name: headers.indexOf('name'),
      unit: headers.indexOf('unit'),
      t: headers.indexOf('t'),
      v: headers.indexOf('v'),
    };
    if (index.svid < 0 || index.t < 0 || index.v < 0) {
      notifier.error('CSV 表头应包含 svid,t,v');
      return;
    }
    const groups = new Map<number, { name: string; unit: string; points: { t: number; v: number }[] }>();
    for (const row of table.slice(1)) {
      /* 空单元格不能走 Number('')：那会变成 0 并通过 isFinite，造出一个假数据点 */
      const rawT = (row[index.t] ?? '').trim();
      const rawV = (row[index.v] ?? '').trim();
      if (rawT === '' || rawV === '') {
        continue;
      }
      const svid = Number(row[index.svid]);
      const t = Number(rawT);
      const v = Number(rawV);
      if (!Number.isFinite(svid) || !Number.isFinite(t) || !Number.isFinite(v)) {
        continue;
      }
      const group = groups.get(svid) ?? {
        name: index.name >= 0 ? row[index.name] : '',
        unit: index.unit >= 0 ? row[index.unit] : '',
        points: [],
      };
      group.points.push({ t, v });
      groups.set(svid, group);
    }
    const series: PreviewSeries[] = [];
    for (const [svid, group] of groups) {
      const points = group.points.sort((left, right) => left.t - right.t);
      const values = points.map((point) => point.v);
      const buckets = Math.min(CHART_BUCKETS, Math.max(1, values.length));
      const min: number[] = [];
      const max: number[] = [];
      const avg: number[] = [];
      for (let i = 0; i < buckets; i += 1) {
        const from = Math.floor((i * values.length) / buckets);
        const to = Math.max(from + 1, Math.floor(((i + 1) * values.length) / buckets));
        const slice = values.slice(from, to);
        min.push(Math.min(...slice));
        max.push(Math.max(...slice));
        avg.push(slice.reduce((sum, x) => sum + x, 0) / Math.max(1, slice.length));
      }
      series.push({
        svid,
        owner: group.name,
        unit: group.unit,
        min,
        max,
        avg,
        __from: points[0].t,
        __to: points[points.length - 1].t,
      } as PreviewSeries & { __from: number; __to: number });
    }
    setImported({ name: file.name, series });
  };

  const displaySeries = imported?.series ?? series;

  return (
    <Panel
      id="panel-hostsim-collect"
      title="③ S1F3 数据采集（主机侧拉取）"
      description="收到「启动 CEID」的 S6F11 开始按周期拉取 SVID，收到「停止 CEID」停止；数据实时画图，可导出 CSV。当前主机在顶部选择。"
    >
      <HostTargetPicker backend={backend} notifier={notifier} hostSims={hostSims} allowCreate={false} />

      {host === null || !host.open ? (
        <p className="hint">
          {openHosts.length === 0
            ? '当前没有已连接的主机。先到「主机实例」新建一个。'
            : '选一台已连接的主机来配置它的 S1F3 采集。'}
        </p>
      ) : (
        <>
          <div className="section-head">
            <h3 style={{ margin: 0 }}>编辑 {host.id} 的采集配置</h3>
            <span className={`badge ${host.remote ? 'tone-info' : state?.running ? 'tone-ok' : state?.configured ? 'tone-warn' : 'tone-muted'}`}>
              {host.remote ? `远程 ${host.target}` : host.session}
            </span>
            <span className="spacer" />
            <span className="hint-inline">
              {state?.running ? '采集中' : state?.configured ? '已配置' : '未配置'}
              {' · '}
              {state?.samples ?? 0} 点
            </span>
          </div>

          <div className="form-row">
            <label className="field">
              启动 CEID
              <input
                value={startCeid}
                onChange={(event) => setStartCeid(event.target.value)}
                inputMode="numeric"
                placeholder="如 2001"
                list="hostsim-collect-ceids"
              />
            </label>
            <label className="field">
              停止 CEID
              <input
                value={stopCeid}
                onChange={(event) => setStopCeid(event.target.value)}
                inputMode="numeric"
                placeholder="如 2002"
                list="hostsim-collect-ceids"
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
          </div>
          <CeidDatalist id="hostsim-collect-ceids" ceids={backend.catalog?.ceids ?? []} />

          {remote ? (
            <>
              <div className="field-block">
                <span className="template-label">采集 SVID（手填，逗号/空格分隔，不限数量）</span>
                <input
                  value={manualSvids}
                  onChange={(event) => setManualSvids(event.target.value)}
                  placeholder="如 1001, 1003, 1005"
                  aria-label="远程主机的采集 SVID 列表"
                />
                <p className="hint">
                  远程主机没有本地 catalog，SVID 只能手填：设备按收到的列表回 S1F4，
                  不在设备里的 SVID 通常回 0 或直接报错。已解析 {effectiveSvids.length} 个。
                </p>
              </div>

              <details className="inline-create">
                <summary>手动发送一条 S1F3（整报文，一次性）</summary>
                <p className="hint">
                  不进周期采集，只发一次并显示回复：用来确认对端在不在、SVID 对不对。
                </p>
                <textarea
                  className="sml-editor"
                  spellCheck={false}
                  value={manualSml === '' ? MANUAL_S1F3_TEMPLATE : manualSml}
                  onChange={(event) => setManualSml(event.target.value)}
                  aria-label="手动 S1F3 报文"
                />
                <div className="editor-foot">
                  <span className="hint">S1F3 的 body 就是要读的 SVID 列表。</span>
                  <button type="button" disabled={manualBusy} onClick={() => void sendManual()}>
                    {manualBusy ? '发送中…' : '发送 S1F3'}
                  </button>
                </div>
                {manualReply !== '' && <pre className="sml-result">{manualReply}</pre>}
              </details>
            </>
          ) : (
            <div className="field-block">
              <span className="template-label">
                采集 SVID（不限数量，已选 {selectedSvids.length} 个）
              </span>
              <SvidCheckList
                choices={svidChoices}
                selected={selectedSvids}
                onChange={setSelectedSvids}
              />
              <p className="hint">
                数量上限由设备侧采集器决定（当前实现为 256 个）；勾选本身不设上限。
              </p>
            </div>
          )}

          <div className="editor-foot">
            <span className="hint">
              收到「启动 CEID」的 S6F11 即自动开始采集；也可以手动启停。热重载后失效的 SVID 会列在这里。
            </span>
            <button type="button" className="primary" disabled={busy} onClick={() => void save()}>
              {busy ? '下发中…' : '下发配置'}
            </button>
            <button
              type="button"
              disabled={busy || !state?.configured}
              onClick={() => void command(host.id, true)}
            >
              手动开始
            </button>
            <button
              type="button"
              disabled={busy || !state?.running}
              onClick={() => void command(host.id, false)}
            >
              手动停止
            </button>
            <button type="button" onClick={exportCsv} disabled={(state?.series ?? []).length === 0}>
              导出 CSV
            </button>
            <button type="button" onClick={() => fileRef.current?.click()}>
              导入 CSV
            </button>
            <input
              ref={fileRef}
              type="file"
              accept=".csv,text/csv"
              style={{ display: 'none' }}
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = '';
                if (file !== undefined) {
                  void importCsv(file);
                }
              }}
            />
            {imported !== null && (
              <button type="button" onClick={() => setImported(null)}>退出导入视图</button>
            )}
          </div>

          {(state?.staleSvids ?? []).length > 0 && (
            <p className="problems">
              热重载后失效的 SVID：{(state?.staleSvids ?? []).join(', ')}
            </p>
          )}

          {imported !== null && (
            <p className="inline-note">正在查看导入文件 {imported.name}（只在本页显示，不写回采集状态）。</p>
          )}

          {displaySeries.length > 0 && (
            <div className="collector-charts">
              {displaySeries.map((item) => {
                const from = (item as PreviewSeries & { __from?: number }).__from ?? 0;
                const to = (item as PreviewSeries & { __to?: number }).__to ?? from + 1000;
                const bucketMs = Math.max(1, Math.round((to - from) / Math.max(1, item.avg.length)));
                const dataMin = item.min.length > 0 ? Math.min(...item.min) : 0;
                const dataMax = item.max.length > 0 ? Math.max(...item.max) : 0;
                const last = item.avg.length > 0 ? item.avg[item.avg.length - 1] : 0;
                return (
                  <div className="collector-chart collector-chart-large" key={item.svid}>
                    <div className="collector-chart-head">
                      <span className="mono">{item.svid}</span>
                      <span>{item.owner}</span>
                      {(item.unit ?? '') !== '' && <span className="hint-inline">{item.unit}</span>}
                      <span className="spacer" />
                      <span className="hint-inline">
                        最近 {last.toFixed(2)} · 范围 {dataMin.toFixed(2)}–{dataMax.toFixed(2)}
                      </span>
                    </div>
                    <VariableChart
                      series={item}
                      fromMs={from}
                      bucketMs={bucketMs}
                      height={220}
                      eventLabel="事件"
                    />
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}

      <PanelFootnote
        items={[
          { label: '采样点', value: state?.samples ?? 0 },
          { label: '解码错误', value: state?.decodeErrors ?? 0 },
          { label: '最近 RTT', value: state?.lastRttMs === undefined ? '—' : `${state.lastRttMs} ms` },
        ]}
      />
    </Panel>
  );
}
