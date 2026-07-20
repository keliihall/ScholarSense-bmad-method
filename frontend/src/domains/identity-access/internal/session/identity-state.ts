import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

import type { CurrentIdentitySession } from './identity-session-client';
import type { ReauthenticationTarget } from './identity-session-client';

export type ShellState =
  | 'loading'
  | 'ready'
  | 'warning'
  | 'session-expired'
  | 'network-failure'
  | 'host-degraded'
  | 'unauthorized';

export const useIdentityState = defineStore('identity-access', () => {
  const session = ref<CurrentIdentitySession>();
  const pendingReauthentication = ref<Readonly<{
    targetRouteId: ReauthenticationTarget;
    authorizationUri: string;
  }>>();
  const shellState = ref<ShellState>('loading');
  const hostReady = ref(false);

  const authenticated = computed(() => session.value?.authenticated === true);

  function acceptSession(value: CurrentIdentitySession): void {
    session.value = value;
    pendingReauthentication.value = undefined;
    shellState.value = Date.parse(value.warningAt) <= Date.now() ? 'warning' : 'ready';
  }

  function fail(code: string): void {
    session.value = undefined;
    pendingReauthentication.value = undefined;
    shellState.value = code === 'IDENTITY_SESSION_REQUIRED' || code === 'IDENTITY_SESSION_EXPIRED'
      ? 'session-expired'
      : code === 'IDENTITY_AUTHORIZATION_REQUIRED' ? 'unauthorized' : 'network-failure';
  }

  function setHostReady(value: boolean): void {
    hostReady.value = value;
    if (!value && shellState.value === 'ready') shellState.value = 'host-degraded';
  }

  function degradeHost(): void {
    hostReady.value = false;
    shellState.value = 'host-degraded';
  }

  function prepareReauthentication(
    targetRouteId: ReauthenticationTarget,
    authorizationUri: string,
  ): void {
    pendingReauthentication.value = Object.freeze({ targetRouteId, authorizationUri });
  }

  function clear(): void {
    session.value = undefined;
    pendingReauthentication.value = undefined;
    hostReady.value = false;
    shellState.value = 'session-expired';
  }

  return {
    session,
    pendingReauthentication,
    shellState,
    hostReady,
    authenticated,
    acceptSession,
    fail,
    setHostReady,
    degradeHost,
    prepareReauthentication,
    clear,
  };
});
