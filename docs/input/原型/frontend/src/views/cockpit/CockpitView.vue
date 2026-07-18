<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import LineChart from '@/components/charts/LineChart.vue';
import PieChart from '@/components/charts/PieChart.vue';
import BarChart from '@/components/charts/BarChart.vue';
import { dashboardKpi, trend7d, categoryDist, levelDist, collegeStats } from '@/mock';
import { pct } from '@/utils/format';

/** 顶部 KPI 磁贴（仅汇总指标，不含任何学生明细） */
const kpis = computed(() => [
  {
    key: 'new',
    label: '今日新增线索',
    value: dashboardKpi.newCluesToday,
    unit: '条',
    icon: 'Bell',
    tone: 'cyan' as const,
    hint: '均为需人工核实事项',
  },
  {
    key: 'key',
    label: '重点关怀学生',
    value: dashboardKpi.keyStudents,
    unit: '人',
    icon: 'StarFilled',
    tone: 'amber' as const,
    hint: '汇总口径 · 明细需授权',
  },
  {
    key: 'close',
    label: '闭环率',
    value: dashboardKpi.closeRate,
    unit: '%',
    icon: 'CircleCheck',
    tone: 'green' as const,
    hint: '近 7 日治理效能',
  },
  {
    key: 'noise',
    label: '噪声率',
    value: dashboardKpi.noiseRate,
    unit: '%',
    icon: 'Filter',
    tone: 'blue' as const,
    hint: '越低越精准',
  },
  {
    key: 'pending',
    label: '待核实',
    value: dashboardKpi.pendingTotal,
    unit: '条',
    icon: 'Loading',
    tone: 'amber' as const,
    hint: '等待辅导员核实',
  },
  {
    key: 'overdue',
    label: '超期',
    value: dashboardKpi.overdueTotal,
    unit: '条',
    icon: 'Warning',
    tone: 'red' as const,
    hint: '需督办关注',
  },
]);

const updatedAt = '2026-06-24 09:00';

/** 近 7 日趋势：新增 vs 已闭环 */
const trendSeries = computed(() => [
  { name: '新增线索', data: trend7d.newClues, color: '#29d3c2' },
  { name: '已闭环', data: trend7d.closed, color: '#2f6db0' },
]);

/** 关怀等级分布配色对齐等级语义（提醒/重点/重点关怀） */
const levelColors = ['#1f9d72', '#e6a23c', '#e5523f'];

/** 各学院新增线索对比（横向条形，便于阅读学院名） */
const collegeBar = computed(() => ({
  categories: collegeStats.map((c) => c.college),
  series: [{ name: '新增线索', data: collegeStats.map((c) => c.newClues), color: '#29d3c2' }],
}));

/** 学院汇总表：闭环率高亮、超期/噪声偏高提示，仅汇总口径 */
const sortedColleges = computed(() => [...collegeStats].sort((a, b) => b.newClues - a.newClues));

function closeRateTone(v: number): 'good' | 'warn' | 'bad' {
  if (v >= 88) return 'good';
  if (v >= 82) return 'warn';
  return 'bad';
}

function requestDrilldown(scope: string) {
  ElMessage.info(
    `「${scope}」属学生明细，需提交授权下钻申请并经审批后查看；本次操作已留痕（操作人 / 时间 / 对象 / 来源IP / traceId）。`,
  );
}
</script>

<template>
  <div class="cockpit-view">
    <!-- 合规提示条 -->
    <div class="ck-banner">
      <span class="ck-badge">需人工核实 · 非结论</span>
      <span class="ck-banner-text">
        驾驶舱仅呈现汇总态势，所有指标为「需人工核实的线索」统计，不作自动决策；学生明细默认不可见，需授权下钻并全程留痕。
      </span>
      <span class="ck-update">数据截至 {{ updatedAt }}</span>
    </div>

    <!-- 顶部 6 个 KPI 磁贴 -->
    <div class="ck-kpis">
      <div v-for="k in kpis" :key="k.key" class="ck-tile" :class="`tone-${k.tone}`">
        <div class="ck-tile-top">
          <span class="ck-tile-label">{{ k.label }}</span>
          <el-icon class="ck-tile-icon"><component :is="k.icon" /></el-icon>
        </div>
        <div class="ck-tile-value">
          {{ k.value }}<span class="ck-tile-unit">{{ k.unit }}</span>
        </div>
        <div class="ck-tile-hint">{{ k.hint }}</div>
      </div>
    </div>

    <!-- 图表区 -->
    <el-row :gutter="16" class="ck-row">
      <el-col :xs="24" :lg="12">
        <section class="ck-panel">
          <header class="ck-panel-head">
            <span class="ck-panel-title">近 7 日线索趋势 · 新增 vs 已闭环</span>
            <span class="ck-panel-sub">闭环率 {{ pct(dashboardKpi.closeRate) }}</span>
          </header>
          <LineChart :dates="trend7d.dates" :series="trendSeries" :dark="true" height="280px" />
        </section>
      </el-col>
      <el-col :xs="24" :lg="12">
        <section class="ck-panel">
          <header class="ck-panel-head">
            <span class="ck-panel-title">线索类别分布</span>
            <span class="ck-panel-sub">学业 / 经济 / 安全</span>
          </header>
          <PieChart :data="categoryDist" :dark="true" height="280px" />
        </section>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="ck-row">
      <el-col :xs="24" :lg="10">
        <section class="ck-panel">
          <header class="ck-panel-head">
            <span class="ck-panel-title">关怀等级分布</span>
            <span class="ck-panel-sub">Ⅰ 提醒 · Ⅱ 重点 · Ⅲ 重点关怀</span>
          </header>
          <PieChart :data="levelDist" :colors="levelColors" :dark="true" height="280px" />
        </section>
      </el-col>
      <el-col :xs="24" :lg="14">
        <section class="ck-panel">
          <header class="ck-panel-head">
            <span class="ck-panel-title">各学院新增线索对比</span>
            <span class="ck-panel-sub">汇总口径 · 需人工核实</span>
          </header>
          <BarChart
            :categories="collegeBar.categories"
            :series="collegeBar.series"
            :dark="true"
            :horizontal="true"
            height="280px"
          />
        </section>
      </el-col>
    </el-row>

    <!-- 各学院关怀与跟进对比 -->
    <section class="ck-panel ck-row">
      <header class="ck-panel-head">
        <span class="ck-panel-title">各学院关怀与跟进对比</span>
        <div class="ck-head-actions">
          <span class="ck-panel-sub">仅汇总指标 · 不含学生明细</span>
          <el-button
            size="small"
            round
            class="ck-drill-btn"
            :icon="'Unlock'"
            @click="requestDrilldown('各学院学生明细')"
          >
            申请授权下钻
          </el-button>
        </div>
      </header>
      <table class="ck-table">
        <thead>
          <tr>
            <th class="left">学院</th>
            <th>新增线索</th>
            <th>闭环率</th>
            <th>噪声率</th>
            <th>超期</th>
            <th class="left">关注提示</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in sortedColleges" :key="c.college">
            <td class="left college">{{ c.college }}</td>
            <td>
              <span class="num">{{ c.newClues }}</span>
            </td>
            <td>
              <div class="rate-cell">
                <span class="rate-val" :class="`rate-${closeRateTone(c.closeRate)}`">{{
                  pct(c.closeRate)
                }}</span>
                <div class="rate-bar">
                  <span
                    class="rate-fill"
                    :class="`rate-${closeRateTone(c.closeRate)}`"
                    :style="{ width: c.closeRate + '%' }"
                  />
                </div>
              </div>
            </td>
            <td>
              <span :class="{ warn: c.noiseRate >= 21 }">{{ pct(c.noiseRate) }}</span>
            </td>
            <td>
              <span :class="{ bad: c.overdue >= 3 }">{{ c.overdue }}</span>
            </td>
            <td class="left">
              <span v-if="c.overdue >= 3" class="flag flag-bad">超期偏高 · 建议督办核实</span>
              <span v-else-if="c.noiseRate >= 21" class="flag flag-warn"
                >噪声偏高 · 建议规则校准</span
              >
              <span v-else-if="c.closeRate >= 88" class="flag flag-good">闭环良好</span>
              <span v-else class="flag flag-mute">运行平稳</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="ck-foot">
        以上为各学院汇总效能，用于态势研判与资源调配；个体学生情况须由责任辅导员人工核实后跟进，不在驾驶舱呈现。
      </p>
    </section>
  </div>
</template>

<style scoped>
.cockpit-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: var(--ss-dark-text);
}

/* 合规提示条 */
.ck-banner {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 16px;
  border: 1px solid var(--ss-dark-border);
  border-radius: var(--ss-radius);
  background: linear-gradient(90deg, rgba(41, 211, 194, 0.1), rgba(15, 36, 64, 0.4));
}
.ck-badge {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: #0a1a2f;
  background: var(--ss-dark-cyan);
  padding: 3px 10px;
  border-radius: 20px;
  box-shadow: 0 0 12px rgba(41, 211, 194, 0.45);
}
.ck-banner-text {
  flex: 1;
  font-size: 13px;
  color: #9cc0e6;
  line-height: 1.5;
}
.ck-update {
  flex: none;
  font-size: 12px;
  color: #6f93ba;
}

/* KPI 磁贴 */
.ck-kpis {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
}
@media (max-width: 1280px) {
  .ck-kpis {
    grid-template-columns: repeat(3, 1fr);
  }
}
@media (max-width: 680px) {
  .ck-kpis {
    grid-template-columns: repeat(2, 1fr);
  }
}
.ck-tile {
  position: relative;
  padding: 16px 16px 14px;
  border: 1px solid var(--ss-dark-border);
  border-radius: var(--ss-radius);
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.03), transparent), var(--ss-dark-panel);
  overflow: hidden;
  transition: all 0.2s;
}
.ck-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--tone, var(--ss-dark-cyan));
  box-shadow: 0 0 14px var(--tone, var(--ss-dark-cyan));
}
.ck-tile:hover {
  transform: translateY(-2px);
  border-color: var(--tone, var(--ss-dark-cyan));
}
.tone-cyan {
  --tone: #29d3c2;
}
.tone-green {
  --tone: #1f9d72;
}
.tone-blue {
  --tone: #2f6db0;
}
.tone-amber {
  --tone: #e6a23c;
}
.tone-red {
  --tone: #e5523f;
}
.ck-tile-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ck-tile-label {
  font-size: 13px;
  color: #9cc0e6;
}
.ck-tile-icon {
  font-size: 18px;
  color: var(--tone, var(--ss-dark-cyan));
  opacity: 0.85;
}
.ck-tile-value {
  margin-top: 8px;
  font-size: 30px;
  font-weight: 700;
  color: #fff;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.ck-tile-unit {
  margin-left: 4px;
  font-size: 14px;
  font-weight: 500;
  color: #8fb6dd;
}
.ck-tile-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #6f93ba;
}

/* 面板 */
.ck-row {
  margin: 0;
}
.ck-panel {
  padding: 14px 18px 16px;
  border: 1px solid var(--ss-dark-border);
  border-radius: var(--ss-radius);
  background: var(--ss-dark-panel);
}
:deep(.el-col) > .ck-panel {
  height: 100%;
}
.ck-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.ck-panel-title {
  position: relative;
  padding-left: 12px;
  font-size: 15px;
  font-weight: 600;
  color: #eaf3ff;
}
.ck-panel-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 15px;
  border-radius: 2px;
  background: var(--ss-dark-cyan);
  box-shadow: 0 0 8px var(--ss-dark-cyan);
}
.ck-panel-sub {
  font-size: 12px;
  color: #6f93ba;
}
.ck-head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ck-drill-btn {
  background: rgba(41, 211, 194, 0.12);
  border-color: var(--ss-dark-cyan);
  color: var(--ss-dark-cyan);
}
.ck-drill-btn:hover {
  background: rgba(41, 211, 194, 0.22);
  border-color: var(--ss-dark-cyan);
  color: #eaf3ff;
}

/* 学院对比表 */
.ck-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.ck-table th,
.ck-table td {
  padding: 10px 12px;
  text-align: center;
  border-bottom: 1px solid var(--ss-dark-border);
}
.ck-table th {
  font-weight: 600;
  color: #8fb6dd;
  font-size: 12px;
  background: rgba(29, 58, 95, 0.35);
}
.ck-table td {
  color: var(--ss-dark-text);
}
.ck-table .left {
  text-align: left;
}
.ck-table tbody tr:hover {
  background: rgba(41, 211, 194, 0.06);
}
.college {
  font-weight: 600;
  color: #eaf3ff;
}
.num {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--ss-dark-cyan);
}
.rate-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.rate-val {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.rate-bar {
  width: 100%;
  max-width: 120px;
  height: 5px;
  border-radius: 3px;
  background: rgba(29, 58, 95, 0.7);
  overflow: hidden;
}
.rate-fill {
  display: block;
  height: 100%;
  border-radius: 3px;
}
.rate-good {
  color: #29d3c2;
}
.rate-good.rate-fill {
  background: #29d3c2;
}
.rate-warn {
  color: #e6a23c;
}
.rate-warn.rate-fill {
  background: #e6a23c;
}
.rate-bad {
  color: #e5523f;
}
.rate-bad.rate-fill {
  background: #e5523f;
}
.warn {
  color: #e6a23c;
  font-weight: 600;
}
.bad {
  color: #e5523f;
  font-weight: 600;
}
.flag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 20px;
  font-size: 12px;
  border: 1px solid transparent;
}
.flag-good {
  color: #29d3c2;
  border-color: rgba(41, 211, 194, 0.4);
  background: rgba(41, 211, 194, 0.1);
}
.flag-warn {
  color: #e6a23c;
  border-color: rgba(230, 162, 60, 0.4);
  background: rgba(230, 162, 60, 0.1);
}
.flag-bad {
  color: #e5523f;
  border-color: rgba(229, 82, 63, 0.4);
  background: rgba(229, 82, 63, 0.1);
}
.flag-mute {
  color: #8fb6dd;
  border-color: var(--ss-dark-border);
}
.ck-foot {
  margin: 12px 0 0;
  font-size: 12px;
  color: #6f93ba;
  line-height: 1.5;
}
</style>
