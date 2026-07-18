import { createRouter, createWebHistory } from 'vue-router';


/** Transport-neutral route contributions; domains may only expose routes through their public entry. */
export type RouteContribution = Readonly<{
  domain: string;
  routes: readonly unknown[];
}>;

export const routeContributions: readonly RouteContribution[] = Object.freeze([]);

export const router = createRouter({
  history: createWebHistory('/scholarsense/'),
  routes: [
    {
      path: '/',
      name: 'baseline-home',
      component: () => import('../views/BaselineHomeView.vue'),
    },
    {
      path: '/compatibility',
      name: 'baseline-compatibility',
      component: () => import('../views/BaselineCompatibilityView.vue'),
    },
  ],
});
