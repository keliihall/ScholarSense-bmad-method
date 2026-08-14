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
});
