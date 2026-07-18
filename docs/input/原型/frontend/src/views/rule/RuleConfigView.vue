<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import { rules } from '@/mock';
import {
  CLUE_CATEGORY,
  CARE_LEVEL,
  CARE_LEVEL_ACTION,
  IDENTITY_TAG,
  PUSH_FREQ,
} from '@/utils/constants';
import type { CareLevel, ClueCategory, IdentityTag, Rule } from '@/types';

type RuleStatus = Rule['status'];

const STATUS_TAG: Record<RuleStatus, string> = {
  生产: 'success',
  灰度: 'warning',
  草稿: 'info',
  熔断: 'danger',
};

const levels = ['I', 'II', 'III'] as CareLevel[];
const identities = ['普通', '一般关注', '重点关注'] as IdentityTag[];
const pushCats = ['safety', 'academic', 'economic'] as ClueCategory[];

const stat = computed(() => ({
  total: rules.length,
  prod: rules.filter((r) => r.status === '生产').length,
  gray: rules.filter((r) => r.status === '灰度').length,
  fused: rules.filter((r) => r.status === '熔断').length,
}));

// 指标筛选
const filterCat = ref<'all' | ClueCategory>('all');
const filtered = computed(() =>
  filterCat.value === 'all' ? rules : rules.filter((r) => r.indicator === filterCat.value),
);

// 查看弹窗（只读）
const dialogVisible = ref(false);
const current = ref<Rule | null>(null);
function openView(r: Rule) {
  current.value = r;
  dialogVisible.value = true;
}

function toggleStage(r: Rule) {
  if (r.status === '熔断') {
    ElMessage.warning('该规则处于熔断保护中，需先恢复数据质量方可发布');
    return;
  }
  const next = r.status === '生产' ? '灰度' : '生产';
  ElMessage.success(
    `规则 #${r.seq} 已申请「${r.status} → ${next}」状态变更（需人工复核、非自动决策）· 操作已留痕`,
  );
}

function onCreate() {
  ElMessage.info('新增规则将进入草稿，经复核后灰度试跑再发布生产 · 操作已留痕');
}

function rowClass({ row }: { row: Rule }): string {
  return row.status === '熔断' ? 'fused-row' : '';
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="规则配置"
      subtitle="对齐《风险预警与干预设计方案》规则宽表 · 可解释 / 可计算 / 可熔断 · 修改全程留痕"
    >
      <template #extra>
        <el-button :icon="'Document'" plain @click="ElMessage.info('规则变更记录已写入审计日志')"
          >变更记录</el-button
        >
        <el-button type="primary" :icon="'Plus'" @click="onCreate">新增规则</el-button>
      </template>
    </PageHeader>

    <!-- 评分模型 -->
    <div class="ss-card model mb16">
      <div class="ss-section-title" style="margin: 0 0 14px">风险评分模型</div>
      <div class="model-grid">
        <div class="m-block formula">
          <div class="m-label">计算公式</div>
          <div class="m-formula">
            <span class="f1">风险行为得分</span><span class="op">×</span>
            <span class="f2">身份加权系数</span><span class="op">=</span>
            <span class="f3">预警得分</span>
          </div>
          <div class="m-note">
            命中规则仅生成「需人工核实的线索」，经辅导员初审方可入库——非自动决策。
          </div>
        </div>

        <div class="m-block">
          <div class="m-label">身份加权系数</div>
          <div class="chips">
            <span
              v-for="id in identities"
              :key="id"
              class="chip"
              :style="{
                color: IDENTITY_TAG[id].color,
                borderColor: IDENTITY_TAG[id].color + '55',
                background: IDENTITY_TAG[id].color + '12',
              }"
            >
              {{ IDENTITY_TAG[id].label }} ×{{ IDENTITY_TAG[id].weight }}
            </span>
          </div>
          <div class="m-note">经济类全体统一标准、不赋系数。</div>
        </div>

        <div class="m-block">
          <div class="m-label">预警分级阈值</div>
          <div class="levels">
            <div v-for="lv in levels" :key="lv" class="lv-row">
              <el-tag :type="CARE_LEVEL[lv].tag as any" effect="dark" round size="small"
                >{{ CARE_LEVEL[lv].short }} 级</el-tag
              >
              <span class="lv-th">≥ {{ CARE_LEVEL_ACTION[lv].threshold }} 分</span>
              <span class="lv-act">{{ CARE_LEVEL_ACTION[lv].action }}</span>
            </div>
          </div>
        </div>

        <div class="m-block">
          <div class="m-label">差异化推送频率</div>
          <div class="pushes">
            <div v-for="c in pushCats" :key="c" class="push-row">
              <span class="push-cat" :style="{ color: CLUE_CATEGORY[c].color }">{{
                CLUE_CATEGORY[c].label
              }}</span>
              <span class="push-freq">{{ PUSH_FREQ[c].push }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 统计卡 -->
    <el-row :gutter="16" class="mb16">
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard title="规则总数" :value="stat.total" unit="条" icon="Files" color="#1b8f7a"
      /></el-col>
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard title="生产运行" :value="stat.prod" unit="条" icon="CircleCheck" color="#1f9d72"
      /></el-col>
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard
          title="灰度试跑"
          :value="stat.gray"
          unit="条"
          icon="MagicStick"
          color="#e6a23c"
          hint="仅观察不产线索"
      /></el-col>
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard
          title="已熔断"
          :value="stat.fused"
          unit="条"
          icon="SwitchButton"
          color="#909399"
          hint="数据质量保护"
      /></el-col>
    </el-row>

    <!-- 规则宽表 -->
    <div class="ss-card pad">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">规则宽表（共 {{ rules.length }} 条）</div>
        <el-radio-group v-model="filterCat" size="small">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button label="academic">学业</el-radio-button>
          <el-radio-button label="economic">经济</el-radio-button>
          <el-radio-button label="safety">安全</el-radio-button>
        </el-radio-group>
      </div>
      <el-table :data="filtered" style="width: 100%" :row-class-name="rowClass" border>
        <el-table-column label="序号" width="64" align="center">
          <template #default="{ row }">{{ (row as Rule).seq }}</template>
        </el-table-column>
        <el-table-column label="指标" width="112">
          <template #default="{ row }">
            <span class="cat" :style="{ color: CLUE_CATEGORY[(row as Rule).indicator].color }">
              <el-icon><component :is="CLUE_CATEGORY[(row as Rule).indicator].icon" /></el-icon>
              {{ CLUE_CATEGORY[(row as Rule).indicator].label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="行为类别" prop="behaviorType" width="100" />
        <el-table-column label="判定规则" prop="rule" min-width="300" show-overflow-tooltip />
        <el-table-column label="赋分" prop="score" min-width="150" show-overflow-tooltip />
        <el-table-column label="赋系数" width="124">
          <template #default="{ row }">
            <el-tag v-if="(row as Rule).weighted" size="small" effect="plain" type="warning"
              >按身份 ×1/1.5/2</el-tag
            >
            <el-tag v-else size="small" effect="plain" type="info">全体统一</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="推送时机 / 备注"
          prop="pushTiming"
          min-width="190"
          show-overflow-tooltip
        />
        <el-table-column label="状态" width="84">
          <template #default="{ row }">
            <el-tag
              :type="STATUS_TAG[(row as Rule).status] as any"
              effect="dark"
              round
              size="small"
              >{{ (row as Rule).status }}</el-tag
            >
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openView(row as Rule)">查看</el-button>
            <el-button
              link
              type="warning"
              :disabled="(row as Rule).status === '熔断'"
              @click="toggleStage(row as Rule)"
              >{{ (row as Rule).status === '生产' ? '转灰度' : '转生产' }}</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <p class="foot-tip">
        命中规则 → 仅生成需人工核实的线索 → 辅导员初审入库 →
        关怀闭环；规则不直接对学生作出任何处置或定性。
      </p>
    </div>

    <!-- 查看弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="current ? `规则 #${current.seq} · ${current.behaviorType}` : '规则详情'"
      width="640px"
      top="8vh"
    >
      <el-descriptions v-if="current" :column="1" border size="small">
        <el-descriptions-item label="风险预警指标">{{
          CLUE_CATEGORY[current.indicator].label
        }}</el-descriptions-item>
        <el-descriptions-item label="风险行为类别">{{ current.behaviorType }}</el-descriptions-item>
        <el-descriptions-item label="判定规则">{{ current.rule }}</el-descriptions-item>
        <el-descriptions-item label="赋分规则"
          >{{ current.score }}（行为间不叠加计算）</el-descriptions-item
        >
        <el-descriptions-item label="赋系数规则">{{
          current.weighted
            ? '按身份加权：普通 ×1 / 一般关注 ×1.5 / 重点关注 ×2'
            : '全体学生统一标准，不赋系数'
        }}</el-descriptions-item>
        <el-descriptions-item label="推送时机 / 备注">{{
          current.pushTiming
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ current.status }}</el-descriptions-item>
      </el-descriptions>
      <p class="foot-tip">本规则仅用于生成需人工核实的线索；命中后由辅导员初审，非自动处置。</p>
      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pad {
  padding: 16px 18px;
}
.mb16 {
  margin-bottom: 16px;
}
.mb12 {
  margin-bottom: 12px;
}
.model {
  padding: 16px 18px;
}
.model-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr 1.2fr 1.2fr;
  gap: 16px;
}
.m-block {
  border-left: 1px solid var(--ss-border);
  padding-left: 16px;
}
.m-block:first-child {
  border-left: none;
  padding-left: 0;
}
.m-label {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin-bottom: 10px;
}
.m-formula {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-weight: 700;
  font-size: 15px;
}
.f1 {
  color: var(--ss-accent);
}
.f2 {
  color: #e6a23c;
}
.f3 {
  color: var(--ss-primary-dark);
}
.op {
  color: var(--ss-text-muted);
  font-weight: 400;
}
.m-note {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin-top: 10px;
  line-height: 1.6;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  font-size: 13px;
  font-weight: 600;
  padding: 4px 10px;
  border: 1px solid;
  border-radius: 14px;
}
.levels,
.pushes {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.lv-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.lv-th {
  font-weight: 600;
  color: var(--ss-text);
}
.lv-act {
  color: var(--ss-text-muted);
  font-size: 12px;
}
.push-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.push-cat {
  font-weight: 600;
  width: 64px;
  flex-shrink: 0;
}
.push-freq {
  color: var(--ss-text-muted);
}
.cat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 12px 0 0;
  line-height: 1.6;
}
:deep(.fused-row) {
  background: #fbeeec;
}
:deep(.fused-row:hover > td) {
  background: #f7e2de !important;
}
</style>
