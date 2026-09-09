import { describe, it, expect } from 'vitest';
import { stageOf } from '../api/packed';
import { addDays, daysBetween } from '../season';
import {
  encodeStops, decodeStops, peakWindow, standingOn,
  planStop, planTrip, bestShift, countAtPeak, MAX_STOPS,
} from './plan';

const SEASON_START = '2026-09-01';
const SEASON_DAYS = 76;

/**
 * A season for one cell, shaped like the exporter's: the same logistic the
 * model uses, quantised the same way, labelled with the same stage function.
 * Building it from `stageOf` rather than hardcoding stages is deliberate --
 * these tests must follow the band boundaries if they ever move again.
 */
function series(peakDate, { confidence = 0.9, width = 6 } = {}) {
  const t50 = daysBetween(SEASON_START, peakDate) - 1.0986 * width;
  return Array.from({ length: SEASON_DAYS }, (_, i) => {
    const progression = Math.round((100 / (1 + Math.exp(-(i - t50) / width))) * 2) / 2;
    return {
      date: addDays(SEASON_START, i),
      progression,
      intensity: 60,
      confidence,
      stage: stageOf(progression),
    };
  });
}

/** A cell with no forecast: exported, drawn, and empty every day of the season. */
const unforecast = () => Array.from({ length: SEASON_DAYS }, (_, i) => ({
  date: addDays(SEASON_START, i),
  progression: null,
  intensity: null,
  confidence: null,
  stage: null,
}));

const BOUNDS = { from: SEASON_START, to: addDays(SEASON_START, SEASON_DAYS - 1) };

describe('the URL', () => {
  const stops = [
    { h3: '862a1072fffffff', from: '2026-10-06', to: '2026-10-06', name: 'Stowe' },
    { h3: '862a10727ffffff', from: '2026-10-08', to: '2026-10-11', name: 'Franconia Notch' },
  ];

  it('round-trips a trip with stays of different lengths', () => {
    expect(decodeStops(encodeStops(stops))).toEqual(stops);
  });

  it('still reads a link written before stays existed', () => {
    // Those carry one date per stop. They must keep working, and must not
    // silently become a stay of some invented length.
    expect(decodeStops('862a1072fffffff~2026-10-06~Stowe')).toEqual([
      { h3: '862a1072fffffff', from: '2026-10-06', to: '2026-10-06', name: 'Stowe' },
    ]);
  });

  it('writes a one-day stay in the old short form', () => {
    // So a link shared before this change and one shared after are the same
    // string rather than two spellings of one trip.
    const oneDay = [{
      h3: '862a1072fffffff', from: '2026-10-06', to: '2026-10-06', name: 'Stowe',
    }];
    expect(encodeStops(oneDay)).toBe('862a1072fffffff~2026-10-06~Stowe');
  });

  it('drops a stay that ends before it starts', () => {
    expect(decodeStops('862a1072fffffff~2026-10-08~2026-10-06~Backwards')).toEqual([]);
  });

  it('survives a name carrying its own delimiters', () => {
    // encodeURIComponent leaves ! and ~ untouched, and both delimit here, so
    // without the extra escaping this splits into nonsense stops.
    const awkward = [{
      h3: '862a1072fffffff', from: '2026-10-06', to: '2026-10-08', name: "Ho'ea! ~ Ridge",
    }];
    expect(decodeStops(encodeStops(awkward))).toEqual(awkward);
  });

  it('drops a malformed stop instead of blanking the trip', () => {
    // A hand-edited or truncated link is the normal way this arrives.
    const raw = `${encodeStops(stops)}!not-a-stop!862bad~2026-13-99~X`;
    expect(decodeStops(raw)).toEqual(stops);
  });

  it('falls back to the cell when the label will not decode', () => {
    const [stop] = decodeStops('862a1072fffffff~2026-10-06~%E0%A4%A');
    expect(stop.name).toBe('862a1072fffffff');
  });

  it('is empty for an empty or missing parameter', () => {
    expect(decodeStops('')).toEqual([]);
    expect(decodeStops(undefined)).toEqual([]);
  });

  it('caps the trip so one link cannot carry a hundred stops', () => {
    const many = Array.from({ length: 20 }, (_, i) => ({
      h3: `862a10${i}ffffffff`, from: '2026-10-06', to: '2026-10-06', name: `Stop ${i}`,
    }));
    expect(decodeStops(encodeStops(many))).toHaveLength(MAX_STOPS);
  });
});

describe('peakWindow', () => {
  it('reads the contiguous run of peak days off the series', () => {
    const w = peakWindow(series('2026-10-05'));
    expect(w.from).toBe('2026-10-05');
    // Peak opens at progression 75 and closes at 94, which the model's own
    // width puts about ten days later -- the "about ten days at peak" the
    // How it works page promises.
    expect(w.length).toBeGreaterThanOrEqual(9);
    expect(w.length).toBeLessThanOrEqual(11);
    expect(w.to).toBe(addDays(w.from, w.length - 1));
  });

  it('returns null for a cell that carries no readings', () => {
    // The case that actually exists in the export: 32 cells are empty for
    // every one of the 106 days. A window cannot be read off them, and that
    // must not throw.
    expect(peakWindow(unforecast())).toBeNull();
  });

  it('returns null when peak falls outside the exported season', () => {
    expect(peakWindow(series('2027-01-20'))).toBeNull();
  });

  it('returns null rather than throwing when the timeline is absent', () => {
    expect(peakWindow(null)).toBeNull();
  });
});

describe('standingOn', () => {
  const w = peakWindow(series('2026-10-05'));

  it('counts the days from leaving to a window it never reached', () => {
    // Measured from departure, not arrival: a stay ending the day before peak
    // opens missed it by one, however long the stay was.
    expect(standingOn('2026-09-28', '2026-10-01', w)).toEqual({ where: 'early', days: 4 });
  });

  it('counts the days since a window that closed before arrival', () => {
    expect(standingOn(addDays(w.to, 3), addDays(w.to, 5), w))
      .toEqual({ where: 'late', days: 3 });
  });

  it('counts the peak days a stay actually catches', () => {
    // The question a stay asks that a single date cannot: not "is this the
    // right day" but "how much of peak am I there for".
    expect(standingOn(w.from, w.from, w)).toEqual({ where: 'inside', days: 1 });
    expect(standingOn(w.from, addDays(w.from, 2), w)).toEqual({ where: 'inside', days: 3 });
  });

  it('counts only the overlap when the stay starts before the window', () => {
    // Arriving two days early and leaving on peak's second day is two peak
    // days, not four days of stay.
    expect(standingOn(addDays(w.from, -2), addDays(w.from, 1), w))
      .toEqual({ where: 'inside', days: 2 });
  });

  it('says so when there is no window at all', () => {
    expect(standingOn('2026-10-05', '2026-10-07', null).where).toBe('never');
  });
});

describe('planStop', () => {
  const stop = { h3: 'a', from: '2026-10-06', to: '2026-10-06', name: 'Stowe' };

  it('resolves a stop against its timeline', () => {
    const p = planStop(stop, { days: series('2026-10-05') });
    expect(p.stage).toBe('PEAK');
    expect(p.ready).toBe(true);
    expect(p.atPeak).toBe(true);
  });

  it('moves both ends of the stay by the shift', () => {
    const stay = { h3: 'a', from: '2026-10-06', to: '2026-10-09', name: 'Stowe' };
    const p = planStop(stay, { days: series('2026-10-05') }, 7);
    expect(p.from).toBe('2026-10-13');
    expect(p.to).toBe('2026-10-16');
    expect(p.nights).toBe(3);
  });

  it('reports the stage on arrival and on leaving', () => {
    // A stay long enough to plan is often long enough to change stage inside
    // it, and showing one of them would be picking a favourite.
    const stay = { h3: 'a', from: '2026-09-28', to: '2026-10-08', name: 'Stowe' };
    const p = planStop(stay, { days: series('2026-10-05') });
    expect(p.stage).not.toBe(p.stageOnLeaving);
  });

  it('catches peak when only part of the stay overlaps it', () => {
    const stay = { h3: 'a', from: '2026-10-01', to: '2026-10-06', name: 'Stowe' };
    const p = planStop(stay, { days: series('2026-10-05') });
    expect(p.atPeak).toBe(true);
    expect(p.standing.days).toBeGreaterThan(0);
  });

  it('still renders while the shard is in flight', () => {
    // Stops are added one at a time and each fetches its own shard, so a
    // half-loaded trip is the normal state, not an error.
    const p = planStop(stop, undefined);
    expect(p.ready).toBe(false);
    expect(p.name).toBe('Stowe');
    expect(p.stage).toBeNull();
  });
});

describe('bestShift', () => {
  // Franconia is birch: seven days ahead of its neighbours, and far enough
  // ahead that no shift catches it along with the rest.
  const stops = [
    { h3: 'stowe', from: '2026-10-06', to: '2026-10-06', name: 'Stowe' },
    { h3: 'franconia', from: '2026-10-08', to: '2026-10-08', name: 'Franconia Notch' },
    { h3: 'conway', from: '2026-10-10', to: '2026-10-10', name: 'North Conway' },
    { h3: 'barharbor', from: '2026-10-12', to: '2026-10-12', name: 'Bar Harbor' },
  ];
  const timelines = {
    stowe: { days: series('2026-10-05') },
    franconia: { days: series('2026-09-24') },
    conway: { days: series('2026-10-11') },
    barharbor: { days: series('2026-10-17') },
  };

  it('finds a shift that beats leaving the trip alone', () => {
    const asPlanned = countAtPeak(planTrip(stops, timelines, 0));
    const best = bestShift(stops, timelines, BOUNDS);
    const shifted = countAtPeak(planTrip(stops, timelines, best.shift));
    expect(shifted).toBeGreaterThan(asPlanned);
  });

  it('cannot rescue a stop whose forest turns a week before the others', () => {
    // The point of the feature: some stops are wrong for the trip, not for
    // the date, and no amount of shifting fixes them.
    const best = bestShift(stops, timelines, BOUNDS);
    const planned = planTrip(stops, timelines, best.shift);
    expect(planned.find((p) => p.h3 === 'franconia').atPeak).toBe(false);
  });

  it('never proposes a shift that runs off the end of the season', () => {
    const late = [{
      h3: 'a', from: addDays(BOUNDS.to, -2), to: addDays(BOUNDS.to, -1), name: 'Late',
    }];
    const best = bestShift(late, { a: { days: series('2026-10-05') } }, BOUNDS);
    // The whole stay has to fit, not just its first day.
    expect(addDays(late[0].to, best.shift) <= BOUNDS.to).toBe(true);
  });

  it('moves a stop off the edge of its window toward the middle', () => {
    // Arriving on the peak date itself means progression 75 -- inside the
    // band, but one day of model error from falling out of it. The national
    // model is about four days out, so the edge is not a safe place to plan
    // for, and the tie-break exists to pull the stop inward.
    const edge = [{ h3: 'a', from: '2026-10-05', to: '2026-10-05', name: 'A' }];
    const timeline = { a: { days: series('2026-10-05') } };
    const best = bestShift(edge, timeline, BOUNDS);

    const before = planStop(edge[0], timeline.a, 0);
    const after = planStop(edge[0], timeline.a, best.shift);
    expect(before.day.progression).toBeCloseTo(75, 0);
    expect(after.stage).toBe('PEAK');
    // Further into the band than the edge it started on, which is the whole
    // point: four days of model error must not tip it out of peak.
    expect(after.day.progression).toBeGreaterThan(before.day.progression);
  });

  it('gives nothing back when no stop has loaded yet', () => {
    expect(bestShift(stops, {}, BOUNDS)).toBeNull();
  });
});
