import { describe, expect, it } from 'vitest';
import { highlightSml, tokenizeSml } from './smlHighlight';

describe('tokenizeSml', () => {
  it('splits header, formats, numbers and strings', () => {
    const tokens = tokenizeSml('S1F1 W.');
    expect(tokens[0]).toMatchObject({ text: 'S1F1 W', kind: 'head' });
    expect(tokens.map((t) => t.text).join('')).toBe('S1F1 W.');
  });

  it('keeps round-trip identity for a list body', () => {
    const sml = 'S6F11\n<L[3]\n  <U4 0>\n  <A "HOST">\n  <F4 1.5>\n>.';
    expect(tokenizeSml(sml).map((t) => t.text).join('')).toBe(sml);
  });

  it('classifies format codes case-insensitively', () => {
    const tokens = tokenizeSml('<l [2]');
    const l = tokens.find((t) => t.text === 'l');
    expect(l?.kind).toBe('format');
  });

  it('escapes html in output', () => {
    const html = highlightSml('<A "<script>">');
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });

  it('colors header when prefixed by result text', () => {
    const html = highlightSml('发送 dev-a  S1F1 W' + String.fromCharCode(10) + '（无 body）');
    expect(html).toContain('sml-k-head');
  });

  it('colors header, format and number spans', () => {
    const html = highlightSml('S2F41 W\n<U4 1>.');
    expect(html).toContain('sml-k-head');
    expect(html).toContain('sml-k-format');
    expect(html).toContain('sml-k-number');
  });
});
