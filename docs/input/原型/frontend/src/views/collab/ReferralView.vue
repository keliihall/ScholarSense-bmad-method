<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import EmptyState from '@/components/EmptyState.vue';
import SensitiveField from '@/components/SensitiveField.vue';
import { referrals } from '@/mock';
import type { Referral } from '@/types';

type ReferralStatus = Referral['status'];
type ReferralTarget = Referral['target'];

const STATUS_META: Record<
  ReferralStatus,
  { tag: 'info' | 'warning' | 'success'; icon: string; color: string }
> = {
  待接收: { tag: 'warning', icon: 'Bell', color: '#e6a23c' },
  处理中: { tag: 'warning', icon: 'Loading', color: '#3b7cff' },
  已回填: { tag: 'success', icon: 'CircleCheck', color: '#1f9d72' },
  已关闭: { tag: 'info', icon: 'Finished', color: '#909399' },
};

// 目标部门配色（演示用，颜色集中于此保持一致）
const TARGET_META: Record<
  ReferralTarget,
  { tag: 'danger' | 'success' | 'primary' | 'warning' | 'info'; sensitive: boolean }
> = {
  心理: { tag: 'danger', sensitive: true },
  资助: { tag: 'success', sensitive: false },
  教务: { tag: 'primary', sensitive: false },
  保卫: { tag: 'warning', sensitive: false },
  学院: { tag: 'info', sensitive: false },
  家长: { tag: 'info', sensitive: false },
};

const STATUS_ORDER: ReferralStatus[] = ['待接收', '处理中', '已回填', '已关闭'];

const counts = computed<Record<ReferralStatus, number>>(() => {
  const base: Record<ReferralStatus, number> = { 待接收: 0, 处理中: 0, 已回填: 0, 已关闭: 0 };
  referrals.forEach((r) => (base[r.status] += 1));
  return base;
});

const statusFilter = ref<ReferralStatus | 'all'>('all');
const list = computed(() =>
  statusFilter.value === 'all'
    ? referrals
    : referrals.filter((r) => r.status === statusFilter.value),
);

// 抽屉 / 流转
const drawerOpen = ref(false);
const active = ref<Referral | null>(null);
const fillForm = reactive({ result: '' });

const canFill = computed(
  () => active.value?.status === '待接收' || active.value?.status === '处理中',
);
const activeTarget = computed(() => (active.value ? TARGET_META[active.value.target] : null));

function openFlow(row: Referral) {
  active.value = row;
  fillForm.result = '';
  drawerOpen.value = true;
}

function submitFill() {
  if (!fillForm.result.trim()) {
    ElMessage.warning('请先填写处理结果');
    return;
  }
  ElMessage.success(
    '处理结果已回填，状态待对方部门确认流转 · 操作已留痕（操作人 / 时间 / 对象 / 来源IP / traceId）',
  );
  fillForm.result = '';
  drawerOpen.value = false;
}

function exportFlow() {
  ElMessage.success(
    '流转记录导出申请已提交 · 操作已留痕（操作人 / 时间 / 对象 / 来源IP / traceId）',
  );
}
</script>

<template>
  <div class="ss-page">
    <PageHeader title="协同转介" subtitle="多方联动 · 责任与状态流转 · 转介为协同处置，非自动决策">
      <template #extra>
        <el-button :icon="'Download'" plain @click="exportFlow">导出流转记录</el-button>
      </template>
    </PageHeader>

    <!-- 合规提示 -->
    <el-alert
      class="mb16"
      type="info"
      :closable="false"
      show-icon
      title="转介是多方联动的责任与状态流转，由相关部门人工核实处置，系统不作自动决策。"
    />

    <!-- 状态计数统计卡（可点击筛选） -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <div
          class="stat-wrap"
          :class="{ on: statusFilter === '待接收' }"
          @click="statusFilter = '待接收'"
        >
          <StatCard
            title="待接收"
            :value="counts['待接收']"
            unit="件"
            icon="Bell"
            color="#e6a23c"
            hint="需对方部门接收"
          />
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div
          class="stat-wrap"
          :class="{ on: statusFilter === '处理中' }"
          @click="statusFilter = '处理中'"
        >
          <StatCard
            title="处理中"
            :value="counts['处理中']"
            unit="件"
            icon="Loading"
            color="#3b7cff"
          />
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div
          class="stat-wrap"
          :class="{ on: statusFilter === '已回填' }"
          @click="statusFilter = '已回填'"
        >
          <StatCard
            title="已回填"
            :value="counts['已回填']"
            unit="件"
            icon="CircleCheck"
            color="#1f9d72"
            hint="结果已返回"
          />
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div
          class="stat-wrap"
          :class="{ on: statusFilter === '已关闭' }"
          @click="statusFilter = '已关闭'"
        >
          <StatCard
            title="已关闭"
            :value="counts['已关闭']"
            unit="件"
            icon="Finished"
            color="#909399"
          />
        </div>
      </el-col>
    </el-row>

    <!-- 转介列表 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">转介工单</div>
        <el-radio-group v-model="statusFilter" size="small">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button v-for="s in STATUS_ORDER" :key="s" :label="s">{{ s }}</el-radio-button>
        </el-radio-group>
      </div>

      <el-table v-if="list.length" :data="list" style="width: 100%">
        <el-table-column label="转介ID" prop="id" width="146" />
        <el-table-column label="学生" width="120">
          <template #default="{ row }">
            <SensitiveField
              class="stu"
              :value="(row as Referral).studentName"
              type="text"
              :allow="TARGET_META[(row as Referral).target].sensitive ? undefined : true"
            />
          </template>
        </el-table-column>
        <el-table-column label="目标部门" width="120">
          <template #default="{ row }">
            <el-tag
              :type="TARGET_META[(row as Referral).target].tag as any"
              effect="light"
              size="small"
              round
            >
              {{ (row as Referral).target }}
            </el-tag>
            <el-tooltip
              v-if="TARGET_META[(row as Referral).target].sensitive"
              content="心理类转介为高敏，仅授权角色可见详情，访问留痕"
              placement="top"
            >
              <el-icon class="lock"><Lock /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="发起人" prop="from" min-width="150" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag
              :type="STATUS_META[(row as Referral).status].tag"
              effect="dark"
              size="small"
              round
            >
              {{ (row as Referral).status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" prop="createdAt" width="160" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="'Share'" @click="openFlow(row as Referral)"
              >查看流转</el-button
            >
          </template>
        </el-table-column>
      </el-table>

      <EmptyState v-else text="该状态下暂无转介工单" icon="Files" />
    </div>

    <!-- 流转抽屉 -->
    <el-drawer
      v-model="drawerOpen"
      :title="active ? `转介流转 · ${active.id}` : '转介流转'"
      size="46%"
    >
      <div v-if="active" class="flow">
        <!-- 概览 -->
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="学生">
            <SensitiveField
              :value="active.studentName"
              type="text"
              :allow="activeTarget?.sensitive ? undefined : true"
            />
          </el-descriptions-item>
          <el-descriptions-item label="关联线索">{{ active.clueId }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ active.from }}</el-descriptions-item>
          <el-descriptions-item label="目标部门">
            <el-tag :type="activeTarget?.tag as any" effect="light" size="small" round>{{
              active.target
            }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag :type="STATUS_META[active.status].tag" effect="dark" size="small" round>{{
              active.status
            }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ active.createdAt }}</el-descriptions-item>
        </el-descriptions>

        <!-- 心理类高敏提示 -->
        <el-alert
          v-if="activeTarget?.sensitive"
          class="mt12"
          type="warning"
          :closable="false"
          show-icon
          title="心理类转介为高敏信息"
        >
          <template #default
            >详情仅对心理中心及授权角色可见，全程访问留痕；请勿外传、勿标签化。</template
          >
        </el-alert>

        <!-- 转介事由 -->
        <div class="ss-section-title mt16">转介事由</div>
        <div class="text-box">{{ active.reason }}</div>

        <!-- 处理结果回填（已回填/已关闭只读展示） -->
        <template v-if="active.result">
          <div class="ss-section-title mt16">处理结果（对方部门回填）</div>
          <div class="text-box result">{{ active.result }}</div>
        </template>

        <!-- 流转时间线 -->
        <div class="ss-section-title mt16">流转记录</div>
        <el-timeline class="tl">
          <el-timeline-item
            v-for="(step, i) in active.steps"
            :key="i"
            :timestamp="step.time"
            placement="top"
            :type="i === active.steps.length - 1 ? 'primary' : 'success'"
            :hollow="i !== active.steps.length - 1"
          >
            <div class="tl-actor">{{ step.actor }}</div>
            <div class="tl-action">{{ step.action }}</div>
          </el-timeline-item>
        </el-timeline>

        <!-- 回填表单（仅 待接收 / 处理中） -->
        <template v-if="canFill">
          <el-divider />
          <div class="ss-section-title">回填处理结果</div>
          <el-form label-position="top">
            <el-form-item label="处理结果说明" required>
              <el-input
                v-model="fillForm.result"
                type="textarea"
                :rows="3"
                maxlength="300"
                show-word-limit
                placeholder="如：已接收并安排访谈 / 已完成评估，建议持续关注…（请填写人工核实后的处置情况）"
              />
            </el-form-item>
            <el-button type="primary" :icon="'Promotion'" style="width: 100%" @click="submitFill">
              提交回填
            </el-button>
            <p class="foot-tip">
              回填即记录操作人 / 时间 / 对象 / 来源IP /
              traceId；转介为多方联动处置，状态由相关部门人工流转，系统不作自动决策。
            </p>
          </el-form>
        </template>

        <el-alert
          v-else
          class="mt16"
          type="success"
          :closable="false"
          show-icon
          title="该转介已回填 / 已关闭，处理结果已留痕归档。"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.pad {
  padding: 16px 18px;
}
.mt16 {
  margin-top: 16px;
}
.mt12 {
  margin-top: 12px;
}
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.stat-wrap {
  cursor: pointer;
  border-radius: var(--ss-radius);
  transition: all 0.2s;
}
.stat-wrap.on {
  outline: 2px solid var(--ss-primary);
  outline-offset: 1px;
  border-radius: var(--ss-radius);
}
.stu {
  font-weight: 600;
}
.lock {
  color: var(--ss-text-muted);
  font-size: 13px;
  margin-left: 4px;
  vertical-align: middle;
}
.flow {
  padding: 0 2px;
}
.text-box {
  background: var(--ss-primary-soft);
  border: 1px solid var(--ss-border);
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ss-text);
}
.text-box.result {
  background: #eef7f2;
}
.tl {
  padding-left: 2px;
}
.tl-actor {
  font-weight: 600;
  font-size: 13px;
}
.tl-action {
  color: var(--ss-text-muted);
  font-size: 13px;
  margin-top: 2px;
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 10px 0 0;
  line-height: 1.6;
}
</style>
