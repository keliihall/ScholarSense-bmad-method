<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { IdentitySessionClient, useIdentityState } from '../../identity-access';
import { AuditSearchClient, AuditSearchMemoryState, clearAuditSearchIdentityBoundary } from './audit-search';
import type { AuditSearchResponse, AuditSearchViewName } from './audit-search';
import AuditRetentionEvidencePanel from './AuditRetentionEvidencePanel.vue';

type PageState = 'loading' | 'results' | 'empty' | 'filtered-empty' | 'forbidden' | 'error' | 'degraded';
const route = useRoute();
const router = useRouter();
const identity = useIdentityState();
const identityClient = new IdentitySessionClient();
const client = new AuditSearchClient(undefined, (signal) => identityClient.csrfProof(signal));
const memory = new AuditSearchMemoryState();
const active = ref<AbortController>();
const state = ref<PageState>('loading');
const result = ref<AuditSearchResponse>();
const view = ref<AuditSearchViewName>('business');
const actorRef = ref('');
const objectRef = ref('');
const objectType = ref('');
const action = ref(textQuery('action'));
const outcome = ref(textQuery('outcome'));
const occurredFrom = ref(textQuery('occurredFrom'));
const occurredTo = ref(textQuery('occurredTo'));
const traceId = ref('');
const page = ref(numberQuery('page'));
const asOfSequence = ref<number>();
const hasFilters = computed(() => [actorRef.value, objectRef.value, objectType.value, action.value,
  outcome.value, occurredFrom.value, occurredTo.value, traceId.value].some(Boolean));
const columns = computed(() => Array.from(new Set(
  result.value?.items.flatMap((item) => Object.keys(item.fields)) ?? [],
)));

async function search(resetSnapshot = true): Promise<void> {
  active.value?.abort();
  const controller = new AbortController();
  active.value = controller;
  state.value = 'loading';
  memory.setSensitiveFilters(actorRef.value, objectRef.value);
  if (resetSnapshot) asOfSequence.value = undefined;
  await persistSafeQuery();
  try {
    const response = await client.search({
      view: view.value,
      ...memory.sensitiveFilters(),
      ...(objectType.value ? { objectType: objectType.value } : {}),
      ...(action.value ? { action: action.value } : {}),
      ...(outcome.value ? { outcome: outcome.value } : {}),
      ...(occurredFrom.value ? { occurredFrom: new Date(occurredFrom.value).toISOString() } : {}),
      ...(occurredTo.value ? { occurredTo: new Date(occurredTo.value).toISOString() } : {}),
      ...(traceId.value ? { traceId: traceId.value } : {}),
      page: page.value, size: 25,
      ...(asOfSequence.value === undefined ? {} : { asOfSequence: asOfSequence.value }),
    }, controller.signal);
    if (controller.signal.aborted) return;
    result.value = response;
    asOfSequence.value = response.asOfSequence;
    state.value = response.items.length === 0
      ? (hasFilters.value ? 'filtered-empty' : 'empty')
      : response.projectionStatus === 'degraded' ? 'degraded' : 'results';
  } catch (failure) {
    if (controller.signal.aborted) return;
    result.value = undefined;
    state.value = failure instanceof Error && failure.message === 'AUDIT_SEARCH_FORBIDDEN'
      ? 'forbidden' : 'error';
  }
}

async function clearFilters(): Promise<void> {
  actorRef.value = ''; objectRef.value = ''; objectType.value = ''; action.value = '';
  outcome.value = ''; occurredFrom.value = ''; occurredTo.value = ''; traceId.value = '';
  memory.clearSensitive('refresh'); page.value = 0;
  await search(true);
}

async function changePage(next: number): Promise<void> {
  page.value = next;
  await search(false);
}

async function persistSafeQuery(): Promise<void> {
  await router.replace({ query: {
    ...(action.value ? { action: action.value } : {}),
    ...(outcome.value ? { outcome: outcome.value } : {}),
    ...(occurredFrom.value ? { occurredFrom: occurredFrom.value } : {}),
    ...(occurredTo.value ? { occurredTo: occurredTo.value } : {}),
    ...(page.value > 0 ? { page: String(page.value) } : {}),
  } });
}

function textQuery(key: string): string {
  const value = route.query[key];
  return typeof value === 'string' ? value : '';
}

function numberQuery(key: string): number {
  const value = Number(textQuery(key));
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

function clearIdentityBoundary(reason: 'account-switch' | 'session-invalid'): void {
  clearAuditSearchIdentityBoundary(active, result, asOfSequence);
  actorRef.value = '';
  objectRef.value = '';
  memory.clearSensitive(reason);
  page.value = 0;
  state.value = 'forbidden';
}

watch(() => identity.session?.sessionPseudonym, (current, previous) => {
  if (previous !== undefined && current !== previous) {
    clearIdentityBoundary('account-switch');
    if (current !== undefined && identity.authenticated) void search(true);
  }
});
watch(() => identity.authenticated, (authenticated) => {
  if (!authenticated) {
    clearIdentityBoundary('session-invalid');
  }
});
onMounted(() => search(page.value === 0));
onBeforeUnmount(() => { active.value?.abort(); memory.clearSensitive('refresh'); });
</script>

<template>
  <section class="audit-search" aria-labelledby="audit-search-heading">
    <h2 id="audit-search-heading" tabindex="-1">授权审计检索</h2>
    <p>每次查询都会重新鉴权并先记录读取行为。当前生产角色源未启用时，本页会安全拒绝。</p>
    <form class="audit-filter" aria-label="审计筛选" @submit.prevent="page = 0; search(true)">
      <label>用途视图<select v-model="view"><option value="business">业务元数据</option><option value="technical">技术元数据</option></select></label>
      <label>用户标识<input v-model="actorRef" autocomplete="off"></label>
      <label>对象类型<input v-model="objectType" autocomplete="off"></label>
      <label>对象标识<input v-model="objectRef" autocomplete="off"></label>
      <label>动作<input v-model="action" autocomplete="off"></label>
      <label>结果<select v-model="outcome"><option value="">全部</option><option value="accepted">接受</option><option value="rejected">拒绝</option><option value="succeeded">成功</option><option value="failed">失败</option></select></label>
      <label>开始时间<input v-model="occurredFrom" type="datetime-local"></label>
      <label>结束时间<input v-model="occurredTo" type="datetime-local"></label>
      <label>Trace ID<input v-model="traceId" autocomplete="off" spellcheck="false"></label>
      <div class="audit-filter-actions">
        <button class="action-target primary-action" type="submit">查询</button>
        <button class="action-target" type="button" @click="clearFilters">清除筛选</button>
      </div>
    </form>

    <div class="audit-result-state" role="status" aria-live="polite" aria-atomic="true">
      <template v-if="state === 'loading'"><p>正在冻结快照并检索…</p><button type="button" @click="active?.abort()">取消本次查询</button></template>
      <template v-else-if="state === 'empty'"><p>当前完整快照中没有审计记录。</p><button type="button" @click="search(true)">重新查询</button></template>
      <template v-else-if="state === 'filtered-empty'"><p>当前筛选没有匹配记录。</p><button type="button" @click="clearFilters">清除筛选</button></template>
      <template v-else-if="state === 'forbidden'"><p>记录不存在或当前用途无权查看。</p><button type="button" @click="router.replace('/')">返回安全首页</button></template>
      <template v-else-if="state === 'error'"><p>审计服务暂时不可用，未返回任何结果。</p><button type="button" @click="search(false)">重试</button></template>
      <template v-else-if="state === 'degraded'"><p>投影正在追赶；以下结果完整覆盖当前冻结快照。</p><button type="button" @click="search(true)">刷新到最新完整快照</button></template>
      <p v-else>已返回当前授权字段，共 {{ result?.total ?? 0 }} 条。</p>
    </div>

    <div v-if="result?.items.length" class="audit-table-wrap" tabindex="0" aria-label="审计检索结果，可横向查看列">
      <table><caption>冻结序列 {{ result.asOfSequence }}；投影水位 {{ result.projectionWatermark }}</caption>
        <thead><tr><th v-for="column in columns" :key="column" scope="col">{{ column }}</th></tr></thead>
        <tbody><tr v-for="(item, index) in result.items" :key="String(item.fields.recordId ?? index)"><td v-for="column in columns" :key="column">{{ item.fields[column] }}</td></tr></tbody>
      </table>
    </div>
    <nav v-if="result && result.total > result.size" class="audit-pagination" aria-label="审计结果分页">
      <button type="button" :disabled="page === 0" @click="changePage(page - 1)">上一页</button>
      <span>第 {{ page + 1 }} 页</span>
      <button type="button" :disabled="(page + 1) * result.size >= result.total" @click="changePage(page + 1)">下一页</button>
    </nav>
    <AuditRetentionEvidencePanel />
  </section>
</template>
