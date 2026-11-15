import { useCallback, useMemo } from 'react';
import { usePersistentState } from './usePersistentState';

/** 未分组的设备与主机都落在这一组。 */
export const DEFAULT_REGION = 'Default';

const STORAGE_KEY = 'secs.ui.regions';

interface RegionStore {
  /** 用户显式建过的分组名。 */
  names: string[];
  /** 配置文件名 → 分组；没写的按 Default 处理。 */
  devices: Record<string, string>;
  /** 主机实例 id → 分组；没写的按 Default 处理。 */
  hosts: Record<string, string>;
}

const DEFAULT_STORE: RegionStore = { names: [DEFAULT_REGION], devices: {}, hosts: {} };

export interface RegionsState {
  /** 全部分组名（Default 永远在第一位）。 */
  names: readonly string[];
  deviceRegion: (configName: string) => string;
  hostRegion: (hostId: string) => string;
  setDeviceRegion: (configName: string, region: string) => void;
  setHostRegion: (hostId: string, region: string) => void;
  addRegion: (name: string) => void;
}

function cleanName(value: string): string {
  return value.trim().slice(0, 32);
}

function normalize(store: RegionStore): RegionStore {
  return {
    names: Array.isArray(store.names) ? store.names.filter((name) => typeof name === 'string' && name.trim() !== '') : [],
    devices: store.devices !== null && typeof store.devices === 'object' ? store.devices : {},
    hosts: store.hosts !== null && typeof store.hosts === 'object' ? store.hosts : {},
  };
}

/**
 * 设备/主机的区域分组。
 *
 * <p>分组是"界面怎么组织设备"的偏好，不是设备本身的定义，所以只存在浏览器里：
 * 后端接口与配置文件都不用加字段，换一台机器打开界面也能正常显示（都归 Default）。</p>
 */
export function useRegions(): RegionsState {
  const [stored, setStored] = usePersistentState<RegionStore>(STORAGE_KEY, DEFAULT_STORE);
  const store = useMemo(() => normalize(stored), [stored]);

  const names = useMemo(() => Array.from(new Set([
    DEFAULT_REGION,
    ...store.names.map(cleanName).filter((name) => name !== ''),
    ...Object.values(store.devices).map(cleanName).filter((name) => name !== ''),
    ...Object.values(store.hosts).map(cleanName).filter((name) => name !== ''),
  ])), [store]);

  const deviceRegion = useCallback(
    (configName: string) => cleanName(store.devices[configName] ?? '') || DEFAULT_REGION,
    [store.devices],
  );
  const hostRegion = useCallback(
    (hostId: string) => cleanName(store.hosts[hostId] ?? '') || DEFAULT_REGION,
    [store.hosts],
  );

  const setDeviceRegion = useCallback((configName: string, region: string) => {
    const name = cleanName(region) || DEFAULT_REGION;
    setStored((previous) => {
      const base = normalize(previous);
      return {
        ...base,
        names: base.names.includes(name) ? base.names : [...base.names, name],
        devices: { ...base.devices, [configName]: name },
      };
    });
  }, [setStored]);

  const setHostRegion = useCallback((hostId: string, region: string) => {
    const name = cleanName(region) || DEFAULT_REGION;
    setStored((previous) => {
      const base = normalize(previous);
      return {
        ...base,
        names: base.names.includes(name) ? base.names : [...base.names, name],
        hosts: { ...base.hosts, [hostId]: name },
      };
    });
  }, [setStored]);

  const addRegion = useCallback((name: string) => {
    const value = cleanName(name);
    if (value === '') {
      return;
    }
    setStored((previous) => {
      const base = normalize(previous);
      if (base.names.includes(value)) {
        return base;
      }
      return { ...base, names: [...base.names, value] };
    });
  }, [setStored]);

  return { names, deviceRegion, hostRegion, setDeviceRegion, setHostRegion, addRegion };
}
