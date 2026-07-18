<script setup lang="ts">
defineProps<{ explanation: string; suggestion: string[] }>();
</script>

<template>
  <div class="explain">
    <div class="block ai">
      <div class="block-title">
        <el-icon><MagicStick /></el-icon> 观澜 · AI 解释
        <el-tag size="small" type="warning" effect="plain" round>需人工核实 · 非结论</el-tag>
      </div>
      <p class="text">{{ explanation }}</p>
    </div>
    <div class="block care">
      <div class="block-title">
        <el-icon><Sunny /></el-icon> 关怀建议
        <el-tag size="small" type="info" effect="plain" round>建议而非自动决策</el-tag>
      </div>
      <ul class="suglist">
        <li v-for="(s, i) in suggestion" :key="i">{{ s }}</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.explain {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.block {
  padding: 14px 16px;
  border-radius: 10px;
}
.block.ai {
  background: linear-gradient(135deg, #fff8ec, #fffdf8);
  border: 1px solid #f5e3c0;
}
.block.care {
  background: var(--ss-primary-soft);
  border: 1px solid #d6ece8;
}
.block-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 8px;
}
.text {
  margin: 0;
  line-height: 1.7;
  color: var(--ss-text);
  font-size: 14px;
}
.suglist {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
  font-size: 14px;
}
</style>
