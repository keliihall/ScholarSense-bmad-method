import { describe, expect, it, vi } from 'vitest';

import {
  QualityFuseRecoveryClient,
  QualityFuseRecoveryFailure,
  QualityFuseRecoveryMemory,
  qualityFuseRecoveryQueryOptions,
} from '../../src/domains/ingestion-quality';

const taskId = '019fe8a0-0000-7000-8000-000000000801';
const requestId = '019fe8a0-0000-7000-8000-000000000901';
const body = Object.freeze({
  recoveryRequestId: requestId, requestVersion: 2, taskId, status: 'validating',
  validationJobId: '019fe8a0-0000-7000-8000-000000000902', validationStatus: 'queued',
  validationResultDigest: null, previewId: null, previewVersion: null, previewDigest: null,
  previewExpiresAt: null, previewSummary: null,
  approvalId: null, approvalVersion: null, approvalStatus: null,
  traceId: '0123456789abcdef0123456789abcdef',
} as const);

function response(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

describe('quality fuse recovery command boundary', () => {
  it('adds fresh CSRF and idempotency evidence to a no-store command', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(response(body));
    const csrf = vi.fn().mockResolvedValue({ headerName: 'X-CSRF-TOKEN', value: 'opaque' });
    const value = await new QualityFuseRecoveryClient(request, csrf).create(
      taskId, 7, 'request-key', new AbortController().signal);

    expect(value.status).toBe('validating');
    expect(csrf).toHaveBeenCalledOnce();
    expect(request).toHaveBeenCalledWith(expect.stringContaining(taskId),
      expect.objectContaining({ method: 'POST', cache: 'no-store',
        credentials: 'include', referrerPolicy: 'no-referrer',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'opaque',
          'Idempotency-Key': 'request-key' }) }));
  });

  it('rejects unknown fields and never reflects an external error body', async () => {
    await expect(new QualityFuseRecoveryClient(
      vi.fn<typeof fetch>().mockResolvedValue(response({ ...body, receipt: 'secret' })),
      vi.fn().mockResolvedValue({ headerName: 'X-CSRF', value: 'v' }),
    ).create(taskId, 7, 'key')).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');

    await expect(new QualityFuseRecoveryClient(
      vi.fn<typeof fetch>().mockResolvedValue(response({ private: 'token-value' }, 503)),
      vi.fn().mockResolvedValue({ headerName: 'X-CSRF', value: 'v' }),
    ).create(taskId, 7, 'key')).rejects.not.toThrow('token-value');
  });

  it('preserves the safe currentVersion of a 409 but no response details', async () => {
    const failure = await new QualityFuseRecoveryClient(
      vi.fn<typeof fetch>().mockResolvedValue(response({
        code: 'INGESTION_QUALITY_VERSION_CONFLICT', currentVersion: 9,
        fieldErrors: [], traceId: '0123456789abcdef0123456789abcdef',
      }, 409)), vi.fn().mockResolvedValue({ headerName: 'X-CSRF', value: 'v' }),
    ).execute(requestId, 8, 'execute-key').catch((value: unknown) => value);
    expect(failure).toBeInstanceOf(QualityFuseRecoveryFailure);
    expect((failure as QualityFuseRecoveryFailure).currentVersion).toBe(9);
  });

  it('uses gcTime zero and keeps volatile memory non-serializable', () => {
    const options = qualityFuseRecoveryQueryOptions({ sessionVersion: 7,
      capability: 'available' }, async () => body);
    expect(options).toMatchObject({ staleTime: 0, gcTime: 0, retry: false,
      networkMode: 'online' });
    const memory = new QualityFuseRecoveryMemory();
    memory.accept(body);
    expect(memory.current()).toEqual(body);
    expect(JSON.stringify(memory)).toBe('{}');
    memory.clear();
    expect(memory.current()).toBeUndefined();
  });

  it('decodes P1D explicitly and sends only opaque final command fields', async () => {
    const observation = {
      recoveryId: requestId, recoveryVersion: 5, taskId, taskVersion: 2, generation: 1,
      sourceClass: 'daily-batch', policyVersion: 'QRP-1.0.0',
      policyDigest: `sha256:${'a'.repeat(64)}`, status: 'ready',
      finalizationState: 'approval-pending',
      approvalId: '019fe8a0-0000-7000-8000-000000000911', approvalVersion: 1,
      consecutivePassedBatches: 2,
      requiredPassedBatches: 2, observedDurationMicros: 86_400_000_000,
      requiredDurationMicros: 86_400_000_000, observationDuration: 'P1D',
      watermark: 'opaque-final-watermark', recoveringStartedAt: '2026-08-12T00:00:00Z',
      lastObservedAt: '2026-08-13T00:00:00Z', latestActionableAt: '2026-08-13T00:00:00Z',
      failedMembers: [], failureReasonCode: null,
      eligibilityStatus: 'recovering', taskStatus: 'open',
      taskClosedAt: null, ownerResultDigest: null, deliveryStatus: 'confirmed',
      deliveryAttempt: 1, deliveryNextAttemptAt: null,
      eligibleForHandoffWindowCount: 0, historyOnlyWindowCount: 0,
      traceId: '0123456789abcdef0123456789abcdef',
    } as const;
    const final = {
      recoveryId: requestId, recoveryVersion: 5, taskId, taskVersion: 2,
      observationStatus: 'ready', finalizationState: 'approval-pending',
      approvalId: '019fe8a0-0000-7000-8000-000000000911', approvalVersion: 1,
      policyVersion: 'QRP-1.0.0', finalPreviewDigest: `sha256:${'b'.repeat(64)}`,
      observationDecisionDigest: `sha256:${'c'.repeat(64)}`,
      finalObservationWatermark: 'opaque-final-watermark',
      traceId: '0123456789abcdef0123456789abcdef',
    } as const;
    const request = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(observation)).mockResolvedValueOnce(response(final));
    const client = new QualityFuseRecoveryClient(request,
      vi.fn().mockResolvedValue({ headerName: 'X-CSRF', value: 'v' }));

    await expect(client.observation(taskId)).resolves.toMatchObject({
      observationDuration: 'P1D', status: 'ready', approvalVersion: 1,
    });
    await client.requestFinalApproval(
      requestId, 5, 2, 'opaque-final-watermark', 'final-key');
    const command = request.mock.calls[1]?.[1];
    expect(JSON.parse(String(command?.body))).toEqual({
      expectedRecoveryVersion: 5, expectedTaskVersion: 2,
      finalObservationWatermark: 'opaque-final-watermark',
    });
    expect(String(command?.body)).not.toContain('approvalReceipt');
    expect(String(command?.body)).not.toContain('executionJti');
  });

  it('fails closed on an unknown duration instead of guessing 24 hours', async () => {
    const invalidObservation = {
      recoveryId: requestId, recoveryVersion: 5, taskId, taskVersion: 2, generation: 1,
      sourceClass: 'daily-batch', policyVersion: 'QRP-1.0.0',
      policyDigest: `sha256:${'a'.repeat(64)}`, status: 'observing',
      finalizationState: 'not-requested', approvalId: null, approvalVersion: null,
      consecutivePassedBatches: 1,
      requiredPassedBatches: 2, observedDurationMicros: 1, requiredDurationMicros: 2,
      observationDuration: 'PT23H', watermark: null,
      recoveringStartedAt: '2026-08-12T00:00:00Z',
      lastObservedAt: '2026-08-12T00:00:00Z', latestActionableAt: null,
      failedMembers: [], failureReasonCode: null,
      eligibilityStatus: 'recovering', taskStatus: 'open',
      taskClosedAt: null, ownerResultDigest: null, deliveryStatus: 'pending',
      deliveryAttempt: 0, deliveryNextAttemptAt: null,
      eligibleForHandoffWindowCount: 0, historyOnlyWindowCount: 0,
      traceId: '0123456789abcdef0123456789abcdef',
    };
    await expect(new QualityFuseRecoveryClient(
      vi.fn<typeof fetch>().mockResolvedValue(response(invalidObservation)),
    ).observation(taskId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
  });
});
