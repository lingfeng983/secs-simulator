import { describe, expect, it } from 'vitest';
import type { WireMessage } from './api';
import { mergeMessages, messageIdentity, type MessageEntry } from './messageHistory';

function message(at: number, overrides: Partial<WireMessage> = {}): WireMessage {
  return {
    sessionId: 's1',
    direction: 'in',
    stream: 1,
    function: 1,
    wbit: false,
    body: `body-${at}`,
    at,
    ...overrides,
  };
}

function entry(key: number, at: number): MessageEntry {
  return { key, msg: message(at) };
}

describe('messageIdentity', () => {
  it('distinguishes all wire fields', () => {
    expect(messageIdentity(message(1))).toBe(messageIdentity(message(1)));
    expect(messageIdentity(message(1))).not.toBe(messageIdentity(message(2)));
    expect(messageIdentity(message(1))).not.toBe(messageIdentity(message(1, { direction: 'out' })));
  });
});

describe('mergeMessages', () => {
  it('merges history without dropping live messages or duplicating entries', () => {
    const previous = [entry(10, 300), entry(11, 400)];
    const incoming = [message(100), message(300), message(200)];

    const merged = mergeMessages(previous, incoming, 12, 10);

    expect(merged.entries.map((item) => item.msg.at)).toEqual([100, 200, 300, 400]);
    expect(merged.entries.map((item) => item.key)).toEqual([12, 13, 10, 11]);
    expect(merged.nextKey).toBe(14);
  });

  it('keeps only the newest entries after merging', () => {
    const previous = [entry(10, 300)];
    const incoming = [message(100), message(200)];

    const merged = mergeMessages(previous, incoming, 11, 2);

    expect(merged.entries.map((item) => item.msg.at)).toEqual([200, 300]);
  });

  it('preserves duplicate messages that occur more often in history than live', () => {
    const previous = [entry(5, 200)];
    const merged = mergeMessages(previous, [message(200), message(200)], 6, 10);

    expect(merged.entries.map((item) => item.msg.at)).toEqual([200, 200]);
    expect(merged.nextKey).toBe(7);
  });

  it('returns a stable next key when everything is already present', () => {
    const previous = [entry(5, 100)];
    const merged = mergeMessages(previous, [message(100)], 6, 10);

    expect(merged.entries).toHaveLength(1);
    expect(merged.nextKey).toBe(6);
  });
});
