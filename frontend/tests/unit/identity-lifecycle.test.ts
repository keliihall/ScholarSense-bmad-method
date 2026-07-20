import { QueryClient } from '@tanstack/vue-query';
import { describe, expect, it, vi } from 'vitest';

import { VolatileClientState } from '../../src/app/state/volatile-client-state';
import { IdentityLifecycleCoordinator } from '../../src/domains/identity-access';

describe('identity lifecycle teardown', () => {
  it.each(['logout', 'account-switch', 'refresh', 'host-session-invalid'] as const)(
    'clears all volatile boundaries on %s',
    (event) => {
      const query = new QueryClient();
      query.setQueryData(['identity-access', 'current-session', {}], { authenticated: true });
      const volatile = new VolatileClientState(query);
      volatile.setDraft('continuation-proof', { opaque: true });
      const clearIdentity = vi.fn();
      const clearReplayCache = vi.fn();
      const coordinator = new IdentityLifecycleCoordinator(
        volatile, clearIdentity, { clearReplayCache },
      );

      coordinator.clear(event);

      expect(query.getQueryCache().getAll()).toHaveLength(0);
      expect(volatile.draftCount).toBe(0);
      expect(clearIdentity).toHaveBeenCalledOnce();
      expect(clearReplayCache).toHaveBeenCalledOnce();
    },
  );
});
