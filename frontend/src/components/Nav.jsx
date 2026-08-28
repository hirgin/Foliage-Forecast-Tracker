import { ROUTES, navigate } from '../routing';

export default function Nav({ route }) {
  return (
    <nav className="nav">
      {Object.entries(ROUTES).map(([key, { title }]) => (
        <button
          key={key || 'map'}
          className={key === route ? 'nav__item nav__item--on' : 'nav__item'}
          onClick={() => navigate(key)}
          aria-current={key === route ? 'page' : undefined}
        >
          {title}
        </button>
      ))}
    </nav>
  );
}
