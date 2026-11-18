import { describe, expect, it } from 'vitest';
import { nextEventText } from './EventsPanel';
import type { EventPlanView } from '../../api';

/** 造一条最小计划；只管「下次事件」这一列读的字段。 */
function plan(patch: Partial<EventPlanView>): EventPlanView {
  return {
    id: 'p1',
    ceid: 2001,
    enabled: false,
    triggerType: 'periodic',
    ...patch,
  };
}

describe('nextEventText', () => {
  it('有倒计时就报秒数（不足一秒向上取整）', () => {
    expect(nextEventText(plan({ enabled: true, status: 'running', nextFireInMs: 1400 }), 1400)).toBe('2 秒');
    expect(nextEventText(plan({ enabled: true, status: 'running', nextFireInMs: 300 }), 300)).toBe('1 秒');
  });

  it('已启用但发不出去：说等待上线，而不是"条件驱动"', () => {
    expect(nextEventText(plan({ enabled: true, status: 'waiting', triggerType: 'periodic' }), 0)).toBe('等待上线');
  });

  it('未启用：周期/一次性说"未启用"，条件计划补一句条件驱动', () => {
    expect(nextEventText(plan({ triggerType: 'periodic' }), 0)).toBe('未启用');
    expect(nextEventText(plan({ triggerType: 'at' }), 0)).toBe('未启用');
    expect(nextEventText(plan({ triggerType: 'onCondition' }), 0)).toBe('条件驱动（未启用）');
  });

  it('运行中但没有倒计时：按触发方式说清在等什么', () => {
    expect(nextEventText(plan({ enabled: true, status: 'running', triggerType: 'onCondition' }), 0)).toBe('等条件成立');
    expect(nextEventText(plan({ enabled: true, status: 'running', triggerType: 'afterEvent' }), 0)).toBe('等上游事件');
    expect(nextEventText(plan({ enabled: true, status: 'running', triggerType: 'at' }), 0)).toBe('已触发过');
    expect(nextEventText(plan({ enabled: true, status: 'running', triggerType: 'periodic' }), 0)).toBe('等下一个周期');
  });
});
