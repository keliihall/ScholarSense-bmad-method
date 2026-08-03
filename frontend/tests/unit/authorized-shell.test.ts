import { QueryClient } from '@tanstack/vue-query';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  AuthorizedShellClient,
  authorizedShellQueryOptions,
  fetchCurrentAuthorizedShell,
  useAuthorizedShellState,
} from '../../src/domains/identity-access';

const shell = {
  schemaVersion: 'AUTHORIZED-SHELL-1.0.0',
  policyVersion: 'RFP-1.0.0',
  fixtureVersion: 'RFP-FIXTURE-1.0.0',
  evaluatedAt: '2026-08-01T00:00:00Z',
  defaultSurface: {
    surfaceId: 'care-workbench', title: '关怀工作台',
    routeName: 'shell.home', providerState: 'not-installed',
  },
  menuItems: [{
    id: 'identity-session', label: '当前会话',
    routeName: 'shell.session', providerState: 'available',
  }],
  entryCapabilities: [
    { id: 'identity-session', state: 'available' },
    { id: 'care-workbench', state: 'not-installed' },
  ],
  dependencyStatus: 'available',
} as const;

beforeEach(() => setActivePinia(createPinia()));

describe('current authorized shell', () => {
  it('accepts only the frozen response and includes browser credentials', async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(shell), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    const client = new AuthorizedShellClient(request);

    await expect(client.current()).resolves.toEqual(shell);
    expect(request).toHaveBeenCalledWith('/api/v1/authorized-shell', expect.objectContaining({
      credentials: 'include',
      cache: 'no-store',
    }));

    for (const invalid of [
      { ...shell, roles: ['R1'] },
      { ...shell, allowToken: 'replayable' },
      { ...shell, menuItems: [{ ...shell.menuItems[0], id: 'identity.session' }] },
    ]) {
      const rejected = new AuthorizedShellClient(vi.fn().mockResolvedValue(
        new Response(JSON.stringify(invalid), { status: 200 }),
      ));
      await expect(rejected.current()).rejects.toThrow('IDENTITY_AUTHORIZED_SHELL_RESPONSE_INVALID');
    }
  });

  it('uses a staleTime-zero, memory-only query and refetches current evidence', async () => {
    const current = vi.fn().mockResolvedValue(shell);
    const options = authorizedShellQueryOptions({ current });
    const query = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    expect(options.staleTime).toBe(0);
    expect(options.queryKey).toEqual(['identity-access', 'authorized-shell', {}]);
    await fetchCurrentAuthorizedShell(query, { current });
    await fetchCurrentAuthorizedShell(query, { current });
    expect(current).toHaveBeenCalledTimes(2);
    expect(browserStorageNameInSource(options)).toBe(false);
  });

  it('keeps one server projection, authorizes only projected routes and detects version change', () => {
    const state = useAuthorizedShellState();

    expect(state.accept(shell)).toBe(false);
    expect(state.canNavigate('shell.home')).toBe(true);
    expect(state.canNavigate('shell.session')).toBe(true);
    expect(state.canNavigate('audit.search')).toBe(false);
    expect(state.accept({
      ...shell,
      menuItems: [...shell.menuItems, {
        id: 'audit-search', label: '审计检索',
        routeName: 'audit.search', providerState: 'available' as const,
      }],
    })).toBe(true);

    state.clear('authorization-unavailable');
    expect(state.current).toBeUndefined();
    expect(state.status).toBe('authorization-unavailable');
  });

  it('contains no browser persistence or persistent-store binding in the authorization boundary', () => {
    const modules = import.meta.glob(
      '../../src/domains/identity-access/internal/authorization/*.ts',
      { eager: true, query: '?raw', import: 'default' },
    ) as Record<string, string>;
    const source = Object.values(modules).join('\n');

    const forbiddenPersistenceApis = [
      ['local', 'Storage'].join(''),
      ['session', 'Storage'].join(''),
      ['indexed', 'DB'].join(''),
      ['caches', '.'].join(''),
      ['service', 'Worker'].join(''),
    ];
    expect(forbiddenPersistenceApis.some((api) => source.includes(api))).toBe(false);
    expect(source).not.toMatch(/persist(?:ed|ence)?\s*[:=(]/i);
  });
});

function browserStorageNameInSource(value: unknown): boolean {
  const serialized = JSON.stringify(value);
  return serialized.includes(['local', 'Storage'].join(''))
    || serialized.includes(['session', 'Storage'].join(''));
}
