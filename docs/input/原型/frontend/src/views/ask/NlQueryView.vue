<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import BarChart from '@/components/charts/BarChart.vue';
import PieChart from '@/components/charts/PieChart.vue';
import LineChart from '@/components/charts/LineChart.vue';
import { useAuthStore } from '@/stores/auth';
import { collegeStats, categoryDist, trend7d, noiseReasons } from '@/mock';

type AnswerKind = 'college' | 'category' | 'trend' | 'noise';

interface QaItem {
  id: number;
  question: string;
  /** 一句自然语言结论 */
  conclusion: string;
  kind: AnswerKind;
  /** 数据来源说明 */
  source: string;
}

const auth = useAuthStore();

/** 顶部示例问题 chips —— 仅统计/态势类，不下钻个人明细 */
const samples: { text: string; kind: AnswerKind }[] = [
  { text: '本周各学院新增线索对比？', kind: 'college' },
  { text: '线索类别分布如何？', kind: 'category' },
  { text: '近 7 日闭环趋势怎么样？', kind: 'trend' },
  { text: '噪声原因主要有哪些？', kind: 'noise' },
];

let seq = 2;
const input = ref('');
const listRef = ref<HTMLElement | null>(null);

/** 默认两条示例问答 */
const qaList = ref<QaItem[]>([
  {
    id: 1,
    question: '本周各学院新增线索对比？',
    conclusion:
      '本周共 6 个学院产生新增线索，计算机科学与技术学院最多（23 条），医学部、商学院次之；建议结合各院规模与闭环率综合研判，线索数高不等于问题多。',
    kind: 'college',
    source: '汇总统计 · 各学院周度报表（CollegeStat）',
  },
  {
    id: 2,
    question: '线索类别分布如何？',
    conclusion:
      '当前线索以学业关怀为主（约 45%），经济关怀、安全关怀次之；分布反映关注面，具体每条仍需辅导员人工核实后再判断。',
    kind: 'category',
    source: '汇总统计 · 线索类别分布（categoryDist）',
  },
]);

/** 关键词 → 回答类型的简单意图识别（演示用，纯前端 Mock） */
function detectKind(q: string): AnswerKind {
  const s = q.toLowerCase();
  if (s.includes('学院') || s.includes('对比') || s.includes('院系')) return 'college';
  if (s.includes('类别') || s.includes('分类') || s.includes('种类') || s.includes('分布'))
    return 'category';
  if (
    s.includes('趋势') ||
    s.includes('闭环') ||
    s.includes('近7') ||
    s.includes('近 7') ||
    s.includes('走势')
  )
    return 'trend';
  if (s.includes('噪声') || s.includes('误报') || s.includes('原因')) return 'noise';
  // 默认给类别分布
  return 'category';
}

function conclusionOf(kind: AnswerKind): { conclusion: string; source: string } {
  switch (kind) {
    case 'college':
      return {
        conclusion:
          '本周共 6 个学院产生新增线索，计算机科学与技术学院最多（23 条），医学部、商学院次之；线索数高不等于问题多，建议结合学院规模与闭环率综合研判。',
        source: '汇总统计 · 各学院周度报表（CollegeStat）',
      };
    case 'category':
      return {
        conclusion:
          '当前线索以学业关怀为主（约 45%），经济关怀、安全关怀次之；分布反映关注面，每条线索仍需人工核实后再做判断。',
        source: '汇总统计 · 线索类别分布（categoryDist）',
      };
    case 'trend':
      return {
        conclusion:
          '近 7 日新增与已闭环线索均呈温和上行，闭环量稳定跟随新增（保持约 88% 以上），整体处置节奏可控，无明显积压。',
        source: '汇总统计 · 近 7 日态势（trend7d）',
      };
    case 'noise':
      return {
        conclusion:
          '近期噪声主要来自「临近假期正常波动」（32 起）与「校外就餐/借卡」（24 起）；建议据此优化白名单与场景排除规则，降低误扰。',
        source: '汇总统计 · 噪声原因分布（noiseReasons）',
      };
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

function ask(q: string, kind?: AnswerKind) {
  const question = q.trim();
  if (!question) {
    ElMessage.warning('请输入要查询的问题');
    return;
  }
  const k = kind ?? detectKind(question);
  const { conclusion, source } = conclusionOf(k);
  qaList.value.push({ id: ++seq, question, conclusion, kind: k, source });
  input.value = '';
  ElMessage.success(
    '问数完成 · 仅返回汇总统计，不下钻个人明细；本次查询已留痕（操作人/时间/IP/traceId）',
  );
  scrollToBottom();
}

function onSample(s: { text: string; kind: AnswerKind }) {
  ask(s.text, s.kind);
}

function onSend() {
  ask(input.value);
}

// 图表数据（来自 reports 汇总统计）
const collegeCats = collegeStats.map((c) => c.college.replace(/学院|学部$/, ''));
const collegeSeries = [{ name: '新增线索', data: collegeStats.map((c) => c.newClues) }];
const trendDates = trend7d.dates;
const trendSeries = [
  { name: '新增线索', data: trend7d.newClues },
  { name: '已闭环', data: trend7d.closed, color: '#2f6db0' },
];
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="自然语言问数"
      subtitle="用日常语言查询关怀态势 · 仅回答汇总统计，不下钻个人明细，不做自动决策"
    >
      <template #extra>
        <el-tag type="success" effect="plain" round>
          <el-icon><DataAnalysis /></el-icon> 当前身份：{{ auth.roleMeta.name }}
        </el-tag>
      </template>
    </PageHeader>

    <!-- 合规提示条 -->
    <el-alert type="info" :closable="false" show-icon class="banner" title="问数边界说明">
      <template #default>
        本功能面向学工/领导，仅返回<strong> 汇总统计与态势 </strong
        >，<strong>不展示任何学生个人明细</strong>；结果为辅助参考，<strong>需人工核实</strong>，不作自动决策依据。
      </template>
    </el-alert>

    <!-- 示例问题 chips -->
    <div class="ss-card pad chips-card">
      <div class="chips-title">
        <el-icon><MagicStick /></el-icon> 试试这样问
      </div>
      <div class="chips">
        <el-tag
          v-for="s in samples"
          :key="s.text"
          class="chip"
          effect="plain"
          round
          size="large"
          @click="onSample(s)"
        >
          {{ s.text }}
        </el-tag>
      </div>
    </div>

    <!-- 问答列表 -->
    <div ref="listRef" class="qa-list">
      <div v-for="qa in qaList" :key="qa.id" class="qa-block">
        <!-- 提问气泡 -->
        <div class="ask-row">
          <div class="ask-bubble">{{ qa.question }}</div>
          <div class="avatar avatar-user">
            <el-icon><User /></el-icon>
          </div>
        </div>

        <!-- 回答卡片 -->
        <div class="answer-row">
          <div class="avatar avatar-bot">
            <el-icon><DataLine /></el-icon>
          </div>
          <div class="ss-card answer-card">
            <p class="conclusion">{{ qa.conclusion }}</p>

            <div class="chart-wrap">
              <BarChart
                v-if="qa.kind === 'college'"
                :categories="collegeCats"
                :series="collegeSeries"
                height="260px"
              />
              <PieChart
                v-else-if="qa.kind === 'category'"
                :data="categoryDist"
                :colors="['#3b7cff', '#16a394', '#e5523f']"
                height="260px"
              />
              <LineChart
                v-else-if="qa.kind === 'trend'"
                :dates="trendDates"
                :series="trendSeries"
                height="260px"
              />
              <BarChart
                v-else
                :categories="noiseReasons.map((n) => n.name)"
                :series="[
                  { name: '出现次数', data: noiseReasons.map((n) => n.value), color: '#e6a23c' },
                ]"
                :horizontal="true"
                height="260px"
              />
            </div>

            <div class="answer-foot">
              <span class="src"
                ><el-icon><Files /></el-icon> 数据来源：{{ qa.source }}</span
              >
              <el-tag type="warning" effect="plain" size="small" round
                >结果需人工核实，仅供参考</el-tag
              >
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="ss-card pad input-bar">
      <el-input
        v-model="input"
        size="large"
        placeholder="例如：本月安全关怀线索的闭环率如何？（仅统计态势，不查个人）"
        clearable
        @keyup.enter="onSend"
      >
        <template #prefix>
          <el-icon><ChatLineRound /></el-icon>
        </template>
      </el-input>
      <el-button type="primary" size="large" :icon="'Promotion'" @click="onSend">发送</el-button>
    </div>
    <p class="foot-tip">
      观澜问数基于汇总统计回答，为辅助洞察工具；如需个案信息，请前往「线索」按授权核实，所有查询全程留痕。
    </p>
  </div>
</template>

<style scoped>
.pad {
  padding: 16px 18px;
}
.banner {
  margin-bottom: 16px;
}
.chips-card {
  margin-bottom: 16px;
}
.chips-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ss-text-muted);
  margin-bottom: 12px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.chip {
  cursor: pointer;
  transition: all 0.15s;
}
.chip:hover {
  color: var(--ss-primary);
  border-color: var(--ss-primary);
  transform: translateY(-1px);
}
.qa-list {
  max-height: 56vh;
  overflow-y: auto;
  padding: 4px 2px;
}
.qa-block {
  margin-bottom: 18px;
}
/* 提问 */
.ask-row {
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 12px;
}
.ask-bubble {
  max-width: 70%;
  background: var(--ss-primary);
  color: #fff;
  padding: 10px 14px;
  border-radius: 12px 12px 2px 12px;
  font-size: 14px;
  line-height: 1.5;
  box-shadow: var(--ss-shadow);
}
/* 回答 */
.answer-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.answer-card {
  flex: 1;
  padding: 14px 18px;
  border-radius: 2px 12px 12px 12px;
}
.conclusion {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ss-text);
}
.chart-wrap {
  background: var(--ss-primary-soft);
  border-radius: 8px;
  padding: 8px 6px 4px;
}
.answer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--ss-border);
}
.src {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--ss-text-muted);
}
/* 头像 */
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
}
.avatar-user {
  background: var(--ss-primary-soft);
  color: var(--ss-primary);
}
.avatar-bot {
  background: var(--ss-accent);
  color: #fff;
}
/* 输入区 */
.input-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 10px 2px 0;
}
</style>
