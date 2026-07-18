<script setup lang="ts">
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { computed, ref } from 'vue';
import VChart from 'vue-echarts';


use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

const values = ref([2, 3, 5]);
const option = computed(() => ({
  animation: false,
  aria: { enabled: true, description: '基线 fixture：三个无业务含义的样本值。' },
  grid: { left: 24, right: 16, top: 16, bottom: 24, containLabel: true },
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: ['A', 'B', 'C'] },
  yAxis: { type: 'value' },
  series: [{ type: 'bar', data: values.value, color: '#AF251B' }],
}));
</script>

<template>
  <section class="baseline-card" aria-labelledby="compatibility-heading">
    <h2 id="compatibility-heading">图表与等价表格 fixture</h2>
    <p id="chart-status" role="status" aria-live="polite">图表与等价表格已同步更新。</p>
    <VChart class="baseline-chart" :option="option" autoresize aria-describedby="chart-status" />
    <table>
      <caption>图表等价数据</caption>
      <thead><tr><th scope="col">样本</th><th scope="col">值</th></tr></thead>
      <tbody>
        <tr v-for="(value, index) in values" :key="index">
          <th scope="row">{{ ['A', 'B', 'C'][index] }}</th>
          <td>{{ value }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
