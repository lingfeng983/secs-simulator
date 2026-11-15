import type { ApiClient, EventPlanView } from '../../api';

export interface PlanBatchResult {
  succeeded: string[];
  failed: { id: string; reason: string }[];
  skipped: number;
}

export async function setPlansEnabled(
  plans: readonly Pick<EventPlanView, 'id' | 'enabled'>[],
  selected: ReadonlySet<string>,
  enabled: boolean,
  sessionId: string,
  setEnabled: ApiClient['setEventEnabled'],
  onProgress?: (done: number, total: number) => void,
): Promise<PlanBatchResult> {
  const chosen = plans.filter((plan) => selected.has(plan.id));
  const targets = chosen.filter((plan) => plan.enabled !== enabled);
  const result: PlanBatchResult = { succeeded: [], failed: [], skipped: chosen.length - targets.length };
  onProgress?.(0, targets.length);
  for (const plan of targets) {
    try {
      const status = await setEnabled(sessionId, plan.id, enabled);
      if (status === 200) result.succeeded.push(plan.id);
      else result.failed.push({ id: plan.id, reason: `状态码 ${status}` });
    } catch (error) {
      result.failed.push({ id: plan.id, reason: error instanceof Error ? error.message : String(error) });
    }
    onProgress?.(result.succeeded.length + result.failed.length, targets.length);
  }
  return result;
}
