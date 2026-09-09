import { addDays, daysBetween } from '../season';

/**
 * Month grids and range picking, kept apart from the component that draws
 * them.
 *
 * All of it is calendar arithmetic on 'YYYY-MM-DD' strings, which is the form
 * every date in this project already travels in -- the season, the export's
 * day files and the URL all use it, and converting to Date objects at the
 * edges is what introduces timezone bugs rather than avoiding them.
 */

const pad = (n) => String(n).padStart(2, '0');

/** First day of the month a date falls in. */
export function startOfMonth(iso) {
  const [y, m] = iso.split('-').map(Number);
  return `${y}-${pad(m)}-01`;
}

/** Months are the one unit addDays cannot express, because they vary. */
export function addMonths(iso, n) {
  const [y, m] = iso.split('-').map(Number);
  const total = (y * 12) + (m - 1) + n;
  return `${Math.floor(total / 12)}-${pad((total % 12) + 1)}-01`;
}

export function monthName(iso) {
  const [y, m] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, 1))
    .toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });
}

/** Sunday first, for a United States audience. */
export const WEEKDAYS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

/**
 * The weeks of a month, padded to whole weeks with nulls.
 *
 * Nulls rather than the neighbouring month's days: those are only ever
 * decoration here, and a grid that holds dates it will not let you pick is a
 * grid every reader has to be told about.
 */
export function monthGrid(anchor) {
  const [y, m] = anchor.split('-').map(Number);
  const lead = new Date(Date.UTC(y, m - 1, 1)).getUTCDay();
  const length = new Date(Date.UTC(y, m, 0)).getUTCDate();

  const cells = Array(lead).fill(null);
  for (let d = 1; d <= length; d += 1) cells.push(`${y}-${pad(m)}-${pad(d)}`);
  while (cells.length % 7 !== 0) cells.push(null);

  const weeks = [];
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
  return { year: y, month: m, weeks };
}

/**
 * One click of a range picker.
 *
 * Two clicks make a range: the first sets both ends and leaves it open, the
 * second closes it. Clicking before the open end starts again rather than
 * extending backwards -- someone who clicks the 3rd and then the 1st has
 * changed their mind about where the trip starts, not asked for a range that
 * runs backwards, and the model would drop an inverted stay anyway.
 */
export function pickRange(range, clicked) {
  if (!range?.picking) return { from: clicked, to: clicked, picking: true };
  if (clicked < range.from) return { from: clicked, to: clicked, picking: true };
  return { from: range.from, to: clicked, picking: false };
}

/**
 * What to draw while a range is half-chosen, so the grid follows the pointer.
 *
 * Without it the second click is made blind: the first day is marked and
 * nothing else moves until the range is already committed.
 */
export function previewRange(range, hovered) {
  if (!range) return { from: null, to: null };
  if (!range.picking || !hovered || hovered < range.from) {
    return { from: range.from, to: range.to };
  }
  return { from: range.from, to: hovered };
}

/** Whether a day is inside a range, for the cell's own styling. */
export function within(day, from, to) {
  return Boolean(day && from && to && day >= from && day <= to);
}

/** How many nights a range spans, for a label that has to say so. */
export function nightsIn(from, to) {
  return from && to ? daysBetween(from, to) : 0;
}

/**
 * Months the picker is allowed to show, so its arrows can be disabled rather
 * than walking off into a year the export knows nothing about.
 */
export function monthRange(min, max) {
  return { first: startOfMonth(min), last: startOfMonth(max) };
}

/** The month to open on: where the trip already is, else the season's start. */
export function openingMonth(from, min, max) {
  const anchor = from && from >= min && from <= max ? from : min;
  return startOfMonth(anchor);
}

/** Clamped one-day step, used by the keyboard handler. */
export function stepDay(iso, n, min, max) {
  const next = addDays(iso, n);
  if (next < min) return min;
  if (next > max) return max;
  return next;
}
