<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { IdentitySessionClient } from '../../identity-access';
import type { QualityRecoveryTask } from './quality-recovery-tasks';
import {
  QualityFuseRecoveryClient,
  QualityFuseRecoveryFailure,
  QualityFuseRecoveryMemory,
} from './quality-fuse-recovery';
import type { QualityFuseRecoveryRequest } from './quality-fuse-recovery';

const props = defineProps<{
  task: QualityRecoveryTask;
  available: boolean;
  identityGeneration: string;
  online: boolean;
  narrow: boolean;
}>();
const emit = defineEmits<{ completed: [] }>();
const identityClient = new IdentitySessionClient();
const client = new QualityFuseRecoveryClient(undefined,
  (signal) => identityClient.csrfProof(signal));
const memory = new QualityFuseRecoveryMemory();
const current = ref<QualityFuseRecoveryRequest>();
const active = ref<AbortController>();
const busy = ref(false);
const executionCompleted = ref(false);
const message = ref('');
const dialog = ref<HTMLDialogElement>();

const visible = computed(() => props.available && props.online && !props.narrow);
const canCreate = computed(() => visible.value && current.value === undefined && !busy.value);
const canApproval = computed(() => current.value?.status === 'validation-succeeded'
  && current.value.previewDigest !== null && !busy.value);
const canApprove = computed(() => current.value?.status === 'approval-pending'
  && current.value.approvalVersion !== null && !busy.value);
const canExecute = computed(() => current.value?.status === 'approval-approved'
  && !executionCompleted.value && !busy.value);
const preview = computed(() => current.value?.previewSummary);

function open(): void {
  if (!canCreate.value) return;
  message.value = '';
  dialog.value?.showModal();
}

async function create(): Promise<void> {
  await run(async (signal) => {
    current.value = await client.create(
      props.task.taskId, props.task.taskVersion, key('request'), signal);
    memory.accept(current.value);
    dialog.value?.close();
    message.value = '恢复验证已排队；完成后请显式刷新状态。';
  });
}

async function refresh(): Promise<void> {
  if (current.value === undefined) return;
  await run(async (signal) => {
    current.value = await client.status(current.value!.recoveryRequestId, signal);
    memory.accept(current.value);
    message.value = statusText(current.value.status);
  });
}

async function requestApproval(): Promise<void> {
  if (!canApproval.value) return;
  await run(async (signal) => {
    current.value = await client.requestApproval(
      current.value!.recoveryRequestId, current.value!.requestVersion,
      key('approval'), signal);
    memory.accept(current.value);
    message.value = '已提交 D4 maker-checker 审批；恢复执行仍不可用。';
  });
}

async function decide(decision: 'approve' | 'reject' | 'cancel'): Promise<void> {
  if (!canApprove.value) return;
  await run(async (signal) => {
    current.value = await client.decide(
      current.value!.recoveryRequestId, current.value!.requestVersion,
      current.value!.approvalVersion!, decision, key(`decision-${decision}`), signal);
    memory.accept(current.value);
    message.value = decision === 'approve'
      ? 'D4 审批已完成；执行前仍会重检当前授权与版本。'
      : decision === 'reject' ? 'D4 审批已拒绝；质量资格保持 Fused。'
        : 'D4 审批已取消；质量资格保持 Fused。';
  });
}

async function execute(): Promise<void> {
  if (!canExecute.value) return;
  await run(async (signal) => {
    const result = await client.execute(
      current.value!.recoveryRequestId, current.value!.requestVersion,
      key('execute'), signal);
    message.value = result.transitionApplied
      ? '质量资格已进入 Recovering 观察；尚未 eligible、recovered 或 production。'
      : '执行已重放；质量资格仍处于 Recovering 观察。';
    executionCompleted.value = true;
    emit('completed');
  });
}

async function run(action: (signal: AbortSignal) => Promise<void>): Promise<void> {
  if (!visible.value || busy.value) return;
  active.value?.abort();
  const controller = new AbortController();
  active.value = controller;
  busy.value = true;
  try {
    await action(controller.signal);
  } catch (failure) {
    if (controller.signal.aborted) return;
    if (failure instanceof QualityFuseRecoveryFailure
        && failure.code === 'INGESTION_QUALITY_VERSION_CONFLICT') {
      clear();
      message.value = failure.currentVersion === null
        ? '对象版本已变化；预览与审批已作废，请重新发起。'
        : `对象版本已变化（当前 ${failure.currentVersion}）；请重新发起。`;
    } else {
      message.value = '恢复依赖暂不可用；没有执行质量状态迁移。';
    }
  } finally {
    if (active.value === controller) active.value = undefined;
    busy.value = false;
  }
}

function clear(): void {
  active.value?.abort();
  active.value = undefined;
  current.value = undefined;
  executionCompleted.value = false;
  memory.clear();
  dialog.value?.close();
}

function key(phase: string): string {
  return `${phase}:${props.task.taskId}:${crypto.randomUUID()}`;
}

function statusText(status: QualityFuseRecoveryRequest['status']): string {
  return ({
    requested: '恢复请求已记录。', validating: '正在执行补数、全量对账与分层抽样。',
    'validation-succeeded': '验证通过；请检查预览并申请 D4 审批。',
    'validation-failed': '验证未通过；质量资格保持 Fused。',
    'approval-pending': '等待独立 checker 审批。',
    'approval-approved': '审批通过；执行仍需当前授权重检。',
    executed: '质量资格已进入 Recovering 观察。',
    cancelled: current.value?.approvalStatus === 'rejected' ? 'D4 审批已拒绝。'
      : current.value?.approvalStatus === 'expired' ? 'D4 审批已过期。'
        : '恢复流程已取消。',
  })[status];
}

watch(() => [props.available, props.online, props.narrow, props.identityGeneration,
  props.task.taskId] as const, (next, previous) => {
  if (previous !== undefined && next.join('|') !== previous.join('|')) clear();
});
onBeforeUnmount(clear);
</script>

<template>
  <section v-if="visible" class="quality-fuse-recovery-panel"
    aria-labelledby="quality-fuse-recovery-heading">
    <h5 id="quality-fuse-recovery-heading">证据化质量恢复</h5>
    <p>只会申请 Fused → Recovering；解除熔断与 Eligible 属于后续观察故事。</p>
    <p v-if="message" role="status" aria-live="polite">{{ message }}</p>
    <section v-if="preview" class="quality-recovery-evidence"
      aria-labelledby="quality-recovery-evidence-heading">
      <h6 id="quality-recovery-evidence-heading">执行前证据与影响预览</h6>
      <dl>
        <div><dt>恢复策略</dt><dd>{{ preview.qualityRecoveryPolicyVersion }}</dd></div>
        <div><dt>连续通过批次</dt><dd>{{ preview.actualConsecutivePassedBatches }} / 至少 {{ preview.requiredConsecutivePassedBatches }}</dd></div>
        <div><dt>Recovering 观察窗</dt><dd>{{ preview.observationDuration === 'PT60M' ? '60 分钟' : '24 小时' }}</dd></div>
        <div><dt>历史补数</dt><dd>{{ preview.backfillStatus }} · {{ preview.backfillLookbackDays }} 天</dd></div>
        <div><dt>全量对账</dt><dd>期望 {{ preview.reconciliationExpectedCount }} / 实际 {{ preview.reconciliationActualCount }} / 不一致 {{ preview.reconciliationMismatchCount }}</dd></div>
        <div><dt>分层抽样</dt><dd>总体 {{ preview.samplePopulationCount }} / 已选 {{ preview.sampleSelectedCount }} / 分层 {{ preview.sampleStrataCount }} / 不一致 {{ preview.sampleMismatchCount }}</dd></div>
        <div><dt>影响窗口</dt><dd>已过期 {{ preview.impactAlreadyExpiredCount }} / 潜在可行动 {{ preview.impactPotentiallyActionableCount }} / 观察完成前预计过期 {{ preview.impactExpectedToExpireCount }}</dd></div>
      </dl>
      <p>本预览不授权执行；最终可行动性由 Story {{ preview.finalActionabilityOwnerStory }} 判定。</p>
      <p v-if="current?.previewExpiresAt">预览有效至 {{ new Date(current.previewExpiresAt).toLocaleString('zh-CN') }}；过期或版本漂移后必须显式重做。</p>
    </section>
    <div class="quality-filter-actions">
      <button v-if="canCreate" type="button" @click="open">发起恢复验证</button>
      <button v-if="current" type="button" :disabled="busy" @click="refresh">刷新恢复状态</button>
      <button v-if="canApproval" type="button" @click="requestApproval">申请 D4 审批</button>
      <button v-if="canApprove" type="button" @click="decide('approve')">独立 checker 批准</button>
      <button v-if="canApprove" type="button" @click="decide('reject')">拒绝审批</button>
      <button v-if="canApprove" type="button" @click="decide('cancel')">取消审批</button>
      <button v-if="canExecute" type="button" @click="execute">执行进入 Recovering</button>
    </div>
    <dialog ref="dialog" aria-labelledby="quality-fuse-recovery-dialog-title">
      <h5 id="quality-fuse-recovery-dialog-title">确认发起恢复验证</h5>
      <p>系统将执行 90 天补数、全量对账和分层抽样。此步不会改变 Fused 状态。</p>
      <div class="quality-filter-actions">
        <button type="button" :disabled="busy" @click="create">确认发起</button>
        <button type="button" :disabled="busy" @click="dialog?.close()">取消</button>
      </div>
    </dialog>
  </section>
</template>
