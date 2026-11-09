/**
 * 请求基础设施：后端地址、错误类型、超时与统一入口。
 *
 * <p>域模块（sessions/configs/messages/hostsim）只写"哪个端点、什么形状"，
 * 超时、错误归一化与后端地址解析都在这里，避免每个端点各写一遍。</p>
 */

import type { EventPlanInput } from './types';

/** 后端地址的本地存储键；空串 = 同源（界面由后端托管时的默认）。 */
const API_BASE_KEY = 'secs-sim-api-base';

/** 桌面壳注入的后端地址；null = 未注入（浏览器环境或壳内后端未就绪）。 */
let runtimeApiBase: string | null = null;

/** 桌面壳启动时注入后端地址；注入后 readApiBase 以它为准，忽略 localStorage。 */
export function setRuntimeApiBase(base: string): void {
  runtimeApiBase = base;
}

/** 当前使用的后端地址；空串表示同源。 */
export function readApiBase(): string {
  if (runtimeApiBase !== null) return runtimeApiBase;
  return window.localStorage.getItem(API_BASE_KEY) ?? '';
}

/**
 * 设置后端地址。
 *
 * <p>远程运行时后端的场景：完整安装包里的界面可以指向另一台机器上的后端，
 * 远程改配置、看曲线、发报文。写入后刷新页面生效。</p>
 */
export function saveApiBase(base: string): void {
  const trimmed = base.trim().replace(/\/+$/, '');
  if (trimmed === '') {
    window.localStorage.removeItem(API_BASE_KEY);
  } else {
    window.localStorage.setItem(API_BASE_KEY, trimmed);
  }
}

/** 把事件计划输入转成后端 wire 形状(trigger 打包成对象)。 */
export function planToWire(plan: EventPlanInput): Record<string, unknown> {
  const trigger: Record<string, unknown> = { type: plan.triggerType };
  if (plan.triggerType === 'at') {
    trigger.atMs = plan.atMs ?? 0;
  } else if (plan.triggerType === 'periodic') {
    trigger.periodMs = plan.periodMs ?? 1000;
    trigger.jitterMs = plan.jitterMs ?? 0;
  } else if (plan.triggerType === 'onCondition') {
    trigger.expression = plan.expression ?? '';
    trigger.debounceMs = plan.debounceMs ?? 0;
  } else {
    trigger.sourcePlan = plan.sourcePlan ?? '';
    trigger.delayMs = plan.delayMs ?? 0;
  }
  return {
    id: plan.id,
    ceid: plan.ceid,
    trigger,
    effects: plan.effects ?? [],
  };
}

/** 拼出 API 地址：本地模式同源，远程模式带后端地址。 */
export function apiUrl(path: string): string {
  const base = readApiBase();
  return base === '' ? path : `${base}${path}`;
}

/** 后端返回 {ok:false,error:"code"} 时的结构化错误;message 保持可读。 */
export class ApiError extends Error {
  readonly status: number;
  /** 后端的错误码字符串(如 forbidden / not-found);非包络响应为 ''。 */
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

/* ---------- 请求超时 ---------- */

/** 默认请求超时：GET 与短命令 8 秒。 */
export const DEFAULT_REQUEST_TIMEOUT_MS = 8000;

/** 长操作（发送等 T3 回复、配置校验/保存、服务端压测）的宽限窗口。 */
export const LONG_REQUEST_TIMEOUT_MS = 60_000;

/**
 * 超时信号。
 *
 * <p>优先用 AbortSignal.timeout；老浏览器没有时自己造一个（不清理定时器也无妨：
 * 请求结束后 abort 已无副作用）。</p>
 */
function timeoutSignal(ms: number): AbortSignal {
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    return AbortSignal.timeout(ms);
  }
  const controller = new AbortController();
  setTimeout(() => controller.abort(), ms);
  return controller.signal;
}

/**
 * 所有请求的统一入口。
 *
 * <p>集中做两件事：超时（默认 8 秒，长操作由调用方传更大的 timeoutMs）与
 * 请求头。内部工具不做鉴权，因此这里不再注入任何凭据。调用方仍各自 catch
 * 并把错误转成 toast——这里不弹任何提示，避免同一个失败被弹两次。</p>
 *
 * <p>调用方自带 signal 时不覆盖它的取消语义。</p>
 */
export async function request(
  url: string,
  init: RequestInit = {},
  timeoutMs: number = DEFAULT_REQUEST_TIMEOUT_MS,
): Promise<Response> {
  const headers = new Headers(init.headers);
  const signal = init.signal ?? timeoutSignal(timeoutMs);
  try {
    return await fetch(url, { ...init, headers, signal });
  } catch (error) {
    /* 浏览器的超时是英文 DOMException，直接弹给用户看不懂 */
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw new ApiError(408, 'timeout', `请求超时（${Math.round(timeoutMs / 1000)} 秒）：后端没有响应`);
    }
    throw error;
  }
}

/** 解析成功响应为 JSON；失败响应统一转成带错误码的 {@link ApiError}。 */
export async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const text = await response.text();
    let code = '';
    try {
      const body = JSON.parse(text) as { error?: unknown };
      if (typeof body.error === 'string') {
        code = body.error;
      }
    } catch {
      /* 非 JSON 响应(网关错误页等)没有错误码 */
    }
    throw new ApiError(response.status, code, `${response.status} ${text.slice(0, 200)}`);
  }
  return (await response.json()) as T;
}

/** 命令类端点：界面只关心状态码，失败原因在响应体或服务端日志里。 */
export function commandStatus(response: Response): number {
  return response.status;
}

/**
 * 采集器结果：{@code {ok,error?,message?}}。
 *
 * <p>后端的 {@code error} 是稳定代码（invalid-collector-config 等），
 * {@code message} 才是给人看的原因——提示优先取 message。</p>
 */
export function parseCollectorResult(text: string): { ok: boolean; error?: string } {
  try {
    const body = JSON.parse(text) as { ok?: boolean; error?: string; message?: string };
    return { ok: body.ok === true, error: body.message ?? body.error };
  } catch {
    return { ok: false, error: text.slice(0, 200) };
  }
}
