/**
 * 轻量 SML 报文体解析：把后端 SmlRenderer 输出的 `<L[4] <U4 1> ...>`
 * 还原成一棵节点树，供界面从 S6F1 / S6F11 里取值画曲线、判 CEID。
 *
 * <p>只做「取值」这一件事：不校验格式码、不还原二进制，遇到不认识的内容
 * 就按文本保留，解析不出来一律返回 null，绝不让一条异常报文把页面打白。</p>
 */

export interface SmlNode {
  /** 格式码与长度，如 `L[4]`、`U4`、`A`。 */
  type: string;
  /** 标量值（含引号原样保留）。 */
  values: string[];
  /** 列表子项。 */
  children: SmlNode[];
}

const TOKEN = /<L\[\d*\]|<[A-Za-z0-9]+|>|"[^"\\]*(?:\\.[^"\\]*)*"|-?0[xX][0-9a-fA-F]+|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|[^\s<>"']+/g;

function tokenize(body: string): string[] {
  /* 报文体偶尔会带整个 SML（含 "S6F1 W" 与结尾的点），先剥掉 */
  const cleaned = body
    .replace(/^\s*S\d+F\d+\s*W?\s*/i, '')
    .replace(/\.\s*$/, '');
  const raw = cleaned.match(TOKEN) ?? [];
  /* 词法层把 "<L[3]" / "<U4" 当成一个 token，但语法层要的是
     "<" + 类型 两段——不拆开的话 parseNode 永远看不到 "<"，
     整棵树恒为 null（曲线与 CEID 兜底都会静默失效）。 */
  const tokens: string[] = [];
  for (const token of raw) {
    if (token.startsWith('<') && token !== '<') {
      tokens.push('<', token.slice(1));
    } else {
      tokens.push(token);
    }
  }
  return tokens;
}

export function parseSmlBody(body: string): SmlNode | null {
  const tokens = tokenize(body);
  let index = 0;

  const parseNode = (depth: number): SmlNode | null => {
    if (depth > 32 || index >= tokens.length || tokens[index] !== '<') {
      return null;
    }
    index += 1;
    const type = tokens[index] ?? '';
    index += 1;
    const node: SmlNode = { type, values: [], children: [] };
    while (index < tokens.length && tokens[index] !== '>') {
      if (tokens[index] === '<') {
        const child = parseNode(depth + 1);
        if (child === null) {
          return null;
        }
        node.children.push(child);
      } else {
        node.values.push(tokens[index]);
        index += 1;
      }
    }
    if (index >= tokens.length) {
      return null;
    }
    index += 1; // 吃掉 '>'
    return node;
  };

  return parseNode(0);
}

/** 从节点里取第一个可解析的数值；字符串里的引号会去掉。 */
export function firstNumber(node: SmlNode | undefined): number | null {
  if (node === undefined) {
    return null;
  }
  for (const raw of node.values) {
    const text = raw.replace(/^"|"$/g, '').trim();
    if (text === '') {
      continue;
    }
    const value = text.toLowerCase().startsWith('0x')
      ? Number.parseInt(text.slice(2), 16)
      : Number(text);
    if (Number.isFinite(value)) {
      return value;
    }
  }
  return null;
}

/** 把一个列表节点展平成数值数组（嵌套列表取首值）。 */
export function flattenNumbers(node: SmlNode | undefined): number[] {
  if (node === undefined) {
    return [];
  }
  const result: number[] = [];
  for (const child of node.children) {
    const value = firstNumber(child);
    if (value !== null) {
      result.push(value);
    }
  }
  if (result.length === 0) {
    for (const raw of node.values) {
      const value = Number(raw.replace(/^"|"$/g, ''));
      if (Number.isFinite(value)) {
        result.push(value);
      }
    }
  }
  return result;
}

/** S6F11 → CEID：标准 L[3] 取第 2 项；非标准 L[2] 尝试第 1 项。 */
export function ceidFromS6f11(body: string): number | null {
  const root = parseSmlBody(body);
  if (root === null || root.children.length < 2) {
    return null;
  }
  const standard = firstNumber(root.children[1]);
  if (standard !== null) {
    return standard;
  }
  return firstNumber(root.children[0]);
}

export interface TraceFrame {
  trid: number;
  smpln: number;
  /** 一条 S6F1 里按 SVID 顺序展平的采样值。 */
  values: number[];
  at: number;
}

/** S6F1 → 一条 Trace 采样帧。 */
export function traceFrameFromS6f1(body: string, at: number): TraceFrame | null {
  const root = parseSmlBody(body);
  if (root === null || root.children.length < 4) {
    return null;
  }
  const trid = firstNumber(root.children[0]);
  if (trid === null) {
    return null;
  }
  const smpln = firstNumber(root.children[1]) ?? 0;
  const values = flattenNumbers(root.children[3]);
  if (values.length === 0) {
    return null;
  }
  return { trid, smpln, values, at };
}
