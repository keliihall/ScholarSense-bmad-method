<script setup lang="ts">
import { computed } from 'vue';
import type { CareLevel } from '@/types';
import { CARE_LEVEL } from '@/utils/constants';

const props = withDefaults(
  defineProps<{ level: CareLevel; size?: 'small' | 'default' | 'large' }>(),
  {
    size: 'small',
  },
);
const meta = computed(() => CARE_LEVEL[props.level]);
</script>

<template>
  <el-tag :type="meta.tag as any" :size="size" effect="dark" round>{{ meta.label }}</el-tag>
</template>
