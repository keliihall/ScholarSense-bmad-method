<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import BrandLogo from '@/components/BrandLogo.vue';

const router = useRouter();
const auth = useAuthStore();

function exitCockpit() {
  // 领导默认仅汇总视图；返回管理端按角色落到首个可见页
  router.push('/reports');
}
</script>

<template>
  <div class="cockpit">
    <header class="cockpit-head">
      <div class="left">
        <BrandLogo :size="28" />
        学林知微 · 观澜智核领导驾驶舱
      </div>
      <div class="center">学生成长关怀 · 态势研判与治理效能</div>
      <div class="right">
        <span class="tip">默认仅汇总 · 学生明细需授权下钻 · 全程留痕</span>
        <el-button size="small" round plain class="exit-btn" @click="exitCockpit">
          <el-icon><Back /></el-icon> 返回
        </el-button>
        <span class="who">{{ auth.currentUser?.name }}</span>
      </div>
    </header>
    <main class="cockpit-body">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.cockpit {
  height: 100vh;
  background:
    radial-gradient(1200px 500px at 50% -10%, rgba(41, 211, 194, 0.12), transparent),
    var(--ss-dark-bg);
  color: var(--ss-dark-text);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.cockpit-head {
  height: 58px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 22px;
  border-bottom: 1px solid var(--ss-dark-border);
  background: linear-gradient(180deg, rgba(15, 36, 64, 0.9), transparent);
}
.left {
  font-weight: 700;
  font-size: 17px;
  color: #fff;
  display: flex;
  align-items: center;
  gap: 8px;
}
.center {
  font-size: 14px;
  letter-spacing: 3px;
  color: #8fb6dd;
}
.right {
  justify-self: end;
  display: flex;
  align-items: center;
  gap: 14px;
}
.tip {
  font-size: 12px;
  color: #6f93ba;
}
.who {
  color: #cfe3f7;
  font-size: 13px;
}
.exit-btn {
  background: transparent;
  border-color: var(--ss-dark-border);
  color: var(--ss-dark-text);
}
.cockpit-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px 24px;
}
</style>
