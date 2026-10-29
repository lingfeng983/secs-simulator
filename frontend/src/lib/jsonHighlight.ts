/**
 * 极简 JSON 高亮：把原文切成 token，交给 React 渲染。
 *
 * <p>不引入编辑器依赖：配置页只需要"看得清结构 + 能格式化"，
 * 一个 60 行的 tokenizer 足够，也不会把几 MB 的编辑器塞进包里。</p>
 */
export type JsonTokenKind = 'key' | 'string' | 'number' | 'literal' | 'punct' | 'text';

export interface JsonToken {
  text: string;
  kind: JsonTokenKind;
}

const TOKEN = /("(?:\\.|[^"\\])*")|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|\b(true|false|null)\b|([{}\[\],:])/g;

/** 把任意文本切成 token；不是合法 JSON 也能画（错误部分按普通文本）。 */
export function tokenizeJson(text: string): JsonToken[] {
  const tokens: JsonToken[] = [];
  let last = 0;
  TOKEN.lastIndex = 0;
  let match = TOKEN.exec(text);

  const push = (chunk: string, kind: JsonTokenKind) => {
    if (chunk !== '') {
      tokens.push({ text: chunk, kind });
    }
  };

  while (match !== null) {
    push(text.slice(last, match.index), 'text');
    const [raw, string, number, literal, punct] = match;
    if (string !== undefined) {
      /* 字符串后面（允许空白）紧跟冒号 → 键 */
      const rest = text.slice(TOKEN.lastIndex);
      const isKey = /^\s*:/.test(rest);
      push(raw, isKey ? 'key' : 'string');
    } else if (number !== undefined) {
      push(raw, 'number');
    } else if (literal !== undefined) {
      push(raw, 'literal');
    } else if (punct !== undefined) {
      push(raw, 'punct');
    }
    last = TOKEN.lastIndex;
    match = TOKEN.exec(text);
  }
  push(text.slice(last), 'text');
  return tokens;
}

/** 尝试把文本格式化（2 空格缩进）；不合法时返回 null。 */
export function formatJson(text: string): string | null {
  try {
    const parsed: unknown = JSON.parse(text);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return null;
  }
}
