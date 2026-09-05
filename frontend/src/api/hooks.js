import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { fetchMeta, fetchForecast, fetchTimeline, fetchExplain, fetchPlaces } from './client';

export function useMeta() {
  return useQuery({
    queryKey: ['meta'],
    queryFn: fetchMeta,
    retry: false,
    staleTime: 60_000,
  });
}

/**
 * Fetches the days just ahead of the one being shown.
 *
 * Every date is its own file -- about 104 KB, roughly 135 ms -- and they are
 * cached forever once fetched, so scrubbing back over ground already covered
 * is instant while the first pass through waits on a round trip per frame.
 * Pressing play is the worst case: it steps through 106 dates having fetched
 * none of them.
 *
 * So the days ahead are pulled while the current one is on screen. Forward
 * further than back, because the slider and the play button both move that way
 * and a user scrubbing backwards is usually returning over cached ground.
 *
 * Deliberately idle work: queued through requestIdleCallback so it never
 * competes with the fetch the map is actually waiting on, and skipped entirely
 * while the browser reports a slow or metered connection.
 */
export function usePrefetchForecast(date, days, resolution = 6) {
  const client = useQueryClient();
  useEffect(() => {
    if (!date || !days?.length) return;
    const conn = navigator.connection;
    if (conn?.saveData || /(^|-)2g$/.test(conn?.effectiveType ?? '')) return;

    const i = days.indexOf(date);
    if (i < 0) return;
    const wanted = [
      ...days.slice(i + 1, i + 5),
      ...days.slice(Math.max(0, i - 2), i),
    ];

    const idle = window.requestIdleCallback ?? ((fn) => setTimeout(fn, 200));
    const cancel = window.cancelIdleCallback ?? clearTimeout;
    const handle = idle(() => {
      for (const d of wanted) {
        client.prefetchQuery({
          queryKey: ['forecast', d, resolution],
          queryFn: () => fetchForecast(d, resolution),
          staleTime: Infinity,
        });
      }
    });
    return () => cancel(handle);
  }, [client, date, days, resolution]);
}

export function useForecast(date, resolution = 6) {
  return useQuery({
    // Resolution is part of the key, so zooming swaps between two cached
    // sets rather than refetching one over the other.
    queryKey: ['forecast', date, resolution],
    queryFn: () => fetchForecast(date, resolution),
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

/**
 * The searchable place index, fetched only once it is wanted.
 *
 * Nationally this file is 10 MB raw and ~2.5 MB gzipped -- five times the rest
 * of a first page load put together, for a search box most visitors never
 * touch. It was eager while the grid was one state and the file was 142 KB,
 * which cost nothing; at national scale it dominates.
 *
 * [enabled] defers it until the search input is first focused. The trade is a
 * pause on that first focus instead of a slower map for everyone, which is the
 * right way round: the map is why people arrive.
 */
export function usePlaces(enabled = true) {
  return useQuery({
    queryKey: ['places'],
    queryFn: fetchPlaces,
    enabled,
    retry: false,
    // Changes only when the grid or the GeoNames dump does.
    staleTime: Infinity,
  });
}
