<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQueryClient } from '@tanstack/vue-query';
import { IdentitySessionClient, useAuthorizedShellState, useIdentityState } from '../../identity-access';
import { DataSourceCatalogClient, DataSourceCatalogMemoryState, clearDataCatalogIdentityBoundary,
  dataCatalogQueryOptions, hasUsableDataCatalogAuthorization } from './data-source-catalog';
import type { CatalogDetail, CatalogPage, CatalogSummary } from './data-source-catalog';

type PageState = 'loading' | 'results' | 'empty' | 'filtered-empty' | 'forbidden' | 'error' | 'stale' | 'invalid';
const route = useRoute(); const router = useRouter(); const queryClient = useQueryClient();
const identity = useIdentityState(); const authorization = useAuthorizedShellState();
const identityClient = new IdentitySessionClient();
const client = new DataSourceCatalogClient(undefined, () => identityClient.csrfProof());
const memory = new DataSourceCatalogMemoryState();
const active = ref<AbortController>(); const response = ref<CatalogPage>(); const detail = ref<CatalogDetail>();
const state = ref<PageState>('loading'); const filter = ref(textQuery('filter')); const page = ref(numberQuery('page'));
const evidenceDigest = ref(''); const commandError = ref('');
const selectedId = computed(() => textQuery('catalogId'));
const filteredItems = computed(() => { const needle = filter.value.trim().toLowerCase();
  return (response.value?.items ?? []).filter((item) => !needle || item.catalogId.toLowerCase().includes(needle)
    || item.status.toLowerCase().includes(needle)); });
const dependencies = computed(() => new Map((detail.value?.dependencies ?? []).map((item) => [item.sourceId, item.dependencyId])));
const capabilitySignature = computed(() => { const capability = authorization.current?.entryCapabilities.find((item) => item.id === 'data-source-catalogs');
  const menu = authorization.current?.menuItems.find((item) => item.routeName === 'data-quality.catalogs');
  return `${capability?.state ?? 'missing'}|${menu?.providerState ?? 'missing'}`; });

function authorized(): boolean { return hasUsableDataCatalogAuthorization(identity.authenticated, authorization.status,
  authorization.current !== undefined, capabilitySignature.value); }
async function load(): Promise<void> {
  clear();
  if (!authorized() || identity.session === undefined || authorization.current === undefined) { state.value = 'forbidden'; return; }
  const controller = new AbortController(); active.value = controller; state.value = 'loading';
  const sessionVersion = identity.session.sessionVersion; const policyVersion = authorization.current.policyVersion;
  try {
    const pageResult = await queryClient.fetchQuery(dataCatalogQueryOptions({ sessionVersion, policyVersion, page: page.value },
      () => client.list(page.value, 20, controller.signal)));
    response.value = pageResult;
    if (!current(controller, sessionVersion, policyVersion)) return;
    const id = selectedId.value || pageResult.items[0]?.catalogId;
    if (id) { detail.value = await client.detail(id, controller.signal);
      if (!current(controller, sessionVersion, policyVersion)) return; memory.accept(detail.value); }
    setResultState();
  } catch (failure) { if (controller.signal.aborted) return;
    state.value = failure instanceof Error && failure.message === 'INGESTION_QUALITY_FORBIDDEN' ? 'forbidden' : 'error';
  } finally { if (active.value === controller) active.value = undefined;
    queryClient.removeQueries({ queryKey: ['ingestion-quality', 'data-source-catalogs'], exact: false }); }
}
function current(controller: AbortController, sessionVersion: number, policyVersion: string): boolean {
  return active.value === controller && !controller.signal.aborted && authorized()
    && identity.session?.sessionVersion === sessionVersion && authorization.current?.policyVersion === policyVersion;
}
function setResultState(): void {
  if (!response.value?.items.length) state.value = 'empty'; else if (!filteredItems.value.length) state.value = 'filtered-empty';
  else if (detail.value?.status === 'INVALID') state.value = 'invalid';
  else if (detail.value && Date.now() - Date.parse(detail.value.updatedAt) > 86_400_000) state.value = 'stale';
  else state.value = 'results';
}
async function select(item: CatalogSummary): Promise<void> { await router.replace({ query: safeQuery({ catalogId: item.catalogId }) }); await load(); }
async function applyFilter(): Promise<void> { page.value = 0; await router.replace({ query: safeQuery({ filter: filter.value || undefined, page: undefined }) }); setResultState(); }
async function validateCurrent(): Promise<void> { if (!detail.value) return; commandError.value = '';
  try { await client.validate(detail.value.catalogId, detail.value.aggregateVersion); await load(); }
  catch (failure) { commandError.value = failure instanceof Error ? failure.message : 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE'; } }
async function publishCurrent(): Promise<void> { if (!detail.value) return; commandError.value = '';
  try { await client.publish(detail.value.catalogId, detail.value.aggregateVersion, generateUuidV7(), evidenceDigest.value.trim(), crypto.randomUUID());
    evidenceDigest.value = ''; await load(); }
  catch (failure) { commandError.value = failure instanceof Error ? failure.message : 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE'; } }
function clear(): void { clearDataCatalogIdentityBoundary(active, response, detail, memory);
  queryClient.removeQueries({ queryKey: ['ingestion-quality', 'data-source-catalogs'], exact: false }); }
function clearBoundary(): void { clear(); state.value = 'forbidden'; }
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
  (next, previous) => { if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) clearBoundary(); });
watch(() => [authorization.status, authorization.current?.policyVersion, capabilitySignature.value] as const,
  (next, previous) => { if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) clearBoundary(); });
watch(filter, setResultState); onMounted(() => { if (authorized()) void load(); else state.value = 'forbidden'; }); onBeforeUnmount(clear);
</script>

<template>
  <section class="data-catalog" aria-labelledby="data-catalog-heading">
    <h2 id="data-catalog-heading" tabindex="-1">数据源目录</h2>
    <p>这里只展示当前身份拥有的数据源合同；目录发布不等于数据质量已达标或规则已投产。</p>
    <form class="catalog-filter" aria-label="目录筛选" @submit.prevent="applyFilter">
      <label>按目录 ID 或状态筛选<input v-model="filter" autocomplete="off"></label><button type="submit">应用筛选</button>
    </form>
    <div class="catalog-state" role="status" aria-live="polite" aria-atomic="true">
      <p v-if="state === 'loading'">正在重新鉴权并加载目录…</p><p v-else-if="state === 'empty'">当前授权范围内还没有数据源目录。</p>
      <p v-else-if="state === 'filtered-empty'">当前筛选没有匹配目录。</p><p v-else-if="state === 'forbidden'">记录不存在或当前用途无权查看。</p>
      <p v-else-if="state === 'error'">目录依赖暂时不可用，旧结果已清除。</p><p v-else-if="state === 'stale'">⚠ 当前目录超过 24 小时未更新，请先重新校验。</p>
      <p v-else-if="state === 'invalid'">⚠ 当前目录不可发布；请按下方错误摘要修复后创建新校验尝试。</p><p v-else>✓ 已加载当前授权目录与源合同。</p>
    </div><button v-if="state === 'error'" type="button" @click="load">重试</button>
    <div v-if="filteredItems.length" class="catalog-version-list" aria-label="目录版本">
      <button v-for="item in filteredItems" :key="item.catalogId" type="button" :aria-current="detail?.catalogId === item.catalogId ? 'page' : undefined" @click="select(item)">
        <span>{{ statusText(item.status) }}</span><span>版本 {{ item.aggregateVersion }}</span><span>{{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</span>
      </button>
    </div>
    <template v-if="detail && !['forbidden', 'error'].includes(state)">
      <section class="catalog-summary" aria-labelledby="catalog-version-heading"><h3 id="catalog-version-heading">目录版本</h3>
        <dl><div><dt>目录 ID</dt><dd>{{ detail.catalogId }}</dd></div><div><dt>合同版本</dt><dd>{{ detail.contractVersion }}</dd></div>
          <div><dt>聚合版本</dt><dd>{{ detail.aggregateVersion }}</dd></div><div><dt>发布状态</dt><dd>{{ statusText(detail.status) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ new Date(detail.updatedAt).toLocaleString('zh-CN') }}</dd></div><div><dt>内容摘要</dt><dd class="digest">{{ detail.contentDigest }}</dd></div></dl>
        <div v-if="detail.validationFailures.length" class="catalog-errors" role="alert" tabindex="-1"><h4>校验错误摘要</h4><ul>
          <li v-for="failure in detail.validationFailures" :key="`${failure.code}:${failure.fieldPath}`"><code>{{ failure.code }}</code>：{{ failure.fieldPath }}</li></ul></div>
        <p v-if="commandError" class="catalog-command-error" role="alert">操作未完成：{{ commandError }}</p>
        <div class="catalog-actions"><button type="button" @click="validateCurrent">创建新校验尝试</button>
          <form v-if="detail.status === 'PUBLISHABLE'" class="publish-form" @submit.prevent="publishCurrent"><label>目标证据集摘要
            <input v-model="evidenceDigest" required pattern="sha256:[0-9a-f]{64}" autocomplete="off" spellcheck="false"></label><button type="submit">发布此目录版本</button></form></div>
      </section>
      <div class="catalog-table-wrap" tabindex="0" aria-label="数据源合同，可横向查看全部列"><table><caption>当前目录的 {{ detail.sources.length }} 个数据源合同</caption>
        <thead><tr><th scope="col">状态</th><th scope="col">Source ID / Dependency ID</th><th scope="col">责任</th><th scope="col">业务定义与用途</th>
          <th scope="col">业务键</th><th scope="col">更新与覆盖</th><th scope="col">Schema / 质量门</th><th scope="col">SLO / 对账补数</th><th scope="col">证据</th></tr></thead>
        <tbody><tr v-for="source in detail.sources" :key="source.sourceId"><td>{{ evidenceText(source) }}</td>
          <td><strong>{{ source.sourceId }}</strong><br>{{ dependencies.get(source.sourceId) ?? '用途隔离，无规则依赖' }}</td><td>{{ source.metadata.ownerDepartment }}<br>{{ source.metadata.ownerName }}</td>
          <td>{{ source.metadata.businessDefinition }}<br><small>{{ source.purpose }}</small></td><td>{{ source.metadata.businessKeys.join('、') || '未登记' }}</td>
          <td>{{ source.metadata.updateFrequency }}<br>{{ source.metadata.coverage }}</td><td>{{ source.schemaVersion }}<br>{{ source.qualityGateVersion }}</td>
          <td>{{ source.metadata.slo }}<br>{{ source.metadata.reconciliation }}；补数 {{ source.metadata.backfillWindowDays }} 天</td>
          <td>{{ evidenceText(source) }}<br><span class="digest">{{ source.evidenceUri }}</span></td></tr></tbody></table></div>
    </template>
  </section>
</template>
