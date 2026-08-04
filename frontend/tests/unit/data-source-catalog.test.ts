import { describe, expect, it, vi } from 'vitest';

import {
  DataSourceCatalogClient,
  DataSourceCatalogMemoryState,
  clearDataCatalogIdentityBoundary,
  dataCatalogQueryOptions,
  hasUsableDataCatalogAuthorization,
} from '../../src/domains/ingestion-quality';
import type { CatalogDetail } from '../../src/domains/ingestion-quality';

const catalogId = '019fc6b8-9400-7000-8000-000000000011';
const summary = {
  catalogId, catalogReleaseId: null, contractVersion: 'DCC-1.0.0', status: 'INVALID',
  aggregateVersion: 2, contentDigest: `sha256:${'a'.repeat(64)}`, evidenceSetDigest: null,
  validationFailures: [{ code: 'DCC_OWNER_MISSING', fieldPath: 'sources[0].ownerName' }],
  updatedAt: '2026-08-04T12:00:00Z', publishedAt: null,
} as const;
const detail = {
  ...summary,
  sources: [{
    sourceId: 'SRC-P0-CALENDAR-001', purpose: 'business-calendar-control', schemaVersion: 'BC-1.0.0',
    qualityGateVersion: 'QG-1.0.0', evidenceUri: 'evidence://pending/SRC-P0-CALENDAR-001',
    runtimeEvidenceClaim: 'NONE', metadata: {
      ownerDepartment: '校办', ownerName: '日历责任人', businessDefinition: '业务日历', businessKeys: ['localDate'],
      updateFrequency: 'daily', slo: '99% <= 4h', coverage: 'today-90..today+180', sensitivity: 'internal',
      reconciliation: 'daily', backfillWindowDays: 90, watermarkRequired: true,
      contractTests: ['provider', 'consumer'], consumerMode: 'rule-dependency',
    },
  }],
  dependencies: [{ sourceId: 'SRC-P0-CALENDAR-001', dependencyId: 'DEP-P0-CALENDAR-001', requirement: 'REQUIRED', operator: 'ALL_OF' }],
} as const;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('data source catalog frontend boundary', () => {
  it('accepts only exact privacy-bounded list/detail responses and uses no-store credentials', async () => {
    const request = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ items: [summary], page: 0, size: 20, hasMore: false }))
      .mockResolvedValueOnce(jsonResponse(detail));
    const client = new DataSourceCatalogClient(request);
    expect((await client.list(0, 20)).items).toHaveLength(1);
    expect((await client.detail(catalogId)).sources[0]?.metadata.ownerName).toBe('日历责任人');
    expect(request).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
      credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer',
    }));
  });

  it('rejects student rows, extra fields, malformed ids and external error text', async () => {
    const leaked = structuredClone(detail) as unknown as Record<string, unknown>;
    (leaked.sources as Array<Record<string, unknown>>)[0]!.studentRef = 'student-sensitive';
    const client = new DataSourceCatalogClient(vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(leaked)));
    await expect(client.detail(catalogId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
    await expect(client.detail(crypto.randomUUID())).rejects.toThrow('INGESTION_QUALITY_REQUEST_INVALID');
    const failed = new DataSourceCatalogClient(vi.fn<typeof fetch>().mockResolvedValue(
      new Response('upstream secret body', { status: 503 }),
    ));
    await expect(failed.list(0, 20)).rejects.toThrow('INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE');
  });

  it('clears response-bearing memory before identity changes and has no serialization surface', () => {
    const memory = new DataSourceCatalogMemoryState();
    memory.accept(detail as CatalogDetail);
    const active = { value: new AbortController() as AbortController | undefined };
    const page = { value: { items: [summary], page: 0, size: 20, hasMore: false } as any };
    const selected = { value: detail as CatalogDetail | undefined };
    clearDataCatalogIdentityBoundary(active, page, selected, memory);
    expect(active.value).toBeUndefined(); expect(page.value).toBeUndefined();
    expect(selected.value).toBeUndefined(); expect(memory.current()).toBeUndefined();
    expect(JSON.stringify(memory)).toBe('{}');
  });

  it('requires both current R6 capability surfaces and keeps details out of query keys', () => {
    expect(hasUsableDataCatalogAuthorization(true, 'ready', true, 'available|available')).toBe(true);
    for (const signature of ['missing|available', 'available|missing', 'unavailable|available']) {
      expect(hasUsableDataCatalogAuthorization(true, 'ready', true, signature)).toBe(false);
    }
    const options = dataCatalogQueryOptions({ sessionVersion: 7, policyVersion: 'RFP-1.0.0', page: 0 }, async () => ({
      items: [], page: 0, size: 20, hasMore: false,
    }));
    expect(JSON.stringify(options.queryKey)).not.toContain('evidence');
    expect(options.staleTime).toBe(0); expect(options.gcTime).toBe(0);
  });
});
