import { describe, expect, it, vi } from 'vitest';

import {
  AuditSearchClient,
  AuditSearchMemoryState,
  auditSearchColumns,
  auditSearchQueryOptions,
  auditSearchValuePresentation,
  clearAuditSearchQueryBoundary,
  clearAuditSearchIdentityBoundary,
  hasUsableAuditSearchAuthorization,
  type AuditSearchRequest,
} from '../../src/domains/audit-operations';

const request: AuditSearchRequest = {
  view: 'business', actorRef: 'student-sensitive', objectRef: 'object-sensitive',
  page: 0, size: 25,
};

function responseWithItem(item: Record<string, unknown>) {
  return {
    items: [item], page: 0, size: 25, total: 1,
    asOfSequence: 42, sourceLedgerHead: 44, projectionWatermark: 42,
    dataCutoffAt: '2026-07-23T00:00:00Z', retentionScheduleVersion: 'RS-1.0.0',
    roleFieldPolicyVersion: 'RFP-1.0.0', projectionStatus: 'current',
  } as const;
}

describe('authorized audit search client', () => {
  it('requires a current ready or degraded shell before any sensitive search', () => {
    expect(hasUsableAuditSearchAuthorization(
      true, 'ready', true, 'available|available',
    )).toBe(true);
    expect(hasUsableAuditSearchAuthorization(
      true, 'degraded', true, 'available|available',
    )).toBe(true);

    for (const status of [
      'loading', 'surface-forbidden', 'authorization-unavailable',
    ] as const) {
      expect(hasUsableAuditSearchAuthorization(
        true, status, true, 'available|available',
      )).toBe(false);
    }
    expect(hasUsableAuditSearchAuthorization(
      false, 'ready', true, 'available|available',
    )).toBe(false);
    expect(hasUsableAuditSearchAuthorization(
      true, 'ready', false, 'available|available',
    )).toBe(false);
  });

  it('gets a CSRF proof and keeps sensitive filters in a JSON body', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [{ fields: { recordId: '019d2c7d-4000-7000-8000-000000000042', ledgerSequence: 42 } }],
      page: 0, size: 25, total: 1, asOfSequence: 42, sourceLedgerHead: 44,
      projectionWatermark: 42, dataCutoffAt: '2026-07-23T00:00:00Z',
      retentionScheduleVersion: 'RS-1.0.0', roleFieldPolicyVersion: 'RFP-1.0.0',
      projectionStatus: 'degraded',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const client = new AuditSearchClient(fetcher, async () => ({
      headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF',
    }));

    const result = await client.search(request);

    expect(result.projectionStatus).toBe('degraded');
    const [url, init] = fetcher.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/audit-records/search');
    expect(url).not.toContain('student-sensitive');
    expect(init.credentials).toBe('include');
    expect(init.headers).toMatchObject({ 'X-CSRF-TOKEN': 'abcdefghijklmnopqrstuvwxyzABCDEF' });
    expect(JSON.parse(String(init.body))).toMatchObject(request);
  });

  it('rejects unknown response fields instead of rendering hidden data', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [{ fields: { payload: 'secret' } }], page: 0, size: 25, total: 1,
      asOfSequence: 1, sourceLedgerHead: 1, projectionWatermark: 1,
      dataCutoffAt: '2026-07-23T00:00:00Z', retentionScheduleVersion: 'RS-1.0.0',
      roleFieldPolicyVersion: 'RFP-1.0.0', projectionStatus: 'current',
    }), { status: 200 }));
    const client = new AuditSearchClient(fetcher, async () => ({ headerName: 'X-CSRF-TOKEN', value: 'x'.repeat(32) }));
    await expect(client.search(request)).rejects.toThrow('AUDIT_SEARCH_RESPONSE_INVALID');
  });

  it('maps an unapproved server error code to the status default without reflecting its canary', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'AUDIT_SEARCH_CANARY_PLAINTEXT_DO_NOT_REFLECT',
    }), { status: 503 }));
    const client = new AuditSearchClient(fetcher, async () => ({
      headerName: 'X-CSRF-TOKEN', value: 'x'.repeat(32),
    }));

    const failure = await client.search(request).catch((error: unknown) => error);

    expect(failure).toEqual(new Error('AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE'));
    expect(String(failure)).not.toContain('CANARY_PLAINTEXT_DO_NOT_REFLECT');
  });

  it('rejects item siblings, wrong field types and fields outside the selected view', async () => {
    for (const [query, item] of [
      [request, { fields: { recordId: '019d2c7d-4000-7000-8000-000000000042' }, debug: 'leak' }],
      [request, { fields: { ledgerSequence: '42' } }],
      [{ ...request, view: 'technical' as const },
        { fields: { actorDisplayRef: '[MASKED-IDENTITY]' } }],
    ] as const) {
      const fetcher = vi.fn().mockResolvedValue(new Response(
        JSON.stringify(responseWithItem(item)), { status: 200 },
      ));
      const client = new AuditSearchClient(fetcher, async () => ({
        headerName: 'X-CSRF-TOKEN', value: 'x'.repeat(32),
      }));
      await expect(client.search(query)).rejects.toThrow('AUDIT_SEARCH_RESPONSE_INVALID');
    }
  });

  it('derives columns in the frozen approved order instead of response key order', () => {
    const columns = auditSearchColumns('business', [
      { fields: { traceId: '[MASKED-TECHNICAL]', ledgerSequence: 42, recordId: 'record-1' } },
      { fields: { occurredAt: '2026-07-23T00:00:00Z' } },
    ]);

    expect(columns).toEqual(['recordId', 'ledgerSequence', 'occurredAt', 'traceId']);
    expect(Object.isFrozen(columns)).toBe(true);
  });

  it('stores raw actor/object only in memory and clears them at identity boundaries', () => {
    const state = new AuditSearchMemoryState();
    state.setSensitiveFilters('student-sensitive', 'object-sensitive');
    expect(state.sensitiveFilters()).toEqual({ actorRef: 'student-sensitive', objectRef: 'object-sensitive' });

    for (const event of ['refresh', 'logout', 'account-switch', 'session-invalid'] as const) {
      state.clearSensitive(event);
      expect(state.sensitiveFilters()).toEqual({});
      state.setSensitiveFilters('student-sensitive', 'object-sensitive');
    }
    expect(JSON.stringify(state)).not.toContain('student-sensitive');
  });

  it('aborts and clears an old account response before reauthorization', () => {
    const controller = new AbortController();
    const active = { value: controller as AbortController | undefined };
    const result = { value: { items: [] } as unknown as Awaited<ReturnType<AuditSearchClient['search']>> | undefined };
    const asOfSequence = { value: 42 as number | undefined };

    clearAuditSearchIdentityBoundary(active, result, asOfSequence);

    expect(controller.signal.aborted).toBe(true);
    expect(active.value).toBeUndefined();
    expect(result.value).toBeUndefined();
    expect(asOfSequence.value).toBeUndefined();
  });

  it('uses a non-sensitive stable query key with immediate stale and garbage collection', () => {
    const options = auditSearchQueryOptions(
      { sessionVersion: 7, policyVersion: 'RFP-1.0.0', view: 'business' },
      async () => ({ items: [] }) as never,
    );

    expect(options.queryKey).toEqual([
      'audit-operations', 'sensitive-search',
      { sessionVersion: 7, policyVersion: 'RFP-1.0.0', view: 'business' },
    ]);
    expect(JSON.stringify(options.queryKey)).not.toContain('student-sensitive');
    expect(options.staleTime).toBe(0);
    expect(options.gcTime).toBe(0);
    expect(options.retry).toBe(false);

    const removeQueries = vi.fn();
    clearAuditSearchQueryBoundary({ removeQueries } as never);
    expect(removeQueries).toHaveBeenCalledWith({
      queryKey: ['audit-operations', 'sensitive-search'], exact: false,
    });
  });

  it('presents masked cells with one fixed visual token and one length-free accessible name', () => {
    expect(auditSearchValuePresentation('business', 'actorDisplayRef', 'raw-identity'))
      .toEqual({ masked: true, visual: '[MASKED-IDENTITY]', accessibleName: '已脱敏' });
    expect(auditSearchValuePresentation('business', 'actorDisplayRef', 'x'.repeat(4096)))
      .toEqual({ masked: true, visual: '[MASKED-IDENTITY]', accessibleName: '已脱敏' });
    expect(auditSearchValuePresentation('business', 'sourceNetworkRecorded', true))
      .toEqual({ masked: true, visual: 'false', accessibleName: '已脱敏' });
    expect(auditSearchValuePresentation('technical', 'businessActionCategory', 'raw-category'))
      .toEqual({ masked: true, visual: '[MASKED-CATEGORY]', accessibleName: '已脱敏' });
    expect(auditSearchValuePresentation('technical', 'producerModule', 'identity-access'))
      .toEqual({ masked: false, visual: 'identity-access' });
  });
});
