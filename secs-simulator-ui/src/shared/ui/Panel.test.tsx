import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Panel } from './Panel';

/**
 * 面板外壳的两条约定：
 * 有标题时 = 标题行 + 内容；无标题（headless）时标题只进无障碍树，
 * 状态与操作落到内容下方的一条操作行里——按钮下移、右对齐。
 */
describe('Panel 外壳', () => {
  it('有标题时渲染标题行，不渲染底部操作行', () => {
    const html = renderToStaticMarkup(
      <Panel title="概览" description="说明" status={<span>有改动</span>} actions={<button type="button">保存</button>}>
        <p>内容</p>
      </Panel>,
    );
    expect(html).toContain('panel-head');
    expect(html).toContain('概览');
    expect(html).not.toContain('panel-foot');
    expect(html).not.toContain('aria-label="概览"');
  });

  it('headless 时不留标题行，状态与按钮落到内容下方', () => {
    const html = renderToStaticMarkup(
      <Panel title="连接" headless status={<span>有未保存改动</span>} actions={<button type="button">保存</button>}>
        <p>内容</p>
      </Panel>,
    );
    expect(html).not.toContain('panel-head');
    expect(html).not.toContain('panel-title');
    /* 标题仍给读屏：面板本身有 aria-label */
    expect(html).toContain('aria-label="连接"');
    /* 内容 → 状态/按钮：脚在 body 之后 */
    expect(html.indexOf('panel-foot')).toBeGreaterThan(html.indexOf('panel-body'));
    expect(html).toContain('有未保存改动');
    expect(html).toContain('>保存</button>');
    expect(html).toContain('panel--footed');
  });

  it('headless 但既无状态也无操作时，不凭空长出一条空操作行', () => {
    const html = renderToStaticMarkup(
      <Panel title="概览" headless>
        <p>内容</p>
      </Panel>,
    );
    expect(html).not.toContain('panel-foot');
    expect(html).not.toContain('panel--footed');
  });
});
