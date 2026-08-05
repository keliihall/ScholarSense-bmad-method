import type { AuthorizedShellStatus, CsrfProof } from '../../identity-access';

export type CatalogStatus = 'DRAFT' | 'INVALID' | 'PUBLISHABLE' | 'PUBLISHED';
export type ValidationFailure = Readonly<{ code: string; fieldPath: string }>;
export type CatalogSummary = Readonly<{
  catalogId: string;
  catalogReleaseId: string | null;
  contractVersion: 'DCC-1.0.0';
  status: CatalogStatus;
  aggregateVersion: number;
  currentPointerVersion: number;
  contentDigest: string;
  evidenceSetDigest: string | null;
  validationFailures: readonly ValidationFailure[];
  updatedAt: string;
  publishedAt: string | null;
}>;
export type SourceContractView = Readonly<{
  sourceId: string;
  purpose: string;
  schemaVersion: string;
  qualityGateVersion: 'QG-1.0.0';
  evidenceUri: string;
  runtimeEvidenceClaim: 'NONE' | 'TARGET_VERIFIED';
  metadata: Readonly<{
    ownerDepartment: string;
    ownerName: string;
    responsibleRole: string;
    businessDefinition: string;
    businessKeys: readonly string[];
    effectiveInterval: 'half-open-utc';
    updateFrequency: string;
    slo: string;
    coverage: string;
    sensitivity: string;
    schemaRef: string;
    reconciliation: string;
    reconciliationMinimumBasisPoints: number;
    backfillWindowDays: number;
    watermarkRequired: boolean;
    contractTests: readonly string[];
    consumerMode: string;
    status: 'approved-contract';
  }>;
}>;
export type DependencyBindingView = Readonly<{
  sourceId: string;
  dependencyId: string;
  requirement: 'REQUIRED' | 'OPTIONAL';
  operator: 'ALL_OF' | 'ANY_OF' | 'THRESHOLD';
}>;
export type CatalogDetail = CatalogSummary & Readonly<{
  sources: readonly SourceContractView[];
  dependencies: readonly DependencyBindingView[];
}>;
export type CatalogPage = Readonly<{
  items: readonly CatalogSummary[];
  page: number;
  size: number;
  hasMore: boolean;
}>;
export type DataCatalogIdentityGeneration = Readonly<{
  sessionPseudonym: string;
  sessionVersion: number;
  policyVersion: 'RFP-1.0.0';
  capabilitySignature: string;
}>;
export type CatalogPublicationInput = Readonly<{
  catalogId: string;
  expectedVersion: number;
  expectedCurrentVersion: number;
}>;
export type CatalogPublicationCommand = CatalogPublicationInput & Readonly<{
  catalogReleaseId: string;
  idempotencyKey: string;
}>;
export type DataCatalogClearReason =
  | 'refresh'
  | 'logout'
  | 'account-switch'
  | 'session-invalid'
  | 'authorization-revoked';

export const FROZEN_DATA_SOURCE_IDS = Object.freeze([
  'SRC-P0-STUDENT-001',
  'SRC-P0-RESPONSIBILITY-001',
  'SRC-P0-ACCOMMODATION-001',
  'SRC-P0-CARD-001',
  'SRC-P0-CAMPUS-ACCESS-001',
  'SRC-P0-DORM-ACCESS-001',
  'SRC-P0-DEVICE-001',
  'SRC-P0-LEAVE-001',
  'SRC-P0-CALENDAR-001',
  'SRC-P0-TIMETABLE-001',
  'SRC-P1-OFFCAMPUS-001',
  'SRC-P1-NETWORK-001',
  'SRC-P1-ACADEMIC-001',
  'SRC-P1-CARE-LIST-001',
  'SRC-P1-PSYCH-DEID-001',
  'SRC-P1-AID-001',
  'SRC-P1-WORK-VISIT-001',
] as const);

export const FROZEN_DATA_DEPENDENCIES = Object.freeze({
  'SRC-P0-CAMPUS-ACCESS-001': 'DEP-P0-CAMPUS-ACCESS-001',
  'SRC-P0-DORM-ACCESS-001': 'DEP-P0-DORM-ACCESS-001',
  'SRC-P0-ACCOMMODATION-001': 'DEP-P0-ACCOMMODATION-001',
  'SRC-P0-LEAVE-001': 'DEP-P0-LEAVE-001',
  'SRC-P0-CALENDAR-001': 'DEP-P0-CALENDAR-001',
  'SRC-P0-CARD-001': 'DEP-P0-CONSUMPTION-001',
  'SRC-P0-TIMETABLE-001': 'DEP-P0-TIMETABLE-001',
  'SRC-P0-DEVICE-001': 'DEP-P0-DEVICE-001',
  'SRC-P1-OFFCAMPUS-001': 'DEP-P1-OFFCAMPUS-001',
  'SRC-P1-NETWORK-001': 'DEP-P1-NETWORK-001',
  'SRC-P1-ACADEMIC-001': 'DEP-P1-ACADEMIC-001',
} as const);

const uuidV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const digest = /^sha256:[0-9a-f]{64}$/;
const frozenSources = new Set<string>(FROZEN_DATA_SOURCE_IDS);
const frozenDependencies = new Map<string, string>(Object.entries(FROZEN_DATA_DEPENDENCIES));
const allowedFailures = new Set([
  'INGESTION_QUALITY_FORBIDDEN', 'INGESTION_QUALITY_VERSION_CONFLICT',
  'INGESTION_QUALITY_IDEMPOTENCY_MISMATCH', 'INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT',
  'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE',
  'INGESTION_QUALITY_CONTRACT_INVALID', 'INGESTION_QUALITY_EVIDENCE_INVALID',
  'INGESTION_QUALITY_INVALID_STATE', 'INGESTION_QUALITY_REQUEST_INVALID',
]);

export class DataCatalogResponseFailure extends Error {
  public constructor(code: string, public readonly retryable: boolean) {
    super(code);
    this.name = 'DataCatalogResponseFailure';
  }
}

export function retainPublicationProof(failure: unknown): boolean {
  return !(failure instanceof DataCatalogResponseFailure) || failure.retryable;
}

export class DataSourceCatalogClient {
  public constructor(
    private readonly request: typeof fetch = globalThis.fetch.bind(globalThis),
    private readonly csrfProof?: (signal?: AbortSignal) => Promise<CsrfProof>,
  ) {}

  public async list(page: number, size: number, signal?: AbortSignal): Promise<CatalogPage> {
    if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 100) {
      throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    }
    const result = await this.response(
      `/api/v1/data-source-catalogs?page=${page}&size=${size}`, { signal }, isPage,
    );
    if (result.page !== page || result.size !== size) {
      throw new Error('INGESTION_QUALITY_RESPONSE_INVALID');
    }
    return result;
  }

  public async detail(catalogId: string, signal?: AbortSignal): Promise<CatalogDetail> {
    if (!uuidV7.test(catalogId)) throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    return this.response(`/api/v1/data-source-catalogs/${catalogId}`, { signal }, isDetail);
  }

  public async validate(
    catalogId: string, expectedVersion: number, signal?: AbortSignal,
  ): Promise<CatalogSummary> {
    if (!integer(expectedVersion, 1)) throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    return this.command(`${catalogId}/validations`, undefined, { expectedVersion }, signal);
  }

  public async publish(
    catalogId: string, expectedVersion: number, expectedCurrentVersion: number,
    catalogReleaseId: string, idempotencyKey: string, signal?: AbortSignal,
  ): Promise<CatalogSummary> {
    if (!integer(expectedVersion, 1) || !integer(expectedCurrentVersion, 0)
      || !uuidV7.test(catalogReleaseId)
      || !idempotencyKey || idempotencyKey.length > 128) {
      throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    }
    return this.command(`${catalogId}/publications`, idempotencyKey, {
      expectedVersion, expectedCurrentVersion, catalogReleaseId,
    }, signal);
  }

  private async command(
    path: string, idempotencyKey: string | undefined, body: object, signal?: AbortSignal,
  ): Promise<CatalogSummary> {
    if (!uuidV7.test(path.split('/')[0])) throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    if (this.csrfProof === undefined) throw new Error('INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE');
    const csrf = await this.csrfProof(signal);
    return this.response(`/api/v1/data-source-catalogs/${path}`, {
      method: 'POST', credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer', signal,
      headers: {
        Accept: 'application/json', 'Content-Type': 'application/json',
        [csrf.headerName]: csrf.value,
        ...(idempotencyKey === undefined ? {} : { 'Idempotency-Key': idempotencyKey }),
      },
      body: JSON.stringify(body),
    }, isSummary);
  }

  private async response<T>(
    input: string, init: RequestInit, validate: (value: unknown) => value is T,
  ): Promise<T> {
    const response = await this.request(input, {
      credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json', ...init.headers }, ...init,
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!validate(value)) throw new Error('INGESTION_QUALITY_RESPONSE_INVALID');
    return deepFreeze(value);
  }
}

export class DataSourceCatalogMemoryState {
  #detail?: CatalogDetail;
  public accept(detail: CatalogDetail): void { this.#detail = detail; }
  public current(): CatalogDetail | undefined { return this.#detail; }
  public clear(_reason: DataCatalogClearReason): void {
    this.#detail = undefined;
  }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

/** Keeps retry proof only in this component's memory; it has no persistence or serialization surface. */
export class DataSourceCatalogPublicationMemory {
  #pending?: CatalogPublicationCommand;

  public prepare(
    input: CatalogPublicationInput,
    releaseId: () => string,
    idempotencyKey: () => string,
  ): CatalogPublicationCommand {
    if (this.#pending !== undefined && samePublicationInput(this.#pending, input)) {
      return this.#pending;
    }
    this.#pending = Object.freeze({
      ...input,
      catalogReleaseId: releaseId(),
      idempotencyKey: idempotencyKey(),
    });
    return this.#pending;
  }

  public complete(command: CatalogPublicationCommand): void {
    if (this.#pending === command) this.#pending = undefined;
  }

  public current(): CatalogPublicationCommand | undefined { return this.#pending; }
  public clear(_reason: DataCatalogClearReason): void { this.#pending = undefined; }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

type Mutable<T> = { value: T };
export function clearDataCatalogIdentityBoundary(
  active: Mutable<AbortController | undefined>, page: Mutable<CatalogPage | undefined>,
  detail: Mutable<CatalogDetail | undefined>, memory: DataSourceCatalogMemoryState,
  reason: DataCatalogClearReason = 'authorization-revoked',
): void {
  active.value?.abort(); active.value = undefined; page.value = undefined; detail.value = undefined;
  memory.clear(reason);
}

export function sameDataCatalogIdentityGeneration(
  requested: DataCatalogIdentityGeneration,
  current: DataCatalogIdentityGeneration | undefined,
): boolean {
  return current !== undefined
    && requested.sessionPseudonym === current.sessionPseudonym
    && requested.sessionVersion === current.sessionVersion
    && requested.policyVersion === current.policyVersion
    && requested.capabilitySignature === current.capabilitySignature;
}

export function hasUsableDataCatalogAuthorization(
  authenticated: boolean, status: AuthorizedShellStatus, hasShell: boolean, signature: string,
): boolean {
  return authenticated && (status === 'ready' || status === 'degraded') && hasShell
    && signature === 'available|available';
}

export function dataCatalogQueryOptions(context: Readonly<{
  sessionVersion: number; policyVersion: 'RFP-1.0.0'; page: number;
}>, queryFn: () => Promise<CatalogPage>) {
  return Object.freeze({
    queryKey: Object.freeze(['ingestion-quality', 'data-source-catalogs', Object.freeze({ ...context })] as const),
    queryFn, staleTime: 0, gcTime: 0, retry: false, networkMode: 'online' as const,
  });
}

function isPage(value: unknown): value is CatalogPage {
  return exact(value, ['hasMore', 'items', 'page', 'size']) && Array.isArray(value.items)
    && value.items.every(isSummary) && integer(value.page, 0) && integer(value.size, 1)
    && value.size <= 100 && value.items.length <= value.size
    && typeof value.hasMore === 'boolean'
    && (!value.hasMore || value.items.length === value.size);
}

function isSummary(value: unknown): value is CatalogSummary {
  if (!exact(value, ['aggregateVersion', 'catalogId', 'catalogReleaseId', 'contentDigest',
    'contractVersion', 'currentPointerVersion', 'evidenceSetDigest', 'publishedAt', 'status', 'updatedAt',
    'validationFailures'])) return false;
  return typeof value.catalogId === 'string' && uuidV7.test(value.catalogId)
    && (value.catalogReleaseId === null || typeof value.catalogReleaseId === 'string' && uuidV7.test(value.catalogReleaseId))
    && value.contractVersion === 'DCC-1.0.0'
    && ['DRAFT', 'INVALID', 'PUBLISHABLE', 'PUBLISHED'].includes(String(value.status))
    && integer(value.aggregateVersion, 1) && integer(value.currentPointerVersion, 0)
    && typeof value.contentDigest === 'string' && digest.test(value.contentDigest)
    && (value.evidenceSetDigest === null || typeof value.evidenceSetDigest === 'string' && digest.test(value.evidenceSetDigest))
    && Array.isArray(value.validationFailures) && value.validationFailures.every((item) =>
      exact(item, ['code', 'fieldPath']) && typeof item.code === 'string' && typeof item.fieldPath === 'string')
    && validInstant(value.updatedAt) && (value.publishedAt === null || validInstant(value.publishedAt))
    && (value.status === 'PUBLISHED'
      ? value.catalogReleaseId !== null && value.evidenceSetDigest !== null && value.publishedAt !== null
      : value.catalogReleaseId === null && value.evidenceSetDigest === null && value.publishedAt === null);
}

function isDetail(value: unknown): value is CatalogDetail {
  if (!exact(value, ['aggregateVersion', 'catalogId', 'catalogReleaseId', 'contentDigest', 'contractVersion',
    'currentPointerVersion', 'dependencies', 'evidenceSetDigest', 'publishedAt', 'sources', 'status', 'updatedAt',
    'validationFailures'])) return false;
  const summary = Object.fromEntries(Object.entries(value).filter(([key]) => !['sources', 'dependencies'].includes(key)));
  if (!isSummary(summary) || !Array.isArray(value.sources) || value.sources.length !== 17
    || !value.sources.every(isSource) || new Set(value.sources.map((item) => item.sourceId)).size !== 17
    || !value.sources.every((item) => frozenSources.has(item.sourceId))
    || !Array.isArray(value.dependencies) || value.dependencies.length !== 11) return false;
  return new Set(value.dependencies.map((item) => item.dependencyId)).size === 11
    && new Set(value.dependencies.map((item) => item.sourceId)).size === 11
    && value.dependencies.every((item) => exact(item,
      ['dependencyId', 'operator', 'requirement', 'sourceId'])
      && typeof item.sourceId === 'string'
      && typeof item.dependencyId === 'string'
      && frozenDependencies.get(item.sourceId) === item.dependencyId
      && item.requirement === 'REQUIRED'
      && item.operator === 'ALL_OF');
}

function isSource(value: unknown): value is SourceContractView {
  return exact(value, ['evidenceUri', 'metadata', 'purpose', 'qualityGateVersion', 'runtimeEvidenceClaim',
    'schemaVersion', 'sourceId']) && typeof value.sourceId === 'string' && frozenSources.has(value.sourceId)
    && typeof value.purpose === 'string'
    && typeof value.schemaVersion === 'string' && value.qualityGateVersion === 'QG-1.0.0'
    && typeof value.evidenceUri === 'string' && ['NONE', 'TARGET_VERIFIED'].includes(String(value.runtimeEvidenceClaim))
    && validEvidenceUri(value.evidenceUri, value.sourceId, value.runtimeEvidenceClaim)
    && exact(value.metadata, ['backfillWindowDays', 'businessDefinition', 'businessKeys', 'consumerMode',
      'contractTests', 'coverage', 'effectiveInterval', 'ownerDepartment', 'ownerName', 'reconciliation',
      'reconciliationMinimumBasisPoints', 'responsibleRole', 'schemaRef', 'sensitivity', 'slo', 'status',
      'updateFrequency', 'watermarkRequired'])
    && ['ownerDepartment', 'ownerName', 'responsibleRole', 'businessDefinition', 'consumerMode', 'coverage',
      'reconciliation', 'schemaRef', 'sensitivity', 'slo', 'updateFrequency'].every(
        (field) => typeof value.metadata[field] === 'string',
      )
    && value.metadata.effectiveInterval === 'half-open-utc'
    && value.metadata.status === 'approved-contract'
    && Array.isArray(value.metadata.businessKeys) && value.metadata.businessKeys.every((item) => typeof item === 'string')
    && Array.isArray(value.metadata.contractTests) && value.metadata.contractTests.every((item) => typeof item === 'string')
    && integer(value.metadata.reconciliationMinimumBasisPoints, 9950)
    && value.metadata.reconciliationMinimumBasisPoints <= 10000
    && integer(value.metadata.backfillWindowDays, 1) && typeof value.metadata.watermarkRequired === 'boolean';
}

function validEvidenceUri(
  uri: string, sourceId: string, claim: unknown,
): boolean {
  if (claim === 'NONE') return uri === `evidence://pending/${sourceId}`;
  if (claim !== 'TARGET_VERIFIED') return false;
  const addressed = /^(?:sha256|evidence\+sha256):\/\/([0-9a-f]{64})(?:#source=(SRC-P[01]-[A-Z-]+-[0-9]{3}))?$/.exec(uri);
  if (addressed !== null) return addressed[2] === undefined || addressed[2] === sourceId;
  return /^oci:\/\/ghcr\.io\/[a-z0-9_.-]+\/[a-z0-9_./-]+@sha256:[0-9a-f]{64}$/.test(uri)
    || /^s3-version:\/\/[a-z0-9][a-z0-9.-]{1,62}\/[^?#]+\?versionId=[A-Za-z0-9._~-]{8,}$/.test(uri);
}

async function safeFailure(response: Response): Promise<Error> {
  let code = response.status === 404 ? 'INGESTION_QUALITY_FORBIDDEN' : 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    if (typeof value === 'object' && value !== null && 'code' in value
      && typeof value.code === 'string' && allowedFailures.has(value.code)) code = value.code;
  } catch { /* External text is deliberately ignored. */ }
  return new DataCatalogResponseFailure(
    code,
    response.status === 408 || response.status === 429 || response.status >= 500,
  );
}

function validInstant(value: unknown): value is string {
  return typeof value === 'string' && /(?:Z|[+-][0-9]{2}:[0-9]{2})$/.test(value) && Number.isFinite(Date.parse(value));
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
    Object.values(value).forEach(deepFreeze); Object.freeze(value);
  }
  return value;
}

function samePublicationInput(
  current: CatalogPublicationInput,
  requested: CatalogPublicationInput,
): boolean {
  return current.catalogId === requested.catalogId
    && current.expectedVersion === requested.expectedVersion
    && current.expectedCurrentVersion === requested.expectedCurrentVersion;
}
