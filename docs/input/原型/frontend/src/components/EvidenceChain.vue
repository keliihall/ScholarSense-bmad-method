<script setup lang="ts">
import type { Evidence } from '@/types';

defineProps<{ evidence: Evidence[] }>();
</script>

<template>
  <div class="chain">
    <div v-for="(e, i) in evidence" :key="i" class="ev ss-card">
      <div class="ev-head">
        <span class="ev-source">
          <el-icon><Connection /></el-icon> {{ e.source }}
        </span>
        <el-tag size="small" effect="plain">可信度 {{ e.confidence }}%</el-tag>
      </div>
      <div class="ev-metric">{{ e.metric }}</div>
      <div class="ev-values">
        <div class="vbox">
          <span class="vlabel">当前值</span>
          <span class="vnum cur">{{ e.current }}</span>
        </div>
        <el-icon class="arrow"><Right /></el-icon>
        <div class="vbox">
          <span class="vlabel">个人基线</span>
          <span class="vnum">{{ e.baseline }}</span>
        </div>
        <div class="vbox change">
          <span class="vlabel">变化幅度</span>
          <span class="vnum chg">{{ e.change }}</span>
        </div>
        <div class="vbox">
          <span class="vlabel">时间窗</span>
          <span class="vnum">{{ e.window }}</span>
        </div>
      </div>
      <div class="ev-exc">
        <el-icon><CircleCheck /></el-icon> 场景排除：{{ e.exclusion }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.chain {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ev {
  padding: 14px 16px;
  border-left: 3px solid var(--ss-primary);
}
.ev-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ev-source {
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ev-metric {
  color: var(--ss-text-muted);
  font-size: 13px;
  margin: 6px 0 10px;
}
.ev-values {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}
.vbox {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.vlabel {
  font-size: 11px;
  color: var(--ss-text-muted);
}
.vnum {
  font-size: 16px;
  font-weight: 600;
}
.vnum.cur {
  color: var(--ss-primary-dark);
}
.vnum.chg {
  color: #e5523f;
}
.arrow {
  color: #c0ccd6;
}
.ev-exc {
  margin-top: 10px;
  font-size: 12px;
  color: #1f9d72;
  display: flex;
  align-items: center;
  gap: 6px;
  background: #f0faf6;
  padding: 6px 10px;
  border-radius: 6px;
}
</style>
