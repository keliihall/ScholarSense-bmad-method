<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import { complianceGates } from '@/mock';
import type { ComplianceGate } from '@/types';

/** 关卡状态 → 标签类型 / 时间线节点状态 / 进度环颜色 */
const STATUS_META: Record<
  ComplianceGate['status'],
  { tag: 'success' | 'warning' | 'info'; node: 'success' | 'warning' | 'primary'; color: string }
> = {
  已完成: { tag: 'success', node: 'success', color: '#1f9d72' },
  进行中: { tag: 'warning', node: 'warning', color: '#e6a23c' },
  待开始: { tag: 'info', node: 'primary', color: '#909399' },
};

const doneCount = computed(() => complianceGates.filter((g) => g.status === '已完成').length);
const doingCount = computed(() => complianceGates.filter((g) => g.status === '进行中').length);
const todoCount = computed(() => complianceGates.filter((g) => g.status === '待开始').length);
const progressPct = computed(() => Math.round((doneCount.value / complianceGates.length) * 100));

/** 四道防线（文案对齐 PRD：线索而非结论、授权可见而非全员可查、全程留痕、人工核实） */
const defenseLines = [
  {
    icon: 'Filter',
    color: '#16a394',
    title: '最小必要',
    desc: '处理学生个人信息坚持「最小必要」：只采必要字段、按目的限定范围、到期删除。夜间上网仅看作息规律性指标，不接入任何访问内容。',
  },
  {
    icon: 'Lock',
    color: '#2f6db0',
    title: '分级授权',
    desc: '授权可见而非全员可查：角色 × 数据范围 × 字段脱敏。辅导员只看责任学生，运维默认不见业务明细，心理关注等高敏标签仅授权角色可见。',
  },
  {
    icon: 'Tickets',
    color: '#e6a23c',
    title: '全程留痕',
    desc: '查看、导出、跟进、规则修改等关键操作均记审计：谁、何时、对谁、来源 IP、traceId，记录不可篡改、按期留存，可追溯、可倒查。',
  },
  {
    icon: 'UserFilled',
    color: '#e5523f',
    title: '人工核实',
    desc: '线索而非结论、建议而非自动决策：系统仅输出需人工核实的线索与关怀建议，重大不利决定不得仅由自动化模型作出，须经辅导员核实确认。',
  },
];

function exportEvidence() {
  ElMessage.success(
    '合规留痕材料导出已提交，操作已留痕（操作人 / 时间 / 对象 / 来源 IP / traceId）',
  );
}

function notifyOwner(gate: ComplianceGate) {
  ElMessage.success(
    `已提醒「${gate.owner}」推进「${gate.step}」，操作已留痕（含操作人 / 时间 / IP / traceId）`,
  );
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="合规检查关卡"
      subtitle="目标合规：等保三级 + PIPL · 上线前须逐关通过，本页为需人工核实的进度概览，非自动放行"
    >
      <template #extra>
        <el-button :icon="'Document'" plain @click="exportEvidence">导出留痕材料</el-button>
      </template>
    </PageHeader>

    <!-- 合规说明横幅 -->
    <el-alert
      type="success"
      :closable="false"
      show-icon
      class="banner"
      title="个人信息处理合规底线（PIPL / 等保三级）"
    >
      <template #default>
        处理学生个人信息坚持<b>最小必要</b>，只采必要字段、按目的限定范围；<b>夜间上网不接入任何访问内容</b>，仅观测作息规律性；<b>重大不利决定不得仅由自动化模型作出</b>，须经人工核实确认。系统输出为「需人工核实的线索」，线索而非结论、建议而非自动决策、授权可见而非全员可查、全程留痕。
      </template>
    </el-alert>

    <!-- 统计卡 -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="关卡总数"
          :value="complianceGates.length"
          unit="道"
          icon="Files"
          color="#2f6db0"
          hint="逐关通过方可上线"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard title="已完成" :value="doneCount" unit="道" icon="CircleCheck" color="#1f9d72" />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard title="进行中" :value="doingCount" unit="道" icon="Loading" color="#e6a23c" />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="待开始"
          :value="todoCount"
          unit="道"
          icon="Clock"
          color="#909399"
          hint="待责任人启动"
        />
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt16">
      <!-- 强制关卡 -->
      <el-col :xs="24" :md="16">
        <div class="ss-card pad">
          <div class="ss-between mb12">
            <div class="ss-section-title" style="margin: 0">
              强制关卡（必要性论证 → PIPIA → 授权脱敏 → 审计回退）
            </div>
            <span class="ss-muted small"
              >{{ doneCount }} / {{ complianceGates.length }} 已完成</span
            >
          </div>

          <el-progress :percentage="progressPct" :stroke-width="10" color="#1b8f7a" class="mb16" />

          <el-timeline>
            <el-timeline-item
              v-for="gate in complianceGates"
              :key="gate.step"
              :type="STATUS_META[gate.status].node"
              :hollow="gate.status === '待开始'"
              size="large"
              :timestamp="gate.updatedAt === '—' ? '尚未启动' : `更新于 ${gate.updatedAt}`"
              placement="top"
            >
              <div class="ss-card gate ss-hover">
                <div class="gate-head">
                  <span class="gate-step">{{ gate.step }}</span>
                  <el-tag :type="STATUS_META[gate.status].tag" effect="light" size="small" round>{{
                    gate.status
                  }}</el-tag>
                </div>
                <p class="gate-desc">{{ gate.desc }}</p>
                <div class="gate-foot">
                  <span class="ss-muted small"
                    ><el-icon><User /></el-icon> 责任方：{{ gate.owner }}</span
                  >
                  <el-button
                    v-if="gate.status !== '已完成'"
                    link
                    type="primary"
                    size="small"
                    @click="notifyOwner(gate)"
                  >
                    提醒推进
                  </el-button>
                  <span v-else class="done-flag small"
                    ><el-icon><Select /></el-icon> 已通过人工复核</span
                  >
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>

          <p class="foot-tip">
            关卡状态由责任方人工填报与复核，<b>非系统自动放行</b>；任一关卡未通过，模块不得进入生产。
          </p>
        </div>
      </el-col>

      <!-- 四道防线 -->
      <el-col :xs="24" :md="8">
        <div class="ss-card pad">
          <div class="ss-section-title" style="margin: 0 0 12px">四道防线</div>
          <div
            v-for="line in defenseLines"
            :key="line.title"
            class="line-card ss-hover"
            :style="{ borderLeftColor: line.color }"
          >
            <div class="line-head">
              <span class="line-icon" :style="{ background: line.color + '1a', color: line.color }">
                <el-icon :size="18"><component :is="line.icon" /></el-icon>
              </span>
              <span class="line-title">{{ line.title }}</span>
            </div>
            <p class="line-desc">{{ line.desc }}</p>
          </div>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="mt8"
            title="授权可见而非全员可查"
          >
            <template #default>
              本页仅展示合规治理进度，不含任何学生个人明细；高敏字段（手机号 / 学号 /
              心理关注）在业务页一律脱敏，按角色授权可见。
            </template>
          </el-alert>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.banner {
  line-height: 1.7;
}
.pad {
  padding: 16px 18px;
}
.mt16 {
  margin-top: 16px;
}
.mt8 {
  margin-top: 8px;
}
.mb12 {
  margin-bottom: 12px;
}
.mb16 {
  margin-bottom: 16px;
}
.small {
  font-size: 12px;
}
.gate {
  padding: 12px 14px;
  background: var(--ss-bg);
  box-shadow: none;
}
.gate-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.gate-step {
  font-weight: 600;
  font-size: 14px;
}
.gate-desc {
  margin: 6px 0 10px;
  color: var(--ss-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.gate-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.gate-foot .el-icon {
  vertical-align: -2px;
  margin-right: 2px;
}
.done-flag {
  color: #1f9d72;
  font-weight: 500;
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 14px 0 0;
}
.line-card {
  border: 1px solid var(--ss-border);
  border-left: 3px solid var(--ss-primary);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
}
.line-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.line-icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.line-title {
  font-weight: 600;
  font-size: 14px;
}
.line-desc {
  margin: 0;
  color: var(--ss-text-muted);
  font-size: 12.5px;
  line-height: 1.65;
}
</style>
