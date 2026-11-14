/**
 * SML 报文文本的语法高亮：返回带 span 的 HTML 片段。
 *
 * <p>SML 结构极简（`S1F1 W` + 数据项），不需要真解析器：
 * 报文头、格式码、数字、字符串、关键字各上一个颜色即可。
 * 输出先整体转义再上色，输入里的任何内容都不可能注入 HTML。</p>
 */

/** 高亮后的片段：kind 用于 CSS 类名 sml-k-{kind}。 */
export interface SmlToken {
  text: string;
  kind: 'head' | 'format' | 'number' | 'string' | 'punct' | 'plain';
}

const FORMAT_CODES = new Set([
  'L', 'B', 'BOOLEAN', 'A', 'JIS8', 'I1', 'I2', 'I4', 'I8',
  'U1', 'U2', 'U4', 'U8', 'F4', 'F8',
]);

/**
 * 把 SML 文本切成带类别的片段。
 *
 * @param text SML 原文
 * @returns 片段序列（纯文本拼接等于原文）
 */
export function tokenizeSml(text: string): SmlToken[] {
  const tokens: SmlToken[] = [];
  /* 报文头：第一次出现的 S<数字>F<数字>（可带 W）。发送结果区前缀是
     "发送 dev-a  S1F1 W"，不在行首，所以不能用 ^ 锚定整个文本；
     同时只匹配第一次，后面的普通词不会被误染。 */
  const head = /S\d+F\d+(\s+W)?/;
  const headIndex = text.search(head);
  let rest = text;
  if (headIndex >= 0) {
    const match = text.substring(headIndex).match(head);
    if (match !== null) {
      if (headIndex > 0) {
        tokens.push({ text: text.substring(0, headIndex), kind: 'plain' });
      }
      tokens.push({ text: match[0], kind: 'head' });
      rest = text.substring(headIndex + match[0].length);
    }
  }

  const pattern = /("[^"]*")|(<)|(>)|(\.\s*$)|([A-Za-z][A-Za-z0-9]*)|(-?0[xX][0-9a-fA-F]+|-?\d+(?:\.\d+)?)|(\s+)|([^\s<>]+)/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(rest)) !== null) {
    const [raw] = match;
    if (match[1] !== undefined) {
      tokens.push({ text: raw, kind: 'string' });
    } else if (match[2] !== undefined || match[3] !== undefined || match[4] !== undefined) {
      tokens.push({ text: raw, kind: 'punct' });
    } else if (match[5] !== undefined) {
      tokens.push({ text: raw, kind: FORMAT_CODES.has(raw.toUpperCase()) ? 'format' : 'plain' });
    } else if (match[6] !== undefined) {
      tokens.push({ text: raw, kind: 'number' });
    } else {
      tokens.push({ text: raw, kind: 'plain' });
    }
  }
  return tokens;
}

function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

/**
 * 渲染成 HTML（已转义）。
 *
 * @param text SML 原文
 * @returns 可直接放进 innerHTML 的片段
 */
export function highlightSml(text: string): string {
  return tokenizeSml(text)
    .map((token) => (token.kind === 'plain' ? escapeHtml(token.text) : `<span class="sml-k-${token.kind}">${escapeHtml(token.text)}</span>`))
    .join('');
}
