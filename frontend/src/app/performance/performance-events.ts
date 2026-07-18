export const ATTRIBUTION_SEGMENTS = [
  'host/navigation',
  'gateway',
  'query',
  'serialization',
  'network',
  'parse',
  'render/main-thread',
  'accessibility-ready',
  'cache-state',
] as const;

export type AttributionSegment = typeof ATTRIBUTION_SEGMENTS[number];
export type SegmentDurations = Readonly<Record<AttributionSegment, number>>;
export type CacheState = 'cold' | 'warm';
export type PerformanceEventName = 'ui.content-ready' | 'ui.filter-ready' | 'ui.state-observed';
export type PerformanceSurface = 'workbench' | 'list' | 'detail' | 'dashboard' | 'cross-client-state';
export type ServerClockSource = 'server-synchronized-ntp';

export type PerformanceSample = Readonly<{
  schemaVersion: '1.0.0';
  sampleId: string;
  eventName: PerformanceEventName;
  surface: PerformanceSurface;
  startedAt: number;
  observedAt: number;
  durationMs: number;
  status: 'success' | 'failure';
  segments: SegmentDurations;
  cacheState: CacheState;
  aggregateVersion?: number;
  failureAttribution?: AttributionSegment;
  committingClientId?: string;
  observingClientId?: string;
  clockSource?: ServerClockSource;
  clockSkewMs?: number;
}>;

type SampleBase = Readonly<{
  sampleId: string;
  startedAt: number;
  observedAt: number;
  segments: SegmentDurations;
  cacheState: CacheState;
}>;

export function createContentReadySample(input: SampleBase & Readonly<{
  surface: 'workbench' | 'list' | 'detail';
  requiredDataReady: boolean;
  authorizedProjectionReady: boolean;
  criticalControlsReady: boolean;
  accessibleNamesReady: boolean;
  skeletonVisible: boolean;
}>): PerformanceSample | null {
  validateCommon(input);
  validateBoolean(input.requiredDataReady, 'PERFORMANCE_READINESS_INVALID');
  validateBoolean(input.authorizedProjectionReady, 'PERFORMANCE_READINESS_INVALID');
  validateBoolean(input.criticalControlsReady, 'PERFORMANCE_READINESS_INVALID');
  validateBoolean(input.accessibleNamesReady, 'PERFORMANCE_READINESS_INVALID');
  validateBoolean(input.skeletonVisible, 'PERFORMANCE_READINESS_INVALID');
  if (
    input.skeletonVisible
    || !input.requiredDataReady
    || !input.authorizedProjectionReady
    || !input.criticalControlsReady
    || !input.accessibleNamesReady
  ) {
    return null;
  }
  return createSuccessSample('ui.content-ready', input.surface, input);
}

export function createFilterReadySample(input: SampleBase & Readonly<{
  chartReady: boolean;
  equivalentTableReady: boolean;
  assistiveFeedbackReady: boolean;
}>): PerformanceSample | null {
  validateCommon(input);
  validateBoolean(input.chartReady, 'PERFORMANCE_READINESS_INVALID');
  validateBoolean(input.equivalentTableReady, 'PERFORMANCE_READINESS_INVALID');
  validateBoolean(input.assistiveFeedbackReady, 'PERFORMANCE_READINESS_INVALID');
  if (!input.chartReady || !input.equivalentTableReady || !input.assistiveFeedbackReady) {
    return null;
  }
  return createSuccessSample('ui.filter-ready', 'dashboard', input);
}

export function createStateObservedSample(input: Omit<SampleBase, 'startedAt'> & Readonly<{
  committedAt: number;
  committedAggregateVersion: number;
  observedAggregateVersion: number;
  committingClientId: string;
  observingClientId: string;
  clockSource: ServerClockSource;
  clockSkewMs: number;
  online: boolean;
}>): PerformanceSample | null {
  const sampleBase = { ...input, startedAt: input.committedAt };
  validateCommon(sampleBase);
  validateAggregateVersion(input.committedAggregateVersion);
  validateAggregateVersion(input.observedAggregateVersion);
  validateClientPair(input.committingClientId, input.observingClientId);
  validateServerClock(input.clockSource, input.clockSkewMs);
  validateBoolean(input.online, 'PERFORMANCE_CONNECTIVITY_INVALID');
  if (!input.online || input.observedAggregateVersion < input.committedAggregateVersion) {
    return null;
  }
  return Object.freeze({
    ...createSuccessSample(
      'ui.state-observed',
      'cross-client-state',
      sampleBase,
    ),
    aggregateVersion: input.observedAggregateVersion,
    committingClientId: input.committingClientId,
    observingClientId: input.observingClientId,
    clockSource: input.clockSource,
    clockSkewMs: input.clockSkewMs,
  });
}

export function createFailedSample(input: SampleBase & Readonly<{
  eventName: PerformanceEventName;
  surface: PerformanceSurface;
  failureAttribution: AttributionSegment;
}>): PerformanceSample {
  validateCommon(input);
  validateEventName(input.eventName);
  validateSurface(input.surface);
  if (!ATTRIBUTION_SEGMENTS.includes(input.failureAttribution)) {
    throw new Error('PERFORMANCE_ATTRIBUTION_INVALID');
  }
  return Object.freeze({
    schemaVersion: '1.0.0',
    sampleId: input.sampleId,
    eventName: input.eventName,
    surface: input.surface,
    startedAt: input.startedAt,
    observedAt: input.observedAt,
    durationMs: input.observedAt - input.startedAt,
    status: 'failure',
    segments: Object.freeze({ ...input.segments }),
    cacheState: input.cacheState,
    failureAttribution: input.failureAttribution,
  });
}

export class PerformanceSampleCollector {
  readonly #samples = new Map<string, PerformanceSample>();

  public get samples(): readonly PerformanceSample[] {
    return Object.freeze([...this.#samples.values()]);
  }

  public record(sample: PerformanceSample): PerformanceSample {
    const snapshot = snapshotPerformanceSample(sample);
    const existing = this.#samples.get(snapshot.sampleId);
    if (existing !== undefined) {
      if (JSON.stringify(existing) !== JSON.stringify(snapshot)) {
        throw new Error('PERFORMANCE_SAMPLE_MISMATCH');
      }
      return existing;
    }
    validatePerformanceSample(snapshot);
    this.#samples.set(snapshot.sampleId, snapshot);
    return snapshot;
  }
}

const PERFORMANCE_SAMPLE_FIELDS = new Set([
  'schemaVersion',
  'sampleId',
  'eventName',
  'surface',
  'startedAt',
  'observedAt',
  'durationMs',
  'status',
  'segments',
  'cacheState',
  'aggregateVersion',
  'failureAttribution',
  'committingClientId',
  'observingClientId',
  'clockSource',
  'clockSkewMs',
]);

const TELEMETRY_ALLOWLIST = new Set([
  'sampleId',
  'traceId',
  'surface',
  'cacheState',
  'aggregateVersion',
  'eventName',
  'durationMs',
  'status',
  'failureAttribution',
  'committingClientId',
  'observingClientId',
  'clockSource',
  'clockSkewMs',
]);

const PERFORMANCE_EVENT_NAMES = new Set<PerformanceEventName>([
  'ui.content-ready',
  'ui.filter-ready',
  'ui.state-observed',
]);
const PERFORMANCE_SURFACES = new Set<PerformanceSurface>([
  'workbench',
  'list',
  'detail',
  'dashboard',
  'cross-client-state',
]);
const CACHE_STATES = new Set<CacheState>(['cold', 'warm']);
const SAMPLE_STATUSES = new Set(['success', 'failure']);
const OPAQUE_IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const SENSITIVE_CONTENT = /(?:student|evidence|token|secret|password|passwd|authorization(?:result)?|private[-_]?key)/i;

export function sanitizeTelemetryPayload(payload: unknown): Readonly<Record<string, unknown>> {
  const sanitized = snapshotDataRecord(payload, 'TELEMETRY_PAYLOAD_INVALID');
  const keys = Reflect.ownKeys(sanitized);
  for (const key of keys) {
    if (typeof key !== 'string') {
      throw new Error('TELEMETRY_FIELD_NOT_ALLOWED: symbol');
    }
    if (!TELEMETRY_ALLOWLIST.has(key)) {
      throw new Error(`TELEMETRY_FIELD_NOT_ALLOWED: ${key}`);
    }
    validateTelemetryField(key, sanitized[key]);
  }
  const committingClientId = sanitized.committingClientId;
  const observingClientId = sanitized.observingClientId;
  if (
    (committingClientId !== undefined || observingClientId !== undefined)
    && (typeof committingClientId !== 'string' || typeof observingClientId !== 'string')
  ) {
    throw new Error('TELEMETRY_CLIENT_PAIR_INVALID');
  }
  if (typeof committingClientId === 'string' && committingClientId === observingClientId) {
    throw new Error('TELEMETRY_CLIENT_PAIR_INVALID');
  }
  if ((sanitized.clockSource === undefined) !== (sanitized.clockSkewMs === undefined)) {
    throw new Error('TELEMETRY_CLOCK_INVALID');
  }
  if (sanitized.eventName === 'ui.state-observed') {
    validateStateObservedFields(sanitized, 'TELEMETRY_STATE_OBSERVED_INVALID');
  }
  return Object.freeze(sanitized);
}

export function evaluateWebVitals(value: Readonly<{ lcpMs: number; inpMs: number; cls: number }>): Readonly<{
  withinGuardrail: boolean;
  classification: 'single-sample-fixture';
}> {
  if (
    !Number.isFinite(value.lcpMs)
    || !Number.isFinite(value.inpMs)
    || !Number.isFinite(value.cls)
    || value.lcpMs < 0
    || value.inpMs < 0
    || value.cls < 0
  ) {
    throw new Error('WEB_VITAL_INVALID');
  }
  return Object.freeze({
    withinGuardrail: value.lcpMs <= 2500 && value.inpMs <= 200 && value.cls <= 0.1,
    classification: 'single-sample-fixture',
  });
}

function createSuccessSample(
  eventName: PerformanceEventName,
  surface: PerformanceSurface,
  input: SampleBase,
): PerformanceSample {
  validateCommon(input);
  validateEventName(eventName);
  validateSurface(surface);
  return Object.freeze({
    schemaVersion: '1.0.0',
    sampleId: input.sampleId,
    eventName,
    surface,
    startedAt: input.startedAt,
    observedAt: input.observedAt,
    durationMs: input.observedAt - input.startedAt,
    status: 'success',
    segments: Object.freeze({ ...input.segments }),
    cacheState: input.cacheState,
  });
}

function validateCommon(input: SampleBase): void {
  validateOpaqueIdentifier(input.sampleId, 'PERFORMANCE_SAMPLE_INVALID');
  if (
    !Number.isFinite(input.startedAt)
    || !Number.isFinite(input.observedAt)
    || input.startedAt < 0
    || input.observedAt < input.startedAt
  ) {
    throw new Error('PERFORMANCE_SAMPLE_INVALID');
  }
  if (!CACHE_STATES.has(input.cacheState)) {
    throw new Error('PERFORMANCE_SAMPLE_INVALID');
  }
  if (!isPlainRecord(input.segments)) {
    throw new Error('PERFORMANCE_SEGMENTS_INVALID');
  }
  const keys = Reflect.ownKeys(input.segments);
  const durationMs = input.observedAt - input.startedAt;
  if (
    keys.length !== ATTRIBUTION_SEGMENTS.length
    || keys.some((key) => typeof key !== 'string')
    || ATTRIBUTION_SEGMENTS.some((segment) => !keys.includes(segment))
    || Object.values(input.segments).some(
      (duration) => !Number.isFinite(duration) || duration < 0 || duration > durationMs,
    )
  ) {
    throw new Error('PERFORMANCE_SEGMENTS_INVALID');
  }
}

function validatePerformanceSample(sample: PerformanceSample): void {
  if (!isPlainRecord(sample)) {
    throw new Error('PERFORMANCE_SAMPLE_INVALID');
  }
  validateCommon(sample);
  validateEventName(sample.eventName);
  validateSurface(sample.surface);
  if (
    sample.schemaVersion !== '1.0.0'
    || sample.durationMs !== sample.observedAt - sample.startedAt
    || !SAMPLE_STATUSES.has(sample.status)
  ) {
    throw new Error('PERFORMANCE_SAMPLE_INVALID');
  }
  if (sample.aggregateVersion !== undefined) {
    validateAggregateVersion(sample.aggregateVersion);
  }
  if (sample.failureAttribution !== undefined && !ATTRIBUTION_SEGMENTS.includes(sample.failureAttribution)) {
    throw new Error('PERFORMANCE_ATTRIBUTION_INVALID');
  }
  if (sample.status === 'failure' && sample.failureAttribution === undefined) {
    throw new Error('PERFORMANCE_ATTRIBUTION_INVALID');
  }
  if (sample.status === 'success' && sample.failureAttribution !== undefined) {
    throw new Error('PERFORMANCE_ATTRIBUTION_INVALID');
  }
  if (sample.eventName === 'ui.state-observed' && sample.status === 'success') {
    validateStateObservedFields(sample, 'PERFORMANCE_STATE_OBSERVED_INVALID');
  }
  const hasAnyClient = sample.committingClientId !== undefined || sample.observingClientId !== undefined;
  if (hasAnyClient && sample.eventName !== 'ui.state-observed') {
    throw new Error('PERFORMANCE_CLIENT_INVALID');
  }
  const hasAnyClock = sample.clockSource !== undefined || sample.clockSkewMs !== undefined;
  if (hasAnyClock && sample.eventName !== 'ui.state-observed') {
    throw new Error('PERFORMANCE_CLOCK_INVALID');
  }
}

function snapshotPerformanceSample(sample: unknown): PerformanceSample {
  const snapshot = snapshotDataRecord(sample, 'PERFORMANCE_SAMPLE_INVALID');
  const keys = Reflect.ownKeys(snapshot);
  if (
    keys.some((key) => typeof key !== 'string' || !PERFORMANCE_SAMPLE_FIELDS.has(key))
    || !('segments' in snapshot)
  ) {
    throw new Error('PERFORMANCE_SAMPLE_INVALID');
  }
  const segmentSnapshot = snapshotDataRecord(snapshot.segments, 'PERFORMANCE_SEGMENTS_INVALID');
  snapshot.segments = Object.freeze(segmentSnapshot);
  return Object.freeze(snapshot) as PerformanceSample;
}

function validateTelemetryField(key: string, value: unknown): void {
  if (typeof value === 'string' && SENSITIVE_CONTENT.test(value)) {
    throw new Error(`TELEMETRY_SENSITIVE_CONTENT: ${key}`);
  }
  switch (key) {
    case 'sampleId':
    case 'traceId':
    case 'committingClientId':
    case 'observingClientId':
      validateOpaqueIdentifier(value, `TELEMETRY_FIELD_INVALID: ${key}`);
      return;
    case 'surface':
      if (typeof value !== 'string' || !PERFORMANCE_SURFACES.has(value as PerformanceSurface)) {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'cacheState':
      if (typeof value !== 'string' || !CACHE_STATES.has(value as CacheState)) {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'aggregateVersion':
      try {
        validateAggregateVersion(value);
      } catch {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'eventName':
      if (typeof value !== 'string' || !PERFORMANCE_EVENT_NAMES.has(value as PerformanceEventName)) {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'durationMs':
      if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'status':
      if (typeof value !== 'string' || !SAMPLE_STATUSES.has(value)) {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'failureAttribution':
      if (typeof value !== 'string' || !ATTRIBUTION_SEGMENTS.includes(value as AttributionSegment)) {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'clockSource':
      if (value !== 'server-synchronized-ntp') {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    case 'clockSkewMs':
      if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > 100) {
        throw new Error(`TELEMETRY_FIELD_INVALID: ${key}`);
      }
      return;
    default:
      throw new Error(`TELEMETRY_FIELD_NOT_ALLOWED: ${key}`);
  }
}

function validateAggregateVersion(value: unknown): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new Error('PERFORMANCE_VERSION_INVALID');
  }
}

function validateClientPair(committingClientId: unknown, observingClientId: unknown): void {
  validateOpaqueIdentifier(committingClientId, 'PERFORMANCE_CLIENT_INVALID');
  validateOpaqueIdentifier(observingClientId, 'PERFORMANCE_CLIENT_INVALID');
  if (committingClientId === observingClientId) {
    throw new Error('PERFORMANCE_CLIENT_INVALID');
  }
}

function validateServerClock(source: unknown, skewMs: unknown): void {
  if (
    source !== 'server-synchronized-ntp'
    || typeof skewMs !== 'number'
    || !Number.isFinite(skewMs)
    || Math.abs(skewMs) > 100
  ) {
    throw new Error('PERFORMANCE_CLOCK_INVALID');
  }
}

function validateStateObservedFields(value: unknown, errorCode: string): void {
  if (!isPlainRecord(value)) {
    throw new Error(errorCode);
  }
  try {
    validateAggregateVersion(value.aggregateVersion);
    validateClientPair(value.committingClientId, value.observingClientId);
    validateServerClock(value.clockSource, value.clockSkewMs);
  } catch {
    throw new Error(errorCode);
  }
  if (value.surface !== 'cross-client-state') {
    throw new Error(errorCode);
  }
}

function validateEventName(value: unknown): asserts value is PerformanceEventName {
  if (typeof value !== 'string' || !PERFORMANCE_EVENT_NAMES.has(value as PerformanceEventName)) {
    throw new Error('PERFORMANCE_EVENT_INVALID');
  }
}

function validateSurface(value: unknown): asserts value is PerformanceSurface {
  if (typeof value !== 'string' || !PERFORMANCE_SURFACES.has(value as PerformanceSurface)) {
    throw new Error('PERFORMANCE_SURFACE_INVALID');
  }
}

function validateOpaqueIdentifier(value: unknown, errorCode: string): asserts value is string {
  if (
    typeof value !== 'string'
    || !OPAQUE_IDENTIFIER.test(value)
    || SENSITIVE_CONTENT.test(value)
  ) {
    throw new Error(errorCode);
  }
}

function validateBoolean(value: unknown, errorCode: string): asserts value is boolean {
  if (typeof value !== 'boolean') {
    throw new Error(errorCode);
  }
}

function snapshotDataRecord(value: unknown, errorCode: string): Record<string, unknown> {
  if (!isPlainRecord(value)) {
    throw new Error(errorCode);
  }
  const snapshot: Record<string, unknown> = {};
  for (const key of Reflect.ownKeys(value)) {
    if (typeof key !== 'string') {
      throw new Error(errorCode);
    }
    const descriptor = Object.getOwnPropertyDescriptor(value, key);
    if (descriptor === undefined || !descriptor.enumerable || !('value' in descriptor)) {
      throw new Error(errorCode);
    }
    Object.defineProperty(snapshot, key, {
      configurable: false,
      enumerable: true,
      value: descriptor.value,
      writable: true,
    });
  }
  return snapshot;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return false;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}
