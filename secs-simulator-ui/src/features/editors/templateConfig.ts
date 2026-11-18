/** 配置文档（schema v7）的前端结构化编辑模型。
 *
 * <p>v7 只有一件事：<b>每个数据项自己带一份 {@code source}</b>。三类数据
 * （晶圆属性 Context / 设备常量 ECID / 参数变量 SVID）共用同一种形状，
 * 没有模板图、没有实例、没有绑定表，事件也不改值。</p>
 *
 * <p>这里刻意不 import 任何 React：解析、读取、写回都是纯函数，方便单测。
 * 原文始终是唯一事实来源——结构化编辑只改内存里的 JSON 对象，
 * 再把整份文档写回文本。未知键会保留，但缩进与空格会被统一。</p>
 */

export type JsonObject = Record<string, unknown>;

export const CURVE_TYPES = [
  { type: 'constant', label: '常量' },
  { type: 'ramp', label: '斜坡' },
  { type: 'sine', label: '正弦' },
  { type: 'step', label: '阶跃序列' },
  { type: 'pwl', label: '折线关键帧' },
  { type: 'uniform', label: '区间随机' },
  { type: 'randomWalk', label: '有界随机游走' },
  { type: 'sequence', label: '分段序列' },
] as const;

export type CurveType = (typeof CURVE_TYPES)[number]['type'];

export type CurveField =
  | { key: string; label: string; kind: 'number'; integer?: boolean; step?: number }
  | { key: string; label: string; kind: 'boolean' }
  | { key: string; label: string; kind: 'select'; options: readonly string[] };

const NUMBER = (key: string, label: string, integer = false, step?: number): CurveField =>
  integer ? { key, label, kind: 'number', integer: true } : { key, label, kind: 'number', step };
const BOOLEAN = (key: string, label: string): CurveField => ({ key, label, kind: 'boolean' });
const SELECT = (key: string, label: string, options: readonly string[]): CurveField =>
  ({ key, label, kind: 'select', options });

/** 每种曲线的标量字段；step / pwl 的关键帧单独渲染。 */
export function curveFieldSpecs(type: CurveType): CurveField[] {
  switch (type) {
    case 'constant':
      return [NUMBER('value', '值')];
    case 'ramp':
      return [NUMBER('from', '起点'), NUMBER('to', '终点'), NUMBER('durationMs', '时长 ms', true), BOOLEAN('cyclic', '循环')];
    case 'sine':
      return [NUMBER('min', '最小值'), NUMBER('max', '最大值'), NUMBER('periodMs', '周期 ms', true), NUMBER('phaseMs', '相位 ms', true)];
    case 'uniform':
      return [NUMBER('min', '最小值'), NUMBER('max', '最大值'), NUMBER('resampleMs', '重采样 ms', true)];
    case 'randomWalk':
      return [
        NUMBER('start', '起点'),
        NUMBER('driftPerSec', '漂移/秒'),
        NUMBER('sigma', '波动'),
        NUMBER('gridMs', '网格 ms', true),
        NUMBER('octaves', '八度数', true),
        NUMBER('min', '下界'),
        NUMBER('max', '上界'),
        SELECT('policy', '越界策略', ['CLAMP', 'REFLECT', 'WRAP']),
      ];
    case 'sequence':
      return [BOOLEAN('cyclic', '走完末段后从头循环')];
    case 'step':
    case 'pwl':
      return [];
  }
}

/** 一种曲线的默认字段（不含 id）。 */
export function defaultCurveFields(type: CurveType): JsonObject {
  switch (type) {
    case 'constant':
      return { type, value: 0 };
    case 'ramp':
      return { type, from: 0, to: 100, durationMs: 60_000, cyclic: true };
    case 'sine':
      return { type, min: 0, max: 100, periodMs: 60_000, phaseMs: 0 };
    case 'step':
      return { type, steps: [{ fromMs: 0, value: 0 }, { fromMs: 30_000, value: 100 }] };
    case 'pwl':
      return { type, points: [{ tMs: 0, value: 0 }, { tMs: 30_000, value: 100 }, { tMs: 60_000, value: 0 }] };
    case 'uniform':
      return { type, min: 0, max: 100, resampleMs: 1_000 };
    case 'sequence':
      return {
        type,
        segments: [
          { durationMs: 20_000, curve: { type: 'ramp', from: 0, to: 30, durationMs: 20_000 } },
          { durationMs: 10_000, curve: { type: 'constant', value: 30 } },
          { durationMs: 20_000, curve: { type: 'ramp', from: 30, to: 0, durationMs: 20_000 } },
        ],
        cyclic: false,
      };
    case 'randomWalk':
      return {
        type,
        start: 50,
        driftPerSec: 0,
        sigma: 1,
        gridMs: 1_000,
        octaves: 1,
        min: 0,
        max: 100,
        policy: 'REFLECT',
      };
  }
}

export function isCurveType(value: unknown): value is CurveType {
  return typeof value === 'string' && CURVE_TYPES.some((item) => item.type === value);
}

export function curveTypeLabel(type: CurveType): string {
  return CURVE_TYPES.find((item) => item.type === type)?.label ?? type;
}

/**
 * 这个名字能否安全地出现在表达式里。
 *
 * <p>后端表达式把标识符限制为字母 / 数字 / 下划线 / 点（私有变量名只允许前者）。
 * 表达式里的私有曲线名要用它校验，避免名字里的连字符被当成减法。</p>
 */
export function isExpressionName(value: string): boolean {
  return /^[\p{L}_][\p{L}\p{N}_]*$/u.test(value);
}

/* ---------- 值定义（source） ---------- */

/** 一个数据项的值定义形态。 */
export const SOURCE_MODES = [
  { mode: 'const', label: '常量（不随时间变）' },
  { mode: 'curve', label: '曲线（随时间变化）' },
  { mode: 'expr', label: '表达式（私有曲线组合）' },
  { mode: 'cycle', label: '按片变化（每片一档）' },
] as const;

export type SourceMode = (typeof SOURCE_MODES)[number]['mode'];

/** 曲线 / 表达式的时间自变量。 */
export const SOURCE_CLOCKS = [
  { value: 'phase', label: '片内相位（每片重放）' },
  { value: 'absolute', label: '绝对时间（只跑一次）' },
] as const;

export type SourceClock = (typeof SOURCE_CLOCKS)[number]['value'];

export function isSourceMode(value: unknown): value is SourceMode {
  return typeof value === 'string' && SOURCE_MODES.some((item) => item.mode === value);
}

/** 数据项的 source 对象（不存在时返回空对象，仅供渲染）。 */
export function sourceOf(def: JsonObject): JsonObject {
  return objectField(def, 'source');
}

/** 数据项的值定义形态；未配置返回空串。 */
export function sourceModeOf(def: JsonObject): SourceMode | '' {
  const mode = sourceOf(def).mode;
  return isSourceMode(mode) ? mode : '';
}

/** 一种值形态的默认对象；text 为真时按文本（晶圆属性）生成。 */
export function defaultSource(mode: SourceMode, text = false): JsonObject {
  switch (mode) {
    case 'const':
      return { mode, value: text ? 'VALUE' : 0 };
    case 'curve':
      return { mode, clock: 'phase', curve: defaultCurveFields('sine') };
    case 'expr':
      return { mode, clock: 'phase', vars: { a: defaultCurveFields('sine') }, expr: 'a' };
    case 'cycle':
      return text
        ? { mode, pattern: 'W{03}', start: 1, every: 1 }
        : { mode, start: 1, step: 1, every: 25 };
  }
}

/** 曲线的时钟（大小写不敏感，缺省 phase）。 */
export function clockOf(source: JsonObject): SourceClock {
  return stringField(source, 'clock', 'phase') === 'absolute' ? 'absolute' : 'phase';
}

/** 一句话描述一个 source 在做什么（列表与预览标题用）。 */
export function describeSource(def: JsonObject): string {
  const source = sourceOf(def);
  const mode = sourceModeOf(def);
  switch (mode) {
    case '':
      return '未配置（按默认值）';
    case 'const': {
      const value = source.value;
      return `常量 ${typeof value === 'string' ? value : String(value ?? '')}`;
    }
    case 'curve': {
      const type = stringField(objectField(source, 'curve'), 'type', '?');
      const clock = clockOf(source) === 'absolute' ? '绝对时间' : '片内相位';
      return `曲线 · ${type} · ${clock}`;
    }
    case 'expr':
      return `表达式 · ${Object.keys(objectField(source, 'vars')).length} 条私有曲线`;
    case 'cycle': {
      /* every 是"每几片进一档"，缺省按 1（每片）。摘要必须带上它，
         否则 every=25 的 LOT{03} 会被读成"每片换批号"——真机一整天会换错几百次。 */
      const every = Math.max(1, numberField(source, 'every') ?? 1);
      const prefix = every === 1 ? '每片' : `每 ${every} 片`;
      if (source.pattern !== undefined) {
        return `${prefix} · ${stringField(source, 'pattern')}`;
      }
      if (Array.isArray(source.list)) {
        return `${prefix} · 轮换 ${(source.list as unknown[]).length} 条`;
      }
      return `${prefix} · ${String(source.value ?? '')} 起`;
    }
  }
}

/* ---------- 通用 JSON 读写 ---------- */

export function isJsonObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** 读取字段；不是对象时返回空对象（仅用于渲染，不用于写回）。 */
export function objectField(value: JsonObject, key: string): JsonObject {
  const child = value[key];
  return isJsonObject(child) ? child : {};
}

/** 读取或创建一个可写子对象。 */
export function ensureObjectField(value: JsonObject, key: string): JsonObject {
  const child = value[key];
  if (isJsonObject(child)) {
    return child;
  }
  const created: JsonObject = {};
  value[key] = created;
  return created;
}

export function arrayField(value: JsonObject, key: string): unknown[] {
  const child = value[key];
  return Array.isArray(child) ? child : [];
}

export function stringField(value: JsonObject, key: string, fallback = ''): string {
  const child = value[key];
  return typeof child === 'string' ? child : fallback;
}

export function numberField(value: JsonObject, key: string): number | undefined {
  const child = value[key];
  return typeof child === 'number' && Number.isFinite(child) ? child : undefined;
}

export function booleanField(value: JsonObject, key: string, fallback = false): boolean {
  const child = value[key];
  return typeof child === 'boolean' ? child : fallback;
}

export interface SvidChoice {
  svid: number;
  owner: string;
  slot: string;
}

export interface ParsedConfig {
  root: JsonObject;
  svids: SvidChoice[];
}

export type ParseConfigResult = { ok: true; config: ParsedConfig } | { ok: false; error: string };
export type EditDocumentResult = { ok: true; text: string } | { ok: false; error: string };

/** 后端 ValueType 枚举接受的全部类型（secs-core ValueType）。 */
export const SECS_TYPES = ['U1', 'U2', 'U4', 'U8', 'I1', 'I2', 'I4', 'I8', 'F4', 'F8', 'ASCII', 'BOOLEAN'] as const;

/** 事件计划的触发类型（docs/guides/schema-v4.md）；v7 周期事件走 cycle 段。 */
export const TRIGGER_TYPES = ['at', 'periodic', 'onCondition', 'afterEvent'] as const;
export type TriggerType = (typeof TRIGGER_TYPES)[number];

/** 解析配置原文；只校验 JSON 与根对象。 */
export function parseConfigDocument(text: string): ParseConfigResult {
  /* Windows 上保存的配置常带 BOM；JsonHub 接受它，JSON.parse 不接受。
     不剥掉的话，后端能读的文件在表单里会变成"非法 JSON"。 */
  const source = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  let value: unknown;
  try {
    value = JSON.parse(source) as unknown;
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
  if (!isJsonObject(value)) {
    return { ok: false, error: '配置根必须是 JSON 对象' };
  }
  return { ok: true, config: { root: value, svids: collectSvids(value) } };
}

/** 把整份文档写回文本。结构化编辑会统一成 2 空格缩进。 */
function stringifyConfig(root: JsonObject): string {
  return `${JSON.stringify(root, null, 2)}
`;
}

/**
 * 在原文之上做一次<b>整文档</b>编辑（session / equipment / events 等任意段）。
 *
 * <p>编辑函数拿到克隆后的根对象，异常转成错误文案，未知键保留，
 * 写回统一 2 空格缩进。</p>
 */
export function editDocument(text: string, mutate: (root: JsonObject) => void): EditDocumentResult {
  const parsed = parseConfigDocument(text);
  if (!parsed.ok) {
    return parsed;
  }
  const root = cloneJson(parsed.config.root);
  try {
    mutate(root);
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
  return { ok: true, text: stringifyConfig(root) };
}

function cloneJson(root: JsonObject): JsonObject {
  return JSON.parse(JSON.stringify(root)) as JsonObject;
}

/** 生成不与 existing 冲突的 id。 */
export function uniqueId(existing: ReadonlyArray<unknown>, base: string): string {
  const used = new Set(existing.map((item) => String(item)));
  if (!used.has(base)) {
    return base;
  }
  let index = 2;
  while (used.has(`${base}${index}`)) {
    index += 1;
  }
  return `${base}${index}`;
}

function readObjectArray(parent: JsonObject, key: string, path: string): JsonObject[] | string {
  const value = parent[key];
  if (value === undefined) {
    return [];
  }
  if (!Array.isArray(value)) {
    return `${path}.${key} 必须是数组`;
  }
  /* 必须返回原数组本身：编辑函数会往这个数组里 push，复制一份等于改动丢失 */
  for (const [index, item] of value.entries()) {
    if (!isJsonObject(item)) {
      return `${path}.${key}[${index}] 必须是对象`;
    }
  }
  return value as JsonObject[];
}

export { readObjectArray };

/** 当前配置里的 SVID 候选（按单元号段 + offset 解析，含 globalSvids）。 */
export function collectSvids(root: JsonObject): SvidChoice[] {
  const equipment = objectField(root, 'equipment');
  const idSpace = objectField(equipment, 'idSpace');
  const svidBase = numberField(idSpace, 'svidBase') ?? 1000;
  const svidStride = numberField(idSpace, 'svidStride') ?? 100;

  const choices: SvidChoice[] = [];
  const units = arrayField(equipment, 'units');
  for (const [index, rawUnit] of units.entries()) {
    if (!isJsonObject(rawUnit)) {
      continue;
    }
    const owner = stringField(rawUnit, 'name', `unit${index + 1}`);
    const base = svidBase + index * svidStride;
    for (const rawDef of arrayField(rawUnit, 'svids')) {
      if (!isJsonObject(rawDef)) {
        continue;
      }
      const offset = numberField(rawDef, 'offset');
      if (offset === undefined) {
        continue;
      }
      choices.push({ svid: base + offset, owner, slot: stringField(rawDef, 'slot') });
    }
  }

  for (const rawDef of arrayField(equipment, 'globalSvids')) {
    if (!isJsonObject(rawDef)) {
      continue;
    }
    const id = numberField(rawDef, 'id');
    if (id !== undefined) {
      choices.push({ svid: id, owner: 'GLOBAL', slot: stringField(rawDef, 'name') });
    }
  }

  const unique = new Map<number, SvidChoice>();
  for (const choice of choices) {
    if (!unique.has(choice.svid)) {
      unique.set(choice.svid, choice);
    }
  }
  return [...unique.values()].sort((left, right) => left.svid - right.svid);
}

/** 当前配置里的 CEID 候选（解析规则与 collectSvids 相同，含 globalCeids）。 */
export function collectCeids(root: JsonObject): SvidChoice[] {
  const equipment = objectField(root, 'equipment');
  const idSpace = objectField(equipment, 'idSpace');
  const ceidBase = numberField(idSpace, 'ceidBase') ?? 2000;
  const ceidStride = numberField(idSpace, 'ceidStride') ?? 100;

  const choices: SvidChoice[] = [];
  const units = arrayField(equipment, 'units');
  for (const [index, rawUnit] of units.entries()) {
    if (!isJsonObject(rawUnit)) {
      continue;
    }
    const owner = stringField(rawUnit, 'name', `unit${index + 1}`);
    const base = ceidBase + index * ceidStride;
    for (const rawDef of arrayField(rawUnit, 'ceids')) {
      if (!isJsonObject(rawDef)) {
        continue;
      }
      const offset = numberField(rawDef, 'offset');
      if (offset === undefined) {
        continue;
      }
      choices.push({ svid: base + offset, owner, slot: stringField(rawDef, 'slot') });
    }
  }

  for (const rawDef of arrayField(equipment, 'globalCeids')) {
    if (!isJsonObject(rawDef)) {
      continue;
    }
    const id = numberField(rawDef, 'id');
    if (id !== undefined) {
      choices.push({ svid: id, owner: 'GLOBAL', slot: stringField(rawDef, 'name') });
    }
  }

  const unique = new Map<number, SvidChoice>();
  for (const choice of choices) {
    if (!unique.has(choice.svid)) {
      unique.set(choice.svid, choice);
    }
  }
  return [...unique.values()].sort((left, right) => left.svid - right.svid);
}

/** 晶圆属性定义（顶层 context 段）。 */
export interface ContextChoice {
  id: string;
  type: string;
}

export function collectContexts(root: JsonObject): ContextChoice[] {
  const out: ContextChoice[] = [];
  for (const item of arrayField(root, 'context')) {
    if (!isJsonObject(item)) {
      continue;
    }
    const id = stringField(item, 'id');
    if (id !== '') {
      out.push({ id, type: stringField(item, 'type', 'ASCII') });
    }
  }
  return out;
}

/** 设备常量定义（equipment.constants）。 */
export interface EcidChoice {
  id: number;
  name: string;
  type: string;
}

export function collectEcids(root: JsonObject): EcidChoice[] {
  const equipment = objectField(root, 'equipment');
  const out: EcidChoice[] = [];
  for (const item of arrayField(equipment, 'constants')) {
    if (!isJsonObject(item)) {
      continue;
    }
    const id = numberField(item, 'id');
    if (id !== undefined) {
      out.push({ id, name: stringField(item, 'name'), type: stringField(item, 'type', 'F4') });
    }
  }
  return out;
}
