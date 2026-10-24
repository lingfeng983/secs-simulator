import { useCallback, useRef } from 'react';
import type { ApiClient, ConfigProblems, ConfigSaveResult, ConfigTemplateEntry } from '../api';
import type { Notifier } from './useToasts';

/**
 * 配置域操作：读 / 校验 / 保存 / 新建。
 *
 * <p>从 useBackend 拆出（会话域在 useSessionCommands）：这些操作只依赖
 * client 与通知，不碰其它域的状态。</p>
 */
export function useConfigCommands({
  client,
  notifier,
  refreshConfigs,
}: {
  client: ApiClient | null;
  notifier: Notifier;
  refreshConfigs: () => Promise<void>;
}) {
  const notifierRef = useRef(notifier);
  notifierRef.current = notifier;

  const createConfig = useCallback(
    async (name: string, templateId?: string): Promise<number> => {
      if (client === null) {
        return 0;
      }
      try {
        const status = await client.createConfig(name, templateId);
        if (status === 201) {
          const kind = templateId && templateId !== 'bare' ? `（模板: ${templateId}）` : '';
          notifierRef.current.success(`已新建 ${name}${kind}，改完点「保存」才会落盘`);
        } else if (status === 409) {
          notifierRef.current.warn('已经有同名的配置了，直接从上面选它来改');
        } else {
          notifierRef.current.error(`新建失败（HTTP ${status}）`);
        }
        await refreshConfigs();
        return status;
      } catch (error) {
        notifierRef.current.error('新建配置失败', (error as Error).message);
        return 0;
      }
    },
    [client, refreshConfigs],
  );

  const readConfig = useCallback(
    async (name: string): Promise<string | null> => {
      if (client === null) {
        return null;
      }
      try {
        return await client.configText(name);
      } catch (error) {
        notifierRef.current.error(`打开配置 ${name} 失败`, (error as Error).message);
        return null;
      }
    },
    [client],
  );

  const validateConfig = useCallback(
    async (name: string, text: string): Promise<ConfigProblems | null> => {
      if (client === null) {
        return null;
      }
      try {
        return await client.validateConfig(name, text);
      } catch (error) {
        notifierRef.current.error('校验失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  const saveConfig = useCallback(
    async (name: string, text: string): Promise<ConfigSaveResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.saveConfig(name, text);
        if (result.status === 200) {
          notifierRef.current.success(`已保存 ${name}（已在运行的会话需要重载才生效）`);
        } else if (result.status === 422) {
          notifierRef.current.error('保存被拒：配置有问题，未落盘');
        } else {
          notifierRef.current.error(`保存失败（HTTP ${result.status}）`);
        }
        return result;
      } catch (error) {
        notifierRef.current.error('保存失败', (error as Error).message);
        return null;
      }
    },
    [client],
  );

  /** 内置设备模板目录；取不到就回空列表，选型下拉里只剩骨架。 */
  const configTemplates = useCallback(async (): Promise<ConfigTemplateEntry[]> => {
    if (client === null) {
      return [];
    }
    try {
      return await client.configTemplates();
    } catch (error) {
      notifierRef.current.error('取设备模板目录失败', (error as Error).message);
      return [];
    }
  }, [client]);

  return { createConfig, readConfig, validateConfig, saveConfig, configTemplates };
}
