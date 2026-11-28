import { useEffect, useState } from 'react';
import type { BackendController } from './useBackend';

/**
 * 设备的监听端点：配置 `session` 段里的 绑定地址 / 端口 / Device ID。
 *
 * <p>设备页那一行「监听 0.0.0.0:5000 | 0」和主机清单的「快速连接设备」都读它。
 * 端点只存在配置文件里（会话摘要不带端口与 Device ID），所以按配置名读一次
 * 原文解析，按「配置名 + 修改时间」缓存：切来切去不会反复读同一份文件，
 * 配置改过（目录里的 modifiedMs 变了）才重新读。</p>
 */
export interface DeviceEndpoint {
  /** 绑定/监听地址；配置没写时按后端默认的 0.0.0.0。 */
  host: string;
  port: number | null;
  deviceId: number | null;
}

interface CacheEntry extends DeviceEndpoint {
  modifiedMs: number;
}

const cache = new Map<string, CacheEntry>();

/** 把配置原文里的 session 段解析成端点；不是合法 JSON 或没有 session 段时返回 null。 */
function parseSession(text: string): DeviceEndpoint | null {
  try {
    const doc = JSON.parse(text) as { session?: Record<string, unknown> };
    const session = doc.session;
    if (session === undefined || session === null) {
      return null;
    }
    const host = typeof session.bindHost === 'string' && session.bindHost.trim() !== ''
      ? session.bindHost.trim()
      : '0.0.0.0';
    const port = Number(session.port);
    const deviceId = Number(session.deviceId);
    return {
      host,
      port: Number.isFinite(port) && port > 0 ? port : null,
      deviceId: Number.isFinite(deviceId) ? deviceId : 0,
    };
  } catch {
    return null;
  }
}

/**
 * 读一台设备的端点（带缓存）。
 *
 * <p>直接打 client 而不是 backend.readConfig：这是页面上的被动展示，
 * 读不到就安静地不显示，不该弹一条"打开配置失败"。</p>
 */
export async function readDeviceEndpoint(
  backend: BackendController,
  configName: string,
  modifiedMs: number,
): Promise<DeviceEndpoint | null> {
  if (configName === '') {
    return null;
  }
  const hit = cache.get(configName);
  if (hit !== undefined && hit.modifiedMs === modifiedMs) {
    return hit;
  }
  if (backend.client === null) {
    return null;
  }
  try {
    const snapshot = await backend.client.configText(configName);
    const parsed = parseSession(snapshot.text);
    if (parsed === null) {
      return null;
    }
    cache.set(configName, { ...parsed, modifiedMs });
    return parsed;
  } catch {
    return null;
  }
}

/**
 * 设备页用：跟着配置名与目录里的修改时间取端点。
 *
 * @param backend    后端控制器
 * @param configName 配置文件（带 .json）
 * @param fallbackPort 读不到配置时的端口兜底（总览里的端口）
 */
export function useDeviceEndpoint(
  backend: BackendController,
  configName: string,
  fallbackPort?: number,
): DeviceEndpoint | null {
  const modifiedMs = backend.configList.find((entry) => entry.name === configName)?.modifiedMs ?? 0;
  const [endpoint, setEndpoint] = useState<DeviceEndpoint | null>(null);
  const client = backend.client;

  useEffect(() => {
    if (configName === '') {
      setEndpoint(null);
      return;
    }
    /* 换配置时先清掉旧端点：读取失败或还没回来时，继续返回上一台的
       host/port 会让设备页的「监听」标签显示别的设备的地址。 */
    setEndpoint(null);
    const hit = cache.get(configName);
    if (hit !== undefined && hit.modifiedMs === modifiedMs) {
      setEndpoint(hit);
      return;
    }
    if (client === null) {
      return;
    }
    let cancelled = false;
    void client.configText(configName)
      .then((snapshot) => {
        const parsed = parseSession(snapshot.text);
        if (parsed === null) {
          return;
        }
        cache.set(configName, { ...parsed, modifiedMs });
        if (!cancelled) {
          setEndpoint(parsed);
        }
      })
      .catch(() => {
        /* 读不到就不显示端点：这是展示信息，不是操作，不打扰用户 */
      });
    return () => {
      cancelled = true;
    };
  }, [client, configName, modifiedMs]);

  if (endpoint !== null) {
    return endpoint;
  }
  /* 配置还没读回来（或这台的配置已被删）：端口先用总览里的数兜住 */
  return fallbackPort === undefined ? null : { host: '0.0.0.0', port: fallbackPort, deviceId: null };
}
