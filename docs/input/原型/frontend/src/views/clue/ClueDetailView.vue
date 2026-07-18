<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import CareLevelTag from '@/components/CareLevelTag.vue';
import ClueStatusTag from '@/components/ClueStatusTag.vue';
import EvidenceChain from '@/components/EvidenceChain.vue';
import ClueExplanation from '@/components/ClueExplanation.vue';
import SensitiveField from '@/components/SensitiveField.vue';
import EmptyState from '@/components/EmptyState.vue';
import { findClue, findStudent } from '@/mock';
import { CLUE_CATEGORY, CARE_LEVEL_ACTION, IDENTITY_TAG } from '@/utils/constants';
import { deadlineHint } from '@/utils/format';

const route = useRoute();
const router = useRouter();
const clue = computed(() => findClue(String(route.params.id)));
const student = computed(() => (clue.value ? findStudent(clue.value.studentId) : undefined));
const cat = computed(() => (clue.value ? CLUE_CATEGORY[clue.value.category] : null));
const alertScore = computed(() =>
  clue.value ? clue.value.behaviorScore * clue.value.identityWeight : 0,
);
const isEconomic = computed(() => clue.value?.category === 'economic');

const form = reactive({
  contact: '',
  result: '属实' as '属实' | '噪声' | '待观察',
  noiseReason: '',
  careWay: '',
  referred: false,
  target: '心理',
  keepWatch: true,
  note: '',
});
const submitted = ref(false);

function submit() {
  if (!form.contact) {
    ElMessage.warning('请先填写联系情况');
    return;
  }
  submitted.value = true;
  ElMessage.success('核实反馈已提交，线索状态流转为「已跟进」，操作已留痕');
}
</script>

<template>
  <div class="ss-page" v-if="clue">
    <PageHeader :title="`线索详情 · ${clue.id}`" :subtitle="`${clue.rule} · ${clue.scene}`">
      <template #extra>
        <el-button :icon="'Back'" plain @click="router.back()">返回</el-button>
        <el-button type="primary" :icon="'User'" @click="router.push(`/students/${clue.studentId}`)"
          >查看一人一档</el-button
        >
      </template>
    </PageHeader>

    <!-- 线索概览 -->
    <div class="ss-card overview">
      <div class="ov-item">
        <span class="ov-label">学生</span>
        <span class="ov-val">{{ clue.studentName }} · {{ clue.college }}</span>
      </div>
      <el-divider direction="vertical" />
      <div class="ov-item">
        <span class="ov-label">类别</span>
        <span class="ov-val" :style="{ color: cat?.color }">
          <el-icon><component :is="cat?.icon" /></el-icon> {{ cat?.label }}
        </span>
      </div>
      <el-divider direction="vertical" />
      <div class="ov-item">
        <span class="ov-label">关怀等级</span><CareLevelTag :level="clue.level" />
      </div>
      <el-divider direction="vertical" />
      <div class="ov-item">
        <span class="ov-label">状态</span><ClueStatusTag :status="clue.status" />
      </div>
      <el-divider direction="vertical" />
      <div class="ov-item">
        <span class="ov-label">评分 / 独立线索</span>
        <span class="ov-val">{{ clue.score }} 分 · {{ clue.independentCount }} 条</span>
      </div>
      <el-divider direction="vertical" />
      <div class="ov-item">
        <span class="ov-label">跟进时限</span>
        <span class="ov-val" :class="{ over: deadlineHint(clue.deadline).overdue }">
          {{ CARE_LEVEL_ACTION[clue.level].limit }} · {{ deadlineHint(clue.deadline).text }}
        </span>
      </div>
    </div>

    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :md="15">
        <!-- 学生简档 -->
        <div class="ss-card pad mb16" v-if="student">
          <div class="ss-section-title">学生概况</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="姓名">{{ student.name }}</el-descriptions-item>
            <el-descriptions-item label="学号"
              ><SensitiveField :value="student.id" type="id"
            /></el-descriptions-item>
            <el-descriptions-item label="专业 / 年级"
              >{{ student.major }} · {{ student.grade }}</el-descriptions-item
            >
            <el-descriptions-item label="培养层次">{{ student.eduLevel }}</el-descriptions-item>
            <el-descriptions-item label="辅导员">{{ student.counselor }}</el-descriptions-item>
            <el-descriptions-item label="联系方式"
              ><SensitiveField :value="student.phone" type="phone"
            /></el-descriptions-item>
            <el-descriptions-item label="关爱标签" :span="2">
              <template v-if="student.tags.length">
                <el-tag
                  v-for="t in student.tags"
                  :key="t.type"
                  :type="t.sensitive ? 'danger' : 'info'"
                  effect="plain"
                  size="small"
                  class="mr6"
                >
                  <template v-if="t.sensitive"
                    ><SensitiveField :value="t.type" />（{{ t.source }}）</template
                  >
                  <template v-else>{{ t.type }}（{{ t.source }}）</template>
                </el-tag>
              </template>
              <span v-else class="ss-muted">无</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 风险评分构成 -->
        <div class="ss-card pad mb16">
          <div class="ss-section-title">风险评分构成（需人工核实 · 非自动决策）</div>
          <div class="score-calc">
            <div class="sc-item">
              <span class="sc-num accent">{{ clue.behaviorScore }}</span>
              <span class="sc-lab">行为得分</span>
            </div>
            <span class="sc-op">×</span>
            <div class="sc-item">
              <span class="sc-num warn">{{ clue.identityWeight }}</span>
              <span class="sc-lab">身份系数{{ student ? ' · ' + student.identityTag : '' }}</span>
            </div>
            <span class="sc-op">=</span>
            <div class="sc-item">
              <span class="sc-num primary">{{ alertScore }}</span>
              <span class="sc-lab">预警得分</span>
            </div>
            <div class="sc-level">
              命中 <CareLevelTag :level="clue.level" />
              <span class="sc-th">（≥ {{ CARE_LEVEL_ACTION[clue.level].threshold }} 分）</span>
            </div>
          </div>
          <div class="sc-note">
            <el-icon><InfoFilled /></el-icon>
            <span v-if="isEconomic">经济类全体统一标准、不赋身份系数（系数 ×1）。</span>
            <span v-else
              >身份系数随身份标签动态更新：{{
                student ? IDENTITY_TAG[student.identityTag].rule : ''
              }}</span
            >
          </div>
        </div>

        <!-- 证据链 -->
        <div class="ss-card pad mb16">
          <div class="ss-section-title">证据链（{{ clue.evidence.length }} 项独立证据）</div>
          <EvidenceChain :evidence="clue.evidence" />
        </div>

        <!-- AI 解释 + 建议 -->
        <div class="ss-card pad">
          <div class="ss-section-title">观澜解释与关怀建议</div>
          <ClueExplanation :explanation="clue.explanation" :suggestion="clue.suggestion" />
        </div>
      </el-col>

      <!-- 核实反馈 -->
      <el-col :xs="24" :md="9">
        <div class="ss-card pad feedback">
          <div class="ss-section-title">核实反馈</div>

          <el-alert
            v-if="clue.feedback || submitted"
            type="success"
            :closable="false"
            show-icon
            class="mb12"
            title="该线索已完成核实"
          >
            <template #default>{{ clue.feedback?.note ?? form.note ?? '已提交反馈' }}</template>
          </el-alert>

          <el-form v-if="!clue.feedback" label-position="top" :disabled="submitted">
            <el-form-item label="联系情况" required>
              <el-input
                v-model="form.contact"
                type="textarea"
                :rows="2"
                placeholder="如：已电话联系本人 / 已当面沟通…"
              />
            </el-form-item>
            <el-form-item label="核实结果">
              <el-radio-group v-model="form.result">
                <el-radio-button label="属实" />
                <el-radio-button label="噪声" />
                <el-radio-button label="待观察" />
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.result === '噪声'" label="噪声原因">
              <el-select v-model="form.noiseReason" placeholder="选择噪声原因" style="width: 100%">
                <el-option label="临近假期正常波动" value="临近假期正常波动" />
                <el-option label="校外就餐 / 借卡" value="校外就餐/借卡" />
                <el-option label="设备 / 数据异常" value="设备/数据异常" />
                <el-option label="已请假未同步" value="已请假未同步" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="form.result !== '噪声'" label="关怀方式">
              <el-input v-model="form.careWay" placeholder="如：谈心谈话 / 学业帮扶 / 经济关怀…" />
            </el-form-item>
            <el-form-item label="转交协同">
              <el-switch v-model="form.referred" />
              <el-select
                v-if="form.referred"
                v-model="form.target"
                class="ml12"
                style="width: 130px"
              >
                <el-option label="心理中心" value="心理" />
                <el-option label="资助中心" value="资助" />
                <el-option label="教务处" value="教务" />
                <el-option label="保卫处" value="保卫" />
                <el-option label="学院" value="学院" />
              </el-select>
            </el-form-item>
            <el-form-item label="持续关注（7 天）">
              <el-switch v-model="form.keepWatch" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="form.note" type="textarea" :rows="2" placeholder="补充说明…" />
            </el-form-item>
            <el-button
              type="primary"
              size="large"
              style="width: 100%"
              :icon="'Promotion'"
              @click="submit"
              >提交反馈并闭环</el-button
            >
            <p class="foot-tip">
              提交后线索状态流转，并记录操作人 / 时间 / 对象 / 来源IP / traceId。
            </p>
          </el-form>

          <!-- 已有反馈只读 -->
          <el-descriptions v-else :column="1" border size="small">
            <el-descriptions-item label="核实人">{{ clue.feedback.operator }}</el-descriptions-item>
            <el-descriptions-item label="核实时间">{{ clue.feedback.time }}</el-descriptions-item>
            <el-descriptions-item label="联系情况">{{
              clue.feedback.contact
            }}</el-descriptions-item>
            <el-descriptions-item label="核实结果">{{ clue.feedback.result }}</el-descriptions-item>
            <el-descriptions-item v-if="clue.feedback.noiseReason" label="噪声原因">{{
              clue.feedback.noiseReason
            }}</el-descriptions-item>
            <el-descriptions-item label="关怀方式">{{
              clue.feedback.careWay ?? '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="持续关注">{{
              clue.feedback.keepWatch ? '是' : '否'
            }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-col>
    </el-row>
  </div>

  <div class="ss-page" v-else>
    <EmptyState text="未找到该线索" icon="DocumentDelete" />
    <div style="text-align: center">
      <el-button @click="router.push('/clues')">返回线索列表</el-button>
    </div>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 0;
  padding: 14px 18px;
}
.ov-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0 18px;
}
.ov-label {
  font-size: 12px;
  color: var(--ss-text-muted);
}
.ov-val {
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.ov-val.over {
  color: #e5523f;
}
.pad {
  padding: 16px 18px;
}
.mt16 {
  margin-top: 16px;
}
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.mr6 {
  margin-right: 6px;
}
.ml12 {
  margin-left: 12px;
}
.feedback {
  position: sticky;
  top: 12px;
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 10px 0 0;
}
.score-calc {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
}
.sc-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 70px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--ss-bg);
}
.sc-num {
  font-size: 26px;
  font-weight: 800;
  line-height: 1.1;
}
.sc-num.accent {
  color: var(--ss-accent);
}
.sc-num.warn {
  color: #e6a23c;
}
.sc-num.primary {
  color: var(--ss-primary-dark);
}
.sc-lab {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin-top: 4px;
}
.sc-op {
  font-size: 20px;
  color: var(--ss-text-muted);
}
.sc-level {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: 6px;
  font-weight: 600;
}
.sc-th {
  font-size: 12px;
  color: var(--ss-text-muted);
  font-weight: 400;
}
.sc-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--ss-text-muted);
}
</style>
