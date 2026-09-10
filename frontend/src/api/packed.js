/**
 * Decoders for the packed static export.
 *
 * The wire format exists because JSON does not survive CONUS: repeating a cell
 * identifier and four labelled numbers for every cell on every day costs ~95
 * bytes, which is 1.16 GB across 76,041 cells and a 76-day season. Here the
 * identifiers live once in an index and a cell-day is three bytes.
 *
 * Values are quantised to a byte on a doubled 0–200 scale, so decoding halves
 * them. 255 is reserved for missing data and can never collide with a real
 * reading, which tops out at 200.
 */

const NO_DATA = 255;

/**
 * The retired evergreen sentinel, still present in published exports.
 *
 * It marked a forest that was surveyed and never going to turn, drawn in a
 * slate blue of its own. The category is gone because the model outgrew it:
 * conifers now score on the same curve as everything else and carry about a
 * third the vividness, so they read as a quiet autumn rather than as a class
 * apart -- which left the category holding almost nothing.
 *
 * What it did still hold was wrong. Read out of the timeline shards, which
 * never applied the sentinel, all 32 cells carrying it were empty for all 106
 * days of the season. They are not forests known to stay green; they are cells
 * with no forecast at all, and the map was asserting the opposite. Decoding
 * them as "no reading" is the truthful answer rather than a downgrade.
 *
 * Still recognised, because published data carries it until the next export.
 * Decoded as a number, 254 would become progression 127 and draw as past peak.
 */
const RETIRED_EVERGREEN = 254;

/** Matches PhenologyModel.stageOf on the backend. */
export function stageOf(progression) {
  if (progression == null) return null;
  if (progression < 10) return 'NO_CHANGE';
  if (progression < 30) return 'PATCHY';
  if (progression < 55) return 'PARTIAL';
  if (progression < 75) return 'NEAR_PEAK';
  // 94, not 90: see PhenologyModel.PAST_PEAK_PROGRESSION. At 90 the map turned
  // New England brown while every published window still called it peak.
  if (progression < 94) return 'PEAK';
  return 'PAST_PEAK';
}

const dequantise = (b) => (b === NO_DATA || b === RETIRED_EVERGREEN ? null : b / 2);

function readMagic(view) {
  return String.fromCharCode(
    view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3),
  );
}

/**
 * Decodes one day: three parallel byte runs in cell-index order.
 *
 * The runs are separate rather than interleaved so each channel compresses
 * against itself; neighbouring cells hold similar values, which gzip exploits.
 */
export function decodeDay(buffer) {
  if (buffer.byteLength < 8) throw new Error('day file truncated');
  const view = new DataView(buffer);

  const magic = readMagic(view);
  if (magic !== 'FFD1') throw new Error(`expected FFD1, got ${magic}`);

  const count = view.getUint32(4, true);
  const expected = 8 + 3 * count;
  if (buffer.byteLength < expected) {
    throw new Error(`day file claims ${count} cells but is ${buffer.byteLength} bytes`);
  }

  const bytes = new Uint8Array(buffer, 8, 3 * count);
  return {
    count,
    progression: bytes.subarray(0, count),
    intensity: bytes.subarray(count, 2 * count),
    confidence: bytes.subarray(2 * count, 3 * count),
  };
}

/** Materialises one cell from a decoded day, given the index. */
export function cellAt(day, index, h3) {
  const progression = dequantise(day.progression[index]);
  return {
    h3,
    progression,
    intensity: dequantise(day.intensity[index]),
    // Confidence was stored on the same 0-100 scale; the app wants 0-1.
    confidence: dequantise(day.confidence[index]) / 100,
    stage: stageOf(progression),
  };
}

/** Every cell of a decoded day, in index order. */
export function toCells(day, h3List) {
  const out = new Array(day.count);
  for (let i = 0; i < day.count; i++) out[i] = cellAt(day, i, h3List[i]);
  return out;
}

/**
 * Decodes a timeline shard: whole-season series for a few hundred cells.
 *
 * Sharded by H3 res 3 ancestor rather than written per cell, because 76,041
 * individual files is not something to put on a CDN. A click fetches one
 * shard and reads the series it needs out of it.
 */
export function decodeTimelineShard(buffer) {
  if (buffer.byteLength < 12) throw new Error('timeline shard truncated');
  const view = new DataView(buffer);

  const magic = readMagic(view);
  if (magic !== 'FFT1') throw new Error(`expected FFT1, got ${magic}`);

  const cellCount = view.getUint32(4, true);
  const dayCount = view.getUint32(8, true);

  const indexStart = 12;
  const dataStart = indexStart + 4 * cellCount;
  const expected = dataStart + 3 * cellCount * dayCount;
  if (buffer.byteLength < expected) {
    throw new Error(`shard claims ${cellCount}x${dayCount} but is ${buffer.byteLength} bytes`);
  }

  const cellIndex = new Map();
  for (let i = 0; i < cellCount; i++) {
    cellIndex.set(view.getUint32(indexStart + 4 * i, true), i);
  }

  return { cellCount, dayCount, cellIndex, bytes: new Uint8Array(buffer, dataStart) };
}

/**
 * Pulls one cell's season out of a shard, or null if it is not in this one.
 * [dates] supplies the calendar; the shard stores values only.
 */
export function seriesFor(shard, globalIndex, dates) {
  const slot = shard.cellIndex.get(globalIndex);
  if (slot === undefined) return null;

  const stride = 3 * shard.dayCount;
  const base = slot * stride;
  const days = new Array(shard.dayCount);

  for (let d = 0; d < shard.dayCount; d++) {
    const progression = dequantise(shard.bytes[base + 3 * d]);
    days[d] = {
      date: dates[d],
      progression,
      intensity: dequantise(shard.bytes[base + 3 * d + 1]),
      confidence: dequantise(shard.bytes[base + 3 * d + 2]) / 100,
      stage: stageOf(progression),
    };
  }
  return days;
}

/** Every date of the season, so packed series can be labelled. */
export function seasonDates(seasonStart, seasonEnd) {
  const out = [];
  const [ys, ms, ds] = seasonStart.split('-').map(Number);
  const [ye, me, de] = seasonEnd.split('-').map(Number);
  let t = Date.UTC(ys, ms - 1, ds);
  const end = Date.UTC(ye, me - 1, de);
  while (t <= end) {
    out.push(new Date(t).toISOString().slice(0, 10));
    t += 86_400_000;
  }
  return out;
}

/**
 * Decodes peak.bin: one byte per cell, in the index's order.
 *
 * The byte is the offset in days from the season's first day to the day that
 * cell first reaches peak, and 255 means it never does inside the season --
 * evergreen country, or ground with no forecast at all. Distinguishing the two
 * is the daily files' job; this one only has to say "not in this season".
 */
export function decodePeak(buffer) {
  if (buffer.byteLength < 8) throw new Error('peak file truncated');
  const view = new DataView(buffer);
  const magic = readMagic(view);
  if (magic !== 'FFPK') throw new Error(`expected FFPK, got ${magic}`);

  const count = view.getUint32(4, true);
  if (buffer.byteLength < 8 + count) {
    throw new Error(`peak claims ${count} cells but is ${buffer.byteLength} bytes`);
  }
  return { count, offsets: new Uint8Array(buffer, 8, count) };
}
