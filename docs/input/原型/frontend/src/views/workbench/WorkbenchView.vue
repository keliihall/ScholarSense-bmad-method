<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import CareLevelTag from '@/components/CareLevelTag.vue';
import ClueStatusTag from '@/components/ClueStatusTag.vue';
import GaugeChart from '@/components/charts/GaugeChart.vue';
import { clues, workbenchStats, findStudent } from '@/mock';
import { CLUE_CATEGORY, IDENTITY_TAG } from '@/utils/constants';
import { deadlineHint, priorityOf } from '@/utils/format';
import type { Clue, ClueStatus, IdentityTag } from '@/types';

const router = useRouter();

const ACTIVE: ClueStatus[] = ['pending', 'processing', 'overdue'];

// Top-k：未关闭线索按评分降序（关怀等级 + 独立线索数 + 时效 + 可信度的综合分）
const topk = computed(() =>
  [...clues].filter((c) => ACTIVE.includes(c.status)).sort((a, b) => b.score - a.score),
);

// 高风险叠加：近 7 日同一学生「安全预警 + 学业/经济」多类叠加，置顶优先（老师方案 三·2）
const highRisk = computed(() => {
  const map = new Map<string, Clue[]>();
  clues
    .filter((c) => ACTIVE.includes(c.status))
    .forEach((c) => {
      const arr = map.get(c.studentId) ?? [];
      arr.push(c);
      map.set(c.studentId, arr);
    });
  const res: { studentId: string; name: string; clues: Clue[] }[] = [];
  map.forEach((cs, sid) => {
    const cats = new Set(cs.map((c) => c.category));
    if (cats.has('safety') && (cats.has('academic') || cats.has('economic'))) {
      res.push({ studentId: sid, name: cs[0].studentName, clues: cs });
    }
  });
  return res;
});

function stuIdentity(sid: string): IdentityTag {
  return findStudent(sid)?.identityTag ?? '普通';
}

const capacityPct = computed(() =>
  Math.round((workbenchStats.todayDone / workbenchStats.todayCapacity) * 100),
);

function openClue(c: Clue) {
  router.push(`/clues/${c.id}`);
}

const priorityTag = (s: number) => (s >= 85 ? 'danger' : s >= 70 ? 'warning' : 'info');
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="辅导员工作台"
      subtitle="按 Top-k 优先级核实线索 · 每条均为需人工核实事项，由您完成关怀闭环"
    >
      <template #extra>
        <el-button :icon="'Refresh'" plain>刷新</el-button>
        <el-button type="primary" :icon="'Checked'" @click="router.push('/clues')"
          >查看全部线索</el-button
        >
      </template>
    </PageHeader>

    <!-- 统计卡 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard
          title="待核实"
          :value="workbenchStats.pending"
          unit="条"
          icon="Bell"
          color="#e5523f"
          hint="需尽快处理"
      /></el-col>
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard
          title="处理中"
          :value="workbenchStats.processing"
          unit="条"
          icon="Loading"
          color="#e6a23c"
      /></el-col>
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard
          title="今日已跟进"
          :value="workbenchStats.followedToday"
          unit="条"
          icon="CircleCheck"
          color="#1f9d72"
      /></el-col>
      <el-col :xs="12" :sm="12" :md="6"
        ><StatCard
          title="本院超期"
          :value="workbenchStats.overdue"
          unit="条"
          icon="Warning"
          color="#909399"
          hint="需关注督办"
      /></el-col>
    </el-row>

    <!-- 高风险叠加 · 置顶 -->
    <div v-if="highRisk.length" class="ss-card hr-card mt16">
      <div class="hr-head">
        <span class="hr-title"
          ><el-icon><WarningFilled /></el-icon> 高风险叠加 · 置顶关注</span
        >
        <span class="hr-sub"
          >近 7 日「安全预警 + 学业/经济」多类叠加，建议优先核实（仍需人工研判，非自动定性）</span
        >
      </div>
      <div class="hr-list">
        <div v-for="h in highRisk" :key="h.studentId" class="hr-item">
          <div class="hr-stu">
            <span class="hr-name">{{ h.name }}</span>
            <el-tag
              size="small"
              :type="IDENTITY_TAG[stuIdentity(h.studentId)].tag as any"
              effect="plain"
              >{{ stuIdentity(h.studentId) }}</el-tag
            >
          </div>
          <div class="hr-clues">
            <button
              v-for="c in h.clues"
              :key="c.id"
              class="hr-chip"
              type="button"
              :style="{ color: CLUE_CATEGORY[c.category].color }"
              @click="openClue(c)"
            >
              {{ CLUE_CATEGORY[c.category].label }}
              <CareLevelTag :level="c.level" />
            </button>
          </div>
          <el-button link type="danger" :icon="'Right'" @click="openClue(h.clues[0])"
            >立即核实</el-button
          >
        </div>
      </div>
    </div>

    <el-row :gutter="16" class="mt16">
      <!-- Top-k 列表 -->
      <el-col :xs="24" :md="17">
        <div class="ss-card pad">
          <div class="ss-between mb12">
            <div class="ss-section-title" style="margin: 0">待核实 Top-k 优先级</div>
            <span class="ss-muted small">综合 关怀等级 · 独立线索数 · 时效 · 数据可信度</span>
          </div>
          <el-table
            :data="topk"
            style="width: 100%"
            @row-click="openClue"
            row-class-name="clickable"
          >
            <el-table-column label="优先级" width="92">
              <template #default="{ row }">
                <el-tag :type="priorityTag(row.score)" effect="dark" round size="small"
                  >{{ priorityOf(row.score) }} · {{ row.score }}</el-tag
                >
              </template>
            </el-table-column>
            <el-table-column label="学生" width="110">
              <template #default="{ row }">
                <span class="stu">{{ row.studentName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类别" width="110">
              <template #default="{ row }">
                <span
                  class="cat"
                  :style="{
                    color: CLUE_CATEGORY[row.category as keyof typeof CLUE_CATEGORY].color,
                  }"
                >
                  <el-icon
                    ><component
                      :is="CLUE_CATEGORY[row.category as keyof typeof CLUE_CATEGORY].icon"
                  /></el-icon>
                  {{ CLUE_CATEGORY[row.category as keyof typeof CLUE_CATEGORY].label }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="关怀等级" width="130">
              <template #default="{ row }"><CareLevelTag :level="row.level" /></template>
            </el-table-column>
            <el-table-column label="独立线索" width="92" align="center">
              <template #default="{ row }"
                ><el-badge :value="row.independentCount" type="primary"
              /></template>
            </el-table-column>
            <el-table-column label="时效" width="110">
              <template #default="{ row }">
                <span :class="{ over: deadlineHint(row.deadline).overdue }">{{
                  deadlineHint(row.deadline).text
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="92">
              <template #default="{ row }"><ClueStatusTag :status="row.status" /></template>
            </el-table-column>
            <el-table-column label="操作" min-width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="openClue(row)">去核实</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>

      <!-- 今日核实容量 + 提示 -->
      <el-col :xs="24" :md="7">
        <div class="ss-card pad">
          <div class="ss-section-title" style="margin: 0 0 4px">今日核实容量</div>
          <GaugeChart :value="capacityPct" label="已完成" color="#1b8f7a" height="190px" />
          <div class="cap-text">
            已核实 <b>{{ workbenchStats.todayDone }}</b> / 建议容量
            <b>{{ workbenchStats.todayCapacity }}</b> 条
          </div>
          <el-divider />
          <div class="ss-section-title" style="margin: 0 0 8px">关怀提示</div>
          <el-alert type="success" :closable="false" show-icon title="线索而非结论">
            <template #default
              >系统输出为需人工核实的线索，请结合实际谈心了解，避免标签化。</template
            >
          </el-alert>
          <el-alert class="mt8" type="warning" :closable="false" show-icon title="时限优先">
            <template #default>Ⅲ级 24h、Ⅱ级 48h 内完成核实；超期将进入督办。</template>
          </el-alert>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
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
.small {
  font-size: 12px;
}
.stu {
  font-weight: 600;
}
.cat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}
.over {
  color: #e5523f;
  font-weight: 600;
}
.cap-text {
  text-align: center;
  color: var(--ss-text-muted);
  font-size: 13px;
  margin-top: -6px;
}
:deep(.clickable) {
  cursor: pointer;
}
.hr-card {
  padding: 14px 18px;
  border: 1px solid #f3c9c2;
  background: linear-gradient(180deg, #fdf1ef, #fff);
}
.hr-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.hr-title {
  font-weight: 700;
  color: var(--ss-level-3);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.hr-sub {
  font-size: 12px;
  color: var(--ss-text-muted);
}
.hr-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.hr-item {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  background: #fff;
  border: 1px solid var(--ss-border);
  border-left: 3px solid var(--ss-level-3);
  border-radius: 8px;
  padding: 10px 14px;
}
.hr-stu {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 130px;
}
.hr-name {
  font-weight: 700;
}
.hr-clues {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  flex: 1;
}
.hr-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  background: var(--ss-bg);
  border: 1px solid var(--ss-border);
  border-radius: 8px;
  padding: 4px 10px;
  cursor: pointer;
}
.hr-chip:hover {
  border-color: var(--ss-level-3);
}
</style>
