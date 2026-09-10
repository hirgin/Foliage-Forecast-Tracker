import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { cellToLatLng, cellToParent } from 'h3-js';
import { useMeta, useTimelines, useForecast, usePlaces } from '../api/hooks';
import { h3ForPlace, fetchBareCells, resolutionForZoom } from '../api/client';
import { nearestPlace } from '../api/places';
import FoliageMap from '../map/FoliageMap';
import { seasonDates } from '../api/packed';
import { hashParam, setHashParam } from '../routing';
import { stageLabel, stageColor } from '../map/colors';
import PlaceSearch from '../components/PlaceSearch';
import TripStrip from '../components/TripStrip';
import DateRange from '../components/DateRange';
import { formatDay } from '../components/TimeSlider';
import { addDays, clampToSeason, horizonDate, isoToday } from '../season';
import {
  MAX_STOPS, SHIFT_LIMIT, decodeStops, encodeStops, planTrip, bestShift, bestSpacing,
  countAtPeak, tripLabel, tripSpan,
} from '../trip/plan';
import { readTrips, addTrip, removeTrip, isSaved } from '../trip/saved';

/**
 * Plan a trip against the forecast.
 *
 * Everything here reads the same export the map does -- one timeline shard per
 * stop, which is what clicking a hexagon already costs. No new endpoint, no
 * row written, and the trip itself lives in the URL, so sharing a plan is
 * sharing a link and there is nothing to sign in to.
 *
 * The output is deliberately a *window* rather than a day. Peak date comes
 * from where a place sits, which does not move from year to year, so "what
 * will the 12th look like" has the same answer every year; the question the
 * data can actually answer well is which week to go.
 */
export default function Trip({ nav }) {
  const meta = useMeta();
  const [stops, setStops] = useState(() => decodeStops(hashParam('s')));
  const [shift, setShift] = useState(0);

  useEffect(() => {
    setHashParam('s', encodeStops(stops));
  }, [stops]);

  // The URL is read at mount, which is not the only time it changes. Someone
  // pasting a trip link while already on this page gets a hashchange and no
  // reload, so without this the address bar shows their friend's trip and the
  // page goes on showing theirs -- in a feature whose entire sharing model is
  // the link. Writes above go through replaceState and fire no event, so this
  // only ever hears changes from outside; the encoded comparison stops an
  // equivalent trip from replacing state and bouncing off the writer.
  useEffect(() => {
    const onHash = () => {
      const incoming = decodeStops(hashParam('s'));
      setStops((current) => {
        if (encodeStops(current) === encodeStops(incoming)) return current;
        // A trip that arrives from outside should be read as its author sent
        // it, not through whatever the slider was left on: inheriting a +10
        // would show every one of their stops ten days off.
        setShift(0);
        setFocusIndex(0);
        setPending(null);
        return incoming;
      });
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // The stop being composed, not yet added. Picking a place used to add it on
  // the spot, which put the date after the decision it belonged to: by the
  // time you reached the date field the stop already existed and had to be
  // corrected. Now a place is staged, the date is set beside it, and the two
  // are committed together.
  const [pending, setPending] = useState(null);

  const timelines = useTimelines(
    // The staged place is fetched too, so the picker can tint the days you
    // are choosing between by what the forecast says that cell is doing.
    [...stops.map((s) => s.h3), pending?.h3].filter(Boolean),
  );

  // Which stop the map is showing. The map draws one day and a trip has
  // several, so it follows a stop rather than trying to average them: pick a
  // stop and the map answers "what does it look like round here, that day".
  const [focusIndex, setFocusIndex] = useState(0);
  // The date the next stop will get. It used to be inferred silently -- today
  // for the first stop, a step on from the last one after that -- which is a
  // reasonable guess and a bad secret: the only way to see it was to add the
  // stop and then correct it on its card. Now it is a field, seeded with the
  // same guess and stepped on after each add, so adding several in a row still
  // takes one click each.
  const [nextFrom, setNextFrom] = useState(null);
  const [nextTo, setNextTo] = useState(null);
  // Bumped on each add, and used to remount the search box so it clears.
  const [added, setAdded] = useState(0);
  const [saved, setSaved] = useState(() => readTrips());
  // What the share control last did, so it can say so. Cleared on a timer
  // because a button that stays "Copied" is lying by the second press.
  const [shared, setShared] = useState(null);
  const barRef = useRef(null);
  const [mapRes, setMapRes] = useState(6);
  const [bareCells, setBareCells] = useState([]);
  const [centre, setCentre] = useState(null);
  // The place index is 16 MB. Search loads it on focus; the map needs it only
  // to name a hexagon someone drops a pin on, so it is pulled when a pointer
  // arrives over the map rather than on page load.
  const [wantPlaces, setWantPlaces] = useState(false);
  const { data: places } = usePlaces(wantPlaces);

  useEffect(() => {
    let live = true;
    fetchBareCells(mapRes).then((h3) => { if (live) setBareCells(h3); });
    return () => { live = false; };
  }, [mapRes]);

  const dates = useMemo(
    () => (meta.data ? seasonDates(meta.data.seasonStart, meta.data.seasonEnd) : []),
    [meta.data],
  );
  const bounds = meta.data
    ? { from: meta.data.seasonStart, to: meta.data.seasonEnd }
    : null;

  // Falls back to the season's own bounds until meta arrives, so the control
  // always holds a date a date input will accept.
  const arriving = nextFrom || (bounds ? clampToSeason(isoToday(), bounds.from, bounds.to) : '');
  // Departure never precedes arrival. A stay of one day is the default, which
  // is what the planner did before stays existed.
  const departing = nextTo && nextTo >= arriving ? nextTo : arriving;

  const planned = planTrip(stops, timelines.byH3, shift);
  const focused = planned[Math.min(focusIndex, Math.max(0, planned.length - 1))] || null;
  const mapDate = focused?.from || bounds?.from || null;
  const mapData = useForecast(mapDate, mapRes);

  // Stops are res 6; zoomed out the map draws res 4 or 5, where a res 6
  // address matches nothing. Highlight the ancestor actually on screen.
  const highlighted = useMemo(
    () => new Set(stops.map((s) => (mapRes === 6 ? s.h3 : cellToParent(s.h3, mapRes)))),
    [stops, mapRes],
  );
  const atPeak = countAtPeak(planned);
  const ready = planned.filter((p) => p.ready).length;
  // Past the horizon the export is climatology, and ADR-0005 is explicit that
  // it must never be presented as a forecast. Trips are planned months out, so
  // this is the normal case here rather than an edge one.
  const horizon = horizonDate();
  const beyondHorizon = planned.some((p) => p.to > horizon);

  // Both ways of choosing a stop -- searching for it and clicking it on the
  // map -- stage it here rather than adding it, so neither can slip a stop in
  // without the same confirmation.
  const stage = (h3, name) => {
    if (!h3 || stops.length >= MAX_STOPS) return;
    setPending({ h3, name });
  };

  const chooseFromSearch = async (place) => {
    if (stops.length >= MAX_STOPS) return;
    const h3 = await h3ForPlace(place, null);
    stage(h3, place.name);
  };

  const commit = (e) => {
    e?.preventDefault();
    if (!pending || !arriving || stops.length >= MAX_STOPS) return;
    // Stored unshifted, so the stop *displays* on the date the field showed.
    //
    // Everything on this page is drawn through planTrip, which adds the shift
    // to every stop. The field was not, so with the slider at +10 a stop
    // entered for the 9th appeared as the 19th -- the date you typed was not
    // the date you got, which reads as the planner skipping days at random.
    // Converting here keeps the field's promise and leaves the slider free to
    // move the whole trip afterwards, which is what it is for.
    setStops([...stops, {
      h3: pending.h3,
      from: addDays(arriving, -shift),
      to: addDays(departing, -shift),
      name: pending.name,
    }]);
    // One day, not two. Two was a guess about how a foliage trip is paced --
    // a night somewhere, then move on -- and it read as the planner skipping a
    // date: add the 10th and the field offers the 12th, the 11th gone with
    // nothing to say why. The next day is what the next stop most obviously
    // means, and anyone touring slower can still set it.
    // The next stop starts the day after this one ends, so a three-night stay
    // advances three days rather than one -- the trip stays contiguous however
    // long each stay is.
    const next = clampToSeason(addDays(departing, 1), bounds?.from, bounds?.to);
    setNextFrom(next);
    setNextTo(next);
    setFocusIndex(stops.length);
    setPending(null);
    setAdded((n) => n + 1);
  };

  // Clicking the map either jumps to a stop already there or drops a new one.
  const onMapSelect = useCallback((h3) => {
    if (!h3) return;
    const existing = stops.findIndex(
      (s) => s.h3 === h3 || (mapRes !== 6 && cellToParent(s.h3, mapRes) === h3),
    );
    if (existing >= 0) { setFocusIndex(existing); return; }

    // A 22 km hexagon is not a place to stand, so a click at that zoom cannot
    // become a stop. It used to return here and do nothing at all, under a
    // caption promising that any hexagon could be clicked -- so zoom to it
    // instead, which is what someone aiming at a whole state actually wants
    // next and leaves the click meaning something.
    if (mapRes !== 6) {
      const [clat, clon] = cellToLatLng(h3);
      setCentre({ lat: clat, lon: clon, nonce: Date.now() });
      return;
    }
    if (stops.length >= MAX_STOPS) return;

    const [lat, lon] = cellToLatLng(h3);
    // A hexagon has an address, not a name. Falling back to coordinates keeps
    // the click working on the rare first one that beats the index loading.
    const near = nearestPlace(places, lat, lon);
    stage(h3, near?.name || `${lat.toFixed(2)}, ${lon.toFixed(2)}`);
    barRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [stops, mapRes, places]);

  const encoded = encodeStops(stops);
  const alreadySaved = isSaved(encoded, undefined);

  /**
   * Hand the trip over, by whatever means the browser has.
   *
   * The share sheet where there is one, the clipboard otherwise, and the bare
   * URL to select if neither works -- clipboard access needs a secure context
   * and a permission, and failing silently would leave someone pressing a
   * button that does nothing.
   */
  const shareTrip = async () => {
    const url = window.location.href;
    if (navigator.share) {
      try {
        await navigator.share({ title: 'Foliage trip', url });
        setShared('shared');
        return;
      } catch (err) {
        // Cancelling the sheet is not a failure, and must not fall through to
        // quietly copying instead.
        if (err?.name === 'AbortError') return;
      }
    }
    try {
      await navigator.clipboard.writeText(url);
      setShared('copied');
    } catch {
      setShared('manual');
    }
  };

  useEffect(() => {
    if (!shared || shared === 'manual') return undefined;
    const t = setTimeout(() => setShared(null), 2400);
    return () => clearTimeout(t);
  }, [shared]);

  const saveTrip = () => {
    const span = tripSpan(stops);
    setSaved(addTrip({
      stops: encoded,
      label: tripLabel(stops),
      dates: span ? `${formatDay(span.from)} – ${formatDay(span.to)}` : '',
    }));
  };

  // Loading goes through the URL rather than straight into state, so a saved
  // trip arrives by exactly the path a shared link does -- including the
  // reset of the shift that comes with it.
  const openSaved = (entry) => {
    window.location.hash = `#/trip?s=${encodeURIComponent(entry.stops)}`;
  };

  const showOnMap = (i) => {
    setFocusIndex(i);
    const [lat, lon] = cellToLatLng(stops[i].h3);
    setCentre({ lat, lon, nonce: Date.now() });
  };

  // Both ends arrive together from the picker, which cannot produce an
  // inverted range, so there is no end to push out of the way any more.
  const setStopStay = (i, from, to) => {
    setStops(stops.map((s, j) => (j === i ? { ...s, from, to } : s)));
  };

  const removeStop = (i) => setStops(stops.filter((_, j) => j !== i));

  const move = (i, by) => {
    const j = i + by;
    if (j < 0 || j >= stops.length) return;
    const next = [...stops];
    [next[i], next[j]] = [next[j], next[i]];
    setStops(next);
  };

  /**
   * Re-time each stop rather than sliding the trip.
   *
   * Deliberately a second button and never the default: shifting is a view of
   * the trip you planned, and this rewrites the dates you chose. It keeps the
   * order and the length of every stay, so what changes is when each one
   * happens, not what the trip is.
   */
  const fitEachStop = () => {
    const placed = bestSpacing(stops, timelines.byH3, bounds);
    if (!placed) return;
    setStops(stops.map((s, i) => ({ ...s, ...placed[i] })));
    // The dates themselves moved, so a shift left over from exploring would
    // now be applied on top of an answer that already accounts for it.
    setShift(0);
  };

  const findBestWeek = () => {
    const best = bestShift(stops, timelines.byH3, bounds);
    if (best) setShift(best.shift);
  };

  return (
    <div className="page">
      <div className="page__inner page__inner--wide">
        <header className="page__head">
          <h1>Plan a trip</h1>
          {nav}
        </header>

        <p className="lede">
          Add the places you are going and when you expect to be there. The map
          shows one day across the whole country; this shows your stops across
          the whole season, so you can see whether the trip lands on peak, and what
          moving it a few days would do.
        </p>

        <section className="tripbar" ref={barRef}>
          {/* A form, so Enter submits and the button is a real submit rather
              than a click handler wearing a button's clothes. */}
          <form className="tripbar__row" onSubmit={commit}>
            <div className="tripbar__find">
              {/* Remounted after each add so the box clears and is ready for
                  the next place, rather than holding the last one's name. */}
              <PlaceSearch key={added} onSelect={chooseFromSearch} />
            </div>
            {bounds && (
              <DateRange
                label="Staying"
                from={arriving}
                to={departing}
                min={bounds.from}
                max={bounds.to}
                series={pending ? timelines.byH3[pending.h3]?.days : null}
                onChange={(f, t) => { setNextFrom(f); setNextTo(t); }}
              />
            )}
            <button
              type="submit"
              className="btn btn--add"
              disabled={!pending || stops.length >= MAX_STOPS}
            >
              {pending ? `Add ${pending.name}` : 'Add stop'}
            </button>
          </form>
          <p className="note">
            {stops.length >= MAX_STOPS
              ? `${MAX_STOPS} stops is the limit. Remove one to add another.`
              : pending
                ? `${pending.name} will be added for ${
                  arriving === departing
                    ? formatDay(arriving)
                    : `${formatDay(arriving)} to ${formatDay(departing)}`
                }. Change the dates first if you want different ones.`
                : 'Search for a place or click one on the map, set when you arrive and leave, then add it. The next stop starts the day after this one ends, and every stay can still be changed afterwards.'}
          </p>
        </section>

        {!stops.length && (
          <section className="callout">
            <h2>Nothing planned yet</h2>
            <p>
              Search for a town or a mountain above to add your first stop.
              Everything stays in the address bar, so copy the link to share the plan or
              keep it for later. Nothing is saved anywhere else.
            </p>
          </section>
        )}

        {stops.length > 0 && mapDate && (
          <section>
            <h2>Where the trip goes</h2>
            <p className="note">
              Coloured for <strong>{formatDay(mapDate)}</strong>, the day you would
              arrive at {focused?.name}. Pick another stop below to see its day.{' '}
              {mapRes === 6
                ? 'Click a forested hexagon to add it to the trip.'
                : 'Zoom in, or click an area, to add a stop by hand.'}
            </p>
            <div
              className="tripmap"
              onPointerEnter={() => setWantPlaces(true)}
            >
              <FoliageMap
                cells={mapData.data?.cells ?? []}
                bareCells={bareCells}
                resolution={mapRes}
                selected={highlighted}
                onSelect={onMapSelect}
                focus={centre}
                onZoom={(zoom) => setMapRes(resolutionForZoom(zoom))}
              />
            </div>
            <div className="tripmap__stops">
              {planned.map((stop, i) => (
                <button
                  type="button"
                  key={`${stop.h3}-${i}`}
                  className={i === focusIndex ? 'chip chip--on' : 'chip'}
                  onClick={() => showOnMap(i)}
                >
                  {stop.name}
                  <span>
                    {stop.from === stop.to
                      ? formatDay(stop.from)
                      : `${formatDay(stop.from)}–${formatDay(stop.to)}`}
                  </span>
                </button>
              ))}
            </div>
          </section>
        )}

        {stops.length > 0 && (
          <>
            <section>
              <h2>Your trip against the season</h2>
              <p className="note">
                Each row is a stop, each column a day. The outlined run is
                your stay. Faded means the forecast is working from a
                typical year rather than this one.
              </p>

              {dates.length > 0 && <TripStrip planned={planned} dates={dates} />}

              <div className="tripcontrols">
                <label htmlFor="shift">Shift the whole trip</label>
                <input
                  id="shift"
                  type="range"
                  min={-SHIFT_LIMIT}
                  max={SHIFT_LIMIT}
                  step="1"
                  value={shift}
                  onChange={(e) => setShift(Number(e.target.value))}
                />
                <span className="tripcontrols__readout">
                  {shift === 0
                    ? 'as planned'
                    : `${Math.abs(shift)} day${Math.abs(shift) === 1 ? '' : 's'} ${shift > 0 ? 'later' : 'earlier'}`}
                </span>
                {shift !== 0 && (
                  <button type="button" className="btn btn--quiet" onClick={() => setShift(0)}>
                    Put it back
                  </button>
                )}
              </div>
              <p className="note">
                Drags every stop together to see what leaving earlier or later
                would do. It is a preview: your dates are untouched until you
                use one of the buttons below.
              </p>

              {/* Each action says what it does to the trip, because the two
                  differ in a way the labels alone cannot carry: one is a view
                  and the other rewrites the dates you chose. */}
              <div className="tripactions">
                <div className="tripactions__one">
                  <button type="button" className="btn" onClick={findBestWeek} disabled={!ready}>
                    Find the best week
                  </button>
                  <p>
                    Slides the whole trip to the week that catches the most peak,
                    keeping the gaps between stops exactly as you have them. Sets
                    the slider above, so your dates stay as they are.
                  </p>
                </div>
                {stops.length > 1 && (
                  <div className="tripactions__one">
                    <button
                      type="button"
                      className="btn"
                      onClick={fitEachStop}
                      disabled={ready < stops.length}
                    >
                      Re-time each stop
                    </button>
                    <p>
                      Moves each stop to its own best dates, which can catch
                      windows that are too far apart for one shift to reach. Keeps
                      the order and the length of every stay, and <strong>does
                      change your dates</strong>.
                    </p>
                  </div>
                )}
              </div>

              {ready > 0 && (
                <p className="verdict">
                  <strong>{atPeak} of {ready}</strong>{' '}
                  {ready === 1 ? 'stop lands' : 'stops land'} at peak
                  {shift !== 0 && `, leaving ${formatDay(planned[0].from)}`}.
                  {beyondHorizon && (
                    <span className="verdict__caveat">
                      {' '}This is beyond the 16-day forecast, so it describes a
                      typical year rather than this one: the likely week, not a
                      promise.
                    </span>
                  )}
                </p>
              )}
            </section>

            <section>
              <h2>Stop by stop</h2>
              <ol className="stoplist">
                {planned.map((stop, i) => (
                  <StopCard
                    key={`${stop.h3}-${i}`}
                    stop={stop}
                    shift={shift}
                    error={timelines.errors[stop.h3]}
                    bounds={bounds}
                    onStay={(f, t) => setStopStay(i, addDays(f, -shift), addDays(t, -shift))}
                    onRemove={() => removeStop(i)}
                    onUp={i > 0 ? () => move(i, -1) : null}
                    onDown={i < planned.length - 1 ? () => move(i, 1) : null}
                  />
                ))}
              </ol>
            </section>
          </>
        )}

        {stops.length > 0 && (
          <section className="tripsave">
            <div className="tripactions">
              <div className="tripactions__one">
                <button type="button" className="btn" onClick={shareTrip}>
                  {shared === 'shared' ? 'Shared' : shared === 'copied' ? 'Link copied' : 'Share this trip'}
                </button>
                <p>
                  Hands over the link, which is the whole trip: stops, stays and
                  all. Uses your phone&rsquo;s share sheet where there is one and
                  copies to the clipboard otherwise.
                </p>
              </div>
              <div className="tripactions__one">
                <button type="button" className="btn" onClick={saveTrip} disabled={alreadySaved}>
                  {alreadySaved ? 'Saved on this device' : 'Save to this device'}
                </button>
                <p>
                  Keeps it in this browser so you can come back to it without the
                  link. It does not travel to another device, and clearing your
                  browsing data clears it.
                </p>
              </div>
            </div>
            {shared === 'manual' && (
              <label className="tripsave__manual">
                <span>Copy this link</span>
                <input
                  type="text"
                  readOnly
                  value={window.location.href}
                  onFocus={(e) => e.target.select()}
                />
              </label>
            )}
            <p className="note">
              Nothing is stored on a server and there is no account behind any of
              this, which is why the link carries the trip rather than pointing at
              one.
            </p>
          </section>
        )}

        {saved.length > 0 && (
          <section>
            <h2>Saved on this device</h2>
            <ul className="savedlist">
              {saved.map((entry) => (
                <li key={entry.id} className={entry.stops === encoded ? 'savedlist__on' : undefined}>
                  <button type="button" className="savedlist__open" onClick={() => openSaved(entry)}>
                    <strong>{entry.label}</strong>
                    {entry.dates && <span>{entry.dates}</span>}
                  </button>
                  <button
                    type="button"
                    className="savedlist__drop"
                    onClick={() => setSaved(removeTrip(entry.id))}
                    aria-label={`Forget ${entry.label}`}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        <section className="callout">
          <h2>What this can and cannot tell you</h2>
          <p>
            Peak date comes from where a place sits: how far north, how high, how near
            the sea, what grows there. That does not change from year to year. Weather changes how good the colour is, not when it
            arrives. So this is the week to aim for in a typical autumn, and it
            is best in New England, where it is checked against foresters&rsquo;
            records. <a href="#/about-the-build">About the build</a> has the
            accuracy figures and what it gets wrong.
          </p>
        </section>

        <footer className="page__foot">
          <a href="#/">← Back to the map</a>
        </footer>
      </div>
    </div>
  );
}

function StopCard({ stop, shift, error, bounds, onStay, onRemove, onUp, onDown }) {
  const rgb = stop.stage ? stageColor(stop.stage) : [110, 106, 98];
  // A stay long enough to plan often changes stage inside itself, and showing
  // only one end would be picking a favourite.
  const turns = stop.stageOnLeaving && stop.stageOnLeaving !== stop.stage;
  const endRgb = turns ? stageColor(stop.stageOnLeaving) : rgb;

  return (
    <li className="stopcard">
      <div className="stopcard__top">
        <span className="stopcard__name">{stop.name}</span>
        {bounds && (
          <DateRange
            compact
            label={`Stay at ${stop.name}`}
            from={stop.from}
            to={stop.to}
            min={bounds.from}
            max={bounds.to}
            series={stop.series}
            onChange={onStay}
          />
        )}
        {stop.stage && (
          <span className="pill" style={{ background: `rgb(${rgb.join(',')})` }}>
            {stageLabel(stop.stage)}
          </span>
        )}
        {turns && (
          <>
            <span className="stopcard__arrow" aria-hidden="true">→</span>
            <span className="pill" style={{ background: `rgb(${endRgb.join(',')})` }}>
              {stageLabel(stop.stageOnLeaving)}
            </span>
          </>
        )}
        <span className="stopcard__actions">
          <button
            type="button"
            onClick={onUp}
            disabled={!onUp}
            aria-label={`Move ${stop.name} earlier in the trip`}
            title="Move this stop earlier in the order. Dates are not changed."
          >
            ↑
          </button>
          <button
            type="button"
            onClick={onDown}
            disabled={!onDown}
            aria-label={`Move ${stop.name} later in the trip`}
            title="Move this stop later in the order. Dates are not changed."
          >
            ↓
          </button>
          <button
            type="button"
            onClick={onRemove}
            aria-label={`Remove ${stop.name} from the trip`}
            title="Remove this stop from the trip"
          >
            ×
          </button>
        </span>
      </div>

      <p className="stopcard__verdict">{verdictFor(stop, error)}</p>

      {stop.window && (
        <p className="stopcard__window">
          Peak here runs <strong>{formatDay(stop.window.from)} – {formatDay(stop.window.to)}</strong>
          {shift !== 0 && ' · dates above include the shift'}
        </p>
      )}
    </li>
  );
}

/**
 * What to say about one stop.
 *
 * Every branch is a distinct claim and they must not blur: a cell that never
 * peaks is a different answer from one whose shard failed to load, which is
 * different again from one that simply has no forecast on that date.
 */
function verdictFor(stop, error) {
  if (error) return 'Could not load the forecast for this place.';
  if (!stop.ready) return 'Loading…';
  if (stop.standing.where === 'never') {
    return 'No peak inside the forecast season for this place.';
  }
  if (!stop.day) return 'Outside the forecast season.';

  const { where, days } = stop.standing;
  const n = (x) => `${x} day${x === 1 ? '' : 's'}`;
  const stayLength = stop.nights + 1;
  // A stay of one day is still the common case, and phrasing written for a
  // range reads badly on it -- "at peak for all 1 day of the stay", or
  // "leaving 4 days before peak" about a visit with no leaving in it.
  const single = stayLength === 1;

  if (where === 'inside') {
    if (single) return 'At peak on the day you are there.';
    // How much of peak the stay catches, which is the question a stay asks
    // and a single date could not.
    if (days >= stayLength) return `At peak for all ${n(stayLength)}.`;
    return `${n(days)} of the ${stayLength} at peak.`;
  }
  if (where === 'early') {
    return single
      ? `${n(days)} early. Peak opens ${formatDay(stop.window.from)}.`
      : `Leaving ${n(days)} before peak opens on ${formatDay(stop.window.from)}.`;
  }
  return single
    ? `${n(days)} late. Peak closed ${formatDay(stop.window.to)}.`
    : `Arriving ${n(days)} after peak closed on ${formatDay(stop.window.to)}.`;
}
