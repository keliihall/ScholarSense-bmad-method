<script setup lang="ts">
import { computed } from 'vue';
import VChart from 'vue-echarts';
import type { EChartsOption } from 'echarts';

const props = withDefaults(
  defineProps<{
    data: { name: string; value: number }[];
    height?: string;
    dark?: boolean;
    colors?: string[];
    doughnut?: boolean;
    roseType?: boolean;
  }>(),
  { height: '280px', dark: false, doughnut: true, roseType: false },
);

const textColor = computed(() => (props.dark ? '#9cc0e6' : '#6b7a8d'));
const defaultColors = ['#1b8f7a', '#2f6db0', '#e6a23c', '#e5523f', '#7c5cff', '#29d3c2'];

const option = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { bottom: 0, textStyle: { color: textColor.value } },
  color: props.colors ?? defaultColors,
  series: [
    {
      type: 'pie',
      radius: props.doughnut ? ['42%', '68%'] : '66%',
      center: ['50%', '46%'],
      roseType: props.roseType ? 'radius' : undefined,
      avoidLabelOverlap: true,
      itemStyle: { borderColor: props.dark ? '#0f2440' : '#fff', borderWidth: 2 },
      label: { color: textColor.value, fontSize: 12 },
      data: props.data,
    },
  ],
}));
</script>

<template>
  <v-chart :option="option" :style="{ height }" autoresize />
</template>
