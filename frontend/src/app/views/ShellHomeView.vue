<script setup lang="ts">
import { RouterLink } from 'vue-router';
</script>

<template>
  <section class="shell-card" aria-labelledby="shell-home-heading">
    <p class="eyebrow">安全首页</p>
    <h2 id="shell-home-heading" tabindex="-1">统一身份已确认</h2>
    <p>当前壳仅提供身份恢复与安全导航；角色菜单和业务对象将在后续 Story 接入。</p>
    <RouterLink class="action-target primary-action" to="/session">查看当前会话</RouterLink>
  </section>
</template>
