<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQueryClient } from '@tanstack/vue-query';
import { IdentitySessionClient, useAuthorizedShellState, useIdentityState } from '../../identity-access';
import { DataSourceCatalogClient, DataSourceCatalogMemoryState, DataSourceCatalogPublicationMemory,
  clearDataCatalogIdentityBoundary, dataCatalogQueryOptions, hasUsableDataCatalogAuthorization,
  retainPublicationProof, sameDataCatalogIdentityGeneration } from './data-source-catalog';
import type { CatalogDetail, CatalogPage, CatalogSummary, DataCatalogClearReason,
  DataCatalogIdentityGeneration } from './data-source-catalog';

type PageState = 'loading' | 'results' | 'empty' | 'filtered-empty' | 'forbidden' | 'error' | 'stale' | 'invalid';
const route = useRoute(); const router = useRouter(); const queryClient = useQueryClient();
const identity = useIdentityState(); const authorization = useAuthorizedShellState();
const identityClient = new IdentitySessionClient();
const client = new DataSourceCatalogClient(undefined, (signal) => identityClient.csrfProof(signal));
const memory = new DataSourceCatalogMemoryState();
const publications = new DataSourceCatalogPublicationMemory();
const active = ref<AbortController>(); const response = ref<CatalogPage>(); const detail = ref<CatalogDetail>();
const state = ref<PageState>('loading'); const filter = ref(textQuery('filter')); const page = ref(numberQuery('page'));
const commandError = ref('');
const selectedId = computed(() => textQuery('catalogId'));
const filteredItems = computed(() => { const needle = filter.value.trim().toLowerCase();
  return (response.value?.items ?? []).filter((item) => !needle || item.catalogId.toLowerCase().includes(needle)
    || item.status.toLowerCase().includes(needle)); });
const visibleDetail = computed(() => detail.value !== undefined
  && filteredItems.value.some((item) => item.catalogId === detail.value?.catalogId) ? detail.value : undefined);
const dependencies = computed(() => new Map((visibleDetail.value?.dependencies ?? []).map((item) => [item.sourceId, item.dependencyId])));
const capabilitySignature = computed(() => { const capability = authorization.current?.entryCapabilities.find((item) => item.id === 'data-source-catalogs');
  const menu = authorization.current?.menuItems.find((item) => item.routeName === 'data-quality.catalogs');
  return `${capability?.state ?? 'missing'}|${menu?.providerState ?? 'missing'}`; });

function authorized(): boolean { return hasUsableDataCatalogAuthorization(identity.authenticated, authorization.status,
  authorization.current !== undefined, capabilitySignature.value); }
function identityGeneration(): DataCatalogIdentityGeneration | undefined {
  if (!authorized() || identity.session === undefined || authorization.current === undefined) return undefined;
  return {
    sessionPseudonym: identity.session.sessionPseudonym,
    sessionVersion: identity.session.sessionVersion,
    policyVersion: authorization.current.policyVersion,
    capabilitySignature: capabilitySignature.value,
  };
}
async function load(): Promise<void> {
  clear();
  const generation = identityGeneration();
  if (generation === undefined) { state.value = 'forbidden'; return; }
  const controller = new AbortController(); active.value = controller; state.value = 'loading';
  try {
    const pageResult = await queryClient.fetchQuery(dataCatalogQueryOptions({
      sessionVersion: generation.sessionVersion, policyVersion: generation.policyVersion, page: page.value,
    },
      () => client.list(page.value, 20, controller.signal)));
    if (!current(controller, generation)) return;
    response.value = pageResult;
    const id = selectedId.value || pageResult.items[0]?.catalogId;
    if (id) {
      const detailResult = await client.detail(id, controller.signal);
      if (!current(controller, generation)) return;
      detail.value = detailResult; memory.accept(detailResult);
    }
    setResultState();
  } catch (failure) { if (!current(controller, generation)) return;
    const failedState = failure instanceof Error && failure.message === 'INGESTION_QUALITY_FORBIDDEN' ? 'forbidden' : 'error';
    clear(); state.value = failedState;
  } finally { if (active.value === controller) active.value = undefined;
    queryClient.removeQueries({ queryKey: ['ingestion-quality', 'data-source-catalogs'], exact: false }); }
}
function current(controller: AbortController, generation: DataCatalogIdentityGeneration): boolean {
  return active.value === controller && !controller.signal.aborted
    && sameDataCatalogIdentityGeneration(generation, identityGeneration());
}
function setResultState(): void {
  if (!response.value?.items.length) state.value = 'empty'; else if (!filteredItems.value.length) state.value = 'filtered-empty';
  else if (visibleDetail.value?.status === 'INVALID') state.value = 'invalid';
  else if (visibleDetail.value && Date.now() - Date.parse(visibleDetail.value.updatedAt) > 86_400_000) state.value = 'stale';
  else state.value = 'results';
}
async function select(item: CatalogSummary): Promise<void> { await router.replace({ query: safeQuery({ catalogId: item.catalogId }) }); await load(); }
async function applyFilter(): Promise<void> {
  const match = filteredItems.value.find((item) => item.catalogId === detail.value?.catalogId)
    ?? filteredItems.value[0];
  await router.replace({ query: safeQuery({
    filter: filter.value || undefined,
    catalogId: match?.catalogId,
  }) });
  if (match !== undefined && match.catalogId !== detail.value?.catalogId) await load();
  else {
    if (match === undefined) { detail.value = undefined; memory.clear('refresh'); }
    setResultState();
  }
}
async function changePage(nextPage: number): Promise<void> {
  if (!Number.isSafeInteger(nextPage) || nextPage < 0
    || (nextPage > page.value && response.value?.hasMore !== true)) return;
  page.value = nextPage;
  await router.replace({ query: safeQuery({
    page: nextPage === 0 ? undefined : String(nextPage), catalogId: undefined,
  }) });
  await load();
}
async function validateCurrent(): Promise<void> {
  const target = detail.value; const generation = identityGeneration();
  if (target === undefined || generation === undefined) return;
  active.value?.abort(); const controller = new AbortController(); active.value = controller; commandError.value = '';
  try {
    await client.validate(target.catalogId, target.aggregateVersion, controller.signal);
    if (!current(controller, generation)) return;
    await load();
  } catch (failure) {
    if (!current(controller, generation)) return;
    commandError.value = failure instanceof Error ? failure.message : 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE';
  } finally { if (active.value === controller) active.value = undefined; }
}
async function publishCurrent(): Promise<void> {
  const target = detail.value; const generation = identityGeneration();
  if (target === undefined || generation === undefined) return;
  active.value?.abort(); const controller = new AbortController(); active.value = controller; commandError.value = '';
  const command = publications.prepare({
    catalogId: target.catalogId,
    expectedVersion: target.aggregateVersion,
    expectedCurrentVersion: target.currentPointerVersion,
  }, generateUuidV7, () => crypto.randomUUID());
  try {
    await client.publish(command.catalogId, command.expectedVersion, command.expectedCurrentVersion,
      command.catalogReleaseId, command.idempotencyKey, controller.signal);
    if (!current(controller, generation)) return;
    publications.complete(command); await load();
  } catch (failure) {
    if (!current(controller, generation)) return;
    commandError.value = failure instanceof Error ? failure.message : 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE';
    if (!retainPublicationProof(failure)) {
      publications.complete(command);
      await load();
    }
  } finally { if (active.value === controller) active.value = undefined; }
}
function clear(reason: DataCatalogClearReason = 'refresh'): void {
  clearDataCatalogIdentityBoundary(active, response, detail, memory, reason);
  queryClient.removeQueries({ queryKey: ['ingestion-quality', 'data-source-catalogs'], exact: false }); }
function clearBoundary(reason: DataCatalogClearReason): void {
  clear(reason); filter.value = ''; page.value = 0; commandError.value = '';
  publications.clear(reason); state.value = 'forbidden';
  if (route.name === 'data-quality-catalogs' && Object.keys(route.query).length > 0) {
    void router.replace({ query: {} });
  }
}
function textQuery(key: string): string { const value = route.query[key]; return typeof value === 'string' ? value : ''; }
function numberQuery(key: string): number { const value = Number(textQuery(key)); return Number.isSafeInteger(value) && value >= 0 ? value : 0; }
function safeQuery(next: Record<string, string | undefined>) { return { ...(filter.value ? { filter: filter.value } : {}),
  ...(page.value ? { page: String(page.value) } : {}), ...(selectedId.value ? { catalogId: selectedId.value } : {}), ...next }; }
function generateUuidV7(): string { const timestamp = Date.now().toString(16).padStart(12, '0');
  const random = [...crypto.getRandomValues(new Uint8Array(10))].map((value) => value.toString(16).padStart(2, '0')).join('');
  const variant = ((Number.parseInt(random[3] ?? '0', 16) & 3) | 8).toString(16);
  return `${timestamp.slice(0, 8)}-${timestamp.slice(8)}-7${random.slice(0, 3)}-${variant}${random.slice(4, 7)}-${random.slice(7, 19)}`; }
function statusText(status: CatalogSummary['status']): string { return ({ DRAFT: '○ 草稿', INVALID: '⚠ 无效', PUBLISHABLE: '✓ 可发布', PUBLISHED: '● 已发布' })[status]; }
function evidenceText(source: CatalogDetail['sources'][number]): string { return source.runtimeEvidenceClaim === 'TARGET_VERIFIED' ? '✓ 目标证据已验证' : '○ 仅合同，等待目标证据'; }
watch(() => [identity.session?.sessionPseudonym, identity.session?.sessionVersion] as const,
  (next, previous) => { if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) {
    clearBoundary(next[0] === undefined ? 'logout' : next[0] !== previous[0] ? 'account-switch' : 'session-invalid');
  } });
watch(() => [authorization.status, authorization.current?.policyVersion, capabilitySignature.value] as const,
  (next, previous) => { if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) {
    clearBoundary('authorization-revoked');
  } });
watch(filter, setResultState); onMounted(() => { if (authorized()) void load(); else state.value = 'forbidden'; });
onBeforeUnmount(() => { clear('refresh'); publications.clear('refresh'); commandError.value = ''; });
</script>

<template>
  <section class="data-catalog" aria-labelledby="data-catalog-heading">
    <h2 id="data-catalog-heading" tabindex="-1">数据源目录</h2>
    <p>这里只展示当前身份拥有的数据源合同；目录发布不等于数据质量已达标或规则已投产。</p>
    <form class="catalog-filter" aria-label="目录筛选" @submit.prevent="applyFilter">
      <label>在当前页按目录 ID 或状态筛选<input v-model="filter" autocomplete="off"></label><button type="submit">应用筛选</button>
    </form>
    <div class="catalog-state" role="status" aria-live="polite" aria-atomic="true">
      <p v-if="state === 'loading'">正在重新鉴权并加载目录…</p><p v-else-if="state === 'empty'">当前授权范围内还没有数据源目录。</p>
      <p v-else-if="state === 'filtered-empty'">当前页筛选没有匹配目录；可翻页继续查找。</p><p v-else-if="state === 'forbidden'">记录不存在或当前用途无权查看。</p>
      <p v-else-if="state === 'error'">目录依赖暂时不可用，旧结果已清除。</p><p v-else-if="state === 'stale'">⚠ 当前目录超过 24 小时未更新，请先重新校验。</p>
      <p v-else-if="state === 'invalid'">⚠ 当前目录不可发布；请按下方错误摘要修复后创建新校验尝试。</p><p v-else>✓ 已加载当前授权目录与源合同。</p>
    </div><button v-if="state === 'error'" type="button" @click="load">重试</button>
    <div v-if="filteredItems.length" class="catalog-version-list" aria-label="目录版本">
      <button v-for="item in filteredItems" :key="item.catalogId" type="button" :aria-current="visibleDetail?.catalogId === item.catalogId ? 'page' : undefined" @click="select(item)">
        <span>{{ statusText(item.status) }}</span><span>版本 {{ item.aggregateVersion }}</span><span>{{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</span>
      </button>
    </div>
    <nav v-if="response" class="catalog-pagination" aria-label="目录分页">
      <button type="button" :disabled="page === 0" @click="changePage(page - 1)">上一页</button>
      <span aria-live="polite">第 {{ response.page + 1 }} 页</span>
      <button type="button" :disabled="!response.hasMore" @click="changePage(page + 1)">下一页</button>
    </nav>
    <template v-if="visibleDetail && !['forbidden', 'error', 'empty', 'filtered-empty'].includes(state)">
      <section class="catalog-summary" aria-labelledby="catalog-version-heading"><h3 id="catalog-version-heading">目录版本</h3>
        <dl><div><dt>目录 ID</dt><dd>{{ visibleDetail.catalogId }}</dd></div><div><dt>合同版本</dt><dd>{{ visibleDetail.contractVersion }}</dd></div>
          <div><dt>聚合版本</dt><dd>{{ visibleDetail.aggregateVersion }}</dd></div><div><dt>发布状态</dt><dd>{{ statusText(visibleDetail.status) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ new Date(visibleDetail.updatedAt).toLocaleString('zh-CN') }}</dd></div><div><dt>内容摘要</dt><dd class="digest">{{ visibleDetail.contentDigest }}</dd></div></dl>
        <div v-if="visibleDetail.validationFailures.length" class="catalog-errors" role="alert" tabindex="-1"><h4>校验错误摘要</h4><ul>
          <li v-for="failure in visibleDetail.validationFailures" :key="`${failure.code}:${failure.fieldPath}`"><code>{{ failure.code }}</code>：{{ failure.fieldPath }}</li></ul></div>
        <p v-if="commandError" class="catalog-command-error" role="alert">操作未完成：{{ commandError }}</p>
        <div class="catalog-actions"><button type="button" @click="validateCurrent">创建新校验尝试</button>
          <button v-if="visibleDetail.status === 'PUBLISHABLE'" type="button" @click="publishCurrent">发布此目录版本</button></div>
      </section>
      <div class="catalog-table-wrap" tabindex="0" aria-label="数据源合同，可横向查看全部列"><table><caption>当前目录的 {{ visibleDetail.sources.length }} 个数据源合同</caption>
        <thead><tr><th scope="col">状态</th><th scope="col">Source ID / Dependency ID</th><th scope="col">责任</th><th scope="col">业务定义与用途</th>
          <th scope="col">业务键</th><th scope="col">更新与覆盖</th><th scope="col">Schema / 质量门</th><th scope="col">SLO / 对账补数</th><th scope="col">证据</th></tr></thead>
        <tbody><tr v-for="source in visibleDetail.sources" :key="source.sourceId"><td>{{ evidenceText(source) }}</td>
          <td><strong>{{ source.sourceId }}</strong><br>{{ dependencies.get(source.sourceId) ?? '用途隔离，无规则依赖' }}</td><td>{{ source.metadata.ownerDepartment }}<br>{{ source.metadata.ownerName }}</td>
          <td>{{ source.metadata.businessDefinition }}<br><small>{{ source.purpose }}</small></td><td>{{ source.metadata.businessKeys.join('、') || '未登记' }}</td>
          <td>{{ source.metadata.updateFrequency }}<br>{{ source.metadata.coverage }}</td><td>{{ source.schemaVersion }}<br>{{ source.qualityGateVersion }}</td>
          <td>{{ source.metadata.slo }}<br>{{ source.metadata.reconciliation }}；补数 {{ source.metadata.backfillWindowDays }} 天</td>
          <td>{{ evidenceText(source) }}<br><span class="digest">{{ source.evidenceUri }}</span></td></tr></tbody></table></div>
    </template>
  </section>
</template>
