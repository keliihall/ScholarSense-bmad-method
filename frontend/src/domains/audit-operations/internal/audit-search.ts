import type { CsrfProof } from '../../identity-access';

export type AuditSearchViewName = 'business' | 'technical';

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

const approvedFields = new Set([
  'recordId', 'ledgerSequence', 'occurredAt', 'outcome', 'factSchemaVersion', 'policyVersion',
  'retentionScheduleVersion', 'actorDisplayRef', 'objectDisplayRef', 'businessActionCategory',
  'businessObjectCategory', 'rolePackageSummary', 'projectionScope', 'producerModule', 'eventType',
  'reasonCode', 'traceId', 'integrityStatus', 'archiveStatus', 'projectionStatus',
  'sourceNetworkRecorded', 'archiveDigest', 'archiveVersionReference',
]);
const approvedResponseKeys = [
  'asOfSequence', 'dataCutoffAt', 'items', 'page', 'projectionStatus', 'projectionWatermark',
  'retentionScheduleVersion', 'roleFieldPolicyVersion', 'size', 'sourceLedgerHead', 'total',
].sort().join();

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
    if (!isSearchResponse(value)) throw new Error('AUDIT_SEARCH_RESPONSE_INVALID');
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

function validateRequest(query: AuditSearchRequest): void {
  if (!['business', 'technical'].includes(query.view)
    || !Number.isSafeInteger(query.page) || query.page < 0
    || !Number.isSafeInteger(query.size) || query.size < 1 || query.size > 100) {
    throw new Error('AUDIT_SEARCH_REQUEST_INVALID');
  }
}

function isSearchResponse(value: unknown): value is AuditSearchResponse {
  if (!isRecord(value) || Object.keys(value).sort().join() !== approvedResponseKeys
    || !Array.isArray(value.items)
    || !integers(value, ['page', 'size', 'total', 'asOfSequence', 'sourceLedgerHead', 'projectionWatermark'])
    || value.retentionScheduleVersion !== 'RS-1.0.0'
    || value.roleFieldPolicyVersion !== 'RFP-1.0.0'
    || !['current', 'degraded'].includes(String(value.projectionStatus))
    || typeof value.dataCutoffAt !== 'string' || !Number.isFinite(Date.parse(value.dataCutoffAt))) return false;
  return value.items.every((item) => isRecord(item) && isRecord(item.fields)
    && Object.keys(item.fields).every((field) => approvedFields.has(field))
    && Object.values(item.fields).every((field) => ['string', 'number', 'boolean'].includes(typeof field)));
}

function freezeResponse(value: AuditSearchResponse): AuditSearchResponse {
  const items = value.items.map((item) => Object.freeze({ fields: Object.freeze({ ...item.fields }) }));
  return Object.freeze({ ...value, items: Object.freeze(items) });
}

async function safeFailure(response: Response): Promise<Error> {
  let code = response.status === 403 ? 'AUDIT_SEARCH_FORBIDDEN' : 'AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    if (isRecord(value) && typeof value.code === 'string' && /^[A-Z][A-Z0-9_]{2,127}$/.test(value.code)) {
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
