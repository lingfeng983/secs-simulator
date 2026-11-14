import { useCallback, useMemo, useRef, useState } from 'react';
import type { ApiClient } from '../api';
import type { Notifier } from './useToasts';

/**
 * 后端日志级别：读当前值、切换。
 *
 * <p>从 useBackend 拆出。级别只影响服务端日志详细程度，与界面状态无关，
 * 所以独立成一个小 hook，跟着管理页的生命周期走。</p>
 */
export interface LogLevelState {
  /** 当前级别（error/info/debug）；后端不支持该端点时保持 info。 */
  level: string;
  refresh: () => Promise<void>;
  /** 切换级别；返回是否成功。 */
  set: (level: string) => Promise<boolean>;
}

export function useLogLevel(client: ApiClient | null, notifier: Notifier): LogLevelState {
  const [level, setLevel] = useState('info');
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  const refresh = useCallback(async () => {
    if (client === null) {
      return;
    }
    try {
      setLevel(await client.logLevel());
    } catch {
      /* 老后端没有这个端点：保持 info */
    }
  }, [client]);

  const set = useCallback(
    async (next: string) => {
      if (client === null) {
        return false;
      }
      try {
        const changed = await client.setLogLevel(next);
        if (changed) {
          setLevel(next);
          notifierRef.current.info(`日志级别已切换为 ${next}`);
        } else {
          /* 后端 400 unknown-level 时以前静默：用户以为切了，其实没切 */
          notifierRef.current.error('切换日志级别失败', `未知级别：${next}（可选 error/info/debug）`);
        }
        return changed;
      } catch (error) {
        notifierRef.current.error('切换日志级别失败', (error as Error).message);
        return false;
      }
    },
    [client],
  );

  return useMemo(() => ({ level, refresh, set }), [level, refresh, set]);
}
