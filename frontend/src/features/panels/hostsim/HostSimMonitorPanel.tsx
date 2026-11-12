import { useMemo, useState } from 'react';
import { Panel, PanelFootnote } from '../../../shared/ui/Panel';
import { MessageFeed } from '../../../shared/ui/MessageFeed';
import type { PanelBaseProps } from '../types';
import type { HostSimCommands } from '../../../state/useHostSims';
import { downloadText } from '../../../shared/lib/table';
import { attributeHostMessages } from '../../../shared/lib/hostMessages';

/**
 * 方向过滤（主机视角的语义）。
 *
 * <p>{@code sent} = 主机发出，{@code received} = 设备发来。报文本身的
 * {@code direction} 是全项目统一的<b>设备视角</b>（{@code in} = 设备收到），
 * 所以 {@code sent} 对应 {@code direction === 'in'}——这里不用 in/out 命名，
 * 免得多一层翻转看错。</p>
 */
type DirectionFilter = 'all' | 'sent' | 'received';

/**
 * 主机模拟 · 消息监控。
 *
 * <p>主机模拟页原来把收发记录塞在「① 消息发送」下面，只能看当前选中的一台，
 * 而且远程主机的报文根本进不来（对端不在本进程，设备侧事件流看不见）。
 * 现在独立成一页：</p>
 *
 * <ul>
 *   <li><b>实时</b>：所有已连接主机实例的收发都汇进来，按时间倒序滚动；</li>
 *   <li><b>收发都有</b>：本机主机的报文由设备会话发布，远程主机由主机实例自己发布；</li>
 *   <li><b>过滤</b>：按主机、方向、报文类别（探测/采集/Trace/事件/…）、SxFy 关键字过滤；</li>
 *   <li><b>导出</b>：导出当前过滤结果（主机视角的 JSONL）。</li>
 * </ul>
 */
export function HostSimMonitorPanel({
  backend,
  notifier,
  hostSims,
  messages = [],
}: PanelBaseProps & { hostSims: HostSimCommands }): JSX.Element {
  const [hostFilter, setHostFilter] = useState('');
  const [direction, setDirection] = useState<DirectionFilter>('all');
  const [sfFilter, setSfFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  const hosts = hostSims.hosts;

  /**
   * 归属到本页的报文。
   *
   * <p>规则见 {@link attributeHostMessages}：远程主机带 {@code hostId} 直接归属，
   * 本机主机按"当前连着这台会话的实例"认领设备侧那一份。</p>
   */
  const hostMessages = useMemo(
    () => attributeHostMessages(messages, hosts),
    [messages, hosts],
  );

  const filtered = useMemo(() => {
    /* 统一成小写比较：输入容错 S6F1 / s6f1 / 带空格，拼接出来的标识也是小写 */
    const sf = sfFilter.trim().toLowerCase().replace(/\s+/g, '');
    const key = keyword.trim().toLowerCase();
    return hostMessages.filter((message) => {
      if (hostFilter !== '' && message.hostId !== hostFilter) {
        return false;
      }
      /* 报文方向是设备视角：设备收到（in）= 主机发出 */
      if (direction === 'sent' && message.direction !== 'in') {
        return false;
      }
      if (direction === 'received' && message.direction !== 'out') {
        return false;
      }
      if (sf !== '' && !`s${message.stream}f${message.function}`.startsWith(sf)) {
        return false;
      }
      if (key !== '' && !message.body.toLowerCase().includes(key)) {
        return false;
      }
      return true;
    });
  }, [hostMessages, hostFilter, direction, sfFilter, keyword]);

  /* 设备视角的 in = 主机发出 */
  const sentByHost = hostMessages.filter((message) => message.direction === 'in').length;
  const receivedByHost = hostMessages.length - sentByHost;

  /**
   * 导出当前过滤结果。
   *
   * <p>方向要翻成主机视角再落盘：{@code MessageFeed} 的导入会按
   * {@code perspective=host} 再翻回来，不翻的话导出再导入就变成反的。</p>
   */
  const exportFiltered = () => {
    const now = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
    const rows = filtered.map((message) => JSON.stringify({
      ...message,
      perspective: 'host',
      direction: message.direction === 'in' ? 'out' : 'in',
    }));
    downloadText(
      `hostsim-monitor-${now}.jsonl`,
      rows.join('\n') + (rows.length > 0 ? '\n' : ''),
      'application/jsonl;charset=utf-8',
    );
    notifier.success(`已导出 ${filtered.length} 条报文`);
  };

  const remoteCount = hosts.filter((host) => host.remote).length;

  return (
    <Panel
      id="panel-hostsim-monitor"
      title="消息监控"
      description="所有主机实例的收发实时汇总：按主机、方向、报文类别与关键字过滤。本机与远程主机都在这里，远程主机的报文由实例自己采集（设备侧看不见）。新建/断开主机在「主机实例」页。"
      actions={
        <button
          type="button"
          onClick={exportFiltered}
          disabled={filtered.length === 0 || backend.client === null}
          title="导出当前过滤结果（主机视角 JSONL）"
        >
          导出过滤结果
        </button>
      }
    >
      <div className="toolbar monitor-filters">
        <label className="field">
          主机
          <select
            value={hostFilter}
            onChange={(event) => setHostFilter(event.target.value)}
            aria-label="按主机过滤"
          >
            <option value="">全部主机（{hosts.length}）</option>
            {hosts.map((host) => (
              <option key={host.id} value={host.id}>
                {host.id} · {host.remote ? `远程 ${host.target}` : host.session}
                {host.open ? '' : '（已断开）'}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          方向
          <select
            value={direction}
            onChange={(event) => setDirection(event.target.value as DirectionFilter)}
            aria-label="按方向过滤"
          >
            <option value="all">收发都看</option>
            <option value="sent">只看主机发出</option>
            <option value="received">只看设备发来</option>
          </select>
        </label>

        <label className="field">
          SxFy
          <input
            value={sfFilter}
            onChange={(event) => setSfFilter(event.target.value)}
            placeholder="如 S6F1"
            aria-label="按 SxFy 过滤"
          />
        </label>

        <label className="field field-wide">
          内容
          <input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜报文体里的 ID / 文本"
            aria-label="按报文内容过滤"
          />
        </label>

        <span className="spacer" />
        <button
          type="button"
          onClick={() => {
            setHostFilter('');
            setDirection('all');
            setSfFilter('');
            setKeyword('');
          }}
          disabled={hostFilter === '' && direction === 'all' && sfFilter === '' && keyword === ''}
        >
          清空过滤
        </button>
      </div>

      {hosts.length === 0 ? (
        <p className="hint">还没有主机实例：先到「主机实例」页新建一个，它的收发会实时出现在这里。</p>
      ) : (
        <MessageFeed
          messages={filtered}
          perspective="host"
          emptyText={hostMessages.length === 0 ? '还没有收发报文。' : '当前过滤条件下没有报文'}
          emptyHint={
            hostMessages.length === 0
              ? '主机发出请求、设备回复或主动上报后，这里实时滚动；也可以到「① 消息发送」页手动发一条。'
              : '放宽上面的主机 / 方向 / SxFy / 内容条件，或点「清空过滤」。'
          }
        />
      )}

      {remoteCount > 0 && (
        <p className="hint">
          其中 {remoteCount} 台是远程主机：它们连的对端不在本进程里，
          报文由主机实例自己采集，因此在「消息监控」才看得到（设备页的「消息」里没有）。
        </p>
      )}
      <p className="hint">
        实时视图共用界面缓冲（最近 5000 条，高吞吐时按时间淘汰最旧的）。
        需要完整记录请用「导出过滤结果」，或看磁盘日志
        <code>logs/messages.jsonl</code>（每条带 <code>hostId</code>）。
      </p>

      <PanelFootnote
        items={[
          { label: '发（主机→设备）', value: sentByHost },
          { label: '收（设备→主机）', value: receivedByHost },
          { label: '显示 / 合计', value: `${filtered.length} / ${hostMessages.length}` },
        ]}
      />
    </Panel>
  );
}
