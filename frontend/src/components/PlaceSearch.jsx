import { useEffect, useRef, useState } from 'react';
import { usePlaces } from '../api/hooks';
import { searchPlaces, describePlace } from '../api/places';

const KIND_ICON = {
  TOWN: '•',
  PARK: '▲',
  FOREST: '▲',
  MOUNTAIN: '▲',
  NOTCH: '▲',
};

/**
 * Type-ahead over the place index.
 *
 * Searching runs locally against arrays already in memory, so there is no
 * request per keystroke and no debounce to tune. The index is a few tens of
 * kilobytes; a round trip would cost more than the scan.
 */
export default function PlaceSearch({ onSelect }) {
  const { data: places } = usePlaces();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [active, setActive] = useState(0);
  const [open, setOpen] = useState(false);
  const boxRef = useRef(null);

  useEffect(() => {
    const found = searchPlaces(places, query);
    setResults(found);
    setActive(0);
  }, [places, query]);

  // Close when focus or a click goes elsewhere, so the list does not sit over
  // the map after the user has moved on.
  useEffect(() => {
    const onDocClick = (e) => {
      if (!boxRef.current?.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, []);

  const choose = (place) => {
    if (!place) return;
    onSelect?.(place);
    setQuery(place.name);
    setOpen(false);
  };

  const onKeyDown = (e) => {
    if (!results.length) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => (i + 1) % results.length);
      setOpen(true);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => (i - 1 + results.length) % results.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      choose(results[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const showList = open && results.length > 0;

  return (
    <div className="search" ref={boxRef}>
      <input
        type="search"
        className="search__input"
        placeholder="Find a town or mountain…"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        aria-label="Search for a place"
        aria-expanded={showList}
        autoComplete="off"
      />

      {showList && (
        <ul className="search__results" role="listbox">
          {results.map((place, i) => (
            <li key={`${place.name}-${place.cell}-${i}`}>
              <button
                type="button"
                className={i === active ? 'search__hit search__hit--on' : 'search__hit'}
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(place)}
                role="option"
                aria-selected={i === active}
              >
                <span className="search__icon">{KIND_ICON[place.kind] ?? '•'}</span>
                <span className="search__name">{place.name}</span>
                <span className="search__meta">{describePlace(place)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {open && query.trim().length >= 2 && results.length === 0 && (
        <div className="search__empty">
          Nothing found. Only places inside the forecast grid are listed.
        </div>
      )}
    </div>
  );
}
