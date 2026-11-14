import { describe, expect, it } from 'vitest';
import { formatJson, tokenizeJson } from './jsonHighlight';

describe('jsonHighlight', () => {
  it('区分键、字符串、数字、字面量与标点', () => {
    const tokens = tokenizeJson('{"a": 1, "b": true, "c": null, "d": "x"}');
    const kinds = tokens.map((token) => token.kind);
    expect(kinds).toContain('key');
    expect(kinds).toContain('string');
    expect(kinds).toContain('number');
    expect(kinds).toContain('literal');
    expect(kinds).toContain('punct');
    expect(tokens.filter((token) => token.kind === 'key').map((token) => token.text))
      .toEqual(['"a"', '"b"', '"c"', '"d"']);
  });

  it('保留空白与非法 JSON 原文', () => {
    const text = '{ "a": 1,\n  "bad": ';
    expect(tokenizeJson(text).map((token) => token.text).join('')).toBe(text);
  });

  it('格式化成功返回 2 空格缩进，失败返回 null', () => {
    expect(formatJson('{"a":1,"b":[2]}')).toBe('{\n  "a": 1,\n  "b": [\n    2\n  ]\n}');
    expect(formatJson('{bad')).toBeNull();
  });
});
