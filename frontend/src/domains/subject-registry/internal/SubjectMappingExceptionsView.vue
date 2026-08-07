<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';

import {
  AuthorizedShellClient,
  IdentitySessionClient,
  useAuthorizedShellState,
  useIdentityState,
} from '../../identity-access';
import type { CurrentAuthorizedShell } from '../../identity-access';
import {
  SubjectMappingClient,
  SubjectRegistryResponseFailure,
  SubjectRepairMemory,
  clearSubjectRegistryIdentityBoundary,
  hasUsableSubjectRegistryAuthorization,
  sameSubjectRegistryIdentityGeneration,
} from './subject-mapping';
import type {
  RepairReasonCode,
  SubjectMappingExceptionDetail,
  SubjectMappingExceptionPage,
  SubjectMappingExceptionStatus,
  SubjectRegistryClearReason,
  SubjectRegistryIdentityGeneration,
  SubjectRepairDraft,
  SubjectRepairResult,
} from './subject-mapping';

type PageState =
  | 'loading' | 'results' | 'empty' | 'forbidden' | 'error' | 'conflict' | 'completed';

const route = useRoute();
const router = useRouter();
const identity = useIdentityState();
const authorization = useAuthorizedShellState();
const identityClient = new IdentitySessionClient();
const authorizationClient = new AuthorizedShellClient();
const client = new SubjectMappingClient(undefined, (signal) => identityClient.csrfProof(signal));
const repairMemory = new SubjectRepairMemory();
const active = ref<AbortController>();
const response = ref<SubjectMappingExceptionPage>();
const detail = ref<SubjectMappingExceptionDetail>();
const result = ref<SubjectRepairResult>();
const state = ref<PageState>('loading');
const pageNumber = ref(numberQuery('page'));
const statusFilter = ref<SubjectMappingExceptionStatus | ''>(statusQuery());
const commandError = ref('');
const boundaryMessage = ref('');
const errorSummary = ref<HTMLElement>();
const reasonCode = ref<RepairReasonCode>('AUTHORITY_CORRECTION');
const sourceWatermark = ref('');
const sourceStudentRef = ref('');
const targetStudentRefs = ref('');

const selectedId = computed(() => textQuery('exceptionId'));
const capabilitySignature = computed(() => shellCapabilitySignature(authorization.current));
const relationType = computed(() => reasonCode.value === 'SUBJECT_MERGED'
  ? 'merged-into' as const
  : reasonCode.value === 'SUBJECT_SPLIT'
    ? 'split-into' as const
    : 'alias' as const);

function authorized(): boolean {
  return hasUsableSubjectRegistryAuthorization(
    identity.authenticated,
    authorization.status,
    authorization.current !== undefined,
    capabilitySignature.value,
  );
}

function identityGeneration(): SubjectRegistryIdentityGeneration | undefined {
  if (!authorized() || identity.session === undefined || authorization.current === undefined) return undefined;
  return {
    sessionPseudonym: identity.session.sessionPseudonym,
    sessionVersion: identity.session.sessionVersion,
    policyVersion: authorization.current.policyVersion,
    capabilitySignature: capabilitySignature.value,
  };
}

async function load(): Promise<void> {
  clearResponseOnly();
  const generation = identityGeneration();
  if (generation === undefined) {
    state.value = 'forbidden';
    return;
  }
  const controller = new AbortController();
  active.value = controller;
  state.value = 'loading';
  commandError.value = '';
  boundaryMessage.value = '';
  result.value = undefined;
  try {
    const pageResult = await client.list(
      pageNumber.value,
      20,
      statusFilter.value || undefined,
      controller.signal,
    );
    if (!current(controller, generation)) return;
    response.value = pageResult;
    const exceptionId = selectedId.value || pageResult.items[0]?.exceptionId;
    if (exceptionId !== undefined) {
      const detailResult = await client.detail(exceptionId, controller.signal);
      if (!current(controller, generation)) return;
      detail.value = detailResult;
    }
    state.value = pageResult.items.length === 0 ? 'empty' : 'results';
  } catch (failure) {
    if (!current(controller, generation)) return;
    clearResponseOnly();
    state.value = failure instanceof SubjectRegistryResponseFailure && !failure.retryable
      ? 'forbidden' : 'error';
  } finally {
    if (active.value === controller) active.value = undefined;
  }
}

async function selectException(exceptionId: string): Promise<void> {
  repairMemory.clear('refresh');
  clearForm();
  await router.replace({ query: safeQuery({ exceptionId }) });
  await load();
}

async function applyFilter(): Promise<void> {
  pageNumber.value = 0;
  repairMemory.clear('refresh');
  clearForm();
  await router.replace({ query: safeQuery({
    status: statusFilter.value || undefined,
    page: undefined,
    exceptionId: undefined,
  }) });
  await load();
}

async function changePage(nextPage: number): Promise<void> {
  if (!Number.isSafeInteger(nextPage) || nextPage < 0
    || (nextPage > pageNumber.value && response.value?.hasMore !== true)) return;
  pageNumber.value = nextPage;
  repairMemory.clear('refresh');
  clearForm();
  await router.replace({ query: safeQuery({
    page: nextPage === 0 ? undefined : String(nextPage), exceptionId: undefined,
  }) });
  await load();
}

async function repair(): Promise<void> {
  const target = detail.value;
  const generation = identityGeneration();
  if (target === undefined || generation === undefined) return;
  active.value?.abort();
  const controller = new AbortController();
  active.value = controller;
  commandError.value = '';
  boundaryMessage.value = '';
  result.value = undefined;
  const draft = draftFromForm();
  if (draft === undefined) {
    commandError.value = '请填写有效的源水位、UUIDv7 StudentRef 和目标列表。';
    await focusError();
    if (active.value === controller) active.value = undefined;
    return;
  }
  if (!await recheckGeneration(generation, controller)) {
    if (active.value === controller) active.value = undefined;
    return;
  }
  const command = repairMemory.prepare(
    target.item.exceptionId,
    target.aggregateVersion,
    draft,
    () => crypto.randomUUID(),
  );
  try {
    const accepted = await client.repair(command, controller.signal);
    if (!current(controller, generation)) return;
    repairMemory.complete(command);
    clearForm();
    result.value = accepted;
    state.value = 'completed';
  } catch (failure) {
    if (!current(controller, generation)) return;
    if (failure instanceof SubjectRegistryResponseFailure && !failure.retryable) {
      repairMemory.conflict(command);
      if (failure.currentAggregateVersion !== undefined && detail.value !== undefined) {
        detail.value = Object.freeze({
          item: detail.value.item,
          aggregateVersion: failure.currentAggregateVersion,
        });
      }
      state.value = 'conflict';
      commandError.value = failure.currentAggregateVersion === undefined
        ? `修复证明冲突（${failure.message}）；请核对后重新提交。`
        : `版本已变化（当前版本 ${failure.currentAggregateVersion}）；草稿已保留，请核对后重新提交。`;
    } else {
      commandError.value = failure instanceof Error
        ? `依赖暂时不可用（${failure.message}）；草稿和同一幂等证明仍保留。`
        : '依赖暂时不可用；草稿和同一幂等证明仍保留。';
      state.value = 'error';
    }
    await focusError();
  } finally {
    if (active.value === controller) active.value = undefined;
  }
}

async function recheckGeneration(
  expected: SubjectRegistryIdentityGeneration,
  controller: AbortController,
): Promise<boolean> {
  try {
    const [freshIdentity, freshShell] = await Promise.all([
      identityClient.current(controller.signal),
      authorizationClient.current(controller.signal),
    ]);
    const exact = freshIdentity.sessionPseudonym === expected.sessionPseudonym
      && freshIdentity.sessionVersion === expected.sessionVersion
      && freshShell.policyVersion === expected.policyVersion
      && shellCapabilitySignature(freshShell) === expected.capabilitySignature;
    if (!exact) {
      clearBoundary(freshIdentity.sessionPseudonym === expected.sessionPseudonym
        ? 'authorization-revoked' : 'account-switch');
      boundaryMessage.value = '身份或授权已变化；旧页面数据、草稿和幂等证明已清除。';
      return false;
    }
    return current(controller, expected);
  } catch {
    if (!controller.signal.aborted) {
      commandError.value = '身份与授权重检未完成；未提交修复。';
      state.value = 'error';
      await focusError();
    }
    return false;
  }
}

function current(
  controller: AbortController,
  generation: SubjectRegistryIdentityGeneration,
): boolean {
  return active.value === controller && !controller.signal.aborted
    && sameSubjectRegistryIdentityGeneration(generation, identityGeneration());
}

function draftFromForm(): SubjectRepairDraft | undefined {
  const targets = targetStudentRefs.value.split(/[\s,，]+/u).filter(Boolean);
  if (!sourceWatermark.value.trim() || !sourceStudentRef.value.trim() || targets.length === 0) {
    return undefined;
  }
  return Object.freeze({
    reasonCode: reasonCode.value,
    sourceWatermark: sourceWatermark.value.trim(),
    relationType: relationType.value,
    sourceStudentRef: sourceStudentRef.value.trim(),
    targetStudentRefs: Object.freeze(targets),
  });
}

function clearResponseOnly(): void {
  active.value?.abort();
  active.value = undefined;
  response.value = undefined;
  detail.value = undefined;
}

function clearBoundary(reason: SubjectRegistryClearReason): void {
  clearSubjectRegistryIdentityBoundary(active, response, detail, repairMemory, reason);
  result.value = undefined;
  clearForm();
  state.value = 'forbidden';
  commandError.value = '';
  if (route.name === 'subject-mapping-exceptions' && Object.keys(route.query).length > 0) {
    void router.replace({ query: {} });
  }
}

function clearForm(): void {
  reasonCode.value = 'AUTHORITY_CORRECTION';
  sourceWatermark.value = '';
  sourceStudentRef.value = '';
  targetStudentRefs.value = '';
}

async function focusError(): Promise<void> {
  await nextTick();
  errorSummary.value?.focus({ preventScroll: true });
}

function shellCapabilitySignature(shell: CurrentAuthorizedShell | undefined): string {
  const capability = shell?.entryCapabilities.find((item) => item.id === 'subject-mapping-exceptions');
  const menu = shell?.menuItems.find((item) =>
    item.routeName === 'data-quality.subject-mapping-exceptions');
  return `${capability?.state ?? 'missing'}|${menu?.providerState ?? 'missing'}`;
}

function statusText(status: SubjectMappingExceptionStatus): string {
  return ({ open: '待处理', repairing: '修复中', resolved: '已解决', dismissed: '已关闭' })[status];
}

function textQuery(key: string): string {
  const value = route.query[key];
  return typeof value === 'string' ? value : '';
}

function numberQuery(key: string): number {
  const value = Number(textQuery(key));
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

function statusQuery(): SubjectMappingExceptionStatus | '' {
  const value = textQuery('status');
  return ['open', 'repairing', 'resolved', 'dismissed'].includes(value)
    ? value as SubjectMappingExceptionStatus : '';
}

function safeQuery(next: Record<string, string | undefined>) {
  return {
    ...(statusFilter.value ? { status: statusFilter.value } : {}),
    ...(pageNumber.value ? { page: String(pageNumber.value) } : {}),
    ...(selectedId.value ? { exceptionId: selectedId.value } : {}),
    ...next,
  };
}

watch(
  () => [identity.session?.sessionPseudonym, identity.session?.sessionVersion] as const,
  (next, previous) => {
    if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) {
      clearBoundary(next[0] === undefined
        ? 'logout' : next[0] !== previous[0] ? 'account-switch' : 'session-invalid');
    }
  },
);
watch(
  () => [authorization.status, authorization.current?.policyVersion, capabilitySignature.value] as const,
  (next, previous) => {
    if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) {
      clearBoundary('authorization-revoked');
    }
  },
);
onMounted(() => { if (authorized()) void load(); else state.value = 'forbidden'; });
onBeforeUnmount(() => {
  clearSubjectRegistryIdentityBoundary(active, response, detail, repairMemory, 'webview-destroyed');
  result.value = undefined;
  clearForm();
});
</script>

<template>
  <section class="subject-registry" aria-labelledby="subject-mapping-heading">
    <h2 id="subject-mapping-heading" tabindex="-1">主体映射异常</h2>
    <p>仅展示当前修复用途和 owned-source 范围内的最小字段；不会展示源报文或候选主体列表。</p>

    <form class="subject-filter" aria-label="主体映射异常筛选" @submit.prevent="applyFilter">
      <label>状态
        <select v-model="statusFilter">
          <option value="">全部状态</option>
          <option value="open">待处理</option>
          <option value="repairing">修复中</option>
          <option value="resolved">已解决</option>
          <option value="dismissed">已关闭</option>
        </select>
      </label>
      <button type="submit">应用筛选</button>
    </form>

    <div class="subject-state" role="status" aria-live="polite" aria-atomic="true">
      <p v-if="boundaryMessage">{{ boundaryMessage }}</p>
      <p v-else-if="state === 'loading'">正在重新鉴权并加载映射异常…</p>
      <p v-else-if="state === 'empty'">当前筛选和授权范围内没有映射异常。</p>
      <p v-else-if="state === 'forbidden'">记录不存在或当前用途无权查看。</p>
      <p v-else-if="state === 'error' && !commandError">依赖暂时不可用，旧结果已清除。</p>
      <p v-else-if="state === 'conflict'">映射版本已变化；草稿仍保留在当前内存中。</p>
      <p v-else-if="state === 'completed'">修复已受理；异步作业不依赖当前页面会话。</p>
      <p v-else>已加载当前授权范围内的映射异常。</p>
    </div>
    <button v-if="state === 'error' && !commandError" type="button" @click="load">重试加载</button>

    <div v-if="response?.items.length" class="subject-table-wrap" tabindex="0" aria-label="映射异常，可横向查看全部列">
      <table>
        <caption>当前页主体映射异常</caption>
        <thead><tr><th scope="col">异常 ID</th><th scope="col">状态</th><th scope="col">官方标识</th><th scope="col">异常代码</th><th scope="col">来源系统</th><th scope="col">来源责任</th><th scope="col">发现时间</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="entry in response.items" :key="entry.exceptionId">
            <td class="technical-id">{{ entry.exceptionId }}</td>
            <td>{{ statusText(entry.status) }}</td>
            <td>{{ entry.subjectOfficialRef ?? '未提供' }}</td>
            <td>{{ entry.exceptionCode }}</td>
            <td>{{ entry.sourceSystem }}</td>
            <td>{{ entry.sourceOwner }}</td>
            <td>{{ new Date(entry.detectedAt).toLocaleString('zh-CN') }}</td>
            <td><button type="button" @click="selectException(entry.exceptionId)">查看并修复</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <nav v-if="response" class="subject-pagination" aria-label="映射异常分页">
      <button type="button" :disabled="pageNumber === 0" @click="changePage(pageNumber - 1)">上一页</button>
      <span>第 {{ response.page + 1 }} 页</span>
      <button type="button" :disabled="!response.hasMore" @click="changePage(pageNumber + 1)">下一页</button>
    </nav>

    <section v-if="detail" class="subject-detail" aria-labelledby="subject-detail-heading">
      <h3 id="subject-detail-heading">异常详情与受控修复</h3>
      <dl>
        <div><dt>异常 ID</dt><dd>{{ detail.item.exceptionId }}</dd></div>
        <div><dt>状态</dt><dd>{{ statusText(detail.item.status) }}</dd></div>
        <div><dt>官方标识</dt><dd>{{ detail.item.subjectOfficialRef ?? '未提供' }}</dd></div>
        <div><dt>异常代码</dt><dd>{{ detail.item.exceptionCode }}</dd></div>
        <div><dt>来源系统</dt><dd>{{ detail.item.sourceSystem }}</dd></div>
        <div><dt>来源责任</dt><dd>{{ detail.item.sourceOwner }}</dd></div>
        <div><dt>发现时间</dt><dd>{{ new Date(detail.item.detectedAt).toLocaleString('zh-CN') }}</dd></div>
      </dl>

      <form v-if="detail.item.status === 'open' || detail.item.status === 'repairing'" class="subject-repair-form" aria-label="受控映射修复" @submit.prevent="repair">
        <label>修复原因
          <select v-model="reasonCode">
            <option value="AUTHORITY_CORRECTION">权威更正</option>
            <option value="IDENTIFIER_REISSUED">标识重发</option>
            <option value="SUBJECT_MERGED">主体合并</option>
            <option value="SUBJECT_SPLIT">主体拆分</option>
            <option value="MAPPING_REVOKED">映射撤销</option>
          </select>
        </label>
        <label>源水位<input v-model="sourceWatermark" maxlength="128" autocomplete="off"></label>
        <label>源 StudentRef<input v-model="sourceStudentRef" maxlength="36" autocomplete="off" spellcheck="false"></label>
        <label>目标 StudentRef
          <textarea v-model="targetStudentRefs" rows="3" maxlength="600" autocomplete="off" spellcheck="false" aria-describedby="target-ref-help"></textarea>
        </label>
        <p id="target-ref-help">多个 UUIDv7 可用换行或逗号分隔；拆分最多 16 个目标。</p>
        <p>关系类型：{{ relationType }}。提交前会重新检查身份、授权和对象版本。</p>
        <button type="submit">提交受控修复</button>
      </form>

      <div v-if="commandError" ref="errorSummary" class="subject-error" role="alert" tabindex="-1">
        <h4>修复错误摘要</h4><p>{{ commandError }}</p>
      </div>
      <section v-if="result" class="subject-success" aria-labelledby="repair-result-heading">
        <h4 id="repair-result-heading">修复已受理</h4>
        <p>状态：已解决；修复事实编号 {{ result.correctionId }}。</p>
        <ul>
          <li v-for="acceptedJobId in result.jobIds" :key="acceptedJobId">
            <RouterLink :to="{ name: 'subject-recompute-jobs', query: { jobId: acceptedJobId } }">查看作业 {{ acceptedJobId }}</RouterLink>
          </li>
        </ul>
      </section>
    </section>
  </section>
</template>
