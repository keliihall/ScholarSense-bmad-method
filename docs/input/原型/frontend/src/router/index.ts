import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import type { RoleKey } from '@/types';
import { useAuthStore } from '@/stores/auth';

/** 路由 meta：标题、图标、可见角色、菜单分组 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    icon?: string;
    roles?: RoleKey[];
    group?: string;
    hideInMenu?: boolean;
    layout?: 'admin' | 'cockpit' | 'blank';
  }
}

const ALL_BIZ: RoleKey[] = ['counselor', 'college', 'affairs', 'leader', 'collaborator'];

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/workbench' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '统一身份认证登录', hideInMenu: true, layout: 'blank' },
  },
  {
    path: '/cockpit',
    name: 'cockpit',
    component: () => import('@/views/cockpit/CockpitView.vue'),
    meta: {
      title: '领导驾驶舱',
      icon: 'DataLine',
      roles: ['leader', 'affairs'],
      group: '态势驾驶舱',
      layout: 'cockpit',
    },
  },
  // 工作台
  {
    path: '/workbench',
    name: 'workbench',
    component: () => import('@/views/workbench/WorkbenchView.vue'),
    meta: { title: '辅导员工作台', icon: 'Monitor', roles: ['counselor'], group: '关怀工作台' },
  },
  {
    path: '/clues',
    name: 'clues',
    component: () => import('@/views/clue/ClueListView.vue'),
    meta: {
      title: '线索列表',
      icon: 'List',
      roles: ['counselor', 'college', 'affairs'],
      group: '关怀工作台',
    },
  },
  {
    path: '/clues/:id',
    name: 'clue-detail',
    component: () => import('@/views/clue/ClueDetailView.vue'),
    meta: { title: '线索详情', roles: ['counselor', 'college', 'affairs'], hideInMenu: true },
  },
  {
    path: '/students/:id?',
    name: 'students',
    component: () => import('@/views/student/StudentProfileView.vue'),
    meta: {
      title: '一人一档',
      icon: 'User',
      roles: ['counselor', 'affairs', 'collaborator'],
      group: '关怀工作台',
    },
  },
  {
    path: '/referrals',
    name: 'referrals',
    component: () => import('@/views/collab/ReferralView.vue'),
    meta: { title: '协同转介', icon: 'Share', roles: ALL_BIZ, group: '关怀工作台' },
  },
  // 治理与运营
  {
    path: '/overview',
    name: 'overview',
    component: () => import('@/views/overview/OverviewView.vue'),
    meta: {
      title: '成长总览',
      icon: 'Histogram',
      roles: ['college', 'affairs', 'leader'],
      group: '治理与运营',
    },
  },
  {
    path: '/rules',
    name: 'rules',
    component: () => import('@/views/rule/RuleConfigView.vue'),
    meta: { title: '规则配置', icon: 'SetUp', roles: ['affairs'], group: '治理与运营' },
  },
  {
    path: '/data-quality',
    name: 'data-quality',
    component: () => import('@/views/dataquality/DataQualityView.vue'),
    meta: {
      title: '数据质量',
      icon: 'Coin',
      roles: ['affairs', 'ops', 'data'],
      group: '治理与运营',
    },
  },
  {
    path: '/reports',
    name: 'reports',
    component: () => import('@/views/report/ReportView.vue'),
    meta: {
      title: '周报月报',
      icon: 'Document',
      roles: ['affairs', 'college', 'leader'],
      group: '治理与运营',
    },
  },
  {
    path: '/tags',
    name: 'tags',
    component: () => import('@/views/tag/TagView.vue'),
    meta: {
      title: '学生标签体系',
      icon: 'CollectionTag',
      roles: ['affairs', 'collaborator'],
      group: '治理与运营',
    },
  },
  {
    path: '/ask',
    name: 'ask',
    component: () => import('@/views/ask/NlQueryView.vue'),
    meta: {
      title: '自然语言问数',
      icon: 'ChatDotRound',
      roles: ['affairs', 'leader'],
      group: '治理与运营',
    },
  },
  // 系统与合规
  {
    path: '/permission',
    name: 'permission',
    component: () => import('@/views/permission/PermissionView.vue'),
    meta: { title: '权限与脱敏', icon: 'Lock', roles: ['ops', 'affairs'], group: '系统与合规' },
  },
  {
    path: '/audit',
    name: 'audit',
    component: () => import('@/views/audit/AuditView.vue'),
    meta: { title: '审计留痕', icon: 'Tickets', roles: ['ops', 'affairs'], group: '系统与合规' },
  },
  {
    path: '/models',
    name: 'models',
    component: () => import('@/views/model/ModelGovernanceView.vue'),
    meta: {
      title: '模型与质量治理',
      icon: 'TrendCharts',
      roles: ['affairs', 'ops'],
      group: '系统与合规',
    },
  },
  {
    path: '/compliance',
    name: 'compliance',
    component: () => import('@/views/compliance/ComplianceView.vue'),
    meta: {
      title: '合规检查关卡',
      icon: 'CircleCheck',
      roles: ['affairs', 'ops'],
      group: '系统与合规',
    },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { hideInMenu: true, layout: 'blank' },
  },
];

const router = createRouter({
  history: createWebHistory('/scholarsense/'),
  routes,
});

// 登录守卫（纯前端模拟）
router.beforeEach((to) => {
  const auth = useAuthStore();
  if (to.name !== 'login' && !auth.isLoggedIn) {
    return { name: 'login' };
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { path: '/' };
  }
  return true;
});

export default router;

/** 供侧栏使用：按当前角色过滤出的菜单分组 */
export const MENU_GROUPS = ['关怀工作台', '态势驾驶舱', '治理与运营', '系统与合规'];

export function menuForRole(role: RoleKey) {
  const items = routes
    .filter((r) => r.meta && !r.meta.hideInMenu && r.meta.group && r.meta.roles?.includes(role))
    .map((r) => ({
      path: r.path,
      name: r.name as string,
      title: r.meta!.title!,
      icon: r.meta!.icon!,
      group: r.meta!.group!,
    }));
  return MENU_GROUPS.map((g) => ({ group: g, items: items.filter((i) => i.group === g) })).filter(
    (g) => g.items.length > 0,
  );
}
