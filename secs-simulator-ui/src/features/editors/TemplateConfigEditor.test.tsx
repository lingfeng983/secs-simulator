import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { TemplateConfigEditor, type TemplateConfigPage } from './TemplateConfigEditor';
import type { BackendController } from '../../state/useBackend';

const text = JSON.stringify({
  schemaVersion: 7,
  session: { id: 'demo', passive: true, port: 5000 },
  equipment: {
    name: 'Demo',
    constants: [{ id: 501, name: 'TEMP_SP', type: 'F4', value: 180 }],
    units: [{ unitId: 1, name: 'CH1', svids: [{ offset: 1, slot: 'TEMP', type: 'F4' }], reports: [], ceids: [] }],
  },
  context: [{ id: 'LOTID', type: 'ASCII', source: { mode: 'cycle', pattern: 'LOT{03}' } }],
  events: { plans: [] },
});

function render(page: TemplateConfigPage, value = text) {
  return renderToStaticMarkup(<TemplateConfigEditor text={value} onChange={() => undefined}
    page={page} onSelect={() => undefined} backend={{ client: null } as BackendController} library={[]} />);
}

describe('TemplateConfigEditor', () => {
  it('offers the configuration tabs in device order, without runtime or history actions', () => {
    const html = render('overview');
    const nav = html.slice(html.indexOf('subtabs'), html.indexOf('template-config-content'));
    let position = -1;
    for (const label of ['概览', '链接配置', '结构配置', '设备常量', '晶圆属性', '变量配置', '事件与报告', '事件计划', '全量配置']) {
      const next = nav.indexOf(`>${label}<`);
      expect(next, label).toBeGreaterThan(position);
      position = next;
    }
    expect(html).not.toContain('运行区');
    expect(html).not.toContain('版本历史');
    expect(html).not.toContain('启动设备');
  });

  it.each([['svids', 'TEMP'], ['constants', 'TEMP_SP'], ['context', 'ctx.LOTID']] as const)(
    'renders the shared %s table with edit controls', (page, name) => {
      const html = render(page);
      expect(html).toContain('data-table');
      expect(html).toContain(name);
      expect(html).toContain('>编辑</button>');
      expect(html).not.toContain('这台设备没有配置文件来源');
    },
  );

  it('keeps malformed JSON accessible in the document tab', () => {
    expect(render('svids', '{bad')).toContain('配置原文');
    expect(render('document', '{bad')).toContain('{bad');
  });

  it('keeps constants out of structure', () => {
    const html = render('structure');
    expect(html).toContain('号段规划');
    expect(html).toContain('单元名');
    expect(html).not.toContain('TEMP_SP');
  });
});
