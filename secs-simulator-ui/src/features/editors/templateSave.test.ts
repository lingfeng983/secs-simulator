import { describe, expect, it } from 'vitest';
import { saveTemplateDraft } from './templateSave';

function storage(valid = true, status = 200) {
  const documents = new Map<string, string>([['builtin', 'original'], ['custom', 'old']]);
  const calls: string[] = [];
  return {
    documents, calls,
    client: {
      validateConfig: async () => { calls.push('validate'); return { valid, problems: valid ? [] : ['配置不完整'] }; },
      createTemplate: async (id: string, text: string) => { calls.push(`create:${id}`); if (status === 200) documents.set(id, text); return status; },
      updateTemplate: async (id: string, text: string) => { calls.push(`update:${id}`); if (status === 200) documents.set(id, text); return status; },
    },
  };
}

describe('saveTemplateDraft', () => {
  it('validates and creates a new template only when saving', async () => {
    const { client, documents, calls } = storage();
    expect(documents.has('new')).toBe(false);
    const result = await saveTemplateDraft(client, { id: 'new', text: 'edited', create: true }, ['builtin', 'custom']);
    expect(result.saved).toBe(true);
    expect(documents.get('new')).toBe('edited');
    expect(calls).toEqual(['validate', 'create:new']);
  });
  it('updates a custom template without creating another', async () => {
    const { client, documents, calls } = storage();
    await saveTemplateDraft(client, { id: 'custom', text: 'edited', create: false }, ['builtin', 'custom']);
    expect(documents.get('custom')).toBe('edited');
    expect(documents.size).toBe(2);
    expect(calls).toEqual(['validate', 'update:custom']);
  });
  it('saves a builtin copy under a different id and preserves the original', async () => {
    const { client, documents } = storage();
    await saveTemplateDraft(client, { id: 'builtin-copy', text: 'edited', create: true }, ['builtin', 'custom']);
    expect(documents.get('builtin')).toBe('original');
    expect(documents.get('builtin-copy')).toBe('edited');
  });
  it('rejects invalid config without writing', async () => {
    const { client, documents, calls } = storage(false);
    const result = await saveTemplateDraft(client, { id: 'new', text: 'bad', create: true }, []);
    expect(result).toMatchObject({ saved: false, problems: ['配置不完整'] });
    expect(documents.has('new')).toBe(false);
    expect(calls).toEqual(['validate']);
  });
  it.each(['builtin', '', '../escape'])('rejects an existing or invalid create id %s', async (id) => {
    const { client, documents, calls } = storage();
    const result = await saveTemplateDraft(client, { id, text: 'edited', create: true }, ['builtin', 'custom']);
    expect(result.saved).toBe(false);
    expect(calls).toEqual([]);
    expect(documents.get('builtin')).toBe('original');
  });
  it('retains failure details when saving fails', async () => {
    const { client, documents } = storage(true, 409);
    const result = await saveTemplateDraft(client, { id: 'new', text: 'edited', create: true }, []);
    expect(result.saved).toBe(false);
    expect(result.problems.join(' ')).toContain('409');
    expect(documents.has('new')).toBe(false);
  });
});
