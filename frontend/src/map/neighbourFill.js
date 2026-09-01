import { gridRing } from 'h3-js';

/**
 * Giving the treeless hexagons the colour of the country around them.
 *
 * Two thirds of Iowa and Kansas carry under 5% tree canopy, so they are never
 * scored and were drawn as flat neutral tiles. That is honest and it leaves
 * the middle of the map looking like an absence. Filling them from their
 * neighbours makes the map continuous.
 *
 * **What the colour means changes, and that is the whole point to be careful
 * about.** A scored hexagon says "this is what the trees here are doing". A
 * filled one says "this is what foliage is doing around here" — a regional
 * reading, not a claim about ground with no trees on it. It is drawn fainter
 * for that reason and named separately in the legend, so the two are never
 * confused for one another.
 */

/**
 * How far to look for a scored neighbour before giving up, in hexagon steps.
 *
 * Each step is about 3.7 km. Chosen off the measured curve rather than picked:
 * across Iowa and Kansas, ring 3 fills 67% and 51% of the treeless cells,
 * ring 5 fills 84% and 67%, and ring 8 reaches 98% and 82% but costs several
 * times as much work. 5 is where the curve is still steep and the cost is not.
 *
 * There is a limit worth keeping regardless of cost. A hexagon 30 km from the
 * nearest tree is in the middle of open country, and colouring it from that
 * distance is extrapolation rather than a local reading -- it would be
 * painting the Great Plains with a forecast borrowed from a river valley.
 */
export const MAX_RING = 5;

/**
 * Which scored cells each unscored cell should take its colour from.
 *
 * Computed once for a grid rather than per date. The neighbours of a hexagon
 * never change, and only the values in them do, so recomputing this on every
 * slider tick would be spending the expensive half of the work over and over.
 *
 * Rings are searched outwards and the search stops at the first ring that
 * finds anything: a cell one step from real forest should read as that forest,
 * not as an average of everything within three steps that happens to include
 * it. Beyond [MAX_RING] a cell is genuinely in the middle of open country and
 * gets no fill at all.
 */
export function donorsFor(bareH3, scored, maxRing = MAX_RING) {
  const donors = new Map();
  for (const h3 of bareH3) {
    for (let k = 1; k <= maxRing; k += 1) {
      // The ring at k, not the disk. gridDisk returns everything within k --
      // O(k squared) cells -- so searching outwards with it re-walks every
      // ring already rejected, and the whole neighbourhood again at each step.
      // A ring is O(k), and the rings below it have just been searched.
      const found = gridRing(h3, k).filter((n) => scored.has(n));
      if (found.length > 0) {
        donors.set(h3, found);
        break;
      }
    }
  }
  return donors;
}

/**
 * The mean of the donors' progression, and the stage that the mean falls in.
 *
 * Averaging progression rather than voting on stage keeps the result on the
 * same continuous ramp the scored cells use, so a filled cell between a
 * "partial" and a "near peak" neighbourhood lands between them instead of
 * snapping to whichever had more votes.
 *
 * Donors without a forecast are skipped rather than counted as zero. A
 * neighbour that has not been scored yet knows nothing; treating it as "no
 * change" would drag whole regions green.
 */
export function fillValue(donorH3s, byH3) {
  let total = 0;
  let n = 0;
  for (const h3 of donorH3s) {
    const cell = byH3.get(h3);
    if (cell?.progression == null || cell.stage == null) continue;
    total += cell.progression;
    n += 1;
  }
  if (n === 0) return null;
  return total / n;
}
