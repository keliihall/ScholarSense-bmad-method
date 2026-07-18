<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import StatCard from '@/components/StatCard.vue';
import BarChart from '@/components/charts/BarChart.vue';
import { modelGates, modelVersions } from '@/mock';
import { pct } from '@/utils/format';
import type { ModelVersion } from '@/types';

const gatesPassed = computed(() => modelGates.filter((g) => g.pass).length);
const allGatesPassed = computed(() => modelGates.every((g) => g.pass));

const grayCount = computed(() => modelVersions.filter((m) => m.releaseStatus === '灰度').length);
const prodCount = computed(() => modelVersions.filter((m) => m.releaseStatus === '生产').length);

// 效果评估对比（Precision@K / Recall@K，按版本）
const evalCategories = computed(() => modelVersions.map((m) => m.version));
const evalSeries = computed(() => [
  {
    name: 'Precision@K',
    data: modelVersions.map((m) => Math.round(m.precisionAtK * 100)),
    color: '#1b8f7a',
  },
  {
    name: 'Recall@K',
    data: modelVersions.map((m) => Math.round(m.recallAtK * 100)),
    color: '#2f6db0',
  },
]);

const releaseTag = (status: ModelVersion['releaseStatus']) =>
  status === '生产' ? 'success' : status === '灰度' ? 'warning' : 'info';

const complianceTag = (status: ModelVersion['complianceCheck']) =>
  status === '通过' ? 'success' : status === '未通过' ? 'danger' : 'warning';

function rollback(row: ModelVersion) {
  ElMessage.success(
    `已回退到稳定基线「${row.rollbackBaseline}」，操作已留痕（操作人/时间/对象/IP/traceId）`,
  );
}

function viewVersion(row: ModelVersion) {
  ElMessage.info(`查看模型版本「${row.version}」评估明细，本次查看已留痕`);
}
</script>

<template>
  <div class="ss-page">
    <PageHeader
      title="模型与质量治理"
      subtitle="先可解释后智能 · 灰度发布 + 稳定版本回退 · 模型仅产出需人工核实的线索，不作自动决策"
    >
      <template #extra>
        <el-button
          :icon="'Document'"
          plain
          @click="ElMessage.info('治理报告导出已留痕（操作人/时间/对象/IP/traceId）')"
        >
          导出治理报告
        </el-button>
      </template>
    </PageHeader>

    <!-- 合规红线提示 -->
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="mb16"
      title="任一关卡未通过即停止扩大范围"
    >
      <template #default>
        模型不得自动作出重大不利决定；不以单一 AUC /
        总体准确率作为上线依据。所有上线决策均经人工复核，全程留痕。
      </template>
    </el-alert>

    <!-- 概览统计 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="治理关卡通过"
          :value="`${gatesPassed}/${modelGates.length}`"
          icon="Finished"
          :color="allGatesPassed ? '#1f9d72' : '#e6a23c'"
          :hint="allGatesPassed ? '全部通过' : '存在未通过关卡'"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="灰度发布中"
          :value="grayCount"
          unit="个"
          icon="Histogram"
          color="#e6a23c"
          hint="小范围验证"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="生产运行"
          :value="prodCount"
          unit="个"
          icon="CircleCheck"
          color="#1f9d72"
          hint="已通过复核"
        />
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <StatCard
          title="可回退基线"
          :value="modelVersions.length"
          unit="个"
          icon="RefreshLeft"
          color="#2f6db0"
          hint="每版本均可回退"
        />
      </el-col>
    </el-row>

    <!-- 四关卡 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">模型治理四关卡</div>
        <span class="ss-muted small"
          >数据质量 → 效果评估 → 分群公平性 → 合规与人工复核 · 任一未通过即停止扩大范围</span
        >
      </div>
      <el-row :gutter="16">
        <el-col v-for="(g, i) in modelGates" :key="g.name" :xs="24" :sm="12" :md="6">
          <div class="gate" :class="g.pass ? 'gate-ok' : 'gate-block'">
            <div class="gate-top">
              <span class="gate-no">关卡 {{ i + 1 }}</span>
              <el-icon class="gate-mark" :size="20">
                <CircleCheckFilled v-if="g.pass" />
                <CircleCloseFilled v-else />
              </el-icon>
            </div>
            <div class="gate-name">{{ g.name }}</div>
            <div class="gate-desc">{{ g.desc }}</div>
            <el-tag :type="g.pass ? 'success' : 'danger'" effect="light" size="small" round>
              {{ g.pass ? '已通过' : '未通过 · 暂缓扩大范围' }}
            </el-tag>
          </div>
        </el-col>
      </el-row>
      <p class="gate-foot">
        关卡顺序体现「先可解释后智能」：先保证数据可信与效果可解释，再校验分群公平性，最终经合规与人工复核方可放量。
      </p>
    </div>

    <el-row :gutter="16" class="mt16">
      <!-- 效果评估对比 -->
      <el-col :xs="24" :md="14">
        <div class="ss-card pad">
          <div class="ss-section-title">各版本效果评估对比（%）</div>
          <BarChart :categories="evalCategories" :series="evalSeries" height="260px" />
          <p class="chart-foot">
            评估为多维参考（Precision@K / Recall@K / 噪声率 / 分群公平性 /
            协同成效），不以单一指标作为上线依据。
          </p>
        </div>
      </el-col>

      <!-- 治理原则 -->
      <el-col :xs="24" :md="10">
        <div class="ss-card pad">
          <div class="ss-section-title">治理原则</div>
          <el-alert type="success" :closable="false" show-icon class="mb8" title="先可解释后智能">
            <template #default
              >规则与基线先行、可解释优先；模型作为排序与发现辅助，输出附证据链与解释。</template
            >
          </el-alert>
          <el-alert type="info" :closable="false" show-icon class="mb8" title="灰度发布 + 稳定回退">
            <template #default
              >新版本先小范围灰度，异常时经人工确认回退至稳定基线，回退动作全程留痕。</template
            >
          </el-alert>
          <el-alert type="warning" :closable="false" show-icon title="不作自动决策">
            <template #default
              >不以单一 AUC / 总体准确率上线；模型不得自动作出重大不利决定，须经人工核实。</template
            >
          </el-alert>
        </div>
      </el-col>
    </el-row>

    <!-- 模型版本表 -->
    <div class="ss-card pad mt16">
      <div class="ss-between mb12">
        <div class="ss-section-title" style="margin: 0">模型版本与发布状态</div>
        <span class="ss-muted small">每个版本均配置回退基线 · 仅产出需人工核实的线索</span>
      </div>
      <el-table :data="modelVersions" style="width: 100%">
        <el-table-column label="版本" min-width="130">
          <template #default="{ row }">
            <span class="ver">{{ (row as ModelVersion).version }}</span>
          </template>
        </el-table-column>
        <el-table-column label="所属镜" min-width="200" prop="mirror" show-overflow-tooltip />
        <el-table-column label="Precision@K" width="120" align="center">
          <template #default="{ row }">{{
            pct((row as ModelVersion).precisionAtK * 100)
          }}</template>
        </el-table-column>
        <el-table-column label="Recall@K" width="110" align="center">
          <template #default="{ row }">{{ pct((row as ModelVersion).recallAtK * 100) }}</template>
        </el-table-column>
        <el-table-column label="噪声率" width="100" align="center">
          <template #default="{ row }">
            <span class="noise">{{ pct((row as ModelVersion).noiseRate * 100) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分群公平性" min-width="190">
          <template #default="{ row }">
            <span class="ss-muted small">{{ (row as ModelVersion).fairness }}</span>
          </template>
        </el-table-column>
        <el-table-column label="合规复核" width="110" align="center">
          <template #default="{ row }">
            <el-tag
              :type="complianceTag((row as ModelVersion).complianceCheck) as any"
              effect="plain"
              size="small"
            >
              {{ (row as ModelVersion).complianceCheck }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="releaseTag((row as ModelVersion).releaseStatus) as any"
              effect="dark"
              size="small"
              round
            >
              {{ (row as ModelVersion).releaseStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回退基线" min-width="130">
          <template #default="{ row }">
            <span class="ss-muted small">{{ (row as ModelVersion).rollbackBaseline }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewVersion(row as ModelVersion)"
              >查看</el-button
            >
            <el-popconfirm
              title="确认回退到稳定基线？回退动作将记录留痕。"
              confirm-button-text="确认回退"
              cancel-button-text="取消"
              width="240"
              @confirm="rollback(row as ModelVersion)"
            >
              <template #reference>
                <el-button link type="warning">回退</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <p class="table-foot">
        发布状态：<el-tag type="warning" effect="dark" size="small" round>灰度</el-tag> 小范围验证 ·
        <el-tag type="success" effect="dark" size="small" round>生产</el-tag> 已通过复核放量 ·
        <el-tag type="info" effect="dark" size="small" round>回退</el-tag>
        已退回稳定基线。任一关卡未通过的版本不得扩大范围。
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
.mb8 {
  margin-bottom: 8px;
}
.small {
  font-size: 12px;
}
.gate {
  border: 1px solid var(--ss-border);
  border-radius: var(--ss-radius);
  padding: 14px 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fff;
}
.gate-ok {
  border-left: 4px solid var(--ss-level-1);
  background: linear-gradient(180deg, rgba(31, 157, 114, 0.06), #fff 60%);
}
.gate-block {
  border-left: 4px solid var(--ss-level-3);
  background: linear-gradient(180deg, rgba(229, 82, 63, 0.06), #fff 60%);
}
.gate-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.gate-no {
  font-size: 12px;
  color: var(--ss-text-muted);
}
.gate-ok .gate-mark {
  color: var(--ss-level-1);
}
.gate-block .gate-mark {
  color: var(--ss-level-3);
}
.gate-name {
  font-size: 15px;
  font-weight: 700;
}
.gate-desc {
  font-size: 12px;
  color: var(--ss-text-muted);
  line-height: 1.5;
  flex: 1;
}
.gate-foot {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--ss-text-muted);
}
.chart-foot,
.table-foot {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ss-text-muted);
  line-height: 1.6;
}
.table-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.ver {
  font-weight: 600;
  font-family: 'SFMono-Regular', Menlo, Consolas, monospace;
}
.noise {
  color: var(--ss-level-2);
  font-weight: 600;
}
</style>
