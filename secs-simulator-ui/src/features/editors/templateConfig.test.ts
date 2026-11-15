import { describe, expect, it } from 'vitest';
import {
  collectContexts,
  collectEcids,
  collectSvids,
  defaultSource,
  describeSource,
  editDocument,
  parseConfigDocument,
  sourceModeOf,
  uniqueId,
  type JsonObject,
} from './templateConfig';

const CONFIG = `{
  "schemaVersion": 7,
  "context": [
    { "id": "LOTID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "LOT{02}", "start": 1 } },
    { "id": "WAFERID", "type": "ASCII", "source": { "mode": "cycle", "pattern": "W{03}", "start": 1 } }
  ],
  "equipment": {
    "idSpace": { "svidBase": 1000, "svidStride": 100 },
    "constants": [ { "id": 501, "name": "TEMP_LIMIT", "type": "F4", "value": 5 } ],
    "units": [
      { "unitId": 1, "name": "CH1",
        "svids": [ { "offset": 1, "slot": "TEMP", "type": "F4",
                     "source": { "mode": "curve", "clock": "phase", "curve": { "type": "sine" } } } ] }
    ]
  }
}`;

describe('parseConfigDocument', () => {
  it('剥掉 BOM 后能解析（Windows 保存的配置常带 BOM）', () => {
    const parsed = parseConfigDocument(`\ufeff${CONFIG}`);
    expect(parsed.ok).toBe(true);
  });

  it('非法 JSON 给可读错误', () => {
    const parsed = parseConfigDocument('{');
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) {
      expect(parsed.error.length).toBeGreaterThan(0);
    }
  });

  it('根不是对象时明确拒绝', () => {
    expect(parseConfigDocument('[]').ok).toBe(false);
  });
});

describe('editDocument', () => {
  it('保留未知键，改动写回 2 空格缩进', () => {
    const result = editDocument(CONFIG, (root) => {
      (root as JsonObject).custom = { keep: true };
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    const parsed = parseConfigDocument(result.text);
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.config.root.custom).toEqual({ keep: true });
      expect(result.text.includes('\n  "schemaVersion"')).toBe(true);
    }
  });

  it('编辑函数抛错时返回错误、不写坏文档', () => {
    const result = editDocument(CONFIG, () => {
      throw new Error('boom');
    });
    expect(result.ok).toBe(false);
  });
});

describe('source 模型', () => {
  it('sourceModeOf 读 mode，不认识时返回空串', () => {
    expect(sourceModeOf({ source: { mode: 'curve' } })).toBe('curve');
    expect(sourceModeOf({ source: { mode: 'weird' } })).toBe('');
    expect(sourceModeOf({})).toBe('');
  });

  it('defaultSource 覆盖四种形态，文本与数值分开', () => {
    expect(defaultSource('const').value).toBe(0);
    expect(defaultSource('const', true).value).toBe('VALUE');
    expect(defaultSource('curve').clock).toBe('phase');
    expect(typeof defaultSource('expr').vars).toBe('object');
    expect(defaultSource('cycle').step).toBe(1);
    expect(defaultSource('cycle', true).pattern).toBe('W{03}');
  });

  it('describeSource 给出一句话摘要', () => {
    const curve = { source: { mode: 'curve', clock: 'absolute', curve: { type: 'sine' } } };
    expect(describeSource(curve)).toContain('sine');
    expect(describeSource(curve)).toContain('绝对时间');
    expect(describeSource({ source: { mode: 'const', value: 42 } })).toContain('42');
    expect(describeSource({})).toContain('未配置');
  });
});

describe('collect*', () => {
  it('collectContexts 保持文档顺序', () => {
    const parsed = parseConfigDocument(CONFIG);
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(collectContexts(parsed.config.root).map((item) => item.id)).toEqual(['LOTID', 'WAFERID']);
    }
  });

  it('collectEcids 读设备常量', () => {
    const parsed = parseConfigDocument(CONFIG);
    if (parsed.ok) {
      expect(collectEcids(parsed.config.root)).toEqual([{ id: 501, name: 'TEMP_LIMIT', type: 'F4' }]);
    }
  });

  it('collectSvids 按号段算出绝对 id', () => {
    const parsed = parseConfigDocument(CONFIG);
    if (parsed.ok) {
      expect(collectSvids(parsed.config.root)).toEqual([{ svid: 1001, owner: 'CH1', slot: 'TEMP' }]);
    }
  });
});

describe('uniqueId', () => {
  it('冲突时顺延', () => {
    expect(uniqueId(['a'], 'a')).toBe('a2');
    expect(uniqueId(['a', 'a2'], 'a')).toBe('a3');
    expect(uniqueId([], 'a')).toBe('a');
  });
});
