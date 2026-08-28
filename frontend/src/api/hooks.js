import { useQuery } from '@tanstack/react-query';
import { fetchMeta } from './client';

export function useMeta() {
  return useQuery({
    queryKey: ['meta'],
    queryFn: fetchMeta,
    retry: false,
    refetchInterval: 10_000,
  });
}
