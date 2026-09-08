/**
 * Trip planning over exported timelines.
 *
 * The map slices the forecast one way: every cell, one day. A trip is the
 * other slice -- a few cells, many days. Both read the same exported cube, so
 * this feature adds no data and no model. A stop costs one timeline shard,
 * which is exactly what clicking a hexagon already fetches.
 *
 * Everything here is pure and takes plain data, so the interesting parts --
 * where a peak window opens and closes, which shift of the trip catches the
 * most of them -- are testable without a browser or a fixture server.
 */

/** Above this a trip stops being a trip, and the URL stops being shareable. */
export const MAX_STOPS = 8;

/**
 * How far the planner will move a trip when hunting for a better week.
 *
 * Wide on purpose. At 21 the answer kept pinning to the limit, which reads as
 * a recommendation but only means "at least three weeks" -- and the real
 * constraint should be the season, which `scoreShift` already enforces by
 * refusing any shift that pushes a stop off either end of it.
 */
export const SHIFT_LIMIT = 35;

import { addDays, daysBetween, isDate } from '../season';

// ------------------------------------------------------------ the URL

// encodeURIComponent leaves ! and ~ alone, and both are delimiters here, so a
// place called "Ho'ea!" would otherwise split a stop in half. Escaping them by
// hand is cheaper than inventing a second encoding.
const esc = (s) => encodeURIComponent(s).replace(/!/g, '%21').replace(/~/g, '%7E');

const STOP_SEP = '!';
const FIELD_SEP = '~';

/**
 * Stops as one URL-safe string.
 *
 * The trip lives in the address bar and nowhere else: no account, no storage,
 * no row written. Sharing a plan is sharing a link, and the page can render
 * from the URL alone before the place index has loaded -- which is why the
 * label travels with the cell rather than being looked up.
 */
export function encodeStops(stops) {
  return (stops || [])
    .slice(0, MAX_STOPS)
    .map((s) => [s.h3, s.date, esc(s.name)].join(FIELD_SEP))
    .join(STOP_SEP);
}

export function decodeStops(raw) {
  if (!raw) return [];
  return raw
    .split(STOP_SEP)
    .map((chunk) => {
      const [h3, date, name] = chunk.split(FIELD_SEP);
      // A hand-edited or truncated link should drop the bad stop, not blank
      // the page: the rest of the trip is still perfectly good.
      if (!h3 || !isDate(date)) return null;
      let label = h3;
      try {
        label = decodeURIComponent(name || '') || h3;
      } catch {
        label = h3;
      }
      return { h3, date, name: label };
    })
    .filter(Boolean)
    .slice(0, MAX_STOPS);
}

// -------------------------------------------------------- reading a season

/** One day out of an exported series, or null if the season does not cover it. */
export function dayAt(days, date) {
  if (!days) return null;
  return days.find((d) => d.date === date) || null;
}

/**
 * The span worth visiting, read off the exported series rather than recomputed.
 *
 * Progression is monotonic, so PEAK is one contiguous run and first/last is
 * enough. Reading it from the data instead of re-deriving it from the model
 * matters: the stage bands are a display decision that has already moved once
 * (90 to 94), and a second copy of that boundary here would be a fourth place
 * to keep in step.
 */
export function peakWindow(days) {
  if (!days) return null;
  let from = null;
  let to = null;
  for (const d of days) {
    if (d.stage !== 'PEAK') continue;
    if (from === null) from = d.date;
    to = d.date;
  }
  return from ? { from, to, length: daysBetween(from, to) + 1 } : null;
}

/**
 * Where a date falls relative to a window, as something a card can say.
 *
 * `null` window means this cell never reaches peak inside the exported season
 * -- a peak falling outside it, or no readings at all. That is a real answer
 * and has to be distinguishable from a stop still loading.
 */
export function standingOn(date, window) {
  if (!window) return { where: 'never', days: 0 };
  if (date < window.from) return { where: 'early', days: daysBetween(date, window.from) };
  if (date > window.to) return { where: 'late', days: daysBetween(window.to, date) };
  return { where: 'inside', days: daysBetween(window.from, date) };
}

/**
 * A stop resolved against its timeline at a given shift.
 *
 * `timeline` is whatever useTimeline returned, so it may be missing while the
 * shard is still in flight. A pending stop still renders its name and date.
 */
export function planStop(stop, timeline, shift = 0) {
  const date = addDays(stop.date, shift);
  const days = timeline?.days || null;
  const window = peakWindow(days);
  const day = dayAt(days, date);
  return {
    ...stop,
    date,
    day,
    // The row in the strip draws every day, not just the one being visited.
    series: days,
    window,
    standing: standingOn(date, window),
    stage: day?.stage ?? null,
    confidence: day?.confidence ?? null,
    ready: Boolean(days),
  };
}

export function planTrip(stops, timelines, shift = 0) {
  return (stops || []).map((s) => planStop(s, timelines?.[s.h3], shift));
}

// ----------------------------------------------------------- best window

/** Middle of the peak band, in progression. Ties break toward it. */
const BAND_MIDDLE = (75 + 94) / 2;

function scoreShift(stops, timelines, shift, bounds) {
  let atPeak = 0;
  let slack = 0;
  let usable = 0;

  for (const stop of stops) {
    const days = timelines?.[stop.h3]?.days;
    if (!days) continue;
    const date = addDays(stop.date, shift);
    // A shift that pushes any stop off the end of the season is not a plan.
    if (bounds && (date < bounds.from || date > bounds.to)) return null;
    const day = dayAt(days, date);
    if (!day || day.progression == null || typeof day.progression !== 'number') continue;
    usable += 1;
    if (day.stage === 'PEAK') atPeak += 1;
    slack += Math.abs(day.progression - BAND_MIDDLE);
  }

  return usable ? { shift, atPeak, slack } : null;
}

/**
 * The shift that puts the most stops at peak.
 *
 * Ties break toward the middle of the band rather than its edge, so the answer
 * survives a day or two of model error instead of clinging to the boundary --
 * which matters, because the national model runs about four days out and this
 * would otherwise hand back a plan that is only right if the model is exact.
 */
export function bestShift(stops, timelines, bounds, limit = SHIFT_LIMIT) {
  let best = null;
  for (let shift = -limit; shift <= limit; shift += 1) {
    const scored = scoreShift(stops, timelines, shift, bounds);
    if (!scored) continue;
    if (
      !best
      || scored.atPeak > best.atPeak
      || (scored.atPeak === best.atPeak && scored.slack < best.slack)
      // Among equals, the plan that moves the trip least.
      || (scored.atPeak === best.atPeak
        && scored.slack === best.slack
        && Math.abs(shift) < Math.abs(best.shift))
    ) {
      best = scored;
    }
  }
  return best;
}

/** How many of the planned stops land at peak, for the running verdict. */
export function countAtPeak(planned) {
  return planned.filter((p) => p.stage === 'PEAK').length;
}
