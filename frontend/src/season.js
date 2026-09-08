/**
 * Season arithmetic, in one place.
 *
 * These lived privately inside MapView until the trip planner needed the same
 * three functions and, more to the point, the same horizon. The forecast
 * horizon is a claim about what the data *is* -- past it the map is showing a
 * typical year rather than a forecast, and ADR-0005 says never to present one
 * as the other. A second copy of that number is a second chance to get it
 * wrong on a page nobody re-checked.
 */

/** Days ahead that a real forecast exists. Beyond this the export is climatology. */
export const FORECAST_HORIZON_DAYS = 16;

export function isoToday() {
  return new Date().toISOString().slice(0, 10);
}

export function addDays(iso, n) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
}

export function daysBetween(from, to) {
  const [ay, am, ad] = from.split('-').map(Number);
  const [by, bm, bd] = to.split('-').map(Number);
  return Math.round((Date.UTC(by, bm - 1, bd) - Date.UTC(ay, am - 1, ad)) / 86_400_000);
}

/** Real, not merely well-shaped: 2026-13-99 passes a regex and is not a date. */
export function isDate(s) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s || '')) return false;
  const [y, m, d] = s.split('-').map(Number);
  const t = new Date(Date.UTC(y, m - 1, d));
  return t.getUTCFullYear() === y && t.getUTCMonth() === m - 1 && t.getUTCDate() === d;
}

/** The last day carrying a real forecast rather than climatology. */
export function horizonDate() {
  return addDays(isoToday(), FORECAST_HORIZON_DAYS);
}

/** Keeps a date inside the exported season, so a control cannot point off the end. */
export function clampToSeason(iso, from, to) {
  if (!from || !to) return iso;
  if (iso < from) return from;
  if (iso > to) return to;
  return iso;
}
