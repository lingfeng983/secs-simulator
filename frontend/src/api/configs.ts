/**
 * 配置域端点：配置目录、模板目录、配置原文读写与校验。
 *
 * <p>这里没有任何会话状态：配置是磁盘上的文件，改完要重载会话才生效。</p>
 */

import type { ApiClient, ConfigEntry, ConfigProblems, ConfigTemplateEntry } from './types';
import { apiUrl, commandStatus, LONG_REQUEST_TIMEOUT_MS, readJson, request } from './base';

type ConfigApi = Pick<
  ApiClient,
  | 'configs'
  | 'createConfig'
  | 'configTemplates'
  | 'templates'
  | 'templateText'
  | 'createTemplate'
  | 'updateTemplate'
  | 'deleteTemplate'
  | 'configText'
  | 'validateConfig'
  | 'saveConfig'
>;

export const configApi: ConfigApi = {
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
};
