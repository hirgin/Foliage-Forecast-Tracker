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
