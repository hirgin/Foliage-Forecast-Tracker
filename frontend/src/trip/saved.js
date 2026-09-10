/**
 * Trips kept on this device.
 *
 * There is no account to save against and no server to save to, which is the
 * same reason a trip lives in its URL: nothing about this site knows who you
 * are. localStorage is the honest version of "save" here -- it is this
 * browser, on this device, and it is not a backup. The list says so rather
 * than implying a locker somewhere.
 *
 * Every entry is the encoded trip, which is the same string the address bar
 * carries. Saving is therefore reversible by hand, and a saved trip that
 * outlives a change to the encoding still decodes through the same path as an
 * old link.
 */

export const KEY = 'foliage.trips.v1';

/** Above this the list stops being a list and starts being a filing problem. */
export const MAX_SAVED = 12;

/**
 * localStorage, or a null object when it is not usable.
 *
 * Reading it can throw outright, not merely return null: a private window, a
 * browser set to block site data, or a thumbnailer with no storage at all. A
 * planner that crashes because it could not offer to remember something is
 * worse than one that quietly cannot remember.
 */
function browserStore() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function readTrips(store = browserStore()) {
  if (!store) return [];
  try {
    const raw = store.getItem(KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    // Anything without the two fields that matter is not a trip, however it
    // got in there -- a half-written value, or a future version's shape.
    return parsed.filter((t) => t && typeof t.stops === 'string' && t.stops);
  } catch {
    return [];
  }
}

function writeTrips(trips, store = browserStore()) {
  if (!store) return false;
  try {
    store.setItem(KEY, JSON.stringify(trips.slice(0, MAX_SAVED)));
    return true;
  } catch {
    // Out of quota, or storage disabled between the read and the write.
    return false;
  }
}

/**
 * Saves a trip, newest first.
 *
 * Saving the same stops twice moves the existing entry to the top instead of
 * making a second copy of it: pressing save again is how someone confirms
 * they meant it, not how they ask for a duplicate.
 */
export function addTrip({ stops, label, dates }, store = browserStore()) {
  if (!stops) return readTrips(store);
  const existing = readTrips(store).filter((t) => t.stops !== stops);
  const next = [{
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    stops,
    label: label || 'Trip',
    dates: dates || '',
    savedAt: new Date().toISOString(),
  }, ...existing].slice(0, MAX_SAVED);
  writeTrips(next, store);
  return next;
}

export function removeTrip(id, store = browserStore()) {
  const next = readTrips(store).filter((t) => t.id !== id);
  writeTrips(next, store);
  return next;
}

/** Whether these exact stops are already saved, so the button can say so. */
export function isSaved(stops, store = browserStore()) {
  return Boolean(stops) && readTrips(store).some((t) => t.stops === stops);
}
