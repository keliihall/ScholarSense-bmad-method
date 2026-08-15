export type QualityTaskDeliveryStatus = 'pending' | 'retrying' | 'confirmed' | 'failed';

export type QualityRecoveryTask = Readonly<{
  taskId: string;
  taskVersion: number;
  episodeId: string;
  episodeGeneration: number;
  sourceId: string;
  dependencyId: string;
  affectedRules: readonly Readonly<{ ruleId: string; ruleVersion: string }>[];
  ownerRef: string;
  priority: 'P0' | 'P1' | 'P2';
  dueAt: string;
  status: 'open' | 'closed';
  closedAt: string | null;
  closureReason: 'RECOVERY_FINALIZED' | null;
  ownerResultDigest: string | null;
  watermark: string;
  trigger: Readonly<{ batchId: string; snapshotId: string; reasonCode: string }>;
  currentEvidence: Readonly<{
    qualityGateVersion: string; qmdpVersion: string; qshmVersion: string;
  }>;
  taskDelivery: Readonly<{
    target: 'public-task-platform';
    status: QualityTaskDeliveryStatus;
    attempt: number;
    nextAttemptAt: string | null;
  }>;
}>;

export type QualityRecoveryTaskCursor = Readonly<{ occurredAt: string; taskId: string }>;
export type QualityRecoveryTaskPage = Readonly<{
  items: readonly QualityRecoveryTask[];
  size: number;
  hasMore: boolean;
  nextCursor: QualityRecoveryTaskCursor | null;
}>;
export type QualityRecoveryTaskFilters = Readonly<{
  sourceId: string;
  status?: 'open' | 'closed';
  afterOccurredAt?: string;
  afterTaskId?: string;
  size: number;
}>;
export type QualityRecoveryTaskIdentityGeneration = Readonly<{
  sessionPseudonym: string;
  sessionVersion: number;
  policyVersion: 'RFP-1.0.0';
  capabilitySignature: string;
}>;
export type QualityRecoveryTaskClearReason = 'refresh' | 'logout' | 'account-switch'
  | 'session-invalid' | 'authorization-revoked' | 'offline' | 'narrow-screen';

const uuidV7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const sourceId = /^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$/;
const dependencyId = /^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$/;
const ruleId = /^[A-Z][A-Z0-9-]{2,63}$/;
const version = /^[A-Z0-9][A-Z0-9.-]{0,63}$/i;
const reasonCode = /^[A-Z][A-Z0-9_]{2,127}$/;
const deliveryStatuses = new Set<QualityTaskDeliveryStatus>([
  'pending', 'retrying', 'confirmed', 'failed',
]);
const allowedFailures = new Set([
  'INGESTION_QUALITY_FORBIDDEN', 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE',
  'INGESTION_QUALITY_REQUEST_INVALID', 'INGESTION_QUALITY_PAGE_INVALID',
]);

export class QualityRecoveryTaskClient {
  public constructor(private readonly request: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  public async list(
    filters: QualityRecoveryTaskFilters, signal?: AbortSignal,
  ): Promise<QualityRecoveryTaskPage> {
    requireFilters(filters);
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined) query.set(key, String(value));
    }
    return this.response(`/api/v1/quality-recovery-tasks?${query.toString()}`, { signal }, isPage);
  }

  public async detail(value: string, signal?: AbortSignal): Promise<QualityRecoveryTask> {
    if (!uuidV7.test(value)) throw invalidRequest();
    return this.response(`/api/v1/quality-recovery-tasks/${value}`, { signal }, isTask);
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

export class QualityRecoveryTaskMemoryState {
  #page?: QualityRecoveryTaskPage;
  #detail?: QualityRecoveryTask;
  public acceptPage(value: QualityRecoveryTaskPage): void { this.#page = value; }
  public acceptDetail(value: QualityRecoveryTask): void { this.#detail = value; }
  public page(): QualityRecoveryTaskPage | undefined { return this.#page; }
  public detail(): QualityRecoveryTask | undefined { return this.#detail; }
  public clear(_reason: QualityRecoveryTaskClearReason): void {
    this.#page = undefined;
    this.#detail = undefined;
  }
  public toJSON(): Readonly<Record<string, never>> { return Object.freeze({}); }
}

type Mutable<T> = { value: T };
export function clearQualityRecoveryTaskIdentityBoundary(
  active: Mutable<AbortController | undefined>,
  page: Mutable<QualityRecoveryTaskPage | undefined>,
  detail: Mutable<QualityRecoveryTask | undefined>,
  memory: QualityRecoveryTaskMemoryState,
  reason: QualityRecoveryTaskClearReason = 'authorization-revoked',
): void {
  active.value?.abort();
  active.value = undefined;
  page.value = undefined;
  detail.value = undefined;
  memory.clear(reason);
}

export function qualityRecoveryTaskQueryOptions(
  context: QualityRecoveryTaskIdentityGeneration
    & Readonly<Record<string, string | number | undefined>>,
  queryFn: () => Promise<QualityRecoveryTaskPage>,
) {
  return Object.freeze({
    queryKey: Object.freeze([
      'ingestion-quality', 'quality-recovery-tasks', Object.freeze({ ...context }),
    ] as const),
    queryFn, staleTime: 0, gcTime: 5 * 60 * 1000,
    retry: false, networkMode: 'online' as const,
  });
}

export function qualityRecoveryTaskDeliveryText(value: QualityTaskDeliveryStatus): string {
  return ({
    pending: '待投递（本地任务已建立）',
    retrying: '重试中（本地质量状态不受影响）',
    confirmed: '外部平台已确认投递',
    failed: '投递失败（本地任务状态不受影响）',
  })[value];
}

function requireFilters(filters: QualityRecoveryTaskFilters): void {
  if (!integer(filters.size, 1) || filters.size > 100
      || !sourceId.test(filters.sourceId)
      || filters.status !== undefined && !['open', 'closed'].includes(filters.status)
      || (filters.afterOccurredAt === undefined) !== (filters.afterTaskId === undefined)
      || filters.afterOccurredAt !== undefined && !validInstant(filters.afterOccurredAt)
      || filters.afterTaskId !== undefined && !uuidV7.test(filters.afterTaskId)) {
    throw invalidRequest();
  }
}

function isPage(value: unknown): value is QualityRecoveryTaskPage {
  if (!exact(value, ['hasMore', 'items', 'nextCursor', 'size'])
      || !Array.isArray(value.items) || !value.items.every(isTask)
      || !integer(value.size, 1) || value.size > 100 || value.items.length > value.size
      || typeof value.hasMore !== 'boolean') return false;
  if (!value.hasMore) return value.nextCursor === null;
  if (!isCursor(value.nextCursor) || value.items.length !== value.size) return false;
  return value.items.at(-1)?.taskId === value.nextCursor.taskId;
}

function isCursor(value: unknown): value is QualityRecoveryTaskCursor {
  return exact(value, ['occurredAt', 'taskId'])
    && uuidV7.test(value.taskId) && validInstant(value.occurredAt);
}

function isTask(value: unknown): value is QualityRecoveryTask {
  return exact(value, [
    'affectedRules', 'closedAt', 'closureReason', 'currentEvidence', 'dependencyId',
    'dueAt', 'episodeGeneration', 'episodeId', 'ownerRef', 'ownerResultDigest',
    'priority', 'sourceId', 'status', 'taskDelivery', 'taskId', 'taskVersion', 'trigger',
    'watermark',
  ]) && uuidV7.test(value.taskId) && integer(value.taskVersion, 1)
    && uuidV7.test(value.episodeId)
    && integer(value.episodeGeneration, 1) && sourceId.test(value.sourceId)
    && dependencyId.test(value.dependencyId)
    && Array.isArray(value.affectedRules) && value.affectedRules.length >= 1
    && value.affectedRules.length <= 100 && value.affectedRules.every(isRule)
    && new Set(value.affectedRules.map((item) => `${item.ruleId}@${item.ruleVersion}`)).size
      === value.affectedRules.length
    && boundedText(value.ownerRef, 256) && ['P0', 'P1', 'P2'].includes(value.priority)
    && validInstant(value.dueAt) && ['open', 'closed'].includes(value.status)
    && validTerminalFields(value) && boundedText(value.watermark, 512)
    && isTrigger(value.trigger) && isCurrentEvidence(value.currentEvidence)
    && isDelivery(value.taskDelivery);
}

function validTerminalFields(value: Record<string, any>): boolean {
  if (value.status === 'open') {
    return value.closedAt === null && value.closureReason === null
      && value.ownerResultDigest === null;
  }
  return validInstant(value.closedAt) && value.closureReason === 'RECOVERY_FINALIZED'
    && typeof value.ownerResultDigest === 'string'
    && /^sha256:[0-9a-f]{64}$/.test(value.ownerResultDigest);
}

function isRule(value: unknown): value is QualityRecoveryTask['affectedRules'][number] {
  return exact(value, ['ruleId', 'ruleVersion'])
    && ruleId.test(value.ruleId) && version.test(value.ruleVersion);
}

function isTrigger(value: unknown): value is QualityRecoveryTask['trigger'] {
  return exact(value, ['batchId', 'reasonCode', 'snapshotId'])
    && uuidV7.test(value.batchId) && uuidV7.test(value.snapshotId)
    && reasonCode.test(value.reasonCode);
}

function isCurrentEvidence(value: unknown): value is QualityRecoveryTask['currentEvidence'] {
  return exact(value, ['qmdpVersion', 'qshmVersion', 'qualityGateVersion'])
    && version.test(value.qualityGateVersion) && version.test(value.qmdpVersion)
    && version.test(value.qshmVersion);
}

function isDelivery(value: unknown): value is QualityRecoveryTask['taskDelivery'] {
  if (!exact(value, ['attempt', 'nextAttemptAt', 'status', 'target'])
      || value.target !== 'public-task-platform' || !deliveryStatuses.has(value.status)
      || !integer(value.attempt, 0)) return false;
  const next = value.nextAttemptAt;
  return value.status === 'retrying'
    ? validInstant(next)
    : next === null;
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
  if (value !== null && typeof value === 'object') {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}
