import { useEffect, useState } from 'react';

/**
 * Hash routing, hand-rolled.
 *
 * Three routes did not justify pulling in a router: this is a dozen lines
 * against ~10 KB, and hash routes need no server rewrite rules, which keeps
 * static hosting trivial.
 */
export const ROUTES = {
  '': { title: 'Map' },
  'how-it-works': { title: 'How it works' },
  'about-the-build': { title: 'About the build' },
};

function currentRoute() {
  const raw = window.location.hash.replace(/^#\/?/, '');
  return raw in ROUTES ? raw : '';
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

export function navigate(route) {
  window.location.hash = route ? `#/${route}` : '#/';
}
