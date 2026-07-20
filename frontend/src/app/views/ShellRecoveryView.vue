<script setup lang="ts">
import { ElButton } from 'element-plus';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { IdentitySessionClient, useIdentityState } from '../../domains/identity-access';
import type { ReauthenticationTarget } from '../../domains/identity-access';

const route = useRoute();
const router = useRouter();
const identity = useIdentityState();
const client = new IdentitySessionClient();
const reason = computed(() => typeof route.query.reason === 'string' ? route.query.reason : 'session-expired');
const targetRouteId = computed<ReauthenticationTarget>(() =>
  route.query.targetRouteId === 'shell.session' ? 'shell.session' : 'shell.home');
const copy = computed(() => ({
  'session-expired': ['会话已失效', '当前身份无法确认。', '重新认证'],
  'network-failure': ['网络连接失败', '暂时无法核验当前身份。', '检查连接'],
  'host-degraded': ['门户宿主降级', '门户握手未完成，业务命令已停用。', '重新连接门户'],
  unauthorized: ['暂时无法访问', '当前目标不可用。', '返回安全首页'],
}[reason.value] ?? ['身份依赖不可用', '当前身份无法确认。', '重新认证']));

async function recover(): Promise<void> {
  if (reason.value === 'unauthorized') {
    await router.replace('/');
    return;
  }
  if (reason.value === 'network-failure') {
    window.location.reload();
    return;
  }
  if (reason.value === 'host-degraded') {
    window.location.reload();
    return;
  }
  try {
    const prepared = identity.pendingReauthentication;
    const authorizationUri = prepared?.targetRouteId === targetRouteId.value
      ? prepared.authorizationUri
      : await client.createReauthentication(
          targetRouteId.value,
          window.location.origin,
          await client.csrfProof(),
        );
    window.location.assign(authorizationUri);
  } catch {
    identity.fail('IDENTITY_DEPENDENCY_UNAVAILABLE');
    await router.replace({
      name: 'shell-recovery',
      query: { reason: 'network-failure', targetRouteId: targetRouteId.value },
    });
  }
}
</script>

<template>
  <section class="state-panel" role="status" aria-live="polite" aria-labelledby="recovery-heading">
    <h2 id="recovery-heading" tabindex="-1">{{ copy[0] }}</h2>
    <p>{{ copy[1] }} 系统不会自动重放上一条命令。</p>
    <ElButton class="action-target" type="primary" @click="recover">{{ copy[2] }}</ElButton>
  </section>
</template>
