<script setup lang="ts">
import { ElAlert, ElButton } from 'element-plus';
import { computed, ref } from 'vue';
import { RouterLink, RouterView, useRouter } from 'vue-router';

import { useAuthorizedShellState, useIdentityState } from './domains/identity-access';

const identity = useIdentityState();
const authorization = useAuthorizedShellState();
const router = useRouter();
const navOpen = ref(false);
const menuItems = computed(() => authorization.menuItems
  .map((item) => ({ ...item, targetName: localRouteName(item.routeName) }))
  .filter((item) => item.providerState === 'available'
    && item.targetName !== undefined && router.hasRoute(item.targetName)));
const liveStatus = computed(() => {
  if (!identity.authenticated) return '正在安全确认统一身份';
  if (authorization.status === 'authorization-unavailable') return '授权依赖暂时不可用';
  if (authorization.status === 'degraded') return '统一身份已确认，部分能力暂时不可用';
  if (authorization.status === 'surface-forbidden') return '当前目标不在职责范围内';
  return '统一身份与当前授权已由服务端确认';
});

function localRouteName(routeId: string): string | undefined {
  return {
    'shell.home': 'shell-home',
    'shell.session': 'shell-session',
    'audit.search': 'audit-search',
    'data-quality.catalogs': 'data-quality-catalogs',
  }[routeId];
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <p class="eyebrow">苏州大学 · 数智学工大系统</p>
        <h1>学林知微</h1>
        <p class="product-line">观澜智核</p>
      </div>
      <button
        class="nav-toggle action-target"
        type="button"
        :aria-expanded="navOpen"
        aria-controls="authorized-navigation"
        @click="navOpen = !navOpen"
      >{{ navOpen ? '关闭导航' : '打开导航' }}</button>
      <nav id="authorized-navigation" :class="{ 'is-open': navOpen }" aria-label="当前授权导航">
        <RouterLink class="action-target" to="/" @click="navOpen = false">安全首页</RouterLink>
        <RouterLink
          v-for="item in menuItems"
          :key="item.id"
          class="action-target"
          :to="{ name: item.targetName }"
          @click="navOpen = false"
        >{{ item.label }}</RouterLink>
      </nav>
    </header>

    <main id="main-content" tabindex="-1">
      <ElAlert
        title="身份与目标只保存在当前内存会话；页面不会保存令牌、学生标识或敏感深链。"
        type="info"
        :closable="false"
        show-icon
      />
      <p class="identity-status" role="status" aria-live="polite">
        {{ liveStatus }}
      </p>
      <RouterView />
    </main>

    <footer>
      <ElButton class="explicit-retry" type="primary" disabled>恢复身份与授权后由用户显式重试</ElButton>
    </footer>
  </div>
</template>
