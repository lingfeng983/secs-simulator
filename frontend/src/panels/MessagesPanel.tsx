import { useEffect, useMemo, useRef, useState } from 'react';
import type { MessageEntry } from '../messageHistory';
import { Panel, PanelFootnote } from '../ui/Panel';
import { highlightSml } from '../lib/smlHighlight';
import { DataTable, type Column } from '../ui/DataTable';
import { Modal } from '../ui/Modal';
import { formatClock, formatDateTime } from '../lib/format';
import type { PanelBaseProps } from './types';

type Direction = 'all' | 'in' | 'out';

/**
 * 报文面板。
 *
 * <p>SSE 只推"现在"；断线或刷新后点「载入历史」补齐。
 * 落盘位置是 <code>--work-dir/logs/messages.jsonl</code>，
 * 每个进程最多回看最近 5000 条。</p>
 *
 * <p>新增的过滤与暂停是为了解决"报文一多就没法看"：
 * 现场排查时通常只关心某一个会话的某一个方向，
 * 没必要在几百行里用眼睛找。</p>
 */
export function MessagesPanel({ backend, preferences, updatePreferences }: PanelBaseProps): JSX.Element {
  const { messages, messageTotal, sessions, activeSession, setActiveSession } = backend;
  const [sessionFilter, setSessionFilter] = useState('');
  const [direction, setDirection] = useState<Direction>('all');
  const [query, setQuery] = useState('');
  const [detail, setDetail] = useState<MessageEntry | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  /* 自动滚动：报文按时间倒序排，新到的在第一行。
     开着就把表格滚回顶部，用户的视线不用自己去追。 */
  useEffect(() => {
    if (!preferences.autoscroll) {
      return;
    }
    const scroller = scrollRef.current?.querySelector('.table-scroll');
    if (scroller !== null && scroller !== undefined) {
      scroller.scrollTop = 0;
    }
  }, [messages, preferences.autoscroll]);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return messages.filter((entry) => {
      if (sessionFilter !== '' && entry.msg.sessionId !== sessionFilter) {
        return false;
      }
      if (direction !== 'all' && entry.msg.direction !== direction) {
        return false;
      }
      if (needle !== '') {
        const haystack = `${entry.msg.sessionId} S${entry.msg.stream}F${entry.msg.function} ${entry.msg.body}`.toLowerCase();
        if (!haystack.includes(needle)) {
          return false;
        }
      }
      return true;
    });
  }, [messages, sessionFilter, direction, query]);

  const columns: Column<MessageEntry>[] = [
    {
      key: 'at',
      header: '时间',
      sortValue: (row) => row.msg.at,
      render: (row) => (
        <span className="mono nowrap" title={formatDateTime(row.msg.at)}>
          {formatClock(row.msg.at)}
        </span>
      ),
    },
    {
      key: 'session',
      header: '会话',
      sortValue: (row) => row.msg.sessionId,
      render: (row) => <span className="mono">{row.msg.sessionId}</span>,
    },
    {
      key: 'direction',
      header: '方向',
      sortValue: (row) => row.msg.direction,
      render: (row) => (
        <span className={`badge badge-${row.msg.direction === 'in' ? 'accent' : 'muted'}`}>
          {row.msg.direction === 'in' ? '← 收' : '→ 发'}
        </span>
      ),
    },
    {
      key: 'sf',
      header: '报文',
      sortValue: (row) => row.msg.stream * 100 + row.msg.function,
      render: (row) => (
        <span className="mono">
          S{row.msg.stream}F{row.msg.function}
          {row.msg.wbit ? ' W' : ''}
        </span>
      ),
    },
    {
      key: 'body',
      header: '报文体',
      className: 'body',
      sortValue: (row) => row.msg.body,
      render: (row) => (
        <button
          type="button"
          className="link-button sml-cell"
          onClick={() => setDetail(row)}
          title="查看完整报文体"
          /* 高亮输出已整体转义，不可能注入 */
          dangerouslySetInnerHTML={{
            __html: highlightSml(row.msg.body.length > 120 ? `${row.msg.body.slice(0, 120)}…` : row.msg.body),
          }}
        />
      ),
    },
  ];

  return (
    <Panel
      id="panel-messages"
      title="报文"
      description="实时报文流（SSE）。过滤、暂停与导出都在这里。"
      actions={
        <>
          <button
            type="button"
            onClick={() => updatePreferences({ pauseStream: !preferences.pauseStream })}
            title={preferences.pauseStream ? '恢复接收新报文' : '暂停接收（服务端照常收，界面上先攒着）'}
          >
            {preferences.pauseStream ? '恢复接收' : '暂停接收'}
          </button>
          <label className="check-inline">
            <input
              type="checkbox"
              checked={preferences.autoscroll}
              onChange={(event) => updatePreferences({ autoscroll: event.target.checked })}
            />
            自动滚动
          </label>
          <button
            type="button"
            onClick={() => void backend.loadHistory(sessionFilter, direction === 'all' ? '' : direction)}
            title={`按当前会话/方向过滤从服务端载入历史（服务端最多保留最近 5000 条；当前过滤：${sessionFilter === '' ? '全部会话' : sessionFilter} · ${direction === 'all' ? '全部方向' : direction === 'in' ? '收' : '发'}）`}
          >
            载入历史
          </button>
          <button
            type="button"
            disabled={messages.length === 0}
            onClick={() => {
              const oldest = messages.reduce((min, item) => Math.min(min, item.msg.at), Number.POSITIVE_INFINITY);
              if (Number.isFinite(oldest)) {
                void backend.loadHistory(sessionFilter, direction === 'all' ? '' : direction, oldest);
              }
            }}
            title="往更早翻一页(以面板里最早一条报文的时间为游标,继续往服务端要更早的历史)"
          >
            载入更早
          </button>
          <button type="button" onClick={() => void backend.exportMessages()}>
            导出 JSONL
          </button>
          <button
            type="button"
            onClick={() => backend.clearMessages()}
            title="只清界面；服务端日志文件不动"
          >
            清空
          </button>
        </>
      }
    >
      <div className="preview-controls">
        <label className="field">
          会话
          <select value={sessionFilter} onChange={(event) => setSessionFilter(event.target.value)}>
            <option value="">全部会话</option>
            {sessions.map((session) => (
              <option key={session.id} value={session.id}>
                {session.id}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          方向
          <select
            value={direction}
            onChange={(event) => setDirection(event.target.value as Direction)}
          >
            <option value="all">全部</option>
            <option value="in">← 收</option>
            <option value="out">→ 发</option>
          </select>
        </label>
        <input
          type="search"
          className="table-search"
          placeholder="搜索报文体 / SxFy"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="搜索报文"
        />
        <button
          type="button"
          onClick={() => {
            setSessionFilter('');
            setDirection('all');
            setQuery('');
          }}
          disabled={sessionFilter === '' && direction === 'all' && query === ''}
        >
          清除过滤
        </button>
        <span className="hint">
          当前操作会话：<span className="mono">{activeSession === '' ? '（无）' : activeSession}</span>
          <button type="button" className="link-button" onClick={() => setSessionFilter(activeSession)}>
            只看它
          </button>
        </span>
      </div>

      <div ref={scrollRef}>
        <DataTable
        tableKey="messages"
          columns={columns}
          rows={visible}
          rowKey={(row) => String(row.key)}
          exportName="messages"
          emptyText={
            messages.length === 0
              ? '暂无报文（主机连上并收发报文后会出现在这里）'
              : '当前过滤条件下没有报文'
          }
          pageSize={200}
          defaultSort={{ key: 'at', direction: 'desc' }}
          exportColumns={[
            { header: '时间', value: (row) => new Date(row.msg.at).toISOString() },
            { header: '会话', value: (row) => row.msg.sessionId },
            { header: '方向', value: (row) => row.msg.direction },
            { header: 'Stream', value: (row) => row.msg.stream },
            { header: 'Function', value: (row) => row.msg.function },
            { header: 'W 位', value: (row) => (row.msg.wbit ? 'W' : '') },
            { header: '报文体', value: (row) => row.msg.body },
          ]}
        />
      </div>

      <Modal
        open={detail !== null}
        title={detail === null ? '' : `S${detail.msg.stream}F${detail.msg.function}${detail.msg.wbit ? ' W' : ''}`}
        onClose={() => setDetail(null)}
      >
        {detail !== null && (
          <>
            <dl className="detail-list">
              <dt>时间</dt>
              <dd className="mono">{formatDateTime(detail.msg.at)}</dd>
              <dt>会话</dt>
              <dd className="mono">{detail.msg.sessionId}</dd>
              <dt>方向</dt>
              <dd>{detail.msg.direction === 'in' ? '← 收到' : '→ 发出'}</dd>
            </dl>
            <pre className="sml-result">{detail.msg.body === '' ? '（无 body）' : detail.msg.body}</pre>
          </>
        )}
      </Modal>
          <PanelFootnote
        items={[
          { label: '面板内', value: messages.length },
          { label: '当前筛选', value: visible.length },
          { label: '服务端记录', value: messageTotal },
          { label: '保留窗口', value: '最近 5000 条' },
          preferences.pauseStream
            ? { label: '接收', value: '已暂停', tone: 'warn' as const }
            : { label: '接收', value: '进行中', tone: 'ok' as const },
        ]}
      />
    </Panel>
  );
}
