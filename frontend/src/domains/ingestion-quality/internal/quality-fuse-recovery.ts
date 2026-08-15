import type { CsrfProof } from '../../identity-access';

export type QualityFuseRecoveryStatus = 'requested' | 'validating' | 'validation-succeeded'
  | 'validation-failed' | 'approval-pending' | 'approval-approved' | 'executed' | 'cancelled';

export type QualityFuseRecoveryRequest = Readonly<{
  recoveryRequestId: string;
  requestVersion: number;
  taskId: string;
  status: QualityFuseRecoveryStatus;
  validationJobId: string | null;
  validationStatus: 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled' | null;
  validationResultDigest: string | null;
  previewId: string | null;
  previewVersion: number | null;
  previewDigest: string | null;
  previewExpiresAt: string | null;
  previewSummary: QualityFuseRecoveryPreviewSummary | null;
  approvalId: string | null;
  approvalVersion: number | null;
  approvalStatus: 'pending' | 'approved' | 'rejected' | 'expired' | 'cancelled' | null;
  traceId: string;
}>;

export type QualityFuseRecoveryPreviewSummary = Readonly<{
  qualityRecoveryPolicyVersion: 'QRP-1.0.0';
  requiredConsecutivePassedBatches: number;
  actualConsecutivePassedBatches: number;
  observationDuration: 'PT60M' | 'PT24H';
  backfillStatus: 'succeeded' | 'failed' | 'unavailable';
  backfillLookbackDays: 90;
  reconciliationExpectedCount: number;
  reconciliationActualCount: number;
  reconciliationMismatchCount: number;
  samplePopulationCount: number;
  sampleSelectedCount: number;
  sampleMismatchCount: number;
  sampleStrataCount: number;
  impactAlreadyExpiredCount: number;
  impactPotentiallyActionableCount: number;
  impactExpectedToExpireCount: number;
  finalActionabilityOwnerStory: '2.5c';
}>;

export type QualityFuseRecoveryExecution = Readonly<{
  recoveryRequestId: string;
  taskId: string;
  episodeId: string;
  state: 'recovering';
  transitionApplied: true;
  ownerCommittedAt: string;
  traceId: string;
}>;

export type QualityRecoveryObservation = Readonly<{
  recoveryId: string; recoveryVersion: number; taskId: string; taskVersion: number;
  generation: number;
  sourceClass: 'streaming' | 'daily-batch'; policyVersion: 'QRP-1.0.0';
  policyDigest: string;
  status: 'observing' | 'ready' | 'relapsed' | 'policy-drift' | 'finalized';
  finalizationState: 'not-requested' | 'approval-pending' | 'approval-approved'
    | 'approval-rejected' | 'cancelled' | 'finalizing' | 'executed';
  approvalId: string | null; approvalVersion: number | null;
  consecutivePassedBatches: number; requiredPassedBatches: number;
  observedDurationMicros: number; requiredDurationMicros: number;
  observationDuration: 'PT60M' | 'P1D'; watermark: string | null;
  recoveringStartedAt: string; lastObservedAt: string; latestActionableAt: string | null;
  failedMembers: readonly string[]; failureReasonCode: string | null;
  eligibilityStatus: 'fused' | 'recovering' | 'eligible';
  taskStatus: 'open' | 'closed'; taskClosedAt: string | null;
  ownerResultDigest: string | null;
  deliveryStatus: 'pending' | 'retrying' | 'confirmed' | 'failed';
  deliveryAttempt: number; deliveryNextAttemptAt: string | null;
  eligibleForHandoffWindowCount: number; historyOnlyWindowCount: number;
  traceId: string;
}>;

export type QualityRecoveryFinalization = Readonly<{
  recoveryId: string; recoveryVersion: number; taskId: string; taskVersion: number;
  observationStatus: 'ready' | 'finalized';
  finalizationState: 'approval-pending' | 'approval-approved' | 'approval-rejected'
    | 'cancelled' | 'executed';
  approvalId: string; approvalVersion: number; policyVersion: 'QRP-1.0.0';
  finalPreviewDigest: string; observationDecisionDigest: string;
  finalObservationWatermark: string; traceId: string;
}>;

export type QualityRecoveryFinalExecution = Readonly<{
  recoveryId: string; taskId: string; generation: number; eligibilityStatus: 'eligible';
  episodeStatus: 'closed'; taskStatus: 'closed'; taskVersion: number;
  recoveryCompletedAt: string; windowOutcomesDigest: string; ownerResultDigest: string;
  deliveryStatus: 'pending' | 'retrying' | 'confirmed' | 'failed'; traceId: string;
}>;

export class QualityFuseRecoveryFailure extends Error {
  public constructor(
    public readonly code: string,
    public readonly currentVersion: number | null = null,
  ) { super(code); }
}

export class QualityFuseRecoveryClient {
  public constructor(
    private readonly request: typeof fetch = globalThis.fetch.bind(globalThis),
    private readonly csrfProof?: (signal?: AbortSignal) => Promise<CsrfProof>,
  ) {}

  public async create(
    taskId: string, expectedTaskVersion: number, key: string, signal?: AbortSignal,
  ): Promise<QualityFuseRecoveryRequest> {
    return this.command(`/api/v1/quality-recovery-tasks/${taskId}/recovery-requests`, key,
      { expectedTaskVersion, reasonCode: 'QUALITY_EVIDENCE_REVALIDATION_REQUESTED' },
      isRequest, signal);
  }

  public async status(
    requestId: string, signal?: AbortSignal,
  ): Promise<QualityFuseRecoveryRequest> {
    requireUuid(requestId);
    return this.response(`/api/v1/quality-recovery-requests/${requestId}`, {
      method: 'GET', signal,
    }, isRequest);
  }

  public async requestApproval(
    requestId: string, expectedVersion: number, key: string, signal?: AbortSignal,
  ): Promise<QualityFuseRecoveryRequest> {
    requireUuid(requestId);
    return this.command(`/api/v1/quality-recovery-requests/${requestId}/approval-requests`,
      key, { expectedVersion }, isRequest, signal);
  }

  public async decide(
    requestId: string, expectedVersion: number, expectedApprovalVersion: number,
    decision: 'approve' | 'reject' | 'cancel', key: string, signal?: AbortSignal,
  ): Promise<QualityFuseRecoveryRequest> {
    requireUuid(requestId);
    return this.command(`/api/v1/quality-recovery-requests/${requestId}/approval-decisions`,
      key, { expectedVersion, expectedApprovalVersion, decision }, isRequest, signal);
  }

  public async execute(
    requestId: string, expectedVersion: number, key: string, signal?: AbortSignal,
  ): Promise<QualityFuseRecoveryExecution> {
    requireUuid(requestId);
    return this.command(`/api/v1/quality-recovery-requests/${requestId}/execute`, key,
      { expectedVersion }, isExecution, signal);
  }

  public async observation(
    taskId: string, signal?: AbortSignal,
  ): Promise<QualityRecoveryObservation> {
    requireUuid(taskId);
    return this.response(`/api/v1/quality-recovery-tasks/${taskId}/observation`, {
      method: 'GET', signal,
    }, isObservation);
  }

  public async requestFinalApproval(
    requestId: string, expectedRecoveryVersion: number, expectedTaskVersion: number,
    finalObservationWatermark: string, key: string, signal?: AbortSignal,
  ): Promise<QualityRecoveryFinalization> {
    requireUuid(requestId);
    return this.command(
      `/api/v1/quality-recovery-requests/${requestId}/final-approval-requests`, key,
      { expectedRecoveryVersion, expectedTaskVersion, finalObservationWatermark },
      isFinalization, signal);
  }

  public async decideFinalApproval(
    requestId: string, expectedApprovalVersion: number,
    decision: 'approve' | 'reject' | 'cancel', key: string, signal?: AbortSignal,
  ): Promise<QualityRecoveryFinalization> {
    requireUuid(requestId);
    return this.command(
      `/api/v1/quality-recovery-requests/${requestId}/final-approval-decisions`, key,
      { expectedApprovalVersion, decision }, isFinalization, signal);
  }

  public async finalize(
    requestId: string, expectedRecoveryVersion: number, expectedTaskVersion: number,
    finalObservationWatermark: string, key: string, signal?: AbortSignal,
  ): Promise<QualityRecoveryFinalExecution> {
    requireUuid(requestId);
    return this.command(`/api/v1/quality-recovery-requests/${requestId}/finalize`, key,
      { expectedRecoveryVersion, expectedTaskVersion, finalObservationWatermark },
      isFinalExecution, signal);
  }

  private async command<T>(
    url: string, key: string | undefined, body: Record<string, unknown>,
    validate: (value: unknown) => value is T, signal?: AbortSignal,
  ): Promise<T> {
    if (this.csrfProof === undefined) throw new QualityFuseRecoveryFailure(
      'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE');
    if (key !== undefined && (key.length < 1 || key.length > 128)) throw invalid();
    const csrf = await this.csrfProof(signal);
    return this.response(url, {
      method: 'POST', signal,
      headers: {
        'Content-Type': 'application/json', [csrf.headerName]: csrf.value,
        ...(key === undefined ? {} : { 'Idempotency-Key': key }),
      },
      body: JSON.stringify(body),
    }, validate);
  }

  private async response<T>(
    url: string, init: RequestInit, validate: (value: unknown) => value is T,
  ): Promise<T> {
    const response = await this.request(url, {
      credentials: 'include', cache: 'no-store', referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json', ...init.headers }, ...init,
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!validate(value)) throw new QualityFuseRecoveryFailure(
      'INGESTION_QUALITY_RESPONSE_INVALID');
    return deepFreeze(value);
  }
}

export class QualityFuseRecoveryMemory {
  #request?: QualityFuseRecoveryRequest;
  public accept(value: QualityFuseRecoveryRequest): void { this.#request = value; }
  public current(): QualityFuseRecoveryRequest | undefined { return this.#request; }
  public clear(): void { this.#request = undefined; }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

export function qualityFuseRecoveryQueryOptions(
  identityGeneration: Readonly<Record<string, string | number>>,
  queryFn: () => Promise<QualityFuseRecoveryRequest>,
) {
  return Object.freeze({
    queryKey: Object.freeze([
      'ingestion-quality', 'quality-fuse-recovery', Object.freeze({ ...identityGeneration }),
    ] as const),
    queryFn, staleTime: 0, gcTime: 0, retry: false, networkMode: 'online' as const,
  });
}

function isRequest(value: unknown): value is QualityFuseRecoveryRequest {
  return exact(value, [
    'approvalId', 'approvalStatus', 'approvalVersion', 'previewDigest', 'previewExpiresAt',
    'previewId', 'previewSummary', 'previewVersion', 'recoveryRequestId', 'requestVersion', 'status', 'taskId',
    'traceId', 'validationJobId', 'validationResultDigest', 'validationStatus',
  ]) && uuid.test(value.recoveryRequestId) && uuid.test(value.taskId)
    && integer(value.requestVersion, 1) && statuses.has(value.status)
    && nullableUuid(value.validationJobId) && nullableText(value.validationStatus)
    && nullableDigest(value.validationResultDigest) && nullableUuid(value.previewId)
    && nullableInteger(value.previewVersion) && nullableDigest(value.previewDigest)
    && nullableInstant(value.previewExpiresAt) && isPreviewSummary(value.previewSummary)
    && nullableUuid(value.approvalId)
    && nullableInteger(value.approvalVersion) && nullableText(value.approvalStatus)
    && trace.test(value.traceId);
}

function isPreviewSummary(value: unknown): value is QualityFuseRecoveryPreviewSummary | null {
  if (value === null) return true;
  return exact(value, [
    'actualConsecutivePassedBatches', 'backfillLookbackDays', 'backfillStatus',
    'finalActionabilityOwnerStory', 'impactAlreadyExpiredCount',
    'impactExpectedToExpireCount', 'impactPotentiallyActionableCount',
    'observationDuration', 'qualityRecoveryPolicyVersion',
    'reconciliationActualCount', 'reconciliationExpectedCount',
    'reconciliationMismatchCount', 'requiredConsecutivePassedBatches',
    'sampleMismatchCount', 'samplePopulationCount', 'sampleSelectedCount',
    'sampleStrataCount',
  ]) && value.qualityRecoveryPolicyVersion === 'QRP-1.0.0'
    && integer(value.requiredConsecutivePassedBatches, 1)
    && integer(value.actualConsecutivePassedBatches, 0)
    && (value.observationDuration === 'PT60M' || value.observationDuration === 'PT24H')
    && ['succeeded', 'failed', 'unavailable'].includes(value.backfillStatus)
    && value.backfillLookbackDays === 90
    && integer(value.reconciliationExpectedCount, 0)
    && integer(value.reconciliationActualCount, 0)
    && integer(value.reconciliationMismatchCount, 0)
    && integer(value.samplePopulationCount, 0) && integer(value.sampleSelectedCount, 0)
    && integer(value.sampleMismatchCount, 0) && integer(value.sampleStrataCount, 0)
    && integer(value.impactAlreadyExpiredCount, 0)
    && integer(value.impactPotentiallyActionableCount, 0)
    && integer(value.impactExpectedToExpireCount, 0)
    && value.finalActionabilityOwnerStory === '2.5c';
}

function isExecution(value: unknown): value is QualityFuseRecoveryExecution {
  return exact(value, [
    'episodeId', 'ownerCommittedAt', 'recoveryRequestId', 'state', 'taskId',
    'traceId', 'transitionApplied',
  ]) && uuid.test(value.recoveryRequestId) && uuid.test(value.taskId)
    && uuid.test(value.episodeId) && value.state === 'recovering'
    && value.transitionApplied === true && validInstant(value.ownerCommittedAt)
    && trace.test(value.traceId);
}

function isObservation(value: unknown): value is QualityRecoveryObservation {
  return exact(value, [
    'consecutivePassedBatches', 'deliveryAttempt', 'deliveryNextAttemptAt',
    'deliveryStatus', 'eligibilityStatus', 'eligibleForHandoffWindowCount',
    'approvalId', 'approvalVersion',
    'failureReasonCode', 'finalizationState', 'generation', 'historyOnlyWindowCount',
    'failedMembers',
    'lastObservedAt', 'latestActionableAt', 'observationDuration',
    'observedDurationMicros', 'ownerResultDigest', 'policyDigest', 'policyVersion',
    'recoveringStartedAt', 'recoveryId', 'recoveryVersion', 'requiredDurationMicros',
    'requiredPassedBatches', 'sourceClass', 'status', 'taskClosedAt', 'taskId',
    'taskStatus', 'taskVersion', 'traceId', 'watermark',
  ]) && uuid.test(value.recoveryId) && integer(value.recoveryVersion, 1)
    && uuid.test(value.taskId) && integer(value.taskVersion, 1) && integer(value.generation, 1)
    && ['streaming', 'daily-batch'].includes(value.sourceClass)
    && value.policyVersion === 'QRP-1.0.0' && digest.test(value.policyDigest)
    && ['observing', 'ready', 'relapsed', 'policy-drift', 'finalized'].includes(value.status)
    && ['not-requested', 'approval-pending', 'approval-approved', 'approval-rejected',
      'cancelled', 'finalizing', 'executed']
      .includes(value.finalizationState)
    && nullableUuid(value.approvalId) && nullableInteger(value.approvalVersion)
    && ((value.approvalId === null) === (value.approvalVersion === null))
    && ((value.finalizationState === 'not-requested')
      === (value.approvalId === null && value.approvalVersion === null))
    && integer(value.consecutivePassedBatches, 0) && integer(value.requiredPassedBatches, 1)
    && integer(value.observedDurationMicros, 0) && integer(value.requiredDurationMicros, 1)
    && ['PT60M', 'P1D'].includes(value.observationDuration)
    && nullableText(value.watermark) && validInstant(value.recoveringStartedAt)
    && validInstant(value.lastObservedAt) && nullableInstant(value.latestActionableAt)
    && Array.isArray(value.failedMembers) && value.failedMembers.length <= 128
    && value.failedMembers.every((member) => typeof member === 'string'
      && /^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$/.test(member))
    && new Set(value.failedMembers).size === value.failedMembers.length
    && nullableText(value.failureReasonCode)
    && ['fused', 'recovering', 'eligible'].includes(value.eligibilityStatus)
    && ['open', 'closed'].includes(value.taskStatus) && nullableInstant(value.taskClosedAt)
    && nullableDigest(value.ownerResultDigest)
    && ['pending', 'retrying', 'confirmed', 'failed'].includes(value.deliveryStatus)
    && integer(value.deliveryAttempt, 0) && nullableInstant(value.deliveryNextAttemptAt)
    && integer(value.eligibleForHandoffWindowCount, 0)
    && integer(value.historyOnlyWindowCount, 0) && trace.test(value.traceId);
}

function isFinalization(value: unknown): value is QualityRecoveryFinalization {
  return exact(value, [
    'approvalId', 'approvalVersion', 'finalObservationWatermark', 'finalPreviewDigest',
    'finalizationState', 'observationDecisionDigest', 'observationStatus',
    'policyVersion', 'recoveryId', 'recoveryVersion', 'taskId', 'taskVersion', 'traceId',
  ]) && uuid.test(value.recoveryId) && uuid.test(value.taskId)
    && integer(value.recoveryVersion, 1) && integer(value.taskVersion, 1)
    && ['ready', 'finalized'].includes(value.observationStatus)
    && ['approval-pending', 'approval-approved', 'approval-rejected', 'cancelled',
      'executed'].includes(value.finalizationState)
    && uuid.test(value.approvalId) && integer(value.approvalVersion, 1)
    && value.policyVersion === 'QRP-1.0.0' && digest.test(value.finalPreviewDigest)
    && digest.test(value.observationDecisionDigest)
    && typeof value.finalObservationWatermark === 'string'
    && value.finalObservationWatermark.length > 0 && value.finalObservationWatermark.length <= 256
    && trace.test(value.traceId);
}

function isFinalExecution(value: unknown): value is QualityRecoveryFinalExecution {
  return exact(value, [
    'deliveryStatus', 'eligibilityStatus', 'episodeStatus', 'generation',
    'ownerResultDigest', 'recoveryCompletedAt', 'recoveryId', 'taskId', 'taskStatus',
    'taskVersion', 'traceId', 'windowOutcomesDigest',
  ]) && uuid.test(value.recoveryId) && uuid.test(value.taskId)
    && integer(value.generation, 1) && value.eligibilityStatus === 'eligible'
    && value.episodeStatus === 'closed' && value.taskStatus === 'closed'
    && integer(value.taskVersion, 1) && validInstant(value.recoveryCompletedAt)
    && digest.test(value.windowOutcomesDigest) && digest.test(value.ownerResultDigest)
    && ['pending', 'retrying', 'confirmed', 'failed'].includes(value.deliveryStatus)
    && trace.test(value.traceId);
}

async function safeFailure(response: Response): Promise<QualityFuseRecoveryFailure> {
  try {
    const value: unknown = await response.json();
    if (exact(value, ['code', 'currentVersion', 'fieldErrors', 'traceId'])
        && typeof value.code === 'string' && allowedFailures.has(value.code)
        && (value.currentVersion === null || integer(value.currentVersion, 0))) {
      return new QualityFuseRecoveryFailure(value.code, value.currentVersion);
    }
  } catch { /* Never reflect an external response body. */ }
  return new QualityFuseRecoveryFailure(response.status === 409
    ? 'INGESTION_QUALITY_VERSION_CONFLICT'
    : response.status === 404 ? 'INGESTION_QUALITY_FORBIDDEN'
      : response.status === 503 ? 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE'
        : 'INGESTION_QUALITY_REQUEST_INVALID');
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const digest = /^sha256:[0-9a-f]{64}$/;
const trace = /^(?!0{32}$)[0-9a-f]{32}$/;
const statuses = new Set<QualityFuseRecoveryStatus>([
  'requested', 'validating', 'validation-succeeded', 'validation-failed',
  'approval-pending', 'approval-approved', 'executed', 'cancelled',
]);
const allowedFailures = new Set([
  'INGESTION_QUALITY_FORBIDDEN', 'INGESTION_QUALITY_REQUEST_INVALID',
  'INGESTION_QUALITY_VERSION_CONFLICT', 'INGESTION_QUALITY_INVALID_STATE',
  'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE', 'INGESTION_QUALITY_RESPONSE_INVALID',
]);

function requireUuid(value: string): void { if (!uuid.test(value)) throw invalid(); }
function invalid(): QualityFuseRecoveryFailure {
  return new QualityFuseRecoveryFailure('INGESTION_QUALITY_REQUEST_INVALID');
}
function exact(value: unknown, keys: readonly string[]): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.keys(value).sort().join('\0') === [...keys].sort().join('\0');
}
function integer(value: unknown, minimum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum;
}
function nullableInteger(value: unknown): boolean { return value === null || integer(value, 1); }
function nullableUuid(value: unknown): boolean { return value === null || typeof value === 'string' && uuid.test(value); }
function nullableDigest(value: unknown): boolean { return value === null || typeof value === 'string' && digest.test(value); }
function nullableText(value: unknown): boolean { return value === null || typeof value === 'string' && value.length > 0; }
function nullableInstant(value: unknown): boolean { return value === null || validInstant(value); }
function validInstant(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}
function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === 'object') {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}
