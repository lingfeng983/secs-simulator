import type { ApiClient } from '../../api';

export async function saveTemplateDraft(
  client: Pick<ApiClient, 'validateConfig' | 'createTemplate' | 'updateTemplate'>,
  draft: { id: string; text: string; create: boolean },
  existingIds: readonly string[],
): Promise<{ saved: boolean; problems: string[] }> {
  const id = draft.id.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(id)) {
    return { saved: false, problems: ['模板 id 以字母或数字开头，只能包含字母、数字、点、下划线和短横线'] };
  }
  if (draft.create && existingIds.includes(id)) {
    return { saved: false, problems: [`模板 ${id} 已存在，请换一个 id`] };
  }
  const validation = await client.validateConfig('template.json', draft.text);
  if (!validation.valid) return { saved: false, problems: validation.problems };
  const status = draft.create
    ? await client.createTemplate(id, draft.text)
    : await client.updateTemplate(id, draft.text);
  const saved = status === 200 || (draft.create && status === 201);
  return { saved, problems: saved ? [] : [`保存失败（状态码 ${status}），请检查后重试`] };
}
