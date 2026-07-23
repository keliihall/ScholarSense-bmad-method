<script setup lang="ts">
import { ref } from 'vue';
import { AuditRetentionEvidenceClient, type RetentionEvidence } from './retention-evidence';

const client = new AuditRetentionEvidenceClient();
const executionId = ref('');
const state = ref<'idle' | 'loading' | 'ready' | 'forbidden' | 'error'>('idle');
const evidence = ref<RetentionEvidence>();

async function readEvidence(): Promise<void> {
  state.value = 'loading';
  evidence.value = undefined;
  try {
    evidence.value = await client.read(executionId.value.trim(), 'technical');
    state.value = 'ready';
  } catch (failure) {
    state.value = failure instanceof Error && failure.message === 'AUDIT_EVIDENCE_NOT_AVAILABLE'
      ? 'forbidden' : 'error';
  }
}
</script>

<template>
  <section class="audit-evidence" aria-labelledby="evidence-heading">
    <h3 id="evidence-heading">保留执行证据（只读）</h3>
    <form class="audit-evidence-form" @submit.prevent="readEvidence">
      <label for="execution-id">执行编号</label>
      <input id="execution-id" v-model="executionId" autocomplete="off" spellcheck="false" required>
      <button class="action-target primary-action" type="submit">读取证据</button>
    </form>
    <div role="status" aria-live="polite">
      <p v-if="state === 'loading'">正在重新鉴权并读取证据…</p>
      <p v-else-if="state === 'forbidden'">证据不存在或当前用途无权查看。</p>
      <p v-else-if="state === 'error'">暂时无法读取证据。请稍后重试。</p>
      <dl v-else-if="state === 'ready' && evidence" class="audit-evidence-list">
        <template v-for="(value, key) in evidence.fields" :key="key">
          <dt>{{ key }}</dt><dd>{{ value }}</dd>
        </template>
      </dl>
    </div>
  </section>
</template>
