import { describe, expect, it } from 'vitest';
import { parseHash } from '../router';

/**
 * 界面精简掉了「实时」组(事件流/报文)、事件流日志与会话管理独立页。
 * 旧深链不该 404 或白屏:一律回落到仍存在的页面。
 */
describe('router fallback for removed pages', () => {
  it('falls the removed view routes back to the workbench', () => {
    expect(parseHash('#/events')).toEqual({ name: 'workbench' });
    expect(parseHash('#/messages')).toEqual({ name: 'workbench' });
    expect(parseHash('#/sessions')).toEqual({ name: 'workbench' });
  });

  it('maps the removed console page to the audit page', () => {
    expect(parseHash('#/system/console')).toEqual({ name: 'system', page: 'audit' });
  });

  it('keeps the surviving routes intact', () => {
    expect(parseHash('#/workbench')).toEqual({ name: 'workbench' });
    expect(parseHash('#/system/audit')).toEqual({ name: 'system', page: 'audit' });
    expect(parseHash('#/device/line1/config')).toEqual({ name: 'device', sessionId: 'line1', tab: 'config' });
  });
});
