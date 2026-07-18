<script setup lang="ts">
import { computed } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * 脱敏字段：体现「授权可见而非全员可查」。
 * - allow 显式传入则以其为准；否则取当前角色的 canViewSensitive。
 * - 未授权时显示掩码并提示「授权可见」。
 */
const props = withDefaults(
  defineProps<{
    value: string;
    /** 显式授权覆盖（不传则按角色判断） */
    allow?: boolean;
    /** 掩码类型 */
    type?: 'phone' | 'id' | 'text';
  }>(),
  { type: 'text' },
);

const auth = useAuthStore();

// 注意：Vue 对 boolean 类型 prop 有特殊处理——未传入时会被强制为 false（而非 undefined）。
// 因此这里用 ||：未显式传 allow 时按角色 canViewSensitive 判断；显式 :allow="true" 可强制可见。
const granted = computed(() => props.allow || auth.canViewSensitive);

const masked = computed(() => {
  if (granted.value) return props.value;
  if (props.type === 'phone') return props.value.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2');
  if (props.type === 'id')
    return props.value.length > 4
      ? `${props.value.slice(0, 2)}****${props.value.slice(-2)}`
      : '****';
  return '••••••';
});
</script>

<template>
  <span class="sensitive">
    {{ masked }}
    <el-tooltip v-if="!granted" content="高敏字段 · 仅授权角色可见，访问留痕" placement="top">
      <el-icon class="lock"><Lock /></el-icon>
    </el-tooltip>
  </span>
</template>

<style scoped>
.sensitive {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.lock {
  color: var(--ss-text-muted);
  font-size: 13px;
}
</style>
