import { useEffect, useState } from 'react';

/**
 * Hash routing, hand-rolled.
 *
 * Four routes did not justify pulling in a router: this is a few dozen lines
 * against ~10 KB, and hash routes need no server rewrite rules, which keeps
 * static hosting trivial.
 */
export const ROUTES = {
  '': { title: 'Map' },
  trip: { title: 'Plan a trip' },
  'how-it-works': { title: 'How it works' },
  'about-the-build': { title: 'About the build' },
};

/**
 * The hash carries state as well as a route: a trip lives entirely in its own
 * URL, so `#/trip?s=...` has to survive route matching rather than falling
 * through to the map because of the query.
 */
function splitHash() {
  const raw = window.location.hash.replace(/^#\/?/, '');
  const q = raw.indexOf('?');
  return q === -1 ? [raw, ''] : [raw.slice(0, q), raw.slice(q + 1)];
}

function currentRoute() {
  const [path] = splitHash();
  return path in ROUTES ? path : '';
}

/** One parameter out of the hash query, or null. */
export function hashParam(name) {
  return new URLSearchParams(splitHash()[1]).get(name);
}

/**
 * Rewrites one parameter without touching history or firing a hashchange.
 *
 * A trip is edited a stop at a time, and pushing a history entry per keystroke
 * would make the back button useless. replaceState also avoids the re-render
 * loop that setting location.hash from an effect would cause.
 */
export function setHashParam(name, value) {
  const [path, query] = splitHash();
  const params = new URLSearchParams(query);
  if (value) params.set(name, value);
  else params.delete(name);
  const suffix = params.toString();
  const next = `#/${path}${suffix ? `?${suffix}` : ''}`;
  if (next !== window.location.hash) {
    window.history.replaceState(null, '', next);
  }
}

export function useHashRoute() {
  const [route, setRoute] = useState(currentRoute);

  useEffect(() => {
    const onChange = () => setRoute(currentRoute());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  return route;
}
