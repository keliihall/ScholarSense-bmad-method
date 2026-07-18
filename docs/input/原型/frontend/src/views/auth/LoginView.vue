<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import BrandLogo from '@/components/BrandLogo.vue';
import { menuForRole } from '@/router';
import { ROLES } from '@/utils/constants';
import type { RoleKey } from '@/types';

const router = useRouter();
const auth = useAuthStore();

const beliefs = [
  { icon: 'Search', title: '线索而非结论', desc: '输出需人工核实的线索，不替代辅导员判断。' },
  {
    icon: 'MagicStick',
    title: '建议而非自动决策',
    desc: '仅给出关怀建议，重大不利决定不交给模型。',
  },
  { icon: 'Lock', title: '授权可见而非全员可查', desc: '最小授权、字段脱敏，敏感信息按权限可见。' },
];

const roleList = (Object.keys(ROLES) as RoleKey[]).map((key) => ({ key, ...ROLES[key] }));

/** 角色登录后的首个可见页（领导优先驾驶舱，其余取菜单首项，兜底 /） */
function landingFor(roleKey: RoleKey): string {
  if (roleKey === 'leader') {
    return '/cockpit';
  }
  const groups = menuForRole(roleKey);
  const first = groups[0]?.items[0];
  return first?.path ?? '/';
}

function casLogin() {
  ElMessage.info('正在跳转学校统一身份认证（CAS）… 此为前端原型，请使用下方角色入口体验');
}

function enter(roleKey: RoleKey) {
  auth.login(roleKey);
  ElMessage.success(
    `已以「${ROLES[roleKey].name}」身份进入，登录操作已留痕（操作人 / 时间 / 来源IP / traceId）`,
  );
  router.push(landingFor(roleKey));
}
</script>

<template>
  <div class="login-wrap">
    <!-- 左侧品牌区 -->
    <section class="brand">
      <div class="brand-inner">
        <div class="logo">
          <BrandLogo :size="40" />
          <span class="logo-text">学林知微 · 观澜智核</span>
        </div>
        <h1 class="brand-title">从细微变化中，<br />把关爱更早送到</h1>
        <p class="brand-sub">数智学工平台 · 学生成长关怀模块 —— 让需要被看见的同学，更早被关心。</p>

        <ul class="beliefs">
          <li v-for="b in beliefs" :key="b.title">
            <span class="belief-ic"
              ><el-icon><component :is="b.icon" /></el-icon
            ></span>
            <div class="belief-body">
              <div class="belief-title">{{ b.title }}</div>
              <div class="belief-desc">{{ b.desc }}</div>
            </div>
          </li>
        </ul>

        <p class="brand-foot">苏州大学 · 学生成长关怀平台 · 此非监控系统</p>
      </div>
    </section>

    <!-- 右侧登录区 -->
    <section class="panel">
      <div class="login-card">
        <div class="lc-head">
          <div class="lc-title">统一身份认证登录</div>
          <div class="lc-sub">本系统不自建账号密码，身份由学校 CAS 统一管控</div>
        </div>

        <el-button class="cas-btn" type="primary" size="large" :icon="'Key'" @click="casLogin">
          通过校园统一身份认证登录（CAS）
        </el-button>

        <div class="divider"><span>角色演示入口</span></div>
        <p class="role-hint">前端原型 · 选择一个角色身份直接体验对应视图与权限范围</p>

        <div class="role-grid">
          <button
            v-for="r in roleList"
            :key="r.key"
            class="role-item ss-hover"
            type="button"
            @click="enter(r.key)"
          >
            <div class="role-top">
              <span class="role-name">{{ r.name }}</span>
              <el-icon class="role-arrow"><Right /></el-icon>
            </div>
            <div class="role-desc">{{ r.desc }}</div>
          </button>
        </div>

        <p class="lc-note">
          本系统不自建账号密码，身份由学校 CAS 统一管控；此为前端原型，角色入口仅供演示与评审。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
}

/* 左侧品牌 */
.brand {
  position: relative;
  color: #fff;
  background: linear-gradient(135deg, #16725f 0%, #1b8f7a 45%, #2f6db0 100%);
  overflow: hidden;
  display: flex;
  align-items: center;
}
.brand::before {
  content: '';
  position: absolute;
  width: 460px;
  height: 460px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  top: -160px;
  right: -120px;
}
.brand::after {
  content: '';
  position: absolute;
  width: 320px;
  height: 320px;
  border-radius: 50%;
  background: rgba(41, 211, 194, 0.18);
  bottom: -120px;
  left: -90px;
}
.brand-inner {
  position: relative;
  z-index: 1;
  padding: 56px 60px;
  max-width: 560px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.logo-text {
  font-size: 18px;
}
.brand-title {
  font-size: 40px;
  line-height: 1.25;
  font-weight: 800;
  margin: 36px 0 16px;
}
.brand-sub {
  font-size: 15px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.88);
  margin: 0 0 40px;
}
.beliefs {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.beliefs li {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.belief-ic {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.16);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}
.belief-title {
  font-size: 16px;
  font-weight: 700;
}
.belief-desc {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.82);
  margin-top: 2px;
  line-height: 1.6;
}
.brand-foot {
  margin: 48px 0 0;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
}

/* 右侧登录 */
.panel {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ss-bg);
  padding: 40px 24px;
}
.login-card {
  width: 100%;
  max-width: 440px;
  background: var(--ss-card);
  border: 1px solid var(--ss-border);
  border-radius: 16px;
  box-shadow: var(--ss-shadow);
  padding: 36px 34px 30px;
}
.lc-head {
  text-align: center;
  margin-bottom: 24px;
}
.lc-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--ss-text);
}
.lc-sub {
  font-size: 13px;
  color: var(--ss-text-muted);
  margin-top: 6px;
}
.cas-btn {
  width: 100%;
  height: 50px;
  font-size: 15px;
  font-weight: 600;
}
.divider {
  position: relative;
  text-align: center;
  margin: 26px 0 4px;
}
.divider::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--ss-border);
}
.divider span {
  position: relative;
  background: var(--ss-card);
  padding: 0 12px;
  font-size: 13px;
  color: var(--ss-text-muted);
}
.role-hint {
  text-align: center;
  font-size: 12px;
  color: var(--ss-text-muted);
  margin: 6px 0 16px;
}
.role-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.role-item {
  text-align: left;
  background: var(--ss-primary-soft);
  border: 1px solid var(--ss-border);
  border-radius: 10px;
  padding: 11px 13px;
  cursor: pointer;
  transition: all 0.2s;
}
.role-item:hover {
  border-color: var(--ss-primary);
}
.role-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.role-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ss-text);
}
.role-arrow {
  color: var(--ss-primary);
  font-size: 14px;
}
.role-desc {
  font-size: 12px;
  color: var(--ss-text-muted);
  margin-top: 4px;
  line-height: 1.5;
}
.lc-note {
  font-size: 12px;
  color: var(--ss-text-muted);
  line-height: 1.7;
  margin: 22px 0 0;
  text-align: center;
}

@media (max-width: 880px) {
  .login-wrap {
    grid-template-columns: 1fr;
  }
  .brand {
    display: none;
  }
}
</style>
