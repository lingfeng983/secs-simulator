import type { TraceFrame } from '../../../shared/lib/smlValues';

/**
 * 主机运行区「启动 / 停止」按钮的状态推断。
 *
 * <p>两个页签都要满足同一条交互约定：按钮只有"启动 ↔ 停止"两态，
 * 点完立刻切过去，**不存在"再启动一次"这条路**——重复下发要么把正在跑的
 * 轮询按回待命，要么把 Trace 采样重置一遍。这里把推断逻辑从组件里摘出来，
 * 用单测钉住，省得下次改界面又把它改回去。</p>
 */

/** 采集器状态里判断启停需要的几个位（顶层汇总与单份计划都有）。 */
export interface CollectorFlags {
  configured?: boolean;
  running?: boolean;
  armed?: boolean;
}

/** 后端采集器状态里新增的 armed 字段：前端公共类型尚未收录，按可选读取。 */
export type ArmedCollectorState = CollectorFlags & { armed?: boolean };

/**
 * 采集器是否"已经启动"（跑着或待命）。
 *
 * <p>一台主机可以同时挂多份采集器（每个采集计划一份），所以这里是**按计划**判断的：
 * 这一份跑着/待命就只给「停止」，没启动的计划照样给「启动」——A 在采不影响 B 再启动。</p>
 */
export function collectorEngaged(state: CollectorFlags | null | undefined): boolean {
  if (state === null || state === undefined || state.configured === false) {
    return false;
  }
  return state.running === true || state.armed === true;
}

/** 用户亲手点过的启停时刻（0 表示没点过）。 */
export interface ManualSwitch {
  started: number;
  stopped: number;
}

/**
 * 这条 TRID 现在算不算「采集中」（决定按钮是「停止」还是「启动」）。
 *
 * <p>顺序按优先级：手动停止 > 采满 TOTSMP > 手点启动还在等首帧 > 最近 60 秒有帧。
 * 手点过的意图一定要压过报文推断——否则点「启动」后的几百毫秒里按钮还是「启动」
 * （手快就重复下发 S2F23），点「停止」之后又会被 60 秒窗口按着显示「停止」一分钟。</p>
 *
 * @param trid      Trace ID
 * @param totsmp    该方案的总样本数（0 = 不设上限）
 * @param frames    本主机收到的 S6F1 帧
 * @param manual    用户手点的启停时刻
 * @param now       当前时刻（测试可注入）
 * @param windowMs  "最近有帧就算在采"的判定窗口
 */
export function traceStarted(
  trid: number,
  totsmp: number,
  frames: readonly TraceFrame[],
  manual: ManualSwitch | undefined,
  now: number = Date.now(),
  windowMs: number = 60_000,
): boolean {
  const startedAt = manual?.started ?? 0;
  const stoppedAt = manual?.stopped ?? 0;
  const own = frames.filter((frame) => frame.trid === trid);

  /* ① 手动停止优先：停止之后没有新帧就算停了，不必等窗口过期 */
  if (stoppedAt > 0 && !own.some((frame) => frame.at > stoppedAt)) {
    return false;
  }

  /* 只看"最近一次手点动作之后"的帧：再次启动时旧帧不能算数 */
  const since = Math.max(startedAt, stoppedAt);
  const fresh = since > 0 ? own.filter((frame) => frame.at >= since) : [];

  /* ② 本次启动之后已经采满 TOTSMP：采样自然结束，按钮回「启动」 */
  if (totsmp > 0 && fresh.some((frame) => frame.smpln >= totsmp)) {
    return false;
  }
  /* ③ 本次启动之后收到帧 */
  if (fresh.length > 0) {
    return true;
  }
  /* ④ 刚点过启动、首帧还没到 */
  if (startedAt > 0 && startedAt > stoppedAt) {
    return true;
  }
  /* ⑤ 没手点过：按"最近窗口内有帧"判断（历史帧不能被当成正在采） */
  return own.some((frame) => now - frame.at <= windowMs);
}
