import { describe, expect, it, vi } from 'vitest';

import {
  QualityEligibilityClient,
  QualityEligibilityMemoryState,
  clearQualityEligibilityIdentityBoundary,
  qualityEligibilityQueryOptions,
  qualityEligibilityStatusText,
  shouldClearQualityEligibilityQueryCache,
} from '../../src/domains/ingestion-quality';
import type { QualityEligibility } from '../../src/domains/ingestion-quality';

const eligibilityId = '019fe570-0000-7000-8000-000000000901';
const snapshotId = '019fe570-0000-7000-8000-000000000902';
const eligibility = Object.freeze({
  eligibilityId, ruleId: 'ACC-SAFE-001', ruleVersion: '1.0.0', status: 'fused',
  reasonCode: 'REQUIRED_MEMBER_FUSED', operator: 'all-of', threshold: null,
  members: [{
    sourceId: 'SRC-P0-DORM-ACCESS-001', sourceVersion: 12,
    dependencyId: 'DEP-P0-DORM-ACCESS-001', dependencyVersion: 12,
    requirement: 'required', state: 'fused', versionContinuous: true,
    sourceWatermark: 'opaque-source-watermark',
    dependencyWatermark: 'opaque-dependency-watermark', snapshotId,
    snapshotImmutableHash: `sha256:${'1'.repeat(64)}`,
  }],
  failedMembers: ['DEP-P0-DORM-ACCESS-001'],
  registryVersion: 'RULE-DEPENDENCY-REGISTRY-1.0.0', aggregateVersion: 1,
  effectiveAt: '2026-08-10T00:00:00Z', occurredAt: '2026-08-10T00:01:00Z',
} as const satisfies QualityEligibility);

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

describe('quality eligibility frontend boundary', () => {
  it('uses an independent exact no-store DTO and bounded keyset filters', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(response({
      items: [eligibility], size: 1, hasMore: true,
      nextCursor: { occurredAt: eligibility.occurredAt, eligibilityId },
    }));
    const client = new QualityEligibilityClient(request);

    const page = await client.list({
      status: 'fused', ruleId: 'ACC-SAFE-001',
      afterOccurredAt: '2026-08-09T00:00:00Z', afterEligibilityId: eligibilityId, size: 1,
    });

    expect(page.items[0]?.eligibilityId).toBe(eligibilityId);
    expect(request).toHaveBeenCalledWith(expect.stringContaining('status=fused'),
      expect.objectContaining({ credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer' }));
    expect(request).toHaveBeenCalledWith(expect.stringContaining('afterEligibilityId='), expect.anything());
  });

  it('rejects leaked, partial, inconsistent and fake eligibility projections', async () => {
    const leaked = { ...structuredClone(eligibility), studentId: 'forbidden' };
    await expect(new QualityEligibilityClient(
      vi.fn<typeof fetch>().mockResolvedValue(response(leaked)),
    ).detail(eligibilityId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');

    const fake = structuredClone(eligibility) as unknown as Record<string, unknown>;
    fake.status = 'eligible';
    await expect(new QualityEligibilityClient(
      vi.fn<typeof fetch>().mockResolvedValue(response(fake)),
    ).detail(eligibilityId)).rejects.toThrow('INGESTION_QUALITY_RESPONSE_INVALID');
  });

  it('keeps status language distinct from snapshot results and technical errors', () => {
    expect(qualityEligibilityStatusText('eligible')).toContain('Eligible');
    expect(qualityEligibilityStatusText('fused')).toContain('Fused');
    expect(qualityEligibilityStatusText('recovering')).toContain('Recovering');
    expect(qualityEligibilityStatusText('missing')).toContain('Missing');
    expect(qualityEligibilityStatusText('fused')).not.toContain('技术错误');
  });

  it('clears volatile objects at identity boundaries and never serializes them', () => {
    const memory = new QualityEligibilityMemoryState();
    memory.acceptPage({ items: [eligibility], size: 20, hasMore: false, nextCursor: null });
    memory.acceptDetail(eligibility);
    const active = { value: new AbortController() };
    const page = { value: memory.page() };
    const detail = { value: memory.detail() };

    clearQualityEligibilityIdentityBoundary(active, page, detail, memory, 'account-switch');

    expect(active.value).toBeUndefined();
    expect(page.value).toBeUndefined();
    expect(detail.value).toBeUndefined();
    expect(memory.toJSON()).toEqual({});
  });

  it('binds cache keys to identity generation and preserves successful refresh cache', () => {
    const options = qualityEligibilityQueryOptions({
      sessionPseudonym: 'session-a', sessionVersion: 7,
      policyVersion: 'RFP-1.0.0', capabilitySignature: 'available|available',
      status: 'fused', ruleId: 'ACC-SAFE-001',
    }, async () => ({ items: [], size: 20, hasMore: false, nextCursor: null }));
    expect(options.queryKey[1]).toBe('quality-eligibilities');
    expect(options.gcTime).toBe(5 * 60 * 1000);
    expect(shouldClearQualityEligibilityQueryCache('refresh')).toBe(false);
    expect(shouldClearQualityEligibilityQueryCache('authorization-revoked')).toBe(true);
  });
});
