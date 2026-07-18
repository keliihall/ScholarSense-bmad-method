import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { CurrentUser, RoleKey } from '@/types';
import { ROLES } from '@/utils/constants';
import { demoUsers } from '@/mock';

/**
 * 鉴权 store（纯前端原型 / Mock）。
 * 注意：真实环境鉴权一律走学校统一身份认证 CAS（M1），禁止自建账号密码。
 * 此处仅模拟「登录态 + 角色」，不含任何口令逻辑。
 */
export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<CurrentUser | null>(null);

  const isLoggedIn = computed(() => currentUser.value !== null);
  const role = computed<RoleKey>(() => currentUser.value?.role ?? 'counselor');
  const roleMeta = computed(() => ROLES[role.value]);
  const canViewSensitive = computed(() => roleMeta.value.canViewSensitive);
  const dataScope = computed(() => roleMeta.value.dataScope);

  /** 模拟 CAS 登录：选择一个角色身份进入 */
  function login(roleKey: RoleKey) {
    currentUser.value = { ...demoUsers[roleKey] };
  }

  /** 顶栏角色切换（演示用） */
  function switchRole(roleKey: RoleKey) {
    currentUser.value = { ...demoUsers[roleKey] };
  }

  function logout() {
    currentUser.value = null;
  }

  return {
    currentUser,
    isLoggedIn,
    role,
    roleMeta,
    canViewSensitive,
    dataScope,
    login,
    switchRole,
    logout,
  };
});
