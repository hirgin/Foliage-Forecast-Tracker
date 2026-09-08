import { cellToParent, gridDisk, latLngToCell, polygonToCells } from 'h3-js';

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
  // A viewport with no extent at all is a resize mid-flight, not a place. The
  // seeding below would happily answer "which cell contains this point" for it
  // and draw a bucket of hexagons somewhere off Africa for a frame.
  if (east === west && north === south) return [];
  const dx = (east - west) * pad;
  const dy = (north - south) * pad;
  const w = west - dx;
  const e = east + dx;
  const s = south - dy;
  const n = north + dy;
  const ring = [[s, w], [n, w], [n, e], [s, e], [s, w]];

  const found = new Set();
  try {
    for (const c of polygonToCells(ring, res, false)) found.add(c);
  } catch {
    // A degenerate viewport -- zero height during a resize, or coordinates
    // that have run past the antimeridian -- contributes nothing rather than
    // taking the map down. The seeds below still cover it.
  }

  // **The polygon fill alone goes blank when zoomed in, which is why this
  // exists.** polygonToCells returns the cells whose *centre* lies inside the
  // polygon, and a res 3 cell is about 176 km across. A viewport narrower than
  // that can easily contain no centre at all, so the fill came back empty and
  // the map drew nothing: measured at Pierre, 159 km across found 3 ancestors,
  // 79 km found none, and every zoom below that found none either.
  //
  // Whether it broke depended on where res 3 centres happened to fall, so it
  // looked like a regional fault -- South Dakota blank while Maine was fine --
  // rather than the zoom-dependent one it is.
  //
  // Seeding from the corners and the middle asks the opposite question: which
  // cell *contains* this point. That can never come back empty. Their
  // immediate neighbours come too, so a viewport sitting inside one cell still
  // pulls in the ring around it and nothing pops in at the edges on a pan.
  const seeds = [[s, w], [n, w], [n, e], [s, e], [(s + n) / 2, (w + e) / 2]];
  for (const [lat, lon] of seeds) {
    try {
      for (const c of gridDisk(latLngToCell(lat, lon, res), 1)) found.add(c);
    } catch {
      // An out-of-range coordinate from a mid-gesture viewport. Skip it.
    }
  }
  return [...found];
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
