import { describe, expect, it } from 'vitest';
import type { WireMessage } from '../../api';
import { attributeHostMessages, type HostOwnershipRef } from './hostMessages';

/** 造一条报文（默认设备视角、无主机标记）。 */
function message(patch: Partial<WireMessage> & { at: number }): WireMessage {
  return {
    sessionId: 'line1',
    direction: 'in',
    stream: 1,
    function: 1,
    wbit: true,
    body: '',
    ...patch,
  };
}

const localHost: HostOwnershipRef = { id: 'host-1', session: 'line1', remote: false, open: true };
const remoteHost: HostOwnershipRef = { id: 'host-r', session: 'line1', remote: true, open: true };

describe('attributeHostMessages', () => {
  it('claims device-side messages for the open local host on that session', () => {
    const messages = [message({ at: 1 }), message({ at: 2, direction: 'out', function: 2 })];
    const owned = attributeHostMessages(messages, [localHost]);
    expect(owned.map((item) => item.hostId)).toEqual(['host-1', 'host-1']);
    /* 不修改入参：设备页还在用同一批对象 */
    expect(messages[0].hostId).toBeUndefined();
  });

  it('keeps host-tagged messages on their own instance', () => {
    const messages = [
      message({ at: 1, sessionId: 'line1', hostId: 'host-r' }),
      message({ at: 2, sessionId: 'line1' }),
    ];
    const owned = attributeHostMessages(messages, [localHost, remoteHost]);
    expect(owned.map((item) => item.hostId)).toEqual(['host-r', 'host-1']);
  });

  it('drops host-tagged messages of an instance that no longer exists', () => {
    const messages = [message({ at: 1, hostId: 'host-gone' })];
    expect(attributeHostMessages(messages, [localHost])).toEqual([]);
  });

  it('does not attribute device traffic to a closed local host', () => {
    /* 主机断开后设备可能被别的主机连上，那批报文不能被算到旧实例头上 */
    const closed: HostOwnershipRef = { ...localHost, open: false };
    expect(attributeHostMessages([message({ at: 1 })], [closed])).toEqual([]);
  });

  it('does not let a remote host claim device-side traffic', () => {
    /* 远程主机的 session 只是宿主会话：设备侧的报文不属于它 */
    const owned = attributeHostMessages([message({ at: 1 })], [remoteHost]);
    expect(owned).toEqual([]);
  });

  it('keeps messages whose session has no host instance out of the monitor', () => {
    const messages = [
      message({ at: 1, sessionId: 'other' }),
      message({ at: 2, sessionId: 'line1' }),
    ];
    const owned = attributeHostMessages(messages, [localHost]);
    expect(owned).toHaveLength(1);
    expect(owned[0].at).toBe(2);
  });

  it('preserves the original order', () => {
    const messages = [
      message({ at: 1 }),
      message({ at: 2, hostId: 'host-r' }),
      message({ at: 3 }),
    ];
    const owned = attributeHostMessages(messages, [localHost, remoteHost]);
    expect(owned.map((item) => item.at)).toEqual([1, 2, 3]);
  });
});
