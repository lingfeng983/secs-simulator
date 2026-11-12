/**
 * 前端 API 的公开入口（barrel）。
 *
 * <p>域模块各写一段端点，`createClient()` 把它们拼成 {@link ApiClient}：
 * 返回类型就是契约本身，少写/写错一个方法会在这里编译失败。</p>
 */

import type { ApiClient } from './types';
import { configApi } from './configs';
import { hostSimApi } from './hostsim';
import { hostsV2Api } from './hostsV2';
import { messageApi } from './messages';
import { sessionApi } from './sessions';

export * from './types';
export * from './base';
export * from './hostsV2';

/** 组装完整客户端；四个域互不重叠，合并即全量。 */
export function createClient(): ApiClient {
  return {
    ...sessionApi,
    ...configApi,
    ...messageApi,
    ...hostSimApi,
    ...hostsV2Api,
  };
}
