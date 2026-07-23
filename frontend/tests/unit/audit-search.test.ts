import { describe, expect, it, vi } from 'vitest';

import {
  AuditSearchClient,
  AuditSearchMemoryState,
  clearAuditSearchIdentityBoundary,
  type AuditSearchRequest,
} from '../../src/domains/audit-operations';

const request: AuditSearchRequest = {
  view: 'business', actorRef: 'student-sensitive', objectRef: 'object-sensitive',
  page: 0, size: 25,
};

describe('authorized audit search client', () => {
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
});
