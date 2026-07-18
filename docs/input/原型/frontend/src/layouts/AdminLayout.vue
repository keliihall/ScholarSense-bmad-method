<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { storeToRefs } from 'pinia';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useUiStore } from '@/stores/ui';
import BrandLogo from '@/components/BrandLogo.vue';
import { menuForRole } from '@/router';
import { ROLES, COMPLIANCE_BANNER } from '@/utils/constants';
import type { RoleKey } from '@/types';

const auth = useAuthStore();
const ui = useUiStore();
const route = useRoute();
const router = useRouter();
const { currentUser, role, roleMeta } = storeToRefs(auth);
const { sidebarCollapsed } = storeToRefs(ui);

const menus = computed(() => menuForRole(role.value));
const activePath = computed(() => '/' + (route.path.split('/')[1] ?? ''));
const pageTitle = computed(() => (route.meta.title as string) ?? '');

const roleOptions = (Object.keys(ROLES) as RoleKey[]).map((k) => ({
  key: k,
  name: ROLES[k].name,
  desc: ROLES[k].desc,
}));

function onSwitchRole(k: RoleKey) {
  auth.switchRole(k);
  ElMessage.success(`已切换为「${ROLES[k].name}」视角`);
  const first = menuForRole(k)[0]?.items[0];
  router.push(first ? first.path : '/');
}

function onLogout() {
  auth.logout();
  router.push('/login');
}
</script>

<template>
  <el-container class="admin">
    <el-aside :width="sidebarCollapsed ? '64px' : '230px'" class="aside">
      <div class="brand">
        <BrandLogo :size="34" />
        <div v-show="!sidebarCollapsed" class="brand-text">
          <div class="brand-name">学林知微</div>
          <div class="brand-sub">观澜智核 · 学生成长关怀</div>
        </div>
      </div>
      <el-scrollbar class="menu-scroll">
        <el-menu
          :collapse="sidebarCollapsed"
          :default-active="activePath"
          router
          :collapse-transition="false"
        >
          <template v-for="g in menus" :key="g.group">
            <div v-show="!sidebarCollapsed" class="menu-group">{{ g.group }}</div>
            <el-menu-item v-for="m in g.items" :key="m.name" :index="m.path.replace('/:id?', '')">
              <el-icon><component :is="m.icon" /></el-icon>
              <template #title>{{ m.title }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="ss-flex ss-gap-12">
          <el-icon class="collapse-btn" @click="ui.toggleSidebar">
            <Fold v-if="!sidebarCollapsed" />
            <Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ roleMeta.name }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="ss-flex ss-gap-12">
          <el-tag size="small" effect="plain" type="info">数据范围：{{ roleMeta.desc }}</el-tag>
          <el-dropdown trigger="click" @command="onSwitchRole">
            <span class="role-switch">
              <el-icon><Switch /></el-icon> 切换角色
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="r in roleOptions"
                  :key="r.key"
                  :command="r.key"
                  :disabled="r.key === role"
                >
                  <div class="role-opt">
                    <span class="role-opt-name">{{ r.name }}</span>
                    <span class="role-opt-desc">{{ r.desc }}</span>
                  </div>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click">
            <span class="ss-flex ss-gap-8 user">
              <el-avatar :size="30" class="avatar">{{ currentUser?.avatarText }}</el-avatar>
              <span>{{ currentUser?.name }}</span>
              <el-icon><CaretBottom /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled
                  >{{ currentUser?.id }} · {{ roleMeta.name }}</el-dropdown-item
                >
                <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <div class="banner">
        <el-icon><InfoFilled /></el-icon>
        <span>{{ COMPLIANCE_BANNER }}</span>
      </div>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin {
  height: 100vh;
}
.aside {
  background: #0f2440;
  transition: width 0.2s;
  display: flex;
  flex-direction: column;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 60px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.brand-name {
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  line-height: 1.2;
}
.brand-sub {
  color: #7fa8cf;
  font-size: 11px;
}
.menu-scroll {
  flex: 1;
}
.menu-group {
  color: #5e7ea0;
  font-size: 11px;
  padding: 14px 20px 6px;
  letter-spacing: 1px;
}
.aside :deep(.el-menu) {
  background: transparent;
  border-right: none;
}
.aside :deep(.el-menu-item) {
  color: #b9cbe0;
  height: 44px;
}
.aside :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}
.aside :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(41, 211, 194, 0.18), transparent);
  color: #29d3c2;
  border-right: 3px solid #29d3c2;
}
.topbar {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid var(--ss-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: var(--ss-text-muted);
}
.role-switch,
.user {
  cursor: pointer;
  color: var(--ss-text);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
}
.avatar {
  background: var(--ss-primary);
  color: #fff;
}
.role-opt {
  display: flex;
  flex-direction: column;
}
.role-opt-name {
  font-weight: 600;
}
.role-opt-desc {
  font-size: 12px;
  color: var(--ss-text-muted);
}
.banner {
  background: var(--ss-primary-soft);
  color: var(--ss-primary-dark);
  padding: 7px 20px;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid #d6ece8;
}
.main {
  background: var(--ss-bg);
  padding: 0;
  overflow-y: auto;
}
</style>
