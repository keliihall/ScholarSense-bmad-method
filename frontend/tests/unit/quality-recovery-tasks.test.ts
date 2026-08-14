import { describe, expect, it, vi } from 'vitest';

import {
  QualityRecoveryTaskClient,
  QualityRecoveryTaskMemoryState,
  clearQualityRecoveryTaskIdentityBoundary,
  qualityRecoveryTaskDeliveryText,
  qualityRecoveryTaskQueryOptions,
} from '../../src/domains/ingestion-quality';
import type { QualityRecoveryTask } from '../../src/domains/ingestion-quality';

const taskId = '019fe8a0-0000-7000-8000-000000000801';
const episodeId = '019fe8a0-0000-7000-8000-000000000802';
const task = Object.freeze({
  taskId, taskVersion: 1, episodeId, episodeGeneration: 1,
  sourceId: 'SRC-P0-CAMPUS-ACCESS-001',
  dependencyId: 'DEP-P0-CAMPUS-ACCESS-001',
  affectedRules: [{ ruleId: 'ACC-SAFE-001', ruleVersion: '1.0.0' }],
  ownerRef: 'source-owner:SRC-P0-CAMPUS-ACCESS-001', priority: 'P1',
  dueAt: '2026-08-11T00:00:00Z', status: 'open', watermark: 'opaque-watermark',
  trigger: { batchId: '019fe8a0-0000-7000-8000-000000000803',
    snapshotId: '019fe8a0-0000-7000-8000-000000000804',
    reasonCode: 'REQUIRED_MEMBER_FUSED' },
  currentEvidence: { qualityGateVersion: 'QG-1.0.0', qmdpVersion: 'QMDP-1.0.0',
    qshmVersion: 'QSHM-1.0.0' },
  taskDelivery: { target: 'public-task-platform', status: 'pending', attempt: 0,
    nextAttemptAt: null },
} as const satisfies QualityRecoveryTask);

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

describe('quality recovery task frontend boundary', () => {
  it('uses an independent source-owned no-store client and stable cursor', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(response({
      items: [task], size: 1, hasMore: true,
      nextCursor: { occurredAt: '2026-08-10T00:00:00Z', taskId },
    }));
    const page = await new QualityRecoveryTaskClient(request).list({
      sourceId: task.sourceId, status: 'open', size: 1,
    });

    expect(page.items[0]?.taskId).toBe(taskId);
    expect(request).toHaveBeenCalledWith(expect.stringContaining('sourceId=SRC-P0'),
      expect.objectContaining({ credentials: 'include', cache: 'no-store',
        referrerPolicy: 'no-referrer' }));
  });

  it('refuses an unscoped list before issuing a network request', async () => {
    const request = vi.fn<typeof fetch>();
    await expect(new QualityRecoveryTaskClient(request).list(
      { size: 20 } as never,
    )).rejects.toThrow('INGESTION_QUALITY_REQUEST_INVALID');
    expect(request).not.toHaveBeenCalled();
  });

  it('rejects unknown fields, hidden relay data, missing evidence and dishonest enums', async () => {
    for (const invalid of [
      { ...structuredClone(task), workItemKey: 'hidden' },
      { ...structuredClone(task), taskDelivery: { ...task.taskDelivery, receiptId: 'hidden' } },
      { ...structuredClone(task), affectedRules: [] },
      { ...structuredClone(task), taskDelivery: { ...task.taskDelivery, status: 'delivered' } },
    ]) {
      await expect(new QualityRecoveryTaskClient(
        vi.fn<typeof fetch>().mockResolvedValue(response(invalid)),
      ).detail(taskId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
    }
  });

  it('keeps business task and transport wording orthogonal and honest', () => {
    expect(qualityRecoveryTaskDeliveryText('pending')).toContain('待投递');
    expect(qualityRecoveryTaskDeliveryText('retrying')).toContain('重试中');
    expect(qualityRecoveryTaskDeliveryText('confirmed')).toBe('外部平台已确认投递');
    expect(qualityRecoveryTaskDeliveryText('failed')).toContain('投递失败');
    expect(qualityRecoveryTaskDeliveryText('confirmed')).not.toContain('已修复');
  });

  it('clears only volatile task objects and binds query keys to identity generation', () => {
    const memory = new QualityRecoveryTaskMemoryState();
    memory.acceptPage({ items: [task], size: 20, hasMore: false, nextCursor: null });
    memory.acceptDetail(task);
    const active = { value: new AbortController() };
    const page = { value: memory.page() };
    const detail = { value: memory.detail() };
    clearQualityRecoveryTaskIdentityBoundary(active, page, detail, memory, 'account-switch');
    expect(active.value).toBeUndefined();
    expect(page.value).toBeUndefined();
    expect(detail.value).toBeUndefined();
    expect(memory.toJSON()).toEqual({});

    const options = qualityRecoveryTaskQueryOptions({
      sessionPseudonym: 'session-a', sessionVersion: 7,
      policyVersion: 'RFP-1.0.0', capabilitySignature: 'available|available',
      sourceId: task.sourceId, status: 'open',
    }, async () => ({ items: [], size: 20, hasMore: false, nextCursor: null }));
    expect(options.queryKey[1]).toBe('quality-recovery-tasks');
    expect(options.retry).toBe(false);
  });
});
