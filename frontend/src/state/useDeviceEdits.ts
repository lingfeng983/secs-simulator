import { useCallback, useMemo, useRef } from 'react';
import type { ApiClient, CeidInput, EventPlanInput, PreviewData, ReportInput, SvidInput } from '../api';
import { previewWindow } from '../shared/lib/previewWindow';
import type { Notifier } from './useToasts';

/**
 * 运行时编辑：目录里的编排/报告/状态变量增删改，以及曲线预览。
 *
 * <p>从 useBackend 拆出。这类操作都不写回配置，重载后失效；
 * 成功后统一刷新当前会话明细，让面板立刻看到新数据。</p>
 */
export interface DeviceEditDeps {
  client: ApiClient | null;
  notifier: Notifier;
  /** 当前选中的会话；所有编辑都落在它身上。 */
  activeSession: string;
  refreshSessionDetail: (sessionId: string) => Promise<void>;
}

export function useDeviceEdits({ client, notifier, activeSession, refreshSessionDetail }: DeviceEditDeps) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  /** 编辑通用骨架：调 client、按状态码通知、成功后刷新当前会话明细。 */
  const runEdit = useCallback(
    async (action: string, okText: string, run: () => Promise<number>): Promise<boolean> => {
      if (client === null) {
        return false;
      }
      try {
        const status = await run();
        if (status === 200 || status === 201) {
          notifierRef.current.success(okText);
          await refreshSessionDetail(activeSession);
          return true;
        }
        if (status === 409) {
          notifierRef.current.error(`${action}被拒：标识已存在`);
        } else if (status === 404) {
          notifierRef.current.error(`${action}失败：目标不存在`);
        } else {
          notifierRef.current.error(`${action}失败（HTTP ${status}）`);
        }
        return false;
      } catch (error) {
        notifierRef.current.error(`${action}失败`, (error as Error).message);
        return false;
      }
    },
    [client, activeSession, refreshSessionDetail],
  );

  const addEventPlan = useCallback(
    (plan: EventPlanInput) => runEdit('新增编排', '已新增编排计划', () => client!.addEventPlan(activeSession, plan)),
    [runEdit, activeSession],
  );
  const updateEventPlan = useCallback(
    (planId: string, plan: EventPlanInput) => runEdit('更新编排', '已更新编排计划', () => client!.updateEventPlan(activeSession, planId, plan)),
    [runEdit],
  );
  const deleteEventPlan = useCallback(
    (planId: string) => runEdit('删除编排', '已删除编排计划', () => client!.deleteEventPlan(activeSession, planId)),
    [runEdit],
  );

  const addCeid = useCallback(
    (def: CeidInput) => runEdit('新增事件', '已新增采集事件', () => client!.addCeid(activeSession, def)),
    [runEdit, activeSession],
  );
  const updateCeid = useCallback(
    (ceid: number, def: CeidInput) => runEdit('更新事件', '已更新采集事件', () => client!.updateCeid(activeSession, ceid, def)),
    [runEdit, activeSession],
  );
  const deleteCeid = useCallback(
    (ceid: number) => runEdit('删除事件', '已删除采集事件', () => client!.deleteCeid(activeSession, ceid)),
    [runEdit, activeSession],
  );

  const addReport = useCallback(
    (report: ReportInput) => runEdit('新增报告', '已新增报告', () => client!.addReport(activeSession, report)),
    [runEdit],
  );
  const updateReport = useCallback(
    (rptid: number, report: ReportInput) => runEdit('更新报告', '已更新报告', () => client!.updateReport(activeSession, rptid, report)),
    [runEdit],
  );
  const deleteReport = useCallback(
    (rptid: number) => runEdit('删除报告', '已删除报告', () => client!.deleteReport(activeSession, rptid)),
    [runEdit],
  );

  const addSvid = useCallback(
    (def: SvidInput) => runEdit('新增状态变量', '已新增状态变量', () => client!.addSvid(activeSession, def)),
    [runEdit],
  );
  const updateSvid = useCallback(
    (svid: number, def: SvidInput) => runEdit('更新状态变量', '已更新状态变量', () => client!.updateSvid(activeSession, svid, def)),
    [runEdit],
  );
  const deleteSvid = useCallback(
    (svid: number) => runEdit('删除状态变量', '已删除状态变量', () => client!.deleteSvid(activeSession, svid)),
    [runEdit],
  );
  const setSvidValue = useCallback(
    (svid: number, value: number) => runEdit('设定值', `已设定 SVID ${svid} 的值`, () => client!.setSvidValue(activeSession, svid, value)),
    [runEdit],
  );
  const clearSvidValue = useCallback(
    (svid: number) => runEdit('取消设定', `已取消 SVID ${svid} 的设定`, () => client!.clearSvidValue(activeSession, svid)),
    [runEdit],
  );

  /** 单 SVID 曲线预览：走预览端点的 svids 过滤。 */
  const previewSvid = useCallback(
    async (sessionId: string, svid: number, windowMs: number): Promise<PreviewData | null> => {
      if (client === null) {
        return null;
      }
      try {
        /* 预览"最近 N 秒"：以进程 uptime 为右端点。
           从 0 开始会把周期 60s 的计划整个漏掉（窗口只到 60s 边界）。 */
        const fresh = await client.health();
        const { from, to } = previewWindow(fresh.uptimeMs, windowMs);
        return await client.preview(sessionId, from, to, 90, [svid], true);
      } catch (error) {
        notifierRef.current.error('曲线预览失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  /** 只回放某一条编排计划，用于「这个事件及其参数的周期预览」。 */
  const previewPlan = useCallback(
    async (sessionId: string, planId: string, windowMs: number): Promise<PreviewData | null> => {
      if (client === null || planId === '') {
        return null;
      }
      try {
        const fresh = await client.health();
        const { from, to } = previewWindow(fresh.uptimeMs, windowMs);
        return await client.preview(sessionId, from, to, 120, undefined, true, planId);
      } catch (error) {
        notifierRef.current.error('事件预览失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  return useMemo(
    () => ({
      addEventPlan,
      updateEventPlan,
      deleteEventPlan,
      addCeid,
      updateCeid,
      deleteCeid,
      addReport,
      updateReport,
      deleteReport,
      addSvid,
      updateSvid,
      deleteSvid,
      setSvidValue,
      clearSvidValue,
      previewSvid,
      previewPlan,
    }),
    [
      addEventPlan,
      updateEventPlan,
      deleteEventPlan,
      addCeid,
      updateCeid,
      deleteCeid,
      addReport,
      updateReport,
      deleteReport,
      addSvid,
      updateSvid,
      deleteSvid,
      setSvidValue,
      clearSvidValue,
      previewSvid,
      previewPlan,
    ],
  );
}
