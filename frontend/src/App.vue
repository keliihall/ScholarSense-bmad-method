<script setup lang="ts">
import { ElAlert, ElButton } from 'element-plus';
import { RouterLink, RouterView } from 'vue-router';
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <p class="eyebrow">FPB-1.0.0</p>
        <h1>ScholarSense 前端基线</h1>
      </div>
      <nav aria-label="基线验证导航">
        <RouterLink class="action-target" to="/">启动面</RouterLink>
        <RouterLink class="action-target" to="/compatibility">兼容 fixture</RouterLink>
      </nav>
    </header>

    <main id="main-content" tabindex="-1">
      <ElAlert
        title="本页只验证生产前端组合，不含学生对象、SSO 或业务 API。"
        type="info"
        :closable="false"
        show-icon
      />
      <RouterView />
    </main>

    <footer>
      <ElButton class="explicit-retry" type="primary" disabled>连接恢复后显式重试</ElButton>
    </footer>
  </div>
</template>
