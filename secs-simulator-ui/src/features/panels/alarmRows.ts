import type { ActiveAlarm } from '../../api';
import type { NoticeWire } from '../../state/useEventStream';

/** 报警面板里的一行：当前置位中的一条报警。 */
export interface AlarmRow {
  alid: number;
  text: string;
  category: number;
  /** 置位时刻（毫秒，服务端时钟；与 SSE 通知的 at 同一时钟）。 */
  at: number;
}

/**
 * 活动报警 = 后端快照 + 本会话的 S5F1 增量。
 *
 * <p>快照（{@code GET /sessions/{id}/alarms}）是权威基线：页面刷新、切设备回来
 * 都能拿到"现在有哪些报警"。SSE 的 {@code alarm} 通知只负责在两次拉取之间
 * 保持实时：置位就往列表里加，清除就删。</p>
 *
 * <p>同一 ALID 以<b>更新的那条</b>为准。比较用的是服务端时钟（快照里的
 * {@code atMillis} 与通知的 {@code at}），所以顺序无关——即使快照比某条通知
 * 还旧（拉取期间刚好置位），也不会把新状态覆盖成旧状态。</p>
 *
 * @param snapshot  后端快照；未加载时为 undefined
 * @param notices   SSE 类型化通知（可能含别的会话的）
 * @param sessionId 当前设备会话
 * @return 按置位时刻倒序（最近的在上）
 */
export function mergeActiveAlarms(
  snapshot: readonly ActiveAlarm[] | undefined,
  notices: readonly NoticeWire[] | undefined,
  sessionId: string,
): AlarmRow[] {
  /** alid → 该条报警的最新状态与时刻。 */
  const byAlid = new Map<number, { row: AlarmRow; at: number }>();

  for (const alarm of snapshot ?? []) {
    byAlid.set(alarm.alid, {
      row: {
        alid: alarm.alid,
        text: alarm.text,
        category: alarm.category,
        at: alarm.atMillis,
      },
      at: alarm.atMillis,
    });
  }

  for (const notice of notices ?? []) {
    if (notice.type !== 'alarm' || notice.sessionId !== sessionId) {
      continue;
    }
    const alid = Number(notice.data.alid);
    if (!Number.isFinite(alid) || alid <= 0) {
      continue;
    }
    const known = byAlid.get(alid);
    /* 已知状态比这条通知还新：通知是拉取之前发生的事，忽略 */
    if (known !== undefined && known.at > notice.at) {
      continue;
    }
    if (notice.data.set === true) {
      const category = Number(notice.data.category);
      byAlid.set(alid, {
        row: {
          alid,
          text: typeof notice.data.text === 'string' ? notice.data.text : '',
          category: Number.isFinite(category) ? category : 0,
          at: notice.at,
        },
        at: notice.at,
      });
    } else {
      byAlid.delete(alid);
    }
  }

  return [...byAlid.values()]
    .map((entry) => entry.row)
    .sort((left, right) => right.at - left.at);
}
