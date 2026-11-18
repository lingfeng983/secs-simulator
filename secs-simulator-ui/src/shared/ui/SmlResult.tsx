import type { SentMessageResult } from '../../api';
import { highlightSml } from '../lib/smlHighlight';

/** 数字缺失或非有限时显示占位符——绝不把 undefined / NaN 拼给用户看。 */
function numOrDash(value: number | undefined | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '—';
}

/**
 * 一次 SML 发送的结构化结果(高亮渲染)——设备侧发送与主机模拟共用。
 *
 * <p>"发出去了但对端没回"和"根本没回请求"是两回事:回复内容、
 * T3 超时、耗时都要列出来。报文头由调用方拼出，这里再兜一道底：
 * mock 或后端缺字段时不能把 `undefined` 漏到界面上。</p>
 */
export function SmlResult({
  header,
  result,
  peerLabel,
}: {
  /** 结果区首行,如 `发送 line1  S1F1 W` 或 `发送 line1(主机)  S1F13 W`。 */
  header: string;
  result: SentMessageResult;
  /** T3 超时文案里的对端称呼(主机 / 设备)。 */
  peerLabel: string;
}): JSX.Element {
  const lines: string[] = [
    /* 调用方用 `S${stream}F${function}` 拼头；字段缺失时这里兜成「—」。
       兼容所有既有调用方（含主机模拟侧），不必各自改一遍。 */
    (header ?? '').replace(/undefined|NaN/g, '—'),
    result.sentSml == null || result.sentSml === '' ? '（无 body）' : result.sentSml,
  ];
  if (result.replySml != null) {
    lines.push(
      '',
      `回复 S${numOrDash(result.replyStream)}F${numOrDash(result.replyFunction)}${result.replyWbit ? ' W' : ''}`,
      result.replySml,
    );
  } else if (result.timedOut) {
    lines.push('', `W 位报文已发出，但${peerLabel ?? '对端'}未在 T3 内回复`);
  } else {
    lines.push('', '未请求回复（W=0）');
  }
  lines.push('', `耗时 ${numOrDash(result.elapsedMs)} ms`);

  return (
    <pre
      className="sml-result"
      /* 高亮输出已整体转义，不可能注入 */
      dangerouslySetInnerHTML={{ __html: highlightSml(lines.join('\n')) }}
    />
  );
}
