import { useMemo, useState } from 'react';
import type { PanelBaseProps } from '../panels/types';
import type { NoticeWire } from '../state/useEventStream';
import { LiveEventList } from '../ui/LiveEventList';
import { downloadText } from '../lib/table';

/** 事件去重键:历史与实时缓冲合并时用。 */
function eventKey(notice: NoticeWire): string {
  return `${notice.at}-${notice.sessionId}-${String(notice.data.ceid ?? '')}`;
}

/**
 * 实时事件流页（全局）。
 *
 * <p>只做一件事:滚动展示 SSE 的类型化 S6F11 通知,可按设备/CEID 过滤、
 * 点设备名下钻;「载入历史」从报文历史里取最近的 S6F11 补进来
 * (CEID 从报文体反解,仅供参考展示)。事件编排在设备页,这里只看不配。</p>
 */
export function EventsPage({ backend, liveEvents = [], goTo }: PanelBaseProps): JSX.Element {
  const [filter, setFilter] = useState('');
  const [historyEvents, setHistoryEvents] = useState<NoticeWire[]>([]);
  const [historyBusy, setHistoryBusy] = useState(false);

  /** 实时缓冲(最新) + 已载入的历史页,按时间倒序合并去重。 */
  const merged = useMemo(() => {
    const seen = new Set(liveEvents.map(eventKey));
    const fresh = historyEvents.filter((notice) => !seen.has(eventKey(notice)));
    return [...liveEvents, ...fresh].sort((left, right) => right.at - left.at);
  }, [liveEvents, historyEvents]);

  const visible = useMemo(() => {
    const keyword = filter.trim().toLowerCase();
    return merged
      .filter((notice) => keyword === ''
        || notice.sessionId.toLowerCase().includes(keyword)
        || String(notice.data.ceid ?? '').includes(keyword));
  }, [merged, filter]);

  const openDevice = (sessionId: string) => goTo?.(`device:${sessionId}`);

  const loadHistory = async () => {
    setHistoryBusy(true);
    try {
      const page = await backend.loadEventHistory();
      if (page.length > 0) {
        setHistoryEvents((previous) => {
          const seen = new Set(previous.map(eventKey));
          return [...previous, ...page.filter((notice) => !seen.has(eventKey(notice)))];
        });
      }
    } finally {
      setHistoryBusy(false);
    }
  };

  const exportCsv = () => {
    if (visible.length === 0) {
      return;
    }
    const lines = ['time,session,ceid,plan'].concat(
      visible.map((notice) => {
        const cells = [
          new Date(notice.at).toISOString(),
          notice.sessionId,
          String(notice.data.ceid ?? ''),
          typeof notice.data.planId === 'string' ? notice.data.planId : '',
        ];
        return cells.map((cell) => `"${cell.replace(/"/g, '""')}"`).join(',');
      }),
    );
    downloadText(`events-${Date.now()}.csv`, `﻿${lines.join('\r\n')}`, 'text/csv;charset=utf-8');
  };

  return (
    <div className="panel-stack">
      <section className="panel-section">
        <div className="section-head">
          <h2>实时事件流（S6F11）</h2>
          <div className="toolbar">
            <input
              type="search"
              placeholder="按设备 / CEID 过滤"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              aria-label="事件流过滤"
            />
            <button
              type="button"
              disabled={historyBusy}
              onClick={() => void loadHistory()}
              title="从报文历史取最近的 S6F11 补进来(CEID 从报文体反解,仅供参考;服务端最多保留 5000 条报文)"
            >
              {historyBusy ? '载入中…' : '载入历史'}
            </button>
            <button type="button" onClick={exportCsv} disabled={visible.length === 0} title="把当前列表里的事件导出为 CSV">
              导出 CSV
            </button>
          </div>
        </div>
        <LiveEventList
          events={visible}
          showDevice
          onOpenDevice={openDevice}
          emptyText="暂无实时事件。事件流来自 SSE 的类型化通知——连上后端、设备上报后这里实时滚动;编排与手动触发在设备页·事件。"
        />
      </section>
    </div>
  );
}
