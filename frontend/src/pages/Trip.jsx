import { useEffect, useMemo, useState } from 'react';
import { useMeta, useTimelines } from '../api/hooks';
import { h3ForPlace } from '../api/client';
import { seasonDates } from '../api/packed';
import { hashParam, setHashParam } from '../routing';
import { stageLabel, stageColor } from '../map/colors';
import PlaceSearch from '../components/PlaceSearch';
import TripStrip from '../components/TripStrip';
import { formatDay } from '../components/TimeSlider';
import { addDays, clampToSeason, horizonDate, isoToday } from '../season';
import {
  MAX_STOPS, SHIFT_LIMIT, decodeStops, encodeStops, planTrip, bestShift, countAtPeak,
} from '../trip/plan';

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

  const timelines = useTimelines(stops.map((s) => s.h3));

  const dates = useMemo(
    () => (meta.data ? seasonDates(meta.data.seasonStart, meta.data.seasonEnd) : []),
    [meta.data],
  );
  const bounds = meta.data
    ? { from: meta.data.seasonStart, to: meta.data.seasonEnd }
    : null;

  const planned = planTrip(stops, timelines.byH3, shift);
  const atPeak = countAtPeak(planned);
  const ready = planned.filter((p) => p.ready).length;
  // Past the horizon the export is climatology, and ADR-0005 is explicit that
  // it must never be presented as a forecast. Trips are planned months out, so
  // this is the normal case here rather than an edge one.
  const horizon = horizonDate();
  const beyondHorizon = planned.some((p) => p.date > horizon);

  const addStop = async (place) => {
    if (stops.length >= MAX_STOPS) return;
    const h3 = await h3ForPlace(place, null);
    if (!h3) return;
    // A new stop lands two days after the last one, which is the usual shape
    // of a foliage trip and saves setting a date for every stop by hand.
    const last = stops[stops.length - 1];
    const date = last
      ? clampToSeason(addDays(last.date, 2), bounds?.from, bounds?.to)
      : clampToSeason(isoToday(), bounds?.from, bounds?.to);
    setStops([...stops, { h3, date, name: place.name }]);
  };

  const setStopDate = (i, date) => {
    setStops(stops.map((s, j) => (j === i ? { ...s, date } : s)));
  };

  const removeStop = (i) => setStops(stops.filter((_, j) => j !== i));

  const move = (i, by) => {
    const j = i + by;
    if (j < 0 || j >= stops.length) return;
    const next = [...stops];
    [next[i], next[j]] = [next[j], next[i]];
    setStops(next);
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
          the whole season, so you can see whether the trip lands on peak — and
          what moving it a few days would do.
        </p>

        <section className="tripbar">
          <PlaceSearch onSelect={addStop} />
          {stops.length >= MAX_STOPS && (
            <p className="note">
              {MAX_STOPS} stops is the limit. Remove one to add another.
            </p>
          )}
        </section>

        {!stops.length && (
          <section className="callout">
            <h2>Nothing planned yet</h2>
            <p>
              Search for a town or a mountain above to add your first stop.
              Everything stays in the address bar — copy the link to share the
              plan or keep it for later. Nothing is saved anywhere else.
            </p>
          </section>
        )}

        {stops.length > 0 && (
          <>
            <section>
              <h2>Your trip against the season</h2>
              <p className="note">
                Each row is a stop, each column a day. The outlined cell is when
                you would be there. Faded means the forecast is working from a
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
                <button type="button" className="btn" onClick={findBestWeek} disabled={!ready}>
                  Find the best week
                </button>
                {shift !== 0 && (
                  <button type="button" className="btn btn--quiet" onClick={() => setShift(0)}>
                    Reset
                  </button>
                )}
              </div>

              {ready > 0 && (
                <p className="verdict">
                  <strong>{atPeak} of {ready}</strong>{' '}
                  {ready === 1 ? 'stop lands' : 'stops land'} at peak
                  {shift !== 0 && `, leaving ${formatDay(planned[0].date)}`}.
                  {beyondHorizon && (
                    <span className="verdict__caveat">
                      {' '}This is beyond the 16-day forecast, so it describes a
                      typical year rather than this one — the likely week, not a
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
                    onDate={(d) => setStopDate(i, addDays(d, -shift))}
                    onRemove={() => removeStop(i)}
                    onUp={i > 0 ? () => move(i, -1) : null}
                    onDown={i < planned.length - 1 ? () => move(i, 1) : null}
                  />
                ))}
              </ol>
            </section>
          </>
        )}

        <section className="callout">
          <h2>What this can and cannot tell you</h2>
          <p>
            Peak date comes from where a place sits — how far north, how high,
            how near the sea, what grows there — and that does not change from
            year to year. Weather changes how good the colour is, not when it
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

function StopCard({ stop, shift, error, bounds, onDate, onRemove, onUp, onDown }) {
  const rgb = stop.stage ? stageColor(stop.stage) : [110, 106, 98];

  return (
    <li className="stopcard">
      <div className="stopcard__top">
        <span className="stopcard__name">{stop.name}</span>
        <input
          type="date"
          className="stopcard__date"
          value={stop.date}
          min={bounds?.from}
          max={bounds?.to}
          onChange={(e) => e.target.value && onDate(e.target.value)}
          aria-label={`Date at ${stop.name}`}
        />
        {stop.stage && (
          <span className="pill" style={{ background: `rgb(${rgb.join(',')})` }}>
            {stageLabel(stop.stage)}
          </span>
        )}
        <span className="stopcard__actions">
          <button type="button" onClick={onUp} disabled={!onUp} aria-label="Move earlier in trip">↑</button>
          <button type="button" onClick={onDown} disabled={!onDown} aria-label="Move later in trip">↓</button>
          <button type="button" onClick={onRemove} aria-label={`Remove ${stop.name}`}>×</button>
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
  const n = `${days} day${days === 1 ? '' : 's'}`;
  if (where === 'inside') {
    return days === 0
      ? 'Arriving exactly as peak opens.'
      : `${n} into peak.`;
  }
  if (where === 'early') return `${n} early — peak opens ${formatDay(stop.window.from)}.`;
  return `${n} late — peak closed ${formatDay(stop.window.to)}.`;
}
