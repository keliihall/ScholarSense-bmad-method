<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>();
</script>

<template>
  <div class="page-header">
    <div>
      <h2 class="ph-title">{{ title }}</h2>
      <p v-if="subtitle" class="ph-sub">{{ subtitle }}</p>
    </div>
    <div class="ph-extra">
      <slot name="extra" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}
.ph-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}
.ph-sub {
  margin: 4px 0 0;
  color: var(--ss-text-muted);
  font-size: 13px;
}
.ph-extra {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
