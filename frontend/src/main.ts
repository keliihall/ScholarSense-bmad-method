import { VueQueryPlugin } from '@tanstack/vue-query';
import 'element-plus/es/components/alert/style/css';
import 'element-plus/es/components/base/style/css';
import 'element-plus/es/components/button/style/css';
import { createPinia } from 'pinia';
import { createApp } from 'vue';

import App from './App.vue';
import { queryClient } from './app/state/query-client';
import { router } from './app/router';
import './app/theme/styles.css';
import { startRuntimeHostBridge } from './domains/identity-access';


const pinia = createPinia();
createApp(App)
  .use(pinia)
  .use(router)
  .use(VueQueryPlugin, { queryClient })
  .mount('#app');

document.querySelector<HTMLAnchorElement>('.skip-link')?.addEventListener('click', (event) => {
  event.preventDefault();
  const main = document.querySelector<HTMLElement>('#main-content');
  main?.focus({ preventScroll: true });
  main?.scrollIntoView({ block: 'start' });
});

void startRuntimeHostBridge(router, pinia);
