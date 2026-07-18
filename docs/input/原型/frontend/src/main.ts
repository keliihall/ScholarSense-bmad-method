import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import 'element-plus/dist/index.css';

// ECharts 按需注册（vue-echarts v7）
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, GaugeChart, LineChart, PieChart, RadarChart } from 'echarts/charts';
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  PolarComponent,
  DataZoomComponent,
} from 'echarts/components';

import App from './App.vue';
import router from './router';
import './styles/global.css';

use([
  CanvasRenderer,
  BarChart,
  LineChart,
  PieChart,
  GaugeChart,
  RadarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  PolarComponent,
  DataZoomComponent,
]);

const app = createApp(App);

// 全局注册 Element Plus 图标
for (const [key, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, comp);
}

app.use(createPinia());
app.use(router);
app.use(ElementPlus, { locale: zhCn });

// M0/门户集成说明：本期为独立可运行原型，直接挂载。
// 接入统一门户（qiankun / 无界）时，在此补充 bootstrap/mount/unmount 生命周期。
app.mount('#app');
