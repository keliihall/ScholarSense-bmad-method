import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

import type { CurrentAuthorizedShell } from './authorized-shell-client';

export type AuthorizedShellStatus =
  | 'loading'
  | 'ready'
  | 'degraded'
  | 'surface-forbidden'
  | 'authorization-unavailable';

export const useAuthorizedShellState = defineStore('identity-authorized-shell', () => {
  const current = ref<CurrentAuthorizedShell>();
  const status = ref<AuthorizedShellStatus>('loading');
  const menuItems = computed(() => current.value?.menuItems ?? []);
  const defaultSurface = computed(() => current.value?.defaultSurface);

  function accept(value: CurrentAuthorizedShell): boolean {
    const previousSignature = current.value === undefined ? undefined : signature(current.value);
    const nextSignature = signature(value);
    current.value = value;
    status.value = value.dependencyStatus === 'unavailable' ? 'degraded' : 'ready';
    return previousSignature !== undefined && previousSignature !== nextSignature;
  }

  function canNavigate(routeName: string): boolean {
    if (current.value === undefined) return false;
    return routeName === current.value.defaultSurface.routeName
      || current.value.menuItems.some((item) =>
        item.routeName === routeName && item.providerState === 'available');
  }

  function clear(nextStatus: AuthorizedShellStatus = 'loading'): void {
    current.value = undefined;
    status.value = nextStatus;
  }

  function rejectSurface(): void {
    status.value = 'surface-forbidden';
  }

  return {
    current, status, menuItems, defaultSurface,
    accept, canNavigate, clear, rejectSurface,
  };
});

function signature(value: CurrentAuthorizedShell): string {
  return JSON.stringify({
    schemaVersion: value.schemaVersion,
    policyVersion: value.policyVersion,
    fixtureVersion: value.fixtureVersion,
    defaultSurface: value.defaultSurface,
    menuItems: value.menuItems,
    entryCapabilities: value.entryCapabilities,
    actionCapabilities: value.actionCapabilities,
    dependencyStatus: value.dependencyStatus,
  });
}
