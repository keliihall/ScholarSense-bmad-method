<script setup lang="ts">
import { computed } from 'vue';
import VChart from 'vue-echarts';
import type { EChartsOption } from 'echarts';

const props = withDefaults(
  defineProps<{
    dates: string[];
    series: { name: string; data: number[]; color?: string }[];
    height?: string;
    dark?: boolean;
    smooth?: boolean;
    area?: boolean;
  }>(),
  { height: '280px', dark: false, smooth: true, area: true },
);

const axisColor = computed(() => (props.dark ? '#3a5a80' : '#e6ebef'));
const textColor = computed(() => (props.dark ? '#9cc0e6' : '#6b7a8d'));
const palette = ['#1b8f7a', '#2f6db0', '#e6a23c', '#e5523f'];

const option = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: props.series.map((s) => s.name), textStyle: { color: textColor.value }, top: 0 },
  grid: { left: 40, right: 18, top: 36, bottom: 28 },
  xAxis: {
    type: 'category',
    data: props.dates,
    boundaryGap: false,
    axisLine: { lineStyle: { color: axisColor.value } },
    axisLabel: { color: textColor.value },
  },
  yAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: axisColor.value, type: 'dashed' } },
    axisLabel: { color: textColor.value },
  },
  series: props.series.map((s, i) => ({
    name: s.name,
    type: 'line',
    smooth: props.smooth,
    data: s.data,
    itemStyle: { color: s.color ?? palette[i % palette.length] },
    areaStyle: props.area
      ? {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: (s.color ?? palette[i % palette.length]) + '40' },
              { offset: 1, color: (s.color ?? palette[i % palette.length]) + '02' },
            ],
          },
        }
      : undefined,
  })),
}));
</script>

<template>
  <v-chart :option="option" :style="{ height }" autoresize />
</template>
