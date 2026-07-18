<script setup lang="ts">
import { computed } from 'vue';
import VChart from 'vue-echarts';
import type { EChartsOption } from 'echarts';

const props = withDefaults(
  defineProps<{
    categories: string[];
    series: { name: string; data: number[]; color?: string }[];
    height?: string;
    dark?: boolean;
    horizontal?: boolean;
    stack?: boolean;
  }>(),
  { height: '280px', dark: false, horizontal: false, stack: false },
);

const axisColor = computed(() => (props.dark ? '#3a5a80' : '#e6ebef'));
const textColor = computed(() => (props.dark ? '#9cc0e6' : '#6b7a8d'));
const palette = ['#1b8f7a', '#2f6db0', '#e6a23c', '#e5523f', '#7c5cff'];

const catAxis = computed(() => ({
  type: 'category' as const,
  data: props.categories,
  axisLine: { lineStyle: { color: axisColor.value } },
  axisLabel: { color: textColor.value, interval: 0, rotate: props.horizontal ? 0 : 0 },
}));
const valAxis = computed(() => ({
  type: 'value' as const,
  splitLine: { lineStyle: { color: axisColor.value, type: 'dashed' as const } },
  axisLabel: { color: textColor.value },
}));

const option = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { data: props.series.map((s) => s.name), textStyle: { color: textColor.value }, top: 0 },
  grid: { left: props.horizontal ? 110 : 44, right: 18, top: 36, bottom: 30 },
  xAxis: props.horizontal ? valAxis.value : catAxis.value,
  yAxis: props.horizontal ? catAxis.value : valAxis.value,
  series: props.series.map((s, i) => ({
    name: s.name,
    type: 'bar',
    stack: props.stack ? 'total' : undefined,
    data: s.data,
    barMaxWidth: 26,
    itemStyle: {
      color: s.color ?? palette[i % palette.length],
      borderRadius: props.horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0],
    },
  })),
}));
</script>

<template>
  <v-chart :option="option" :style="{ height }" autoresize />
</template>
