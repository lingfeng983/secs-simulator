import { useEffect, useMemo, useRef, useState } from 'react';
import { Panel, StatCard } from '../ui/Panel';
import { downloadText } from '../lib/table';
import type { PanelBaseProps } from './types';

/**
 * 事件流控制台。
 *
 * <p>以前操作反馈只写到这里、还埋在页面最底下，用户点了按钮完全不知道结果。
 * 现在每次操作都会同时弹一条提示条，这个面板退居"事后查账"的角色，
 * 所以加了搜索与复制。</p>
 */
export function ConsolePanel({ backend }: PanelBaseProps): JSX.Element {
  const { log, clearLog } = backend;
  const [query, setQuery] = useState('');
  const [follow, setFollow] = useState(true);
  const logRef = useRef<HTMLPreElement | null>(null);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (needle === '') {
      return log;
    }
    return log.filter((line) => line.text.toLowerCase().includes(needle));
  }, [log, query]);

  /* 跟随最新：只在开着的时候滚，滚上去看历史时不要把人拽回来 */
  useEffect(() => {
    if (!follow || logRef.current === null) {
      return;
    }
    logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [visible, follow]);

  return (
    <Panel
      id="panel-console"
      title="事件流"
      description="连接状态与操作记录。这里是事后查账用的；即时反馈会直接弹提示条。"
      stats={
        <>
          <StatCard label="日志条数" value={log.length} />
          <StatCard label="当前过滤" value={visible.length} />
        </>
      }
      actions={
        <>
          <input
            type="search"
            className="table-search"
            placeholder="搜索日志"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            aria-label="搜索日志"
          />
          <label className="check-inline">
            <input type="checkbox" checked={follow} onChange={(event) => setFollow(event.target.checked)} />
            跟随最新
          </label>
          <button
            type="button"
            onClick={() => downloadText('event-log.txt', log.map((line) => `[${line.at}] ${line.text}`).join('\n'))}
            disabled={log.length === 0}
          >
            导出
          </button>
          <button type="button" onClick={clearLog} disabled={log.length === 0} title="只清界面">
            清空
          </button>
        </>
      }
    >
      {visible.length === 0 ? (
        <p className="hint">
          {log.length === 0 ? '暂无日志。连接后端后，状态变化与操作结果会写在这里。' : '没有匹配的日志行。'}
        </p>
      ) : (
        <pre className="log" ref={logRef}>
          {visible.map((line, index) => `[${line.at}] ${line.text}`).join('\n')}
        </pre>
      )}
    </Panel>
  );
}
