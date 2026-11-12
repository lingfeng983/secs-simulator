import { useEffect, useMemo, useState } from 'react';
import { Panel } from '../../../shared/ui/Panel';
import { PresetSelect } from '../../../shared/ui/PresetSelect';
import type { PanelBaseProps } from '../types';
import type { HostSimCommands } from '../../../state/useHostSims';
import type { BurstResult, SentMessageResult } from '../../../api';

/** 主机方向的常用报文模板；只列设备真正实现了的报文。 */
export interface HostPreset {
  label: string;
  group: string;
  /** 一句话说明"这条报文是干什么的" */
  desc: string;
  /** 正常回复的函数号（仅说明，不参与发送） */
  reply: string;
  sml: string;
  /** 常用项直接放按钮，其余收进下拉 */
  quick?: boolean;
}

export const HOST_PRESETS: readonly HostPreset[] = [
  {
    label: 'S1F1 在吗',
    group: '建链与控制',
    desc: '主机探活/心跳：确认设备还在线，设备回 S1F2。',
    reply: 'S1F2',
    sml: 'S1F1 W.',
    quick: true,
  },
  {
    label: 'S1F13 建立通信',
    group: '建链与控制',
    desc: '建立通信：主机请求建链，设备回 S1F14（COMMACK）；之后才能发其它报文。',
    reply: 'S1F14',
    sml: 'S1F13 W.',
    quick: true,
  },
  {
    label: 'S1F17 上线',
    group: '建链与控制',
    desc: '请求设备进入 ONLINE；只有 ONLINE 后设备才主动上报 S6F1 / S6F11。',
    reply: 'S1F18',
    sml: 'S1F17 W.',
    quick: true,
  },
  {
    label: 'S1F15 下线',
    group: '建链与控制',
    desc: '请求设备退出 ONLINE，设备回 S1F16；主动上报随之停止。',
    reply: 'S1F16',
    sml: 'S1F15 W.',
    quick: true,
  },
  {
    label: 'S1F3 读状态变量',
    group: '查询',
    desc: '按 SVID 列表读当前值（这里读 1001/1003），设备回 S1F4。',
    reply: 'S1F4',
    sml: 'S1F3 W\n<L\n  <U4 1001>\n  <U4 1003>\n>.',
    quick: true,
  },
  {
    label: 'S1F11 读变量名',
    group: '查询',
    desc: '查询 SVID 的名称/单位（这里查 1001/1003），设备回 S1F12。',
    reply: 'S1F12',
    sml: 'S1F11 W\n<L\n  <U4 1001>\n  <U4 1003>\n>.',
  },
  {
    label: 'S2F13 读全部常量',
    group: '查询',
    desc: '读设备常量（空列表 = 全部 ECV），设备回 S2F14。',
    reply: 'S2F14',
    sml: 'S2F13 W.',
  },
  {
    label: 'S2F17 读设备时间',
    group: '查询',
    desc: '读设备时钟，设备回 S2F18。',
    reply: 'S2F18',
    sml: 'S2F17 W.',
  },
  {
    label: 'S2F31 对时',
    group: '查询',
    desc: '把设备时间设为 A16 格式（yyyyMMddHHmmsscc），设备回 S2F32。',
    reply: 'S2F32',
    sml: 'S2F31 W\n<A "20261028003000">.',
  },
  {
    label: 'S2F33 定义报告',
    group: '报告与事件',
    desc: '定义报告 RPTID 3001，由 SVID 1001/1004 组成，设备回 S2F34。',
    reply: 'S2F34',
    sml: 'S2F33 W\n<L\n  <U4 0>\n  <L\n    <L\n      <U4 3001>\n      <L\n        <U4 1001>\n        <U4 1004>\n      >\n    >\n  >\n>.',
  },
  {
    label: 'S2F35 链接事件',
    group: '报告与事件',
    desc: '把 CEID 2001 链接到 RPTID 3001，事件触发时按该报告上报，设备回 S2F36。',
    reply: 'S2F36',
    sml: 'S2F35 W\n<L\n  <L\n    <U4 2001>\n    <L\n      <U4 3001>\n    >\n  >\n>.',
  },
  {
    label: 'S2F37 停用全部事件',
    group: '报告与事件',
    desc: 'CEED=0、CEID 列表为空 = 关闭全部事件上报，设备回 S2F38。',
    reply: 'S2F38',
    sml: 'S2F37 W\n<L\n  <BOOLEAN 0x00>\n  <L>\n>.',
  },
  {
    label: 'S2F37 启用 CEID 2001',
    group: '报告与事件',
    desc: 'CEED=1、CEID 列表为 2001 = 只启用该事件，设备回 S2F38。',
    reply: 'S2F38',
    sml: 'S2F37 W\n<L\n  <BOOLEAN 0x01>\n  <L\n    <U4 2001>\n  >\n>.',
  },
  {
    label: 'S2F23 开始 Trace',
    group: '采集与终端',
    desc: '按 DSPER=1s、REPGSZ=1 采集 1001/1003，设备回 S2F24 后周期推 S6F1。',
    reply: 'S2F24',
    sml: 'S2F23 W\n<L\n  <U4 1>\n  <A "000001">\n  <U4 100>\n  <U4 1>\n  <L\n    <U4 1001>\n    <U4 1003>\n  >\n>.',
    quick: true,
  },
  {
    label: 'S2F23 停止 Trace',
    group: '采集与终端',
    desc: 'TOTSMP=0 = 终止该 TRID 的周期采样，设备回 S2F24。',
    reply: 'S2F24',
    sml: 'S2F23 W\n<L\n  <U4 1>\n  <A "000000">\n  <U4 0>\n  <U4 1>\n  <L>\n>.',
  },
  {
    label: 'S10F3 终端消息',
    group: '采集与终端',
    desc: '向设备操作员显示一行文本（TID=0）；带 W，设备回 S10F4。',
    reply: 'S10F4',
    sml: 'S10F3 W\n<L\n  <B 0x0>\n  <A "Hello from host">\n>.',
  },
];

export const QUICK_PRESETS = HOST_PRESETS.filter((preset) => preset.quick === true);

/** 下拉按组归类；保持定义顺序。 */
export const PRESET_GROUPS = [...new Set(HOST_PRESETS.map((preset) => preset.group))].map((group) => ({
  label: group,
  items: HOST_PRESETS.filter((preset) => preset.group === group)
    .map((preset) => ({ label: preset.label, value: preset.sml })),
}));

/** 一次批量发送的结果。 */
interface SendOutcome {
  hostId: string;
  ok: boolean;
  summary: string;
  detail: string;
}

/** 压测统计的展示行。 */
interface BurstOutcome {
  hostId: string;
  ok: boolean;
  text: string;
  detail: string;
}

/**
 * ① 消息发送（以主机角色）。
 *
 * <p>主机的新建/断开只在「主机实例」页做；这里选"发给哪几台"。
 * 多选后同一条 SML 依次发给每台主机，结果逐台列出——修一条报文、
 * 给多台设备同时下发时不用来回切主机。</p>
 *
 * <p>收发记录已独立到「消息监控」页：那里能看全部主机（含远程）的实时收发，
 * 这个页只负责"发"。同一条报文要连发多次用页内的压测。</p>
 */
export function HostSimMessagesPanel({
  notifier,
  hostSims,
}: PanelBaseProps & { hostSims: HostSimCommands }): JSX.Element {
  const [draft, setDraft] = useState('S1F13 W');
  const [busy, setBusy] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [outcomes, setOutcomes] = useState<SendOutcome[]>([]);

  /* 压测参数 */
  const [stressCount, setStressCount] = useState('100');
  const [stressInterval, setStressInterval] = useState('0');
  const [stressBusy, setStressBusy] = useState(false);
  const [burstOutcomes, setBurstOutcomes] = useState<BurstOutcome[]>([]);

  const openHosts = useMemo(() => hostSims.hosts.filter((host) => host.open), [hostSims.hosts]);
  const openIdsKey = openHosts.map((host) => host.id).join('|');

  /* 默认全选；主机集合变化时去掉已断开的实例 */
  useEffect(() => {
    setSelectedIds((previous) => {
      const still = previous.filter((id) => openHosts.some((host) => host.id === id));
      return still.length > 0 ? still : openHosts.map((host) => host.id);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openIdsKey]);

  const selected = openHosts.filter((host) => selectedIds.includes(host.id));

  const toggle = (hostId: string) => {
    setSelectedIds((previous) => (
      previous.includes(hostId)
        ? previous.filter((id) => id !== hostId)
        : [...previous, hostId]
    ));
  };

  const send = async () => {
    if (selected.length === 0) {
      notifier.error('至少选一台主机');
      return;
    }
    setBusy(true);
    setOutcomes([]);
    const results: SendOutcome[] = [];
    for (const host of selected) {
      try {
        const raw = await hostSims.send(host.id, draft);
        const result = raw === null ? null : (JSON.parse(raw) as SentMessageResult);
        if (result === null) {
          results.push({ hostId: host.id, ok: false, summary: '无结果', detail: '' });
          continue;
        }
        const summary = result.replySml != null
          ? `S${result.stream}F${result.function} → S${result.replyStream}F${result.replyFunction}`
          : result.timedOut
            ? `S${result.stream}F${result.function} → 等待回复超时`
            : `S${result.stream}F${result.function} → 已发送`;
        results.push({ hostId: host.id, ok: true, summary, detail: `${result.elapsedMs} ms` });
      } catch (error) {
        results.push({ hostId: host.id, ok: false, summary: '发送失败', detail: (error as Error).message });
      }
    }
    setOutcomes(results);
    setBusy(false);
    const okCount = results.filter((item) => item.ok).length;
    if (okCount === results.length) {
      notifier.success(`已发送到 ${okCount} 台主机`);
    } else {
      notifier.error(`发送完成：成功 ${okCount}，失败 ${results.length - okCount}`);
    }
  };

  /**
   * 压测发送：服务端后台连发同一条 SML。
   *
   * <p>逐台串行跑——压测本身已经是"一条报文连发 N 次"，多台同时跑只会让
   * 统计互相干扰，也容易把设备侧 W 位队列打满。</p>
   */
  const runStress = async () => {
    const count = Number.parseInt(stressCount, 10);
    const interval = Number.parseInt(stressInterval, 10) || 0;
    if (draft.trim() === '') {
      notifier.error('SML 不能为空');
      return;
    }
    if (!Number.isFinite(count) || count < 1 || count > 5000) {
      notifier.error('次数要在 1..5000 之间');
      return;
    }
    if (selected.length === 0) {
      notifier.error('至少选一台主机');
      return;
    }
    setStressBusy(true);
    setBurstOutcomes([]);
    const results: BurstOutcome[] = [];
    for (const host of selected) {
      try {
        const stats: BurstResult = await hostSims.burst(host.id, draft, count, interval);
        results.push({
          hostId: host.id,
          ok: stats.failed === 0,
          text: `成功 ${stats.sent} / 失败 ${stats.failed}`,
          detail: `耗时 ${stats.durationMs} ms · P50 ${stats.latencyP50} ms · P95 ${stats.latencyP95} ms · Max ${stats.latencyMax} ms`,
        });
      } catch (error) {
        results.push({ hostId: host.id, ok: false, text: '压测失败', detail: (error as Error).message });
      }
    }
    setBurstOutcomes(results);
    setStressBusy(false);
    const sent = results.reduce((sum, item) => sum + Number(/成功 (\d+)/.exec(item.text)?.[1] ?? 0), 0);
    notifier.info(`压测完成：已发 ${sent} 条（${results.length} 台主机）`);
  };

  return (
    <Panel
      id="panel-hostsim-messages"
      title="① 消息发送（以主机角色）"
      description="选择一至多台已连接主机，发送同一条 SML；同一条报文可压测连发。收发记录在「消息监控」页。主机的新建/断开在「主机实例」页。"
    >
      <div className="host-multi">
        <div className="host-multi-head">
          <strong>发送到主机</strong>
          <span className="hint-inline">已选 {selected.length} / {openHosts.length} 台</span>
          <span className="spacer" />
          <button type="button" onClick={() => setSelectedIds(openHosts.map((host) => host.id))}>
            全选
          </button>
          <button type="button" onClick={() => setSelectedIds([])} disabled={selected.length === 0}>
            清空
          </button>
        </div>
        {openHosts.length === 0 ? (
          <p className="hint">还没有已连接的主机：先到「主机实例」页新建一个。</p>
        ) : (
          <div className="host-check-row">
            {openHosts.map((host) => (
              <label key={host.id} className="check-inline">
                <input
                  type="checkbox"
                  checked={selectedIds.includes(host.id)}
                  onChange={() => toggle(host.id)}
                />
                <span className="mono">{host.id}</span>
                <span className="hint-inline">{host.remote ? `远程 ${host.target}` : host.session}</span>
              </label>
            ))}
          </div>
        )}
      </div>

      {openHosts.length === 0 ? null : (
        <>
          <div className="preview-controls">
            {QUICK_PRESETS.map((preset) => (
              <button
                key={preset.label}
                type="button"
                title={preset.desc}
                onClick={() => setDraft(preset.sml)}
              >
                {preset.label}
              </button>
            ))}
            <PresetSelect
              groups={PRESET_GROUPS}
              onPick={setDraft}
            />
          </div>

          <textarea
            className="sml-editor"
            spellCheck={false}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            aria-label="主机角色 SML 报文内容"
          />

          <div className="editor-foot">
            <span className="hint">
              SML 格式；primary（奇数 function）不写 W 也默认要求回复，secondary 不能带 W。
              预设只是模板，选中后仍可改 SVID / CEID / 时间再发送。
              同一份报文会依次发给选中的每一台主机；收发记录在「消息监控」页。
            </span>
            <button
              type="button"
              className="primary"
              disabled={busy || draft.trim() === '' || selected.length === 0}
              onClick={() => void send()}
            >
              {busy ? '发送中…' : `发送到 ${selected.length} 台`}
            </button>
          </div>

          <details className="preset-guide">
            <summary>预设说明：这些报文分别是什么（{HOST_PRESETS.length} 条）</summary>
            <p className="hint">
              预设是主机方向常用报文的模板，不是自动流程：选中后只填入编辑框，
              可以继续改 SVID / CEID / 时间再发送。W 表示等待设备回复。
            </p>
            <div className="preset-guide-grid">
              {PRESET_GROUPS.map((group) => (
                <section key={group.label}>
                  <h4>{group.label}</h4>
                  <dl>
                    {HOST_PRESETS.filter((preset) => preset.group === group.label).map((preset) => (
                      <div className="preset-guide-item" key={preset.label}>
                        <dt>{preset.label}</dt>
                        <dd>
                          {preset.desc}
                          <span className="hint-inline">正常回复 {preset.reply}</span>
                        </dd>
                      </div>
                    ))}
                  </dl>
                </section>
              ))}
            </div>
          </details>

          {outcomes.length > 0 && (
            <div className="effects-table-wrap">
              <table className="effects-table">
                <thead>
                  <tr>
                    <th>主机</th>
                    <th>结果</th>
                    <th>耗时 / 原因</th>
                  </tr>
                </thead>
                <tbody>
                  {outcomes.map((outcome) => (
                    <tr key={outcome.hostId}>
                      <td className="mono">{outcome.hostId}</td>
                      <td>
                        <span className={`badge ${outcome.ok ? 'tone-ok' : 'tone-danger'}`}>
                          {outcome.summary}
                        </span>
                      </td>
                      <td className="hint-inline">{outcome.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h3 className="sub-title">压测发送（服务端循环这条报文）</h3>
          <div className="toolbar">
            <label className="field">
              次数
              <input
                value={stressCount}
                onChange={(event) => setStressCount(event.target.value)}
                inputMode="numeric"
                aria-label="压测次数"
              />
            </label>
            <label className="field">
              间隔 ms
              <input
                value={stressInterval}
                onChange={(event) => setStressInterval(event.target.value)}
                inputMode="numeric"
                title="0 = 全速"
                aria-label="压测间隔"
              />
            </label>
            <button
              type="button"
              className="primary"
              disabled={stressBusy || busy || draft.trim() === '' || selected.length === 0}
              onClick={() => void runStress()}
              title="在服务端循环发送编辑框里这条 SML，消除 HTTP 往返失真"
            >
              {stressBusy ? '压测中…' : `压测发送（${selected.length} 台）`}
            </button>
            <span className="hint-inline">
              服务端后台执行：W 位会逐条等回复，最长 5 分钟自动收尾。
            </span>
          </div>

          {burstOutcomes.length > 0 && (
            <div className="effects-table-wrap">
              <table className="effects-table">
                <thead>
                  <tr>
                    <th>主机</th>
                    <th>结果</th>
                    <th>耗时 / 分位</th>
                  </tr>
                </thead>
                <tbody>
                  {burstOutcomes.map((item) => (
                    <tr key={item.hostId}>
                      <td className="mono">{item.hostId}</td>
                      <td>
                        <span className={`badge ${item.ok ? 'tone-ok' : 'tone-warn'}`}>{item.text}</span>
                      </td>
                      <td className="hint-inline">{item.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </Panel>
  );
}
