<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();

function goHome() {
  router.push('/');
}
</script>

<template>
  <div class="nf-wrap">
    <div class="nf-card">
      <div class="nf-brand">
        <el-icon class="nf-brand-icon"><Compass /></el-icon>
        <span>学林知微 · 观澜智核</span>
      </div>

      <div class="nf-code">404</div>
      <h1 class="nf-title">页面不存在或无访问权限</h1>
      <p class="nf-sub">
        您访问的页面不存在，或当前角色未被授权查看。系统遵循「授权可见而非全员可查」，如确需访问请联系管理员授权。
      </p>

      <div class="nf-actions">
        <el-button type="primary" size="large" :icon="'HomeFilled'" @click="goHome"
          >返回首页</el-button
        >
        <el-button size="large" :icon="'Back'" plain @click="router.back()">返回上一页</el-button>
      </div>

      <p class="nf-foot">
        <el-icon><Lock /></el-icon>
        线索而非结论 · 建议而非自动决策 · 授权可见而非全员可查 · 全程留痕
      </p>
    </div>
  </div>
</template>

<style scoped>
.nf-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(900px 480px at 50% -10%, var(--ss-primary-soft) 0%, transparent 60%),
    var(--ss-bg);
}
.nf-card {
  width: 100%;
  max-width: 520px;
  text-align: center;
  background: var(--ss-card);
  border: 1px solid var(--ss-border);
  border-radius: var(--ss-radius);
  box-shadow: var(--ss-shadow);
  padding: 40px 36px 28px;
}
.nf-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ss-primary);
  margin-bottom: 8px;
}
.nf-brand-icon {
  font-size: 18px;
}
.nf-code {
  font-size: 96px;
  line-height: 1.1;
  font-weight: 800;
  letter-spacing: 4px;
  background: linear-gradient(135deg, var(--ss-primary) 0%, var(--ss-accent) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.nf-title {
  margin: 4px 0 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--ss-text);
}
.nf-sub {
  margin: 12px auto 0;
  max-width: 420px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ss-text-muted);
}
.nf-actions {
  margin-top: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
}
.nf-foot {
  margin: 28px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--ss-border);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ss-text-muted);
}
</style>
