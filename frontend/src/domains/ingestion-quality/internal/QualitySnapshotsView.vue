<script setup lang="ts">
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useQueryClient } from '@tanstack/vue-query';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import VChart from 'vue-echarts';
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

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

type PageState = 'loading' | 'results' | 'empty' | 'filter-empty' | 'forbidden'
  | 'error' | 'degraded' | 'offline' | 'narrow';
const route = useRoute();
const router = useRouter();
const queryClient = useQueryClient();
const identity = useIdentityState();
const authorization = useAuthorizedShellState();
const client = new QualitySnapshotClient();
const memory = new QualitySnapshotMemoryState();
const active = ref<AbortController>();
const response = ref<QualitySnapshotPage>();
const detail = ref<QualitySnapshot>();
const metricDetail = ref<QualityMetricResult>();
const state = ref<PageState>('loading');
const sourceFilter = ref(query('sourceId'));
const resultFilter = ref(query('overallResult'));
const fromFilter = ref(query('evaluatedFrom'));
const toFilter = ref(query('evaluatedTo'));
const sortDirection = ref(query('sortDirection') === 'asc' ? 'asc' : 'desc');
const selectedTrendKey = ref('');
const narrow = ref(false);
const online = ref(true);
let media: MediaQueryList | undefined;
let activeRequestKey: string | undefined;

const capabilitySignature = computed(() => {
  const capability = authorization.current?.entryCapabilities
    .find((item) => item.id === 'quality-snapshots');
  const menu = authorization.current?.menuItems
    .find((item) => item.routeName === 'data-quality.quality-snapshots');
  return `${capability?.state ?? 'missing'}|${menu?.providerState ?? 'missing'}`;
});
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
async function load(): Promise<void> {
  if (narrow.value) { clear('refresh'); state.value = 'narrow'; return; }
  if (!online.value) { clear('refresh'); state.value = 'offline'; return; }
  const requested = generation();
  if (requested === undefined) { clear('refresh'); state.value = 'forbidden'; return; }
  const requestedFilters = filters();
  const requestKey = JSON.stringify({ ...requested, ...requestedFilters });
  if (active.value !== undefined && activeRequestKey === requestKey) return;
  clear('refresh');
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
    clear('refresh');
    state.value = failure instanceof Error && failure.message === 'INGESTION_QUALITY_FORBIDDEN'
      ? 'forbidden' : 'error';
  } finally {
    if (active.value === controller) {
      active.value = undefined;
      activeRequestKey = undefined;
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
async function applyFilters(): Promise<void> {
  const target = {
    ...(sourceFilter.value.trim() ? { sourceId: sourceFilter.value.trim() } : {}),
    ...(resultFilter.value ? { overallResult: resultFilter.value } : {}),
    ...(fromFilter.value.trim() ? { evaluatedFrom: fromFilter.value.trim() } : {}),
    ...(toFilter.value.trim() ? { evaluatedTo: toFilter.value.trim() } : {}),
    sortField: 'evaluatedAt',
    sortDirection: sortDirection.value,
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
  const target = { sortField: 'evaluatedAt', sortDirection: 'desc' };
  if (sameRouteQuery(target)) await load();
  else await router.replace({ query: target });
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
      clear('refresh');
      state.value = 'error';
    }
  } finally {
    if (active.value === controller) active.value = undefined;
  }
}
function clear(reason: QualitySnapshotClearReason): void {
  clearQualitySnapshotIdentityBoundary(active, response, detail, memory, reason);
  activeRequestKey = undefined;
  metricDetail.value = undefined;
  selectedTrendKey.value = '';
  if (shouldClearQualitySnapshotQueryCache(reason)) {
    queryClient.removeQueries({ queryKey: ['ingestion-quality', 'quality-snapshots'], exact: false });
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
  };
}
function hasFilters(value: QualitySnapshotFilters): boolean {
  return Boolean(value.sourceId || value.overallResult || value.evaluatedFrom || value.evaluatedTo);
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
  void load();
});
watch(() => [identity.session?.sessionPseudonym, identity.session?.sessionVersion] as const,
  (next, previous) => {
    if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) {
      clearBoundary(next[0] === undefined ? 'logout'
        : next[0] !== previous[0] ? 'account-switch' : 'session-invalid');
    }
  });
watch(() => [authorization.status, authorization.current?.policyVersion, capabilitySignature.value] as const,
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

        <section class="quality-pending" aria-labelledby="quality-pending-heading">
          <h3 id="quality-pending-heading">后续能力边界</h3>
          <p><strong>依赖资格与组合树：</strong>pending-story-execution/not-available（Story 2.4）</p>
          <p><strong>熔断、恢复观察与 eligible：</strong>pending-story-execution/not-available（Story 2.5）</p>
          <p>此处不返回空依赖数组、假 eligible 或恢复动作。</p>
        </section>
      </template>
    </template>
  </section>
</template>
