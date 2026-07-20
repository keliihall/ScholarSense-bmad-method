import { createRouter, createWebHistory } from 'vue-router';
import { nextTick } from 'vue';

import { IdentitySessionClient, useIdentityState } from '../../domains/identity-access';
import type { ReauthenticationTarget } from '../../domains/identity-access';


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
      name: 'shell-home',
      component: () => import('../views/ShellHomeView.vue'),
      meta: { requiresIdentity: true },
    },
    {
      path: '/baseline',
      name: 'baseline-home',
      component: () => import('../views/BaselineHomeView.vue'),
    },
    {
      path: '/compatibility',
      name: 'baseline-compatibility',
      component: () => import('../views/BaselineCompatibilityView.vue'),
    },
    {
      path: '/session',
      name: 'shell-session',
      component: () => import('../views/ShellSessionView.vue'),
      meta: { requiresIdentity: true },
    },
    {
      path: '/recovery',
      name: 'shell-recovery',
      component: () => import('../views/ShellRecoveryView.vue'),
    },
  ],
});

const identityClient = new IdentitySessionClient();

router.beforeEach(async (to) => {
  if (to.meta.requiresIdentity !== true) return true;
  const identity = useIdentityState();
  try {
    identity.acceptSession(await identityClient.current());
    return true;
  } catch (failure) {
    const code = failure instanceof Error ? failure.message : 'IDENTITY_DEPENDENCY_UNAVAILABLE';
    identity.fail(code);
    const targetRouteId = protectedTarget(to.name);
    if (identity.shellState === 'session-expired' && targetRouteId !== undefined) {
      try {
        const csrf = await identityClient.csrfProof();
        const authorizationUri = await identityClient.createReauthentication(
          targetRouteId, window.location.origin, csrf,
        );
        identity.prepareReauthentication(targetRouteId, authorizationUri);
      } catch {
        // The persistent recovery panel remains available for an explicit retry.
      }
    }
    return to.name === 'shell-recovery'
      ? true
      : {
          name: 'shell-recovery',
          query: {
            reason: identity.shellState,
            ...(targetRouteId === undefined ? {} : { targetRouteId }),
          },
        };
  }
});

router.afterEach(async () => {
  await nextTick();
  const heading = document.querySelector<HTMLElement>('main h2, main h1');
  heading?.focus({ preventScroll: true });
});

function protectedTarget(routeName: unknown): ReauthenticationTarget | undefined {
  if (routeName === 'shell-session') return 'shell.session';
  if (routeName === 'shell-home') return 'shell.home';
  return undefined;
}
