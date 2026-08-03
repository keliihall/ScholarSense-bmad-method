<script setup lang="ts">
import { computed } from 'vue';
import { useAuthorizedShellState } from '../../domains/identity-access';

const authorization = useAuthorizedShellState();
const surface = computed(() => authorization.defaultSurface);
const providerCopy = computed(() => {
  if (surface.value?.providerState === 'available') return '当前工作台已就绪。';
  if (surface.value?.providerState === 'unavailable') return '当前工作台依赖暂时不可用，请稍后重试。';
  return '当前默认工作台尚未安装；系统不会用模拟待办或“0 条任务”冒充真实数据。';
});
</script>

<template>
  <section class="shell-card" aria-labelledby="shell-home-heading">
    <p class="eyebrow">安全首页</p>
    <h2 id="shell-home-heading" tabindex="-1">{{ surface?.title ?? '正在加载当前首页' }}</h2>
    <p>{{ providerCopy }}</p>
    <p v-if="authorization.status === 'degraded'" class="state-note" role="status">
      部分入口依赖暂时不可用；未就绪入口不会显示为可操作菜单。
    </p>
    <p v-if="authorization.menuItems.length === 0" class="state-note">
      当前没有已安装且可用的导航能力。这是能力未安装状态，不代表业务任务为零。
    </p>
  </section>
</template>
