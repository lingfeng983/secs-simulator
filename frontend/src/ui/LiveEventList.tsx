import type { NoticeWire } from '../state/useEventStream';

/**
 * 实时事件(S6F11 类型化通知)列表——事件流页与设备事件子页共用。
 *
 * <p>showDevice 时设备名是链接(全局页下钻);设备子页里自己就是本设备,不用链。</p>
 */
export function LiveEventList({
  events,
  showDevice = false,
  onOpenDevice,
  emptyText = '暂无实时事件。',
}: {
  events: NoticeWire[];
  showDevice?: boolean;
  onOpenDevice?: (sessionId: string) => void;
  emptyText?: string;
}): JSX.Element {
  if (events.length === 0) {
    return <p className="hint">{emptyText}</p>;
  }
  return (
    <ul className="live-event-list">
      {events.slice(0, 50).map((notice, index) => (
        <li key={`${notice.at}-${index}`}>
          <span className="mono">{new Date(notice.at).toLocaleTimeString()}</span>
          {showDevice && onOpenDevice !== undefined ? (
            <button type="button" className="link" onClick={() => onOpenDevice(notice.sessionId)}>
              {notice.sessionId}
            </button>
          ) : (
            <span>{notice.sessionId}</span>
          )}
          <span>CEID {String(notice.data.ceid ?? '-')}</span>
          {typeof notice.data.planId === 'string' && notice.data.planId !== '' && (
            <span className="hint-inline">{notice.data.planId}</span>
          )}
        </li>
      ))}
    </ul>
  );
}
