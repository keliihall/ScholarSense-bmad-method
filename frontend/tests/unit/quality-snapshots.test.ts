import { describe, expect, it, vi } from 'vitest';

import {
  QualitySnapshotClient,
  QualitySnapshotMemoryState,
  buildQualityMetricTrends,
  clearQualitySnapshotIdentityBoundary,
  hasUsableQualitySnapshotAuthorization,
  qualityMetricCategory,
  qualityMetricDisplayValue,
  qualitySnapshotQueryOptions,
  sameQualitySnapshotIdentityGeneration,
  shouldClearQualitySnapshotQueryCache,
} from '../../src/domains/ingestion-quality';
import type { QualitySnapshot } from '../../src/domains/ingestion-quality';

const snapshotId = '019fe570-0000-7000-8000-000000000601';
const batchId = '019fe570-0000-7000-8000-000000000602';
const lineageId = '019fe570-0000-7000-8000-000000000603';
const metric = Object.freeze({
  metricId: 'PRIMARY_KEY_COMPLETENESS_BP',
  formulaId: 'QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP', formulaVersion: '1.0.0',
  result: 'passed', applicable: true, numerator: 999, denominator: 1000,
  valueBasisPoints: 9990, unit: 'basis-point', operator: '>=',
  thresholdNumerator: 995, thresholdDenominator: 1000,
  boundary: 'inclusive', reasonCode: null,
} as const);
const snapshot = Object.freeze({
  snapshotId, batchId, sourceId: 'SRC-P0-CARD-001',
  assessedBatchStatus: 'quality-passed', overallResult: 'quality-passed',
  observationWindow: { startAt: '2026-08-01T00:00:00Z', endAt: '2026-08-10T00:00:00Z' },
  cutoffAt: '2026-08-10T00:00:00Z', evaluatedAt: '2026-08-10T00:02:00Z',
  watermark: 'src-p0-card-001@2026-08-10', metricResults: [metric],
  impactScopeCodes: ['PRIMARY_KEY_COMPLETENESS_BP'], sourceOwnerRef: 'CARD-OWNER',
  approvalRef: 'AUTH-2026-08-08-001', effectiveAt: '2026-08-09T00:00:00Z',
  retentionScheduleVersion: 'RS-1.0.0', qualityMetricDecisionProfileVersion: 'QMDP-1.0.0',
  qualityMetricDecisionProfileDigest: `sha256:${'1'.repeat(64)}`,
  qualityGateVersion: 'QG-1.0.0', qualityGateDigest: `sha256:${'2'.repeat(64)}`,
  canonicalizationProfile: 'SCHOLARSENSE-CANONICAL-JSON-1.0.0',
  manifestDigest: `sha256:${'3'.repeat(64)}`, sourceSchemaVersion: 'CARD-SLICE-1.0.0',
  sourceSchemaDigest: `sha256:${'4'.repeat(64)}`, immutableHash: `sha256:${'5'.repeat(64)}`,
  traceId: '00112233445566778899aabbccddeeff', lineageId, supersedesSnapshotId: null,
  aggregateVersion: 3,
} as const satisfies QualitySnapshot);

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

describe('quality snapshot frontend boundary', () => {
  it('uses explicit keyset filters and accepts only the exact no-store DTO', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(response({
      items: [snapshot], size: 1, hasMore: true,
      nextCursor: { evaluatedAt: snapshot.evaluatedAt, snapshotId },
    }));
    const client = new QualitySnapshotClient(request);

    const page = await client.list({
      sourceId: 'SRC-P0-CARD-001', overallResult: 'quality-passed',
      evaluatedFrom: '2026-08-01T08:00:00+08:00', evaluatedTo: '2026-08-11T08:00:00+08:00',
      sortField: 'evaluatedAt', sortDirection: 'asc',
      afterEvaluatedAt: '2026-08-09T08:00:00+08:00', afterSnapshotId: snapshotId, size: 1,
    });

    expect(page.items[0]?.snapshotId).toBe(snapshotId);
    expect(request).toHaveBeenCalledWith(expect.stringContaining('afterSnapshotId='),
      expect.objectContaining({ credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer' }));
    expect(request).toHaveBeenCalledWith(expect.stringContaining('sortField=evaluatedAt'), expect.anything());
    expect(request).toHaveBeenCalledWith(expect.stringContaining('sortDirection=asc'), expect.anything());
  });

  it('rejects extra student/evidence fields and external error bodies', async () => {
    const leaked = structuredClone(snapshot) as Record<string, unknown>;
    leaked.studentName = '不应出现';
    await expect(new QualitySnapshotClient(
      vi.fn<typeof fetch>().mockResolvedValue(response(leaked)),
    ).detail(snapshotId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');

    await expect(new QualitySnapshotClient(
      vi.fn<typeof fetch>().mockResolvedValue(new Response('upstream secret', { status: 503 })),
    ).detail(snapshotId)).rejects.toThrow('INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE');
  });

  it('keeps trends separated by formulaVersion and maps every metric to one FR-11 category', () => {
    const older: QualitySnapshot = {
      ...structuredClone(snapshot),
      snapshotId: '019fe570-0000-7000-8000-000000000604',
      evaluatedAt: '2026-08-09T00:02:00Z',
      metricResults: [{ ...metric, formulaVersion: '0.9.0' }],
    };
    const trends = buildQualityMetricTrends([snapshot, older]);

    expect(trends).toHaveLength(2);
    expect(trends.map((item) => item.formulaVersion).sort()).toEqual(['0.9.0', '1.0.0']);
    expect(qualityMetricCategory(metric.metricId)).toBe('completeness');
    expect(qualityMetricCategory('SOURCE_CONTINUITY_GATE')).toBe('continuity');
    expect(qualityMetricCategory('CORE_FIELD_COVERAGE_BP')).toBe('coverage');
    expect(qualityMetricCategory('REQUIRED_FIELD_VALIDITY_BP')).toBe('coverage');
    expect(qualityMetricCategory('FRESHNESS_WITHIN_SLO_BP')).toBe('freshness');
  });

  it('keeps applicable count and duration evidence visible in cards and trends', () => {
    const count = {
      ...metric, metricId: 'DUPLICATE_BUSINESS_KEY_COUNT',
      formulaId: 'QMDP-1.0.0/DUPLICATE_BUSINESS_KEY_COUNT',
      numerator: 7, denominator: 1, valueBasisPoints: null, unit: 'count' as const,
    };
    const duration = {
      ...metric, metricId: 'FRESHNESS_DURATION',
      formulaId: 'QMDP-1.0.0/FRESHNESS_DURATION',
      numerator: 3600, denominator: 1, valueBasisPoints: null, unit: 'millisecond' as const,
    };
    const current: QualitySnapshot = {
      ...structuredClone(snapshot), metricResults: [count, duration],
    };

    expect(qualityMetricDisplayValue(count)).toBe(7);
    expect(qualityMetricDisplayValue(duration)).toBe(3600);
    expect(buildQualityMetricTrends([current]).map((item) => item.points[0]?.value))
      .toEqual(expect.arrayContaining([7, 3600]));
  });

  it('clears all business objects at identity boundaries and has no serialization surface', () => {
    const memory = new QualitySnapshotMemoryState();
    memory.acceptPage({ items: [snapshot], size: 20, hasMore: false, nextCursor: null });
    memory.acceptDetail(snapshot);
    const active = { value: new AbortController() };
    const page = { value: memory.page() };
    const detail = { value: memory.detail() };
    clearQualitySnapshotIdentityBoundary(active, page, detail, memory, 'account-switch');

    expect(active.value).toBeUndefined();
    expect(page.value).toBeUndefined();
    expect(detail.value).toBeUndefined();
    expect(memory.toJSON()).toEqual({});
  });

  it('binds query keys and route capability to the current identity generation', () => {
    const generation = {
      sessionPseudonym: 'session-a', sessionVersion: 7,
      policyVersion: 'RFP-1.0.0' as const, capabilitySignature: 'available|available',
    };
    expect(sameQualitySnapshotIdentityGeneration(generation, { ...generation })).toBe(true);
    expect(hasUsableQualitySnapshotAuthorization(true, 'ready', true, 'available|available')).toBe(true);
    expect(hasUsableQualitySnapshotAuthorization(true, 'ready', true, 'available|missing')).toBe(false);
    const options = qualitySnapshotQueryOptions(
      { ...generation, sourceId: 'SRC-P0-CARD-001' }, async () => ({
      items: [], size: 20, hasMore: false, nextCursor: null,
      }));
    expect(options.queryKey[1]).toBe('quality-snapshots');
    expect(options.gcTime).toBe(5 * 60 * 1000);
    expect(shouldClearQualitySnapshotQueryCache('refresh')).toBe(false);
    expect(shouldClearQualitySnapshotQueryCache('logout')).toBe(true);
    expect(shouldClearQualitySnapshotQueryCache('authorization-revoked')).toBe(true);
  });
});
