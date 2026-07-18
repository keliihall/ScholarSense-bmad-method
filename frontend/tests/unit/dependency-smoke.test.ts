import { QueryClient } from '@tanstack/vue-query';
import { BarChart } from 'echarts/charts';
import { use } from 'echarts/core';
import { ElAlert, ElButton } from 'element-plus';
import { createPinia } from 'pinia';
import { createApp, defineComponent } from 'vue';
import VChart from 'vue-echarts';
import { createMemoryHistory, createRouter } from 'vue-router';
import { describe, expect, it } from 'vitest';
import typescriptPackage from 'typescript/package.json';
import compilerPackage from '@typescript/old/package.json';


describe('PAB frontend dependency integration', () => {
  it('loads the approved runtime integration points without an implicit old compiler', () => {
    use([BarChart]);
    const app = createApp(defineComponent({ template: '<main />' }));
    const router = createRouter({ history: createMemoryHistory('/scholarsense/'), routes: [] });

    app.use(createPinia());
    app.use(router);
    app.component('ElAlert', ElAlert);
    app.component('ElButton', ElButton);
    app.component('VChart', VChart);

    expect(new QueryClient()).toBeDefined();
    expect(app).toBeDefined();
    expect(typescriptPackage).toMatchObject({ name: '@typescript/typescript6', version: '6.0.2' });
    expect(compilerPackage).toMatchObject({ name: 'typescript', version: '6.0.2' });
  });
});
