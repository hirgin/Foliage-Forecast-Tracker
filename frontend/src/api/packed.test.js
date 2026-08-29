import { describe, it, expect } from 'vitest';
import { resolutionForZoom, COARSE_RES, COARSE_BELOW_ZOOM } from './client';
import {
  decodeDay,
  decodeTimelineShard,
  cellAt,
  toCells,
  seriesFor,
  seasonDates,
  stageOf,
} from './packed';

/** Builds a day file the way the backend does. */
function buildDay(values) {
  const n = values.length;
  const buf = new ArrayBuffer(8 + 3 * n);
  const view = new DataView(buf);
  'FFD1'.split('').forEach((c, i) => view.setUint8(i, c.charCodeAt(0)));
  view.setUint32(4, n, true);
  const bytes = new Uint8Array(buf, 8);
  values.forEach((v, i) => {
    bytes[i] = v.progression;
    bytes[n + i] = v.intensity;
    bytes[2 * n + i] = v.confidence;
  });
  return buf;
}

function buildShard(cells, dayCount) {
  const n = cells.length;
  const buf = new ArrayBuffer(12 + 4 * n + 3 * n * dayCount);
  const view = new DataView(buf);
  'FFT1'.split('').forEach((c, i) => view.setUint8(i, c.charCodeAt(0)));
  view.setUint32(4, n, true);
  view.setUint32(8, dayCount, true);
  cells.forEach((c, i) => view.setUint32(12 + 4 * i, c.globalIndex, true));
  const bytes = new Uint8Array(buf, 12 + 4 * n);
  cells.forEach((c, i) => {
    for (let d = 0; d < dayCount; d++) {
      bytes[i * 3 * dayCount + 3 * d] = c.series[d];
      bytes[i * 3 * dayCount + 3 * d + 1] = 100;
      bytes[i * 3 * dayCount + 3 * d + 2] = 200;
    }
  });
  return buf;
}

describe('decodeDay', () => {
  it('reads the header and splits the three channels', () => {
    const day = decodeDay(buildDay([
      { progression: 142, intensity: 100, confidence: 170 },
      { progression: 60, intensity: 40, confidence: 200 },
    ]));
    expect(day.count).toBe(2);
    expect(Array.from(day.progression)).toEqual([142, 60]);
    expect(Array.from(day.intensity)).toEqual([100, 40]);
    expect(Array.from(day.confidence)).toEqual([170, 200]);
  });

  it('rejects a file with the wrong magic', () => {
    // Catches a mismatched format version loudly rather than rendering noise.
    const buf = buildDay([{ progression: 1, intensity: 1, confidence: 1 }]);
    new DataView(buf).setUint8(0, 'X'.charCodeAt(0));
    expect(() => decodeDay(buf)).toThrow(/expected FFD1/);
  });

  it('rejects a truncated file rather than reading past the end', () => {
    const full = buildDay([{ progression: 1, intensity: 1, confidence: 1 }]);
    expect(() => decodeDay(full.slice(0, 9))).toThrow(/claims 1 cells/);
    expect(() => decodeDay(new ArrayBuffer(4))).toThrow(/truncated/);
  });
});

describe('cellAt', () => {
  it('halves the doubled scale and converts confidence to 0-1', () => {
    const day = decodeDay(buildDay([{ progression: 142, intensity: 100, confidence: 170 }]));
    const c = cellAt(day, 0, '862abc');
    expect(c.progression).toBe(71);
    expect(c.intensity).toBe(50);
    expect(c.confidence).toBeCloseTo(0.85, 5);
    expect(c.h3).toBe('862abc');
  });

  it('maps 255 to null rather than a real value', () => {
    // 255 is the no-data sentinel. Decoding it as 127.5 would paint an
    // unscored cell as past peak.
    const day = decodeDay(buildDay([{ progression: 255, intensity: 255, confidence: 255 }]));
    const c = cellAt(day, 0, 'x');
    expect(c.progression).toBeNull();
    expect(c.intensity).toBeNull();
    expect(c.stage).toBeNull();
  });

  it('derives stage from progression', () => {
    const day = decodeDay(buildDay([
      { progression: 10, intensity: 0, confidence: 200 },   // 5   -> NO_CHANGE
      { progression: 120, intensity: 0, confidence: 200 },  // 60  -> NEAR_PEAK
      { progression: 170, intensity: 0, confidence: 200 },  // 85  -> PEAK
      { progression: 200, intensity: 0, confidence: 200 },  // 100 -> PAST_PEAK
    ]));
    expect([0, 1, 2, 3].map((i) => cellAt(day, i, 'x').stage))
      .toEqual(['NO_CHANGE', 'NEAR_PEAK', 'PEAK', 'PAST_PEAK']);
  });
});

describe('stageOf', () => {
  it('matches the backend boundaries exactly', () => {
    // Drift here would colour cells differently from how the model classified
    // them, and the legend counts would stop matching the map.
    expect(stageOf(9.9)).toBe('NO_CHANGE');
    expect(stageOf(10)).toBe('PATCHY');
    expect(stageOf(29.9)).toBe('PATCHY');
    expect(stageOf(30)).toBe('PARTIAL');
    expect(stageOf(54.9)).toBe('PARTIAL');
    expect(stageOf(55)).toBe('NEAR_PEAK');
    expect(stageOf(74.9)).toBe('NEAR_PEAK');
    expect(stageOf(75)).toBe('PEAK');
    expect(stageOf(89.9)).toBe('PEAK');
    expect(stageOf(90)).toBe('PAST_PEAK');
  });
});

describe('toCells', () => {
  it('pairs values with identifiers by position', () => {
    // Position IS identity in this format -- the whole saving depends on it.
    const day = decodeDay(buildDay([
      { progression: 100, intensity: 100, confidence: 200 },
      { progression: 160, intensity: 100, confidence: 200 },
    ]));
    const cells = toCells(day, ['aaa', 'bbb']);
    expect(cells.map((c) => c.h3)).toEqual(['aaa', 'bbb']);
    expect(cells.map((c) => c.progression)).toEqual([50, 80]);
  });
});

describe('timeline shards', () => {
  const shardBuf = buildShard(
    [
      { globalIndex: 7, series: [20, 60, 140, 190] },
      { globalIndex: 41, series: [10, 30, 80, 150] },
    ],
    4,
  );
  const dates = ['2026-09-01', '2026-09-02', '2026-09-03', '2026-09-04'];

  it('reads counts and the global index map', () => {
    const shard = decodeTimelineShard(shardBuf);
    expect(shard.cellCount).toBe(2);
    expect(shard.dayCount).toBe(4);
    expect([...shard.cellIndex.keys()].sort((a, b) => a - b)).toEqual([7, 41]);
  });

  it('extracts one cell series without disturbing its neighbour', () => {
    const shard = decodeTimelineShard(shardBuf);
    const a = seriesFor(shard, 7, dates);
    const b = seriesFor(shard, 41, dates);
    expect(a.map((d) => d.progression)).toEqual([10, 30, 70, 95]);
    expect(b.map((d) => d.progression)).toEqual([5, 15, 40, 75]);
    expect(a.map((d) => d.date)).toEqual(dates);
  });

  it('returns null for a cell in a different shard', () => {
    // Callers derive the shard from the cell's res 3 ancestor; a miss means a
    // bug in that derivation and should be visible, not silently empty.
    expect(seriesFor(decodeTimelineShard(shardBuf), 999, dates)).toBeNull();
  });

  it('rejects wrong magic and truncation', () => {
    expect(() => decodeTimelineShard(new ArrayBuffer(4))).toThrow(/truncated/);
    const bad = shardBuf.slice(0);
    new DataView(bad).setUint8(0, 'Z'.charCodeAt(0));
    expect(() => decodeTimelineShard(bad)).toThrow(/expected FFT1/);
  });
});

describe('seasonDates', () => {
  it('enumerates the season inclusively', () => {
    const d = seasonDates('2026-09-01', '2026-11-15');
    expect(d).toHaveLength(76);
    expect(d[0]).toBe('2026-09-01');
    expect(d[75]).toBe('2026-11-15');
  });

  it('steps in UTC so no day is skipped or repeated', () => {
    // Local-time arithmetic across a DST boundary drops or duplicates a day,
    // which would silently misalign every packed series after it.
    const d = seasonDates('2026-10-30', '2026-11-05');
    expect(d).toEqual([
      '2026-10-30', '2026-10-31', '2026-11-01',
      '2026-11-02', '2026-11-03', '2026-11-04', '2026-11-05',
    ]);
  });
});

describe('picking a resolution for the zoom', () => {
  it('draws the detailed grid when zoomed in', () => {
    expect(resolutionForZoom(9)).toBe(6);
    expect(resolutionForZoom(7)).toBe(6);
  });

  it('uses the middle level across the awkward band', () => {
    // Res 4 to res 6 is a 49x jump in area, so without this there is a stretch
    // of zoom where the coarse cells look blocky and the detailed ones are
    // still under two pixels.
    expect(resolutionForZoom(5)).toBe(5);
    expect(resolutionForZoom(6.9)).toBe(5);
  });

  it('falls back to the coarse grid when zoomed out', () => {
    // The bug this exists for: a res 6 cell is ~3 km, which is under a pixel
    // with the whole country on screen, so the national view -- the first
    // thing anyone sees -- rendered as a faint speckle instead of a map.
    expect(resolutionForZoom(3)).toBe(COARSE_RES);
    expect(resolutionForZoom(4.9)).toBe(COARSE_RES);
  });

  it('is stable at the boundary rather than flickering', () => {
    // Just either side of the switch must land on different levels and stay
    // there; the map reports zoom on moveend, so an unstable edge would
    // refetch on every nudge.
    expect(resolutionForZoom(COARSE_BELOW_ZOOM - 0.01)).toBe(COARSE_RES);
    expect(resolutionForZoom(COARSE_BELOW_ZOOM)).toBe(5);
    expect(resolutionForZoom(COARSE_BELOW_ZOOM + 0.01)).toBe(5);
  });

  it('assumes the detailed grid when zoom is unknown', () => {
    // Before the map has loaded there is no zoom to read. Guessing coarse
    // would show a blocky map to anyone who lands already zoomed in.
    expect(resolutionForZoom(null)).toBe(6);
    expect(resolutionForZoom(undefined)).toBe(6);
  });
});
