import type { AuthorizedShellStatus } from '../../identity-access';

export type QualityMetricResult = Readonly<{
  metricId: string;
  formulaId: string;
  formulaVersion: string;
  result: 'passed' | 'failed' | 'not-applicable';
  applicable: boolean;
  numerator: number;
  denominator: number;
  valueBasisPoints: number | null;
  unit: 'basis-point' | 'count' | 'millisecond' | 'member-count';
  operator: '>=' | '<=' | '=';
  thresholdNumerator: number;
  thresholdDenominator: number;
  boundary: 'inclusive';
  reasonCode: null;
}>;

export type QualitySnapshot = Readonly<{
  snapshotId: string;
  batchId: string;
  sourceId: string;
  assessedBatchStatus: 'quality-passed' | 'quality-failed';
  overallResult: 'quality-passed' | 'quality-failed';
  observationWindow: Readonly<{ startAt: string; endAt: string }>;
  cutoffAt: string;
  evaluatedAt: string;
  watermark: string;
  metricResults: readonly QualityMetricResult[];
  impactScopeCodes: readonly string[];
  sourceOwnerRef: string;
  approvalRef: 'AUTH-2026-08-08-001';
  effectiveAt: string;
  retentionScheduleVersion: 'RS-1.0.0';
  qualityMetricDecisionProfileVersion: 'QMDP-1.0.0';
  qualityMetricDecisionProfileDigest: string;
  qualityGateVersion: 'QG-1.0.0';
  qualityGateDigest: string;
  canonicalizationProfile: 'SCHOLARSENSE-CANONICAL-JSON-1.0.0';
  manifestDigest: string;
  sourceSchemaVersion: string;
  sourceSchemaDigest: string;
  immutableHash: string;
  traceId: string;
  lineageId: string;
  supersedesSnapshotId: string | null;
  aggregateVersion: number;
}>;

export type QualitySnapshotCursor = Readonly<{
  evaluatedAt: string;
  snapshotId: string;
}>;

export type QualitySnapshotPage = Readonly<{
  items: readonly QualitySnapshot[];
  size: number;
  hasMore: boolean;
  nextCursor: QualitySnapshotCursor | null;
}>;

export type QualitySnapshotFilters = Readonly<{
  sourceId?: string;
  overallResult?: 'quality-passed' | 'quality-failed';
  evaluatedFrom?: string;
  evaluatedTo?: string;
  sortField: 'evaluatedAt';
  sortDirection: 'asc' | 'desc';
  afterEvaluatedAt?: string;
  afterSnapshotId?: string;
  size: number;
}>;

export type QualitySnapshotIdentityGeneration = Readonly<{
  sessionPseudonym: string;
  sessionVersion: number;
  policyVersion: 'RFP-1.0.0';
  capabilitySignature: string;
}>;

export type QualitySnapshotClearReason =
  | 'refresh'
  | 'logout'
  | 'account-switch'
  | 'session-invalid'
  | 'authorization-revoked'
  | 'offline'
  | 'narrow-screen';

export type QualityMetricCategory = 'completeness' | 'continuity' | 'coverage' | 'freshness';
export type QualityMetricTrend = Readonly<{
  metricId: string;
  formulaId: string;
  formulaVersion: string;
  category: QualityMetricCategory;
  points: readonly Readonly<{
    snapshotId: string;
    evaluatedAt: string;
    value: number | null;
    result: QualityMetricResult['result'];
    unit: QualityMetricResult['unit'];
  }>[];
}>;

const uuidV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const sourceId = /^SRC-P[01]-[A-Z-]+-[0-9]{3}$/;
const metricId = /^[A-Z][A-Z0-9_]{1,127}$/;
const formulaId = /^QMDP-1\.0\.0\/[A-Za-z0-9._/-]{1,245}$/;
const formulaVersion = /^[0-9]+\.[0-9]+\.[0-9]+$/;
const digest = /^sha256:[0-9a-f]{64}$/;
const traceId = /^(?!0{32}$)[0-9a-f]{32}$/;
const allowedFailures = new Set([
  'INGESTION_QUALITY_FORBIDDEN',
  'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE',
  'INGESTION_QUALITY_REQUEST_INVALID',
  'INGESTION_QUALITY_PAGE_INVALID',
]);

export class QualitySnapshotClient {
  public constructor(private readonly request: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  public async list(filters: QualitySnapshotFilters, signal?: AbortSignal): Promise<QualitySnapshotPage> {
    requireFilters(filters);
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined) query.set(key, String(value));
    }
    return this.response(`/api/v1/quality-snapshots?${query.toString()}`, { signal }, isPage);
  }

  public async detail(snapshotIdValue: string, signal?: AbortSignal): Promise<QualitySnapshot> {
    if (!uuidV7.test(snapshotIdValue)) throw invalidRequest();
    return this.response(`/api/v1/quality-snapshots/${snapshotIdValue}`, { signal }, isSnapshot);
  }

  public async metric(
    snapshotIdValue: string, metricIdValue: string, formulaIdValue: string,
    formulaVersionValue: string, signal?: AbortSignal,
  ): Promise<QualityMetricResult> {
    if (!uuidV7.test(snapshotIdValue) || !metricId.test(metricIdValue)
      || !formulaId.test(formulaIdValue) || !formulaVersion.test(formulaVersionValue)) {
      throw invalidRequest();
    }
    const query = new URLSearchParams({
      formulaId: formulaIdValue, formulaVersion: formulaVersionValue,
    });
    return this.response(
      `/api/v1/quality-snapshots/${snapshotIdValue}/metrics/${metricIdValue}?${query.toString()}`,
      { signal }, isMetric,
    );
  }

  private async response<T>(
    input: string, init: RequestInit, validate: (value: unknown) => value is T,
  ): Promise<T> {
    const response = await this.request(input, {
      credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json' }, ...init,
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!validate(value)) throw new Error('INGESTION_QUALITY_RESPONSE_INVALID');
    return deepFreeze(value);
  }
}

export class QualitySnapshotMemoryState {
  #page?: QualitySnapshotPage;
  #detail?: QualitySnapshot;

  public acceptPage(value: QualitySnapshotPage): void { this.#page = value; }
  public acceptDetail(value: QualitySnapshot): void { this.#detail = value; }
  public page(): QualitySnapshotPage | undefined { return this.#page; }
  public detail(): QualitySnapshot | undefined { return this.#detail; }
  public clear(_reason: QualitySnapshotClearReason): void {
    this.#page = undefined;
    this.#detail = undefined;
  }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

type Mutable<T> = { value: T };
export function clearQualitySnapshotIdentityBoundary(
  active: Mutable<AbortController | undefined>,
  page: Mutable<QualitySnapshotPage | undefined>,
  detail: Mutable<QualitySnapshot | undefined>,
  memory: QualitySnapshotMemoryState,
  reason: QualitySnapshotClearReason = 'authorization-revoked',
): void {
  active.value?.abort();
  active.value = undefined;
  page.value = undefined;
  detail.value = undefined;
  memory.clear(reason);
}

export function shouldClearQualitySnapshotQueryCache(
  reason: QualitySnapshotClearReason,
): boolean {
  return reason !== 'refresh';
}

export function sameQualitySnapshotIdentityGeneration(
  requested: QualitySnapshotIdentityGeneration,
  current: QualitySnapshotIdentityGeneration | undefined,
): boolean {
  return current !== undefined
    && requested.sessionPseudonym === current.sessionPseudonym
    && requested.sessionVersion === current.sessionVersion
    && requested.policyVersion === current.policyVersion
    && requested.capabilitySignature === current.capabilitySignature;
}

export function hasUsableQualitySnapshotAuthorization(
  authenticated: boolean, status: AuthorizedShellStatus, hasShell: boolean, signature: string,
): boolean {
  return authenticated && (status === 'ready' || status === 'degraded') && hasShell
    && signature === 'available|available';
}

export function qualitySnapshotQueryOptions(
  context: QualitySnapshotIdentityGeneration & Readonly<Record<string, string | number | undefined>>,
  queryFn: () => Promise<QualitySnapshotPage>,
) {
  return Object.freeze({
    queryKey: Object.freeze([
      'ingestion-quality', 'quality-snapshots', Object.freeze({ ...context }),
    ] as const),
    queryFn, staleTime: 0, gcTime: 5 * 60 * 1000,
    retry: false, networkMode: 'online' as const,
  });
}

export function qualityMetricCategory(value: string): QualityMetricCategory {
  if (value === 'FRESHNESS_WITHIN_SLO_BP') return 'freshness';
  if (value.includes('CONTINUITY') || value.includes('VERSION_REGRESSION')
      || value.includes('DUPLICATE') || value.includes('INTERVAL_CONFLICT')) return 'continuity';
  if (value.includes('COVERAGE') || value.includes('VALID_RECORD')
      || value.includes('REQUIRED_FIELD_VALIDITY')
      || value.includes('SCHEMA_ALLOWLIST') || value.includes('FORBIDDEN_FIELD')) return 'coverage';
  return 'completeness';
}

export function qualityMetricDisplayValue(metric: QualityMetricResult): number | null {
  if (!metric.applicable) return null;
  return metric.unit === 'basis-point' ? metric.valueBasisPoints : metric.numerator;
}

export function buildQualityMetricTrends(
  snapshots: readonly QualitySnapshot[],
): readonly QualityMetricTrend[] {
  const groups = new Map<string, {
    metricId: string; formulaId: string; formulaVersion: string;
    category: QualityMetricCategory; points: QualityMetricTrend['points'][number][];
  }>();
  for (const snapshot of snapshots) {
    for (const metric of snapshot.metricResults) {
      const key = `${metric.metricId}\u0000${metric.formulaId}\u0000${metric.formulaVersion}`;
      let group = groups.get(key);
      if (group === undefined) {
        group = {
          metricId: metric.metricId, formulaId: metric.formulaId,
          formulaVersion: metric.formulaVersion,
          category: qualityMetricCategory(metric.metricId), points: [],
        };
        groups.set(key, group);
      }
      group.points.push({
        snapshotId: snapshot.snapshotId, evaluatedAt: snapshot.evaluatedAt,
        value: qualityMetricDisplayValue(metric), result: metric.result, unit: metric.unit,
      });
    }
  }
  return Object.freeze([...groups.values()].map((group) => Object.freeze({
    ...group,
    points: Object.freeze(group.points.sort((left, right) =>
      left.evaluatedAt.localeCompare(right.evaluatedAt))),
  })).sort((left, right) =>
    `${left.category}:${left.metricId}:${left.formulaVersion}`
      .localeCompare(`${right.category}:${right.metricId}:${right.formulaVersion}`)));
}

function requireFilters(filters: QualitySnapshotFilters): void {
  if (!integer(filters.size, 1) || filters.size > 100
      || filters.sourceId !== undefined && !sourceId.test(filters.sourceId)
      || filters.overallResult !== undefined
        && !['quality-passed', 'quality-failed'].includes(filters.overallResult)
      || filters.evaluatedFrom !== undefined && !validInstant(filters.evaluatedFrom)
      || filters.evaluatedTo !== undefined && !validInstant(filters.evaluatedTo)
      || filters.sortField !== 'evaluatedAt'
      || !['asc', 'desc'].includes(filters.sortDirection)
      || (filters.afterEvaluatedAt === undefined) !== (filters.afterSnapshotId === undefined)
      || filters.afterEvaluatedAt !== undefined && !validInstant(filters.afterEvaluatedAt)
      || filters.afterSnapshotId !== undefined && !uuidV7.test(filters.afterSnapshotId)) {
    throw invalidRequest();
  }
}

function isPage(value: unknown): value is QualitySnapshotPage {
  if (!exact(value, ['hasMore', 'items', 'nextCursor', 'size'])
      || !Array.isArray(value.items) || !value.items.every(isSnapshot)
      || !integer(value.size, 1) || value.size > 100 || value.items.length > value.size
      || typeof value.hasMore !== 'boolean') return false;
  if (!value.hasMore) return value.nextCursor === null;
  if (!isCursor(value.nextCursor) || value.items.length !== value.size) return false;
  const last = value.items.at(-1);
  return last !== undefined && last.snapshotId === value.nextCursor.snapshotId
    && last.evaluatedAt === value.nextCursor.evaluatedAt;
}

function isCursor(value: unknown): value is QualitySnapshotCursor {
  return exact(value, ['evaluatedAt', 'snapshotId'])
    && validInstant(value.evaluatedAt) && uuidV7.test(value.snapshotId);
}

function isSnapshot(value: unknown): value is QualitySnapshot {
  if (!exact(value, [
    'aggregateVersion', 'approvalRef', 'assessedBatchStatus', 'batchId',
    'canonicalizationProfile', 'cutoffAt', 'effectiveAt', 'evaluatedAt',
    'immutableHash', 'impactScopeCodes', 'lineageId', 'manifestDigest',
    'metricResults', 'observationWindow', 'overallResult',
    'qualityGateDigest', 'qualityGateVersion', 'qualityMetricDecisionProfileDigest',
    'qualityMetricDecisionProfileVersion', 'retentionScheduleVersion', 'snapshotId',
    'sourceId', 'sourceOwnerRef', 'sourceSchemaDigest', 'sourceSchemaVersion',
    'supersedesSnapshotId', 'traceId', 'watermark',
  ])) return false;
  return uuidV7.test(value.snapshotId) && uuidV7.test(value.batchId)
    && uuidV7.test(value.lineageId)
    && (value.supersedesSnapshotId === null || uuidV7.test(value.supersedesSnapshotId))
    && sourceId.test(value.sourceId)
    && ['quality-passed', 'quality-failed'].includes(value.assessedBatchStatus)
    && value.overallResult === value.assessedBatchStatus
    && exact(value.observationWindow, ['endAt', 'startAt'])
    && validInstant(value.observationWindow.startAt)
    && validInstant(value.observationWindow.endAt)
    && validInstant(value.cutoffAt) && validInstant(value.evaluatedAt)
    && validInstant(value.effectiveAt)
    && typeof value.watermark === 'string' && value.watermark.length > 0 && value.watermark.length <= 512
    && Array.isArray(value.metricResults) && value.metricResults.length >= 1
    && value.metricResults.length <= 14 && value.metricResults.every(isMetric)
    && Array.isArray(value.impactScopeCodes) && value.impactScopeCodes.length <= 64
    && value.impactScopeCodes.every((item) => typeof item === 'string' && metricId.test(item))
    && new Set(value.impactScopeCodes).size === value.impactScopeCodes.length
    && typeof value.sourceOwnerRef === 'string' && value.sourceOwnerRef.length > 0
    && value.approvalRef === 'AUTH-2026-08-08-001'
    && value.retentionScheduleVersion === 'RS-1.0.0'
    && value.qualityMetricDecisionProfileVersion === 'QMDP-1.0.0'
    && value.qualityGateVersion === 'QG-1.0.0'
    && value.canonicalizationProfile === 'SCHOLARSENSE-CANONICAL-JSON-1.0.0'
    && [value.qualityMetricDecisionProfileDigest, value.qualityGateDigest,
      value.manifestDigest, value.sourceSchemaDigest, value.immutableHash]
      .every((item) => typeof item === 'string' && digest.test(item))
    && typeof value.sourceSchemaVersion === 'string' && value.sourceSchemaVersion.length > 0
    && traceId.test(value.traceId) && value.aggregateVersion === 3;
}

function isMetric(value: unknown): value is QualityMetricResult {
  if (!exact(value, [
    'applicable', 'boundary', 'denominator', 'formulaId', 'formulaVersion', 'metricId',
    'numerator', 'operator', 'reasonCode', 'result', 'thresholdDenominator',
    'thresholdNumerator', 'unit', 'valueBasisPoints',
  ])) return false;
  return metricId.test(value.metricId)
    && typeof value.formulaId === 'string' && value.formulaId.startsWith('QMDP-1.0.0/')
    && typeof value.formulaVersion === 'string' && /^[0-9]+\.[0-9]+\.[0-9]+$/.test(value.formulaVersion)
    && ['passed', 'failed', 'not-applicable'].includes(value.result)
    && typeof value.applicable === 'boolean'
    && integer(value.numerator, 0) && integer(value.denominator, 0)
    && (value.valueBasisPoints === null || integer(value.valueBasisPoints, 0))
    && ['basis-point', 'count', 'millisecond', 'member-count'].includes(value.unit)
    && ['>=', '<=', '='].includes(value.operator)
    && integer(value.thresholdNumerator, 0) && integer(value.thresholdDenominator, 1)
    && value.boundary === 'inclusive' && value.reasonCode === null
    && (value.applicable ? value.result !== 'not-applicable' : value.result === 'not-applicable');
}

async function safeFailure(response: Response): Promise<Error> {
  let code = response.status === 404
    ? 'INGESTION_QUALITY_FORBIDDEN' : 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    if (typeof value === 'object' && value !== null && 'code' in value
        && typeof value.code === 'string' && allowedFailures.has(value.code)) code = value.code;
  } catch { /* External text is deliberately ignored. */ }
  return new Error(code);
}

function validInstant(value: unknown): value is string {
  return typeof value === 'string' && /(?:Z|[+-][0-9]{2}:[0-9]{2})$/.test(value)
    && Number.isFinite(Date.parse(value));
}
function integer(value: unknown, minimum: number): value is number {
  return Number.isSafeInteger(value) && Number(value) >= minimum;
}
function exact(value: unknown, keys: readonly string[]): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.keys(value).sort().join() === [...keys].sort().join();
}
function deepFreeze<T>(value: T): T {
  if (typeof value === 'object' && value !== null) {
    Object.values(value).forEach(deepFreeze);
    Object.freeze(value);
  }
  return value;
}
function invalidRequest(): Error { return new Error('INGESTION_QUALITY_REQUEST_INVALID'); }
