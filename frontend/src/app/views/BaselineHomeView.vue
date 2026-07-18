<script setup lang="ts">
import { computed } from 'vue';

import { useConnectivityStore } from '../state/connectivity-store';


const connectivity = useConnectivityStore();
const status = computed(() => connectivity.online ? '基线连接可用' : '网络不可用，请恢复连接后显式重试。');
</script>

<template>
  <section class="baseline-card" aria-labelledby="baseline-heading">
    <h2 id="baseline-heading">生产启动面已建立</h2>
    <p>路由、内存状态和服务端 query provider 已接入；业务页由后续 Story 交付。</p>
    <p class="network-status" role="status" aria-live="polite">{{ status }}</p>
  </section>
</template>
