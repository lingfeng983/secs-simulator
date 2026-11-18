import { describe, expect, it } from 'vitest';
import { mergeActiveAlarms } from './alarmRows';
import type { NoticeWire } from '../../state/useEventStream';

/** 造一条报警通知；payload 形状与后端 SSE 一致（sessionId/at 由帧补）。 */
function alarmNotice(
  sessionId: string,
  at: number,
  data: { alid: number; set: boolean; text?: string; category?: number },
): NoticeWire {
  return { type: 'alarm', sessionId, at, data: { ...data } };
}

describe('mergeActiveAlarms', () => {
  it('uses the server snapshot as the baseline, newest first', () => {
    const rows = mergeActiveAlarms([
      { alid: 1001, text: 'TEMP HIGH', category: 1, atMillis: 1_000 },
      { alid: 1002, text: 'DOOR OPEN', category: 2, atMillis: 2_000 },
    ], [], 's1');

    expect(rows.map((row) => row.alid)).toEqual([1002, 1001]);
    expect(rows[1]).toEqual({ alid: 1001, text: 'TEMP HIGH', category: 1, at: 1_000 });
  });

  it('adds a row when an S5F1 set notice arrives after the snapshot', () => {
    const rows = mergeActiveAlarms(
      [{ alid: 1001, text: 'TEMP HIGH', category: 1, atMillis: 1_000 }],
      [alarmNotice('s1', 3_000, { alid: 2002, set: true, text: 'GAS LEAK', category: 3 })],
      's1',
    );

    expect(rows.map((row) => row.alid)).toEqual([2002, 1001]);
    expect(rows[0]).toEqual({ alid: 2002, text: 'GAS LEAK', category: 3, at: 3_000 });
  });

  it('drops a row when an S5F1 clear notice arrives', () => {
    const rows = mergeActiveAlarms(
      [
        { alid: 1001, text: 'TEMP HIGH', category: 1, atMillis: 1_000 },
        { alid: 1002, text: 'DOOR OPEN', category: 2, atMillis: 2_000 },
      ],
      [alarmNotice('s1', 3_000, { alid: 1001, set: false })],
      's1',
    );

    expect(rows.map((row) => row.alid)).toEqual([1002]);
  });

  it('ignores notices older than the snapshot for the same ALID', () => {
    /* 快照是 5000ms 时的状态（该报警 4000ms 置位）；
       重放出来的 1000ms 置位、2000ms 清除都是更早的事，不能把现状改掉 */
    const rows = mergeActiveAlarms(
      [{ alid: 1001, text: 'TEMP HIGH', category: 1, atMillis: 4_000 }],
      [
        alarmNotice('s1', 1_000, { alid: 1001, set: true, text: 'TEMP HIGH', category: 1 }),
        alarmNotice('s1', 2_000, { alid: 1001, set: false }),
      ],
      's1',
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].at).toBe(4_000);
  });

  it('ignores other sessions, other notice types and malformed ALIDs', () => {
    const rows = mergeActiveAlarms(
      undefined,
      [
        alarmNotice('other', 1_000, { alid: 1001, set: true }),
        { type: 's6f11', sessionId: 's1', at: 1_000, data: { ceid: 2001 } },
        alarmNotice('s1', 1_000, { alid: 0, set: true }),
        alarmNotice('s1', 1_000, { alid: Number.NaN, set: true }),
      ],
      's1',
    );

    expect(rows).toEqual([]);
  });

  it('returns nothing before the snapshot arrives and no notices exist', () => {
    expect(mergeActiveAlarms(undefined, undefined, 's1')).toEqual([]);
  });
});
