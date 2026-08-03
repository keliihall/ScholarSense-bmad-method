import type { AuthorizedShellStatus, CsrfProof } from '../../identity-access';
import type { QueryClient } from '@tanstack/vue-query';

export type AuditSearchViewName = 'business' | 'technical';

export function hasUsableAuditSearchAuthorization(
  authenticated: boolean,
  status: AuthorizedShellStatus,
  hasCurrentShell: boolean,
  capabilitySignature: string,
): boolean {
  return authenticated
    && (status === 'ready' || status === 'degraded')
    && hasCurrentShell
    && capabilitySignature === 'available|available';
}

export type AuditSearchRequest = Readonly<{
  view: AuditSearchViewName;
  actorRef?: string;
  objectType?: string;
  objectRef?: string;
  action?: string;
  occurredFrom?: string;
  occurredTo?: string;
  outcome?: string;
  traceId?: string;
  page: number;
  size: number;
  asOfSequence?: number;
}>;

export type AuditSearchItem = Readonly<{ fields: Readonly<Record<string, string | number | boolean>> }>;
export type AuditSearchResponse = Readonly<{
  items: readonly AuditSearchItem[];
  page: number;
  size: number;
  total: number;
  asOfSequence: number;
  sourceLedgerHead: number;
  projectionWatermark: number;
  dataCutoffAt: string;
  retentionScheduleVersion: 'RS-1.0.0';
  roleFieldPolicyVersion: 'RFP-1.0.0';
  projectionStatus: 'current' | 'degraded';
}>;

const businessFieldOrder = Object.freeze([
  'recordId', 'ledgerSequence', 'occurredAt', 'outcome', 'factSchemaVersion', 'policyVersion',
  'retentionScheduleVersion', 'actorDisplayRef', 'objectDisplayRef', 'businessActionCategory',
  'businessObjectCategory', 'rolePackageSummary', 'projectionScope', 'producerModule', 'eventType',
  'reasonCode', 'traceId', 'integrityStatus', 'archiveStatus', 'projectionStatus',
  'sourceNetworkRecorded',
] as const);
const technicalFieldOrder = Object.freeze(businessFieldOrder.filter(
  (field) => !['actorDisplayRef', 'objectDisplayRef'].includes(field),
));
const approvedFieldsByView: Readonly<Record<AuditSearchViewName, ReadonlySet<string>>> = Object.freeze({
  business: new Set(businessFieldOrder),
  technical: new Set(technicalFieldOrder),
});
const fieldTypes: Readonly<Record<string, 'string' | 'number' | 'boolean'>> = Object.freeze({
  recordId: 'string', ledgerSequence: 'number', occurredAt: 'string', outcome: 'string',
  factSchemaVersion: 'string', policyVersion: 'string', retentionScheduleVersion: 'string',
  actorDisplayRef: 'string', objectDisplayRef: 'string', businessActionCategory: 'string',
  businessObjectCategory: 'string', rolePackageSummary: 'string', projectionScope: 'string',
  producerModule: 'string', eventType: 'string', reasonCode: 'string', traceId: 'string',
  integrityStatus: 'string', archiveStatus: 'string', projectionStatus: 'string',
  sourceNetworkRecorded: 'boolean',
});
const approvedResponseKeys = [
  'asOfSequence', 'dataCutoffAt', 'items', 'page', 'projectionStatus', 'projectionWatermark',
  'retentionScheduleVersion', 'roleFieldPolicyVersion', 'size', 'sourceLedgerHead', 'total',
].sort().join();
const allowedServerFailureCodes = new Set([
  'AUDIT_SEARCH_FORBIDDEN',
  'AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE',
  'AUDIT_SEARCH_PROJECTION_NOT_CAUGHT_UP',
  'AUDIT_SEARCH_AUDIT_COMMIT_FAILED',
  'AUDIT_SEARCH_INVALID_REQUEST',
  'AUDIT_SEARCH_RESPONSE_INVALID',
]);

export class AuditSearchClient {
  public constructor(
    private readonly request: typeof fetch = (input, init) => globalThis.fetch(input, init),
    private readonly csrfProof: (signal?: AbortSignal) => Promise<CsrfProof>,
  ) {}

  public async search(query: AuditSearchRequest, signal?: AbortSignal): Promise<AuditSearchResponse> {
    validateRequest(query);
    const csrf = await this.csrfProof(signal);
    const response = await this.request('/api/v1/audit-records/search', {
      method: 'POST', credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer', signal,
      headers: {
        Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.value,
      },
      body: JSON.stringify(query),
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!isSearchResponse(value, query.view)) throw new Error('AUDIT_SEARCH_RESPONSE_INVALID');
    return freezeResponse(value);
  }
}

export type SensitiveClearReason = 'refresh' | 'logout' | 'account-switch' | 'session-invalid';

/** Raw references have no serialization surface and never become router/query-cache keys. */
export class AuditSearchMemoryState {
  #actorRef?: string;
  #objectRef?: string;

  public setSensitiveFilters(actorRef?: string, objectRef?: string): void {
    this.#actorRef = normalized(actorRef);
    this.#objectRef = normalized(objectRef);
  }

  public sensitiveFilters(): Readonly<{ actorRef?: string; objectRef?: string }> {
    return Object.freeze({
      ...(this.#actorRef === undefined ? {} : { actorRef: this.#actorRef }),
      ...(this.#objectRef === undefined ? {} : { objectRef: this.#objectRef }),
    });
  }

  public clearSensitive(_reason: SensitiveClearReason): void {
    this.#actorRef = undefined;
    this.#objectRef = undefined;
  }

  public toJSON(): Readonly<Record<string, never>> {
    return Object.freeze({});
  }
}

type MutableValue<T> = { value: T };

/** Clears every response-bearing ref before a changed identity can trigger a new request. */
export function clearAuditSearchIdentityBoundary(
  active: MutableValue<AbortController | undefined>,
  result: MutableValue<AuditSearchResponse | undefined>,
  asOfSequence: MutableValue<number | undefined>,
): void {
  active.value?.abort();
  active.value = undefined;
  result.value = undefined;
  asOfSequence.value = undefined;
}

export type AuditSearchQueryContext = Readonly<{
  sessionVersion: number;
  policyVersion: 'RFP-1.0.0';
  view: AuditSearchViewName;
}>;

/** Sensitive filters never enter this key; cached data is stale and collectible immediately. */
export function auditSearchQueryOptions(
  context: AuditSearchQueryContext,
  queryFn: () => Promise<AuditSearchResponse>,
) {
  return Object.freeze({
    queryKey: Object.freeze([
      'audit-operations',
      'sensitive-search',
      Object.freeze({ ...context }),
    ] as const),
    queryFn,
    staleTime: 0,
    gcTime: 0,
    retry: false,
    networkMode: 'online' as const,
  });
}

export function clearAuditSearchQueryBoundary(client: Pick<QueryClient, 'removeQueries'>): void {
  client.removeQueries({ queryKey: ['audit-operations', 'sensitive-search'], exact: false });
}

/** Uses the contract order, filtered to keys that are actually present in this response. */
export function auditSearchColumns(
  view: AuditSearchViewName,
  items: readonly AuditSearchItem[],
): readonly string[] {
  const present = new Set(items.flatMap((item) => Object.keys(item.fields)));
  const order = view === 'business' ? businessFieldOrder : technicalFieldOrder;
  return Object.freeze(order.filter((field) => present.has(field)));
}

export type AuditSearchValuePresentation = Readonly<{
  masked: boolean;
  visual: string;
  accessibleName?: '已脱敏';
}>;

export function auditSearchValuePresentation(
  view: AuditSearchViewName,
  field: string,
  value: string | number | boolean,
): AuditSearchValuePresentation {
  if (maskedFields(view).has(field)) {
    return Object.freeze({
      masked: true,
      visual: String(expectedMask(field)),
      accessibleName: '已脱敏' as const,
    });
  }
  return Object.freeze({ masked: false, visual: String(value) });
}

function validateRequest(query: AuditSearchRequest): void {
  if (!['business', 'technical'].includes(query.view)
    || !Number.isSafeInteger(query.page) || query.page < 0
    || !Number.isSafeInteger(query.size) || query.size < 1 || query.size > 100) {
    throw new Error('AUDIT_SEARCH_REQUEST_INVALID');
  }
}

function isSearchResponse(value: unknown, view: AuditSearchViewName): value is AuditSearchResponse {
  if (!isRecord(value) || Object.keys(value).sort().join() !== approvedResponseKeys
    || !Array.isArray(value.items)
    || !integers(value, ['page', 'size', 'total', 'asOfSequence', 'sourceLedgerHead', 'projectionWatermark'])
    || value.retentionScheduleVersion !== 'RS-1.0.0'
    || value.roleFieldPolicyVersion !== 'RFP-1.0.0'
    || !['current', 'degraded'].includes(String(value.projectionStatus))
    || typeof value.dataCutoffAt !== 'string' || !Number.isFinite(Date.parse(value.dataCutoffAt))) return false;
  return value.items.every((item) => isRecord(item) && isRecord(item.fields)
    && Object.keys(item).length === 1 && Object.hasOwn(item, 'fields')
    && Object.keys(item.fields).every((field) => approvedFieldsByView[view].has(field))
    && Object.entries(item.fields).every(([field, fieldValue]) =>
      validFieldType(field, fieldValue)
      && (!maskedFields(view).has(field) || fieldValue === expectedMask(field))));
}

function validFieldType(field: string, value: unknown): boolean {
  const expected = fieldTypes[field];
  if (expected === undefined || typeof value !== expected) return false;
  return expected !== 'number' || (Number.isSafeInteger(value) && Number(value) >= 0);
}

function maskedFields(view: AuditSearchViewName): ReadonlySet<string> {
  return view === 'business'
    ? new Set([
      'actorDisplayRef', 'objectDisplayRef', 'producerModule', 'eventType', 'reasonCode', 'traceId',
      'integrityStatus', 'archiveStatus', 'projectionStatus', 'sourceNetworkRecorded',
    ])
    : new Set([
      'businessActionCategory', 'businessObjectCategory', 'rolePackageSummary', 'projectionScope',
    ]);
}

function expectedMask(field: string): string | boolean {
  if (['actorDisplayRef', 'objectDisplayRef'].includes(field)) return '[MASKED-IDENTITY]';
  if (['businessActionCategory', 'businessObjectCategory', 'rolePackageSummary', 'projectionScope']
    .includes(field)) return '[MASKED-CATEGORY]';
  if (field === 'reasonCode') return '[MASKED-CODE]';
  if (field === 'sourceNetworkRecorded') return false;
  return '[MASKED-TECHNICAL]';
}

function freezeResponse(value: AuditSearchResponse): AuditSearchResponse {
  const items = value.items.map((item) => Object.freeze({ fields: Object.freeze({ ...item.fields }) }));
  return Object.freeze({ ...value, items: Object.freeze(items) });
}

async function safeFailure(response: Response): Promise<Error> {
  let code = response.status === 403 ? 'AUDIT_SEARCH_FORBIDDEN' : 'AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    if (isRecord(value) && typeof value.code === 'string'
      && allowedServerFailureCodes.has(value.code)) {
      code = value.code;
    }
  } catch { /* External text is deliberately ignored. */ }
  return new Error(code);
}

function integers(value: Record<string, unknown>, fields: readonly string[]): boolean {
  return fields.every((field) => Number.isSafeInteger(value[field]) && Number(value[field]) >= 0);
}

function normalized(value?: string): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
