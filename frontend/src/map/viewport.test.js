import { describe, it, expect } from 'vitest';
import { latLngToCell, cellToParent } from 'h3-js';
import {
  bucketByAncestor, ancestorsInView, cellsInView, BUCKET_RES,
} from './viewport';

const at = (lat, lon) => latLngToCell(lat, lon, 6);

describe('bucketByAncestor', () => {
  it('groups cells under their ancestor', () => {
    const a = at(41.9, -93.5);
    const b = at(41.91, -93.51);
    const buckets = bucketByAncestor([a, b]);
    for (const [parent, cells] of buckets) {
      for (const c of cells) expect(cellToParent(c, BUCKET_RES)).toBe(parent);
    }
  });

  it('keeps every cell', () => {
    const cells = [at(41.9, -93.5), at(44.5, -72.7), at(37.5, -77.4)];
    const buckets = bucketByAncestor(cells);
    expect([...buckets.values()].flat().sort()).toEqual([...cells].sort());
  });
});

describe('ancestorsInView', () => {
  const iowa = { west: -94.2, south: 41.4, east: -92.8, north: 42.4 };

  it('covers the cells actually inside the viewport', () => {
    const ancestors = new Set(ancestorsInView(iowa));
    const inside = at(41.9, -93.5);
    expect(ancestors.has(cellToParent(inside, BUCKET_RES))).toBe(true);
  });

  it('reaches past the edges, so panning does not pop', () => {
    // Without a margin the hexagons just outside the viewport are absent until
    // a drag finishes, and that strip is exactly where someone is looking
    // while they drag.
    const tight = ancestorsInView(iowa, BUCKET_RES, 0);
    const padded = ancestorsInView(iowa, BUCKET_RES, 0.25);
    expect(padded.length).toBeGreaterThanOrEqual(tight.length);
  });

  it('returns nothing rather than throwing on a degenerate viewport', () => {
    // Happens for real during a resize, when the container briefly has no
    // height. Drawing nothing for one frame is recoverable; an exception
    // inside a render is not.
    expect(ancestorsInView(null)).toEqual([]);
    expect(ancestorsInView({ west: 0, south: 0, east: 0, north: 0 })).toEqual([]);
  });
});

describe('cellsInView', () => {
  it('returns only the buckets asked for', () => {
    const iowaCell = at(41.9, -93.5);
    const vermontCell = at(44.5, -72.7);
    const buckets = bucketByAncestor([iowaCell, vermontCell]);
    const visible = cellsInView(buckets, [cellToParent(iowaCell, BUCKET_RES)]);

    expect(visible).toContain(iowaCell);
    expect(visible).not.toContain(vermontCell);
  });

  it('ignores ancestors that hold nothing', () => {
    const buckets = bucketByAncestor([at(41.9, -93.5)]);
    expect(cellsInView(buckets, ['8f1d2c3a4b5c6d7'])).toEqual([]);
  });

  it('cuts a national grid down to a viewport', () => {
    // The whole point, expressed as a number: a street-level view should be
    // looking at a small fraction of the country, not all of it.
    const cells = [];
    for (let lat = 32; lat <= 47; lat += 0.5) {
      for (let lon = -120; lon <= -70; lon += 0.5) cells.push(at(lat, lon));
    }
    const buckets = bucketByAncestor(cells);
    const visible = cellsInView(buckets, ancestorsInView({
      west: -94.2, south: 41.4, east: -92.8, north: 42.4,
    }));

    expect(visible.length).toBeLessThan(cells.length / 20);
    expect(visible.length).toBeGreaterThan(0);
  });
});
