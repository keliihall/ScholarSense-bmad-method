<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import SensitiveField from '@/components/SensitiveField.vue';
import EmptyState from '@/components/EmptyState.vue';
import { tagSummary, identitySummary } from '@/mock';
import { IDENTITY_TAG } from '@/utils/constants';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();

/** 标签来源（演示）：按标签类型给出归集口径 */
const TAG_SOURCE: Record<string, string> = {
  学业预警: '教务系统 · 课业表现',
  经济困难: '资助中心 · 家庭经济认定',
  困难身份: '资助中心 · 身份名单',
  重点关爱: '学院 · 辅导员标注',
  心理关注: '心理中心 · 授权同步',
};

/** 卡片图标（Element Plus 已全局注册） */
const TAG_ICON: Record<string, string> = {
  学业预警: 'Reading',
  经济困难: 'Wallet',
  困难身份: 'House',
  重点关爱: 'Star',
  心理关注: 'FirstAidKit',
};

const tags = computed(() =>
  [...tagSummary].sort((a, b) => Number(b.sensitive) - Number(a.sensitive)),
);

const totalTags = computed(() => tagSummary.length);
const totalTagged = computed(() => tagSummary.reduce((sum, t) => sum + t.count, 0));
const sensitiveCount = computed(() => tagSummary.filter((t) => t.sensitive).length);

const iconOf = (type: string) => TAG_ICON[type] ?? 'CollectionTag';
const sourceOf = (type: string) => TAG_SOURCE[type] ?? '学工归集';

// —— 新增标签（演示用 dialog，仅 Mock 反馈，不写库）——
const dialogVisible = ref(false);
const form = reactive({
  type: '',
  source: '',
  sensitive: false,
  desc: '',
});

function openDialog() {
  form.type = '';
  form.source = '';
  form.sensitive = false;
  form.desc = '';
  dialogVisible.value = true;
}

function submitTag() {
  if (!form.type.trim()) {
    ElMessage.warning('请填写标签名称');
    return;
  }
  dialogVisible.value = false;
  ElMessage.success('标签已提交配置（演示）；操作已留痕：操作人 / 时间 / 对象 / 来源IP / traceId');
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="学生标签体系"
      subtitle="关爱标签授权可见 · 不贴歧视性标签、保护学生尊严 · 标签用于关怀而非定性"
    >
      <template #extra>
        <el-button :icon="'CollectionTag'" plain>导出口径说明</el-button>
        <el-button type="primary" :icon="'Plus'" @click="openDialog">新增标签</el-button>
      </template>
    </PageHeader>

    <!-- 合规提示 -->
    <el-alert type="success" :closable="false" show-icon class="mb16" title="关爱标签使用原则">
      <template #default>
        标签仅作关怀线索的辅助归集，<b>非结论、非定性</b>；心理关注等高敏标签<b>仅授权角色可见</b>，访问全程留痕。请勿据此贴歧视性标签。
      </template>
    </el-alert>

    <!-- 统计卡 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="标签种类"
          :value="totalTags"
          unit="种"
          icon="CollectionTag"
          color="#1b8f7a"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="标注人次"
          :value="totalTagged"
          unit="人次"
          icon="UserFilled"
          color="#2f6db0"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="高敏标签"
          :value="sensitiveCount"
          unit="种"
          icon="Lock"
          color="#e5523f"
          hint="仅授权角色可见"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="当前授权可见"
          :value="auth.canViewSensitive ? '是' : '否'"
          icon="View"
          color="#e6a23c"
          :hint="auth.roleMeta.name"
        />
      </el-col>
    </el-row>

    <!-- 三级身份标签 -->
    <div class="ss-section-title mt16">身份标签 · 决定风险加权系数</div>
    <el-row :gutter="16">
      <el-col v-for="g in identitySummary" :key="g.tag" :xs="24" :md="8" class="mb16">
        <div
          class="ss-card ss-hover id-card"
          :style="{ borderTopColor: IDENTITY_TAG[g.tag].color }"
        >
          <div class="id-head">
            <span class="id-name" :style="{ color: IDENTITY_TAG[g.tag].color }">{{ g.tag }}</span>
            <span
              class="id-weight"
              :style="{
                color: IDENTITY_TAG[g.tag].color,
                background: IDENTITY_TAG[g.tag].color + '14',
              }"
              >×{{ IDENTITY_TAG[g.tag].weight }}</span
            >
          </div>
          <div class="id-count">
            <b>{{ g.count }}</b> 名学生
          </div>
          <div class="id-rule">
            <el-icon><Refresh /></el-icon>
            <span>动态更新：{{ IDENTITY_TAG[g.tag].rule }}</span>
          </div>
          <div class="id-stus">
            <el-tag v-for="(n, i) in g.students" :key="i" size="small" effect="plain">{{
              n
            }}</el-tag>
            <span v-if="!g.students.length" class="ss-muted">—</span>
          </div>
        </div>
      </el-col>
    </el-row>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb16"
      title="身份标签自动纳入需辅导员审核后采用 · 学工部每月更新 + 辅导员手动添加 · 仅提升核实优先级，关注身份 ≠ 自动定性"
    />

    <!-- 标签卡片网格 -->
    <div class="ss-section-title mt16">关爱标签清单（{{ tags.length }} 种）</div>
    <el-row :gutter="16" v-if="tags.length">
      <el-col v-for="t in tags" :key="t.type" :xs="24" :sm="12" :md="8" class="mb16">
        <div class="ss-card ss-hover tag-card">
          <div class="tag-head">
            <div class="tag-icon" :class="{ sens: t.sensitive }">
              <el-icon :size="20"><component :is="iconOf(t.type)" /></el-icon>
            </div>
            <div class="tag-meta">
              <div class="tag-name">
                <template v-if="t.sensitive">
                  <el-icon class="lock"><Lock /></el-icon>
                  <SensitiveField :value="t.type" />
                </template>
                <template v-else>{{ t.type }}</template>
              </div>
              <div class="tag-source">来源：{{ sourceOf(t.type) }}</div>
            </div>
            <el-tag
              :type="(t.sensitive ? 'danger' : 'info') as any"
              effect="plain"
              size="small"
              round
            >
              {{ t.sensitive ? '高敏' : '普通' }}
            </el-tag>
          </div>

          <div class="tag-count">
            <span class="cnt">{{ t.count }}</span>
            <span class="cnt-unit">名学生</span>
          </div>

          <div v-if="t.sensitive" class="sens-note">
            <el-icon><InfoFilled /></el-icon>
            <span>高敏标签 · 仅授权角色可见，下方学生名按授权脱敏，访问留痕</span>
          </div>

          <el-divider class="div" />

          <div class="stu-title">关联学生</div>
          <div class="stu-list">
            <template v-if="t.sensitive">
              <el-tag
                v-for="(name, i) in t.students"
                :key="i"
                effect="plain"
                size="small"
                :type="'danger' as any"
                class="stu-tag"
              >
                <SensitiveField :value="name" type="text" />
              </el-tag>
            </template>
            <template v-else>
              <el-tag
                v-for="(name, i) in t.students"
                :key="i"
                effect="plain"
                size="small"
                :type="'info' as any"
                class="stu-tag"
              >
                {{ name }}
              </el-tag>
            </template>
          </div>
        </div>
      </el-col>
    </el-row>
    <EmptyState v-else text="暂无标签" icon="CollectionTag" />

    <p class="foot-tip">
      说明：标签为关怀工作的辅助归集口径，<b>需人工核实</b>后方可作为关怀依据，系统不据标签作自动决策；高敏标签的查看与导出均记录操作人
      / 时间 / 对象 / 来源IP / traceId。
    </p>

    <!-- 新增标签弹窗（演示） -->
    <el-dialog v-model="dialogVisible" title="新增关爱标签" width="460px">
      <el-form label-position="top">
        <el-form-item label="标签名称" required>
          <el-input
            v-model="form.type"
            placeholder="如：学业帮扶、家庭关怀（请勿使用歧视性措辞）"
          />
        </el-form-item>
        <el-form-item label="数据来源 / 归集口径">
          <el-input v-model="form.source" placeholder="如：教务系统 · 课业表现" />
        </el-form-item>
        <el-form-item label="是否高敏（仅授权角色可见）">
          <el-switch v-model="form.sensitive" />
          <span class="switch-hint">开启后该标签按授权可见，访问全程留痕</span>
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input
            v-model="form.desc"
            type="textarea"
            :rows="2"
            placeholder="说明此标签用于何种关怀场景（非定性、非处置依据）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitTag">提交配置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mt16 {
  margin-top: 16px;
}
.id-card {
  padding: 16px 18px;
  height: 100%;
  border-top: 3px solid var(--ss-primary);
}
.id-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.id-name {
  font-size: 17px;
  font-weight: 700;
}
.id-weight {
  font-size: 14px;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 12px;
}
.id-count {
  margin-top: 8px;
  font-size: 13px;
  color: var(--ss-text-muted);
}
.id-count b {
  font-size: 22px;
  color: var(--ss-text);
}
.id-rule {
  margin-top: 10px;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  color: var(--ss-text-muted);
  line-height: 1.6;
}
.id-stus {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tag-card {
  padding: 16px 18px;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.tag-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.tag-icon {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--ss-primary-soft);
  color: var(--ss-primary);
}
.tag-icon.sens {
  background: #fdecea;
  color: var(--ss-level-3);
}
.tag-meta {
  flex: 1;
  min-width: 0;
}
.tag-name {
  font-size: 15px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.tag-name .lock {
  color: var(--ss-level-3);
  font-size: 14px;
}
.tag-source {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin-top: 2px;
}
.tag-count {
  margin-top: 14px;
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.cnt {
  font-size: 28px;
  font-weight: 700;
  color: var(--ss-text);
}
.cnt-unit {
  font-size: 13px;
  color: var(--ss-text-muted);
}
.sens-note {
  margin-top: 10px;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  color: var(--ss-level-3);
  background: #fdecea;
  border-radius: 8px;
  padding: 8px 10px;
  line-height: 1.5;
}
.div {
  margin: 14px 0 10px;
}
.stu-title {
  font-size: 13px;
  color: var(--ss-text-muted);
  margin-bottom: 8px;
}
.stu-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.stu-tag {
  margin: 0;
}
.switch-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--ss-text-muted);
}
.foot-tip {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 6px 0 0;
  line-height: 1.6;
}
</style>
