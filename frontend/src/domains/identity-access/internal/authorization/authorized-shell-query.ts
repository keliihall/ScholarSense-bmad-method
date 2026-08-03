import { queryOptions } from '@tanstack/vue-query';
import type { QueryClient } from '@tanstack/vue-query';

import { createQueryKey } from '../../../../app/state/volatile-client-state';
import type { AuthorizedShellClient, CurrentAuthorizedShell } from './authorized-shell-client';

export const authorizedShellQueryKey = createQueryKey(
  'identity-access', 'authorized-shell', Object.freeze({}),
);

export function authorizedShellQueryOptions(
  client: Pick<AuthorizedShellClient, 'current'>,
) {
  return queryOptions({
    queryKey: authorizedShellQueryKey,
    queryFn: ({ signal }) => client.current(signal),
    staleTime: 0,
    gcTime: 5 * 60 * 1000,
    retry: false,
    networkMode: 'online',
  });
}

export function fetchCurrentAuthorizedShell(
  queryClient: QueryClient,
  client: Pick<AuthorizedShellClient, 'current'>,
): Promise<CurrentAuthorizedShell> {
  const narrowed = queryClient as unknown as Readonly<{
    fetchQuery: (
      options: ReturnType<typeof authorizedShellQueryOptions>,
    ) => Promise<CurrentAuthorizedShell>;
  }>;
  return narrowed.fetchQuery(authorizedShellQueryOptions(client));
}
