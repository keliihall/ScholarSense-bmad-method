<script setup lang="ts">
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useQueryClient } from '@tanstack/vue-query';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import VChart from 'vue-echarts';
import QualityFuseRecoveryPanel from './QualityFuseRecoveryPanel.vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthorizedShellState, useIdentityState } from '../../identity-access';
import {
  QualitySnapshotClient,
  QualitySnapshotMemoryState,
  buildQualityMetricTrends,
  clearQualitySnapshotIdentityBoundary,
  hasUsableQualitySnapshotAuthorization,
  qualityMetricCategory,
  qualityMetricDisplayValue,
  qualitySnapshotQueryOptions,
  sameQualitySnapshotIdentityGeneration,
  shouldClearQualitySnapshotQueryCache,
} from './quality-snapshots';
import type {
  QualityMetricCategory,
  QualityMetricResult,
  QualityMetricTrend,
  QualitySnapshot,
  QualitySnapshotClearReason,
  QualitySnapshotFilters,
  QualitySnapshotIdentityGeneration,
  QualitySnapshotPage,
} from './quality-snapshots';
import {
  QualityEligibilityClient,
  QualityEligibilityMemoryState,
  clearQualityEligibilityIdentityBoundary,
  qualityEligibilityQueryOptions,
  qualityEligibilityStatusText,
  shouldClearQualityEligibilityQueryCache,
} from './rule-quality-eligibilities';
import {
  QualityRecoveryTaskClient,
  QualityRecoveryTaskMemoryState,
  clearQualityRecoveryTaskIdentityBoundary,
  qualityRecoveryTaskDeliveryText,
  qualityRecoveryTaskQueryOptions,
} from './quality-recovery-tasks';
import type {
  QualityRecoveryTask,
  QualityRecoveryTaskFilters,
  QualityRecoveryTaskPage,
  QualityTaskDeliveryStatus,
} from './quality-recovery-tasks';
import type {
  QualityEligibility,
  QualityEligibilityFilters,
  QualityEligibilityPage,
  QualityEligibilityStatus,
} from './rule-quality-eligibilities';

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

type PageState = 'loading' | 'results' | 'empty' | 'filter-empty' | 'forbidden'
  | 'error' | 'degraded' | 'offline' | 'narrow';
type EligibilityState = 'loading' | 'results' | 'empty' | 'filter-empty'
  | 'forbidden' | 'error' | 'degraded';
type TaskState = EligibilityState | 'scope-required';
const route = useRoute();
const router = useRouter();
const queryClient = useQueryClient();
const identity = useIdentityState();
const authorization = useAuthorizedShellState();
const client = new QualitySnapshotClient();
const memory = new QualitySnapshotMemoryState();
const eligibilityClient = new QualityEligibilityClient();
const eligibilityMemory = new QualityEligibilityMemoryState();
const recoveryTaskClient = new QualityRecoveryTaskClient();
const recoveryTaskMemory = new QualityRecoveryTaskMemoryState();
const active = ref<AbortController>();
const eligibilityActive = ref<AbortController>();
const recoveryTaskActive = ref<AbortController>();
const response = ref<QualitySnapshotPage>();
const detail = ref<QualitySnapshot>();
const eligibilityResponse = ref<QualityEligibilityPage>();
const eligibilityDetail = ref<QualityEligibility>();
const recoveryTaskResponse = ref<QualityRecoveryTaskPage>();
const recoveryTaskDetail = ref<QualityRecoveryTask>();
const metricDetail = ref<QualityMetricResult>();
const state = ref<PageState>('loading');
const eligibilityState = ref<EligibilityState>('loading');
const recoveryTaskState = ref<TaskState>('loading');
const sourceFilter = ref(query('sourceId'));
const resultFilter = ref(query('overallResult'));
const fromFilter = ref(query('evaluatedFrom'));
const toFilter = ref(query('evaluatedTo'));
const sortDirection = ref(query('sortDirection') === 'asc' ? 'asc' : 'desc');
const eligibilityStatusFilter = ref(query('eligibilityStatus'));
const eligibilityRuleFilter = ref(query('eligibilityRuleId'));
const recoveryTaskSourceFilter = ref(query('taskSourceId'));
const recoveryTaskStatusFilter = ref(query('taskStatus'));
const selectedTrendKey = ref('');
const narrow = ref(false);
const online = ref(true);
let media: MediaQueryList | undefined;
let activeRequestKey: string | undefined;
let activeEligibilityRequestKey: string | undefined;
let activeRecoveryTaskRequestKey: string | undefined;

const capabilitySignature = computed(() => {
  const capability = authorization.current?.entryCapabilities
    .find((item) => item.id === 'quality-snapshots');
  const menu = authorization.current?.menuItems
    .find((item) => item.routeName === 'data-quality.quality-snapshots');
  return `${capability?.state ?? 'missing'}|${menu?.providerState ?? 'missing'}`;
});
const recoveryActionSignature = computed(() => authorization.current?.actionCapabilities
  .find((item) => item.actionType === 'quality-fuse.recover')?.state ?? 'missing');
const recoveryActionAvailable = computed(() => recoveryActionSignature.value === 'available');
const recoveryIdentityGeneration = computed(() => [
  identity.session?.sessionPseudonym ?? 'missing',
  identity.session?.sessionVersion ?? 'missing',
  authorization.current?.policyVersion ?? 'missing', recoveryActionSignature.value,
].join('|'));
const trends = computed(() => buildQualityMetricTrends(response.value?.items ?? []));
const selectedTrend = computed<QualityMetricTrend | undefined>(() =>
  trends.value.find((item) => trendKey(item) === selectedTrendKey.value) ?? trends.value[0]);
const failedSnapshots = computed(() => (response.value?.items ?? [])
  .filter((item) => item.overallResult === 'quality-failed'));
const groupedMetrics = computed(() => {
  const groups = new Map<QualityMetricCategory, QualityMetricResult[]>([
    ['completeness', []], ['continuity', []], ['coverage', []], ['freshness', []],
  ]);
  for (const item of detail.value?.metricResults ?? []) {
    groups.get(qualityMetricCategory(item.metricId))?.push(item);
  }
  return groups;
});
const chartOption = computed(() => {
  const trend = selectedTrend.value;
  const points = trend?.points ?? [];
  return {
    animation: false,
    aria: {
      enabled: true,
      description: trend === undefined ? '当前没有可绘制的质量趋势。'
        : `${categoryText(trend.category)} ${trend.metricId}，公式版本 ${trend.formulaVersion}。`,
    },
    grid: { left: 24, right: 16, top: 24, bottom: 40, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: points.map((item) => localTime(item.evaluatedAt)) },
    yAxis: { type: 'value', name: trend?.points[0]?.unit ?? '' },
    series: [{
      type: 'line', showSymbol: true, connectNulls: false, color: '#AF251B',
      data: points.map((item) => item.value),
    }],
  };
});

function authorized(): boolean {
  return hasUsableQualitySnapshotAuthorization(
    identity.authenticated, authorization.status,
    authorization.current !== undefined, capabilitySignature.value,
  );
}
function generation(): QualitySnapshotIdentityGeneration | undefined {
  if (!authorized() || identity.session === undefined || authorization.current === undefined) return undefined;
  return {
    sessionPseudonym: identity.session.sessionPseudonym,
    sessionVersion: identity.session.sessionVersion,
    policyVersion: authorization.current.policyVersion,
    capabilitySignature: capabilitySignature.value,
  };
}
function filters(): QualitySnapshotFilters {
  const result = query('overallResult');
  return {
    ...(query('sourceId') ? { sourceId: query('sourceId') } : {}),
    ...(result === 'quality-passed' || result === 'quality-failed' ? { overallResult: result } : {}),
    ...(query('evaluatedFrom') ? { evaluatedFrom: query('evaluatedFrom') } : {}),
    ...(query('evaluatedTo') ? { evaluatedTo: query('evaluatedTo') } : {}),
    sortField: 'evaluatedAt',
    sortDirection: query('sortDirection') === 'asc' ? 'asc' : 'desc',
    ...(query('afterEvaluatedAt') ? { afterEvaluatedAt: query('afterEvaluatedAt') } : {}),
    ...(query('afterSnapshotId') ? { afterSnapshotId: query('afterSnapshotId') } : {}),
    size: 20,
  };
}
function eligibilityFilters(): QualityEligibilityFilters {
  const status = query('eligibilityStatus');
  return {
    ...(['eligible', 'fused', 'recovering', 'missing'].includes(status)
      ? { status: status as QualityEligibilityStatus } : {}),
    ...(query('eligibilityRuleId') ? { ruleId: query('eligibilityRuleId') } : {}),
    ...(query('afterEligibilityOccurredAt')
      ? { afterOccurredAt: query('afterEligibilityOccurredAt') } : {}),
    ...(query('afterEligibilityId')
      ? { afterEligibilityId: query('afterEligibilityId') } : {}),
    size: 20,
  };
}
function recoveryTaskFilters(): QualityRecoveryTaskFilters | undefined {
  const requestedSourceId = query('taskSourceId');
  if (!requestedSourceId) return undefined;
  return {
    sourceId: requestedSourceId,
    ...(query('taskStatus') === 'open' ? { status: 'open' as const } : {}),
    ...(query('afterTaskOccurredAt')
      ? { afterOccurredAt: query('afterTaskOccurredAt') } : {}),
    ...(query('afterTaskId') ? { afterTaskId: query('afterTaskId') } : {}),
    size: 20,
  };
}
async function load(): Promise<void> {
  if (narrow.value) { clear('refresh'); state.value = 'narrow'; return; }
  if (!online.value) { clear('refresh'); state.value = 'offline'; return; }
  const requested = generation();
  if (requested === undefined) { clear('refresh'); state.value = 'forbidden'; return; }
  const requestedFilters = filters();
  const requestKey = JSON.stringify({ ...requested, ...requestedFilters });
  if (active.value !== undefined && activeRequestKey === requestKey) return;
  clear('refresh');
  void loadEligibilities(requested);
  void loadRecoveryTasks(requested);
  const controller = new AbortController();
  active.value = controller;
  activeRequestKey = requestKey;
  state.value = 'loading';
  try {
    const page = await queryClient.fetchQuery(qualitySnapshotQueryOptions({
      ...requested,
      sourceId: requestedFilters.sourceId ?? '',
      overallResult: requestedFilters.overallResult ?? '',
      evaluatedFrom: requestedFilters.evaluatedFrom ?? '',
      evaluatedTo: requestedFilters.evaluatedTo ?? '',
      sortField: requestedFilters.sortField,
      sortDirection: requestedFilters.sortDirection,
      afterEvaluatedAt: requestedFilters.afterEvaluatedAt ?? '',
      afterSnapshotId: requestedFilters.afterSnapshotId ?? '',
    }, () => client.list(requestedFilters, controller.signal)));
    if (!current(controller, requested)) return;
    response.value = page;
    memory.acceptPage(page);
    const selectedId = query('snapshotId') || page.items[0]?.snapshotId;
    if (selectedId !== undefined) {
      const selected = await client.detail(selectedId, controller.signal);
      if (!current(controller, requested)) return;
      detail.value = selected;
      memory.acceptDetail(selected);
    }
    selectedTrendKey.value = trends.value[0] === undefined ? '' : trendKey(trends.value[0]);
    state.value = page.items.length === 0
      ? hasFilters(requestedFilters) ? 'filter-empty' : 'empty'
      : authorization.status === 'degraded' ? 'degraded' : 'results';
  } catch (failure) {
    if (!current(controller, requested)) return;
    clearSnapshot('refresh');
    state.value = failure instanceof Error && failure.message === 'INGESTION_QUALITY_FORBIDDEN'
      ? 'forbidden' : 'error';
  } finally {
    if (active.value === controller) {
      active.value = undefined;
      activeRequestKey = undefined;
    }
  }
}
async function loadRecoveryTasks(requested: QualitySnapshotIdentityGeneration): Promise<void> {
  const requestedFilters = recoveryTaskFilters();
  if (requestedFilters === undefined) {
    clearRecoveryTasks('refresh');
    recoveryTaskState.value = 'scope-required';
    return;
  }
  const requestKey = JSON.stringify({ ...requested, ...requestedFilters });
  if (recoveryTaskActive.value !== undefined
      && activeRecoveryTaskRequestKey === requestKey) return;
  clearRecoveryTasks('refresh');
  const controller = new AbortController();
  recoveryTaskActive.value = controller;
  activeRecoveryTaskRequestKey = requestKey;
  recoveryTaskState.value = 'loading';
  try {
    const page = await queryClient.fetchQuery(qualityRecoveryTaskQueryOptions({
      ...requested,
      sourceId: requestedFilters.sourceId,
      status: requestedFilters.status ?? '',
      afterOccurredAt: requestedFilters.afterOccurredAt ?? '',
      afterTaskId: requestedFilters.afterTaskId ?? '',
    }, () => recoveryTaskClient.list(requestedFilters, controller.signal)));
    if (!recoveryTaskCurrent(controller, requested)) return;
    recoveryTaskResponse.value = page;
    recoveryTaskMemory.acceptPage(page);
    const selectedId = query('taskId') || page.items[0]?.taskId;
    if (selectedId !== undefined) {
      const selected = await recoveryTaskClient.detail(selectedId, controller.signal);
      if (!recoveryTaskCurrent(controller, requested)) return;
      recoveryTaskDetail.value = selected;
      recoveryTaskMemory.acceptDetail(selected);
    }
    recoveryTaskState.value = page.items.length === 0
      ? recoveryTaskHasFilters(requestedFilters) ? 'filter-empty' : 'empty'
      : authorization.status === 'degraded' ? 'degraded' : 'results';
  } catch (failure) {
    if (!recoveryTaskCurrent(controller, requested)) return;
    recoveryTaskResponse.value = undefined;
    recoveryTaskDetail.value = undefined;
    recoveryTaskMemory.clear('refresh');
    recoveryTaskState.value = failure instanceof Error
      && failure.message === 'INGESTION_QUALITY_FORBIDDEN' ? 'forbidden' : 'error';
  } finally {
    if (recoveryTaskActive.value === controller) {
      recoveryTaskActive.value = undefined;
      activeRecoveryTaskRequestKey = undefined;
    }
  }
}
async function refreshEligibilityAfterRecovery(): Promise<void> {
  const requested = generation();
  if (requested !== undefined) await loadEligibilities(requested);
}
async function loadEligibilities(requested: QualitySnapshotIdentityGeneration): Promise<void> {
  const requestedFilters = eligibilityFilters();
  const requestKey = JSON.stringify({ ...requested, ...requestedFilters });
  if (eligibilityActive.value !== undefined && activeEligibilityRequestKey === requestKey) return;
  clearEligibility('refresh');
  const controller = new AbortController();
  eligibilityActive.value = controller;
  activeEligibilityRequestKey = requestKey;
  eligibilityState.value = 'loading';
  try {
    const page = await queryClient.fetchQuery(qualityEligibilityQueryOptions({
      ...requested,
      status: requestedFilters.status ?? '',
      ruleId: requestedFilters.ruleId ?? '',
      afterOccurredAt: requestedFilters.afterOccurredAt ?? '',
      afterEligibilityId: requestedFilters.afterEligibilityId ?? '',
    }, () => eligibilityClient.list(requestedFilters, controller.signal)));
    if (!eligibilityCurrent(controller, requested)) return;
    eligibilityResponse.value = page;
    eligibilityMemory.acceptPage(page);
    const selectedId = query('eligibilityId') || page.items[0]?.eligibilityId;
    if (selectedId !== undefined) {
      const selected = await eligibilityClient.detail(selectedId, controller.signal);
      if (!eligibilityCurrent(controller, requested)) return;
      eligibilityDetail.value = selected;
      eligibilityMemory.acceptDetail(selected);
    }
    eligibilityState.value = page.items.length === 0
      ? eligibilityHasFilters(requestedFilters) ? 'filter-empty' : 'empty'
      : authorization.status === 'degraded' ? 'degraded' : 'results';
  } catch (failure) {
    if (!eligibilityCurrent(controller, requested)) return;
    eligibilityResponse.value = undefined;
    eligibilityDetail.value = undefined;
    eligibilityMemory.clear('refresh');
    eligibilityState.value = failure instanceof Error
      && failure.message === 'INGESTION_QUALITY_FORBIDDEN' ? 'forbidden' : 'error';
  } finally {
    if (eligibilityActive.value === controller) {
      eligibilityActive.value = undefined;
      activeEligibilityRequestKey = undefined;
    }
  }
}
function current(
  controller: AbortController, requested: QualitySnapshotIdentityGeneration,
): boolean {
  return active.value === controller && !controller.signal.aborted
    && !narrow.value && online.value
    && sameQualitySnapshotIdentityGeneration(requested, generation());
}
function eligibilityCurrent(
  controller: AbortController, requested: QualitySnapshotIdentityGeneration,
): boolean {
  return eligibilityActive.value === controller && !controller.signal.aborted
    && !narrow.value && online.value
    && sameQualitySnapshotIdentityGeneration(requested, generation());
}
function recoveryTaskCurrent(
  controller: AbortController, requested: QualitySnapshotIdentityGeneration,
): boolean {
  return recoveryTaskActive.value === controller && !controller.signal.aborted
    && !narrow.value && online.value
    && sameQualitySnapshotIdentityGeneration(requested, generation());
}
async function applyFilters(): Promise<void> {
  const target = {
    ...(sourceFilter.value.trim() ? { sourceId: sourceFilter.value.trim() } : {}),
    ...(resultFilter.value ? { overallResult: resultFilter.value } : {}),
    ...(fromFilter.value.trim() ? { evaluatedFrom: fromFilter.value.trim() } : {}),
    ...(toFilter.value.trim() ? { evaluatedTo: toFilter.value.trim() } : {}),
    sortField: 'evaluatedAt',
    sortDirection: sortDirection.value,
    ...eligibilityRouteQuery(),
    ...recoveryTaskRouteQuery(),
  };
  if (sameRouteQuery(target)) await load();
  else await router.replace({ query: target });
}
async function resetFilters(): Promise<void> {
  sourceFilter.value = '';
  resultFilter.value = '';
  fromFilter.value = '';
  toFilter.value = '';
  sortDirection.value = 'desc';
  const target = {
    sortField: 'evaluatedAt', sortDirection: 'desc', ...eligibilityRouteQuery(),
    ...recoveryTaskRouteQuery(),
  };
  if (sameRouteQuery(target)) await load();
  else await router.replace({ query: target });
}
async function applyEligibilityFilters(): Promise<void> {
  const target = routeStringsExcept([
    'eligibilityStatus', 'eligibilityRuleId', 'eligibilityId',
    'afterEligibilityOccurredAt', 'afterEligibilityId',
  ]);
  if (eligibilityStatusFilter.value) {
    target.eligibilityStatus = eligibilityStatusFilter.value;
  }
  if (eligibilityRuleFilter.value.trim()) {
    target.eligibilityRuleId = eligibilityRuleFilter.value.trim();
  }
  if (sameRouteQuery(target)) {
    const requested = generation();
    if (requested !== undefined) await loadEligibilities(requested);
  } else await router.replace({ query: target });
}
async function resetEligibilityFilters(): Promise<void> {
  eligibilityStatusFilter.value = '';
  eligibilityRuleFilter.value = '';
  const target = routeStringsExcept([
    'eligibilityStatus', 'eligibilityRuleId', 'eligibilityId',
    'afterEligibilityOccurredAt', 'afterEligibilityId',
  ]);
  if (sameRouteQuery(target)) {
    const requested = generation();
    if (requested !== undefined) await loadEligibilities(requested);
  } else await router.replace({ query: target });
}
async function nextEligibilityPage(): Promise<void> {
  const cursor = eligibilityResponse.value?.nextCursor;
  if (cursor === null || cursor === undefined) return;
  await router.push({ query: {
    ...routeStringsExcept(['eligibilityId', 'afterEligibilityOccurredAt', 'afterEligibilityId']),
    afterEligibilityOccurredAt: cursor.occurredAt,
    afterEligibilityId: cursor.eligibilityId,
  } });
}
async function selectEligibility(value: QualityEligibility): Promise<void> {
  await router.replace({ query: { ...route.query, eligibilityId: value.eligibilityId } });
}
async function retryEligibility(): Promise<void> {
  const requested = generation();
  if (requested !== undefined) await loadEligibilities(requested);
}
async function applyRecoveryTaskFilters(): Promise<void> {
  const target = routeStringsExcept([
    'taskSourceId', 'taskStatus', 'taskId', 'afterTaskOccurredAt', 'afterTaskId',
  ]);
  if (recoveryTaskSourceFilter.value.trim()) {
    target.taskSourceId = recoveryTaskSourceFilter.value.trim();
  }
  if (recoveryTaskStatusFilter.value === 'open') target.taskStatus = 'open';
  if (sameRouteQuery(target)) {
    const requested = generation();
    if (requested !== undefined) await loadRecoveryTasks(requested);
  } else await router.replace({ query: target });
}
async function resetRecoveryTaskFilters(): Promise<void> {
  recoveryTaskSourceFilter.value = '';
  recoveryTaskStatusFilter.value = '';
  const target = routeStringsExcept([
    'taskSourceId', 'taskStatus', 'taskId', 'afterTaskOccurredAt', 'afterTaskId',
  ]);
  if (sameRouteQuery(target)) {
    const requested = generation();
    if (requested !== undefined) await loadRecoveryTasks(requested);
  } else await router.replace({ query: target });
}
async function nextRecoveryTaskPage(): Promise<void> {
  const cursor = recoveryTaskResponse.value?.nextCursor;
  if (cursor === null || cursor === undefined) return;
  await router.push({ query: {
    ...routeStringsExcept(['taskId', 'afterTaskOccurredAt', 'afterTaskId']),
    afterTaskOccurredAt: cursor.occurredAt,
    afterTaskId: cursor.taskId,
  } });
}
async function selectRecoveryTask(value: QualityRecoveryTask): Promise<void> {
  await router.replace({ query: { ...route.query, taskId: value.taskId } });
}
async function retryRecoveryTasks(): Promise<void> {
  const requested = generation();
  if (requested !== undefined) await loadRecoveryTasks(requested);
}
async function nextPage(): Promise<void> {
  const cursor = response.value?.nextCursor;
  if (cursor === null || cursor === undefined) return;
  await router.push({ query: {
    ...baseFilterQuery(), afterEvaluatedAt: cursor.evaluatedAt,
    afterSnapshotId: cursor.snapshotId,
  } });
}
async function selectSnapshot(value: QualitySnapshot): Promise<void> {
  await router.replace({ query: { ...route.query, snapshotId: value.snapshotId } });
}
async function drillMetric(value: QualityMetricResult): Promise<void> {
  const selected = detail.value;
  const requested = generation();
  if (selected === undefined || requested === undefined) return;
  const controller = new AbortController();
  active.value?.abort();
  active.value = controller;
  try {
    const metric = await client.metric(
      selected.snapshotId, value.metricId, value.formulaId, value.formulaVersion,
      controller.signal,
    );
    if (current(controller, requested)) metricDetail.value = metric;
  } catch {
    if (current(controller, requested)) {
      clearSnapshot('refresh');
      state.value = 'error';
    }
  } finally {
    if (active.value === controller) active.value = undefined;
  }
}
function clear(reason: QualitySnapshotClearReason): void {
  clearSnapshot(reason);
  clearEligibility(reason);
  clearRecoveryTasks(reason);
}
function clearSnapshot(reason: QualitySnapshotClearReason): void {
  clearQualitySnapshotIdentityBoundary(active, response, detail, memory, reason);
  activeRequestKey = undefined;
  metricDetail.value = undefined;
  selectedTrendKey.value = '';
  if (shouldClearQualitySnapshotQueryCache(reason)) {
    queryClient.removeQueries({ queryKey: ['ingestion-quality', 'quality-snapshots'], exact: false });
  }
}
function clearEligibility(reason: QualitySnapshotClearReason): void {
  clearQualityEligibilityIdentityBoundary(
    eligibilityActive, eligibilityResponse, eligibilityDetail, eligibilityMemory, reason,
  );
  activeEligibilityRequestKey = undefined;
  if (shouldClearQualityEligibilityQueryCache(reason)) {
    queryClient.removeQueries({
      queryKey: ['ingestion-quality', 'quality-eligibilities'], exact: false,
    });
  }
}
function clearRecoveryTasks(reason: QualitySnapshotClearReason): void {
  clearQualityRecoveryTaskIdentityBoundary(
    recoveryTaskActive, recoveryTaskResponse, recoveryTaskDetail,
    recoveryTaskMemory, reason,
  );
  activeRecoveryTaskRequestKey = undefined;
  if (shouldClearQualitySnapshotQueryCache(reason)) {
    queryClient.removeQueries({
      queryKey: ['ingestion-quality', 'quality-recovery-tasks'], exact: false,
    });
  }
}
function clearBoundary(reason: QualitySnapshotClearReason): void {
  clear(reason);
  state.value = reason === 'offline' ? 'offline' : reason === 'narrow-screen' ? 'narrow' : 'forbidden';
}
function updateViewport(event: MediaQueryListEvent | MediaQueryList): void {
  const changed = narrow.value !== event.matches;
  narrow.value = event.matches;
  if (!changed) return;
  if (narrow.value) clearBoundary('narrow-screen');
  else void load();
}
function updateConnectivity(): void {
  const next = navigator.onLine;
  if (online.value === next) return;
  online.value = next;
  if (!next) clearBoundary('offline');
  else void load();
}
function query(key: string): string {
  const value = route.query[key];
  return typeof value === 'string' ? value : '';
}
function sameRouteQuery(target: Readonly<Record<string, string>>): boolean {
  const keys = Object.keys(route.query);
  return keys.length === Object.keys(target).length
    && keys.every((key) => typeof route.query[key] === 'string'
      && route.query[key] === target[key]);
}
function baseFilterQuery() {
  return {
    ...(query('sourceId') ? { sourceId: query('sourceId') } : {}),
    ...(query('overallResult') ? { overallResult: query('overallResult') } : {}),
    ...(query('evaluatedFrom') ? { evaluatedFrom: query('evaluatedFrom') } : {}),
    ...(query('evaluatedTo') ? { evaluatedTo: query('evaluatedTo') } : {}),
    sortField: 'evaluatedAt',
    sortDirection: query('sortDirection') === 'asc' ? 'asc' : 'desc',
    ...eligibilityRouteQuery(),
    ...recoveryTaskRouteQuery(),
  };
}
function eligibilityRouteQuery() {
  return {
    ...(query('eligibilityStatus')
      ? { eligibilityStatus: query('eligibilityStatus') } : {}),
    ...(query('eligibilityRuleId')
      ? { eligibilityRuleId: query('eligibilityRuleId') } : {}),
    ...(query('eligibilityId') ? { eligibilityId: query('eligibilityId') } : {}),
    ...(query('afterEligibilityOccurredAt')
      ? { afterEligibilityOccurredAt: query('afterEligibilityOccurredAt') } : {}),
    ...(query('afterEligibilityId')
      ? { afterEligibilityId: query('afterEligibilityId') } : {}),
  };
}
function recoveryTaskRouteQuery() {
  return {
    ...(query('taskSourceId') ? { taskSourceId: query('taskSourceId') } : {}),
    ...(query('taskStatus') ? { taskStatus: query('taskStatus') } : {}),
    ...(query('taskId') ? { taskId: query('taskId') } : {}),
    ...(query('afterTaskOccurredAt')
      ? { afterTaskOccurredAt: query('afterTaskOccurredAt') } : {}),
    ...(query('afterTaskId') ? { afterTaskId: query('afterTaskId') } : {}),
  };
}
function routeStringsExcept(excluded: readonly string[]): Record<string, string> {
  const target: Record<string, string> = {};
  for (const [key, value] of Object.entries(route.query)) {
    if (!excluded.includes(key) && typeof value === 'string') target[key] = value;
  }
  return target;
}
function hasFilters(value: QualitySnapshotFilters): boolean {
  return Boolean(value.sourceId || value.overallResult || value.evaluatedFrom || value.evaluatedTo);
}
function eligibilityHasFilters(value: QualityEligibilityFilters): boolean {
  return Boolean(value.status || value.ruleId);
}
function recoveryTaskHasFilters(value: QualityRecoveryTaskFilters): boolean {
  return Boolean(value.status);
}
function trendKey(value: QualityMetricTrend): string {
  return `${value.metricId}:${value.formulaId}:${value.formulaVersion}`;
}
function categoryText(value: QualityMetricCategory): string {
  return ({ completeness: '主键完整性', continuity: '连续性', coverage: '覆盖率', freshness: '新鲜度' })[value];
}
function resultText(value: QualityMetricResult['result'] | QualitySnapshot['overallResult']): string {
  return ({
    passed: '通过', failed: '未通过', 'not-applicable': '不适用',
    'quality-passed': '质量通过', 'quality-failed': '质量未通过',
  })[value];
}
function operatorText(value: QualityEligibility['operator'], threshold: number | null): string {
  if (value === 'all-of') return 'All-of（全部满足）';
  if (value === 'any-of') return 'Any-of（至少一个满足）';
  return `Threshold（至少 ${threshold ?? '—'} 个满足）`;
}
function requirementText(value: QualityEligibility['members'][number]['requirement']): string {
  return value === 'required' ? '必需' : '可选';
}
function eligibilityStatusClass(value: QualityEligibilityStatus): string {
  return `eligibility-status eligibility-${value}`;
}
function deliveryStatusClass(value: QualityTaskDeliveryStatus): string {
  return `task-delivery-status task-delivery-${value}`;
}
function deliveryStatusIcon(value: QualityTaskDeliveryStatus): string {
  return ({ pending: '○', retrying: '↻', confirmed: '✓', failed: '!' })[value];
}
function localTime(value: string): string { return new Date(value).toLocaleString('zh-CN'); }
function displayValue(value: QualityMetricResult): string {
  const display = qualityMetricDisplayValue(value);
  if (display === null) return '不适用';
  if (value.unit === 'basis-point') return `${(display / 100).toFixed(2)}%`;
  if (value.unit === 'millisecond') return `${display} ms`;
  if (value.unit === 'member-count') return `${display} 个成员`;
  return `${display} 个`;
}

watch(() => route.query, () => {
  sourceFilter.value = query('sourceId');
  resultFilter.value = query('overallResult');
  fromFilter.value = query('evaluatedFrom');
  toFilter.value = query('evaluatedTo');
  sortDirection.value = query('sortDirection') === 'asc' ? 'asc' : 'desc';
  eligibilityStatusFilter.value = query('eligibilityStatus');
  eligibilityRuleFilter.value = query('eligibilityRuleId');
  recoveryTaskSourceFilter.value = query('taskSourceId');
  recoveryTaskStatusFilter.value = query('taskStatus');
  void load();
});
watch(() => [identity.session?.sessionPseudonym, identity.session?.sessionVersion] as const,
  (next, previous) => {
    if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) {
      clearBoundary(next[0] === undefined ? 'logout'
        : next[0] !== previous[0] ? 'account-switch' : 'session-invalid');
    }
  });
watch(() => [authorization.status, authorization.current?.policyVersion,
  capabilitySignature.value, recoveryActionSignature.value] as const,
  (next, previous) => {
    if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) {
      clearBoundary('authorization-revoked');
    }
  });
onMounted(() => {
  media = window.matchMedia('(max-width: 767px)');
  narrow.value = media.matches;
  online.value = navigator.onLine;
  media.addEventListener('change', updateViewport);
  window.addEventListener('online', updateConnectivity);
  window.addEventListener('offline', updateConnectivity);
  void load();
});
onBeforeUnmount(() => {
  media?.removeEventListener('change', updateViewport);
  window.removeEventListener('online', updateConnectivity);
  window.removeEventListener('offline', updateConnectivity);
  clear('refresh');
});
</script>

<template>
  <section class="quality-snapshots" aria-labelledby="quality-snapshots-heading">
    <h2 id="quality-snapshots-heading" tabindex="-1">批次与质量快照</h2>
    <p>只读展示已评估快照及其冻结批次元数据；不会显示 receiving/sealed 批次，也不会提供手工封账、判定或发布。</p>

    <section v-if="state === 'narrow'" class="state-panel quality-lightweight" role="status" aria-live="polite">
      <h3>此功能需要桌面宽度</h3>
      <p>小于 768px 不加载质量表格或业务对象。请在桌面端重新打开本页。</p>
    </section>
    <section v-else-if="state === 'offline'" class="state-panel quality-lightweight" role="status" aria-live="polite">
      <h3>网络不可用</h3>
      <p>旧质量对象已清除且不会离线缓存；恢复网络后请显式重试。</p>
      <button type="button" @click="load">重新检查网络</button>
    </section>
    <template v-else>
      <form class="quality-filter" aria-label="质量快照筛选" @submit.prevent="applyFilters">
        <label>Source ID<input v-model="sourceFilter" placeholder="SRC-P0-CARD-001" autocomplete="off"></label>
        <label>结论<select v-model="resultFilter"><option value="">全部</option><option value="quality-passed">质量通过</option><option value="quality-failed">质量未通过</option></select></label>
        <label>评估起点（含时区）<input v-model="fromFilter" placeholder="2026-08-01T08:00:00+08:00" autocomplete="off"></label>
        <label>评估终点（含时区）<input v-model="toFilter" placeholder="2026-08-11T08:00:00+08:00" autocomplete="off"></label>
        <label>评估时间排序<select v-model="sortDirection"><option value="desc">从新到旧</option><option value="asc">从旧到新</option></select></label>
        <div class="quality-filter-actions"><button type="submit">应用筛选</button><button type="button" @click="resetFilters">清除筛选</button></div>
      </form>

      <section class="state-panel quality-state" role="status" aria-live="polite" aria-atomic="true">
        <h3>当前状态</h3>
        <p v-if="state === 'loading'">正在重新鉴权并读取不可变快照…</p>
        <p v-else-if="state === 'empty'">当前授权范围内还没有已评估质量快照。</p>
        <p v-else-if="state === 'filter-empty'">筛选条件没有匹配快照；不会把无数据显示为 0。</p>
        <p v-else-if="state === 'forbidden'">记录不存在或当前用途无权查看。</p>
        <p v-else-if="state === 'error'">快照、授权或读取审计依赖暂时不可用，旧结果已清除。</p>
        <p v-else-if="state === 'degraded'">已加载当前快照；其他授权能力暂时不可用。</p>
        <p v-else>已加载当前授权范围内的已评估质量快照。</p>
        <button v-if="state === 'error'" type="button" @click="load">重试读取</button>
        <button v-else-if="state === 'filter-empty'" type="button" @click="resetFilters">清除筛选</button>
      </section>

      <template v-if="response && response.items.length && detail && ['results', 'degraded'].includes(state)">
        <div class="quality-snapshot-list" aria-label="已评估快照">
          <button v-for="item in response.items" :key="item.snapshotId" type="button"
            :aria-current="detail.snapshotId === item.snapshotId ? 'page' : undefined" @click="selectSnapshot(item)">
            <strong>{{ item.sourceId }}</strong><span>{{ resultText(item.overallResult) }}</span>
            <span>截止 {{ localTime(item.cutoffAt) }}</span><span>评估 {{ localTime(item.evaluatedAt) }}</span>
          </button>
        </div>
        <nav class="quality-pagination" aria-label="快照分页">
          <button type="button" :disabled="!route.query.afterSnapshotId" @click="router.back()">返回上一游标</button>
          <span>本页 {{ response.items.length }} 条</span>
          <button type="button" :disabled="!response.hasMore" @click="nextPage">下一页</button>
        </nav>

        <section class="quality-summary" aria-labelledby="quality-summary-heading">
          <h3 id="quality-summary-heading">冻结快照与批次元数据</h3>
          <dl>
            <div><dt>Source</dt><dd>{{ detail.sourceId }}</dd></div>
            <div><dt>评估后状态</dt><dd>{{ resultText(detail.assessedBatchStatus) }}</dd></div>
            <div><dt>数据截止</dt><dd>{{ localTime(detail.cutoffAt) }}</dd></div>
            <div><dt>评估时间</dt><dd>{{ localTime(detail.evaluatedAt) }}</dd></div>
            <div><dt>观察窗</dt><dd>{{ localTime(detail.observationWindow.startAt) }} — {{ localTime(detail.observationWindow.endAt) }}</dd></div>
            <div><dt>影响范围</dt><dd>{{ detail.impactScopeCodes.join('、') || '无额外影响范围' }}</dd></div>
            <div><dt>QMDP / QG</dt><dd>{{ detail.qualityMetricDecisionProfileVersion }} / {{ detail.qualityGateVersion }}</dd></div>
            <div><dt>Source schema</dt><dd>{{ detail.sourceSchemaVersion }}</dd></div>
            <div><dt>Snapshot ID</dt><dd class="technical-id">{{ detail.snapshotId }}</dd></div>
            <div><dt>不可变摘要</dt><dd class="digest">{{ detail.immutableHash }}</dd></div>
          </dl>
        </section>

        <section class="quality-metrics" aria-labelledby="quality-metrics-heading">
          <h3 id="quality-metrics-heading">四类质量指标</h3>
          <div class="quality-category-grid">
            <article v-for="category in (['completeness', 'continuity', 'coverage', 'freshness'] as const)" :key="category">
              <h4>{{ categoryText(category) }}</h4>
              <p v-if="groupedMetrics.get(category)?.length === 0">本快照没有该类适用公式，不显示虚构值。</p>
              <ul v-else><li v-for="item in groupedMetrics.get(category)" :key="`${item.metricId}:${item.formulaId}`">
                <button type="button" @click="drillMetric(item)">{{ item.metricId }}：{{ displayValue(item) }}；{{ resultText(item.result) }}</button>
              </li></ul>
            </article>
          </div>
          <div v-if="metricDetail" class="quality-metric-drilldown" role="status" aria-live="polite">
            <h4>指标证据：{{ metricDetail.metricId }}</h4>
            <p>公式 {{ metricDetail.formulaId }} · 版本 {{ metricDetail.formulaVersion }}</p>
            <p>分子 {{ metricDetail.numerator }} / 分母 {{ metricDetail.denominator }}；门槛 {{ metricDetail.operator }} {{ metricDetail.thresholdNumerator }}/{{ metricDetail.thresholdDenominator }}（含边界）</p>
          </div>
        </section>

        <section class="quality-trends" aria-labelledby="quality-trends-heading">
          <h3 id="quality-trends-heading">趋势与异常范围</h3>
          <label>趋势公式（不同 formulaVersion 永不混线）
            <select v-model="selectedTrendKey"><option v-for="item in trends" :key="trendKey(item)" :value="trendKey(item)">{{ categoryText(item.category) }} · {{ item.metricId }} · {{ item.formulaVersion }}</option></select>
          </label>
          <p id="quality-chart-status" role="status" aria-live="polite">图表与下方表格共享同一只读快照 DTO。</p>
          <VChart class="quality-chart" :option="chartOption" autoresize aria-describedby="quality-chart-status" />
          <div class="quality-table-wrap" tabindex="0" aria-label="质量趋势等价数据，可横向查看">
            <table><caption>{{ selectedTrend ? `${selectedTrend.metricId} / ${selectedTrend.formulaVersion}` : '无趋势数据' }}</caption>
              <thead><tr><th scope="col">评估时间</th><th scope="col">值</th><th scope="col">单位</th><th scope="col">结论</th><th scope="col">Snapshot ID</th></tr></thead>
              <tbody><tr v-for="point in selectedTrend?.points ?? []" :key="point.snapshotId"><td>{{ localTime(point.evaluatedAt) }}</td><td>{{ point.value ?? '不适用' }}</td><td>{{ point.unit }}</td><td>{{ resultText(point.result) }}</td><td class="technical-id">{{ point.snapshotId }}</td></tr></tbody>
            </table>
          </div>
          <div class="quality-anomalies"><h4>当前页异常范围</h4>
            <p v-if="failedSnapshots.length === 0">当前页没有质量失败快照；这不是跨页总数。</p>
            <ul v-else><li v-for="item in failedSnapshots" :key="item.snapshotId">{{ item.sourceId }}：{{ localTime(item.observationWindow.startAt) }} — {{ localTime(item.observationWindow.endAt) }}；{{ item.impactScopeCodes.join('、') }}</li></ul>
          </div>
        </section>

        <section class="quality-eligibilities" aria-labelledby="quality-eligibilities-heading">
          <h3 id="quality-eligibilities-heading">规则依赖资格与组合树</h3>
          <p>这是独立的 QualityEligibility 事实，不等同于上方“质量通过”快照；单个来源可用也不代表整条规则可用。</p>

          <form class="quality-eligibility-filter" aria-label="规则依赖资格筛选"
            @submit.prevent="applyEligibilityFilters">
            <label>资格状态
              <select v-model="eligibilityStatusFilter">
                <option value="">全部</option><option value="eligible">Eligible</option>
                <option value="fused">Fused</option><option value="recovering">Recovering</option>
                <option value="missing">Missing</option>
              </select>
            </label>
            <label>Rule ID
              <input v-model="eligibilityRuleFilter" placeholder="ACC-SAFE-001" autocomplete="off">
            </label>
            <div class="quality-filter-actions">
              <button type="submit">应用资格筛选</button>
              <button type="button" @click="resetEligibilityFilters">清除资格筛选</button>
            </div>
          </form>

          <section class="state-panel quality-eligibility-state" role="status"
            aria-live="polite" aria-atomic="true">
            <h4>资格读取状态</h4>
            <p v-if="eligibilityState === 'loading'">正在逐一验证全部依赖成员的当前 owner 权限并读取组合事实…</p>
            <p v-else-if="eligibilityState === 'empty'">当前授权范围内还没有已发布的规则依赖资格事实；请等待上游快照完成评估与发布。</p>
            <p v-else-if="eligibilityState === 'filter-empty'">资格筛选没有匹配事实；不会返回空成员树或把无数据显示为 Missing。</p>
            <p v-else-if="eligibilityState === 'forbidden'">组合事实不存在，或至少一个暴露成员不在当前 owned-source 范围内。</p>
            <p v-else-if="eligibilityState === 'error'">资格存储、逐成员授权、重检或读取审计暂时不可用；技术错误没有显示为 Fused。</p>
            <p v-else-if="eligibilityState === 'degraded'">已安全加载组合事实；其他授权能力暂时不可用。</p>
            <p v-else>已加载全部成员均获授权的当前组合事实。</p>
            <button v-if="eligibilityState === 'error'" type="button" @click="retryEligibility">重试资格读取</button>
            <button v-else-if="eligibilityState === 'filter-empty'" type="button"
              @click="resetEligibilityFilters">清除资格筛选</button>
          </section>

          <template v-if="eligibilityResponse && eligibilityResponse.items.length
            && eligibilityDetail && ['results', 'degraded'].includes(eligibilityState)">
            <div class="quality-eligibility-list" aria-label="当前规则依赖资格">
              <button v-for="item in eligibilityResponse.items" :key="item.eligibilityId"
                type="button" :aria-current="eligibilityDetail.eligibilityId === item.eligibilityId
                  ? 'page' : undefined" @click="selectEligibility(item)">
                <strong>{{ item.ruleId }} @ {{ item.ruleVersion }}</strong>
                <span :class="eligibilityStatusClass(item.status)">
                  {{ qualityEligibilityStatusText(item.status) }}
                </span>
                <span>更新 {{ localTime(item.occurredAt) }}</span>
              </button>
            </div>
            <nav class="quality-pagination" aria-label="规则依赖资格分页">
              <button type="button" :disabled="!route.query.afterEligibilityId"
                @click="router.back()">返回资格上一游标</button>
              <span>本页 {{ eligibilityResponse.items.length }} 条资格事实</span>
              <button type="button" :disabled="!eligibilityResponse.hasMore"
                @click="nextEligibilityPage">资格下一页</button>
            </nav>

            <article class="quality-composition" aria-labelledby="quality-composition-heading">
              <header>
                <div>
                  <h4 id="quality-composition-heading">
                    {{ eligibilityDetail.ruleId }} @ {{ eligibilityDetail.ruleVersion }}
                  </h4>
                  <p>{{ operatorText(eligibilityDetail.operator, eligibilityDetail.threshold) }}</p>
                </div>
                <strong :class="eligibilityStatusClass(eligibilityDetail.status)">
                  {{ qualityEligibilityStatusText(eligibilityDetail.status) }}
                </strong>
              </header>
              <dl>
                <div><dt>稳定原因</dt><dd>{{ eligibilityDetail.reasonCode }}</dd></div>
                <div><dt>Registry</dt><dd>{{ eligibilityDetail.registryVersion }}</dd></div>
                <div><dt>事实版本</dt><dd>{{ eligibilityDetail.aggregateVersion }}</dd></div>
                <div><dt>生效时间</dt><dd>{{ localTime(eligibilityDetail.effectiveAt) }}</dd></div>
                <div><dt>更新时间</dt><dd>{{ localTime(eligibilityDetail.occurredAt) }}</dd></div>
                <div><dt>Eligibility ID</dt><dd class="technical-id">{{ eligibilityDetail.eligibilityId }}</dd></div>
              </dl>
              <div class="quality-failed-members">
                <h5>未满足成员</h5>
                <p v-if="eligibilityDetail.failedMembers.length === 0">无；全部必需成员当前均可用。</p>
                <ul v-else><li v-for="member in eligibilityDetail.failedMembers" :key="member">{{ member }}</li></ul>
              </div>
              <div class="quality-table-wrap" tabindex="0" aria-label="规则依赖成员组合，可横向查看">
                <table>
                  <caption>{{ eligibilityDetail.ruleId }} 的 {{ eligibilityDetail.members.length }} 个组合成员</caption>
                  <thead><tr>
                    <th scope="col">Source → Dependency</th><th scope="col">版本</th>
                    <th scope="col">要求</th><th scope="col">成员状态</th>
                    <th scope="col">连续</th><th scope="col">水位</th><th scope="col">快照引用</th>
                  </tr></thead>
                  <tbody><tr v-for="member in eligibilityDetail.members" :key="member.dependencyId">
                    <td><strong>{{ member.sourceId }}</strong><br>→ {{ member.dependencyId }}</td>
                    <td>source {{ member.sourceVersion }}<br>dependency {{ member.dependencyVersion }}</td>
                    <td>{{ requirementText(member.requirement) }}</td>
                    <td><span :class="eligibilityStatusClass(member.state)">
                      {{ qualityEligibilityStatusText(member.state) }}
                    </span></td>
                    <td>{{ member.versionContinuous ? '连续' : '不连续（失败关闭）' }}</td>
                    <td class="watermark">source: {{ member.sourceWatermark }}<br>
                      dependency: {{ member.dependencyWatermark }}</td>
                    <td class="technical-id">{{ member.snapshotId ?? '无快照（Missing）' }}<br>
                      <span class="digest">{{ member.snapshotImmutableHash ?? '无不可变摘要' }}</span></td>
                  </tr></tbody>
                </table>
              </div>
            </article>
          </template>
        </section>
      </template>

        <section class="quality-recovery-tasks" aria-labelledby="quality-recovery-tasks-heading">
          <h3 id="quality-recovery-tasks-heading">质量异常任务与投递状态</h3>
          <p>RecoveryTask 是独立的业务任务；TaskDelivery 只说明外部投递，不表示任务受理、质量修复或解除熔断。</p>

          <form class="quality-recovery-task-filter" aria-label="质量异常任务筛选"
            @submit.prevent="applyRecoveryTaskFilters">
            <label>任务 Source ID
              <input v-model="recoveryTaskSourceFilter"
                placeholder="SRC-P0-CAMPUS-ACCESS-001" autocomplete="off" required>
            </label>
            <label>任务状态
              <select v-model="recoveryTaskStatusFilter">
                <option value="">全部</option><option value="open">Open（待处置）</option>
              </select>
            </label>
            <div class="quality-filter-actions">
              <button type="submit">应用任务筛选</button>
              <button type="button" @click="resetRecoveryTaskFilters">清除任务筛选</button>
            </div>
          </form>

          <section class="state-panel quality-recovery-task-state" role="status"
            aria-live="polite" aria-atomic="true">
            <h4>任务读取状态</h4>
            <p v-if="recoveryTaskState === 'loading'">正在重检当前 source owner 权限并读取质量任务…</p>
            <p v-else-if="recoveryTaskState === 'scope-required'">请输入一个任务 Source ID 作为 owner 授权范围；不会执行无范围的全局查询。</p>
            <p v-else-if="recoveryTaskState === 'empty'">当前授权来源还没有质量异常任务；请继续查看质量资格事实。</p>
            <p v-else-if="recoveryTaskState === 'filter-empty'">任务筛选没有匹配项；请清除筛选后重试。</p>
            <p v-else-if="recoveryTaskState === 'forbidden'">任务不存在或不属于当前 source owner；未暴露总数、分页或详情。</p>
            <p v-else-if="recoveryTaskState === 'error'">任务存储、当前授权重检或读取审计暂时不可用；旧任务已清除。</p>
            <p v-else-if="recoveryTaskState === 'degraded'">已加载当前 source-owned 任务分片；其他授权能力暂时不可用。</p>
            <p v-else>已加载当前 source-owned 质量异常任务。</p>
            <button v-if="recoveryTaskState === 'error'" type="button"
              @click="retryRecoveryTasks">重试任务读取</button>
            <button v-else-if="recoveryTaskState === 'filter-empty'" type="button"
              @click="resetRecoveryTaskFilters">清除任务筛选</button>
          </section>

          <template v-if="recoveryTaskResponse && recoveryTaskResponse.items.length
            && recoveryTaskDetail && ['results', 'degraded'].includes(recoveryTaskState)">
            <div class="quality-recovery-task-list" aria-label="当前质量异常任务">
              <button v-for="item in recoveryTaskResponse.items" :key="item.taskId"
                type="button" :aria-current="recoveryTaskDetail.taskId === item.taskId
                  ? 'page' : undefined" @click="selectRecoveryTask(item)">
                <strong>{{ item.sourceId }}</strong>
                <span>{{ item.dependencyId }}</span>
                <span>Open · {{ item.priority }} · 截止 {{ localTime(item.dueAt) }}</span>
                <span :class="deliveryStatusClass(item.taskDelivery.status)">
                  <span aria-hidden="true">{{ deliveryStatusIcon(item.taskDelivery.status) }}</span>
                  {{ qualityRecoveryTaskDeliveryText(item.taskDelivery.status) }}
                </span>
              </button>
            </div>
            <nav class="quality-pagination" aria-label="质量异常任务分页">
              <button type="button" :disabled="!route.query.afterTaskId"
                @click="router.back()">返回任务上一游标</button>
              <span>本页 {{ recoveryTaskResponse.items.length }} 条 source-owned 任务</span>
              <button type="button" :disabled="!recoveryTaskResponse.hasMore"
                @click="nextRecoveryTaskPage">任务下一页</button>
            </nav>

            <article class="quality-recovery-task-detail"
              aria-labelledby="quality-recovery-task-detail-heading">
              <header>
                <div>
                  <h4 id="quality-recovery-task-detail-heading">门禁数据连续性未达门槛，相关规则已暂停产出</h4>
                  <p>{{ recoveryTaskDetail.sourceId }} → {{ recoveryTaskDetail.dependencyId }}</p>
                </div>
                <strong class="task-business-status">Open（待处置）</strong>
              </header>
              <dl>
                <div><dt>责任归属</dt><dd>{{ recoveryTaskDetail.ownerRef }}</dd></div>
                <div><dt>优先级 / 截止</dt><dd>{{ recoveryTaskDetail.priority }} / {{ localTime(recoveryTaskDetail.dueAt) }}</dd></div>
                <div><dt>熔断 episode</dt><dd>第 {{ recoveryTaskDetail.episodeGeneration }} 代</dd></div>
                <div><dt>触发原因</dt><dd>{{ recoveryTaskDetail.trigger.reasonCode }}</dd></div>
                <div><dt>水位</dt><dd class="watermark">{{ recoveryTaskDetail.watermark }}</dd></div>
                <div><dt>QG / QMDP / QSHM</dt><dd>{{ recoveryTaskDetail.currentEvidence.qualityGateVersion }} / {{ recoveryTaskDetail.currentEvidence.qmdpVersion }} / {{ recoveryTaskDetail.currentEvidence.qshmVersion }}</dd></div>
                <div><dt>Task ID</dt><dd class="technical-id">{{ recoveryTaskDetail.taskId }}</dd></div>
                <div><dt>Episode ID</dt><dd class="technical-id">{{ recoveryTaskDetail.episodeId }}</dd></div>
              </dl>
              <section class="quality-affected-rules" aria-labelledby="quality-affected-rules-heading">
                <h5 id="quality-affected-rules-heading">受影响规则范围</h5>
                <ul><li v-for="rule in recoveryTaskDetail.affectedRules"
                  :key="`${rule.ruleId}@${rule.ruleVersion}`">
                  {{ rule.ruleId }} @ {{ rule.ruleVersion }}
                </li></ul>
              </section>
              <section class="quality-task-delivery" aria-labelledby="quality-task-delivery-heading">
                <h5 id="quality-task-delivery-heading">独立 TaskDelivery</h5>
                <p :class="deliveryStatusClass(recoveryTaskDetail.taskDelivery.status)">
                  <span aria-hidden="true">{{ deliveryStatusIcon(recoveryTaskDetail.taskDelivery.status) }}</span>
                  {{ qualityRecoveryTaskDeliveryText(recoveryTaskDetail.taskDelivery.status) }}
                </p>
                <p>目标 {{ recoveryTaskDetail.taskDelivery.target }}；尝试 {{ recoveryTaskDetail.taskDelivery.attempt }} 次<span v-if="recoveryTaskDetail.taskDelivery.nextAttemptAt">；下次 {{ localTime(recoveryTaskDetail.taskDelivery.nextAttemptAt) }}</span>。</p>
              </section>
              <QualityFuseRecoveryPanel :task="recoveryTaskDetail"
                :available="recoveryActionAvailable"
                :identity-generation="recoveryIdentityGeneration"
                :online="online" :narrow="narrow"
                @completed="refreshEligibilityAfterRecovery" />
            </article>
          </template>
        </section>

        <section class="quality-pending" aria-labelledby="quality-pending-heading">
          <h3 id="quality-pending-heading">后续恢复能力边界</h3>
          <p><strong>解除熔断与 Eligible：</strong>not-available（Story 2.5c）</p>
          <p>仅在独立 action capability 可用时显示 Fused → Recovering；本页不提供手工 pass、fuse、eligible 或 close 动作。</p>
        </section>
    </template>
  </section>
</template>
