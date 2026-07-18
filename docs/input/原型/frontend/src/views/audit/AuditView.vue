<script setup lang="ts">
import { computed, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import EmptyState from '@/components/EmptyState.vue';
import { auditLogs } from '@/mock';
import type { AuditLog } from '@/types';

const total = auditLogs.length;
const successCount = auditLogs.filter((l) => l.result === '成功').length;
const rejectCount = auditLogs.filter((l) => l.result === '拒绝').length;

/** 操作类型选项（按数据动态去重，避免与类型枚举漂移） */
const actionOptions = computed(() => Array.from(new Set(auditLogs.map((l) => l.action))));

const filters = reactive({
  action: '',
  result: '' as '' | '成功' | '拒绝',
  keyword: '',
});

const filtered = computed(() =>
  auditLogs.filter((l) => {
    if (filters.action && l.action !== filters.action) return false;
    if (filters.result && l.result !== filters.result) return false;
    if (filters.keyword && !l.operator.includes(filters.keyword.trim())) return false;
    return true;
  }),
);

/** 拒绝/越权类操作标红 */
function isDanger(action: string, result: string) {
  return result === '拒绝' || action === '越权拦截' || action === '尝试访问';
}

function actionTagType(action: string) {
  return isDanger(action, '') ? 'danger' : 'info';
}

function rowClass({ row }: { row: AuditLog }) {
  return row.result === '拒绝' ? 'row-reject' : '';
}

function resetFilters() {
  filters.action = '';
  filters.result = '';
  filters.keyword = '';
}

function onExport() {
  ElMessage.success(
    '审计记录导出任务已提交，操作已留痕（操作人 / 时间 / 对象 / 来源IP / traceId）',
  );
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="审计留痕"
      subtitle="登录 / 查看 / 导出 / 跟进 / 规则修改 / 白名单调整全程留痕 · 记录不可篡改、按期留存"
    >
      <template #extra>
        <el-button type="primary" :icon="'Download'" @click="onExport">导出审计记录</el-button>
      </template>
    </PageHeader>

    <!-- 统计卡 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="审计记录总数"
          :value="total"
          unit="条"
          icon="Tickets"
          color="#1b8f7a"
          hint="覆盖关键操作"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="成功操作"
          :value="successCount"
          unit="条"
          icon="CircleCheck"
          color="#1f9d72"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="拒绝 / 越权拦截"
          :value="rejectCount"
          unit="条"
          icon="WarningFilled"
          color="#e5523f"
          hint="访问控制生效"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="留存周期"
          value="≥ 6"
          unit="个月"
          icon="Lock"
          color="#2f6db0"
          hint="不可篡改 · 按期留存"
        />
      </el-col>
    </el-row>

    <!-- 筛选 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">审计记录查询</div>
        <span class="ss-muted small">共 {{ filtered.length }} 条结果</span>
      </div>
      <el-form :inline="true" class="filter-form">
        <el-form-item label="操作类型">
          <el-select v-model="filters.action" placeholder="全部类型" clearable style="width: 150px">
            <el-option v-for="a in actionOptions" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="filters.result" placeholder="全部结果" clearable style="width: 130px">
            <el-option label="成功" value="成功" />
            <el-option label="拒绝" value="拒绝" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input
            v-model="filters.keyword"
            placeholder="按操作人关键字"
            clearable
            style="width: 180px"
            :prefix-icon="'Search'"
          />
        </el-form-item>
        <el-form-item>
          <el-button :icon="'RefreshLeft'" @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 记录表 -->
    <div class="ss-card pad mt16">
      <el-table
        v-if="filtered.length"
        :data="filtered"
        style="width: 100%"
        :row-class-name="rowClass"
        stripe
      >
        <el-table-column prop="id" label="记录ID" width="110" />
        <el-table-column prop="time" label="时间" width="170" />
        <el-table-column prop="operator" label="操作人" width="110" />
        <el-table-column prop="role" label="角色" width="120" />
        <el-table-column label="操作类型" width="120">
          <template #default="{ row }">
            <el-tag
              :type="actionTagType((row as AuditLog).action) as any"
              effect="light"
              size="small"
              round
            >
              {{ (row as AuditLog).action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="target" label="对象" min-width="220" show-overflow-tooltip />
        <el-table-column prop="ip" label="来源IP" width="130" />
        <el-table-column label="traceId" width="130">
          <template #default="{ row }">
            <span class="trace">{{ (row as AuditLog).traceId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="92" fixed="right">
          <template #default="{ row }">
            <el-tag
              :type="(row as AuditLog).result === '成功' ? 'success' : 'danger'"
              effect="dark"
              size="small"
            >
              {{ (row as AuditLog).result }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else text="无符合条件的审计记录" icon="DocumentDelete" />

      <el-alert class="mt12" type="info" :closable="false" show-icon title="留痕说明">
        <template #default>
          系统对登录、查看、导出、跟进、规则修改、白名单调整等关键操作全程留痕，记录「谁 · 何时 ·
          做了什么 · 来源IP · traceId」；审计记录不可篡改、按期留存（≥ 6
          个月）。越权访问与敏感字段尝试均被拦截并记录，授权可见而非全员可查。
        </template>
      </el-alert>
    </div>
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
.mb12 {
  margin-bottom: 12px;
}
.small {
  font-size: 12px;
}
.filter-form {
  display: flex;
  flex-wrap: wrap;
  row-gap: 4px;
}
.trace {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  color: var(--ss-text-muted);
}
:deep(.row-reject) {
  background: #fef0f0;
}
:deep(.row-reject:hover > td.el-table__cell) {
  background: #fde2e2 !important;
}
</style>
