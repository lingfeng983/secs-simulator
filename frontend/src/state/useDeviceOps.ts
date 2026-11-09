import { useCallback, useMemo, useRef } from 'react';
import type { ApiClient, EventPlanView, HostSimTarget, SentMessageResult } from '../api';
import type { Notifier } from './useToasts';

/** 发送结果的统一摘要：回复/超时/已发送三态，外加耗时。 */
function sentSummary(result: SentMessageResult): { summary: string; detail: string } {
  const summary =
    result.replySml != null
      ? `S${result.stream}F${result.function} -> S${result.replyStream}F${result.replyFunction}`
      : result.timedOut
        ? `S${result.stream}F${result.function} -> 等待回复超时`
        : `S${result.stream}F${result.function} -> 已发送`;
  return { summary, detail: `耗时 ${result.elapsedMs} ms` };
}

/**
 * 设备侧现场操作：报警、事件开关、手动发送、主机模拟连接。
 *
 * <p>从 useBackend 拆出。共同点是"改设备或连接的状态，不碰目录编辑"，
 * 且多数在完成后要刷新当前会话明细（主机连接会影响目录里的事件来源）。</p>
 */
export interface DeviceOpsDeps {
  client: ApiClient | null;
  notifier: Notifier;
  /** 当前选中的会话；事件开关与主机模拟都作用在它身上。 */
  activeSession: string;
  refreshSessionDetail: (sessionId: string) => Promise<void>;
}

export function useDeviceOps({ client, notifier, activeSession, refreshSessionDetail }: DeviceOpsDeps) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  const setAlarm = useCallback(
    async (sessionId: string, alid: number, set: boolean, text: string, category: number) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.alarm(sessionId, alid, set, text, category);
        const message = `报警${set ? '置位' : '清除'} ${sessionId} ALID ${alid} -> HTTP ${status}`;
        if (status >= 200 && status < 300) {
          notifierRef.current.success(message);
        } else {
          notifierRef.current.error(message);
        }
        await refreshSessionDetail(sessionId);
      } catch (error) {
        notifierRef.current.error('报警失败', (error as Error).message);
      }
    },
    [client, refreshSessionDetail],
  );

  const toggleEvent = useCallback(
    async (plan: EventPlanView) => {
      if (client === null || activeSession === '') {
        return;
      }
      try {
        const status = await client.setEventEnabled(activeSession, plan.id, !plan.enabled);
        if (status === 200) {
          notifierRef.current.success(`事件计划 ${plan.id} 已${plan.enabled ? '禁用' : '启用'}`);
        } else if (status === 404) {
          notifierRef.current.error(`事件计划 ${plan.id} 操作失败：会话或计划不存在`);
        } else {
          notifierRef.current.error(`事件计划 ${plan.id} 操作失败（HTTP ${status}）`);
        }
        await refreshSessionDetail(activeSession);
      } catch (error) {
        notifierRef.current.error('事件计划操作失败', (error as Error).message);
      }
    },
    [client, activeSession, refreshSessionDetail],
  );

  /** 以设备角色手动发送一条 SML；失败时把错误抛给调用方（发送面板要留在原处）。 */
  const sendSml = useCallback(
    async (sessionId: string, sml: string): Promise<SentMessageResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.sendSml(sessionId, sml);
        const { summary, detail } = sentSummary(result);
        notifierRef.current.success(summary, detail);
        return result;
      } catch (error) {
        notifierRef.current.error('手动发送失败', (error as Error).message);
        throw error;
      }
    },
    [client],
  );

  /** 以主机角色手动发送一条 SML；失败只提示，返回 null。 */
  const sendHostSml = useCallback(
    async (sessionId: string, sml: string): Promise<SentMessageResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.sendHostSml(sessionId, sml);
        const { summary, detail } = sentSummary(result);
        notifierRef.current.success(summary, detail);
        return result;
      } catch (error) {
        notifierRef.current.error('主机模拟发送失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  const openHostSim = useCallback(
    async (sessionId: string, target?: HostSimTarget) => {
      if (client === null) {
        return;
      }
      const status = await client.openHostSim(sessionId, target).catch(() => 0);
      if (status === 201) {
        notifierRef.current.success(target === undefined ? '主机模拟器已连接' : '主机模拟器已连接（远程）');
      } else if (status === 409) {
        notifierRef.current.error('连接失败', '设备已有主机连接，或模拟器已开启');
      } else {
        notifierRef.current.error(`主机模拟器连接失败（HTTP ${status}）`);
      }
      await refreshSessionDetail(sessionId);
    },
    [client, refreshSessionDetail],
  );

  const closeHostSim = useCallback(
    async (sessionId: string) => {
      if (client === null) {
        return;
      }
      await client.closeHostSim(sessionId).catch(() => 0);
      notifierRef.current.info('主机模拟器已断开');
      await refreshSessionDetail(sessionId);
    },
    [client, refreshSessionDetail],
  );

  return useMemo(
    () => ({ setAlarm, toggleEvent, sendSml, sendHostSml, openHostSim, closeHostSim }),
    [setAlarm, toggleEvent, sendSml, sendHostSml, openHostSim, closeHostSim],
  );
}
