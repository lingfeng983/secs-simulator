import { useCallback, useMemo, useRef } from 'react';
import type { ApiClient, SessionBulkParams, SessionBulkResult } from '../api';
import type { Notifier } from './useToasts';

/**
 * 会话生命周期操作的状态码文案。
 *
 * <p>服务端已经在日志里记了原因，这里只负责把状态码说成人话：
 * 400 分成"配置有问题"和"名字不合法"两种可能，光一个 400 谁也看不出该改哪。</p>
 */
function lifecycleMessage(status: number, what: string): string {
  switch (status) {
    case 200:
    case 201:
      return `${what}成功`;
    case 400:
      return `${what}失败（配置不合法/读不到，或端口被占；详见服务端日志）`;
    case 404:
      return `${what}失败：会话不存在`;
    case 409:
      return `${what}失败：标识已被占用，或该会话没有配置来源`;
    default:
      return `${what} -> HTTP ${status}`;
  }
}

/**
 * 会话域写操作：创建 / 批量 / 重载 / 关闭 / 上报。
 *
 * <p>从 useBackend 拆出来的第一块：这些操作只依赖 client、刷新回调与通知，
 * 不碰其它域的状态，单独成文件让每个域都可读。
 * notifierRef 由调用方语义延续——通知器在面板刷新时重建，闭包里必须取最新。</p>
 */
export interface SessionCommandsDeps {
  client: ApiClient | null;
  notifier: Notifier;
  refreshSessions: () => Promise<void>;
  refreshSessionDetail: (sessionId: string) => Promise<void>;
}

export function useSessionCommands({ client, notifier, refreshSessions, refreshSessionDetail }: SessionCommandsDeps) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  /**
   * 新建会话。
   *
   * <p>返回 HTTP 状态码（`201` 才算真的建好；异常与未连后端返回 `0`）。
   * 调用方需要区分"成功/失败"时必须看返回值——这里把错误提示发出去之后是
   * 正常 resolve 的，早期调用处（CSV 导入）直接把 resolve 当成功，结果
   * 全部 409 的情况下也会报"已导入 N 个"。</p>
   */
  const createSession = useCallback(
    async (config: string): Promise<number> => {
      if (client === null) {
        return 0;
      }
      try {
        const status = await client.createSession(config.trim());
        const text = lifecycleMessage(status, '新建会话');
        if (status === 201) {
          notifierRef.current.success(text);
        } else {
          notifierRef.current.error(text);
        }
        await refreshSessions();
        return status;
      } catch (error) {
        notifierRef.current.error('新建会话失败', (error as Error).message);
        return 0;
      }
    },
    [client, refreshSessions],
  );

  /** 从一份设备配置批量开多台设备；逐台独立，失败的台数不影响其余。 */
  const bulkCreateSessions = useCallback(
    async (params: SessionBulkParams) => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.bulkSessions(params);
        const failedNote = result.failed.length > 0 ? `，${result.failed.length} 台失败` : '';
        notifierRef.current.success(`批量新建完成：${result.created.length} 台${failedNote}`);
        await refreshSessions();
        return result;
      } catch (error) {
        notifierRef.current.error('批量新建失败', (error as Error).message);
        await refreshSessions();
        return null;
      }
    },
    [client, refreshSessions],
  );

  /**
   * 逐台执行的通用骨架：5 路并发 + 进度回调。
   *
   * <p>串行要 100 台 × 500ms = 50 秒；并发上限收在 5 是因为后端命令
   * 仍要占会话线程，开太高会把自己的事件心跳挤掉。</p>
   */
  const batchRun = useCallback(
    async (
      ids: ReadonlySet<string>,
      verb: string,
      run: (id: string) => Promise<number>,
      tone: 'success' | 'info',
      onProgress?: (done: number, total: number) => void,
    ) => {
      const total = ids.size;
      let ok = 0;
      let fail = 0;
      let done = 0;
      let next = 0;
      const list = [...ids];

      const worker = async (): Promise<void> => {
        for (;;) {
          const index = next;
          if (index >= total) {
            return;
          }
          next += 1;
          try {
            const status = await run(list[index]);
            if (status >= 200 && status < 300) {
              ok += 1;
            } else {
              fail += 1;
            }
          } catch {
            fail += 1;
          }
          done += 1;
          onProgress?.(done, total);
        }
      };

      await Promise.all(Array.from({ length: Math.min(5, total) }, () => worker()));
      notifierRef.current[tone](`批量${verb}完成：成功 ${ok}，失败 ${fail}`);
      await refreshSessions();
      return { ok, fail };
    },
    [refreshSessions],
  );

  const batchTrigger = useCallback(
    (ids: ReadonlySet<string>, ceid: number, onProgress?: (done: number, total: number) => void) =>
      batchRun(ids, '上报', (id) => client === null ? Promise.resolve(0) : client.trigger(id, ceid), 'success', onProgress),
    [batchRun, client],
  );

  const batchReload = useCallback(
    (ids: ReadonlySet<string>, onProgress?: (done: number, total: number) => void) =>
      batchRun(ids, '重载', (id) => client === null ? Promise.resolve(0) : client.reloadSession(id), 'info', onProgress),
    [batchRun, client],
  );

  const batchClose = useCallback(
    (ids: ReadonlySet<string>, onProgress?: (done: number, total: number) => void) =>
      batchRun(ids, '断开', (id) => client === null ? Promise.resolve(0) : client.closeSession(id), 'info', onProgress),
    [batchRun, client],
  );

  const closeSession = useCallback(
    async (sessionId: string) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.closeSession(sessionId);
        /* 3.0 起这是「停止端口监听」不是「删除」：设备仍在清单里，随时能再启动 */
        const text = lifecycleMessage(status, `停止设备 ${sessionId}`);
        if (status === 200 || status === 204) {
          notifierRef.current.success(text, '端口已释放，设备保留在设备清单里，可随时启动');
        } else {
          notifierRef.current.error(text);
        }
        await refreshSessions();
      } catch (error) {
        notifierRef.current.error('断开设备失败', (error as Error).message);
      }
    },
    [client, refreshSessions],
  );

  const connectSession = useCallback(
    async (deviceId: string) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.connectDevice(deviceId);
        const text = lifecycleMessage(status, `启动设备 ${deviceId}`);
        if (status === 200 || status === 201) {
          notifierRef.current.success(text, '端口已开始监听，等主机连接；设定值与事件启停按上次保留');
        } else {
          notifierRef.current.error(text);
        }
        await refreshSessions();
      } catch (error) {
        notifierRef.current.error('连接设备失败', (error as Error).message);
      }
    },
    [client, refreshSessions],
  );

  /* 手动上线/下线：GEM 里这是主机的 S1F17 / S1F15，这里给操作员一条同样的路。
     只改控制状态不动链路，所以不需要重连；失败时把后端的原因说清楚——
     最常见的是"链路还没建"，这时点上线没有任何意义。 */
  const setDeviceOnline = useCallback(
    async (deviceId: string, online: boolean) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.setDeviceOnline(deviceId, online);
        if (status === 200) {
          notifierRef.current.success(
            online ? `设备 ${deviceId} 已上线` : `设备 ${deviceId} 已下线`,
            online ? '按计划规则上报事件（等价于主机发 S1F17）' : '主动上报立即停止（等价于主机发 S1F15）',
          );
        } else if (status === 409) {
          notifierRef.current.error('设备还没连上主机', '先「启动」端口并等主机建立 HSMS 链接，才能上线');
        } else {
          notifierRef.current.error(lifecycleMessage(status, `${online ? '上线' : '下线'}设备 ${deviceId}`));
        }
        await refreshSessions();
        await refreshSessionDetail(deviceId);
      } catch (error) {
        notifierRef.current.error(online ? '上线失败' : '下线失败', (error as Error).message);
      }
    },
    [client, refreshSessions, refreshSessionDetail],
  );

  const reloadSession = useCallback(
    async (sessionId: string) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.reloadSession(sessionId);
        const text = lifecycleMessage(status, `重载会话 ${sessionId}`);
        if (status === 200) {
          notifierRef.current.success(text);
        } else {
          notifierRef.current.error(text);
        }
        await refreshSessions();
        await refreshSessionDetail(sessionId);
      } catch (error) {
        notifierRef.current.error('重载会话失败', (error as Error).message);
      }
    },
    [client, refreshSessions, refreshSessionDetail],
  );

  const trigger = useCallback(
    async (sessionId: string, ceid: number) => {
      if (client === null) {
        return;
      }
      try {
        const status = await client.trigger(sessionId, ceid);
        const text = `触发 ${sessionId} CEID ${ceid} -> HTTP ${status}`;
        if (status >= 200 && status < 300) {
          notifierRef.current.success(text);
        } else {
          notifierRef.current.error(text);
        }
      } catch (error) {
        notifierRef.current.error('触发失败', (error as Error).message);
      }
    },
    [client],
  );

  return useMemo(
    () => ({
      createSession,
      bulkCreateSessions,
      batchTrigger,
      batchReload,
      batchClose,
      closeSession,
      connectSession,
      setDeviceOnline,
      reloadSession,
      trigger,
    }),
    [createSession, bulkCreateSessions, batchTrigger, batchReload, batchClose,
      closeSession, connectSession, setDeviceOnline, reloadSession, trigger],
  );
}
