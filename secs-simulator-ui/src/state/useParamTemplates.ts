import { useCallback, useEffect, useState } from 'react';
import type { ParamTemplate } from '../api/paramTemplates';
import type { BackendController } from './useBackend';

/**
 * 参数模板库（所有设备共享）：加载一次 + 手动刷新。
 *
 * <p>参数模板页管理库本身；变量配置的值来源下拉合并库条目，
 * 选中即把模板体复制进设备配置（复制快照语义）。</p>
 */
export function useParamTemplates(backend: BackendController): {
  entries: ParamTemplate[];
  ready: boolean;
  reload: () => Promise<void>;
} {
  const [entries, setEntries] = useState<ParamTemplate[]>([]);
  const [ready, setReady] = useState(false);

  const reload = useCallback(async () => {
    const client = backend.client;
    if (client === null) {
      setEntries([]);
      setReady(true);
      return;
    }
    try {
      setEntries(await client.paramTemplates());
    } catch {
      /* 库读不到不阻塞编辑：下拉只是少了「模板库」一组 */
      setEntries([]);
    } finally {
      setReady(true);
    }
  }, [backend.client]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { entries, ready, reload };
}
