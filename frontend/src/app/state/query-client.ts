import { QueryClient } from '@tanstack/vue-query';
import { VolatileClientState } from './volatile-client-state';


export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      networkMode: 'online',
      retry: false,
      staleTime: 0,
      gcTime: 5 * 60 * 1000,
    },
    mutations: {
      networkMode: 'online',
      retry: false,
    },
  },
});

export const volatileClientState = new VolatileClientState(queryClient);
