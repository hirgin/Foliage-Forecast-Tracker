import { cellToParent, polygonToCells } from 'h3-js';

/**
 * Drawing only the hexagons that are on screen.
 *
 * The export is national and the map hands deck.gl all of it: at res 6 that is
 * 217,412 hexagons, of which a street-level viewport can show perhaps two
 * thousand. Everything else is geometry built, uploaded and rasterised
 * off-screen. Measured on the deployed site, the two costs that come with it
 * are 336 ms of boundary generation and 1.2 seconds of neighbour searching,
 * both on the main thread, both paid for ground nobody is looking at.
 *
 * Filtering by bounding box would mean asking every one of the 217,412 cells
 * where it is, which is the same problem wearing a different hat. H3 already
 * knows: every cell has an ancestor, so bucketing the grid once by res 3
 * ancestor turns "what is visible" into "which handful of buckets does the
 * viewport touch". A res 3 cell is about 176 km across, so a zoom 7 viewport
 * touches one or a few.
 */

/**
 * Resolution to bucket by. Matches the shard resolution the export already
 * uses for timelines, so the two describe the same neighbourhoods.
 */
export const BUCKET_RES = 3;

/**
 * The grid grouped by ancestor, built once per dataset.
 *
 * This is the one pass over everything, and it is why the rest is cheap: after
 * it, no code has to look at a cell to know whether it is on screen.
 */
export function bucketByAncestor(h3List, res = BUCKET_RES) {
  const buckets = new Map();
  for (const h3 of h3List) {
    const parent = cellToParent(h3, res);
    const bucket = buckets.get(parent);
    if (bucket) bucket.push(h3);
    else buckets.set(parent, [h3]);
  }
  return buckets;
}

/**
 * The ancestors covering a viewport.
 *
 * Padded by one bucket's worth on every side. A viewport edge almost never
 * lands on a bucket boundary, and without the margin the hexagons in the strip
 * between the two would pop in only after a pan finished -- visibly, because
 * that strip is exactly where the eye is when someone drags the map.
 */
export function ancestorsInView(bounds, res = BUCKET_RES, pad = 0.25) {
  if (!bounds) return [];
  const { west, south, east, north } = bounds;
  const dx = (east - west) * pad;
  const dy = (north - south) * pad;
  const ring = [
    [south - dy, west - dx],
    [north + dy, west - dx],
    [north + dy, east + dx],
    [south - dy, east + dx],
    [south - dy, west - dx],
  ];
  try {
    return polygonToCells(ring, res, false);
  } catch {
    // A degenerate viewport -- zero height during a resize, or coordinates
    // that have run past the antimeridian -- should draw nothing this frame
    // rather than take the map down.
    return [];
  }
}

/** The cells of whichever buckets are in view, flattened. */
export function cellsInView(buckets, ancestors) {
  const out = [];
  for (const a of ancestors) {
    const bucket = buckets.get(a);
    if (bucket) out.push(...bucket);
  }
  return out;
}
