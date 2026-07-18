<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import CareLevelTag from '@/components/CareLevelTag.vue';
import ClueStatusTag from '@/components/ClueStatusTag.vue';
import SensitiveField from '@/components/SensitiveField.vue';
import EmptyState from '@/components/EmptyState.vue';
import { students, clues } from '@/mock';
import { CLUE_CATEGORY, IDENTITY_TAG } from '@/utils/constants';
import type { Clue, Student } from '@/types';

const route = useRoute();
const router = useRouter();

/** 当前选中学生：优先取路由参数 id，无参数时默认第一个 */
const current = computed<Student | undefined>(() => {
  const id = route.params.id ? String(route.params.id) : '';
  return students.find((s) => s.id === id) ?? students[0];
});

/** 切换列表项：更新路由 path（带 id），不重复跳转 */
function selectStudent(s: Student) {
  if (s.id !== current.value?.id) {
    router.push(`/students/${s.id}`);
  }
}

/** 三维信号配置（数值越高越需关注） */
const signalDims = computed(() => {
  const s = current.value;
  if (!s) return [];
  return [
    {
      key: 'academic',
      label: '学业信号',
      value: s.signals.academic,
      color: CLUE_CATEGORY.academic.color,
    },
    {
      key: 'economic',
      label: '经济信号',
      value: s.signals.economic,
      color: CLUE_CATEGORY.economic.color,
    },
    {
      key: 'safety',
      label: '安全信号',
      value: s.signals.safety,
      color: CLUE_CATEGORY.safety.color,
    },
  ];
});

/** 信号强度文案（仅描述变化幅度，非结论） */
function signalLevel(v: number): string {
  if (v >= 70) return '需重点关注';
  if (v >= 50) return '值得留意';
  return '平稳';
}

/** 当前学生的历史线索 */
const studentClues = computed<Clue[]>(() =>
  current.value ? clues.filter((c) => c.studentId === current.value!.id) : [],
);

/** 列表项的活跃线索数（用于学生列表角标） */
function activeClueCount(s: Student): number {
  return clues.filter(
    (c) =>
      c.studentId === s.id &&
      (c.status === 'pending' || c.status === 'processing' || c.status === 'overdue'),
  ).length;
}

function openClue(c: Clue) {
  router.push(`/clues/${c.id}`);
}

function exportProfile() {
  ElMessage.success(
    '一人一档导出申请已提交，操作已留痕（操作人 / 时间 / 对象 / 来源 IP / traceId）',
  );
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="一人一档"
      subtitle="学生成长关怀档案 · 辅导员仅可见本人责任学生 · 信号为需人工核实的线索，非结论"
    >
      <template #extra>
        <el-button :icon="'Download'" plain @click="exportProfile">导出档案</el-button>
        <el-button
          v-if="current"
          type="primary"
          :icon="'Tickets'"
          @click="router.push(`/clues/${studentClues[0]?.id ?? ''}`)"
          :disabled="!studentClues.length"
        >
          查看最新线索
        </el-button>
      </template>
    </PageHeader>

    <el-alert type="info" :closable="false" show-icon class="mb16" title="授权可见而非全员可查">
      <template #default>
        档案用于关怀闭环，非监控。手机号 / 学号 / 心理关注等高敏字段按角色授权显示，访问全程留痕。
      </template>
    </el-alert>

    <el-row :gutter="16">
      <!-- 左侧：责任学生列表 -->
      <el-col :xs="24" :md="7" :lg="6">
        <div class="ss-card pad">
          <div class="ss-between mb12">
            <div class="ss-section-title" style="margin: 0">责任学生</div>
            <el-tag type="info" effect="plain" size="small" round
              >共 {{ students.length }} 人</el-tag
            >
          </div>
          <ul class="stu-list">
            <li
              v-for="s in students"
              :key="s.id"
              class="stu-item ss-hover"
              :class="{ active: s.id === current?.id }"
              @click="selectStudent(s)"
            >
              <div class="avatar">{{ s.name.slice(0, 1) }}</div>
              <div class="stu-meta">
                <div class="stu-name">
                  {{ s.name }}
                  <span class="stu-gender">{{ s.gender }}</span>
                </div>
                <div class="stu-sub ss-muted">{{ s.major }} · {{ s.grade }}</div>
              </div>
              <el-badge
                v-if="activeClueCount(s)"
                :value="activeClueCount(s)"
                type="danger"
                class="stu-badge"
              />
            </li>
          </ul>
        </div>
      </el-col>

      <!-- 右侧：选中学生档案 -->
      <el-col :xs="24" :md="17" :lg="18">
        <template v-if="current">
          <!-- 基本信息 -->
          <div class="ss-card pad mb16">
            <div class="ss-section-title">基本信息</div>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="姓名"
                >{{ current.name }} · {{ current.gender }}</el-descriptions-item
              >
              <el-descriptions-item label="学号">
                <SensitiveField :value="current.id" type="id" />
              </el-descriptions-item>
              <el-descriptions-item label="学籍状态">
                <el-tag
                  :type="current.status === '在读' ? 'success' : 'warning'"
                  effect="plain"
                  size="small"
                >
                  {{ current.status }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="专业">{{ current.major }}</el-descriptions-item>
              <el-descriptions-item label="年级">{{ current.grade }}</el-descriptions-item>
              <el-descriptions-item label="培养层次">{{ current.eduLevel }}</el-descriptions-item>
              <el-descriptions-item label="班级">{{ current.className }}</el-descriptions-item>
              <el-descriptions-item label="辅导员">{{ current.counselor }}</el-descriptions-item>
              <el-descriptions-item label="联系方式">
                <SensitiveField :value="current.phone" type="phone" />
              </el-descriptions-item>
              <el-descriptions-item label="身份标签（加权系数）">
                <el-tag
                  :type="IDENTITY_TAG[current.identityTag].tag as any"
                  effect="plain"
                  size="small"
                >
                  {{ current.identityTag }} · ×{{ IDENTITY_TAG[current.identityTag].weight }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>

            <!-- 关爱标签 -->
            <div class="tag-block">
              <span class="tag-label ss-muted">关爱标签</span>
              <template v-if="current.tags.length">
                <el-tag
                  v-for="t in current.tags"
                  :key="t.type"
                  :type="t.sensitive ? 'danger' : 'info'"
                  effect="plain"
                  size="small"
                  class="mr6"
                  :class="{ 'sensitive-tag': t.sensitive }"
                >
                  <template v-if="t.sensitive">
                    <SensitiveField :value="t.type" type="text" />（{{ t.source }}）
                  </template>
                  <template v-else>{{ t.type }}（{{ t.source }}）</template>
                </el-tag>
              </template>
              <span v-else class="ss-muted">暂无标签</span>
            </div>
          </div>

          <!-- 动态信号聚合 -->
          <div class="ss-card pad mb16">
            <div class="ss-between mb12">
              <div class="ss-section-title" style="margin: 0">动态信号聚合</div>
              <span class="ss-muted small"
                >基于个人基线 · 数值越高越需关注 · 需人工核实，非结论</span
              >
            </div>
            <el-row :gutter="16">
              <el-col v-for="d in signalDims" :key="d.key" :xs="24" :sm="8">
                <div class="signal-box">
                  <div class="signal-top">
                    <span class="signal-name">{{ d.label }}</span>
                    <span class="signal-score" :style="{ color: d.color }">{{ d.value }}</span>
                  </div>
                  <el-progress
                    :percentage="d.value"
                    :color="d.color"
                    :stroke-width="10"
                    :show-text="false"
                  />
                  <div class="signal-foot">
                    <span class="ss-muted small">关注度</span>
                    <span
                      class="signal-level"
                      :style="{
                        color:
                          d.value >= 70
                            ? 'var(--ss-level-3)'
                            : d.value >= 50
                              ? 'var(--ss-level-2)'
                              : 'var(--ss-level-1)',
                      }"
                    >
                      {{ signalLevel(d.value) }}
                    </span>
                  </div>
                </div>
              </el-col>
            </el-row>
            <p class="signal-tip ss-muted">
              信号仅反映与该生个人历史基线的偏移，用于辅助关注，不对学生作任何定性。请结合谈心了解后再判断。
            </p>
          </div>

          <!-- 个人基线对照 -->
          <div class="ss-card pad mb16">
            <div class="ss-between mb12">
              <div class="ss-section-title" style="margin: 0">个人基线对照</div>
              <span class="ss-muted small"
                >基线为本人历史正常区间（近 28/56 日）· 仅辅助、非结论</span
              >
            </div>
            <div class="bl-list">
              <div v-for="(b, i) in current.baselines" :key="i" class="bl-row">
                <span class="bl-dim">{{ b.dimension }}</span>
                <div class="bl-box">
                  <span class="bl-lab">当前</span><span class="bl-cur">{{ b.current }}</span>
                </div>
                <el-icon class="bl-arrow"><Right /></el-icon>
                <div class="bl-box">
                  <span class="bl-lab">个人基线</span><span class="bl-base">{{ b.baseline }}</span>
                </div>
                <div class="bl-box">
                  <span class="bl-lab">偏移</span>
                  <span class="bl-chg" :class="b.status">{{ b.change }}</span>
                </div>
                <span class="bl-win">{{ b.window }}</span>
              </div>
            </div>
            <p class="signal-tip ss-muted">
              基线对照用于解释信号来源：与本人历史区间比较（非全校统一标准），偏移仅作关注提示，需人工核实。
            </p>
          </div>

          <!-- 历史线索与跟进记录 -->
          <div class="ss-card pad">
            <div class="ss-between mb12">
              <div class="ss-section-title" style="margin: 0">历史线索与跟进记录</div>
              <span class="ss-muted small">每条均为需人工核实的线索</span>
            </div>
            <el-table
              v-if="studentClues.length"
              :data="studentClues"
              style="width: 100%"
              @row-click="(row: Clue) => openClue(row)"
              row-class-name="clickable"
            >
              <el-table-column label="线索ID" prop="id" width="130" />
              <el-table-column label="类别" width="120">
                <template #default="{ row }">
                  <span class="cat" :style="{ color: CLUE_CATEGORY[(row as Clue).category].color }">
                    <el-icon
                      ><component :is="CLUE_CATEGORY[(row as Clue).category].icon"
                    /></el-icon>
                    {{ CLUE_CATEGORY[(row as Clue).category].label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="关怀等级" width="140">
                <template #default="{ row }"
                  ><CareLevelTag :level="(row as Clue).level"
                /></template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }"
                  ><ClueStatusTag :status="(row as Clue).status"
                /></template>
              </el-table-column>
              <el-table-column label="生成时间" prop="createdAt" min-width="150" />
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click.stop="openClue(row as Clue)"
                    >查看</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-else text="该生暂无关怀线索" icon="CircleCheck" />
          </div>
        </template>

        <div v-else class="ss-card pad">
          <EmptyState text="未找到该学生，或不在您的责任范围内" icon="UserFilled" />
        </div>
      </el-col>
    </el-row>
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
.mr6 {
  margin-right: 6px;
}
.small {
  font-size: 12px;
}

/* 学生列表 */
.stu-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--ss-border);
  border-radius: 10px;
  cursor: pointer;
  position: relative;
  background: var(--ss-card);
}
.stu-item.active {
  border-color: var(--ss-primary);
  background: var(--ss-primary-soft);
}
.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--ss-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  flex: 0 0 auto;
}
.stu-meta {
  min-width: 0;
  flex: 1;
}
.stu-name {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}
.stu-gender {
  font-size: 12px;
  font-weight: 400;
  color: var(--ss-text-muted);
}
.stu-sub {
  font-size: 12px;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.stu-badge {
  flex: 0 0 auto;
  margin-right: 6px;
}

/* 关爱标签区 */
.tag-block {
  margin-top: 14px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.tag-label {
  font-size: 13px;
  margin-right: 6px;
}
.sensitive-tag {
  font-weight: 600;
}

/* 信号 */
.signal-box {
  border: 1px solid var(--ss-border);
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 8px;
}
.signal-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.signal-name {
  font-size: 14px;
  font-weight: 600;
}
.signal-score {
  font-size: 26px;
  font-weight: 700;
}
.signal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.signal-level {
  font-size: 13px;
  font-weight: 600;
}
.signal-tip {
  font-size: 12px;
  margin: 12px 0 0;
  line-height: 1.6;
}

/* 个人基线对照 */
.bl-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.bl-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border: 1px solid var(--ss-border);
  border-radius: 8px;
  background: var(--ss-bg);
}
.bl-dim {
  font-weight: 600;
  min-width: 104px;
}
.bl-box {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 78px;
}
.bl-lab {
  font-size: 11px;
  color: var(--ss-text-muted);
}
.bl-cur {
  font-weight: 600;
}
.bl-base {
  color: var(--ss-text-muted);
}
.bl-chg {
  font-weight: 700;
}
.bl-chg.alert {
  color: var(--ss-level-3);
}
.bl-chg.watch {
  color: var(--ss-level-2);
}
.bl-chg.normal {
  color: var(--ss-level-1);
}
.bl-arrow {
  color: #c0ccd6;
}
.bl-win {
  margin-left: auto;
  font-size: 12px;
  color: var(--ss-text-muted);
}

/* 线索表格 */
.cat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}
:deep(.clickable) {
  cursor: pointer;
}
</style>
