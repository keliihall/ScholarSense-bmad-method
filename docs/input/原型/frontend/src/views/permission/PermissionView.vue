<script setup lang="ts">
import { reactive } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import { ROLES, COMPLIANCE_BANNER } from '@/utils/constants';
import type { RoleKey } from '@/types';

/** 数据范围 → 中文 + 标签色 */
const DATA_SCOPE: Record<'all' | 'college' | 'own' | 'none', { label: string; tag: string }> = {
  all: { label: '全校', tag: 'danger' },
  college: { label: '本学院', tag: 'warning' },
  own: { label: '责任学生', tag: 'success' },
  none: { label: '不见业务明细', tag: 'info' },
};

/** 角色矩阵（来自统一常量 ROLES，保证全站一致） */
const roleRows = (Object.keys(ROLES) as RoleKey[]).map((key) => ({ key, ...ROLES[key] }));

/** 矩阵统计（演示） */
const stats = {
  roleTotal: roleRows.length,
  sensitiveRoles: roleRows.filter((r) => r.canViewSensitive).length,
  noBizRoles: roleRows.filter((r) => r.dataScope === 'none').length,
};

/** 字段脱敏矩阵：行=敏感字段，列=角色（取与业务最相关的几类角色演示） */
type MaskMode = 'plain' | 'mask' | 'hidden';

const MASK_MODE: Record<MaskMode, { label: string; tag: string; icon: string }> = {
  plain: { label: '明文', tag: 'danger', icon: 'View' },
  mask: { label: '脱敏', tag: 'warning', icon: 'Hide' },
  hidden: { label: '不可见', tag: 'info', icon: 'Lock' },
};
const MASK_ORDER: MaskMode[] = ['plain', 'mask', 'hidden'];

/** 参与脱敏配置的角色列（运维/数据责任人默认不见业务明细，不列入逐字段配置） */
const fieldRoleCols: { key: RoleKey; name: string }[] = [
  { key: 'counselor', name: ROLES.counselor.name },
  { key: 'college', name: ROLES.college.name },
  { key: 'affairs', name: ROLES.affairs.name },
  { key: 'collaborator', name: ROLES.collaborator.name },
  { key: 'leader', name: ROLES.leader.name },
];

interface FieldRow {
  field: string;
  desc: string;
  /** 是否高敏字段 */
  high: boolean;
  modes: Record<RoleKey, MaskMode>;
}

/** 默认值合理：心理标签仅辅导员/协同/学工可见；学院、领导看脱敏或不可见 */
const fieldRows = reactive<FieldRow[]>([
  {
    field: '手机号',
    desc: '学生联系方式',
    high: true,
    modes: {
      counselor: 'plain',
      college: 'mask',
      affairs: 'plain',
      collaborator: 'mask',
      leader: 'hidden',
      ops: 'hidden',
      data: 'hidden',
    },
  },
  {
    field: '学号',
    desc: '学生唯一标识',
    high: true,
    modes: {
      counselor: 'plain',
      college: 'plain',
      affairs: 'plain',
      collaborator: 'mask',
      leader: 'mask',
      ops: 'hidden',
      data: 'hidden',
    },
  },
  {
    field: '心理关注标签',
    desc: '高敏 · 仅授权角色可见',
    high: true,
    modes: {
      counselor: 'plain',
      college: 'hidden',
      affairs: 'plain',
      collaborator: 'plain',
      leader: 'hidden',
      ops: 'hidden',
      data: 'hidden',
    },
  },
  {
    field: '困难身份',
    desc: '经济资助名单',
    high: true,
    modes: {
      counselor: 'plain',
      college: 'mask',
      affairs: 'plain',
      collaborator: 'mask',
      leader: 'hidden',
      ops: 'hidden',
      data: 'hidden',
    },
  },
  {
    field: '夜间上网汇总',
    desc: '作息行为汇总（非明细）',
    high: false,
    modes: {
      counselor: 'plain',
      college: 'plain',
      affairs: 'plain',
      collaborator: 'mask',
      leader: 'mask',
      ops: 'hidden',
      data: 'hidden',
    },
  },
]);

function cycleMode(row: FieldRow, roleKey: RoleKey) {
  const cur = row.modes[roleKey];
  const next = MASK_ORDER[(MASK_ORDER.indexOf(cur) + 1) % MASK_ORDER.length];
  row.modes[roleKey] = next;
  const roleName = ROLES[roleKey].name;
  ElMessage.success(
    `「${roleName}」对「${row.field}」的可见性调整为「${MASK_MODE[next].label}」（演示）· 操作已留痕：操作人 / 时间 / 对象 / 来源IP / traceId`,
  );
}

function exportMatrix() {
  ElMessage.success('授权配置已导出（演示）· 操作已留痕：操作人 / 时间 / 对象 / 来源IP / traceId');
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="权限与脱敏"
      subtitle="最小必要 · 分级授权 · 授权可见而非全员可查 · 运维默认不见业务明细"
    >
      <template #extra>
        <el-button :icon="'Download'" plain @click="exportMatrix">导出授权配置</el-button>
      </template>
    </PageHeader>

    <!-- 合规横幅 -->
    <el-alert type="success" :closable="false" show-icon class="mb16" title="授权可见 · 最小必要">
      <template #default>{{ COMPLIANCE_BANNER }}</template>
    </el-alert>

    <!-- 统计卡 -->
    <el-row :gutter="16">
      <el-col :xs="24" :sm="8"
        ><StatCard
          title="角色总数"
          :value="stats.roleTotal"
          unit="类"
          icon="UserFilled"
          color="#1b8f7a"
          hint="按 RBAC 分级授权"
      /></el-col>
      <el-col :xs="24" :sm="8"
        ><StatCard
          title="可见高敏字段角色"
          :value="stats.sensitiveRoles"
          unit="类"
          icon="View"
          color="#e6a23c"
          hint="心理/困难等按授权"
      /></el-col>
      <el-col :xs="24" :sm="8"
        ><StatCard
          title="不见业务明细角色"
          :value="stats.noBizRoles"
          unit="类"
          icon="Lock"
          color="#909399"
          hint="运维 / 数据责任人"
      /></el-col>
    </el-row>

    <!-- 角色权限矩阵 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">角色权限矩阵</div>
        <span class="ss-muted small">数据范围 + 高敏字段可见性，遵循最小必要原则</span>
      </div>
      <el-table :data="roleRows" style="width: 100%" border>
        <el-table-column label="角色" min-width="150">
          <template #default="{ row }">
            <span class="role-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" width="150">
          <template #default="{ row }">
            <el-tag
              :type="DATA_SCOPE[row.dataScope as keyof typeof DATA_SCOPE].tag as any"
              effect="light"
              round
              size="small"
            >
              {{ DATA_SCOPE[row.dataScope as keyof typeof DATA_SCOPE].label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="可见高敏字段" width="150" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.canViewSensitive" type="warning" effect="dark" round size="small">
              <el-icon class="tag-ic"><View /></el-icon>授权可见
            </el-tag>
            <el-tag v-else type="info" effect="plain" round size="small">
              <el-icon class="tag-ic"><Lock /></el-icon>默认不可见
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="授权说明" min-width="240">
          <template #default="{ row }">
            <span class="ss-muted">{{ row.desc }}</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="foot-tip">
        说明：高敏字段（心理关注等）即便角色「授权可见」，仍受数据范围约束——辅导员仅见责任学生，协同角色按转介最小授权。
      </p>
    </div>

    <!-- 字段脱敏配置矩阵 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">字段脱敏配置</div>
        <span class="ss-muted small">单击单元格在 明文 / 脱敏 / 不可见 之间切换（演示）</span>
      </div>

      <el-table :data="fieldRows" style="width: 100%" border>
        <el-table-column label="敏感字段" min-width="180" fixed>
          <template #default="{ row }">
            <div class="field-cell">
              <span class="field-name">{{ (row as FieldRow).field }}</span>
              <el-tag
                v-if="(row as FieldRow).high"
                type="danger"
                effect="plain"
                size="small"
                class="hi-tag"
                >高敏</el-tag
              >
              <div class="field-desc ss-muted">{{ (row as FieldRow).desc }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          v-for="col in fieldRoleCols"
          :key="col.key"
          :label="col.name"
          align="center"
          min-width="120"
        >
          <template #default="{ row }">
            <el-tag
              :type="MASK_MODE[(row as FieldRow).modes[col.key]].tag as any"
              :effect="(row as FieldRow).modes[col.key] === 'plain' ? 'dark' : 'light'"
              round
              size="small"
              class="mode-tag ss-hover"
              @click="cycleMode(row as FieldRow, col.key)"
            >
              <el-icon class="tag-ic"
                ><component :is="MASK_MODE[(row as FieldRow).modes[col.key]].icon"
              /></el-icon>
              {{ MASK_MODE[(row as FieldRow).modes[col.key]].label }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="legend">
        <span class="ss-muted small">图例：</span>
        <el-tag type="danger" effect="dark" round size="small" class="lg"
          ><el-icon class="tag-ic"><View /></el-icon>明文</el-tag
        >
        <el-tag type="warning" effect="light" round size="small" class="lg"
          ><el-icon class="tag-ic"><Hide /></el-icon>脱敏</el-tag
        >
        <el-tag type="info" effect="light" round size="small" class="lg"
          ><el-icon class="tag-ic"><Lock /></el-icon>不可见</el-tag
        >
        <span class="ss-muted small ml-auto"
          >运维管理员、数据责任人默认对全部业务明细不可见，不参与逐字段配置。</span
        >
      </div>
    </div>

    <!-- 原则说明 -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :md="8">
        <div class="ss-card pad principle">
          <div class="p-head">
            <el-icon color="#1b8f7a"><Aim /></el-icon><b>最小必要</b>
          </div>
          <p class="ss-muted">只采必要字段、只授必要权限；非业务场景不下放高敏字段访问。</p>
        </div>
      </el-col>
      <el-col :xs="24" :md="8">
        <div class="ss-card pad principle">
          <div class="p-head">
            <el-icon color="#e6a23c"><Operation /></el-icon><b>分级授权</b>
          </div>
          <p class="ss-muted">
            数据范围（全校 / 本院 / 责任学生）与字段脱敏（明文 / 脱敏 / 不可见）双重控制。
          </p>
        </div>
      </el-col>
      <el-col :xs="24" :md="8">
        <div class="ss-card pad principle">
          <div class="p-head">
            <el-icon color="#909399"><Lock /></el-icon><b>运维隔离</b>
          </div>
          <p class="ss-muted">
            运维 / 数据责任人默认不见业务明细；任何字段访问与授权变更全程留痕可追溯。
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
.role-name {
  font-weight: 600;
}
.tag-ic {
  margin-right: 3px;
  vertical-align: -1px;
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 12px 0 0;
}
.field-cell {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.field-name {
  font-weight: 600;
}
.hi-tag {
  transform: scale(0.9);
}
.field-desc {
  flex-basis: 100%;
  font-size: 12px;
}
.mode-tag {
  cursor: pointer;
  user-select: none;
}
.legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}
.legend .lg {
  cursor: default;
}
.ml-auto {
  margin-left: auto;
}
.principle .p-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  margin-bottom: 6px;
}
.principle p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}
</style>
