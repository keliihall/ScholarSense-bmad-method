import type { QueryClient } from '@tanstack/vue-query';


export type LifecycleBoundary =
  | 'logout'
  | 'account-switch'
  | 'refresh'
  | 'webview-destroyed'
  | 'host-session-invalid';

export type QueryKeyValue = string | number | boolean | null | readonly QueryKeyValue[] | QueryKeyObject;
export interface QueryKeyObject {
  readonly [key: string]: QueryKeyValue;
}
export type QueryKeyParams = QueryKeyObject;
export type ScholarSenseQueryKey = readonly [domain: string, resource: string, params: QueryKeyParams];

declare const completedConnectivityRecoveryBrand: unique symbol;
export type CompletedConnectivityRecovery = Readonly<{
  aggregateVersion: number;
  requiresExplicitRetry: true;
  [completedConnectivityRecoveryBrand]: true;
}>;

export type OnlineCommandGate = Readonly<{
  online: boolean;
  recovery?: CompletedConnectivityRecovery;
  explicitRetryRequested: boolean;
  accountId: string;
  sessionId: string;
  connectionGeneration: number;
  commandId: string;
  aggregateVersion: number;
}>;

type ConnectivityRecoveryContext = Readonly<{
  accountId: string;
  sessionId: string;
  connectionGeneration: number;
  commandId: string;
}>;

type RecoveryMetadata = ConnectivityRecoveryContext & Readonly<{
  aggregateVersion: number;
  attemptId: number;
}>;

const recoveryMetadata = new WeakMap<object, RecoveryMetadata>();
const activeRecoveryAttempts = new Map<string, Readonly<{ connectionGeneration: number; attemptId: number }>>();
const highestConnectionGenerations = new Map<string, number>();
let nextRecoveryAttemptId = 0;

export function createQueryKey(
  domain: string,
  resource: string,
  params: QueryKeyParams,
): ScholarSenseQueryKey {
  if (!domain || !resource) {
    throw new Error('QUERY_KEY_PART_REQUIRED');
  }
  const paramsSnapshot = cloneAndFreezeQueryParams(params);
  return Object.freeze([domain, resource, paramsSnapshot]);
}

export class VolatileClientState {
  readonly #drafts = new Map<string, unknown>();

  public constructor(private readonly queryClient: QueryClient) {}

  public get draftCount(): number {
    return this.#drafts.size;
  }

  public setDraft(key: string, value: unknown): void {
    this.#drafts.set(key, value);
  }

  public handleLifecycle(_event: LifecycleBoundary): void {
    this.#drafts.clear();
    this.queryClient.clear();
    revokeAllConnectivityRecoveries();
  }
}

export function canSubmitOnlineCommand(input: OnlineCommandGate): Readonly<{ allowed: boolean; message?: string }> {
  validateOnlineCommandGate(input);
  const sessionKey = connectivitySessionKey(input);
  if (!input.online) {
    revokeConnectivitySession(sessionKey);
    return Object.freeze({
      allowed: false,
      message: '网络不可用，请恢复连接后显式重试。',
    });
  }
  const metadata = input.recovery === undefined ? undefined : recoveryMetadata.get(input.recovery);
  const activeAttempt = activeRecoveryAttempts.get(sessionKey);
  if (
    metadata === undefined
    || activeAttempt === undefined
    || metadata.attemptId !== activeAttempt.attemptId
    || metadata.accountId !== input.accountId
    || metadata.sessionId !== input.sessionId
    || metadata.connectionGeneration !== input.connectionGeneration
    || metadata.commandId !== input.commandId
    || metadata.aggregateVersion !== input.aggregateVersion
  ) {
    return Object.freeze({
      allowed: false,
      message: '连接恢复处理中，请等待身份认证、授权和版本刷新完成。',
    });
  }
  if (input.explicitRetryRequested !== true) {
    return Object.freeze({
      allowed: false,
      message: '连接已恢复，请由用户显式重试。',
    });
  }
  recoveryMetadata.delete(input.recovery!);
  activeRecoveryAttempts.delete(sessionKey);
  return Object.freeze({ allowed: true });
}

export async function recoverAfterConnectivity(steps: ConnectivityRecoveryContext & Readonly<{
  authenticate: () => Promise<void>;
  authorize: () => Promise<void>;
  refreshAggregateVersion: () => Promise<number>;
}>): Promise<CompletedConnectivityRecovery> {
  validateRecoveryContext(steps);
  const sessionKey = connectivitySessionKey(steps);
  const highestGeneration = highestConnectionGenerations.get(sessionKey);
  if (highestGeneration !== undefined && steps.connectionGeneration < highestGeneration) {
    throw new Error('RECOVERY_CONNECTION_GENERATION_STALE');
  }
  highestConnectionGenerations.set(sessionKey, steps.connectionGeneration);
  const attemptId = ++nextRecoveryAttemptId;
  activeRecoveryAttempts.set(sessionKey, {
    connectionGeneration: steps.connectionGeneration,
    attemptId,
  });
  await steps.authenticate();
  await steps.authorize();
  const aggregateVersion = await steps.refreshAggregateVersion();
  if (!Number.isSafeInteger(aggregateVersion) || aggregateVersion < 0) {
    throw new Error('RECOVERY_AGGREGATE_VERSION_INVALID');
  }
  const activeAttempt = activeRecoveryAttempts.get(sessionKey);
  if (activeAttempt?.attemptId !== attemptId) {
    throw new Error('RECOVERY_SUPERSEDED');
  }
  const recovery = Object.freeze({
    aggregateVersion,
    requiresExplicitRetry: true as const,
  }) as CompletedConnectivityRecovery;
  recoveryMetadata.set(recovery, {
    accountId: steps.accountId,
    sessionId: steps.sessionId,
    connectionGeneration: steps.connectionGeneration,
    commandId: steps.commandId,
    aggregateVersion,
    attemptId,
  });
  return recovery;
}

function cloneAndFreezeQueryParams(params: QueryKeyParams): QueryKeyParams {
  return cloneAndFreezeQueryValue(params, new WeakSet<object>()) as QueryKeyParams;
}

function cloneAndFreezeQueryValue(value: QueryKeyValue, ancestors: WeakSet<object>): QueryKeyValue {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      throw new Error('QUERY_KEY_PARAM_INVALID');
    }
    return value;
  }
  if (typeof value !== 'object') {
    throw new Error('QUERY_KEY_PARAM_INVALID');
  }
  if (ancestors.has(value)) {
    throw new Error('QUERY_KEY_PARAM_INVALID');
  }
  ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      const snapshot = value.map((item) => cloneAndFreezeQueryValue(item, ancestors));
      return Object.freeze(snapshot);
    }
    if (!isPlainDataRecord(value)) {
      throw new Error('QUERY_KEY_PARAM_INVALID');
    }
    const snapshot: Record<string, QueryKeyValue> = {};
    for (const key of Reflect.ownKeys(value)) {
      if (
        typeof key !== 'string'
        || key === '__proto__'
        || key === 'prototype'
        || key === 'constructor'
      ) {
        throw new Error('QUERY_KEY_PARAM_INVALID');
      }
      const descriptor = Object.getOwnPropertyDescriptor(value, key);
      if (descriptor === undefined || !descriptor.enumerable || !('value' in descriptor)) {
        throw new Error('QUERY_KEY_PARAM_INVALID');
      }
      snapshot[key] = cloneAndFreezeQueryValue(descriptor.value as QueryKeyValue, ancestors);
    }
    return Object.freeze(snapshot);
  } finally {
    ancestors.delete(value);
  }
}

function validateOnlineCommandGate(input: OnlineCommandGate): void {
  if (typeof input.online !== 'boolean' || typeof input.explicitRetryRequested !== 'boolean') {
    throw new Error('ONLINE_COMMAND_GATE_INVALID');
  }
  validateRecoveryContext(input);
  if (!Number.isSafeInteger(input.aggregateVersion) || input.aggregateVersion < 0) {
    throw new Error('ONLINE_COMMAND_GATE_INVALID');
  }
}

function validateRecoveryContext(context: ConnectivityRecoveryContext): void {
  for (const value of [context.accountId, context.sessionId, context.commandId]) {
    if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)) {
      throw new Error('RECOVERY_CONTEXT_INVALID');
    }
  }
  if (!Number.isSafeInteger(context.connectionGeneration) || context.connectionGeneration < 0) {
    throw new Error('RECOVERY_CONTEXT_INVALID');
  }
}

function connectivitySessionKey(context: Pick<ConnectivityRecoveryContext, 'accountId' | 'sessionId'>): string {
  return `${context.accountId}\u0000${context.sessionId}`;
}

function revokeConnectivitySession(sessionKey: string): void {
  activeRecoveryAttempts.delete(sessionKey);
}

function revokeAllConnectivityRecoveries(): void {
  activeRecoveryAttempts.clear();
  highestConnectionGenerations.clear();
}

function isPlainDataRecord(value: object): boolean {
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}
