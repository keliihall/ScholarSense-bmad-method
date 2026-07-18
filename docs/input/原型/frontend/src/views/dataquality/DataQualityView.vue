<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import { dataQuality } from '@/mock';
import type { DataQuality } from '@/types';

// 本地可变副本，便于演示熔断开关（不改动 Mock 原数据）
const sources = ref<DataQuality[]>(dataQuality.map((d) => ({ ...d })));

const total = computed(() => sources.value.length);
const normalCount = computed(() => sources.value.filter((d) => !d.fused).length);
const fusedCount = computed(() => sources.value.filter((d) => d.fused).length);
const avgCoverage = computed(() => {
  if (!sources.value.length) return 0;
  const sum = sources.value.reduce((acc, d) => acc + d.coverage, 0);
  return Math.round((sum / sources.value.length) * 10) / 10;
});

const fusedSources = computed(() => sources.value.filter((d) => d.fused));

// 表格行高亮：熔断行
function rowClassName(o: { row: DataQuality }) {
  return o.row.fused ? 'fused-row' : '';
}

// 进度条颜色：达标绿、临界橙、不达标红
function barColor(v: number) {
  if (v >= 95) return '#1f9d72';
  if (v >= 90) return '#e6a23c';
  return '#e5523f';
}

function onFuseChange(row: DataQuality) {
  if (row.fused) {
    ElMessage.warning(
      `已对「${row.source}」执行熔断：该源相关规则暂停，不产生正式线索（操作已留痕 · 操作人/时间/对象/来源IP/traceId）`,
    );
  } else {
    ElMessage.success(
      `已恢复「${row.source}」：相关规则重新生效（操作已留痕 · 操作人/时间/对象/来源IP/traceId）`,
    );
  }
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="数据质量"
      subtitle="一数一源 · 最小必要 · 质量不达标即熔断 · 熔断仅暂停取数与规则，不产生结论"
    >
      <template #extra>
        <el-button :icon="'Refresh'" plain>刷新质检</el-button>
      </template>
    </PageHeader>

    <!-- 顶部概览 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard title="数据源总数" :value="total" unit="个" icon="Coin" color="#2f6db0" />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="质量正常"
          :value="normalCount"
          unit="个"
          icon="CircleCheck"
          color="#1f9d72"
          hint="覆盖率/连续性达标"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="已熔断"
          :value="fusedCount"
          unit="个"
          icon="SwitchButton"
          color="#e5523f"
          hint="相关规则暂停"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="平均覆盖率"
          :value="avgCoverage"
          unit="%"
          icon="DataLine"
          color="#16a394"
        />
      </el-col>
    </el-row>

    <!-- 熔断提示横幅 -->
    <el-alert
      v-if="fusedCount"
      class="mt16"
      type="warning"
      show-icon
      :closable="false"
      :title="`当前有 ${fusedCount} 个数据源已熔断：${fusedSources.map((f) => f.source).join('、')}`"
    >
      <template #default>
        熔断期间，该源相关规则已自动暂停、<b>不产生正式线索</b>，避免低质量数据引发误判。质量恢复达标后可手动解除熔断。
      </template>
    </el-alert>

    <!-- 数据源质检表 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">数据源质检明细</div>
        <span class="ss-muted small"
          >主键完整性 · 连续性 · 覆盖率（&lt; 90% 视为不达标，建议熔断）</span
        >
      </div>

      <el-table :data="sources" style="width: 100%" :row-class-name="rowClassName">
        <el-table-column label="数据源" min-width="150">
          <template #default="{ row }">
            <div class="src">
              <span class="src-name">{{ (row as DataQuality).source }}</span>
              <el-tag
                v-if="(row as DataQuality).fused"
                type="danger"
                size="small"
                effect="dark"
                round
                >已熔断</el-tag
              >
            </div>
          </template>
        </el-table-column>
        <el-table-column label="责任部门" min-width="130">
          <template #default="{ row }">{{ (row as DataQuality).owner }}</template>
        </el-table-column>
        <el-table-column label="更新频率" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="plain"
              :type="(row as DataQuality).freq === '准实时' ? 'success' : 'info'"
            >
              {{ (row as DataQuality).freq }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="主键完整性" min-width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="(row as DataQuality).pkIntegrity"
              :stroke-width="10"
              :color="barColor((row as DataQuality).pkIntegrity)"
              :format="(p: number) => `${p}%`"
            />
          </template>
        </el-table-column>
        <el-table-column label="连续性" min-width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="(row as DataQuality).continuity"
              :stroke-width="10"
              :color="barColor((row as DataQuality).continuity)"
              :format="(p: number) => `${p}%`"
            />
          </template>
        </el-table-column>
        <el-table-column label="覆盖率" min-width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="(row as DataQuality).coverage"
              :stroke-width="10"
              :color="barColor((row as DataQuality).coverage)"
              :format="(p: number) => `${p}%`"
            />
          </template>
        </el-table-column>
        <el-table-column label="最近更新" width="160" align="center">
          <template #default="{ row }">
            <span class="ss-muted small">{{ (row as DataQuality).lastUpdate }}</span>
          </template>
        </el-table-column>
        <el-table-column label="熔断" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-switch
              v-model="(row as DataQuality).fused"
              inline-prompt
              active-text="断"
              inactive-text="通"
              style="--el-switch-on-color: #e5523f; --el-switch-off-color: #1f9d72"
              @change="onFuseChange(row as DataQuality)"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 治理说明 -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :md="8">
        <div class="ss-card pad principle">
          <div class="p-icon" style="background: #e8f4f1; color: #16a394">
            <el-icon :size="20"><Connection /></el-icon>
          </div>
          <div class="p-title">一数一源</div>
          <div class="p-desc ss-muted">
            同一指标只取唯一权威源，源头由责任部门负责，避免口径冲突与重复采集。
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :md="8">
        <div class="ss-card pad principle">
          <div class="p-icon" style="background: #eef4fc; color: #2f6db0">
            <el-icon :size="20"><Filter /></el-icon>
          </div>
          <div class="p-title">最小必要</div>
          <div class="p-desc ss-muted">
            仅采集关怀线索所必需的字段，高敏数据按授权可见、按期删除，遵循数据最小化。
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :md="8">
        <div class="ss-card pad principle">
          <div class="p-icon" style="background: #fdeeec; color: #e5523f">
            <el-icon :size="20"><SwitchButton /></el-icon>
          </div>
          <div class="p-title">不达标即熔断</div>
          <div class="p-desc ss-muted">
            主键完整性/连续性/覆盖率不达标时熔断该源相关规则，仅暂停取数，不产生正式线索。
          </div>
        </div>
      </el-col>
    </el-row>

    <p class="foot-tip ss-muted">
      数据质量为线索可信度的前置保障；熔断为「暂停取数与规则」的防误判机制，<b>不构成对任何学生的结论</b>，所有熔断/恢复操作均记录操作人、时间、对象、来源IP
      与 traceId。
    </p>
  </div>
</template>

<style scoped>
.pad {
  padding: 16px 18px;
}
.mt16 {
  margin-top: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.small {
  font-size: 12px;
}
.src {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.src-name {
  font-weight: 600;
}
.principle {
  height: 100%;
}
.p-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 10px;
}
.p-title {
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 6px;
}
.p-desc {
  font-size: 13px;
  line-height: 1.6;
}
.foot-tip {
  font-size: 12px;
  margin: 16px 2px 0;
  line-height: 1.6;
}
:deep(.fused-row) {
  background: #fdf6f5;
}
:deep(.fused-row:hover > td.el-table__cell) {
  background: #fbecea !important;
}
</style>
