import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { TemplateEditor } from './TemplateEditor';

const CONFIG = `{
  "schemaVersion": 5,
  "equipment": {
    "name": "LINE1",
    "units": [
      {
        "unitId": 1,
        "name": "CH1",
        "svids": [
          { "offset": 1, "slot": "TEMP", "type": "F4" },
          { "offset": 2, "slot": "PRESS", "type": "F4" }
        ]
      }
    ]
  },
  "templates": {
    "curves": [
      { "id": "wave", "type": "sine", "min": 0, "max": 100, "periodMs": 60000, "phaseMs": 0 }
    ],
    "composites": [
      { "id": "derived", "outputs": { "P": "in.wave.V * 0.5" } }
    ],
    "instances": [
      { "id": "wave-inst", "template": "wave", "svids": { "V": 1001 }, "params": { "gain": 1 } }
    ]
  }
}`;

describe('TemplateEditor', () => {
  it('renders curves, composites, instances, and SVID candidates', () => {
    const html = renderToStaticMarkup(<TemplateEditor text={CONFIG} onChange={() => undefined} />);

    expect(html).toContain('模板（结构化编辑）');
    expect(html).toContain('wave');
    expect(html).toContain('derived');
    expect(html).toContain('wave-inst');
    expect(html).toContain('template-svid-options');
    expect(html).toContain('1001');
    expect(html).toContain('+ 曲线');
    expect(html).toContain('+ 实例');
  });

  it('explains a missing templates section', () => {
    const html = renderToStaticMarkup(
      <TemplateEditor text='{ "schemaVersion": 5 }' onChange={() => undefined} />,
    );

    expect(html).toContain('这份配置还没有');
    expect(html).toContain('插入示例（正弦 + 实例）');
  });

  it('disables the form when the raw JSON is invalid', () => {
    const html = renderToStaticMarkup(<TemplateEditor text='{' onChange={() => undefined} />);

    expect(html).toContain('原文还不是合法 JSON');
  });

  it('reports an unknown instance template by name', () => {
    const broken = CONFIG.replace('"template": "wave"', '"template": "missing"');
    const html = renderToStaticMarkup(<TemplateEditor text={broken} onChange={() => undefined} />);

    expect(html).toContain('模板 missing 不在 curves / composites 里。');
  });
});
