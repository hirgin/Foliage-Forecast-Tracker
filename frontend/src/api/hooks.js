import { useQuery } from '@tanstack/react-query';
import { fetchMeta, fetchCells } from './client';

export function useMeta() {
  return useQuery({
    queryKey: ['meta'],
    queryFn: fetchMeta,
    retry: false,
    refetchInterval: 10_000,
  });
}

export function useCells(state, minCanopy) {
  return useQuery({
    queryKey: ['cells', state, minCanopy],
    queryFn: () => fetchCells(state, minCanopy),
    retry: false,
    // The grid only changes when a bootstrap runs.
    staleTime: Infinity,
  });
}
