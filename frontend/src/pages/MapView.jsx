import { useCallback, useEffect, useMemo, useState } from 'react';
import { useForecast, useMeta } from '../api/hooks';
import { resolutionForZoom, cellWidthKm, h3ForPlace, fetchBareCells } from '../api/client';
import FoliageMap from '../map/FoliageMap';
import TimeSlider, { formatDay } from '../components/TimeSlider';
import DetailPanel from '../components/DetailPanel';
import PlaceSearch from '../components/PlaceSearch';
import { STAGES, NO_FORECAST_RGB, EVERGREEN_STAGE } from '../map/colors';

const FORECAST_HORIZON_DAYS = 16;

function isoToday() {
  return new Date().toISOString().slice(0, 10);
}

function addDays(iso, n) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
}

export default function MapView({ nav }) {
  const meta = useMeta();
  const [date, setDate] = useState(null);
  const [selected, setSelected] = useState(null);
  // Mobile only: the panel collapses to its essentials so the map, which is
  // the whole point of the page, is not buried under a legend. Desktop has
  // room for everything at once and ignores this entirely.
  const [panelOpen, setPanelOpen] = useState(false);
  // Which resolution to draw. A res 6 hexagon is under a pixel with the whole
  // country on screen, so the map falls back to a coarser export when zoomed
  // out and swaps back on the way in.
  const [resolution, setResolution] = useState(6);

  // The unforested rest of the grid, so the map has no holes in it. Fetched
  // once and never per date: these cells carry no forecast, which is exactly
  // why they can be held apart from the daily files.
  //
  // Not fetched until something would actually be drawn with it. It is an
  // index of every tiled cell that is not forest -- around 122,000 of them,
  // the largest single file the site serves -- and it is only drawn at res 6,
  // so anyone who looks at the national view and leaves never pays for it.
  const [bareCells, setBareCells] = useState([]);
  useEffect(() => {
    if (resolution !== 6) return undefined;
    let live = true;
    fetchBareCells().then((h3) => { if (live) setBareCells(h3); });
    return () => { live = false; };
  }, [resolution]);
  // Where the map should centre. Carries a nonce so choosing the same place
  // twice still recentres, rather than being ignored as an unchanged prop.
  const [focus, setFocus] = useState(null);

  // Season bounds come from meta, not from a forecast response. Today is
  // usually outside the season, and a static build has no file for a date
  // outside it to clamp against — there would be nothing to fall back from.
  const seasonStart = meta.data?.seasonStart;
  const seasonEnd = meta.data?.seasonEnd;

  // Opens at the start of the season, not at today.
  //
  // Today is the better answer to "where is colour right now" and the worse
  // answer to everything else. The map's subject is a progression across three
  // months, and opening part-way through it hides how much of the season the
  // slider covers -- in early September the two are days apart and look
  // identical, so the map appears to open on a blank green country for no
  // visible reason. Starting at the beginning means pressing play walks the
  // whole autumn.
  useEffect(() => {
    if (!seasonStart || date) return;
    setDate(seasonStart);
  }, [seasonStart, date]);

  const { data, error, isLoading } = useForecast(date, resolution);
  const cells = data?.cells ?? [];

  const horizonDate = useMemo(() => addDays(isoToday(), FORECAST_HORIZON_DAYS), []);
  const beyondHorizon = Boolean(date) && date > horizonDate;

  const counts = useMemo(() => {
    const out = {};
    for (const c of cells) out[c.stage] = (out[c.stage] ?? 0) + 1;
    return out;
  }, [cells]);

  // Cells that exist but have not been scored yet. While the backfill works
  // through the country this is most of the map, and leaving it out of the
  // legend made a waiting grid look like a broken one.
  const notForecast = counts[null] ?? counts.undefined ?? 0;

  const peakCount = (counts.PEAK ?? 0) + (counts.NEAR_PEAK ?? 0);
  const onSelect = useCallback((h3) => setSelected(h3), []);

  return (
    // On a phone the detail panel takes the whole lower half, so the main
    // panel steps aside rather than stacking two sheets over a hidden map.
    <div className={selected ? 'app app--detail' : 'app'}>
      <FoliageMap
        cells={cells}
        bareCells={bareCells}
        resolution={resolution}
        selected={selected}
        onSelect={onSelect}
        focus={focus}
        onZoom={(zoom) => setResolution(resolutionForZoom(zoom))}
      />

      <aside className={panelOpen ? 'panel panel--open' : 'panel'}>
        <header className="panel__head">
          <div>
            <h1>Foliage Forecast</h1>
            {/* The size follows what is actually being drawn. Zooming out swaps
              to a coarser export and the counts below become counts of those
              larger areas, so a fixed "3 km" would make them look wrong. */}
          <p className="sub">
              {meta.data?.coverage ?? 'United States'} ·{' '}
              {cells.length ? cells.length.toLocaleString() : '—'} hexagons at ~
            {cellWidthKm(resolution)} km
            </p>
          </div>
          <button
            type="button"
            className="panel__toggle"
            onClick={() => setPanelOpen((o) => !o)}
            aria-expanded={panelOpen}
            aria-label={panelOpen ? 'Hide details' : 'Show legend and details'}
          >
            <span aria-hidden="true">{panelOpen ? '−' : '+'}</span>
          </button>
        </header>

        {nav}
        <PlaceSearch
          onSelect={async (place) => {
            // Resolved against the detailed cell list rather than whatever is
            // on screen: zoomed out, the drawn cells are a coarser set and the
            // index would point at the wrong hexagon.
            setFocus({ lat: place.lat, lon: place.lon, nonce: Date.now() });
            const h3 = await h3ForPlace(place, cells);
            if (h3) setSelected(h3);
          }}
        />

        {(meta.isLoading || (isLoading && !cells.length)) && (
          <p className="note">Loading forecast…</p>
        )}
        {(error || meta.error) && (
          <p className="note note--bad">{(error ?? meta.error).message}</p>
        )}

        {Boolean(cells.length) && (
          <div className="headline">
            <span className="headline__date">{date ? formatDay(date) : '—'}</span>
            <span className="headline__peak">
              {peakCount
                ? `${peakCount.toLocaleString()} of ${cells.length.toLocaleString()} at or near peak`
                : 'No cells near peak yet'}
            </span>
          </div>
        )}

        {/* The legend stays visible at every size. Without it the colours on
            the map mean nothing, and the counts are how you see where the
            season actually is. On a phone it lays out in two columns. */}
        {Boolean(cells.length) && (
          <section className="legend">
            <h2>Stage</h2>
            <div className="legend__rows">
              {STAGES.map((s) => (
                <div className="legend__row" key={s.key}>
                  <span className="swatch" style={{ background: `rgb(${s.rgb.join(',')})` }} />
                  <span className="legend__label">{s.label}</span>
                  <span className="legend__count">{(counts[s.key] ?? 0).toLocaleString()}</span>
                </div>
              ))}
            </div>
            {/* Evergreen sits below the ramp rather than in it: it is not a
                stage of autumn but the absence of one. Counting it under "not
                forecast yet" read as a hole in the map, when these cells are
                known and known to stay green. */}
            {(counts.EVERGREEN ?? 0) > 0 && (
              <div className="legend__row">
                <span
                  className="swatch"
                  style={{ background: `rgb(${EVERGREEN_STAGE.rgb.join(',')})` }}
                />
                <span className="legend__label">{EVERGREEN_STAGE.label}</span>
                <span className="legend__count">
                  {(counts.EVERGREEN ?? 0).toLocaleString()}
                </span>
              </div>
            )}
            {notForecast > 0 && (
              <div className="legend__row legend__row--pending">
                <span
                  className="swatch"
                  style={{ background: `rgb(${NO_FORECAST_RGB.join(',')})` }}
                />
                <span className="legend__label">Not forecast yet</span>
                <span className="legend__count">{notForecast.toLocaleString()}</span>
              </div>
            )}
            {/*
              The gaps need explaining as much as the colours do.

              The grid is masked to real forest, so farmland, towns and water
              get no hexagon at all and the basemap shows through. Across the
              farm belt that is a quarter of them -- 75% of cells present in a
              sample of Ohio, 80% in Maryland, against 100% in Vermont -- which
              scatters holes through the map and reads as something broken.
              Every other absence on this map is labelled; this one was not,
              so it was the one people asked about.
            */}
            <p className="legend__note">
              Faded tiles have too few trees to forecast — fields, towns and
              water — and are coloured from the nearest forest that does turn,
              so read them as the season around there rather than a forecast
              for that spot. Where a forest is mostly evergreen, its autumn is
              drawn in muted colour: the same timing, far less of it.
            </p>
          </section>
        )}

        {/* Reference rather than answer: folded away on a phone. */}
        <div className="panel__more">
        {beyondHorizon && (
          <p className="note note--warn">
            Beyond the 16-day weather forecast. This date is estimated from a
            three-year average, not forecast — treat it as a typical year rather
            than a prediction about this one.
          </p>
        )}

        <footer>
          <p>
            A model, not an official forecast. No official record of peak dates
            exists, so it is checked against volunteer observations of real
            leaves instead. <a href="#/how-it-works">How it works</a>.
          </p>
          {/* How far the load has got. Most of the map is grey while the
              nightly backfill works through the country against a metered
              weather API, and a map that is merely unfinished should say so
              rather than look broken. */}
          {meta.data?.statesForecast != null && meta.data.statesForecast < meta.data.stateCount && (
            <p className="build">
              Forecast so far for {meta.data.statesForecast} of {meta.data.stateCount} states
              {meta.data.cellsForecast != null && meta.data.cellCount
                ? ` · ${Math.round((100 * meta.data.cellsForecast) / meta.data.cellCount)}% of the map`
                : ''}
              . The rest is still loading, a few states a night.
            </p>
          )}
          {meta.data && (
            <p className="build">
              model {meta.data.modelVersion}
              {/* Schema version is a backend concept; a static build reports
                  the payload format instead of an empty "schema v?". */}
              {meta.data.database?.schemaVersion
                ? ` · schema v${meta.data.database.schemaVersion}`
                : meta.data.format
                  ? ` · ${meta.data.format}`
                  : ''}
              {meta.data.generatedAt && ` · built ${meta.data.generatedAt.slice(0, 10)}`}
            </p>
          )}
        </footer>
        </div>
      </aside>

      {seasonStart && date && (
        <TimeSlider
          seasonStart={seasonStart}
          seasonEnd={seasonEnd}
          value={date}
          onChange={setDate}
          horizonDate={horizonDate}
        />
      )}

      {selected && (
        <DetailPanel h3={selected} date={date} onClose={() => setSelected(null)} />
      )}
    </div>
  );
}
