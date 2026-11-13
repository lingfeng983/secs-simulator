import { describe, expect, it } from 'vitest';
import { parseHash } from './router';

/**
 * 3.0 两段式路由 + 旧深链回落。
 * 旧深链不该 404 或白屏：一次性映射到新家或回落到仍存在的页面。
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
  });

  it('falls back instead of throwing on a broken percent escape', () => {
    expect(parseHash('#/device/%E0%A4%A/ceids')).toEqual({ name: 'workbench' });
    expect(parseHash('#/device/line%201/svids')).toEqual({
      name: 'device',
      sessionId: 'line 1',
      zone: 'runtime',
      page: 'svids',
    });
  });
});

describe('3.0 two-zone routes', () => {
  it('parses device zone/page routes', () => {
    expect(parseHash('#/device/line1/config/connection')).toEqual({
      name: 'device',
      sessionId: 'line1',
      zone: 'config',
      page: 'connection',
    });
    expect(parseHash('#/device/line1/runtime/svids')).toEqual({
      name: 'device',
      sessionId: 'line1',
      zone: 'runtime',
      page: 'svids',
    });
  });

  it('maps legacy flat device tabs into zones', () => {
    expect(parseHash('#/device/line1/ceids')).toEqual({
      name: 'device',
      sessionId: 'line1',
      zone: 'runtime',
      page: 'catalog',
    });
    expect(parseHash('#/device/line1/config')).toEqual({
      name: 'device',
      sessionId: 'line1',
      zone: 'config',
      page: 'document',
    });
  });

  it('falls back to the zone home when the sub-page name is unknown', () => {
    /* 旧书签/手输的子页名不认识时不能原样塞进路由：DevicePage 一个分支都匹配不上，
       页面只剩页签、下面全空，看起来像坏了 */
    expect(parseHash('#/device/line1/runtime/whatever')).toEqual({
      name: 'device',
      sessionId: 'line1',
      zone: 'runtime',
      page: 'status',
    });
    expect(parseHash('#/device/line1/config/nope')).toEqual({
      name: 'device',
      sessionId: 'line1',
      zone: 'config',
      page: 'overview',
    });
    expect(parseHash('#/hosts/h1/runtime/nope')).toEqual({
      name: 'host',
      hostId: 'h1',
      zone: 'runtime',
      page: 'status',
    });
  });

  it('parses hosts list and host detail routes', () => {
    expect(parseHash('#/hosts')).toEqual({ name: 'hosts' });
    expect(parseHash('#/hosts/host-1/config/overview')).toEqual({
      name: 'host',
      hostId: 'host-1',
      zone: 'config',
      page: 'overview',
    });
    expect(parseHash('#/hosts/host-1/runtime/monitor')).toEqual({
      name: 'host',
      hostId: 'host-1',
      zone: 'runtime',
      page: 'monitor',
    });
    expect(parseHash('#/hostsim/monitor')).toEqual({ name: 'hosts' });
  });

  it('parses the standalone tool routes', () => {
    expect(parseHash('#/host-templates')).toEqual({ name: 'host-templates' });
    expect(parseHash('#/templates')).toEqual({ name: 'templates' });
  });
});
