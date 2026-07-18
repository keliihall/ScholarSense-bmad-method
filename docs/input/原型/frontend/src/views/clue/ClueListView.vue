<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import CareLevelTag from '@/components/CareLevelTag.vue';
import ClueStatusTag from '@/components/ClueStatusTag.vue';
import EmptyState from '@/components/EmptyState.vue';
import { clues } from '@/mock';
import { CLUE_CATEGORY, CLUE_STATUS, CARE_LEVEL, SCENES } from '@/utils/constants';
import { deadlineHint, priorityOf } from '@/utils/format';
import type { Clue, ClueCategory, CareLevel, ClueStatus } from '@/types';

const router = useRouter();

// 按状态计数（用于顶部状态概览）
const statusCounts = computed(() => {
  const map: Record<string, number> = {};
  (Object.keys(CLUE_STATUS) as ClueStatus[]).forEach((k) => {
    map[k] = clues.filter((c) => c.status === k).length;
  });
  return map;
});

// 筛选条件
const filters = reactive<{
  category: ClueCategory | '';
  level: CareLevel | '';
  status: ClueStatus | '';
  scene: string;
  keyword: string;
}>({
  category: '',
  level: '',
  status: '',
  scene: '',
  keyword: '',
});

const categoryKeys = Object.keys(CLUE_CATEGORY) as ClueCategory[];
const levelKeys = Object.keys(CARE_LEVEL) as CareLevel[];
const statusKeys = Object.keys(CLUE_STATUS) as ClueStatus[];

const filtered = computed(() =>
  clues.filter((c) => {
    if (filters.category && c.category !== filters.category) return false;
    if (filters.level && c.level !== filters.level) return false;
    if (filters.status && c.status !== filters.status) return false;
    if (filters.scene && c.scene !== filters.scene) return false;
    if (filters.keyword && !c.studentName.includes(filters.keyword.trim())) return false;
    return true;
  }),
);

function resetFilters() {
  filters.category = '';
  filters.level = '';
  filters.status = '';
  filters.scene = '';
  filters.keyword = '';
}

function openClue(c: Clue) {
  router.push(`/clues/${c.id}`);
}

function filterByStatus(s: ClueStatus) {
  filters.status = filters.status === s ? '' : s;
}

function exportList() {
  ElMessage.success(
    `已导出 ${filtered.value.length} 条线索（仅含授权可见字段）。操作已留痕：操作人 / 时间 / 对象 / 来源IP / traceId`,
  );
}

const priorityTag = (s: number) => (s >= 85 ? 'danger' : s >= 70 ? 'warning' : 'info');
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="线索列表"
      subtitle="每条均为「需人工核实的线索」，非系统结论；请核实后完成关怀闭环"
    >
      <template #extra>
        <el-button :icon="'Download'" plain @click="exportList">导出</el-button>
      </template>
    </PageHeader>

    <!-- 按状态统计概览 -->
    <div class="ss-card status-bar">
      <div class="sb-total">
        <span class="sb-num">{{ clues.length }}</span>
        <span class="sb-label">线索总数</span>
      </div>
      <el-divider direction="vertical" class="sb-div" />
      <div class="sb-tags">
        <div
          v-for="k in statusKeys"
          :key="k"
          class="sb-pill ss-hover"
          :class="{ active: filters.status === k }"
          @click="filterByStatus(k)"
        >
          <ClueStatusTag :status="k" />
          <span class="sb-count">{{ statusCounts[k] }}</span>
        </div>
      </div>
    </div>

    <!-- 筛选行 -->
    <div class="ss-card filter-bar">
      <el-select v-model="filters.category" placeholder="全部类别" clearable class="f-item">
        <el-option v-for="k in categoryKeys" :key="k" :label="CLUE_CATEGORY[k].label" :value="k" />
      </el-select>
      <el-select v-model="filters.level" placeholder="全部关怀等级" clearable class="f-item">
        <el-option v-for="k in levelKeys" :key="k" :label="CARE_LEVEL[k].label" :value="k" />
      </el-select>
      <el-select v-model="filters.status" placeholder="全部状态" clearable class="f-item">
        <el-option v-for="k in statusKeys" :key="k" :label="CLUE_STATUS[k].label" :value="k" />
      </el-select>
      <el-select v-model="filters.scene" placeholder="全部场景" clearable class="f-item f-wide">
        <el-option v-for="s in SCENES" :key="s" :label="s" :value="s" />
      </el-select>
      <el-input
        v-model="filters.keyword"
        placeholder="学生姓名关键字"
        clearable
        class="f-item f-wide"
        :prefix-icon="'Search'"
      />
      <el-button :icon="'RefreshLeft'" @click="resetFilters">重置</el-button>
      <span class="f-result ss-muted">共 {{ filtered.length }} 条</span>
    </div>

    <!-- 线索表格 -->
    <div class="ss-card table-card">
      <el-table
        v-if="filtered.length"
        :data="filtered"
        style="width: 100%"
        row-class-name="clickable"
        @row-click="(row: any) => openClue(row as Clue)"
      >
        <el-table-column label="线索ID" width="130" prop="id">
          <template #default="{ row }">
            <span class="clue-id">{{ (row as Clue).id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="学生" width="100">
          <template #default="{ row }">
            <span class="stu">{{ (row as Clue).studentName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="学院" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="ss-muted">{{ (row as Clue).college }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类别" width="118">
          <template #default="{ row }">
            <span class="cat" :style="{ color: CLUE_CATEGORY[(row as Clue).category].color }">
              <el-icon><component :is="CLUE_CATEGORY[(row as Clue).category].icon" /></el-icon>
              {{ CLUE_CATEGORY[(row as Clue).category].label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="关怀等级" width="132">
          <template #default="{ row }"><CareLevelTag :level="(row as Clue).level" /></template>
        </el-table-column>
        <el-table-column label="评分 / 优先级" width="130">
          <template #default="{ row }">
            <el-tag
              :type="priorityTag((row as Clue).score) as any"
              effect="dark"
              round
              size="small"
            >
              {{ priorityOf((row as Clue).score) }} · {{ (row as Clue).score }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="独立线索" width="90" align="center">
          <template #default="{ row }">
            <el-badge :value="(row as Clue).independentCount" type="primary" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }"><ClueStatusTag :status="(row as Clue).status" /></template>
        </el-table-column>
        <el-table-column label="时效" width="116">
          <template #default="{ row }">
            <span :class="{ over: deadlineHint((row as Clue).deadline).overdue }">
              {{ deadlineHint((row as Clue).deadline).text }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openClue(row as Clue)">去核实</el-button>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState v-else text="没有符合条件的线索，请调整筛选条件" icon="Search" />
    </div>

    <p class="page-foot ss-muted">
      列表仅展示授权范围内、且当前角色可见的线索；高敏字段在详情页按授权脱敏显示。
    </p>
  </div>
</template>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  margin-bottom: 16px;
}
.sb-total {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 78px;
}
.sb-num {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--ss-primary);
}
.sb-label {
  font-size: 12px;
  color: var(--ss-text-muted);
}
.sb-div {
  height: 38px;
}
.sb-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.sb-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid var(--ss-border);
  border-radius: 999px;
  cursor: pointer;
  background: #fff;
}
.sb-pill.active {
  border-color: var(--ss-primary);
  background: var(--ss-primary-soft);
}
.sb-count {
  font-weight: 700;
  font-size: 14px;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  margin-bottom: 16px;
}
.f-item {
  width: 150px;
}
.f-wide {
  width: 180px;
}
.f-result {
  margin-left: auto;
  font-size: 13px;
}
.table-card {
  padding: 6px 8px;
}
.clue-id {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px;
  color: var(--ss-text-muted);
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
.page-foot {
  font-size: 12px;
  margin: 14px 2px 0;
}
:deep(.clickable) {
  cursor: pointer;
}
</style>
