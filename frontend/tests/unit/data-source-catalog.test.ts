import { describe, expect, it, vi } from 'vitest';

import {
  DataSourceCatalogClient,
  DataSourceCatalogMemoryState,
  DataSourceCatalogPublicationMemory,
  DataCatalogResponseFailure,
  FROZEN_DATA_DEPENDENCIES,
  FROZEN_DATA_SOURCE_IDS,
  clearDataCatalogIdentityBoundary,
  dataCatalogQueryOptions,
  hasUsableDataCatalogAuthorization,
  retainPublicationProof,
  sameDataCatalogIdentityGeneration,
} from '../../src/domains/ingestion-quality';
import type { CatalogDetail } from '../../src/domains/ingestion-quality';

const catalogId = '019fc6b8-9400-7000-8000-000000000011';
const summary = {
  catalogId, catalogReleaseId: null, contractVersion: 'DCC-1.0.0', status: 'INVALID',
  aggregateVersion: 2, currentPointerVersion: 0,
  contentDigest: `sha256:${'a'.repeat(64)}`, evidenceSetDigest: null,
  validationFailures: [{ code: 'DCC_OWNER_MISSING', fieldPath: 'sources[0].ownerName' }],
  updatedAt: '2026-08-04T12:00:00Z', publishedAt: null,
} as const;
const sources = FROZEN_DATA_SOURCE_IDS.map((sourceId, index) => {
  const ordinal = String(index + 1).padStart(3, '0');
  return {
    sourceId, purpose: index === 8 ? 'business-calendar-control' : `bounded-source-${ordinal}`,
    schemaVersion: index === 8 ? 'BC-1.0.0' : 'SLICE-1.0.0', qualityGateVersion: 'QG-1.0.0',
    evidenceUri: `evidence://pending/${sourceId}`, runtimeEvidenceClaim: 'NONE', metadata: {
      ownerDepartment: '校办', ownerName: '日历责任人', responsibleRole: '校历业务负责人',
      businessDefinition: '业务日历', businessKeys: ['localDate'], effectiveInterval: 'half-open-utc',
      updateFrequency: 'daily', slo: '99% <= 4h', coverage: 'today-90..today+180', sensitivity: 'internal',
      schemaRef: 'contracts/data-catalog/sources/business-calendar.schema.json', reconciliation: 'daily',
      reconciliationMinimumBasisPoints: 9950, backfillWindowDays: 90, watermarkRequired: true,
      contractTests: ['provider', 'consumer'], consumerMode: 'rule-dependency', status: 'approved-contract',
    },
  };
});
const dependencies = Object.entries(FROZEN_DATA_DEPENDENCIES).map(([sourceId, dependencyId]) => ({
  sourceId,
  dependencyId,
  requirement: 'REQUIRED', operator: 'ALL_OF',
}));
const detail = { ...summary, sources, dependencies } as const;

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

  it('rejects a list response whose server page identity does not match the request', async () => {
    const client = new DataSourceCatalogClient(vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ items: [summary], page: 0, size: 20, hasMore: false }),
    ));

    await expect(client.list(1, 20)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
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

  it('rejects partial catalog details instead of presenting an incomplete control baseline', async () => {
    const partial = structuredClone(detail);
    partial.sources.pop();
    const client = new DataSourceCatalogClient(vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(partial)));

    await expect(client.detail(catalogId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
  });

  it('rejects a valid-looking replacement source or a swapped frozen dependency mapping', async () => {
    const replaced = structuredClone(detail) as any;
    replaced.sources[16].sourceId = 'SRC-P1-SYNTHETIC-017';
    const replacedClient = new DataSourceCatalogClient(
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(replaced)),
    );
    await expect(replacedClient.detail(catalogId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');

    const swapped = structuredClone(detail) as any;
    const firstDependency = swapped.dependencies[0].dependencyId;
    swapped.dependencies[0].dependencyId = swapped.dependencies[1].dependencyId;
    swapped.dependencies[1].dependencyId = firstDependency;
    const swappedClient = new DataSourceCatalogClient(
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(swapped)),
    );
    await expect(swappedClient.detail(catalogId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
  });

  it('binds pending and immutable evidence URIs to the declared source claim', async () => {
    const external = structuredClone(detail) as any;
    external.sources[0].evidenceUri = 'https://example.invalid/mutable-evidence';
    await expect(new DataSourceCatalogClient(
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(external)),
    ).detail(catalogId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');

    const falseClaim = structuredClone(detail) as any;
    falseClaim.sources[0].runtimeEvidenceClaim = 'TARGET_VERIFIED';
    await expect(new DataSourceCatalogClient(
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(falseClaim)),
    ).detail(catalogId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');

    const wrongSource = structuredClone(detail) as any;
    wrongSource.sources[0].runtimeEvidenceClaim = 'TARGET_VERIFIED';
    wrongSource.sources[0].evidenceUri = `evidence+sha256://${'a'.repeat(64)}#source=SRC-P0-CALENDAR-001`;
    await expect(new DataSourceCatalogClient(
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(wrongSource)),
    ).detail(catalogId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
  });

  it('rejects sparse hasMore pages and contradictory published state fields', async () => {
    const sparse = new DataSourceCatalogClient(vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ items: [summary], page: 0, size: 20, hasMore: true }),
    ));
    await expect(sparse.list(0, 20)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');

    const contradictory = { ...summary, status: 'PUBLISHED' };
    const published = new DataSourceCatalogClient(vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse(contradictory),
    ));
    await expect(published.list(0, 20)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
  });

  it('classifies a catalog-release conflict as deterministic so retry proof is discarded', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      code: 'INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT',
      traceId: 'a'.repeat(32),
      fieldErrors: [],
    }, 409));
    const client = new DataSourceCatalogClient(request, async () => ({
      headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF',
    }));

    const failure = await client.publish(
      catalogId,
      2,
      0,
      '019fc6b8-9400-7000-8000-000000000099',
      'publication-attempt-1',
    ).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(DataCatalogResponseFailure);
    expect((failure as Error).message).toBe('INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT');
    expect(retainPublicationProof(failure)).toBe(false);
    expect(retainPublicationProof(new TypeError('connection reset'))).toBe(true);
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

  it('retains one logical publication proof across retry and forgets it only after completion', () => {
    const publications = new DataSourceCatalogPublicationMemory();
    const input = {
      catalogId,
      expectedVersion: 2,
      expectedCurrentVersion: 0,
    } as const;
    const first = publications.prepare(
      input,
      () => '019fc6b8-9400-7000-8000-000000000099',
      () => 'publication-attempt-1',
    );
    const retry = publications.prepare(
      input,
      () => { throw new Error('release id must not rotate'); },
      () => { throw new Error('idempotency key must not rotate'); },
    );

    expect(retry).toBe(first);
    expect(retry).toMatchObject({
      catalogReleaseId: '019fc6b8-9400-7000-8000-000000000099',
      idempotencyKey: 'publication-attempt-1',
    });
    publications.complete(retry);
    expect(publications.current()).toBeUndefined();

    const next = publications.prepare(
      input,
      () => '019fc6b8-9400-7000-8000-000000000100',
      () => 'publication-attempt-2',
    );
    expect(next.catalogReleaseId).not.toBe(first.catalogReleaseId);
    expect(next.idempotencyKey).not.toBe(first.idempotencyKey);
  });

  it.each(['logout', 'account-switch', 'authorization-revoked'] as const)(
    'clears pending publication state on %s without exposing a serialization surface',
    (reason) => {
      const publications = new DataSourceCatalogPublicationMemory();
      publications.prepare(
        { catalogId, expectedVersion: 2, expectedCurrentVersion: 0 },
        () => '019fc6b8-9400-7000-8000-000000000099',
        () => 'publication-attempt-1',
      );

      publications.clear(reason);

      expect(publications.current()).toBeUndefined();
      expect(JSON.stringify(publications)).toBe('{}');
    },
  );

  it('rejects stale list, detail and command generations even when numeric versions collide', () => {
    const requested = {
      sessionPseudonym: 'sp_account_one_1234567890',
      sessionVersion: 7,
      policyVersion: 'RFP-1.0.0',
      capabilitySignature: 'available|available',
    } as const;

    expect(sameDataCatalogIdentityGeneration(requested, { ...requested })).toBe(true);
    expect(sameDataCatalogIdentityGeneration(requested, {
      ...requested,
      sessionPseudonym: 'sp_account_two_1234567890',
    })).toBe(false);
    expect(sameDataCatalogIdentityGeneration(requested, {
      ...requested,
      capabilitySignature: 'missing|missing',
    })).toBe(false);
    expect(sameDataCatalogIdentityGeneration(requested, undefined)).toBe(false);
  });

  it('sends only server-clocked validation and server-evidenced publication fields', async () => {
    const request = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(summary))
      .mockResolvedValueOnce(jsonResponse(summary));
    const client = new DataSourceCatalogClient(request, async () => ({
      headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF',
    }));

    await client.validate(catalogId, 2);
    await client.publish(
      catalogId,
      2,
      0,
      '019fc6b8-9400-7000-8000-000000000099',
      'publication-attempt-1',
    );

    expect(JSON.parse(String(request.mock.calls[0]?.[1]?.body))).toEqual({ expectedVersion: 2 });
    expect(JSON.parse(String(request.mock.calls[1]?.[1]?.body))).toEqual({
      expectedVersion: 2,
      expectedCurrentVersion: 0,
      catalogReleaseId: '019fc6b8-9400-7000-8000-000000000099',
    });
  });
});
