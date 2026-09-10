import { KIND_LABEL } from '../api/places';

/**
 * The named places closest to their best on a given day.
 *
 * The map answers "what is happening everywhere"; this answers "so where
 * should I actually go this weekend", which is the same data and a question
 * the map could never quite be read for. It needs nothing new: a place carries
 * an index into the cell list, and the daily file is in that same order, so
 * this is a join the browser can already do.
 */

/** Middle of the peak band. Ranking is by distance from here, not from 100. */
const BAND_MIDDLE = (75 + 94) / 2;

/**
 * What a kind of place is worth as a destination.
 *
 * Small, because the index cannot tell a national forest from a municipal car
 * park -- both are PARK -- so leaning on it produced a list of "AFS Park" and
 * "Airport Park". It nudges; prominence does the work.
 */
const KIND_WEIGHT = {
  PARK: 0.6, FOREST: 0.9, MOUNTAIN: 0.9, NOTCH: 0.9, TOWN: 0.4,
};

/**
 * At most this many from any one state.
 *
 * Progression is quantised to half a point, so scores tie constantly, and the
 * first ranking answered "where should I go" with eight parks from the same
 * corner of Minnesota. A national question deserves a national answer.
 */
const PER_STATE = 2;

/**
 * How well known a place is, which is the only prominence the index carries.
 *
 * Logged and capped, so a city of a million cannot outrank being at peak, and
 * a hamlet of forty does not tie with a town of ten thousand.
 */
function prominence(population) {
  return Math.min(3, Math.log10(Math.max(1, population || 0)));
}

export function describeKind(kind) {
  return KIND_LABEL[kind] ?? kind;
}

/**
 * Ranks places at peak on this day.
 *
 * [cells] is the day already decoded for the detailed grid, in index order --
 * the same order `place.cell` points into. Anything else silently mismatches,
 * so the caller passes res 6 or nothing.
 */
export function rankPlaces(places, cells, { limit = 8 } = {}) {
  if (!places?.cell?.length || !cells?.length) return [];

  const scored = [];
  for (let i = 0; i < places.cell.length; i += 1) {
    const cell = cells[places.cell[i]];
    // A place whose cell is off the end of this grid, or has no reading, is
    // not a miss worth reporting -- it is simply not answerable today.
    if (!cell || cell.stage !== 'PEAK' || typeof cell.progression !== 'number') continue;

    const gap = Math.abs(cell.progression - BAND_MIDDLE);
    scored.push({
      name: places.name[i],
      state: places.state?.[i] ?? '',
      kind: places.kind?.[i] ?? 'TOWN',
      lat: places.lat[i],
      lon: places.lon[i],
      cell: places.cell[i],
      progression: cell.progression,
      population: places.population?.[i] ?? 0,
      // Distance from the middle of the band, less how much of a destination
      // the place is. Both adjustments together are smaller than the width of
      // the band, so nothing can be promoted past somewhere better timed.
      score: gap
        - (KIND_WEIGHT[places.kind?.[i]] ?? 0.4)
        - prominence(places.population?.[i]),
    });
  }

  // Ties break on population rather than on the alphabet. Scores tie often --
  // progression is quantised to half a point -- and sorting those by name is
  // what produced a list beginning "Able Park, Acorn Park, Acton".
  scored.sort((a, b) => a.score - b.score
    || b.population - a.population
    || a.name.localeCompare(b.name));

  // One entry per place, and a ceiling per state. The index carries a row per
  // name per state, and a list that says Stowe three times is a worse answer
  // than one that says it once and moves on.
  const seen = new Set();
  const byState = new Map();
  const out = [];
  for (const p of scored) {
    const key = `${p.name}|${p.state}`;
    if (seen.has(key)) continue;
    const used = byState.get(p.state) ?? 0;
    if (p.state && used >= PER_STATE) continue;
    seen.add(key);
    byState.set(p.state, used + 1);
    out.push(p);
    if (out.length >= limit) break;
  }
  return out;
}
