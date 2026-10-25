import { describe, expect, it } from 'vitest';
import {
  booleanField,
  collectCeids,
  collectSvids,
  editDocument,
  editTemplates,
  parseConfigDocument,
  readTemplates,
  stringifyConfig,
  templateOutputs,
  uniqueId,
  isExpressionName,
  type JsonObject,
} from './templateConfig';

const CONFIG = `{
  "schemaVersion": 5,
  "session": { "id": "line1", "custom": { "keep": true } },
  "equipment": {
    "name": "LINE1",
    "idSpace": { "svidBase": 500, "svidStride": 50 },
    "units": [
      {
        "unitId": 1,
        "name": "CH1",
        "svids": [
          { "offset": 1, "slot": "TEMP", "type": "F4" },
          { "offset": 2, "slot": "PRESS", "type": "F4" }
        ]
      },
      {
        "unitId": 2,
        "name": "CH2",
        "svids": [{ "offset": 1, "slot": "TEMP", "type": "F4" }]
      }
    ],
    "globalSvids": [{ "id": 9001, "name": "AMBIENT", "type": "F4" }]
  }
}`;

describe('parseConfigDocument', () => {
  it('parses an object root and collects absolute SVIDs', () => {
    const parsed = parseConfigDocument(CONFIG);

    expect(parsed.ok).toBe(true);
    if (!parsed.ok) {
      return;
    }

    expect(parsed.config.svids.map((item) => item.svid)).toEqual([501, 502, 551, 9001]);
    expect(parsed.config.svids[0]).toMatchObject({ owner: 'CH1', slot: 'TEMP' });
    expect(parsed.config.svids[2]).toMatchObject({ owner: 'CH2', slot: 'TEMP' });
  });

  it('rejects invalid JSON and non-object roots', () => {
    expect(parseConfigDocument('{').ok).toBe(false);
    expect(parseConfigDocument('[]').ok).toBe(false);
  });

  it('accepts a UTF-8 BOM like the backend does', () => {
    expect(parseConfigDocument('\uFEFF' + CONFIG).ok).toBe(true);
  });
});

describe('readTemplates', () => {
  it('reports absence without inventing a section', () => {
    const parsed = parseConfigDocument(CONFIG);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) {
      return;
    }

    const view = readTemplates(parsed.config.root);
    expect(view.ok).toBe(true);
    if (view.ok) {
      expect(view.templates.present).toBe(false);
    }
  });

  it('rejects malformed arrays with a field path', () => {
    const parsed = parseConfigDocument(`{ "templates": { "curves": {} } }`);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) {
      return;
    }

    const view = readTemplates(parsed.config.root);
    expect(view.ok).toBe(false);
    if (!view.ok) {
      expect(view.error).toContain('templates.curves');
    }
  });
});

describe('editTemplates', () => {
  it('inserts a section while keeping unknown keys', () => {
    const result = editTemplates(CONFIG, (templates) => {
      templates.curves.push({
        id: 'wave',
        type: 'sine',
        min: 0,
        max: 100,
        periodMs: 60_000,
        phaseMs: 0,
      });
      templates.instances.push({
        id: 'wave-inst',
        template: 'wave',
        svids: { V: 501 },
        params: {},
      });
    });

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    const parsed = parseConfigDocument(result.text);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) {
      return;
    }

    const view = readTemplates(parsed.config.root);
    expect(view.ok).toBe(true);
    if (view.ok) {
      expect(view.templates.curves).toHaveLength(1);
      expect(view.templates.instances[0]).toMatchObject({ id: 'wave-inst', template: 'wave' });
    }
    expect((parsed.config.root.session as JsonObject).custom).toEqual({ keep: true });
    /* 结构化编辑按 2 空格重排；这不是逐字节保真 */
    expect(result.text.endsWith('\n')).toBe(true);
    expect(result.text).toContain('\n  "session"');
  });

  it('fills missing arrays when templates exists but curves is absent', () => {
    const result = editTemplates('{ "templates": {} }', (templates) => {
      templates.curves.push({ id: 'wave', type: 'sine' });
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    const parsed = parseConfigDocument(result.text);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) {
      return;
    }
    const view = readTemplates(parsed.config.root);
    expect(view.ok).toBe(true);
    if (view.ok) {
      expect(view.templates.curves).toHaveLength(1);
    }
  });

  it('returns the parse error and does not change text when the source is invalid', () => {
    const result = editTemplates('{', () => undefined);
    expect(result.ok).toBe(false);
  });

  it('refuses to edit a malformed templates section', () => {
    const result = editTemplates('{ "templates": { "curves": {} } }', () => undefined);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error).toContain('templates.curves');
    }
  });
});

describe('template helpers', () => {
  it('knows curve and composite outputs, and unknown templates have none', () => {
    const root: JsonObject = {
      templates: {
        curves: [{ id: 'wave', type: 'sine' }],
        composites: [{ id: 'derived', outputs: { SV: '1', HEATER: '0' } }],
        instances: [],
      },
    };
    const view = readTemplates(root);
    expect(view.ok).toBe(true);
    if (!view.ok) {
      return;
    }

    expect(templateOutputs(view.templates, 'wave')).toEqual(['V']);
    expect(templateOutputs(view.templates, 'derived')).toEqual(['SV', 'HEATER']);
    expect(templateOutputs(view.templates, 'missing')).toEqual([]);
  });

  it('generates stable unique ids', () => {
    expect(uniqueId([], 'curve')).toBe('curve');
    expect(uniqueId(['curve'], 'curve')).toBe('curve2');
    expect(uniqueId(['curve', 'curve2'], 'curve')).toBe('curve3');
  });

  it('recognizes names that can appear in expressions', () => {
    expect(isExpressionName('wave')).toBe(true);
    expect(isExpressionName('_wave2')).toBe(true);
    expect(isExpressionName('温度')).toBe(true);
    expect(isExpressionName('wave-inst')).toBe(false);
    expect(isExpressionName('2wave')).toBe(false);
    expect(isExpressionName('in.wave.V')).toBe(false);
  });

  it('serializes with a trailing newline', () => {
    expect(stringifyConfig({ a: 1 })).toBe('{\n  "a": 1\n}\n');
  });

  it('edits the whole document while preserving unknown keys', () => {
    const result = editDocument(CONFIG, (root) => {
      objectField(root, 'session').port = 6000;
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    const parsed = parseConfigDocument(result.text);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) {
      return;
    }
    expect(parsed.config.root.session).toMatchObject({ id: 'line1', port: 6000, custom: { keep: true } });
  });

  it('rejects edits to unparseable text and rethrows mutate errors', () => {
    expect(editDocument('not json', () => undefined)).toMatchObject({ ok: false });
    const boom = editDocument(CONFIG, () => {
      throw new Error('boom');
    });
    expect(boom).toMatchObject({ ok: false, error: 'boom' });
  });

  it('collects ceids with unit segments and globals', () => {
    const parsed = parseConfigDocument(`{
      "equipment": {
        "idSpace": { "ceidBase": 2000, "ceidStride": 100 },
        "units": [
          { "unitId": 1, "name": "CH1", "ceids": [{ "offset": 1, "slot": "START" }] },
          { "unitId": 2, "name": "CH2", "ceids": [{ "offset": 2, "slot": "STOP" }] }
        ],
        "globalCeids": [{ "id": 1001, "name": "GLOBAL", "reports": [] }]
      }
    }`);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) {
      return;
    }
    expect(collectCeids(parsed.config.root).map((choice) => choice.svid)).toEqual([1001, 2001, 2102]);
    expect(collectCeids(parsed.config.root)[2]).toMatchObject({ owner: 'CH2', slot: 'STOP' });
  });

  it('booleanField honours an explicit default', () => {
    expect(booleanField({ flag: true }, 'flag')).toBe(true);
    expect(booleanField({ flag: false }, 'flag', true)).toBe(false);
    expect(booleanField({}, 'flag')).toBe(false);
    expect(booleanField({}, 'flag', true)).toBe(true);
    expect(booleanField({ flag: 'yes' }, 'flag', true)).toBe(true);
  });
});

/** 测试里要用的小工具（与 templateConfig 导出同语义）。 */
function objectField(value: JsonObject, key: string): JsonObject {
  const child = value[key];
  return typeof child === 'object' && child !== null && !Array.isArray(child) ? (child as JsonObject) : {};
}
