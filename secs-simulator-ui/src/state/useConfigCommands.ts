import { useCallback, useMemo, useRef } from 'react';
import type {
  ApiClient, ConfigProblems, ConfigSaveResult, ConfigSectionApplyResult, ConfigSectionName,
  ConfigSectionSaveResult, ConfigTemplateEntry, ConfigTextSnapshot, DeviceBatchParams, DeviceBatchResult,
} from '../api';
import { effectNote } from '../shared/lib/configSections';
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
  reloadSessionsUsing,
}: {
  client: ApiClient | null;
  notifier: Notifier;
  refreshConfigs: () => Promise<void>;
  /** 保存成功后自动重载使用该配置的会话（热生效或重建由后端判定）。 */
  reloadSessionsUsing: (configName: string) => Promise<void>;
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
          notifierRef.current.success(`已新建 ${name}${kind}，改完点「保存」才会写入文件`);
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

  /**
   * 批量新建设备：一台一份配置，名字 = 前缀 + 序号。
   *
   * <p>不在这里弹成功提示：调用方还要处理"设备名重复"这种整批拒绝的情形
   * （`conflicts` 非空），由它决定说哪一句。</p>
   */
  const createDevicesBatch = useCallback(
    async (params: DeviceBatchParams): Promise<DeviceBatchResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        return await client.createDevicesBatch(params);
      } catch (error) {
        notifierRef.current.error('批量新建失败', (error as Error).message);
        return null;
      } finally {
        /* 建到了没有、建了几台，都要让清单重新取一遍：
           整批被拒时清单没变，重取一次也不贵 */
        await refreshConfigs();
      }
    },
    [client, refreshConfigs],
  );

  /**
   * 删掉一台设备（软删）。
   *
   * <p>不在这里弹提示：单个删和批量删的成功口径不一样（批量要看总数），
   * 提示交给调用方按结果汇总，免得一次批量删弹出十条 toast。</p>
   */
  const deleteConfig = useCallback(
    async (name: string): Promise<number> => {
      if (client === null) {
        return 0;
      }
      try {
        const status = await client.deleteConfig(name);
        await refreshConfigs();
        return status;
      } catch (error) {
        notifierRef.current.error(`删除 ${name} 失败`, (error as Error).message);
        return 0;
      }
    },
    [client, refreshConfigs],
  );

  const readConfig = useCallback(    async (name: string): Promise<ConfigTextSnapshot | null> => {
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
    async (name: string, text: string, expectedModifiedMs?: number | null): Promise<ConfigSaveResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.saveConfig(name, text, expectedModifiedMs);
        if (result.status === 200) {
          notifierRef.current.success(`已保存 ${name}，正在让相关会话生效…`);
          await reloadSessionsUsing(name);
        } else if (result.status === 409) {
          notifierRef.current.warn('配置在编辑期间被其他窗口修改过，未覆盖；请点「重新载入」');
        } else if (result.status === 422) {
          notifierRef.current.error('保存被拒：配置有问题，文件没有改动');
        } else {
          notifierRef.current.error(`保存失败（HTTP ${result.status}）`);
        }
        return result;
      } catch (error) {
        notifierRef.current.error('保存失败', (error as Error).message);
        return null;
      }
    },
    [client, reloadSessionsUsing],
  );

  /**
   * 按段保存：只提交本页拥有的片段。
   *
   * <p>不在这里自动重载会话——保存完要按"实际变化的段"调
   * {@link applyConfigSectionsLike}，逐段最小生效；整份重载会把没改的东西也重置。
   * 成功只弹右下角 toast，页面内不写「已保存」（2026-11-18 反馈）。</p>
   */
  const saveConfigSections = useCallback(
    async (
      name: string,
      sections: Partial<Record<ConfigSectionName, unknown>>,
      expectedModifiedMs?: number | null,
      confirmOverwrite?: boolean,
    ): Promise<ConfigSectionSaveResult | null> => {
      if (client === null) {
        return null;
      }
      try {
        const result = await client.saveConfigSections(name, sections, expectedModifiedMs, confirmOverwrite);
        if (result.status === 200) {
          notifierRef.current.success(`已保存 ${name}`);
          await refreshConfigs();
        }
        /* 守卫拒绝与并发冲突由页面按 error 显示原因（那里才知道是哪一页） */
        return result;
      } catch (error) {
        notifierRef.current.error('保存失败', (error as Error).message);
        return null;
      }
    },
    [client, refreshConfigs],
  );

  /** 分段生效：把刚保存的段应用到正在跑的这台设备。 */
  const applyConfigSections = useCallback(
    async (deviceId: string, sections: readonly ConfigSectionName[]): Promise<ConfigSectionApplyResult | null> => {
      if (client === null || deviceId === '' || sections.length === 0) {
        return null;
      }
      try {
        const result = await client.applyConfigSections(deviceId, sections);
        if (result.status !== 200) {
          notifierRef.current.warn(
            '配置已保存到文件，但设备未生效',
            result.detail === '' ? result.error : result.detail,
          );
          return result;
        }
        /* 只有"有东西被重置"时才补一条：周期被停、钉值失效都属于必须知道的事 */
        const note = effectNote(result.effect, result.reset, result.pinsDropped, result.plansKept);
        if (note !== '' && result.reset.includes('cycle')) {
          notifierRef.current.info('配置已生效', note);
        }
        return result;
      } catch (error) {
        notifierRef.current.warn('配置已保存，但设备未生效', (error as Error).message);
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

  return useMemo(
    () => ({
      createConfig, createDevicesBatch, deleteConfig, readConfig, validateConfig, saveConfig,
      saveConfigSections, applyConfigSections, configTemplates,
    }),
    [createConfig, createDevicesBatch, deleteConfig, readConfig, validateConfig, saveConfig,
      saveConfigSections, applyConfigSections, configTemplates],
  );
}
