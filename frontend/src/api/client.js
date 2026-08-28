const BASE = '/api/v1';

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export async function get(path) {
  let res;
  try {
    res = await fetch(`${BASE}${path}`);
  } catch (cause) {
    // fetch only rejects on network-level failure -- almost always "backend is down".
    throw new ApiError('Could not reach the forecast service.', 0);
  }
  if (!res.ok) {
    throw new ApiError(`Request failed (${res.status})`, res.status);
  }
  return res.json();
}

export const fetchMeta = () => get('/meta');

// state is a FIPS code; 50 is Vermont, the only state bootstrapped so far.
export const fetchCells = (state = '50', minCanopy = 0) =>
  get(`/cells?state=${state}&minCanopy=${minCanopy}`);

export const fetchForecast = (date) => get(`/forecast?date=${date}`);

export const fetchTimeline = (h3) => get(`/cells/${h3}/timeline`);

export const fetchExplain = (h3, date) => get(`/cells/${h3}/explain?date=${date}`);
