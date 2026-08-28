import { useQuery } from '@tanstack/react-query';
import { fetchMeta, fetchForecast, fetchTimeline, fetchExplain, fetchPlaces } from './client';

export function useMeta() {
  return useQuery({
    queryKey: ['meta'],
    queryFn: fetchMeta,
    retry: false,
    staleTime: 60_000,
  });
}

export function useForecast(date) {
  return useQuery({
    queryKey: ['forecast', date],
    queryFn: () => fetchForecast(date),
    enabled: Boolean(date),
    retry: false,
    // Scrubbing the slider revisits dates constantly; keeping them resident
    // is what makes dragging feel instant after the first pass.
    staleTime: Infinity,
    placeholderData: (previous) => previous,
  });
}

export function useTimeline(h3) {
  return useQuery({
    queryKey: ['timeline', h3],
    queryFn: () => fetchTimeline(h3),
    enabled: Boolean(h3),
    retry: false,
    staleTime: Infinity,
  });
}

export function useExplain(h3, date) {
  return useQuery({
    queryKey: ['explain', h3, date],
    queryFn: () => fetchExplain(h3, date),
    enabled: Boolean(h3 && date),
    retry: false,
    staleTime: Infinity,
  });
}

export function usePlaces() {
  return useQuery({
    queryKey: ['places'],
    queryFn: fetchPlaces,
    retry: false,
    // Changes only when the grid or the GeoNames dump does.
    staleTime: Infinity,
  });
}
