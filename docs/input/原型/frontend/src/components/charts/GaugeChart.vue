<script setup lang="ts">
import { computed } from 'vue';
import VChart from 'vue-echarts';
import type { EChartsOption } from 'echarts';

const props = withDefaults(
  defineProps<{
    value: number;
    max?: number;
    label?: string;
    height?: string;
    dark?: boolean;
    color?: string;
    suffix?: string;
  }>(),
  { max: 100, height: '220px', dark: false, color: '#1b8f7a', suffix: '%' },
);

const textColor = computed(() => (props.dark ? '#cfe3f7' : '#1f2d3d'));

const option = computed<EChartsOption>(() => ({
  series: [
    {
      type: 'gauge',
      min: 0,
      max: props.max,
      progress: { show: true, width: 14, itemStyle: { color: props.color } },
      axisLine: { lineStyle: { width: 14, color: [[1, props.dark ? '#1d3a5f' : '#eef2f5']] } },
      axisTick: { show: false },
      splitLine: { show: false },
      axisLabel: { show: false },
      pointer: { show: false },
      anchor: { show: false },
      title: { offsetCenter: [0, '32%'], color: textColor.value, fontSize: 13 },
      detail: {
        valueAnimation: true,
        offsetCenter: [0, '-6%'],
        fontSize: 30,
        fontWeight: 'bolder',
        formatter: `{value}${props.suffix}`,
        color: textColor.value,
      },
      data: [{ value: props.value, name: props.label ?? '' }],
    },
  ],
}));
</script>

<template>
  <v-chart :option="option" :style="{ height }" autoresize />
</template>
