import { useMemo, useRef, useState } from 'react';
import type { WireMessage } from '../../api';
import {
  MESSAGE_CATEGORIES,
  categorizeMessage,
  messageCounts,
  messageKey,
  type MessageCategory,
} from '../lib/messageFilter';
import { formatClock } from '../lib/format';
import { saveHint, saveTextFile } from '../lib/saveFile';
import { EmptyState } from './EmptyState';

/** 画面里最多渲染多少行：报文可能每秒几十条，DOM 不能无限长。 */
const MAX_ROWS = 2_000;

export interface MessageFeedProps {
  /** 已经按会话筛过的报文（时间升序）。 */
  messages: readonly WireMessage[];
  /** device = 设备视角；host = 主机视角（方向文案相反）。 */
  perspective: 'device' | 'host';
  emptyText?: string;
  emptyHint?: string;
}

const CATEGORY_TITLE: Record<MessageCategory, string> = Object.fromEntries(
  MESSAGE_CATEGORIES.map((category) => [category.id, category.hint]),
) as Record<MessageCategory, string>;

/**
 * 报文收发监视：实时列表 + 按类别屏蔽。
 *
 * <p>只看"有没有在跑"时，探测/采集/上报混在一起会刷屏：
 * 现场通常先屏蔽探测与采集，只留上报和报警。过滤只影响显示，不丢数据。</p>
 */
export function MessageFeed({
  messages,
  perspective,
  emptyText = '还没有报文。',
  emptyHint,
}: MessageFeedProps): JSX.Element {
  const [hidden, setHidden] = useState<ReadonlySet<MessageCategory>>(() => new Set());
  const [imported, setImported] = useState<WireMessage[]>([]);
  const [importNotice, setImportNotice] = useState('');
  const fileRef = useRef<HTMLInputElement | null>(null);

  /* 导入的离线日志与实时报文合并；按 key 去重，避免同一条显示两遍 */
  const combined = useMemo(() => {
    if (imported.length === 0) {
      return messages as readonly WireMessage[];
    }
    const map = new Map<string, WireMessage>();
    for (const message of messages) {
      map.set(messageKey(message), message);
    }
    for (const message of imported) {
      map.set(messageKey(message), message);
    }
    return [...map.values()].sort((left, right) => left.at - right.at);
  }, [messages, imported]);

  const counts = useMemo(() => messageCounts(combined), [combined]);
  const visible = useMemo(() => {
    const filtered = combined.filter((message) => {
      const category = categorizeMessage(message.stream, message.function);
      return !hidden.has(category);
    });
    /* 最新一条在最上面：监视场景不需要滚到底 */
    return filtered.slice(-MAX_ROWS).reverse();
  }, [combined, hidden]);

  /** 导出当前过滤后的全部报文（不是只导出屏幕上 2000 条）。 */
  const exportJsonl = async () => {
    const rows = combined.filter((message) => {
      const category = categorizeMessage(message.stream, message.function);
      return !hidden.has(category);
    });
    const result = await saveTextFile(
      `messages-${perspective}-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.jsonl`,
      rows.map((message) => JSON.stringify(message)).join('\n') + (rows.length > 0 ? '\n' : ''),
      'application/jsonl;charset=utf-8',
    );
    const hint = saveHint(result);
    if (hint !== '') {
      setImportNotice(hint);
    }
  };

  const importJsonl = async (file: File) => {
    try {
      const text = await file.text();
      const rows: WireMessage[] = [];
      for (const line of text.split('\n')) {
        if (line.trim() === '') {
          continue;
        }
        const raw = JSON.parse(line) as WireMessage & { perspective?: string };
        if (
          typeof raw.stream !== 'number'
          || typeof raw.function !== 'number'
          || typeof raw.at !== 'number'
          || (raw.direction !== 'in' && raw.direction !== 'out')
        ) {
          continue;
        }
        const item: WireMessage = {
          sessionId: typeof raw.sessionId === 'string' ? raw.sessionId : '',
          direction: raw.direction,
          stream: raw.stream,
          function: raw.function,
          wbit: raw.wbit === true,
          body: typeof raw.body === 'string' ? raw.body : '',
          at: raw.at,
        };
        /* 主机导出的文件是主机视角；这里内部统一存设备视角，显示时再翻 */
        if (raw.perspective === 'host') {
          item.direction = item.direction === 'in' ? 'out' : 'in';
        }
        rows.push(item);
      }
      setImported((previous) => [...previous, ...rows]);
      setImportNotice(`已导入 ${rows.length} 条报文（仅本页显示，不写回服务端日志）`);
    } catch (error) {
      setImportNotice(`导入失败：${(error as Error).message}`);
    }
  };

  const toggle = (category: MessageCategory) => {
    setHidden((previous) => {
      const next = new Set(previous);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  };

  const allHidden = hidden.size >= MESSAGE_CATEGORIES.length;

  return (
    <div className="message-feed">
      <div className="message-filters" role="group" aria-label="报文类别过滤">
        {MESSAGE_CATEGORIES.map((category) => {
          const active = !hidden.has(category.id);
          return (
            <button
              key={category.id}
              type="button"
              className={`chip filter-chip${active ? '' : ' off'}`}
              aria-pressed={active}
              title={`${category.hint}（点击${active ? '屏蔽' : '显示'}）`}
              onClick={() => toggle(category.id)}
            >
              {category.label}
              <span className="chip-count">{counts[category.id]}</span>
            </button>
          );
        })}
        <span className="spacer" />
        <span className="hint-inline">
          {visible.length} / {combined.length} 条
        </span>
        <button
          type="button"
          onClick={() => void exportJsonl()}
          disabled={combined.length === 0}
          title="导出当前过滤后的全部报文 JSONL（可选择保存位置）"
        >
          导出
        </button>
        <button type="button" onClick={() => fileRef.current?.click()} title="导入之前导出的 JSONL（只在本页查看）">
          导入
        </button>
        <input
          ref={fileRef}
          type="file"
          accept=".jsonl,.json,text/plain"
          style={{ display: 'none' }}
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file !== undefined) {
              void importJsonl(file);
            }
          }}
        />
        <button type="button" onClick={() => setHidden(new Set())} disabled={hidden.size === 0}>
          全部显示
        </button>
        <button
          type="button"
          onClick={() => setHidden(new Set(MESSAGE_CATEGORIES.map((category) => category.id)))}
          disabled={allHidden}
        >
          全部屏蔽
        </button>
      </div>

      {importNotice !== '' && <p className="hint">{importNotice}</p>}

      {visible.length === 0 ? (
        <EmptyState
          text={combined.length === 0 ? emptyText : '当前类别都被屏蔽了'}
          hint={combined.length === 0 ? emptyHint : '点上面的类别恢复显示，或点「全部显示」。'}
        />
      ) : (
        <div className="message-list" role="log" aria-label="报文收发记录">
          {visible.map((message) => (
            <div className="message-row" key={messageKey(message)}>
              <span className="message-time mono">{formatClock(message.at)}</span>
              <span className={`message-dir ${message.direction}`} title={CATEGORY_TITLE[categorizeMessage(message.stream, message.function)]}>
                {directionLabel(message.direction, perspective)}
              </span>
              <span className="message-head mono">
                S{message.stream}F{message.function}
                {message.wbit ? ' W' : ''}
              </span>
              <span className="message-body mono" title={message.body}>
                {message.body === '' ? '（空）' : message.body}
              </span>
            </div>
          ))}
        </div>
      )}
      {combined.length > MAX_ROWS && (
        <p className="hint">
          只显示最近 {MAX_ROWS} 条（界面缓冲区 5000 条）；可拖动面板右下角放大，
          或「导出」全部过滤结果/「导入」之前导出的 JSONL 离线查看。
        磁盘日志已按会话拆分：设备视角 <code>logs/devices/&lt;会话&gt;.jsonl</code>，
        主机视角 <code>logs/hosts/&lt;会话&gt;.jsonl</code>（方向相反），另有一份混合的 <code>logs/messages.jsonl</code>。
        </p>
      )}
    </div>
  );
}

/** 方向文案：同一个 in/out 在设备侧与主机侧含义相反。 */
function directionLabel(direction: 'in' | 'out', perspective: 'device' | 'host'): string {
  if (perspective === 'device') {
    return direction === 'in' ? '收 ← 主机' : '发 → 主机';
  }
  return direction === 'in' ? '发 → 设备' : '收 ← 设备';
}
