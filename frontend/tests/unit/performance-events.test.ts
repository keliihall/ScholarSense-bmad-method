import { describe, expect, it } from 'vitest';

import {
  ATTRIBUTION_SEGMENTS,
  PerformanceSampleCollector,
  createContentReadySample,
  createFailedSample,
  createFilterReadySample,
  createStateObservedSample,
  evaluateWebVitals,
  sanitizeTelemetryPayload,
} from '../../src/app/performance/performance-events';
import type { SegmentDurations } from '../../src/app/performance/performance-events';


const segments = Object.fromEntries(ATTRIBUTION_SEGMENTS.map((name) => [name, 1])) as SegmentDurations;

describe('user-perceived performance events', () => {
  it('does not emit content-ready for a skeleton, gateway-only completion or missing a11y names', () => {
    const base = {
      sampleId: 'sample-content-1',
      surface: 'list' as const,
      startedAt: 1000,
      observedAt: 1500,
      requiredDataReady: true,
      authorizedProjectionReady: true,
      criticalControlsReady: true,
      accessibleNamesReady: true,
      skeletonVisible: false,
      segments,
      cacheState: 'cold' as const,
    };

    expect(createContentReadySample({ ...base, skeletonVisible: true })).toBeNull();
    expect(createContentReadySample({ ...base, requiredDataReady: false })).toBeNull();
    expect(createContentReadySample({ ...base, accessibleNamesReady: false })).toBeNull();
    expect(createContentReadySample(base)).toMatchObject({
      eventName: 'ui.content-ready',
      durationMs: 500,
      status: 'success',
    });
  });

  it('requires chart, equivalent table and assistive feedback before filter-ready', () => {
    const base = {
      sampleId: 'sample-filter-1',
      startedAt: 2000,
      observedAt: 2600,
      chartReady: true,
      equivalentTableReady: true,
      assistiveFeedbackReady: true,
      segments,
      cacheState: 'warm' as const,
    };
    expect(createFilterReadySample({ ...base, equivalentTableReady: false })).toBeNull();
    expect(createFilterReadySample(base)?.eventName).toBe('ui.filter-ready');
  });

  it('does not emit state-observed for an old aggregate version or an offline client', () => {
    const base = {
      sampleId: 'sample-observed-1',
      committedAt: 3000,
      observedAt: 3400,
      committedAggregateVersion: 12,
      observedAggregateVersion: 12,
      committingClientId: 'client-committing-1',
      observingClientId: 'client-observing-1',
      clockSource: 'server-synchronized-ntp' as const,
      clockSkewMs: 100,
      online: true,
      segments,
      cacheState: 'cold' as const,
    };
    expect(createStateObservedSample({ ...base, observedAggregateVersion: 11 })).toBeNull();
    expect(createStateObservedSample({ ...base, online: false })).toBeNull();
    expect(createStateObservedSample(base)).toMatchObject({
      eventName: 'ui.state-observed',
      aggregateVersion: 12,
      durationMs: 400,
      committingClientId: 'client-committing-1',
      observingClientId: 'client-observing-1',
      clockSource: 'server-synchronized-ntp',
      clockSkewMs: 100,
    });
  });

  it('requires different clients, valid versions and a synchronized clock for state-observed', () => {
    const base = {
      sampleId: 'sample-observed-contract-1',
      committedAt: 3000,
      observedAt: 3400,
      committedAggregateVersion: 12,
      observedAggregateVersion: 12,
      committingClientId: 'client-committing-1',
      observingClientId: 'client-observing-1',
      clockSource: 'server-synchronized-ntp' as const,
      clockSkewMs: -100,
      online: true,
      segments,
      cacheState: 'cold' as const,
    };

    expect(() => createStateObservedSample({
      ...base,
      observingClientId: base.committingClientId,
    })).toThrow('PERFORMANCE_CLIENT_INVALID');
    expect(() => createStateObservedSample({ ...base, observedAggregateVersion: Number.NaN }))
      .toThrow('PERFORMANCE_VERSION_INVALID');
    expect(() => createStateObservedSample({ ...base, observedAggregateVersion: 12.5 }))
      .toThrow('PERFORMANCE_VERSION_INVALID');
    expect(() => createStateObservedSample({ ...base, committedAggregateVersion: -1 }))
      .toThrow('PERFORMANCE_VERSION_INVALID');
    expect(() => createStateObservedSample({ ...base, clockSkewMs: 100.01 }))
      .toThrow('PERFORMANCE_CLOCK_INVALID');
    expect(() => createStateObservedSample({ ...base, clockSkewMs: Number.POSITIVE_INFINITY }))
      .toThrow('PERFORMANCE_CLOCK_INVALID');
    expect(() => createStateObservedSample({
      ...base,
      clockSource: 'device-local',
    } as unknown as Parameters<typeof createStateObservedSample>[0])).toThrow('PERFORMANCE_CLOCK_INVALID');
  });

  it('rejects non-finite timestamps and illegal segment durations', () => {
    const base = {
      sampleId: 'sample-runtime-validation-1',
      surface: 'list' as const,
      startedAt: 1000,
      observedAt: 1500,
      requiredDataReady: true,
      authorizedProjectionReady: true,
      criticalControlsReady: true,
      accessibleNamesReady: true,
      skeletonVisible: false,
      segments,
      cacheState: 'cold' as const,
    };

    expect(() => createContentReadySample({ ...base, observedAt: Number.NaN }))
      .toThrow('PERFORMANCE_SAMPLE_INVALID');
    expect(() => createContentReadySample({
      ...base,
      observedAt: Number.NaN,
      skeletonVisible: true,
    })).toThrow('PERFORMANCE_SAMPLE_INVALID');
    expect(() => createContentReadySample({ ...base, observedAt: Number.POSITIVE_INFINITY }))
      .toThrow('PERFORMANCE_SAMPLE_INVALID');
    expect(() => createContentReadySample({ ...base, startedAt: -1 }))
      .toThrow('PERFORMANCE_SAMPLE_INVALID');
    expect(() => createContentReadySample({
      ...base,
      segments: { ...segments, network: Number.NaN },
    })).toThrow('PERFORMANCE_SEGMENTS_INVALID');
    expect(() => createContentReadySample({
      ...base,
      segments: { ...segments, network: -1 },
    })).toThrow('PERFORMANCE_SEGMENTS_INVALID');
    expect(() => createContentReadySample({
      ...base,
      segments: { ...segments, network: 501 },
    })).toThrow('PERFORMANCE_SEGMENTS_INVALID');
    expect(() => createContentReadySample({
      ...base,
      requiredDataReady: 'false',
    } as unknown as Parameters<typeof createContentReadySample>[0]))
      .toThrow('PERFORMANCE_READINESS_INVALID');
    expect(() => createFilterReadySample({
      sampleId: 'sample-filter-runtime-1',
      startedAt: 1000,
      observedAt: 1500,
      chartReady: 'false',
      equivalentTableReady: true,
      assistiveFeedbackReady: true,
      segments,
      cacheState: 'cold',
    } as unknown as Parameters<typeof createFilterReadySample>[0]))
      .toThrow('PERFORMANCE_READINESS_INVALID');
  });

  it('retains failed samples with explicit attribution', () => {
    const collector = new PerformanceSampleCollector();
    const failed = createFailedSample({
      sampleId: 'sample-failed-1',
      eventName: 'ui.content-ready',
      surface: 'detail',
      startedAt: 1000,
      observedAt: 3100,
      failureAttribution: 'network',
      segments,
      cacheState: 'cold',
    });
    collector.record(failed);
    expect(collector.samples).toHaveLength(1);
    expect(collector.samples[0]).toMatchObject({ status: 'failure', failureAttribution: 'network' });
  });

  it('makes identical duplicate samples idempotent and rejects mismatches', () => {
    const collector = new PerformanceSampleCollector();
    const sample = createFailedSample({
      sampleId: 'sample-duplicate-1',
      eventName: 'ui.filter-ready',
      surface: 'dashboard',
      startedAt: 1000,
      observedAt: 1500,
      failureAttribution: 'render/main-thread',
      segments,
      cacheState: 'warm',
    });
    collector.record(sample);
    collector.record(sample);
    expect(collector.samples).toHaveLength(1);
    expect(() => collector.record({ ...sample, durationMs: 501 })).toThrow('PERFORMANCE_SAMPLE_MISMATCH');
  });

  it('snapshots collector input and rejects unknown sample fields', () => {
    const collector = new PerformanceSampleCollector();
    const original = {
      ...createFailedSample({
        sampleId: 'sample-snapshot-1',
        eventName: 'ui.content-ready',
        surface: 'detail',
        startedAt: 1000,
        observedAt: 1500,
        failureAttribution: 'network',
        segments,
        cacheState: 'cold',
      }),
      segments: { ...segments },
    };
    const stored = collector.record(original);
    original.observedAt = 9000;
    original.segments.network = 400;

    expect(stored.observedAt).toBe(1500);
    expect(stored.segments.network).toBe(1);
    expect(Object.isFrozen(stored)).toBe(true);
    expect(Object.isFrozen(stored.segments)).toBe(true);
    expect(() => collector.record({
      ...original,
      sampleId: 'sample-unknown-field-1',
      observedAt: 1500,
      studentName: '张三',
    } as unknown as Parameters<PerformanceSampleCollector['record']>[0]))
      .toThrow('PERFORMANCE_SAMPLE_INVALID');
  });

  it('rejects sensitive or unknown telemetry payload fields', () => {
    expect(sanitizeTelemetryPayload({
      sampleId: 'sample-safe-1',
      traceId: 'trace-safe-1',
      surface: 'cross-client-state',
      cacheState: 'cold',
      aggregateVersion: 7,
      eventName: 'ui.state-observed',
      durationMs: 400,
      status: 'success',
      committingClientId: 'client-committing-1',
      observingClientId: 'client-observing-1',
      clockSource: 'server-synchronized-ntp',
      clockSkewMs: 100,
    })).toEqual({
      sampleId: 'sample-safe-1',
      traceId: 'trace-safe-1',
      surface: 'cross-client-state',
      cacheState: 'cold',
      aggregateVersion: 7,
      eventName: 'ui.state-observed',
      durationMs: 400,
      status: 'success',
      committingClientId: 'client-committing-1',
      observingClientId: 'client-observing-1',
      clockSource: 'server-synchronized-ntp',
      clockSkewMs: 100,
    });
    for (const payload of [
      { studentName: '张三' },
      { evidenceText: '敏感正文' },
      { ['to' + 'ken']: 'fixture-value' },
      { authorizationResult: 'allow' },
    ]) {
      expect(() => sanitizeTelemetryPayload(payload)).toThrow('TELEMETRY_FIELD_NOT_ALLOWED');
    }
  });

  it('strictly validates telemetry scalar types, enums, lengths and sensitive values', () => {
    const symbolPayload = { sampleId: 'sample-safe-1' } as Record<PropertyKey, unknown>;
    symbolPayload[Symbol('hidden')] = 'value';
    for (const payload of [
      null,
      [],
      { sampleId: { nested: 'value' } },
      { sampleId: 'a'.repeat(129) },
      { sampleId: 'studentName-zhangsan' },
      { traceId: 'token-secret-value' },
      { surface: 'student-detail' },
      { cacheState: 'disk' },
      { aggregateVersion: -1 },
      { aggregateVersion: 1.5 },
      { eventName: 'ui.gateway-ready' },
      {
        eventName: 'ui.state-observed',
        surface: 'cross-client-state',
        aggregateVersion: 12,
      },
      { durationMs: Number.POSITIVE_INFINITY },
      { status: 'partial' },
      { failureAttribution: 'database' },
      { clockSource: 'device-local', clockSkewMs: 0 },
      { clockSource: 'server-synchronized-ntp', clockSkewMs: 101 },
      { committingClientId: 'client-1', observingClientId: 'client-1' },
      symbolPayload,
    ]) {
      expect(() => sanitizeTelemetryPayload(payload)).toThrow();
    }
    expect(Object.isFrozen(sanitizeTelemetryPayload({ sampleId: 'sample-safe-1' }))).toBe(true);
  });

  it('takes one data-property snapshot without re-reading telemetry input', () => {
    let getterReads = 0;
    const getterPayload = {};
    Object.defineProperty(getterPayload, 'sampleId', {
      enumerable: true,
      get: () => {
        getterReads += 1;
        return getterReads === 1 ? 'sample-safe-1' : 'token-secret-value';
      },
    });
    expect(() => sanitizeTelemetryPayload(getterPayload)).toThrow('TELEMETRY_PAYLOAD_INVALID');
    expect(getterReads).toBe(0);

    const mutablePayload = { sampleId: 'sample-safe-1' };
    const sanitized = sanitizeTelemetryPayload(mutablePayload);
    mutablePayload.sampleId = 'token-secret-value';
    expect(sanitized).toEqual({ sampleId: 'sample-safe-1' });

    let descriptorReads = 0;
    const proxyPayload = new Proxy({}, {
      ownKeys: () => ['sampleId'],
      getOwnPropertyDescriptor: () => {
        descriptorReads += 1;
        return {
          configurable: true,
          enumerable: true,
          value: descriptorReads === 1 ? 'sample-safe-2' : 'token-secret-value',
          writable: true,
        };
      },
    });
    expect(sanitizeTelemetryPayload(proxyPayload)).toEqual({ sampleId: 'sample-safe-2' });
    expect(descriptorReads).toBe(1);
  });

  it('evaluates LCP, INP and CLS guardrails without claiming an application percentile', () => {
    expect(evaluateWebVitals({ lcpMs: 2500, inpMs: 200, cls: 0.1 })).toEqual({
      withinGuardrail: true,
      classification: 'single-sample-fixture',
    });
    expect(evaluateWebVitals({ lcpMs: 2501, inpMs: 200, cls: 0.1 }).withinGuardrail).toBe(false);
    expect(() => evaluateWebVitals({ lcpMs: -1, inpMs: 200, cls: 0.1 })).toThrow('WEB_VITAL_INVALID');
    expect(() => evaluateWebVitals({ lcpMs: 2500, inpMs: Number.NaN, cls: 0.1 }))
      .toThrow('WEB_VITAL_INVALID');
  });
});
