<script setup lang="ts">
defineProps<{ text?: string; icon?: string }>();
</script>

<template>
  <div class="empty">
    <el-icon :size="40"><component :is="icon ?? 'Files'" /></el-icon>
    <p>{{ text ?? '暂无数据' }}</p>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
  color: #b6c2cd;
  gap: 8px;
}
</style>
