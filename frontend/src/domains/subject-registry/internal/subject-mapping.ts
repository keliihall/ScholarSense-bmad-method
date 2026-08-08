import type { AuthorizedShellStatus, CsrfProof } from '../../identity-access';

export type SubjectMappingExceptionStatus = 'open' | 'repairing' | 'resolved' | 'dismissed';
export type SubjectMappingExceptionItem = Readonly<{
  exceptionId: string;
  status: SubjectMappingExceptionStatus;
  subjectOfficialRef: string | null;
  exceptionCode: string;
  sourceSystem: string;
  sourceOwner: string;
  detectedAt: string;
}>;
export type SubjectMappingExceptionPage = Readonly<{
  items: readonly SubjectMappingExceptionItem[];
  page: number;
  size: number;
  hasMore: boolean;
}>;
export type SubjectMappingExceptionDetail = Readonly<{
  item: SubjectMappingExceptionItem;
  aggregateVersion: number;
}>;
export type RepairReasonCode =
  | 'AUTHORITY_CORRECTION'
  | 'IDENTIFIER_REISSUED'
  | 'SUBJECT_MERGED'
  | 'SUBJECT_SPLIT'
  | 'MAPPING_REVOKED';
export type SubjectRelationType = 'alias' | 'merged-into' | 'split-into';
export type SubjectRepairDraft = Readonly<{
  reasonCode: RepairReasonCode;
  sourceWatermark: string;
  relationType: SubjectRelationType;
  sourceStudentRef: string;
  targetStudentRefs: readonly string[];
}>;
export type SubjectRepairCommand = SubjectRepairDraft & Readonly<{
  exceptionId: string;
  expectedAggregateVersion: number;
  idempotencyKey: string;
}>;
export type SubjectRepairResult = Readonly<{
  exceptionId: string;
  status: 'resolved';
  aggregateVersion: number;
  correctionId: string;
  jobIds: readonly string[];
  traceId: string;
}>;
export type SubjectRecomputeJob = Readonly<{
  jobId: string;
  status: 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';
  attemptNo: number;
  queuedAt: string;
  completedAt: string | null;
  resultCode: string | null;
  traceId: string;
}>;
export type SubjectRegistryIdentityGeneration = Readonly<{
  sessionPseudonym: string;
  sessionVersion: number;
  policyVersion: 'RFP-1.0.0';
  capabilitySignature: string;
}>;
export type SubjectRegistryClearReason =
  | 'refresh'
  | 'logout'
  | 'account-switch'
  | 'session-invalid'
  | 'authorization-revoked'
  | 'webview-destroyed';

const uuidV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const traceId = /^(?!0{32})[0-9a-f]{32}$/;
const statuses = new Set(['open', 'repairing', 'resolved', 'dismissed']);
const jobStatuses = new Set(['queued', 'running', 'succeeded', 'failed', 'cancelled']);
const reasons = new Set([
  'AUTHORITY_CORRECTION', 'IDENTIFIER_REISSUED', 'SUBJECT_MERGED',
  'SUBJECT_SPLIT', 'MAPPING_REVOKED',
]);
const relations = new Set(['alias', 'merged-into', 'split-into']);

export class SubjectRegistryResponseFailure extends Error {
  public constructor(
    code: string,
    public readonly retryable: boolean,
    public readonly traceId?: string,
    public readonly currentAggregateVersion?: number,
  ) {
    super(code);
    this.name = 'SubjectRegistryResponseFailure';
  }
}

export class SubjectMappingClient {
  public constructor(
    private readonly request: typeof fetch = globalThis.fetch.bind(globalThis),
    private readonly csrfProof?: (signal?: AbortSignal) => Promise<CsrfProof>,
  ) {}

  public async list(
    page: number,
    size: number,
    status?: SubjectMappingExceptionStatus,
    signal?: AbortSignal,
  ): Promise<SubjectMappingExceptionPage> {
    if (!integer(page, 0) || !integer(size, 1) || size > 100
      || (status !== undefined && !statuses.has(status))) {
      throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
    }
    const query = new URLSearchParams({ page: String(page), size: String(size) });
    if (status !== undefined) query.set('status', status);
    return this.response(
      `/api/v1/subject-mapping-exceptions?${query.toString()}`,
      { signal },
      isExceptionPage,
    );
  }

  public async detail(
    exceptionId: string,
    signal?: AbortSignal,
  ): Promise<SubjectMappingExceptionDetail> {
    requireUuidV7(exceptionId);
    const response = await this.raw(`/api/v1/subject-mapping-exceptions/${exceptionId}`, { signal });
    const value: unknown = await response.json();
    if (!isExceptionItem(value)) throw new Error('SUBJECT_REGISTRY_RESPONSE_INVALID');
    const aggregateVersion = parseAggregateVersion(response.headers.get('ETag'));
    return Object.freeze({ item: deepFreeze(value), aggregateVersion });
  }

  public async repair(
    command: SubjectRepairCommand,
    signal?: AbortSignal,
  ): Promise<SubjectRepairResult> {
    validateCommand(command);
    if (this.csrfProof === undefined) throw new Error('SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE');
    const csrf = await this.csrfProof(signal);
    return this.response(
      `/api/v1/subject-mapping-exceptions/${command.exceptionId}/repair`,
      {
        method: 'POST', signal,
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'Idempotency-Key': command.idempotencyKey,
          [csrf.headerName]: csrf.value,
        },
        body: JSON.stringify({
          expectedAggregateVersion: command.expectedAggregateVersion,
          reasonCode: command.reasonCode,
          sourceWatermark: command.sourceWatermark,
          relationType: command.relationType,
          sourceStudentRef: command.sourceStudentRef,
          targetStudentRefs: command.targetStudentRefs,
        }),
      },
      isRepairResult,
    );
  }

  public async job(jobId: string, signal?: AbortSignal): Promise<SubjectRecomputeJob> {
    requireUuidV7(jobId);
    return this.response(`/api/v1/subject-recompute-jobs/${jobId}`, { signal }, isRecomputeJob);
  }

  private async response<T>(
    input: string,
    init: RequestInit,
    validate: (value: unknown) => value is T,
  ): Promise<T> {
    const response = await this.raw(input, init);
    const value: unknown = await response.json();
    if (!validate(value)) throw new Error('SUBJECT_REGISTRY_RESPONSE_INVALID');
    return deepFreeze(value);
  }

  private async raw(input: string, init: RequestInit): Promise<Response> {
    const response = await this.request(input, {
      credentials: 'include',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json', ...init.headers },
      ...init,
    });
    if (!response.ok) throw await safeFailure(response);
    return response;
  }
}

/** Plaintext repair data and retry proof live only in this non-serializable component memory. */
export class SubjectRepairMemory {
  #draft?: SubjectRepairDraft;
  #command?: SubjectRepairCommand;

  public prepare(
    exceptionId: string,
    aggregateVersion: number,
    draft: SubjectRepairDraft,
    idempotencyKey: () => string,
  ): SubjectRepairCommand {
    validateDraft(draft);
    requireUuidV7(exceptionId);
    if (!integer(aggregateVersion, 1)) throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
    const snapshot = freezeDraft(draft);
    this.#draft = snapshot;
    if (this.#command !== undefined
      && this.#command.exceptionId === exceptionId
      && this.#command.expectedAggregateVersion === aggregateVersion
      && sameDraft(this.#command, snapshot)) {
      return this.#command;
    }
    const proof = idempotencyKey();
    if (typeof proof !== 'string' || proof.length < 16 || proof.length > 128) {
      throw new Error('SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE');
    }
    this.#command = Object.freeze({
      ...snapshot,
      exceptionId,
      expectedAggregateVersion: aggregateVersion,
      idempotencyKey: proof,
    });
    return this.#command;
  }

  public conflict(command: SubjectRepairCommand): void {
    if (this.#command === command) this.#command = undefined;
  }

  public complete(command: SubjectRepairCommand): void {
    if (this.#command === command) {
      this.#command = undefined;
      this.#draft = undefined;
    }
  }

  public currentDraft(): SubjectRepairDraft | undefined { return this.#draft; }
  public currentCommand(): SubjectRepairCommand | undefined { return this.#command; }
  public clear(_reason: SubjectRegistryClearReason): void {
    this.#draft = undefined;
    this.#command = undefined;
  }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

type Mutable<T> = { value: T };
export function clearSubjectRegistryIdentityBoundary(
  active: Mutable<AbortController | undefined>,
  page: Mutable<SubjectMappingExceptionPage | undefined>,
  detail: Mutable<SubjectMappingExceptionDetail | undefined>,
  memory: SubjectRepairMemory,
  reason: SubjectRegistryClearReason = 'authorization-revoked',
): void {
  active.value?.abort();
  active.value = undefined;
  page.value = undefined;
  detail.value = undefined;
  memory.clear(reason);
}

export function sameSubjectRegistryIdentityGeneration(
  requested: SubjectRegistryIdentityGeneration,
  current: SubjectRegistryIdentityGeneration | undefined,
): boolean {
  return current !== undefined
    && requested.sessionPseudonym === current.sessionPseudonym
    && requested.sessionVersion === current.sessionVersion
    && requested.policyVersion === current.policyVersion
    && requested.capabilitySignature === current.capabilitySignature;
}

export function hasUsableSubjectRegistryAuthorization(
  authenticated: boolean,
  status: AuthorizedShellStatus,
  hasShell: boolean,
  signature: string,
): boolean {
  return authenticated && (status === 'ready' || status === 'degraded') && hasShell
    && signature === 'available|available';
}

export function subjectMappingQueryOptions(
  context: Readonly<{ sessionVersion: number; policyVersion: 'RFP-1.0.0'; page: number }>,
  queryFn: () => Promise<SubjectMappingExceptionPage>,
) {
  return Object.freeze({
    queryKey: Object.freeze([
      'subject-registry', 'subject-mapping-exceptions', Object.freeze({ ...context }),
    ] as const),
    queryFn,
    staleTime: 0,
    gcTime: 0,
    retry: false,
    networkMode: 'online' as const,
  });
}

function isExceptionPage(value: unknown): value is SubjectMappingExceptionPage {
  return exact(value, ['hasMore', 'items', 'page', 'size'])
    && Array.isArray(value.items) && value.items.every(isExceptionItem)
    && integer(value.page, 0) && integer(value.size, 1) && value.size <= 100
    && value.items.length <= value.size && typeof value.hasMore === 'boolean'
    && (!value.hasMore || value.items.length === value.size);
}

function isExceptionItem(value: unknown): value is SubjectMappingExceptionItem {
  return exact(value, [
    'detectedAt', 'exceptionCode', 'exceptionId', 'sourceOwner',
    'sourceSystem', 'status', 'subjectOfficialRef',
  ])
    && typeof value.exceptionId === 'string' && uuidV7.test(value.exceptionId)
    && typeof value.status === 'string' && statuses.has(value.status)
    && (value.subjectOfficialRef === null || bounded(value.subjectOfficialRef, 1, 128))
    && bounded(value.exceptionCode, 1, 128)
    && bounded(value.sourceSystem, 1, 128)
    && bounded(value.sourceOwner, 1, 128)
    && validInstant(value.detectedAt);
}

function isRepairResult(value: unknown): value is SubjectRepairResult {
  return exact(value, [
    'aggregateVersion', 'correctionId', 'exceptionId', 'jobIds', 'status', 'traceId',
  ])
    && typeof value.exceptionId === 'string' && uuidV7.test(value.exceptionId)
    && value.status === 'resolved'
    && integer(value.aggregateVersion, 2)
    && typeof value.correctionId === 'string' && uuidV7.test(value.correctionId)
    && Array.isArray(value.jobIds) && value.jobIds.length <= 100
    && value.jobIds.every((id) => typeof id === 'string' && uuidV7.test(id))
    && new Set(value.jobIds).size === value.jobIds.length
    && typeof value.traceId === 'string' && traceId.test(value.traceId);
}

function isRecomputeJob(value: unknown): value is SubjectRecomputeJob {
  return exact(value, [
    'attemptNo', 'completedAt', 'jobId', 'queuedAt', 'resultCode', 'status', 'traceId',
  ])
    && typeof value.jobId === 'string' && uuidV7.test(value.jobId)
    && typeof value.status === 'string' && jobStatuses.has(value.status)
    && integer(value.attemptNo, 0)
    && validInstant(value.queuedAt)
    && (value.completedAt === null || validInstant(value.completedAt))
    && (value.resultCode === null || bounded(value.resultCode, 1, 128))
    && typeof value.traceId === 'string' && traceId.test(value.traceId);
}

function validateCommand(command: SubjectRepairCommand): void {
  if (!exact(command, [
    'exceptionId', 'expectedAggregateVersion', 'idempotencyKey', 'reasonCode',
    'relationType', 'sourceStudentRef', 'sourceWatermark', 'targetStudentRefs',
  ])) throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
  requireUuidV7(command.exceptionId);
  if (!integer(command.expectedAggregateVersion, 1)
    || !bounded(command.idempotencyKey, 16, 128)) {
    throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
  }
  validateDraft(command);
}

function validateDraft(draft: SubjectRepairDraft): void {
  if (!reasons.has(draft.reasonCode) || !relations.has(draft.relationType)
    || !bounded(draft.sourceWatermark, 1, 128)) {
    throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
  }
  requireUuidV7(draft.sourceStudentRef);
  if (!Array.isArray(draft.targetStudentRefs) || draft.targetStudentRefs.length > 16
    || !draft.targetStudentRefs.every((id) => typeof id === 'string' && uuidV7.test(id))
    || new Set(draft.targetStudentRefs).size !== draft.targetStudentRefs.length) {
    throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
  }
  const expected = draft.reasonCode === 'SUBJECT_MERGED' ? 'merged-into'
    : draft.reasonCode === 'SUBJECT_SPLIT' ? 'split-into' : 'alias';
  if (draft.relationType !== expected) throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
}

function freezeDraft(draft: SubjectRepairDraft): SubjectRepairDraft {
  return Object.freeze({ ...draft, targetStudentRefs: Object.freeze([...draft.targetStudentRefs]) });
}

function sameDraft(left: SubjectRepairDraft, right: SubjectRepairDraft): boolean {
  return left.reasonCode === right.reasonCode
    && left.sourceWatermark === right.sourceWatermark
    && left.relationType === right.relationType
    && left.sourceStudentRef === right.sourceStudentRef
    && left.targetStudentRefs.length === right.targetStudentRefs.length
    && left.targetStudentRefs.every((value, index) => value === right.targetStudentRefs[index]);
}

function parseAggregateVersion(value: string | null): number {
  const matched = value?.match(/^"([1-9][0-9]*)"$/);
  const version = matched === null || matched === undefined ? Number.NaN : Number(matched[1]);
  if (!Number.isSafeInteger(version)) throw new Error('SUBJECT_REGISTRY_RESPONSE_INVALID');
  return version;
}

async function safeFailure(response: Response): Promise<SubjectRegistryResponseFailure> {
  const fallback = response.status === 404
    ? 'SUBJECT_REGISTRY_FORBIDDEN'
    : response.status === 409
      ? 'SUBJECT_REGISTRY_VERSION_CONFLICT'
      : 'SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    const baseKeys = ['code', 'fieldErrors', 'message', 'traceId'];
    const conflictKeys = [...baseKeys, 'currentAggregateVersion'];
    const validShape = exact(value, baseKeys) || exact(value, conflictKeys);
    if (validShape && bounded(value.code, 3, 128) && /^[A-Z0-9_]+$/.test(value.code)
      && bounded(value.message, 1, 256)
      && typeof value.traceId === 'string' && traceId.test(value.traceId)
      && Array.isArray(value.fieldErrors)
      && (value.currentAggregateVersion === undefined || integer(value.currentAggregateVersion, 1))) {
      return new SubjectRegistryResponseFailure(
        value.code, response.status >= 500, value.traceId,
        value.currentAggregateVersion as number | undefined,
      );
    }
  } catch {
    // Error bodies are never surfaced if they do not match the approved envelope.
  }
  return new SubjectRegistryResponseFailure(fallback, response.status >= 500);
}

function requireUuidV7(value: string): void {
  if (typeof value !== 'string' || !uuidV7.test(value)) {
    throw new Error('SUBJECT_REGISTRY_REQUEST_INVALID');
  }
}

function integer(value: unknown, minimum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum;
}

function bounded(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === 'string' && value.length >= minimum && value.length <= maximum;
}

function validInstant(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value)) && /(?:Z|[+-][0-9]{2}:[0-9]{2})$/.test(value);
}

function exact(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.getPrototypeOf(value) === Object.prototype
    && Object.keys(value).sort().join() === [...keys].sort().join();
}

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  Object.freeze(value);
  for (const nested of Object.values(value)) deepFreeze(nested);
  return value;
}
