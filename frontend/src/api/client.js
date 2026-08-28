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

const shardKey = (h3) => cellToParent(h3, SHARD_RES);

// --- public API ----------------------------------------------------------

export const fetchMeta = () => (STATIC ? staticMeta() : request('/meta'));

/** The searchable place index. Small enough to fetch once and keep. */
export const fetchPlaces = () => request(STATIC ? '/places.json' : '/places');

export async function fetchForecast(date) {
  if (!STATIC) return request(`/forecast?date=${date}`);

  const [index, meta, buffer] = await Promise.all([
    staticIndex(),
    staticMeta(),
    request(`/forecast/${date}.bin`, true),
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
    count: day.count,
    seasonStart: meta.seasonStart,
    seasonEnd: meta.seasonEnd,
    cells: toCells(day, index.h3),
  };
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
