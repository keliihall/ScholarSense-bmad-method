import { describe, expect, it, vi } from 'vitest';

import {
  SubjectMappingClient,
  SubjectRegistryResponseFailure,
  SubjectRepairMemory,
  clearSubjectRegistryIdentityBoundary,
  hasUsableSubjectRegistryAuthorization,
  sameSubjectRegistryIdentityGeneration,
  subjectMappingQueryOptions,
} from '../../src/domains/subject-registry';
import type {
  SubjectMappingExceptionPage,
  SubjectRegistryIdentityGeneration,
} from '../../src/domains/subject-registry';

const exceptionId = '019fcfea-6500-7000-8000-000000000001';
const sourceStudentRef = '019fcfea-6500-7000-8000-000000000002';
const targetStudentRef = '019fcfea-6500-7000-8000-000000000003';
const jobId = '019fcfea-6500-7000-8000-000000000011';
const item = Object.freeze({
  exceptionId,
  status: 'open' as const,
  subjectOfficialRef: 'SYNTHETIC-001',
  exceptionCode: 'AMBIGUOUS',
  sourceSystem: 'SRC-P0-CARD-001',
  sourceOwner: '合成一卡通 owner',
  detectedAt: '2026-08-06T07:00:00Z',
});
const page = Object.freeze({ items: Object.freeze([item]), page: 0, size: 20, hasMore: false });

describe('subject registry frontend boundary', () => {
  it('accepts only the approved seven-field exception projection with no-store transport', async () => {
    const request = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) =>
      new Response(JSON.stringify(page), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }));
    const client = new SubjectMappingClient(request as typeof fetch);

    await expect(client.list(0, 20, 'open')).resolves.toEqual(page);
    expect(request).toHaveBeenCalledWith(
      '/api/v1/subject-mapping-exceptions?page=0&size=20&status=open',
      expect.objectContaining({ credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer' }),
    );

    const leaked = { ...page, items: [{ ...item, evidenceBody: 'forbidden' }] };
    const invalid = new SubjectMappingClient(vi.fn(async () =>
      new Response(JSON.stringify(leaked), { status: 200 })) as typeof fetch);
    await expect(invalid.list(0, 20)).rejects.toThrow('SUBJECT_REGISTRY_RESPONSE_INVALID');
  });

  it('keeps aggregate version out of the seven-field body and reads it from the detail ETag', async () => {
    const request = vi.fn(async () => new Response(JSON.stringify(item), {
      status: 200,
      headers: { ETag: '"7"', 'Content-Type': 'application/json' },
    }));
    const detail = await new SubjectMappingClient(request as typeof fetch).detail(exceptionId);

    expect(detail).toEqual({ item, aggregateVersion: 7 });
    expect(Object.keys(detail.item).sort()).toEqual([
      'detectedAt', 'exceptionCode', 'exceptionId', 'sourceOwner',
      'sourceSystem', 'status', 'subjectOfficialRef',
    ]);
  });

  it('preserves the repair draft on 409 but discards its stale idempotency proof', async () => {
    const request = vi.fn(async () => new Response(JSON.stringify({
      code: 'SUBJECT_REGISTRY_VERSION_CONFLICT',
      message: 'Request could not be completed',
      traceId: '00112233445566778899aabbccddeeff',
      fieldErrors: [],
      currentAggregateVersion: 8,
    }), { status: 409, headers: { 'Content-Type': 'application/json' } }));
    const client = new SubjectMappingClient(
      request as typeof fetch,
      async () => ({ headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF' }),
    );
    const memory = new SubjectRepairMemory();
    const draft = Object.freeze({
      reasonCode: 'SUBJECT_MERGED' as const,
      sourceWatermark: 'wm-synthetic-42',
      relationType: 'merged-into' as const,
      sourceStudentRef,
      targetStudentRefs: Object.freeze([targetStudentRef]),
    });
    const first = memory.prepare(exceptionId, 7, draft, () => 'idem-subject-repair-0001');

    let failure: unknown;
    try {
      await client.repair(first, undefined);
    } catch (caught) {
      failure = caught;
    }
    expect(failure).toBeInstanceOf(SubjectRegistryResponseFailure);
    expect((failure as SubjectRegistryResponseFailure).currentAggregateVersion).toBe(8);
    memory.conflict(first);
    expect(memory.currentDraft()).toEqual(draft);
    expect(memory.currentCommand()).toBeUndefined();
    expect(memory.prepare(exceptionId, 8, draft, () => 'idem-subject-repair-0002').idempotencyKey)
      .toBe('idem-subject-repair-0002');
  });

  it('validates the approved job projection and rejects hidden detail', async () => {
    const valid = {
      jobId, status: 'running', attemptNo: 2, queuedAt: '2026-08-06T08:00:00Z',
      completedAt: null, resultCode: null, traceId: '00112233445566778899aabbccddeeff',
    };
    const client = new SubjectMappingClient(vi.fn(async () =>
      new Response(JSON.stringify(valid), { status: 200 })) as typeof fetch);
    await expect(client.job(jobId)).resolves.toEqual(valid);

    const leaked = new SubjectMappingClient(vi.fn(async () =>
      new Response(JSON.stringify({ ...valid, studentRef: sourceStudentRef }), { status: 200 })) as typeof fetch);
    await expect(leaked.job(jobId)).rejects.toThrow('SUBJECT_REGISTRY_RESPONSE_INVALID');
  });

  it('aborts work and clears all plaintext and retry proof at every identity boundary', () => {
    const active = { value: new AbortController() };
    const response = { value: page as SubjectMappingExceptionPage | undefined };
    const detail = { value: { item, aggregateVersion: 7 } };
    const memory = new SubjectRepairMemory();
    memory.prepare(exceptionId, 7, {
      reasonCode: 'AUTHORITY_CORRECTION', sourceWatermark: 'wm-1', relationType: 'alias',
      sourceStudentRef, targetStudentRefs: [targetStudentRef],
    }, () => 'idem-subject-repair-0003');
    const signal = active.value.signal;

    clearSubjectRegistryIdentityBoundary(active, response, detail, memory, 'account-switch');

    expect(signal.aborted).toBe(true);
    expect(response.value).toBeUndefined();
    expect(detail.value).toBeUndefined();
    expect(memory.currentDraft()).toBeUndefined();
    expect(memory.toJSON()).toEqual({});
  });

  it('binds queries and responses to the exact identity and policy generation', () => {
    const generation: SubjectRegistryIdentityGeneration = {
      sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw', sessionVersion: 7,
      policyVersion: 'RFP-1.0.0', capabilitySignature: 'available|available',
    };
    expect(sameSubjectRegistryIdentityGeneration(generation, { ...generation })).toBe(true);
    expect(sameSubjectRegistryIdentityGeneration(generation, { ...generation, sessionVersion: 8 })).toBe(false);
    expect(hasUsableSubjectRegistryAuthorization(true, 'ready', true, 'available|available')).toBe(true);
    expect(hasUsableSubjectRegistryAuthorization(true, 'ready', true, 'available|missing')).toBe(false);
    expect(subjectMappingQueryOptions({ sessionVersion: 7, policyVersion: 'RFP-1.0.0', page: 0 },
      async () => page)).toMatchObject({ staleTime: 0, gcTime: 0, retry: false, networkMode: 'online' });
  });
});
