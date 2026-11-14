import { describe, expect, it } from 'vitest';
import type { PreviewEventMark } from '../../api';
import { eventColorIndexes, eventColorToken, eventColorVar, eventKey, eventLegendLabel } from './eventColors';

function event(partial: Partial<PreviewEventMark>): PreviewEventMark {
  return { at: 1, ceid: 2001, ...partial };
}

describe('eventColors', () => {
  it('同图内每条计划分配不同色号', () => {
    const events = [
      event({ planId: 'pl-a' }),
      event({ planId: 'pl-b' }),
      event({ planId: 'pl-c' }),
      event({ planId: 'pl-d' }),
      event({ planId: 'pl-e' }),
    ];
    const indexes = eventColorIndexes(events);
    expect(indexes.size).toBe(5);
    expect(new Set(indexes.values()).size).toBe(5);
    expect(indexes.get('plan:pl-a')).toBe(1);
    expect(indexes.get('plan:pl-e')).toBe(5);
  });

  it('没有 planId 时按 CEID 分组', () => {
    const events = [event({ ceid: 2001 }), event({ ceid: 2001 }), event({ ceid: 2002 })];
    expect(eventColorIndexes(events).size).toBe(2);
    expect(eventKey(events[0])).toBe('ceid:2001');
    expect(eventColorVar(1)).toBe('--event-1');
    expect(eventColorToken(1)).toBe('var(--event-1)');
    expect(eventLegendLabel(events[0])).toBe('CEID 2001');
  });
});
