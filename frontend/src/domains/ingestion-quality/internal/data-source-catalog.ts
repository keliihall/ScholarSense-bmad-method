import type { AuthorizedShellStatus, CsrfProof } from '../../identity-access';

export type CatalogStatus = 'DRAFT' | 'INVALID' | 'PUBLISHABLE' | 'PUBLISHED';
export type ValidationFailure = Readonly<{ code: string; fieldPath: string }>;
export type CatalogSummary = Readonly<{
  catalogId: string;
  catalogReleaseId: string | null;
  contractVersion: 'DCC-1.0.0';
  status: CatalogStatus;
  aggregateVersion: number;
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
    businessDefinition: string;
    businessKeys: readonly string[];
    updateFrequency: string;
    slo: string;
    coverage: string;
    sensitivity: string;
    reconciliation: string;
    backfillWindowDays: number;
    watermarkRequired: boolean;
    contractTests: readonly string[];
    consumerMode: string;
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

const uuidV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const digest = /^sha256:[0-9a-f]{64}$/;
const allowedFailures = new Set([
  'INGESTION_QUALITY_FORBIDDEN', 'INGESTION_QUALITY_VERSION_CONFLICT',
  'INGESTION_QUALITY_IDEMPOTENCY_MISMATCH', 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE',
  'INGESTION_QUALITY_CONTRACT_INVALID', 'INGESTION_QUALITY_EVIDENCE_INVALID',
  'INGESTION_QUALITY_INVALID_STATE', 'INGESTION_QUALITY_REQUEST_INVALID',
]);

export class DataSourceCatalogClient {
  public constructor(
    private readonly request: typeof fetch = globalThis.fetch.bind(globalThis),
    private readonly csrfProof?: () => Promise<CsrfProof>,
  ) {}

  public async list(page: number, size: number, signal?: AbortSignal): Promise<CatalogPage> {
    if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 100) {
      throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    }
    return this.response(`/api/v1/data-source-catalogs?page=${page}&size=${size}`, { signal }, isPage);
  }

  public async detail(catalogId: string, signal?: AbortSignal): Promise<CatalogDetail> {
    if (!uuidV7.test(catalogId)) throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    return this.response(`/api/v1/data-source-catalogs/${catalogId}`, { signal }, isDetail);
  }

  public async validate(catalogId: string, expectedVersion: number): Promise<CatalogSummary> {
    return this.command(`${catalogId}/validations`, undefined, { expectedVersion, requestedAt: new Date().toISOString() });
  }

  public async publish(
    catalogId: string, expectedVersion: number, catalogReleaseId: string,
    evidenceSetDigest: string, idempotencyKey: string,
  ): Promise<CatalogSummary> {
    if (!uuidV7.test(catalogReleaseId) || !digest.test(evidenceSetDigest)
      || !idempotencyKey || idempotencyKey.length > 128) {
      throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    }
    return this.command(`${catalogId}/publications`, idempotencyKey, {
      expectedVersion, catalogReleaseId, evidenceSetDigest, requestedAt: new Date().toISOString(),
    });
  }

  private async command(path: string, idempotencyKey: string | undefined, body: object): Promise<CatalogSummary> {
    if (!uuidV7.test(path.split('/')[0])) throw new Error('INGESTION_QUALITY_REQUEST_INVALID');
    if (this.csrfProof === undefined) throw new Error('INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE');
    const csrf = await this.csrfProof();
    return this.response(`/api/v1/data-source-catalogs/${path}`, {
      method: 'POST', credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer',
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
  public clear(_reason: 'refresh' | 'logout' | 'account-switch' | 'session-invalid' | 'authorization-revoked'): void {
    this.#detail = undefined;
  }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

type Mutable<T> = { value: T };
export function clearDataCatalogIdentityBoundary(
  active: Mutable<AbortController | undefined>, page: Mutable<CatalogPage | undefined>,
  detail: Mutable<CatalogDetail | undefined>, memory: DataSourceCatalogMemoryState,
): void {
  active.value?.abort(); active.value = undefined; page.value = undefined; detail.value = undefined;
  memory.clear('authorization-revoked');
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
    && typeof value.hasMore === 'boolean';
}

function isSummary(value: unknown): value is CatalogSummary {
  if (!exact(value, ['aggregateVersion', 'catalogId', 'catalogReleaseId', 'contentDigest',
    'contractVersion', 'evidenceSetDigest', 'publishedAt', 'status', 'updatedAt', 'validationFailures'])) return false;
  return typeof value.catalogId === 'string' && uuidV7.test(value.catalogId)
    && (value.catalogReleaseId === null || typeof value.catalogReleaseId === 'string' && uuidV7.test(value.catalogReleaseId))
    && value.contractVersion === 'DCC-1.0.0'
    && ['DRAFT', 'INVALID', 'PUBLISHABLE', 'PUBLISHED'].includes(String(value.status))
    && integer(value.aggregateVersion, 1) && typeof value.contentDigest === 'string' && digest.test(value.contentDigest)
    && (value.evidenceSetDigest === null || typeof value.evidenceSetDigest === 'string' && digest.test(value.evidenceSetDigest))
    && Array.isArray(value.validationFailures) && value.validationFailures.every((item) =>
      exact(item, ['code', 'fieldPath']) && typeof item.code === 'string' && typeof item.fieldPath === 'string')
    && validInstant(value.updatedAt) && (value.publishedAt === null || validInstant(value.publishedAt));
}

function isDetail(value: unknown): value is CatalogDetail {
  if (!exact(value, ['aggregateVersion', 'catalogId', 'catalogReleaseId', 'contentDigest', 'contractVersion',
    'dependencies', 'evidenceSetDigest', 'publishedAt', 'sources', 'status', 'updatedAt', 'validationFailures'])) return false;
  const summary = Object.fromEntries(Object.entries(value).filter(([key]) => !['sources', 'dependencies'].includes(key)));
  return isSummary(summary) && Array.isArray(value.sources) && value.sources.every(isSource)
    && Array.isArray(value.dependencies) && value.dependencies.every((item) => exact(item,
      ['dependencyId', 'operator', 'requirement', 'sourceId'])
      && typeof item.sourceId === 'string' && typeof item.dependencyId === 'string'
      && ['REQUIRED', 'OPTIONAL'].includes(String(item.requirement))
      && ['ALL_OF', 'ANY_OF', 'THRESHOLD'].includes(String(item.operator)));
}

function isSource(value: unknown): value is SourceContractView {
  return exact(value, ['evidenceUri', 'metadata', 'purpose', 'qualityGateVersion', 'runtimeEvidenceClaim',
    'schemaVersion', 'sourceId']) && typeof value.sourceId === 'string' && typeof value.purpose === 'string'
    && typeof value.schemaVersion === 'string' && value.qualityGateVersion === 'QG-1.0.0'
    && typeof value.evidenceUri === 'string' && ['NONE', 'TARGET_VERIFIED'].includes(String(value.runtimeEvidenceClaim))
    && exact(value.metadata, ['backfillWindowDays', 'businessDefinition', 'businessKeys', 'consumerMode',
      'contractTests', 'coverage', 'ownerDepartment', 'ownerName', 'reconciliation', 'sensitivity', 'slo',
      'updateFrequency', 'watermarkRequired'])
    && ['ownerDepartment', 'ownerName', 'businessDefinition', 'consumerMode', 'coverage', 'reconciliation',
      'sensitivity', 'slo', 'updateFrequency'].every((field) => typeof value.metadata[field] === 'string')
    && Array.isArray(value.metadata.businessKeys) && value.metadata.businessKeys.every((item) => typeof item === 'string')
    && Array.isArray(value.metadata.contractTests) && value.metadata.contractTests.every((item) => typeof item === 'string')
    && integer(value.metadata.backfillWindowDays, 0) && typeof value.metadata.watermarkRequired === 'boolean';
}

async function safeFailure(response: Response): Promise<Error> {
  let code = response.status === 404 ? 'INGESTION_QUALITY_FORBIDDEN' : 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    if (typeof value === 'object' && value !== null && 'code' in value
      && typeof value.code === 'string' && allowedFailures.has(value.code)) code = value.code;
  } catch { /* External text is deliberately ignored. */ }
  return new Error(code);
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
