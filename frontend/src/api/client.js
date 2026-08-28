/**
 * Two data sources, one interface.
 *
 * In development the app talks to the running backend. The deployed build
 * reads pre-exported JSON from the same origin: the read path is entirely
 * precomputed, so publishing it as files on a CDN removes the need for a
 * server and, with it, the 30-60 s cold start every free JVM host imposes.
 */
const STATIC = import.meta.env.VITE_DATA_MODE === 'static';
const BASE = STATIC ? `${import.meta.env.BASE_URL}data` : '/api/v1';

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function get(path) {
  let res;
  try {
    res = await fetch(`${BASE}${path}`);
  } catch (cause) {
    throw new ApiError(
      STATIC ? 'Could not load forecast data.' : 'Could not reach the forecast service.',
      0,
    );
  }
  if (!res.ok) throw new ApiError(`Request failed (${res.status})`, res.status);
  return res.json();
}

export const isStatic = STATIC;

export const fetchMeta = () => get(STATIC ? '/meta.json' : '/meta');

export const fetchForecast = (date) =>
  get(STATIC ? `/forecast/${date}.json` : `/forecast?date=${date}`);

export const fetchTimeline = (h3) =>
  get(STATIC ? `/timeline/${h3}.json` : `/cells/${h3}/timeline`);

/**
 * Static builds carry each cell's factor breakdown at its *peak* day only.
 * Exporting one per cell per day would be 49,000 files for a marginal gain —
 * peak is the date anyone actually asks "why" about.
 */
export async function fetchExplain(h3, date) {
  if (!STATIC) return get(`/cells/${h3}/explain?date=${date}`);
  const timeline = await fetchTimeline(h3);
  return {
    h3,
    date: timeline.peakDay,
    atPeakOnly: true,
    factors: timeline.factorsAtPeak ?? [],
  };
}
