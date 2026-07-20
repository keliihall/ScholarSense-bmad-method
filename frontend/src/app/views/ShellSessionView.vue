<script setup lang="ts">
import { ElButton } from 'element-plus';

import { useIdentityState } from '../../domains/identity-access';

const identity = useIdentityState();
</script>

<template>
  <section class="shell-card" aria-labelledby="shell-session-heading">
    <p class="eyebrow">安全会话</p>
    <h2 id="shell-session-heading" tabindex="-1">当前统一身份会话</h2>
    <p>本页只呈现服务端确认的脱敏会话事实，不包含令牌、学号或业务对象。</p>
    <dl v-if="identity.session" class="session-facts">
      <div><dt>会话状态</dt><dd>已认证</dd></div>
      <div><dt>会话版本</dt><dd>{{ identity.session.sessionVersion }}</dd></div>
      <div><dt>到期提醒</dt><dd>{{ identity.session.warningAt }}</dd></div>
    </dl>
    <ElButton class="action-target" type="primary">重新检查会话</ElButton>
  </section>
</template>
