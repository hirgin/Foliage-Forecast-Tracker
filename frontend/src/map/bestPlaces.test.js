import { describe, it, expect } from 'vitest';
import { rankPlaces } from './bestPlaces';
import { stageOf } from '../api/packed';

/** Cells in index order, the way a decoded day arrives. */
function grid(progressions) {
  return progressions.map((p, i) => ({
    h3: `cell-${i}`,
    progression: p,
    stage: stageOf(p),
    intensity: 60,
    confidence: 0.9,
  }));
}

/** The place index is columnar, and `cell` points into the grid above. */
function places(rows) {
  return {
    name: rows.map((r) => r[0]),
    state: rows.map((r) => r[1]),
    kind: rows.map((r) => r[2]),
    cell: rows.map((r) => r[3]),
    population: rows.map((r) => r[4] ?? 0),
    lat: rows.map(() => 44),
    lon: rows.map(() => -72),
  };
}

describe('rankPlaces', () => {
  it('returns only places at peak', () => {
    // 84.5 is mid-peak, 40 is partial, 97 is past it.
    const cells = grid([84.5, 40, 97]);
    const p = places([
      ['Peaking', 'VT', 'TOWN', 0],
      ['Early', 'VT', 'TOWN', 1],
      ['Over', 'VT', 'TOWN', 2],
    ]);
    expect(rankPlaces(p, cells).map((x) => x.name)).toEqual(['Peaking']);
  });

  it('puts the middle of the band first, not the highest number', () => {
    // The point of the ranking: 93 is nearly past peak, and someone asking
    // where to go this weekend does not want the place that is nearly over.
    const cells = grid([93, 84, 76]);
    const p = places([
      ['Nearly over', 'VT', 'TOWN', 0],
      ['Bang on', 'VT', 'TOWN', 1],
      ['Just started', 'VT', 'TOWN', 2],
    ]);
    expect(rankPlaces(p, cells)[0].name).toBe('Bang on');
  });

  it('breaks a tie toward the better known place, not the alphabet', () => {
    // The first ranking sorted ties by name and answered "where should I go"
    // with Able Park, Acorn Park and Acton, all from one corner of Minnesota.
    const cells = grid([84.5, 84.5]);
    const p = places([
      ['Able Park', 'MN', 'PARK', 0, 0],
      ['Stowe', 'VT', 'TOWN', 1, 4314],
    ]);
    expect(rankPlaces(p, cells)[0].name).toBe('Stowe');
  });

  it('spreads across states rather than emptying one', () => {
    // Scores tie constantly, because progression is quantised to half a
    // point, so without a ceiling one state fills the whole list.
    const cells = grid(Array.from({ length: 12 }, () => 84.5));
    const p = places([
      ...Array.from({ length: 10 }, (_, i) => [`MN ${i}`, 'MN', 'TOWN', i, 5000]),
      ['Stowe', 'VT', 'TOWN', 10, 4314],
      ['Bar Harbor', 'ME', 'TOWN', 11, 5089],
    ]);
    const got = rankPlaces(p, cells, { limit: 6 });
    expect(got.filter((x) => x.state === 'MN')).toHaveLength(2);
    expect(got.map((x) => x.state)).toContain('VT');
    expect(got.map((x) => x.state)).toContain('ME');
  });

  it('cannot let prominence outrank being well timed', () => {
    // A city at the very edge of peak must not beat a village in the middle
    // of it: the adjustments reorder, they never promote.
    const cells = grid([75, 84.5]);
    const p = places([
      ['Big City', 'VT', 'TOWN', 0, 900000],
      ['Middle Village', 'VT', 'TOWN', 1, 200],
    ]);
    expect(rankPlaces(p, cells)[0].name).toBe('Middle Village');
  });

  it('says each place once', () => {
    const cells = grid([84.5, 84.4]);
    const p = places([
      ['Stowe', 'VT', 'TOWN', 0],
      ['Stowe', 'VT', 'TOWN', 1],
    ]);
    expect(rankPlaces(p, cells)).toHaveLength(1);
  });

  it('keeps same-named places in different states apart', () => {
    const cells = grid([84.5, 84.4]);
    const p = places([
      ['Franconia', 'NH', 'TOWN', 0],
      ['Franconia', 'VA', 'TOWN', 1],
    ]);
    expect(rankPlaces(p, cells)).toHaveLength(2);
  });

  it('honours the limit', () => {
    const cells = grid(Array.from({ length: 20 }, () => 84.5));
    // Each in its own state, or the per-state ceiling would cap it first.
    const p = places(Array.from({ length: 20 }, (_, i) => [`Place ${i}`, `S${i}`, 'TOWN', i, 1000]));
    expect(rankPlaces(p, cells, { limit: 5 })).toHaveLength(5);
  });

  it('ignores a place pointing outside the grid it was given', () => {
    // Happens the moment this is handed a coarse day by mistake: the indices
    // are into the detailed list and would otherwise read as undefined.
    const cells = grid([84.5]);
    const p = places([['Off the end', 'VT', 'TOWN', 99]]);
    expect(rankPlaces(p, cells)).toEqual([]);
  });

  it('is empty rather than broken before anything has loaded', () => {
    expect(rankPlaces(null, grid([84.5]))).toEqual([]);
    expect(rankPlaces(places([['A', 'VT', 'TOWN', 0]]), null)).toEqual([]);
    expect(rankPlaces(places([]), grid([]))).toEqual([]);
  });
});
