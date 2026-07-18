import { QueryClient } from '@tanstack/vue-query';


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
