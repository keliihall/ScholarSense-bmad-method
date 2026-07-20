<script setup lang="ts">
import { ElAlert, ElButton } from 'element-plus';
import { RouterLink, RouterView } from 'vue-router';

import { useIdentityState } from './domains/identity-access';

const identity = useIdentityState();
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <p class="eyebrow">苏州大学 · 数智学工大系统</p>
        <h1>学林知微</h1>
        <p class="product-line">观澜智核</p>
      </div>
      <nav aria-label="学林知微导航">
        <RouterLink class="action-target" to="/">安全首页</RouterLink>
        <RouterLink class="action-target" to="/session">当前会话</RouterLink>
        <RouterLink class="action-target" to="/baseline">基线回归</RouterLink>
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
        {{ identity.authenticated ? '统一身份已由服务端确认' : '正在安全确认统一身份' }}
      </p>
      <RouterView />
    </main>

    <footer>
      <ElButton class="explicit-retry" type="primary" disabled>恢复身份与授权后由用户显式重试</ElButton>
    </footer>
  </div>
</template>
