import { describe, it, expect } from 'vitest';
import { latLngToCell, cellToParent } from 'h3-js';
import { BUCKET_RES, bucketByAncestor, cellsInView } from './viewport';

/** A patch of res 6 cells around a point, as the export would hold them. */
function patch(lat, lon, span = 0.6, step = 0.04) {
  const out = new Set();
  for (let dla = -span / 2; dla <= span / 2; dla += step) {
    for (let dlo = -span; dlo <= span; dlo += step) {
      out.add(latLngToCell(lat + dla, lon + dlo, 6));
    }
  }
  return [...out];
}

const box = (lat, lon, deg) => ({
  west: lon - deg / 2, east: lon + deg / 2,
  south: lat - deg / 4, north: lat + deg / 4,
});

describe('bucketByAncestor', () => {
  it('groups cells under their res 3 ancestor', () => {
    const cells = patch(44.37, -100.35);
    const buckets = bucketByAncestor(cells);
    for (const [parent, bucket] of buckets) {
      for (const c of bucket.cells) expect(cellToParent(c, BUCKET_RES)).toBe(parent);
    }
    const total = [...buckets.values()].reduce((a, b) => a + b.cells.length, 0);
    expect(total).toBe(cells.length);
  });

  it('records the extent its cells actually occupy', () => {
    // The box is measured from the cells rather than taken from the ancestor's
    // own geometry, and that is what makes the lookup exact: H3 parentage and
    // geography disagree near boundaries, so a box derived from the parent
    // would not describe the children it holds.
    const buckets = bucketByAncestor(patch(44.37, -100.35));
    for (const b of buckets.values()) {
      expect(b.north).toBeGreaterThanOrEqual(b.south);
      expect(b.east).toBeGreaterThanOrEqual(b.west);
    }
  });
});

describe('cellsInView', () => {
  const cells = patch(44.37, -100.35, 1.2, 0.03);
  const buckets = bucketByAncestor(cells);

  it('returns cells at every zoom, not just when the view is wide', () => {
    // THE REGRESSION. Selection used to ask polygonToCells which res 3 cells
    // lay inside the viewport, and that returns the cells whose *centre* is
    // inside. A res 3 cell is about 176 km across, so a viewport narrower than
    // that contained no centre, came back empty, and the map drew nothing.
    // Measured around Pierre: 159 km across found 3 ancestors, 79 km found
    // none, and every zoom below that found none either.
    //
    // It read as a regional fault -- South Dakota blank while Maine was fine --
    // because whether it broke depended on where res 3 centres happen to fall.
    for (const deg of [4, 2, 1, 0.5, 0.25, 0.1, 0.05]) {
      const got = cellsInView(buckets, box(44.37, -100.35, deg));
      expect(got.length).toBeGreaterThan(0);
    }
  });

  it('covers every cell a viewport actually shows', () => {
    // The property that matters: no hexagon on screen may be filtered out.
    const view = box(44.37, -100.35, 0.5);
    const got = new Set(cellsInView(buckets, view));
    // Sample the viewport and check each cell that really exists there was
    // selected. Points landing on ground the grid does not cover are skipped,
    // or this would measure the fixture rather than the lookup.
    let missed = 0;
    for (let i = 0; i <= 12; i += 1) {
      for (let j = 0; j <= 12; j += 1) {
        const lat = view.south + ((view.north - view.south) * j) / 12;
        const lon = view.west + ((view.east - view.west) * i) / 12;
        const c = latLngToCell(lat, lon, 6);
        if (buckets.has(cellToParent(c, BUCKET_RES)) && !got.has(c)) missed += 1;
      }
    }
    expect(missed).toBe(0);
  });

  it('narrows as you zoom in', () => {
    // The whole reason for filtering: a street-level view must not hand
    // deck.gl the country.
    const wide = cellsInView(buckets, box(44.37, -100.35, 4)).length;
    const tight = cellsInView(buckets, box(44.37, -100.35, 0.1)).length;
    expect(tight).toBeLessThan(wide);
  });

  it('returns nothing rather than throwing on a degenerate viewport', () => {
    // Happens for real during a resize, when the container briefly has no
    // height. Drawing nothing for one frame is recoverable; an exception
    // inside a render is not.
    expect(cellsInView(buckets, null)).toEqual([]);
    expect(cellsInView(null, box(44.37, -100.35, 1))).toEqual([]);
    expect(cellsInView(buckets, {
      west: 0, south: 0, east: 0, north: 0,
    })).toEqual([]);
  });

  it('pads, so hexagons do not pop in at the edges while panning', () => {
    const tight = cellsInView(buckets, box(44.37, -100.35, 1), 0).length;
    const padded = cellsInView(buckets, box(44.37, -100.35, 1), 0.25).length;
    expect(padded).toBeGreaterThanOrEqual(tight);
  });
});
