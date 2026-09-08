import { ROUTES } from '../routing';

const href = (key) => (key ? `#/${key}` : '#/');

/**
 * Site navigation.
 *
 * Anchors rather than buttons. These are real destinations with real URLs, and
 * a button throws that away: no middle-click, no open-in-new-tab, no hover
 * target, nothing to copy. Hash routes need no JavaScript to follow either --
 * the browser sets the hash, `useHashRoute` hears it, and the click handler
 * exists only to stop the current page navigating to itself.
 *
 * That last part matters more than it looks. A trip lives in the hash query,
 * so following `#/trip` while already on the trip page would drop the query
 * and quietly delete the plan.
 */
export default function Nav({ route }) {
  return (
    <nav className="nav" aria-label="Site">
      {Object.entries(ROUTES).map(([key, { title }]) => {
        const current = key === route;
        return (
          <a
            key={key || 'map'}
            href={href(key)}
            className={current ? 'nav__item nav__item--on' : 'nav__item'}
            aria-current={current ? 'page' : undefined}
            onClick={current ? (e) => e.preventDefault() : undefined}
          >
            {title}
          </a>
        );
      })}
    </nav>
  );
}
