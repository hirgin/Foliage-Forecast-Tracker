import { cellToLatLng, cellToParent } from 'h3-js';

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
 * where it is, which is the same problem wearing a different hat. So the grid
 * is bucketed once by res 3 ancestor — a res 3 cell is about 176 km across —
 * and each bucket remembers the box its cells actually occupy. After that,
 * "what is visible" is a scan of ~726 boxes, which is nothing.
 *
 * **The box is measured from the cells, not from the ancestor's own geometry,
 * and that is the point.** H3's parent-child relationship is not strictly
 * geometric: `cellToParent(c, 3)` and `latLngToCell(centreOf(c), 3)` disagree
 * near boundaries. Any scheme that picks ancestors by geography and then looks
 * them up by parentage loses cells in the gap between the two. Asking the
 * bucket where its own cells are cannot disagree with itself.
 */

/**
 * Resolution to bucket by. Matches the shard resolution the export already
 * uses for timelines, so the two describe the same neighbourhoods.
 */
export const BUCKET_RES = 3;

/**
 * How far outside the viewport a bucket still counts as visible, in degrees.
 *
 * A cell whose centre sits just outside the edge still draws into the frame,
 * because a hexagon is about 3 km across and is placed by its centre. Without
 * the margin the outermost row is clipped away and the map ends in a ragged
 * edge that moves as you pan.
 */
const EDGE_MARGIN_DEG = 0.06;

/**
 * The grid grouped by ancestor, with the extent of each group.
 *
 * This is the one pass over everything, and it is why the rest is cheap: after
 * it, no code has to look at a cell to know whether it is on screen. Measured
 * at about 200 ms for 217,412 cells, paid once per dataset rather than per
 * view.
 */
export function bucketByAncestor(h3List, res = BUCKET_RES) {
  const buckets = new Map();
  for (const h3 of h3List) {
    const parent = cellToParent(h3, res);
    const [lat, lon] = cellToLatLng(h3);
    const bucket = buckets.get(parent);
    if (bucket) {
      bucket.cells.push(h3);
      if (lat < bucket.south) bucket.south = lat;
      if (lat > bucket.north) bucket.north = lat;
      if (lon < bucket.west) bucket.west = lon;
      if (lon > bucket.east) bucket.east = lon;
    } else {
      buckets.set(parent, {
        cells: [h3], south: lat, north: lat, west: lon, east: lon,
      });
    }
  }
  return buckets;
}

/**
 * The cells of whichever buckets the viewport touches.
 *
 * Padded by a quarter of the viewport on every side. An edge almost never
 * lands on a bucket boundary, and without the margin the hexagons in the strip
 * between the two would pop in only after a pan finished — visibly, because
 * that strip is exactly where the eye is when someone drags the map.
 */
export function cellsInView(buckets, bounds, pad = 0.25) {
  if (!buckets || !bounds) return [];
  const { west, south, east, north } = bounds;
  // A viewport with no extent at all is a resize mid-flight rather than a
  // place, and should draw nothing for that frame rather than a stale bucket.
  if (east === west && north === south) return [];
  const dx = (east - west) * pad;
  const dy = (north - south) * pad;
  const w = west - dx - EDGE_MARGIN_DEG;
  const e = east + dx + EDGE_MARGIN_DEG;
  const s = south - dy - EDGE_MARGIN_DEG;
  const n = north + dy + EDGE_MARGIN_DEG;

  const out = [];
  for (const b of buckets.values()) {
    if (b.east < w || b.west > e || b.north < s || b.south > n) continue;
    out.push(...b.cells);
  }
  return out;
}
