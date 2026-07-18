<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import PieChart from '@/components/charts/PieChart.vue';
import BarChart from '@/components/charts/BarChart.vue';
import LineChart from '@/components/charts/LineChart.vue';
import { collegeStats, dashboardKpi, noiseReasons, trend7d, referrals } from '@/mock';
import { pct } from '@/utils/format';
import type { CollegeStat } from '@/types';

/** 报告周期 */
const period = ref<'week' | 'month'>('week');
const periodLabel = computed(() => (period.value === 'week' ? '周报' : '月报'));
const periodRange = computed(() =>
  period.value === 'week'
    ? '2026-06-18 ~ 2026-06-24（近 7 日）'
    : '2026-06-01 ~ 2026-06-24（本月累计）',
);

/** KPI（求和来自 collegeStats，比率来自 dashboardKpi） */
const totalNewClues = computed(() => collegeStats.reduce((s, c) => s + c.newClues, 0));
const totalOverdue = computed(() => collegeStats.reduce((s, c) => s + c.overdue, 0));

/** 各学院闭环率对比（横向柱状） */
const closeRateBar = computed(() => ({
  categories: collegeStats.map((c) => c.college.replace(/学院|学部$/, '')),
  series: [{ name: '闭环率(%)', data: collegeStats.map((c) => c.closeRate), color: '#1b8f7a' }],
}));

/** 近 7 日趋势 */
const trendSeries = computed(() => [
  { name: '新增线索', data: trend7d.newClues, color: '#2f6db0' },
  { name: '已闭环', data: trend7d.closed, color: '#1b8f7a' },
]);

/** 噪声原因 Top 文案 */
const noiseTop = computed(() => {
  const total = noiseReasons.reduce((s, n) => s + n.value, 0) || 1;
  return [...noiseReasons]
    .sort((a, b) => b.value - a.value)
    .slice(0, 3)
    .map((n) => ({ name: n.name, value: n.value, ratio: pct((n.value / total) * 100) }));
});

/** 协同成效（来自 referrals 统计） */
const referralStats = computed(() => {
  const total = referrals.length;
  const handled = referrals.filter((r) => r.status === '已回填' || r.status === '已关闭').length;
  const inProgress = referrals.filter((r) => r.status === '处理中' || r.status === '待接收').length;
  const targets = Array.from(new Set(referrals.map((r) => r.target)));
  return { total, handled, inProgress, targets: targets.join(' / ') };
});

function exportReport() {
  ElMessage.success(
    `${periodLabel.value}已生成并导出（PDF）。操作已留痕：操作人 王老师 · ${new Date().toLocaleString()} · 对象 ${periodLabel.value} · 来源IP 10.12.x.x · traceId TRC-RPT-0626`,
  );
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="周报 / 月报"
      subtitle="关怀工作运行概览 · 数据为辅助统计，线索结论以人工核实为准"
    >
      <template #extra>
        <el-radio-group v-model="period">
          <el-radio-button label="week">周报</el-radio-button>
          <el-radio-button label="month">月报</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :icon="'Download'" @click="exportReport"
          >导出{{ periodLabel }}</el-button
        >
      </template>
    </PageHeader>

    <!-- 报告周期与口径说明 -->
    <el-alert type="info" :closable="false" show-icon class="mb16">
      <template #title>
        当前为<b>{{ periodLabel }}</b
        >，统计周期
        {{
          periodRange
        }}。本报表用于关怀工作回顾与资源调度，<b>不对学生个体作出任何自动判定</b>；任何线索均需辅导员人工核实后方可处置。
      </template>
    </el-alert>

    <!-- KPI -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="新增线索总数"
          :value="totalNewClues"
          unit="条"
          icon="DocumentAdd"
          color="#2f6db0"
          hint="各学院汇总"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="闭环率"
          :value="dashboardKpi.closeRate"
          unit="%"
          icon="CircleCheck"
          color="#1f9d72"
          hint="已核实并完成跟进占比"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="噪声率"
          :value="dashboardKpi.noiseRate"
          unit="%"
          icon="Filter"
          color="#e6a23c"
          hint="核实为噪声占比"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="超期总数"
          :value="totalOverdue"
          unit="条"
          icon="Warning"
          color="#e5523f"
          hint="需关注督办"
        />
      </el-col>
    </el-row>

    <!-- 图表区 -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :md="9">
        <div class="ss-card pad full">
          <div class="ss-section-title">噪声原因分布</div>
          <PieChart :data="noiseReasons" height="300px" />
          <p class="chart-tip">噪声反馈帮助持续校准规则，降低对学生的误打扰。</p>
        </div>
      </el-col>
      <el-col :xs="24" :md="15">
        <div class="ss-card pad full">
          <div class="ss-between mb12">
            <div class="ss-section-title" style="margin: 0">各学院闭环率对比</div>
            <span class="ss-muted small">闭环率 = 已核实闭环 / 新增线索</span>
          </div>
          <BarChart
            :categories="closeRateBar.categories"
            :series="closeRateBar.series"
            height="300px"
            horizontal
          />
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt16">
      <el-col :span="24">
        <div class="ss-card pad full">
          <div class="ss-between mb12">
            <div class="ss-section-title" style="margin: 0">线索新增与闭环趋势</div>
            <span class="ss-muted small">近 7 日 · 新增 vs 已闭环</span>
          </div>
          <LineChart :dates="trend7d.dates" :series="trendSeries" height="280px" />
        </div>
      </el-col>
    </el-row>

    <!-- 各学院明细表 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">各学院关怀工作明细</div>
        <span class="ss-muted small">仅展示汇总指标，不含学生个人明细</span>
      </div>
      <el-table :data="collegeStats" style="width: 100%" stripe>
        <el-table-column type="index" label="#" width="56" align="center" />
        <el-table-column label="学院" min-width="200">
          <template #default="{ row }">
            <span class="college">{{ (row as CollegeStat).college }}</span>
          </template>
        </el-table-column>
        <el-table-column label="新增线索" width="110" align="center">
          <template #default="{ row }">{{ (row as CollegeStat).newClues }} 条</template>
        </el-table-column>
        <el-table-column label="闭环率" width="160">
          <template #default="{ row }">
            <div class="rate-cell">
              <el-progress
                :percentage="(row as CollegeStat).closeRate"
                :stroke-width="8"
                :color="'#1b8f7a'"
                :show-text="false"
                style="flex: 1"
              />
              <span class="rate-num">{{ pct((row as CollegeStat).closeRate) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="噪声率" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              :type="((row as CollegeStat).noiseRate > 20 ? 'warning' : 'info') as any"
              effect="plain"
              size="small"
            >
              {{ pct((row as CollegeStat).noiseRate) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="超期" width="100" align="center">
          <template #default="{ row }">
            <span :class="{ over: (row as CollegeStat).overdue > 0 }"
              >{{ (row as CollegeStat).overdue }} 条</span
            >
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 文字小结 -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :md="12">
        <div class="ss-card pad full">
          <div class="ss-section-title">噪声原因 Top 与规则校准建议</div>
          <ul class="summary-list">
            <li v-for="(n, i) in noiseTop" :key="n.name">
              <span class="rank">Top {{ i + 1 }}</span>
              <span class="sm-name">{{ n.name }}</span>
              <span class="ss-muted">{{ n.value }} 次 · 占比 {{ n.ratio }}</span>
            </li>
          </ul>
          <el-alert type="success" :closable="false" class="mt8">
            <template #title>
              建议结合上述噪声原因优化场景排除（如临近假期、校外就餐），<b>建议而非自动调整</b>，规则变更须经数据责任人复核后灰度。
            </template>
          </el-alert>
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="ss-card pad full">
          <div class="ss-section-title">多部门协同成效</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="协同转介总数"
              >{{ referralStats.total }} 项</el-descriptions-item
            >
            <el-descriptions-item label="已回填 / 已关闭"
              >{{ referralStats.handled }} 项</el-descriptions-item
            >
            <el-descriptions-item label="处理中 / 待接收"
              >{{ referralStats.inProgress }} 项</el-descriptions-item
            >
            <el-descriptions-item label="协同部门">{{
              referralStats.targets
            }}</el-descriptions-item>
          </el-descriptions>
          <p class="chart-tip">
            转介遵循最小授权与按需可见原则，协同部门仅在授权范围内查看，全程留痕可追溯。
          </p>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.pad {
  padding: 16px 18px;
}
.full {
  height: 100%;
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
.college {
  font-weight: 600;
}
.rate-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rate-num {
  font-weight: 600;
  font-size: 13px;
  min-width: 48px;
}
.over {
  color: #e5523f;
  font-weight: 600;
}
.chart-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 10px 0 0;
}
.summary-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.summary-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--ss-border);
}
.summary-list li:last-child {
  border-bottom: none;
}
.rank {
  background: var(--ss-primary-soft);
  color: var(--ss-primary-dark);
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 6px;
  flex-shrink: 0;
}
.sm-name {
  font-weight: 600;
  flex: 1;
}
</style>
