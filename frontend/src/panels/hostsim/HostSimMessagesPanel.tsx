import { useEffect, useMemo, useState } from 'react';
import { Panel } from '../../ui/Panel';
import { PresetSelect } from '../../ui/PresetSelect';
import { MessageFeed } from '../../ui/MessageFeed';
import type { PanelBaseProps } from '../types';
import type { HostSimCommands } from '../../state/useHostSims';
import type { SentMessageResult } from '../../api';
import { downloadText, safeFilename } from '../../lib/table';

/** 主机方向的常用报文模板；只列设备真正实现了的报文。 */
interface HostPreset {
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

const HOST_PRESETS: readonly HostPreset[] = [
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

const QUICK_PRESETS = HOST_PRESETS.filter((preset) => preset.quick === true);

/** 下拉按组归类；保持定义顺序。 */
const PRESET_GROUPS = [...new Set(HOST_PRESETS.map((preset) => preset.group))].map((group) => ({
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

/**
 * ① 消息发送（以主机角色）。
 *
 * <p>主机的新建/断开只在「主机实例」页做；这里选"发给哪几台"。
 * 多选后同一条 SML 依次发给每台主机，结果逐台列出——修一条报文、
 * 给多台设备同时下发时不用来回切主机。</p>
 */
export function HostSimMessagesPanel({
  backend,
  notifier,
  hostSims,
  messages = [],
}: PanelBaseProps & { hostSims: HostSimCommands }): JSX.Element {
  const [draft, setDraft] = useState('S1F13 W');
  const [busy, setBusy] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [outcomes, setOutcomes] = useState<SendOutcome[]>([]);

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
  const viewHost = hostSims.selected;

  /** 只导出当前主机的报文；方向转成主机视角（与页面显示一致）。 */
  const exportHostLog = async () => {
    if (viewHost === null || backend.client === null) {
      return;
    }
    try {
      const text = await backend.client.messagesJsonl(viewHost.session, '', 5000);
      const lines = text.split('\n').filter((line) => line.trim() !== '').map((line) => {
        const item = JSON.parse(line) as { direction?: string; perspective?: string; hostId?: string };
        item.direction = item.direction === 'in' ? 'out' : 'in';
        item.perspective = 'host';
        item.hostId = viewHost.id;
        return JSON.stringify(item);
      });
      downloadText(
        `messages-host-${safeFilename(viewHost.id)}.jsonl`,
        lines.join('\n') + (lines.length > 0 ? '\n' : ''),
        'application/jsonl;charset=utf-8',
      );
    } catch (error) {
      notifier.error('导出该主机报文失败', (error as Error).message);
    }
  };

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

  return (
    <Panel
      id="panel-hostsim-messages"
      title="① 消息发送（以主机角色）"
      description="选择一至多台已连接主机，发送同一条 SML；下方按主机查看收发记录。主机的新建/断开在「主机实例」页。"
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
              同一份报文会依次发给选中的每一台主机；回复与耗时逐台列在下方。
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
        </>
      )}

      <section className="panel-section">
        <div className="section-head">
          <h2>收发记录（主机视角）</h2>
          <label className="field">
            查看
            <select
              value={viewHost?.id ?? ''}
              onChange={(event) => hostSims.select(event.target.value)}
              aria-label="查看哪台主机的收发记录"
            >
              {hostSims.hosts.length === 0 && <option value="">（还没有主机）</option>}
              {hostSims.hosts.map((host) => (
                <option key={host.id} value={host.id}>
                  {host.id} · {host.remote ? `远程 ${host.target}` : host.session}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            onClick={() => void exportHostLog()}
            disabled={viewHost === null || viewHost.remote || backend.client === null}
            title="只导出当前主机的报文，方向按主机视角翻转"
          >
            导出该主机报文
          </button>
        </div>
        {viewHost === null ? (
          <p className="hint">先在上面选一台主机；它的收发报文会实时列在这里。</p>
        ) : viewHost.remote ? (
          <p className="hint">
            当前是远程主机（<code>{viewHost.target}</code>）：报文不经过本进程的事件流，无法在这里监视；
            可到对端设备的日志里查。
          </p>
        ) : (
          <MessageFeed
            messages={messages.filter((message) => message.sessionId === viewHost.session)}
            perspective="host"
            emptyText="还没有收发报文。"
            emptyHint="主机发出请求、设备回复或主动上报后，这里实时滚动。"
          />
        )}
      </section>
    </Panel>
  );
}
