import type { WireMessage } from '../../api';

/**
 * 报文分类：按 Stream/Function 归到用户能理解的几类。
 *
 * <p>现场看报文不是为了逐条读，而是先回答"现在跑的是探测、采集还是上报"。
 * 分类只做粗粒度归档；未列入的 SxFy 进"其它"，不丢。</p>
 */
export type MessageCategory =
  | 'probe'      // S1F1 / S1F2
  | 'collection' // S1F3 / S1F4
  | 'trace'      // S6F1 / S6F2
  | 'event'      // S6F11 / S6F12
  | 'control'    // S1F13–S1F18
  | 'alarm'      // S5F1 / S5F2
  | 'terminal'   // S10F3 / S10F4
  | 'other';

export interface MessageCategoryDef {
  id: MessageCategory;
  label: string;
  hint: string;
}

export const MESSAGE_CATEGORIES: readonly MessageCategoryDef[] = [
  { id: 'probe', label: '探测', hint: 'S1F1 / S1F2 在线探测' },
  { id: 'collection', label: '采集', hint: 'S1F3 / S1F4 按需读取' },
  { id: 'trace', label: 'Trace', hint: 'S6F1 / S6F2 周期采样上报' },
  { id: 'event', label: '事件', hint: 'S6F11 / S6F12 事件上报' },
  { id: 'control', label: '通信', hint: 'S1F13–S1F18 建链/上线/下线' },
  { id: 'alarm', label: '报警', hint: 'S5F1 / S5F2' },
  { id: 'terminal', label: '终端', hint: 'S10F3 / S10F4' },
  { id: 'other', label: '其它', hint: '未归类的报文' },
];

/** 一条报文归哪一类。 */
export function categorizeMessage(stream: number, func: number): MessageCategory {
  if (stream === 1 && (func === 1 || func === 2)) {
    return 'probe';
  }
  if (stream === 1 && (func === 3 || func === 4)) {
    return 'collection';
  }
  /* S6F1 是设备按 DSPER 推的 Trace，S6F11 是事件上报：两者采样频率不同，
     混在一个"上报"里没法单独屏蔽高频的那一类。 */
  if (stream === 6 && (func === 1 || func === 2)) {
    return 'trace';
  }
  if (stream === 6 && (func === 11 || func === 12)) {
    return 'event';
  }
  if (stream === 1 && func >= 13 && func <= 18) {
    return 'control';
  }
  if (stream === 5 && (func === 1 || func === 2)) {
    return 'alarm';
  }
  if (stream === 10 && (func === 3 || func === 4)) {
    return 'terminal';
  }
  return 'other';
}

/**
 * 去重键：SSE 与历史补拉可能给出同一条报文。
 *
 * <p>优先用后端序号：时间戳只有毫秒，压测时同一毫秒内两条完全相同的报文
 * 会被误判成重复吞掉。旧日志行没有 seq，退回复合键。</p>
 */
export function messageKey(message: WireMessage): string {
  const owner = message.hostId === undefined || message.hostId === ''
    ? message.sessionId
    : `${message.sessionId}#${message.hostId}`;
  if (typeof message.seq === 'number' && message.seq > 0) {
    return `${owner}|seq:${message.seq}`;
  }
  return `${owner}|${message.at}|${message.direction}|${message.stream}|${message.function}|${message.wbit}|${message.body}`;
}

/**
 * 合并两批报文：去重、按时间升序、只保留最近 max 条。
 *
 * <p>历史补拉与 SSE 增量会重叠，不去重的话刷新一次就出现双份。</p>
 */
export function mergeMessages(
  existing: readonly WireMessage[],
  incoming: readonly WireMessage[],
  max = 500,
): WireMessage[] {
  const seen = new Set(existing.map(messageKey));
  const merged = [...existing];
  for (const message of incoming) {
    const key = messageKey(message);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    merged.push(message);
  }
  merged.sort((left, right) => left.at - right.at);
  return merged.length > max ? merged.slice(merged.length - max) : merged;
}

/** 各分类的条数（给过滤条上的计数用）。 */
export function messageCounts(messages: readonly WireMessage[]): Record<MessageCategory, number> {
  const counts: Record<MessageCategory, number> = {
    probe: 0,
    collection: 0,
    trace: 0,
    event: 0,
    control: 0,
    alarm: 0,
    terminal: 0,
    other: 0,
  };
  for (const message of messages) {
    counts[categorizeMessage(message.stream, message.function)] += 1;
  }
  return counts;
}
