/**
 * 值定义「试跑」：把一段 {@code source}（schema v7）在 [0, durationMs] 上采样。
 *
 * <p>v7 里一个数据项只有一份 source，没有模板图、没有实例——所以预览也就退化成
 * 「按这份定义算一遍」。纯函数、不依赖 React，方便单测；语义对齐后端 secs-core 的
 * {@code Curve} / {@code Expression}：8 类曲线、{@code t} / 私有曲线名两种变量、
 * 12 个纯函数与比较运算符。随机类曲线用确定性哈希噪声，同一模板 + 同一 seed
 * 永远得到同一条曲线；换 seed 即「重新模拟一次」。</p>
 *
 * <p>这是<b>示意</b>而非运行时回放：运行时噪声种子由会话与数据项派生，
 * 晶圆属性还按片号变化。预览只回答「这条变化逻辑长什么样」。</p>
 */

import {
  arrayField,
  booleanField,
  clockOf,
  isJsonObject,
  numberField,
  objectField,
  stringField,
  type JsonObject,
} from './templateConfig';

// ---------------------------------------------------------------------------
// 对外类型
// ---------------------------------------------------------------------------

export interface SimSample {
  tMs: number;
  value: number;
}

export interface TemplateSimulation {
  samples: SimSample[];
  durationMs: number;
  min: number;
  max: number;
  avg: number;
  first: number;
  last: number;
  /** 形态的一句话说明，展示在预览里。 */
  shape: string;
  /** 结构/表达式无法完全求值时的说明（只读展示，不阻断画图）。 */
  warning: string;
}

export interface SimulateOptions {
  /** 时间窗口；缺省由曲线关键帧/周期推断。 */
  durationMs?: number;
  /** 基础采样点数，缺省 361；阶梯/折线断点会额外插入。 */
  samples?: number;
  /** 噪声种子；换一个值等于重新模拟一次。 */
  seed?: number;
  /** 按片变化时的片长（毫秒）；缺省取模拟时长。 */
  cycleMs?: number;
}

export const SIM_DURATION_CHOICES = [
  { label: '自动（按曲线周期）', value: 0 },
  { label: '1 分钟', value: 60_000 },
  { label: '5 分钟', value: 300_000 },
  { label: '30 分钟', value: 1_800_000 },
  { label: '2 小时', value: 7_200_000 },
] as const;

// ---------------------------------------------------------------------------
// 确定性随机（splitmix32 + FNV-1a；与后端思路一致，位数不同不影响形态）
// ---------------------------------------------------------------------------

function mix32(value: number): number {
  let z = (value + 0x9e3779b9) | 0;
  z = Math.imul(z ^ (z >>> 16), 0x85ebca6b);
  z = Math.imul(z ^ (z >>> 13), 0xc2b2ae35);
  return (z ^ (z >>> 16)) >>> 0;
}

function hashString(text: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** [0,1) 的确定性均匀数。 */
function uniform01(seed: number, index: number): number {
  return mix32(seed ^ mix32(index)) / 0x1_0000_0000;
}

/** 网格间平滑插值的连续噪声。 */
function smooth01(seed: number, tMs: number, gridMs: number): number {
  const grid = Math.max(1, gridMs);
  const index = Math.floor(tMs / grid);
  const frac = (tMs - index * grid) / grid;
  const eased = frac * frac * (3 - 2 * frac);
  const left = uniform01(seed, index);
  const right = uniform01(seed, index + 1);
  return left + (right - left) * eased;
}

/** 多倍频噪声，落在 [-1,1]，对应后端 Rng.fbm。 */
function fbm(seed: number, tMs: number, gridMs: number, octaves: number): number {
  const layers = Math.max(1, Math.min(6, Math.round(octaves)));
  let sum = 0;
  let amplitude = 1;
  let amplitudeSum = 0;
  let grid = Math.max(1, gridMs);
  for (let i = 0; i < layers; i += 1) {
    sum += (smooth01(seed, tMs, grid) * 2 - 1) * amplitude;
    amplitudeSum += amplitude;
    amplitude *= 0.5;
    grid = Math.max(1, Math.floor(grid / 2));
  }
  return amplitudeSum === 0 ? 0 : sum / amplitudeSum;
}

/** CLAMP / REFLECT / WRAP，对应后端 BoundPolicy.apply。 */
function applyBound(value: number, min: number, max: number, policy: string): number {
  if (!(min < max)) {
    return min;
  }
  if (!Number.isFinite(value)) {
    return value === Number.POSITIVE_INFINITY ? max : min;
  }
  const range = max - min;
  if (policy === 'WRAP') {
    let x = (value - min) % range;
    if (x < 0) {
      x += range;
    }
    return min + x;
  }
  if (policy === 'REFLECT') {
    const period = range * 2;
    let x = (value - min) % period;
    if (x < 0) {
      x += period;
    }
    return x <= range ? min + x : min + (period - x);
  }
  return Math.min(max, Math.max(min, value));
}

function num(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function objects(list: unknown[]): JsonObject[] {
  return list.filter(isJsonObject);
}

interface KeyFrame {
  tMs: number;
  value: number;
}

function keyFrames(curve: JsonObject, timeKey: string, arrayKey: string): KeyFrame[] {
  return objects(arrayField(curve, arrayKey))
    .map((item) => ({ tMs: num(item[timeKey], Number.NaN), value: num(item.value, Number.NaN) }))
    .filter((item) => Number.isFinite(item.tMs) && Number.isFinite(item.value))
    .sort((left, right) => left.tMs - right.tMs);
}

// ---------------------------------------------------------------------------
// 曲线求值
// ---------------------------------------------------------------------------

/** 单条曲线在 tMs 的值；seed 已按数据项派生。 */
function curveAt(curve: JsonObject, tMs: number, seed: number): number {
  const t = Math.max(0, tMs);
  switch (stringField(curve, 'type')) {
    case 'constant':
      return num(curve.value, 0);
    case 'ramp': {
      const from = num(curve.from, 0);
      const to = num(curve.to, 0);
      const duration = Math.max(1, num(curve.durationMs, 60_000));
      let progress = t / duration;
      if (booleanField(curve, 'cyclic')) {
        progress -= Math.floor(progress);
      } else {
        progress = Math.min(1, progress);
      }
      return from + (to - from) * progress;
    }
    case 'sine': {
      const min = num(curve.min, 0);
      const max = num(curve.max, 100);
      const period = Math.max(1, num(curve.periodMs, 60_000));
      const phase = num(curve.phaseMs, 0);
      const mid = (min + max) / 2;
      const amplitude = (max - min) / 2;
      const angle = 2 * Math.PI * (((t + phase) % period) / period);
      return mid + amplitude * Math.sin(angle);
    }
    case 'step': {
      const steps = keyFrames(curve, 'fromMs', 'steps');
      if (steps.length === 0) {
        return 0;
      }
      let value = steps[0].value;
      for (const step of steps) {
        if (t >= step.tMs) {
          value = step.value;
        } else {
          break;
        }
      }
      return value;
    }
    case 'pwl': {
      const points = keyFrames(curve, 'tMs', 'points');
      if (points.length === 0) {
        return 0;
      }
      const first = points[0];
      const last = points[points.length - 1];
      if (t <= first.tMs) {
        return first.value;
      }
      if (t >= last.tMs) {
        return last.value;
      }
      for (let i = 1; i < points.length; i += 1) {
        const right = points[i];
        if (t <= right.tMs) {
          const left = points[i - 1];
          const span = right.tMs - left.tMs;
          const k = span === 0 ? 0 : (t - left.tMs) / span;
          return left.value + (right.value - left.value) * k;
        }
      }
      return last.value;
    }
    case 'uniform': {
      const min = num(curve.min, 0);
      const max = num(curve.max, 100);
      const resample = Math.max(1, num(curve.resampleMs, 1_000));
      return min + (max - min) * uniform01(seed, Math.floor(t / resample));
    }
    case 'sequence': {
      /* 段内用局部时刻委托子曲线；每段派生独立噪声，与后端 sequence 同语义 */
      const segments = objects(arrayField(curve, 'segments'));
      if (segments.length === 0) {
        return 0;
      }
      const durations = segments.map((segment) => Math.max(1, num(segment.durationMs, 1)));
      const total = durations.reduce((sum, duration) => sum + duration, 0);
      let local = t;
      if (booleanField(curve, 'cyclic')) {
        local %= total;
      } else if (local >= total) {
        const last = segments[segments.length - 1];
        return curveAt(objectField(last, 'curve'), durations[durations.length - 1], seed);
      }
      let start = 0;
      for (const [index, segment] of segments.entries()) {
        if (local < start + durations[index]) {
          return curveAt(
            objectField(segment, 'curve'),
            local - start,
            mix32(seed ^ hashString(`segment:${index}`)),
          );
        }
        start += durations[index];
      }
      return 0;
    }
    case 'randomWalk': {
      const start = num(curve.start, 0);
      const drift = num(curve.driftPerSec, 0) * (t / 1_000);
      const sigma = Math.max(0, num(curve.sigma, 0));
      const noise = sigma * fbm(seed, t, num(curve.gridMs, 1_000), num(curve.octaves, 1));
      return applyBound(
        start + drift + noise,
        num(curve.min, 0),
        num(curve.max, 100),
        stringField(curve, 'policy', 'REFLECT').toUpperCase(),
      );
    }
    default:
      return 0;
  }
}

/** 阶梯/折线/锯齿的关键时刻，采样时补进去，跳变才不会被画成斜线。 */
function curveBreakpoints(curve: JsonObject, durationMs: number): number[] {
  const result: number[] = [];
  const push = (t: number) => {
    if (t > 0 && t < durationMs) {
      result.push(Math.round(t));
    }
  };
  const type = stringField(curve, 'type');
  if (type === 'step' || type === 'pwl') {
    const frames = keyFrames(curve, type === 'step' ? 'fromMs' : 'tMs', type === 'step' ? 'steps' : 'points');
    for (const frame of frames) {
      push(frame.tMs);
      if (type === 'step') {
        push(frame.tMs - 1);
      }
    }
  } else if (type === 'sequence') {
    /* 段边界前后各补一点：切换处的跳变不能被画成斜线 */
    let start = 0;
    for (const segment of objects(arrayField(curve, 'segments'))) {
      const duration = Math.max(1, num(segment.durationMs, 1));
      for (const inner of curveBreakpoints(objectField(segment, 'curve'), duration)) {
        push(start + inner);
      }
      start += duration;
      push(start - 1);
      push(start);
    }
  } else if (type === 'uniform') {
    const resample = Math.max(1, num(curve.resampleMs, 1_000));
    for (let t = resample; t < durationMs && result.length < 400; t += resample) {
      push(t - 1);
      push(t);
    }
  } else if (type === 'ramp' && booleanField(curve, 'cyclic')) {
    const duration = Math.max(1, num(curve.durationMs, 60_000));
    for (let t = duration; t < durationMs && result.length < 400; t += duration) {
      push(t - 1);
      push(t);
    }
  } else if (type === 'randomWalk') {
    /* 噪声在网格点上转向；网格很密时不逐点插，靠均匀采样已足够。 */
    const grid = Math.max(1, num(curve.gridMs, 1_000));
    if (durationMs / grid < 120) {
      for (let t = grid; t < durationMs; t += grid) {
        push(t);
      }
    }
  }
  return result;
}

/** 按曲线里最长的周期/关键帧推断一个能看清形态的时间窗口。 */
export function suggestDurationMs(source: JsonObject): number {
  const curves: JsonObject[] = [];
  if (isJsonObject(source.curve)) {
    curves.push(source.curve);
  }
  for (const value of Object.values(isJsonObject(source.vars) ? source.vars : {})) {
    if (isJsonObject(value)) {
      curves.push(value);
    }
  }
  if (curves.length === 0) {
    return 60_000;
  }
  let suggested = 60_000;
  for (const curve of curves) {
    const type = stringField(curve, 'type');
    let span = 60_000;
    if (type === 'sine') {
      span = Math.max(1, num(curve.periodMs, 60_000)) * 2;
    } else if (type === 'ramp') {
      span = Math.max(1, num(curve.durationMs, 60_000)) * (booleanField(curve, 'cyclic') ? 2 : 1.2);
    } else if (type === 'step') {
      const steps = keyFrames(curve, 'fromMs', 'steps');
      span = (steps.at(-1)?.tMs ?? 60_000) * 1.3 + 10_000;
    } else if (type === 'pwl') {
      const points = keyFrames(curve, 'tMs', 'points');
      span = (points.at(-1)?.tMs ?? 60_000) * 1.1 + 5_000;
    } else if (type === 'uniform') {
      span = Math.max(60_000, num(curve.resampleMs, 1_000) * 24);
    } else if (type === 'sequence') {
      const segments = objects(arrayField(curve, 'segments'));
      const total = segments.reduce((sum, item) => sum + Math.max(1, num(item.durationMs, 1)), 0);
      span = Math.max(30_000, total) * (booleanField(curve, 'cyclic') ? 2 : 1.05);
    } else if (type === 'randomWalk') {
      span = Math.max(120_000, num(curve.gridMs, 1_000) * 80);
    }
    suggested = Math.max(suggested, span);
  }
  return Math.round(Math.max(30_000, Math.min(7_200_000, suggested)));
}

// ---------------------------------------------------------------------------
// 受限表达式（与后端 Expression.java 同语法；不用 eval，不执行任意代码）
// ---------------------------------------------------------------------------

type Env = (name: string) => number;
type EvalFn = (env: Env) => number;

const IDENT_PART = /[\p{L}\p{N}_.]/u;
const FIXED_ARITY: Record<string, number> = {
  clamp: 3, lerp: 3, select: 3, roundTo: 2, abs: 1, sin: 1, floor: 1, ceil: 1,
};
const VARIADIC = new Set(['min', 'max', 'sum', 'avg']);

function applyFunction(name: string, args: number[]): number {
  switch (name) {
    case 'clamp': return Math.min(args[2], Math.max(args[1], args[0]));
    case 'lerp': return args[0] + (args[1] - args[0]) * args[2];
    case 'min': return Math.min(...args);
    case 'max': return Math.max(...args);
    case 'sum': return args.reduce((a, b) => a + b, 0);
    case 'avg': return args.reduce((a, b) => a + b, 0) / args.length;
    case 'select': return args[0] !== 0 ? args[1] : args[2];
    case 'abs': return Math.abs(args[0]);
    case 'roundTo': return args[1] === 0 ? args[0] : Math.round(args[0] / args[1]) * args[1];
    case 'sin': return Math.sin(args[0]);
    case 'floor': return Math.floor(args[0]);
    case 'ceil': return Math.ceil(args[0]);
    default: return 0;
  }
}

/** 递归下降解析；带深度与运算符上限，恶意长表达式不会打爆调用栈。 */
class ExpressionParser {
  private pos = 0;
  private depth = 0;
  private operators = 0;

  constructor(private readonly src: string) {}

  parse(): EvalFn {
    const node = this.expression();
    this.spaces();
    if (this.pos !== this.src.length) {
      throw new Error(`表达式尾部有多余内容: ${this.src.slice(this.pos)}`);
    }
    return node;
  }

  private peek(): string {
    return this.pos < this.src.length ? this.src[this.pos] : '';
  }

  private spaces(): void {
    while (this.pos < this.src.length && /\s/u.test(this.src[this.pos])) {
      this.pos += 1;
    }
  }

  private enter(): void {
    this.depth += 1;
    if (this.depth > 64) {
      throw new Error('表达式嵌套超过 64 层');
    }
  }

  private countOperator(): void {
    this.operators += 1;
    if (this.operators > 1024) {
      throw new Error('表达式运算符超过 1024 个');
    }
  }

  private comparison(): string {
    const c = this.peek();
    const next = this.pos + 1 < this.src.length ? this.src[this.pos + 1] : '';
    if ((c === '>' || c === '<') && next === '=') {
      return `${c}=`;
    }
    if (c === '>' || c === '<') {
      return c;
    }
    if ((c === '=' || c === '!') && next === '=') {
      return `${c}=`;
    }
    return '';
  }

  private expression(): EvalFn {
    let left = this.sum();
    for (;;) {
      this.spaces();
      const op = this.comparison();
      if (op === '') {
        return left;
      }
      this.pos += op.length;
      this.countOperator();
      const right = this.sum();
      const prev = left;
      left = (env) => {
        const a = prev(env);
        const b = right(env);
        switch (op) {
          case '>': return a > b ? 1 : 0;
          case '<': return a < b ? 1 : 0;
          case '>=': return a >= b ? 1 : 0;
          case '<=': return a <= b ? 1 : 0;
          case '==': return a === b ? 1 : 0;
          case '!=': return a !== b ? 1 : 0;
          default: return 0;
        }
      };
    }
  }

  private sum(): EvalFn {
    let left = this.term();
    for (;;) {
      this.spaces();
      const c = this.peek();
      if (c !== '+' && c !== '-') {
        return left;
      }
      this.pos += 1;
      this.countOperator();
      const right = this.term();
      const prev = left;
      left = c === '+' ? (env) => prev(env) + right(env) : (env) => prev(env) - right(env);
    }
  }

  private term(): EvalFn {
    let left = this.unary();
    for (;;) {
      this.spaces();
      const c = this.peek();
      if (c !== '*' && c !== '/') {
        return left;
      }
      this.pos += 1;
      this.countOperator();
      const right = this.unary();
      const prev = left;
      left = c === '*' ? (env) => prev(env) * right(env) : (env) => prev(env) / right(env);
    }
  }

  private unary(): EvalFn {
    this.spaces();
    const c = this.peek();
    if (c === '-' || c === '+') {
      this.enter();
      try {
        this.pos += 1;
        const operand = this.unary();
        return c === '-' ? (env) => -operand(env) : operand;
      } finally {
        this.depth -= 1;
      }
    }
    return this.primary();
  }

  private primary(): EvalFn {
    this.enter();
    try {
      this.spaces();
      const c = this.peek();
      if (c === '(') {
        this.pos += 1;
        const inner = this.expression();
        this.spaces();
        if (this.peek() !== ')') {
          throw new Error('缺少右括号');
        }
        this.pos += 1;
        return inner;
      }
      if (/[0-9.]/u.test(c)) {
        const start = this.pos;
        while (this.pos < this.src.length && /[0-9.]/u.test(this.src[this.pos])) {
          this.pos += 1;
        }
        const value = Number.parseFloat(this.src.slice(start, this.pos));
        if (!Number.isFinite(value)) {
          throw new Error(`非法数字: ${this.src.slice(start, this.pos)}`);
        }
        return () => value;
      }
      if (/[\p{L}_]/u.test(c)) {
        return this.identifier();
      }
      throw new Error(`无法识别的字符 '${c}'`);
    } finally {
      this.depth -= 1;
    }
  }

  private identifier(): EvalFn {
    let name = '';
    while (this.pos < this.src.length) {
      const ch = this.src[this.pos];
      if (IDENT_PART.test(ch)) {
        name += ch;
        this.pos += 1;
        continue;
      }
      break;
    }
    this.spaces();
    if (this.peek() !== '(') {
      return (env) => env(name);
    }

    this.pos += 1;
    const args: EvalFn[] = [];
    this.spaces();
    if (this.peek() !== ')') {
      for (;;) {
        args.push(this.expression());
        this.spaces();
        if (this.peek() === ',') {
          this.pos += 1;
        } else {
          break;
        }
      }
    }
    this.spaces();
    if (this.peek() !== ')') {
      throw new Error(`函数 ${name} 缺少右括号`);
    }
    this.pos += 1;
    if (FIXED_ARITY[name] !== undefined && args.length !== FIXED_ARITY[name]) {
      throw new Error(`${name} 需要 ${FIXED_ARITY[name]} 个参数，实际 ${args.length}`);
    }
    if (VARIADIC.has(name) && args.length < 1) {
      throw new Error(`${name} 至少需要 1 个参数`);
    }
    if (FIXED_ARITY[name] === undefined && !VARIADIC.has(name)) {
      throw new Error(`未知函数: ${name}`);
    }
    return (env) => applyFunction(name, args.map((arg) => arg(env)));
  }
}

/** 解析一次表达式，失败时抛出可读错误。 */
export function compileExpression(source: string): EvalFn {
  return new ExpressionParser(source).parse();
}

// ---------------------------------------------------------------------------
// source 求值
// ---------------------------------------------------------------------------

/** 一次试跑的上下文：私有曲线、噪声种子、片长、告警收集。 */
interface SimContext {
  source: JsonObject;
  seed: number;
  cycleMs: number;
  warnings: string[];
  /** 私有曲线名 → 曲线（expr 形态）。 */
  vars: Map<string, JsonObject>;
  /** 变换表达式只编译一次（成功或失败都缓存）。 */
  transform?: { fn?: EvalFn; error?: string };
  /** 表达式只编译一次。 */
  expr?: { fn?: EvalFn };
}

function transformOf(ctx: SimContext): EvalFn | undefined {
  const text = stringField(ctx.source, 'transform');
  if (text.trim() === '') {
    return undefined;
  }
  if (ctx.transform === undefined) {
    try {
      ctx.transform = { fn: compileExpression(text) };
    } catch (error) {
      ctx.transform = { error: (error as Error).message };
      ctx.warnings.push(`变换表达式：${(error as Error).message}`);
    }
  }
  return ctx.transform.fn;
}

function exprOf(ctx: SimContext): EvalFn | undefined {
  if (ctx.expr === undefined) {
    const text = stringField(ctx.source, 'expr');
    if (text.trim() === '') {
      ctx.expr = {};
    } else {
      try {
        ctx.expr = { fn: compileExpression(text) };
      } catch (error) {
        ctx.expr = {};
        ctx.warnings.push(`表达式：${(error as Error).message}`);
      }
    }
  }
  return ctx.expr.fn;
}

/** 一条曲线在本数据项里的噪声种子（不同私有曲线派生不同种子）。 */
function seedOf(ctx: SimContext, name: string): number {
  return mix32(ctx.seed ^ hashString(`source:${name}`));
}

/** 某时刻的值；文本型或无法求值时返回 NaN。 */
function sourceValueAt(ctx: SimContext, tMs: number): number {
  const source = ctx.source;
  switch (stringField(source, 'mode')) {
    case 'const':
      return typeof source.value === 'number' ? source.value : Number.NaN;
    case 'curve': {
      const base = curveAt(objectField(source, 'curve'), tMs, seedOf(ctx, 'curve'));
      const transform = transformOf(ctx);
      return transform === undefined ? base : safeEval(transform, { value: base, tMs });
    }
    case 'expr': {
      const fn = exprOf(ctx);
      if (fn === undefined) {
        return Number.NaN;
      }
      return safeEval(fn, {
        tMs,
        resolve: (name) => {
          if (name === 't') {
            return tMs / 1_000;
          }
          const curve = ctx.vars.get(name);
          return curve === undefined ? 0 : curveAt(curve, tMs, seedOf(ctx, name));
        },
      });
    }
    case 'cycle': {
      if (source.pattern !== undefined || Array.isArray(source.list) || typeof source.value === 'string') {
        return Number.NaN;
      }
      const start = num(source.start, 1);
      const step = num(source.step, 0);
      const every = Math.max(1, num(source.every, 1));
      const index = Math.floor(Math.max(0, tMs) / Math.max(1, ctx.cycleMs));
      return start + step * Math.floor(index / every);
    }
    default:
      return Number.NaN;
  }
}

/** 求值一次；任何异常都收敛成 NaN（画图时按断点处理）。 */
function safeEval(fn: EvalFn, ctx: { value?: number; tMs: number; resolve?: Env }): number {
  try {
    const env: Env = ctx.resolve ?? ((name) => {
      if (name === 'V') {
        return ctx.value ?? 0;
      }
      return name === 't' ? ctx.tMs / 1_000 : 0;
    });
    return fn(env);
  } catch {
    return Number.NaN;
  }
}

function describeMode(source: JsonObject): string {
  switch (stringField(source, 'mode')) {
    case 'const':
      return typeof source.value === 'number' ? `常量 ${source.value}` : '常量文本（无曲线）';
    case 'curve': {
      const clock = clockOf(source) === 'absolute' ? '绝对时间' : '片内相位';
      return `${clock}曲线 · ${stringField(objectField(source, 'curve'), 'type', '?')}`;
    }
    case 'expr': {
      const clock = clockOf(source) === 'absolute' ? '绝对时间' : '片内相位';
      return `表达式 · ${Object.keys(objectField(source, 'vars')).length} 条私有曲线 · ${clock}`;
    }
    case 'cycle':
      return '按片阶跃（片长取模拟时长）';
    default:
      return '未配置';
  }
}

function sampleTimes(breakpoints: number[], durationMs: number, count: number): number[] {
  const times = new Set<number>([0, durationMs]);
  for (let i = 0; i < count; i += 1) {
    times.add(Math.round((i / (count - 1)) * durationMs));
  }
  for (const t of breakpoints) {
    if (t > 0 && t < durationMs) {
      times.add(t);
    }
  }
  return [...times].sort((a, b) => a - b).slice(0, 2_000);
}

/** 毫秒 → mm:ss / h:mm:ss，坐标轴与读数共用。 */
export function formatSimTime(tMs: number): string {
  const total = Math.max(0, Math.round(tMs / 1_000));
  const hours = Math.floor(total / 3_600);
  const minutes = Math.floor((total % 3_600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}

/** 数值保留 2~3 位有效小数，避免坐标轴上出现 180.00000000002。 */
export function formatSimValue(value: number): string {
  if (!Number.isFinite(value)) {
    return '—';
  }
  const abs = Math.abs(value);
  if (abs >= 10_000) {
    return value.toFixed(0);
  }
  if (abs >= 100) {
    return value.toFixed(1);
  }
  if (abs >= 1) {
    return value.toFixed(2);
  }
  if (abs === 0) {
    return '0';
  }
  return value.toFixed(3);
}

/**
 * 试跑一段 source。
 *
 * @param source  值定义（{@code mode: const | curve | expr | cycle}）
 * @param options 时间窗口 / 采样数 / 噪声种子 / 片长
 */
export function simulateSource(
  source: JsonObject,
  options: SimulateOptions = {},
): TemplateSimulation {
  const durationMs = Math.max(1_000, Math.round(options.durationMs !== undefined && options.durationMs > 0
    ? options.durationMs
    : suggestDurationMs(source)));
  const count = Math.max(32, Math.min(1_200, Math.round(options.samples ?? 361)));
  const seed = options.seed ?? 0x5ec5_51ff;
  const cycleMs = Math.max(1_000, Math.round(options.cycleMs ?? durationMs));

  const vars = new Map<string, JsonObject>();
  for (const [name, curve] of Object.entries(isJsonObject(source.vars) ? source.vars : {})) {
    if (isJsonObject(curve)) {
      vars.set(name, curve);
    }
  }
  const ctx: SimContext = { source, seed, cycleMs, warnings: [], vars };

  const breakpoints: number[] = [];
  if (isJsonObject(source.curve)) {
    breakpoints.push(...curveBreakpoints(source.curve, durationMs));
  }
  for (const curve of vars.values()) {
    breakpoints.push(...curveBreakpoints(curve, durationMs));
  }
  if (stringField(source, 'mode') === 'cycle' && source.pattern === undefined) {
    /* 片边界：阶跃不能被画成斜线 */
    for (let t = cycleMs; t < durationMs; t += cycleMs) {
      breakpoints.push(t - 1, t);
    }
  }

  const samples = sampleTimes(breakpoints, durationMs, count)
    .map((tMs) => ({ tMs, value: sourceValueAt(ctx, tMs) }));

  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  let sum = 0;
  let numeric = 0;
  for (const sample of samples) {
    if (!Number.isFinite(sample.value)) {
      sample.value = Number.NaN;
      continue;
    }
    numeric += 1;
    min = Math.min(min, sample.value);
    max = Math.max(max, sample.value);
    sum += sample.value;
  }
  const warnings = [...new Set(ctx.warnings)];
  if (numeric === 0) {
    /* 文本型（LOTID 之类）没有曲线可画：给空样本，画布显示"无数据"，
       而不是把 NaN 写进 SVG 路径。 */
    warnings.push('这份值定义不是数值型（或表达式无法求值），画不出曲线');
    return {
      samples: [],
      durationMs,
      min: 0,
      max: 0,
      avg: 0,
      first: 0,
      last: 0,
      shape: describeMode(source),
      warning: warnings.join('；'),
    };
  }
  if (numeric < samples.length) {
    warnings.push('部分时刻无法求值，曲线上是断点');
  }
  return {
    samples,
    durationMs,
    min: numeric === 0 ? 0 : min,
    max: numeric === 0 ? 0 : max,
    avg: numeric === 0 ? 0 : sum / numeric,
    first: samples.find((sample) => Number.isFinite(sample.value))?.value ?? 0,
    last: [...samples].reverse().find((sample) => Number.isFinite(sample.value))?.value ?? 0,
    shape: describeMode(source),
    warning: warnings.join('；'),
  };
}
