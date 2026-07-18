<script setup lang="ts">
import { computed } from 'vue';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import LineChart from '@/components/charts/LineChart.vue';
import PieChart from '@/components/charts/PieChart.vue';
import BarChart from '@/components/charts/BarChart.vue';
import { ElMessage } from 'element-plus';
import { dashboardKpi, trend7d, categoryDist, levelDist, collegeStats } from '@/mock';
import { COMPLIANCE_BANNER } from '@/utils/constants';
import { pct } from '@/utils/format';
import type { CollegeStat } from '@/types';

// 近 7 日：新增 vs 已闭环
const trendSeries = computed(() => [
  { name: '新增线索', data: trend7d.newClues, color: '#2f6db0' },
  { name: '已闭环', data: trend7d.closed, color: '#1b8f7a' },
]);

// 各学院对比：新增线索数 与 闭环率
const collegeCats = computed(() => collegeStats.map((c) => c.college.replace(/学院|学部$/, '')));
const collegeBarSeries = computed(() => [
  { name: '新增线索', data: collegeStats.map((c) => c.newClues), color: '#2f6db0' },
  { name: '闭环率(%)', data: collegeStats.map((c) => c.closeRate), color: '#1b8f7a' },
]);

const closeRateColor = (v: number) => (v >= 88 ? '#1f9d72' : v >= 82 ? '#e6a23c' : '#e5523f');

function exportReport() {
  ElMessage.success('总览报表已导出，操作已留痕（操作人 / 时间 / 对象 / 来源IP / traceId）');
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="成长总览"
      subtitle="面向 学工 / 学院 / 领导 · 汇总态势，线索而非结论，授权可见而非全员可查"
    >
      <template #extra>
        <el-button :icon="'Refresh'" plain>刷新</el-button>
        <el-button type="primary" :icon="'Download'" @click="exportReport">导出报表</el-button>
      </template>
    </PageHeader>

    <!-- 合规提示 -->
    <el-alert type="info" :closable="false" show-icon class="mb16" title="阅读须知">
      <template #default>{{ COMPLIANCE_BANNER }}</template>
    </el-alert>

    <!-- 统计卡行 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="今日新增线索"
          :value="dashboardKpi.newCluesToday"
          unit="条"
          icon="Document"
          color="#2f6db0"
          hint="均为需人工核实事项"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="重点关怀学生"
          :value="dashboardKpi.keyStudents"
          unit="人"
          icon="UserFilled"
          color="#e5523f"
          hint="按授权范围统计"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="闭环率"
          :value="pct(dashboardKpi.closeRate)"
          icon="CircleCheck"
          color="#1f9d72"
          hint="已核实并完成关怀闭环"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="噪声率"
          :value="pct(dashboardKpi.noiseRate)"
          icon="Filter"
          color="#e6a23c"
          hint="核实为非真实关切的占比"
        />
      </el-col>
    </el-row>

    <!-- 趋势 + 类别/等级分布 -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :md="14">
        <div class="ss-card pad">
          <div class="ss-section-title" style="margin: 0 0 8px">近 7 日趋势 · 新增 vs 已闭环</div>
          <LineChart :dates="trend7d.dates" :series="trendSeries" height="300px" />
        </div>
      </el-col>
      <el-col :xs="24" :md="10">
        <div class="ss-card pad">
          <div class="ss-section-title" style="margin: 0 0 8px">线索类别分布</div>
          <PieChart
            :data="categoryDist"
            height="300px"
            :colors="['#3b7cff', '#16a394', '#e5523f']"
          />
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :md="10">
        <div class="ss-card pad">
          <div class="ss-section-title" style="margin: 0 0 8px">关怀等级分布</div>
          <PieChart :data="levelDist" height="280px" :colors="['#1f9d72', '#e6a23c', '#e5523f']" />
        </div>
      </el-col>
      <el-col :xs="24" :md="14">
        <div class="ss-card pad">
          <div class="ss-section-title" style="margin: 0 0 8px">各学院对比 · 新增线索与闭环率</div>
          <BarChart :categories="collegeCats" :series="collegeBarSeries" height="280px" />
        </div>
      </el-col>
    </el-row>

    <!-- 各学院明细 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">各学院成长关怀概览</div>
        <span class="ss-muted small">按学院汇总 · 下钻明细需相应授权</span>
      </div>
      <el-table :data="collegeStats" style="width: 100%">
        <el-table-column type="index" label="#" width="56" align="center" />
        <el-table-column prop="college" label="学院" min-width="200" />
        <el-table-column label="新增线索" width="110" align="center">
          <template #default="{ row }">
            <span class="num">{{ (row as CollegeStat).newClues }}</span> 条
          </template>
        </el-table-column>
        <el-table-column label="闭环率" min-width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="(row as CollegeStat).closeRate"
              :stroke-width="14"
              :color="closeRateColor((row as CollegeStat).closeRate)"
              :format="(p: number) => pct(p)"
            />
          </template>
        </el-table-column>
        <el-table-column label="噪声率" width="120" align="center">
          <template #default="{ row }">
            <el-tag
              :type="(row as CollegeStat).noiseRate >= 22 ? 'warning' : 'info'"
              effect="plain"
              round
              size="small"
            >
              {{ pct((row as CollegeStat).noiseRate) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="超期" width="100" align="center">
          <template #default="{ row }">
            <el-badge
              v-if="(row as CollegeStat).overdue > 0"
              :value="(row as CollegeStat).overdue"
              type="danger"
            />
            <span v-else class="ss-muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="foot-tip">
        数据为系统汇总的「需人工核实线索」统计，非对学生或学院的结论性评价；建议结合实际谈心了解，避免标签化。
      </p>
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
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.small {
  font-size: 12px;
}
.num {
  font-weight: 600;
  font-size: 15px;
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 12px 0 0;
}
</style>
