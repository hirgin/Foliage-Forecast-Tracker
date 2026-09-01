import { cellToParent } from 'h3-js';
import {
  decodeDay,
  decodeTimelineShard,
  toCells,
  seriesFor,
  seasonDates,
} from './packed';

/**
 * Two data sources behind one interface.
 *
 * In development the app talks to the running backend. The deployed build
 * reads a precomputed static payload: the read path is entirely precomputed,
 * so publishing it on a CDN removes the need for a server and the 30-60 s cold
 * start every free JVM host imposes.
 *
 * The static payload is packed rather than JSON. Cell identifiers live once in
 * an index and each day is three parallel byte arrays in that order, which is
 * what makes a national grid viable at all -- see api/packed.js.
 */
const STATIC = import.meta.env.VITE_DATA_MODE === 'static';
const BASE = STATIC ? `${import.meta.env.BASE_URL}data` : '/api/v1';

/** Res 3 ancestor, matching the exporter's shard key. */
const SHARD_RES = 3;

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request(path, asBuffer = false) {
  let res;
  try {
    res = await fetch(`${BASE}${path}`);
  } catch (cause) {
    throw new ApiError(
      STATIC ? 'Could not load forecast data.' : 'Could not reach the forecast service.',
      0,
    );
  }
  if (!res.ok) throw new ApiError(`Request failed (${res.status})`, res.status);
  return asBuffer ? res.arrayBuffer() : res.json();
}

export const isStatic = STATIC;

// --- shared static state -------------------------------------------------

/**
 * The cell index and season calendar, fetched once and reused.
 *
 * Kept as promises rather than values so concurrent callers share a single
 * in-flight request instead of racing to download a 1.4 MB index several times.
 */
let indexPromise = null;
let metaPromise = null;
/** Coarse indices, keyed by resolution, fetched at most once each. */
const coarseIndexPromises = new Map();

function staticMeta() {
  if (!metaPromise) metaPromise = request('/meta.json');
  return metaPromise;
}

async function staticIndex() {
  if (!indexPromise) {
    indexPromise = (async () => {
      const [cells, meta] = await Promise.all([request('/cells.json'), staticMeta()]);
      return {
        h3: cells.h3,
        elevationM: cells.elevationM,
        canopyPct: cells.canopyPct,
        // Position in this array is the cell's identity in every packed file.
        indexOf: new Map(cells.h3.map((h, i) => [h, i])),
        dates: seasonDates(meta.seasonStart, meta.seasonEnd),
      };
    })();
  }
  return indexPromise;
}

let barePromise = null;

/**
 * The tiled cells that are not forest, as indexes only.
 *
 * Fetched separately and once, never per date. These carry no forecast and
 * never will, so they are not in any daily file -- the whole point of holding
 * them apart is that adding them cost one file rather than doubling every
 * daily one.
 *
 * Only ever drawn flat. A caller that fails to get them still gets a working
 * map with holes in it, which is what the map was before, so this resolves to
 * an empty list rather than rejecting.
 */
export async function fetchBareCells() {
  if (!barePromise) {
    barePromise = request('/cells-bare.json')
      .then((r) => r.h3 ?? [])
      .catch(() => []);
  }
  return barePromise;
}

const shardKey = (h3) => cellToParent(h3, SHARD_RES);

/**
 * Resolution to draw at, from the map's zoom.
 *
 * A res 6 cell is ~3 km, which is under a pixel when the whole country is on
 * screen -- the national view rendered as a faint speckle rather than a map.
 * Below the switch the coarser export is drawn instead, where a cell is ~22 km
 * and reads as a continuous field.
 *
 * The threshold is where res 6 hexagons reach roughly two pixels, and the
 * coarse level is only ~2% of the detailed payload, so the swap costs
 * essentially nothing.
 */
export const COARSE_RES = 4;
export const COARSE_BELOW_ZOOM = 5;

/**
 * Two coarse levels rather than one. Res 4 to res 6 is a 49x jump in area, and
 * across zoom 5 to 7 that leaves a band where the coarse cells are blocky and
 * the detailed ones are still under two pixels. Res 5 (~8 km) covers it.
 *
 * Thresholds are where each level's cells reach roughly four pixels:
 * res 4 at ~22 km below zoom 5, res 5 at ~8 km below zoom 7, res 6 above.
 */
export function resolutionForZoom(zoom) {
  if (zoom == null) return 6;
  if (zoom < COARSE_BELOW_ZOOM) return COARSE_RES;
  if (zoom < 7) return 5;
  return 6;
}

/**
 * Roughly how wide a cell is at a given H3 resolution, in km.
 *
 * Shown to the reader, so it has to change when the map swaps levels: the
 * counts on screen are of whatever is being drawn, and labelling 22 km cells
 * as 3 km would make those numbers look broken.
 */
export const cellWidthKm = (res) => (res === 6 ? 3 : res === 5 ? 8 : res === 4 ? 22 : 59);

async function coarseIndex(res) {
  if (!coarseIndexPromises.has(res)) {
    coarseIndexPromises.set(res, request(`/cells-r${res}.json`));
  }
  return coarseIndexPromises.get(res);
}

// --- public API ----------------------------------------------------------

export const fetchMeta = () => (STATIC ? staticMeta() : request('/meta'));

/** The searchable place index. Small enough to fetch once and keep. */
export const fetchPlaces = () => request(STATIC ? '/places.json' : '/places');

export async function fetchForecast(date, resolution = 6) {
  if (!STATIC) return request(`/forecast?date=${date}`);

  // The coarse level has its own index and its own daily files, in the same
  // packed format, so everything downstream is unchanged apart from which
  // pair it reads.
  const coarse = resolution !== 6;
  const [index, meta, buffer] = await Promise.all([
    coarse ? coarseIndex(resolution) : staticIndex(),
    staticMeta(),
    request(coarse ? `/forecast-r${resolution}/${date}.bin` : `/forecast/${date}.bin`, true),
  ]);
  const day = decodeDay(buffer);
  if (day.count !== index.h3.length) {
    // Position is identity here, so a length mismatch means every cell would
    // be drawn with another cell's colour. Fail rather than render a lie.
    throw new ApiError(
      `forecast for ${date} has ${day.count} cells but the index has ${index.h3.length}`,
      0,
    );
  }
  return {
    date,
    resolution,
    count: day.count,
    seasonStart: meta.seasonStart,
    seasonEnd: meta.seasonEnd,
    cells: toCells(day, index.h3),
  };
}

/**
 * The hexagon a search result points at.
 *
 * A place carries an *index* into the detailed cell list, not an address. That
 * was fine while the map only ever drew one resolution; once it swaps to a
 * coarser export when zoomed out, indexing the cells currently on screen picks
 * the wrong hexagon or none at all -- selecting a city from search did nothing
 * when the map happened to be zoomed out.
 *
 * So the index is always resolved against the detailed list it belongs to,
 * whatever is being drawn.
 */
export async function h3ForPlace(place, drawnCells) {
  if (place?.cell == null) return null;
  if (!STATIC) return drawnCells?.[place.cell]?.h3 ?? null;
  const index = await staticIndex();
  return index.h3[place.cell] ?? null;
}

export async function fetchTimeline(h3) {
  if (!STATIC) return request(`/cells/${h3}/timeline`);

  const index = await staticIndex();
  const globalIndex = index.indexOf.get(h3);
  if (globalIndex === undefined) throw new ApiError(`unknown cell ${h3}`, 404);

  const shard = decodeTimelineShard(await request(`/timeline/${shardKey(h3)}.bin`, true));
  const days = seriesFor(shard, globalIndex, index.dates);
  if (!days) throw new ApiError(`cell ${h3} missing from its shard`, 0);

  return {
    h3,
    peakDay: days.find((d) => d.stage === 'PEAK')?.date ?? null,
    elevationM: index.elevationM[globalIndex],
    canopyPct: index.canopyPct[globalIndex],
    days,
  };
}

/**
 * Static builds carry each cell's factor breakdown at its *peak* day only.
 * Exporting one per cell per day would be millions of entries for a marginal
 * gain -- peak is the date anyone actually asks "why" about.
 */
export async function fetchExplain(h3, date) {
  if (!STATIC) return request(`/cells/${h3}/explain?date=${date}`);

  const shard = await request(`/factors/${shardKey(h3)}.json`);
  const entry = shard[h3];
  return {
    h3,
    date: entry?.peakDay ?? null,
    atPeakOnly: true,
    factors: entry?.factors ?? [],
  };
}
