import { describe, expect, it } from 'vitest';
import { setPlansEnabled } from './eventBatch';

const plans = [{ id: 'a', enabled: false }, { id: 'b', enabled: true }, { id: 'c', enabled: false }];

describe('setPlansEnabled', () => {
  it('starts only selected plans that need a change, with explicit device and state', async () => {
    const states = new Map(plans.map((plan) => [plan.id, plan.enabled]));
    const calls: unknown[] = [];
    const progress: number[][] = [];
    const result = await setPlansEnabled(plans, new Set(['a', 'b']), true, 'device-a', async (device, id, enabled) => {
      calls.push([device, id, enabled]);
      states.set(id, enabled);
      return 200;
    }, (done, total) => progress.push([done, total]));
    expect([...states.values()]).toEqual([true, true, false]);
    expect(calls).toEqual([['device-a', 'a', true]]);
    expect(result).toEqual({ succeeded: ['a'], failed: [], skipped: 1 });
    expect(progress).toEqual([[0, 1], [1, 1]]);
  });

  it('continues after both response and network failures', async () => {
    const calls: string[] = [];
    const result = await setPlansEnabled(plans.map((plan) => ({ ...plan, enabled: true })), new Set(['a', 'b', 'c']), false, 'device-b', async (_device, id, enabled) => {
      expect(enabled).toBe(false);
      calls.push(id);
      if (id === 'a') return 404;
      if (id === 'b') throw new Error('connection lost');
      return 200;
    });
    expect(calls).toEqual(['a', 'b', 'c']);
    expect(result.succeeded).toEqual(['c']);
    expect(result.failed).toEqual([{ id: 'a', reason: '状态码 404' }, { id: 'b', reason: 'connection lost' }]);
  });

  it('does not operate on empty, missing, or filtered-out selections', async () => {
    const calls: string[] = [];
    const result = await setPlansEnabled([plans[1]], new Set(['a', 'missing']), false, 'device-a', async (_device, id) => {
      calls.push(id);
      return 200;
    });
    expect(calls).toEqual([]);
    expect(result).toEqual({ succeeded: [], failed: [], skipped: 0 });
  });
});
