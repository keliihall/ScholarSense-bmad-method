<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAuthorizedShellState, useIdentityState } from '../../identity-access';
import type { CurrentAuthorizedShell } from '../../identity-access';
import {
  SubjectMappingClient,
  SubjectRegistryResponseFailure,
  hasUsableSubjectRegistryAuthorization,
  sameSubjectRegistryIdentityGeneration,
} from './subject-mapping';
import type {
  SubjectRecomputeJob,
  SubjectRegistryIdentityGeneration,
} from './subject-mapping';

type JobState = 'prompt' | 'loading' | 'ready' | 'forbidden' | 'error';
const route = useRoute();
const router = useRouter();
const identity = useIdentityState();
const authorization = useAuthorizedShellState();
const client = new SubjectMappingClient();
const active = ref<AbortController>();
const job = ref<SubjectRecomputeJob>();
const jobId = ref(textQuery('jobId'));
const state = ref<JobState>(jobId.value ? 'loading' : 'prompt');
const capabilitySignature = computed(() => shellCapabilitySignature(authorization.current));

function authorized(): boolean {
  return hasUsableSubjectRegistryAuthorization(
    identity.authenticated,
    authorization.status,
    authorization.current !== undefined,
    capabilitySignature.value,
  );
}

function generation(): SubjectRegistryIdentityGeneration | undefined {
  if (!authorized() || identity.session === undefined || authorization.current === undefined) return undefined;
  return {
    sessionPseudonym: identity.session.sessionPseudonym,
    sessionVersion: identity.session.sessionVersion,
    policyVersion: authorization.current.policyVersion,
    capabilitySignature: capabilitySignature.value,
  };
}

async function load(): Promise<void> {
  clearJob();
  const expected = generation();
  if (expected === undefined) { state.value = 'forbidden'; return; }
  if (!jobId.value) { state.value = 'prompt'; return; }
  const controller = new AbortController();
  active.value = controller;
  state.value = 'loading';
  try {
    const currentJob = await client.job(jobId.value, controller.signal);
    if (!current(controller, expected)) return;
    job.value = currentJob;
    state.value = 'ready';
  } catch (failure) {
    if (!current(controller, expected)) return;
    job.value = undefined;
    state.value = failure instanceof SubjectRegistryResponseFailure && !failure.retryable
      ? 'forbidden' : 'error';
  } finally {
    if (active.value === controller) active.value = undefined;
  }
}

async function openJob(): Promise<void> {
  const normalized = jobId.value.trim().toLowerCase();
  jobId.value = normalized;
  await router.replace({ query: normalized ? { jobId: normalized } : {} });
  await load();
}

function current(controller: AbortController, expected: SubjectRegistryIdentityGeneration): boolean {
  return active.value === controller && !controller.signal.aborted
    && sameSubjectRegistryIdentityGeneration(expected, generation());
}

function clearJob(): void {
  active.value?.abort();
  active.value = undefined;
  job.value = undefined;
}

function clearBoundary(): void {
  clearJob();
  jobId.value = '';
  state.value = 'forbidden';
  if (route.name === 'subject-recompute-jobs' && Object.keys(route.query).length > 0) {
    void router.replace({ query: {} });
  }
}

function shellCapabilitySignature(shell: CurrentAuthorizedShell | undefined): string {
  const capability = shell?.entryCapabilities.find((item) => item.id === 'subject-recompute-jobs');
  const menu = shell?.menuItems.find((item) => item.routeName === 'subject-registry.recompute-jobs');
  return `${capability?.state ?? 'missing'}|${menu?.providerState ?? 'missing'}`;
}

function statusText(status: SubjectRecomputeJob['status']): string {
  return ({ queued: '排队中', running: '运行中', succeeded: '已成功', failed: '已失败', cancelled: '已取消' })[status];
}

function textQuery(key: string): string {
  const value = route.query[key];
  return typeof value === 'string' ? value : '';
}

watch(
  () => [identity.session?.sessionPseudonym, identity.session?.sessionVersion] as const,
  (next, previous) => {
    if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) clearBoundary();
  },
);
watch(
  () => [authorization.status, authorization.current?.policyVersion, capabilitySignature.value] as const,
  (next, previous) => {
    if (previous?.some((item) => item !== undefined) && next.join('|') !== previous.join('|')) clearBoundary();
  },
);
onMounted(() => { if (authorized()) void load(); else state.value = 'forbidden'; });
onBeforeUnmount(clearJob);
</script>

<template>
  <section class="subject-registry" aria-labelledby="subject-job-heading">
    <h2 id="subject-job-heading" tabindex="-1">主体重算作业</h2>
    <p>作业状态保存在服务端并脱离页面会话；本页只展示获批的技术状态字段。</p>
    <form class="subject-filter" aria-label="查询主体重算作业" @submit.prevent="openJob">
      <label>作业 ID<input v-model="jobId" maxlength="36" autocomplete="off" spellcheck="false"></label>
      <button type="submit">查询作业</button>
    </form>
    <div class="subject-state" role="status" aria-live="polite" aria-atomic="true">
      <p v-if="state === 'prompt'">输入修复结果中的作业 ID 查看当前状态。</p>
      <p v-else-if="state === 'loading'">正在重新鉴权并加载作业…</p>
      <p v-else-if="state === 'forbidden'">作业不存在或当前职责无权查看。</p>
      <p v-else-if="state === 'error'">作业依赖暂时不可用，旧结果已清除。</p>
      <p v-else>已加载当前授权的作业状态。</p>
    </div>
    <button v-if="state === 'error'" type="button" @click="load">重试</button>
    <section v-if="job" class="subject-detail" aria-labelledby="subject-job-detail-heading">
      <h3 id="subject-job-detail-heading">作业结果</h3>
      <dl>
        <div><dt>作业 ID</dt><dd>{{ job.jobId }}</dd></div>
        <div><dt>状态</dt><dd>{{ statusText(job.status) }}</dd></div>
        <div><dt>尝试次数</dt><dd>{{ job.attemptNo }}</dd></div>
        <div><dt>排队时间</dt><dd>{{ new Date(job.queuedAt).toLocaleString('zh-CN') }}</dd></div>
        <div><dt>完成时间</dt><dd>{{ job.completedAt ? new Date(job.completedAt).toLocaleString('zh-CN') : '尚未完成' }}</dd></div>
        <div><dt>结果代码</dt><dd>{{ job.resultCode ?? '尚未产生' }}</dd></div>
        <div><dt>Trace ID</dt><dd class="technical-id">{{ job.traceId }}</dd></div>
      </dl>
    </section>
  </section>
</template>
