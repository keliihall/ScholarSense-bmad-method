export type QualityEligibilityStatus = 'eligible' | 'fused' | 'recovering' | 'missing';

export type QualityEligibilityMember = Readonly<{
  sourceId: string;
  sourceVersion: number;
  dependencyId: string;
  dependencyVersion: number;
  requirement: 'required' | 'optional';
  state: QualityEligibilityStatus;
  versionContinuous: boolean;
  sourceWatermark: string;
  dependencyWatermark: string;
  snapshotId: string | null;
  snapshotImmutableHash: string | null;
}>;

export type QualityEligibility = Readonly<{
  eligibilityId: string;
  ruleId: string;
  ruleVersion: '1.0.0';
  status: QualityEligibilityStatus;
  reasonCode: 'ALL_REQUIRED_ELIGIBLE' | 'REQUIRED_MEMBER_FUSED'
    | 'REQUIRED_MEMBER_RECOVERING' | 'REQUIRED_MEMBER_MISSING'
    | 'REQUIRED_MEMBER_VERSION_GAP' | 'ANY_OF_UNSATISFIED'
    | 'THRESHOLD_UNSATISFIED' | 'RECOVERY_COMMAND_ACCEPTED';
  operator: 'all-of' | 'any-of' | 'threshold';
  threshold: number | null;
  members: readonly QualityEligibilityMember[];
  failedMembers: readonly string[];
  registryVersion: 'RULE-DEPENDENCY-REGISTRY-1.0.0';
  aggregateVersion: number;
  effectiveAt: string;
  occurredAt: string;
}>;

export type QualityEligibilityCursor = Readonly<{
  occurredAt: string;
  eligibilityId: string;
}>;

export type QualityEligibilityPage = Readonly<{
  items: readonly QualityEligibility[];
  size: number;
  hasMore: boolean;
  nextCursor: QualityEligibilityCursor | null;
}>;

export type QualityEligibilityFilters = Readonly<{
  status?: QualityEligibilityStatus;
  ruleId?: string;
  afterOccurredAt?: string;
  afterEligibilityId?: string;
  size: number;
}>;

export type QualityEligibilityIdentityGeneration = Readonly<{
  sessionPseudonym: string;
  sessionVersion: number;
  policyVersion: 'RFP-1.0.0';
  capabilitySignature: string;
}>;

export type QualityEligibilityClearReason = 'refresh' | 'logout' | 'account-switch'
  | 'session-invalid' | 'authorization-revoked' | 'offline' | 'narrow-screen';

const uuidV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ruleId = /^[A-Z][A-Z0-9-]{2,63}$/;
const sourceId = /^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$/;
const dependencyId = /^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$/;
const digest = /^sha256:[0-9a-f]{64}$/;
const statuses = new Set<QualityEligibilityStatus>(['eligible', 'fused', 'recovering', 'missing']);
const reasons = new Set([
  'ALL_REQUIRED_ELIGIBLE', 'REQUIRED_MEMBER_FUSED', 'REQUIRED_MEMBER_RECOVERING',
  'REQUIRED_MEMBER_MISSING', 'REQUIRED_MEMBER_VERSION_GAP', 'ANY_OF_UNSATISFIED',
  'THRESHOLD_UNSATISFIED', 'RECOVERY_COMMAND_ACCEPTED',
]);
const allowedFailures = new Set([
  'INGESTION_QUALITY_FORBIDDEN', 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE',
  'INGESTION_QUALITY_REQUEST_INVALID', 'INGESTION_QUALITY_PAGE_INVALID',
]);

export class QualityEligibilityClient {
  public constructor(private readonly request: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  public async list(
    filters: QualityEligibilityFilters, signal?: AbortSignal,
  ): Promise<QualityEligibilityPage> {
    requireFilters(filters);
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined) query.set(key, String(value));
    }
    return this.response(`/api/v1/quality-eligibilities?${query.toString()}`, { signal }, isPage);
  }

  public async detail(value: string, signal?: AbortSignal): Promise<QualityEligibility> {
    if (!uuidV7.test(value)) throw invalidRequest();
    return this.response(`/api/v1/quality-eligibilities/${value}`, { signal }, isEligibility);
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

export class QualityEligibilityMemoryState {
  #page?: QualityEligibilityPage;
  #detail?: QualityEligibility;

  public acceptPage(value: QualityEligibilityPage): void { this.#page = value; }
  public acceptDetail(value: QualityEligibility): void { this.#detail = value; }
  public page(): QualityEligibilityPage | undefined { return this.#page; }
  public detail(): QualityEligibility | undefined { return this.#detail; }
  public clear(_reason: QualityEligibilityClearReason): void {
    this.#page = undefined;
    this.#detail = undefined;
  }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

type Mutable<T> = { value: T };
export function clearQualityEligibilityIdentityBoundary(
  active: Mutable<AbortController | undefined>,
  page: Mutable<QualityEligibilityPage | undefined>,
  detail: Mutable<QualityEligibility | undefined>,
  memory: QualityEligibilityMemoryState,
  reason: QualityEligibilityClearReason = 'authorization-revoked',
): void {
  active.value?.abort();
  active.value = undefined;
  page.value = undefined;
  detail.value = undefined;
  memory.clear(reason);
}

export function shouldClearQualityEligibilityQueryCache(
  reason: QualityEligibilityClearReason,
): boolean {
  return reason !== 'refresh';
}

export function qualityEligibilityQueryOptions(
  context: QualityEligibilityIdentityGeneration & Readonly<Record<string, string | number | undefined>>,
  queryFn: () => Promise<QualityEligibilityPage>,
) {
  return Object.freeze({
    queryKey: Object.freeze([
      'ingestion-quality', 'quality-eligibilities', Object.freeze({ ...context }),
    ] as const),
    queryFn, staleTime: 0, gcTime: 5 * 60 * 1000,
    retry: false, networkMode: 'online' as const,
  });
}

export function qualityEligibilityStatusText(value: QualityEligibilityStatus): string {
  return ({
    eligible: '✓ Eligible（全部必需依赖可用）',
    fused: '⛔ Fused（质量熔断）',
    recovering: '↻ Recovering（恢复观察中）',
    missing: '? Missing（必需证据缺失）',
  })[value];
}

function requireFilters(filters: QualityEligibilityFilters): void {
  if (!integer(filters.size, 1) || filters.size > 100
      || filters.status !== undefined && !statuses.has(filters.status)
      || filters.ruleId !== undefined && !ruleId.test(filters.ruleId)
      || (filters.afterOccurredAt === undefined) !== (filters.afterEligibilityId === undefined)
      || filters.afterOccurredAt !== undefined && !validInstant(filters.afterOccurredAt)
      || filters.afterEligibilityId !== undefined && !uuidV7.test(filters.afterEligibilityId)) {
    throw invalidRequest();
  }
}

function isPage(value: unknown): value is QualityEligibilityPage {
  if (!exact(value, ['hasMore', 'items', 'nextCursor', 'size'])
      || !Array.isArray(value.items) || !value.items.every(isEligibility)
      || !integer(value.size, 1) || value.size > 100 || value.items.length > value.size
      || typeof value.hasMore !== 'boolean') return false;
  if (!value.hasMore) return value.nextCursor === null;
  if (!isCursor(value.nextCursor) || value.items.length !== value.size) return false;
  const last = value.items.at(-1);
  return last !== undefined && last.eligibilityId === value.nextCursor.eligibilityId
    && last.occurredAt === value.nextCursor.occurredAt;
}

function isCursor(value: unknown): value is QualityEligibilityCursor {
  return exact(value, ['eligibilityId', 'occurredAt'])
    && uuidV7.test(value.eligibilityId) && validInstant(value.occurredAt);
}

function isEligibility(value: unknown): value is QualityEligibility {
  if (!exact(value, [
    'aggregateVersion', 'effectiveAt', 'eligibilityId', 'failedMembers', 'members',
    'occurredAt', 'operator', 'reasonCode', 'registryVersion', 'ruleId', 'ruleVersion',
    'status', 'threshold',
  ]) || !uuidV7.test(value.eligibilityId) || !ruleId.test(value.ruleId)
      || value.ruleVersion !== '1.0.0' || !statuses.has(value.status)
      || !reasons.has(value.reasonCode)
      || !['all-of', 'any-of', 'threshold'].includes(value.operator)
      || value.registryVersion !== 'RULE-DEPENDENCY-REGISTRY-1.0.0'
      || !integer(value.aggregateVersion, 1)
      || !validInstant(value.effectiveAt) || !validInstant(value.occurredAt)
      || new Date(value.effectiveAt).getTime() > new Date(value.occurredAt).getTime()
      || !Array.isArray(value.members) || value.members.length < 1 || value.members.length > 11
      || !value.members.every(isMember)
      || !Array.isArray(value.failedMembers) || value.failedMembers.length > 11
      || !value.failedMembers.every((item) => typeof item === 'string' && dependencyId.test(item))) {
    return false;
  }
  const memberIds = value.members.map((item) => item.dependencyId);
  const failed = value.members.filter((item) => item.state !== 'eligible')
    .map((item) => item.dependencyId).sort();
  const claimedFailed = [...value.failedMembers].sort();
  if (new Set(memberIds).size !== memberIds.length
      || new Set(claimedFailed).size !== claimedFailed.length
      || JSON.stringify(failed) !== JSON.stringify(claimedFailed)
      || value.status === 'eligible' && claimedFailed.length > 0) return false;
  return value.operator === 'threshold'
    ? integer(value.threshold, 1) && value.threshold <= value.members.length
    : value.threshold === null;
}

function isMember(value: unknown): value is QualityEligibilityMember {
  if (!exact(value, [
    'dependencyId', 'dependencyVersion', 'dependencyWatermark', 'requirement',
    'snapshotId', 'snapshotImmutableHash', 'sourceId', 'sourceVersion', 'sourceWatermark',
    'state', 'versionContinuous',
  ])) return false;
  const snapshotPair = value.snapshotId === null && value.snapshotImmutableHash === null
    || typeof value.snapshotId === 'string' && uuidV7.test(value.snapshotId)
      && typeof value.snapshotImmutableHash === 'string' && digest.test(value.snapshotImmutableHash);
  return sourceId.test(value.sourceId) && integer(value.sourceVersion, 1)
    && dependencyId.test(value.dependencyId) && integer(value.dependencyVersion, 1)
    && ['required', 'optional'].includes(value.requirement) && statuses.has(value.state)
    && typeof value.versionContinuous === 'boolean'
    && boundedText(value.sourceWatermark, 512) && boundedText(value.dependencyWatermark, 512)
    && snapshotPair && (value.state === 'missing' || value.snapshotId !== null);
}

function exact(value: unknown, fields: readonly string[]): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.keys(value).sort().join('\0') === [...fields].sort().join('\0');
}

function integer(value: unknown, minimum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum;
}

function validInstant(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && !Number.isNaN(Date.parse(value));
}

function boundedText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && [...value].length >= 1 && [...value].length <= maximum;
}

async function safeFailure(response: Response): Promise<Error> {
  try {
    const value: unknown = await response.json();
    if (exact(value, ['code', 'currentVersion', 'fieldErrors', 'traceId'])
        && typeof value.code === 'string' && allowedFailures.has(value.code)) {
      return new Error(value.code);
    }
  } catch { /* external bodies are never reflected */ }
  if (response.status === 404) return new Error('INGESTION_QUALITY_FORBIDDEN');
  if (response.status === 503) return new Error('INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE');
  return invalidRequest();
}

function invalidRequest(): Error { return new Error('INGESTION_QUALITY_REQUEST_INVALID'); }

function deepFreeze<T>(value: T): T {
  if (typeof value === 'object' && value !== null && !Object.isFrozen(value)) {
    Object.freeze(value);
    Object.values(value).forEach((item) => deepFreeze(item));
  }
  return value;
}
