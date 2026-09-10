import { describe, it, expect } from 'vitest';
import {
  KEY, MAX_SAVED, readTrips, addTrip, removeTrip, isSaved,
} from './saved';

/** A localStorage that behaves, backed by a Map. */
function store(initial) {
  const data = new Map(initial ? [[KEY, initial]] : []);
  return {
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, v),
    removeItem: (k) => data.delete(k),
  };
}

/** One that does not, which is a real browser in a private window. */
const throwingStore = {
  getItem() { throw new Error('storage disabled'); },
  setItem() { throw new Error('storage disabled'); },
};

const trip = (stops, label = 'Stowe') => ({ stops, label, dates: '3–9 Oct' });

describe('readTrips', () => {
  it('is empty before anything is saved', () => {
    expect(readTrips(store())).toEqual([]);
  });

  it('survives a value that is not JSON', () => {
    expect(readTrips(store('{not json'))).toEqual([]);
  });

  it('survives JSON that is not a list', () => {
    expect(readTrips(store('{"stops":"x"}'))).toEqual([]);
  });

  it('drops entries that are not trips', () => {
    // A half-written value, or a shape from some future version.
    const mixed = JSON.stringify([{ stops: 'abc' }, { label: 'no stops' }, null, 7]);
    expect(readTrips(store(mixed))).toEqual([{ stops: 'abc' }]);
  });

  it('returns empty rather than throwing when storage is blocked', () => {
    // A planner that crashes because it could not offer to remember
    // something is worse than one that quietly cannot remember.
    expect(readTrips(throwingStore)).toEqual([]);
  });
});

describe('addTrip', () => {
  it('saves a trip and reads it back', () => {
    const s = store();
    addTrip(trip('cell~2026-10-03~Stowe'), s);
    const [saved] = readTrips(s);
    expect(saved.stops).toBe('cell~2026-10-03~Stowe');
    expect(saved.label).toBe('Stowe');
    expect(saved.id).toBeTruthy();
  });

  it('puts the newest first', () => {
    const s = store();
    addTrip(trip('one', 'One'), s);
    addTrip(trip('two', 'Two'), s);
    expect(readTrips(s).map((t) => t.label)).toEqual(['Two', 'One']);
  });

  it('moves an identical trip to the top instead of duplicating it', () => {
    // Pressing save again is how someone confirms they meant it, not how
    // they ask for a second copy.
    const s = store();
    addTrip(trip('same', 'A'), s);
    addTrip(trip('other', 'B'), s);
    addTrip(trip('same', 'A'), s);
    const saved = readTrips(s);
    expect(saved).toHaveLength(2);
    expect(saved[0].stops).toBe('same');
  });

  it('caps the list', () => {
    const s = store();
    for (let i = 0; i < MAX_SAVED + 5; i += 1) addTrip(trip(`trip-${i}`), s);
    expect(readTrips(s)).toHaveLength(MAX_SAVED);
  });

  it('ignores an empty trip', () => {
    const s = store();
    addTrip(trip(''), s);
    expect(readTrips(s)).toEqual([]);
  });

  it('does not throw when storage refuses the write', () => {
    expect(() => addTrip(trip('x'), throwingStore)).not.toThrow();
  });
});

describe('removeTrip', () => {
  it('removes only the one asked for', () => {
    const s = store();
    addTrip(trip('one', 'One'), s);
    addTrip(trip('two', 'Two'), s);
    const [newest] = readTrips(s);
    const left = removeTrip(newest.id, s);
    expect(left).toHaveLength(1);
    expect(left[0].label).toBe('One');
  });

  it('is harmless for an id that is not there', () => {
    const s = store();
    addTrip(trip('one'), s);
    expect(removeTrip('nope', s)).toHaveLength(1);
  });
});

describe('isSaved', () => {
  it('recognises the exact trip and nothing else', () => {
    const s = store();
    addTrip(trip('cell~2026-10-03~Stowe'), s);
    expect(isSaved('cell~2026-10-03~Stowe', s)).toBe(true);
    // Same place, different dates, is a different trip.
    expect(isSaved('cell~2026-10-04~Stowe', s)).toBe(false);
    expect(isSaved('', s)).toBe(false);
  });
});
