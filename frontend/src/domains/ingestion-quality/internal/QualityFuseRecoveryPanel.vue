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
import type {
  QualityRecoveryFinalization,
  QualityRecoveryObservation,
} from './quality-fuse-recovery';

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
const observation = ref<QualityRecoveryObservation>();
const finalization = ref<QualityRecoveryFinalization>();
const active = ref<AbortController>();
const busy = ref(false);
const executionCompleted = ref(false);
const message = ref('');
const dialog = ref<HTMLDialogElement>();
const finalDialog = ref<HTMLDialogElement>();

const visible = computed(() => props.available && props.online && !props.narrow);
const canCreate = computed(() => visible.value && current.value === undefined && !busy.value);
const canApproval = computed(() => current.value?.status === 'validation-succeeded'
  && current.value.previewDigest !== null && !busy.value);
const canApprove = computed(() => current.value?.status === 'approval-pending'
  && current.value.approvalVersion !== null && !busy.value);
const canExecute = computed(() => current.value?.status === 'approval-approved'
  && !executionCompleted.value && !busy.value);
const canFinalApproval = computed(() => observation.value?.status === 'ready'
  && ['not-requested', 'approval-rejected', 'cancelled']
    .includes(observation.value.finalizationState)
  && observation.value.watermark !== null && !busy.value);
const finalizationState = computed(() => finalization.value?.finalizationState
  ?? observation.value?.finalizationState);
const finalApprovalVersion = computed(() => finalization.value?.approvalVersion
  ?? observation.value?.approvalVersion);
const canFinalApprove = computed(() => finalizationState.value === 'approval-pending'
  && finalApprovalVersion.value !== null && finalApprovalVersion.value !== undefined
  && !busy.value);
const canFinalize = computed(() => finalizationState.value === 'approval-approved'
  && observation.value?.watermark !== null && !busy.value);
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
    await loadObservation(signal);
    emit('completed');
  });
}

async function refreshObservation(): Promise<void> {
  await run(loadObservation);
}

async function loadObservation(signal: AbortSignal): Promise<void> {
  observation.value = await client.observation(props.task.taskId, signal);
  message.value = observationText(observation.value);
}

async function requestFinalApproval(): Promise<void> {
  if (!canFinalApproval.value || observation.value?.watermark === null) return;
  await run(async (signal) => {
    finalization.value = await client.requestFinalApproval(
      observation.value!.recoveryId, observation.value!.recoveryVersion,
      observation.value!.taskVersion, observation.value!.watermark!,
      key('final-approval'), signal);
    message.value = '最终 D4 已提交；仍需独立 checker 确认，当前资格保持 Recovering。';
  });
}

async function decideFinal(decision: 'approve' | 'reject' | 'cancel'): Promise<void> {
  const recoveryId = finalization.value?.recoveryId ?? observation.value?.recoveryId;
  const approvalVersion = finalApprovalVersion.value;
  if (!canFinalApprove.value || recoveryId === undefined
      || approvalVersion === null || approvalVersion === undefined) return;
  await run(async (signal) => {
    finalization.value = await client.decideFinalApproval(
      recoveryId, approvalVersion,
      decision, key(`final-decision-${decision}`), signal);
    message.value = decision === 'approve'
      ? '最终 D4 已批准；提交时仍会重检观察、水位、成员、策略与授权。'
      : '最终确认未获批准；质量资格保持 Recovering。';
  });
}

function openFinalizationConfirmation(): void {
  if (!canFinalize.value) return;
  finalDialog.value?.showModal();
}

async function finalizeRecovery(): Promise<void> {
  if (!canFinalize.value || observation.value?.watermark === null
      || observation.value === undefined) return;
  await run(async (signal) => {
    const result = await client.finalize(
      observation.value!.recoveryId, observation.value!.recoveryVersion,
      observation.value!.taskVersion, observation.value!.watermark!,
      key('finalize'), signal);
    message.value = result.deliveryStatus === 'confirmed'
      ? '本地质量已 Eligible；同一任务已关闭；外部平台已确认投递。'
      : '本地质量已 Eligible；同一任务关闭 intent 已提交，外部投递仍独立处理中。';
    finalDialog.value?.close();
    await loadObservation(signal);
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
  observation.value = undefined;
  finalization.value = undefined;
  executionCompleted.value = false;
  memory.clear();
  dialog.value?.close();
  finalDialog.value?.close();
}

function observationText(value: QualityRecoveryObservation): string {
  if (value.status === 'relapsed') return '观察到真实质量失败，资格已回退为 Fused。';
  if (value.status === 'policy-drift') return '策略或成员已变化；旧观察不能用于最终确认。';
  if (value.status === 'ready') return '完整观察已通过；可发起 fresh D4 最终确认。';
  if (value.status === 'finalized') return value.deliveryStatus === 'confirmed'
    ? '本地质量已 Eligible；外部平台已确认投递。'
    : '本地质量已 Eligible；任务关闭投递仍独立处理中。';
  return '正在累计真实质量事实；无数据不会被当作通过。';
}

function durationText(value: 'PT60M' | 'PT24H' | 'P1D'): string {
  if (value === 'PT60M') return '60 分钟';
  if (value === 'PT24H' || value === 'P1D') return '24 小时';
  return '未知策略时长';
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
    <p>先进入 Recovering 持续观察；仅完整达标并完成 fresh D4 后才会进入 Eligible。</p>
    <p v-if="message" role="status" aria-live="polite">{{ message }}</p>
    <section v-if="preview" class="quality-recovery-evidence"
      aria-labelledby="quality-recovery-evidence-heading">
      <h6 id="quality-recovery-evidence-heading">执行前证据与影响预览</h6>
      <dl>
        <div><dt>恢复策略</dt><dd>{{ preview.qualityRecoveryPolicyVersion }}</dd></div>
        <div><dt>连续通过批次</dt><dd>{{ preview.actualConsecutivePassedBatches }} / 至少 {{ preview.requiredConsecutivePassedBatches }}</dd></div>
        <div><dt>Recovering 观察窗</dt><dd>{{ durationText(preview.observationDuration) }}</dd></div>
        <div><dt>历史补数</dt><dd>{{ preview.backfillStatus }} · {{ preview.backfillLookbackDays }} 天</dd></div>
        <div><dt>全量对账</dt><dd>期望 {{ preview.reconciliationExpectedCount }} / 实际 {{ preview.reconciliationActualCount }} / 不一致 {{ preview.reconciliationMismatchCount }}</dd></div>
        <div><dt>分层抽样</dt><dd>总体 {{ preview.samplePopulationCount }} / 已选 {{ preview.sampleSelectedCount }} / 分层 {{ preview.sampleStrataCount }} / 不一致 {{ preview.sampleMismatchCount }}</dd></div>
        <div><dt>影响窗口</dt><dd>已过期 {{ preview.impactAlreadyExpiredCount }} / 潜在可行动 {{ preview.impactPotentiallyActionableCount }} / 观察完成前预计过期 {{ preview.impactExpectedToExpireCount }}</dd></div>
      </dl>
      <p>本预览不授权执行；最终可行动性由 Story {{ preview.finalActionabilityOwnerStory }} 判定。</p>
      <p v-if="current?.previewExpiresAt">预览有效至 {{ new Date(current.previewExpiresAt).toLocaleString('zh-CN') }}；过期或版本漂移后必须显式重做。</p>
    </section>
    <section v-if="observation" class="quality-recovery-observation"
      aria-labelledby="quality-recovery-observation-heading">
      <h6 id="quality-recovery-observation-heading">恢复观察与终态</h6>
      <dl>
        <div><dt>策略 / 来源类别</dt><dd>{{ observation.policyVersion }} / {{ observation.sourceClass }}</dd></div>
        <div><dt>连续通过批次</dt><dd>{{ observation.consecutivePassedBatches }} / {{ observation.requiredPassedBatches }}</dd></div>
        <div><dt>观察时长</dt><dd>{{ Math.floor(observation.observedDurationMicros / 60000000) }} 分钟 / {{ durationText(observation.observationDuration) }}</dd></div>
        <div><dt>水位</dt><dd>{{ observation.watermark ?? '尚无可信事实' }}</dd></div>
        <div><dt>最早可行动截止</dt><dd>{{ observation.latestActionableAt ? new Date(observation.latestActionableAt).toLocaleString('zh-CN') : '无窗口' }}</dd></div>
        <div><dt>失败成员</dt><dd>{{ observation.failedMembers.length === 0 ? '无' : observation.failedMembers.join('、') }}</dd></div>
        <div><dt>资格 / 原任务</dt><dd>{{ observation.eligibilityStatus }} / {{ observation.taskStatus }}</dd></div>
        <div><dt>任务投递</dt><dd>{{ observation.deliveryStatus }}（独立传输状态）</dd></div>
        <div v-if="observation.status === 'finalized'"><dt>最终窗口</dt><dd>可交接 {{ observation.eligibleForHandoffWindowCount }} / 仅历史 {{ observation.historyOnlyWindowCount }}</dd></div>
        <div v-if="observation.failureReasonCode"><dt>未通过原因</dt><dd>{{ observation.failureReasonCode }}</dd></div>
      </dl>
    </section>
    <div class="quality-filter-actions">
      <button v-if="canCreate" type="button" @click="open">发起恢复验证</button>
      <button v-if="current" type="button" :disabled="busy" @click="refresh">刷新恢复状态</button>
      <button v-if="canApproval" type="button" @click="requestApproval">申请 D4 审批</button>
      <button v-if="canApprove" type="button" @click="decide('approve')">独立 checker 批准</button>
      <button v-if="canApprove" type="button" @click="decide('reject')">拒绝审批</button>
      <button v-if="canApprove" type="button" @click="decide('cancel')">取消审批</button>
      <button v-if="canExecute" type="button" @click="execute">执行进入 Recovering</button>
      <button type="button" :disabled="busy" @click="refreshObservation">刷新观察与任务状态</button>
      <button v-if="canFinalApproval" type="button" @click="requestFinalApproval">{{ observation?.finalizationState === 'not-requested' ? '申请最终 D4' : '重新申请最终 D4' }}</button>
      <button v-if="canFinalApprove" type="button" @click="decideFinal('approve')">独立 checker 最终批准</button>
      <button v-if="canFinalApprove" type="button" @click="decideFinal('reject')">拒绝最终确认</button>
      <button v-if="canFinalize" type="button" @click="openFinalizationConfirmation">确认 Eligible 并关闭同一任务</button>
    </div>
    <dialog ref="dialog" aria-labelledby="quality-fuse-recovery-dialog-title">
      <h5 id="quality-fuse-recovery-dialog-title">确认发起恢复验证</h5>
      <p>系统将执行 90 天补数、全量对账和分层抽样。此步不会改变 Fused 状态。</p>
      <div class="quality-filter-actions">
        <button type="button" :disabled="busy" @click="create">确认发起</button>
        <button type="button" :disabled="busy" @click="dialog?.close()">取消</button>
      </div>
    </dialog>
    <dialog ref="finalDialog" aria-labelledby="quality-finalization-dialog-title">
      <h5 id="quality-finalization-dialog-title">确认进入 Eligible 并关闭同一任务</h5>
      <p>此操作将提交不可逆的本地终态：全部受影响资格进入 Eligible、当前 episode 关闭、原质量任务关闭。提交前系统仍会重检策略、成员、水位、授权与 fresh D4 lease。</p>
      <p>外部任务投递是独立状态；投递失败不会回滚本地 Eligible。</p>
      <div class="quality-filter-actions">
        <button type="button" :disabled="busy" @click="finalizeRecovery">确认执行不可逆终态</button>
        <button type="button" :disabled="busy" @click="finalDialog?.close()">返回检查</button>
      </div>
    </dialog>
  </section>
</template>
