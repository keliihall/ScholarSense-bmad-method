<script setup lang="ts">
defineProps<{
  title: string;
  value: string | number;
  unit?: string;
  icon?: string;
  color?: string;
  trend?: string;
  trendUp?: boolean;
  hint?: string;
}>();
</script>

<template>
  <div class="ss-card stat ss-hover">
    <div
      class="stat-icon"
      :style="{ background: (color ?? '#1b8f7a') + '1a', color: color ?? '#1b8f7a' }"
    >
      <el-icon :size="22"><component :is="icon ?? 'DataAnalysis'" /></el-icon>
    </div>
    <div class="stat-body">
      <div class="stat-title">{{ title }}</div>
      <div class="stat-value">
        {{ value }}<span class="stat-unit">{{ unit }}</span>
        <span v-if="trend" class="stat-trend" :class="{ up: trendUp, down: trendUp === false }">{{
          trend
        }}</span>
      </div>
      <div v-if="hint" class="stat-hint">{{ hint }}</div>
    </div>
  </div>
</template>

<style scoped>
.stat {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-title {
  color: var(--ss-text-muted);
  font-size: 13px;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.3;
}
.stat-unit {
  font-size: 13px;
  font-weight: 400;
  color: var(--ss-text-muted);
  margin-left: 3px;
}
.stat-trend {
  font-size: 12px;
  margin-left: 8px;
  font-weight: 500;
}
.stat-trend.up {
  color: #e5523f;
}
.stat-trend.down {
  color: #1f9d72;
}
.stat-hint {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin-top: 2px;
}
</style>
