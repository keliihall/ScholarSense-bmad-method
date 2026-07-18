<script setup lang="ts">
import { computed } from 'vue';
import type { ClueStatus } from '@/types';
import { CLUE_STATUS } from '@/utils/constants';

const props = withDefaults(
  defineProps<{ status: ClueStatus; size?: 'small' | 'default' | 'large' }>(),
  {
    size: 'small',
  },
);
const meta = computed(() => CLUE_STATUS[props.status]);
</script>

<template>
  <el-tag :type="meta.tag as any" :size="size" effect="light" round>{{ meta.label }}</el-tag>
</template>
