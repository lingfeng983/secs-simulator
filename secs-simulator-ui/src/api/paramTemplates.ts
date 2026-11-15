/**
 * 参数模板库端点：/api/v2/param-templates（所有设备共享的「参数变化逻辑」）。
 *
 * <p>模板体就是一段值定义（schema v7）：{@code {"source": {mode:"curve"|"expr"|"const"|"cycle", …}}}，
 * 与任何变量无关。设备配置时从库里选择，把这段 source 深拷贝进数据项，
 * 之后修改库不影响已配置设备，设备里也不留引用。</p>
 */

import { apiUrl, readJson, request } from './base';

/** 参数模板条目（列表直接带模板体，设备绑定不用二次请求）。 */
export interface ParamTemplate {
  id: string;
  name: string;
  description: string;
  source: 'builtin' | 'custom';
  readonly: boolean;
  /** 模板体：`{ source: {…} }`（一段值定义）。 */
  body: Record<string, unknown>;
  updatedAt: number;
}

export const paramTemplateApi = {
  async paramTemplates(): Promise<ParamTemplate[]> {
    const body = await readJson<{ templates?: ParamTemplate[] }>(
      await request(apiUrl('/api/v2/param-templates')),
    );
    return body.templates ?? [];
  },

  /** 新建（id 缺省，服务端生成）/ 更新参数模板；返回落库后的 id。 */
  async saveParamTemplate(requestBody: {
    id?: string;
    name: string;
    description?: string;
    /** 模板体 JSON 原文（后端归一化校验后入库）。 */
    body: string;
  }): Promise<string> {
    const body = await readJson<{ ok?: boolean; error?: string; problems?: string[]; id?: string }>(
      await request(apiUrl('/api/v2/param-templates'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(requestBody),
      }),
    );
    if (!body.ok) {
      const problem = body.problems !== undefined && body.problems.length > 0
        ? body.problems.join('；')
        : body.error ?? '保存参数模板失败';
      throw new Error(problem);
    }
    return body.id ?? '';
  },

  async deleteParamTemplate(id: string): Promise<boolean> {
    const response = await request(apiUrl(`/api/v2/param-templates/${encodeURIComponent(id)}`), {
      method: 'DELETE',
    });
    return response.ok;
  },
};
