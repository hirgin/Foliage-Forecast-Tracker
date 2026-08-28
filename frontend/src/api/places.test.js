import { describe, it, expect } from 'vitest';
import { searchPlaces, describePlace, KIND_LABEL } from './places';

/** Mirrors the exported payload: parallel arrays, not objects. */
function index(rows) {
  return {
    count: rows.length,
    name: rows.map((r) => r.name),
    state: rows.map((r) => r.state ?? 'VT'),
    kind: rows.map((r) => r.kind ?? 'TOWN'),
    population: rows.map((r) => r.population ?? 0),
    cell: rows.map((_, i) => i),
    lat: rows.map(() => 44),
    lon: rows.map(() => -72.7),
  };
}

const vermont = index([
  { name: 'Stowe', population: 4314 },
  { name: 'Stowe Hollow', population: 0 },
  { name: 'Killington', population: 0 },
  { name: 'Killington Peak', kind: 'MOUNTAIN' },
  { name: 'Burlington', population: 42452 },
  { name: 'South Burlington', population: 20292 },
  { name: 'Manchester', population: 740 },
  { name: 'Mount Mansfield', kind: 'MOUNTAIN' },
  { name: 'Smugglers Notch', kind: 'NOTCH' },
  { name: 'Green Mountain National Forest', kind: 'FOREST' },
  { name: 'Grafton', population: 0 },
]);

describe('searchPlaces', () => {
  it('puts an exact match first', () => {
    expect(searchPlaces(vermont, 'stowe')[0].name).toBe('Stowe');
    expect(searchPlaces(vermont, 'killington')[0].name).toBe('Killington');
  });

  it('finds small and unpopulated destinations', () => {
    // The reason this dataset is filtered by feature code, not population.
    // Killington and Grafton record zero people.
    expect(searchPlaces(vermont, 'killington')[0].population).toBe(0);
    expect(searchPlaces(vermont, 'grafton')[0].name).toBe('Grafton');
    expect(searchPlaces(vermont, 'manchester')[0].name).toBe('Manchester');
  });

  it('does not let a big city outrank a better name match', () => {
    // "Burlington" must beat "South Burlington" despite both matching, and
    // population must not drag a larger place above an exact match.
    expect(searchPlaces(vermont, 'burlington')[0].name).toBe('Burlington');
  });

  it('matches at word boundaries', () => {
    // Someone typing "mansfield" means Mount Mansfield.
    expect(searchPlaces(vermont, 'mansfield')[0].name).toBe('Mount Mansfield');
    expect(searchPlaces(vermont, 'notch')[0].name).toBe('Smugglers Notch');
  });

  it('prefers a town over a mountain of the same name', () => {
    const results = searchPlaces(vermont, 'killington');
    expect(results[0].name).toBe('Killington');
    expect(results[1].name).toBe('Killington Peak');
  });

  it('prefers the shorter name when both match from the start', () => {
    const results = searchPlaces(vermont, 'stowe');
    expect(results[0].name).toBe('Stowe');
    expect(results[1].name).toBe('Stowe Hollow');
  });

  it('ignores case, punctuation and surrounding space', () => {
    expect(searchPlaces(vermont, '  STOWE ')[0].name).toBe('Stowe');
    expect(searchPlaces(vermont, "smuggler's notch")[0].name).toBe('Smugglers Notch');
  });

  it('needs at least two characters', () => {
    // A single letter matches almost everything and is never a real query.
    expect(searchPlaces(vermont, 's')).toEqual([]);
    expect(searchPlaces(vermont, '')).toEqual([]);
    expect(searchPlaces(vermont, '  ')).toEqual([]);
  });

  it('returns nothing rather than throwing on no match', () => {
    expect(searchPlaces(vermont, 'zzzzz')).toEqual([]);
  });

  it('survives a missing index', () => {
    expect(searchPlaces(null, 'stowe')).toEqual([]);
    expect(searchPlaces(undefined, 'stowe')).toEqual([]);
  });

  it('honours the result limit', () => {
    expect(searchPlaces(vermont, 'o', 3)).toHaveLength(0); // too short
    expect(searchPlaces(vermont, 'on', 2).length).toBeLessThanOrEqual(2);
  });

  it('carries the cell index through', () => {
    // This is what links a search result to a forecast: without it a match
    // has nowhere to navigate to.
    const stowe = searchPlaces(vermont, 'stowe')[0];
    expect(stowe.cell).toBe(0);
    expect(typeof stowe.lat).toBe('number');
  });
});

describe('describePlace', () => {
  it('reads as state and kind', () => {
    expect(describePlace({ state: 'VT', kind: 'TOWN' })).toBe('VT · Town');
    expect(describePlace({ state: 'NH', kind: 'MOUNTAIN' })).toBe('NH · Mountain');
  });

  it('omits a missing state rather than printing undefined', () => {
    expect(describePlace({ state: null, kind: 'NOTCH' })).toBe('Notch');
  });

  it('labels every kind the exporter can emit', () => {
    ['TOWN', 'PARK', 'FOREST', 'MOUNTAIN', 'NOTCH'].forEach((k) => {
      expect(KIND_LABEL[k]).toBeTruthy();
    });
  });
});
