import type { Page } from '@playwright/test';

export const authorizedShell = Object.freeze({
  schemaVersion: 'AUTHORIZED-SHELL-1.0.0',
  policyVersion: 'RFP-1.0.0',
  fixtureVersion: 'RFP-FIXTURE-1.0.0',
  evaluatedAt: '2026-08-01T00:00:00Z',
  defaultSurface: Object.freeze({
    surfaceId: 'care-workbench',
    title: '关怀工作台',
    routeName: 'shell.home',
    providerState: 'not-installed',
  }),
  menuItems: Object.freeze([
    Object.freeze({
      id: 'identity-session', label: '当前会话',
      routeName: 'shell.session', providerState: 'available',
    }),
    Object.freeze({
      id: 'audit-search', label: '审计检索',
      routeName: 'audit.search', providerState: 'available',
    }),
  ]),
  entryCapabilities: Object.freeze([
    Object.freeze({ id: 'identity-session', state: 'available' }),
    Object.freeze({ id: 'audit-search', state: 'available' }),
    Object.freeze({ id: 'care-workbench', state: 'not-installed' }),
  ]),
  dependencyStatus: 'available',
});

export async function installAuthorizedShellRoute(
  page: Page,
  value: unknown = authorizedShell,
): Promise<void> {
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(value),
    headers: { 'Cache-Control': 'no-store, no-cache' },
  }));
}
