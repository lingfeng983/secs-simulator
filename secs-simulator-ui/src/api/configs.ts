/**
 * 配置域端点：配置目录、模板目录、配置原文读写与校验。
 *
 * <p>这里没有任何会话状态：配置是磁盘上的文件，改完要重载会话才生效。</p>
 */

import type { ApiClient, ConfigEntry, ConfigProblems, ConfigSectionApplyResult, ConfigSectionEffect, ConfigSectionName, ConfigSectionSaveResult, ConfigTemplateEntry, DeviceBatchResult, PreviewData } from './types';
import { ApiError, apiUrl, commandStatus, LONG_REQUEST_TIMEOUT_MS, readJson, request } from './base';

type ConfigApi = Pick<
  ApiClient,
  | 'configs'
  | 'createConfig'
  | 'createDevicesBatch'
  | 'deleteConfig'
  | 'configTemplates'
  | 'templates'
  | 'templateText'
  | 'createTemplate'
  | 'updateTemplate'
  | 'deleteTemplate'
  | 'configText'
  | 'validateConfig'
  | 'saveConfig'
  | 'saveConfigSections'
  | 'applyConfigSections'
  | 'configRevisions'
  | 'previewConfigDraft'
>;

/**
 * 后端的 detail 可能是字符串、冲突数组或常量差异列表：统一翻成一句人话。
 *
 * <p>守卫 B 回的是 {@code [{kind,name,ceids}]}，守卫 C 回的是
 * {@code warnings:[{ecid,runtime,config}]}——直接 {@code String()} 会变成
 * 「[object Object]」，现场看不懂被哪条变量挡住了。</p>
 */
function detailText(raw: unknown): string {
  if (raw == null) {
    return '';
  }
  if (typeof raw === 'string') {
    return raw;
  }
  if (Array.isArray(raw)) {
    return raw.map((item) => {
      if (item === null || typeof item !== 'object') {
        return String(item);
      }
      const entry = item as { kind?: string; name?: string; ceids?: number[]; ecid?: number; runtime?: number; config?: number };
      if (entry.kind !== undefined) {
        const kind = entry.kind === 'context' ? '晶圆属性' : '变量';
        const ceids = (entry.ceids ?? []).join('/');
        return `${kind} ${entry.name ?? ''} 正被周期事件 CEID ${ceids} 引用`;
      }
      if (entry.ecid !== undefined) {
        return `常量 ${entry.ecid}：运行时值 ${entry.runtime ?? '?'} → 配置值 ${entry.config ?? '?'}`;
      }
      return JSON.stringify(item);
    }).join('；');
  }
  if (typeof raw === 'object') {
    return JSON.stringify(raw);
  }
  return String(raw);
}

/** 按配置草稿算确定性预览（不落盘、不需要运行中的会话）；编译失败返回 null。 */
async function previewConfigDraft(text: string, fromMs: number, toMs: number,
                                  buckets: number, withEvents: boolean): Promise<PreviewData | null> {
  const query = new URLSearchParams({
    from: String(fromMs),
    to: String(toMs),
    buckets: String(buckets),
  });
  if (!withEvents) {
    query.set('withEvents', 'false');
  }
  const response = await request(apiUrl(`/api/v1/config-preview?${query}`), {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    body: text,
  }, LONG_REQUEST_TIMEOUT_MS);
  if (!response.ok) {
    return null;
  }
  return readJson<PreviewData>(response);
}

export const configApi: ConfigApi = {
  previewConfigDraft,
  async configs() {
    const body = await readJson<{ configs?: ConfigEntry[] }>(
      await request(apiUrl('/api/v1/configs')),
    );
    return body.configs ?? [];
  },
  async createConfig(name, templateId) {
    const response = await request(apiUrl('/api/v1/configs'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(templateId ? { name, template: templateId } : { name }),
    });
    return commandStatus(response);
  },
  async createDevicesBatch(params) {
    /* 最多 100 台、逐台建配置并启动，8 秒不够 */
    const response = await request(apiUrl('/api/v1/devices/bulk'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(params),
    }, LONG_REQUEST_TIMEOUT_MS);
    const text = await response.text();
    let body: DeviceBatchResult | null = null;
    try {
      body = JSON.parse(text) as DeviceBatchResult;
    } catch {
      body = null;
    }
    /* 409（整批重名）与 422（一台都没建成）都带逐台原因：当成结果返回，
       让面板能把"哪几台叫什么名字撞了"直接说给用户，而不是一句 HTTP 409 */
    if (body !== null && Array.isArray(body.created)) {
      return {
        created: body.created,
        failed: Array.isArray(body.failed) ? body.failed : [],
        conflicts: Array.isArray(body.conflicts) ? body.conflicts : [],
      };
    }
    throw new ApiError(response.status, 'bad-body',
      `${response.status} 返回的不是批量结果：${text.slice(0, 120)}`);
  },
  async deleteConfig(name) {
    /* 后端会先停掉这台设备的会话再软删配置：删完它就从这个清单里消失了 */
    const response = await request(apiUrl(`/api/v1/configs/${encodeURIComponent(name)}`), {
      method: 'DELETE',
    });
    return commandStatus(response);
  },
  async configTemplates() {
    const body = await readJson<{ templates?: ConfigTemplateEntry[] }>(
      await request(apiUrl('/api/v1/config-templates')),
    );
    return body.templates ?? [];
  },
  async templates() {
    const body = await readJson<{ templates?: ConfigTemplateEntry[] }>(
      await request(apiUrl('/api/v1/templates')),
    );
    return body.templates ?? [];
  },
  async templateText(id) {
    const response = await request(apiUrl(`/api/v1/templates/${encodeURIComponent(id)}`));
    /* 404 = 模板不存在，对调用方来说是"没有原文"而不是错误 */
    if (response.status === 404) {
      return null;
    }
    const body = await readJson<{ text?: string }>(response);
    return body.text ?? null;
  },
  async createTemplate(id, text) {
    const response = await request(apiUrl('/api/v1/templates'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ id, text }),
    });
    return commandStatus(response);
  },
  async updateTemplate(id, text) {
    const response = await request(apiUrl(`/api/v1/templates/${encodeURIComponent(id)}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: text,
    });
    return commandStatus(response);
  },
  async deleteTemplate(id) {
    const response = await request(apiUrl(`/api/v1/templates/${encodeURIComponent(id)}`), {
      method: 'DELETE',
    });
    return commandStatus(response);
  },
  async configText(name) {
    const body = await readJson<{ name?: string; text?: string; modifiedMs?: number }>(
      await request(apiUrl(`/api/v1/configs/${encodeURIComponent(name)}`)),
    );
    return {
      text: body.text ?? '',
      modifiedMs: typeof body.modifiedMs === 'number' ? body.modifiedMs : null,
    };
  },
  async validateConfig(name, text) {
    const body = await readJson<ConfigProblems>(
      await request(apiUrl(`/api/v1/configs/${encodeURIComponent(name)}/validate`), {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: text,
      }, LONG_REQUEST_TIMEOUT_MS),
    );
    return { valid: body.valid ?? false, problems: body.problems ?? [] };
  },
  async saveConfig(name, text, expectedModifiedMs) {
    const headers: Record<string, string> = { 'Content-Type': 'text/plain; charset=utf-8' };
    /* 快照带回来的 modifiedMs 经 If-Match 回去：期间被别人改过就 409，不静默覆盖 */
    if (expectedModifiedMs != null) {
      headers['If-Match'] = String(expectedModifiedMs);
    }
    const response = await request(apiUrl(`/api/v1/configs/${encodeURIComponent(name)}`), {
      method: 'PUT',
      headers,
      body: text,
    }, LONG_REQUEST_TIMEOUT_MS);

    /* 失败响应也带 problems，尽量把它带回来；解析不了就当没有 */
    let problems: string[] = [];
    try {
      const body = (await response.json()) as { problems?: string[] };
      problems = body.problems ?? [];
    } catch {
      /* 非 JSON 响应（网关错误页等）没有可带回来的问题 */
    }
    return { status: response.status, problems };
  },
  async saveConfigSections(name, sections, expectedModifiedMs, confirmOverwrite) {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (expectedModifiedMs != null) {
      headers['If-Match'] = String(expectedModifiedMs);
    }
    const query = confirmOverwrite === true ? '?confirmOverwrite=1' : '';
    const response = await request(
      apiUrl(`/api/v1/configs/${encodeURIComponent(name)}/sections${query}`),
      {
        method: 'PUT',
        headers,
        body: JSON.stringify({ sections }),
      },
      LONG_REQUEST_TIMEOUT_MS,
    );

    /* 失败响应带 error/detail/problems，尽量带回来给页面显示原因 */
    let body: {
      error?: string; detail?: unknown;
      problems?: string[] | Array<{ path?: string; message?: string }>;
      warnings?: unknown;
      changed?: ConfigSectionName[]; effect?: ConfigSectionEffect;
    } = {};
    try {
      body = (await response.json()) as typeof body;
    } catch {
      /* 非 JSON 响应（网关错误页等）没有可带回来的细节 */
    }
    const problems = (body.problems ?? []).map((item) => {
      if (typeof item === 'string') {
        return item;
      }
      const path = item.path ?? '';
      return path === '' ? (item.message ?? String(item)) : `${path}：${item.message ?? ''}`;
    });
    return {
      status: response.status,
      problems,
      changed: body.changed ?? [],
      effect: body.effect ?? 'hot',
      error: body.error ?? '',
      /* 常量覆盖的确认文案在 warnings 里（ecv-overwrite 没有 detail） */
      detail: detailText(body.detail ?? body.warnings ?? ''),
    };
  },

  async applyConfigSections(deviceId, sections) {
    const response = await request(
      apiUrl(`/api/v2/devices/${encodeURIComponent(deviceId)}/apply-sections`),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sections }),
      },
      LONG_REQUEST_TIMEOUT_MS,
    );
    let body: {
      effect?: ConfigSectionEffect; changed?: ConfigSectionName[];
      reset?: string[]; preserved?: string[];
      plansKept?: number; pinsDropped?: number;
      error?: string; detail?: unknown;
    } = {};
    try {
      body = (await response.json()) as typeof body;
    } catch {
      /* 同上：没有 body 也要把状态码带回去 */
    }
    return {
      status: response.status,
      effect: body.effect ?? 'hot',
      changed: body.changed ?? [],
      reset: body.reset ?? [],
      preserved: body.preserved ?? [],
      plansKept: body.plansKept ?? 0,
      pinsDropped: body.pinsDropped ?? 0,
      error: body.error ?? '',
      detail: detailText(body.detail ?? ''),
    } satisfies ConfigSectionApplyResult;
  },

  async configRevisions(name, limit = 50) {
    const body = await readJson<{ revisions?: Array<{
      revision: number; sha256: string; createdAt: number; createdBy: string; note: string;
    }> }>(
      await request(apiUrl(`/api/v2/devices/${encodeURIComponent(name)}/config/revisions?limit=${limit}`)),
    );
    return body.revisions ?? [];
  },
};
