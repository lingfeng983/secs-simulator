import type { WireMessage } from './api';

/** 报文表的一行：key 只增不减，避免 slice 之后 index 位移导致整表重建。 */
export interface MessageEntry {
  key: number;
  msg: WireMessage;
}

/** 合并实时与历史时用来去重；报文本身没有序列号，只能按全部字段认。 */
export function messageIdentity(message: WireMessage): string {
  return JSON.stringify([
    message.sessionId,
    message.direction,
    message.stream,
    message.function,
    message.wbit,
    message.at,
    message.body,
  ]);
}

export interface MessageMerge {
  entries: MessageEntry[];
  /** 下一次分配 key 的起点，调用方要写回 ref。 */
  nextKey: number;
}

/**
 * 把历史报文并入当前列表。
 *
 * <p>历史可能包含实时列表已有的条目，也可能比当前列表更早；
 * 先按全部字段去重，再按时间排序，最后只留面板上限。
 * 这样“载入历史”不会把刚收到的实时报文覆盖掉。</p>
 *
 * @param previous 当前列表
 * @param incoming 历史列表（通常按时间升序）
 * @param startKey 本次分配 key 的起点
 * @param max      面板最多保留条数
 */
export function mergeMessages(
  previous: ReadonlyArray<MessageEntry>,
  incoming: ReadonlyArray<WireMessage>,
  startKey: number,
  max: number,
): MessageMerge {
  /* 按"出现次数"去重，而不是只用一个 Set：同一毫秒内两条完全相同的报文
     是可能发生的，历史里多出来的那一条不能被当成重复吞掉。 */
  const remaining = new Map<string, number>();
  for (const entry of previous) {
    const identity = messageIdentity(entry.msg);
    remaining.set(identity, (remaining.get(identity) ?? 0) + 1);
  }
  let key = startKey;
  const missing: MessageEntry[] = [];

  for (const message of incoming) {
    const identity = messageIdentity(message);
    const count = remaining.get(identity) ?? 0;
    if (count > 0) {
      remaining.set(identity, count - 1);
      continue;
    }
    missing.push({ key, msg: message });
    key += 1;
  }

  const merged = [...missing, ...previous].sort(
    (left, right) => left.msg.at - right.msg.at || left.key - right.key,
  );
  return {
    entries: merged.length > max ? merged.slice(merged.length - max) : merged,
    nextKey: key,
  };
}
