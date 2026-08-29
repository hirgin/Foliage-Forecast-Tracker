import { useCallback, useEffect, useMemo, useState } from 'react';
import { useForecast, useMeta } from '../api/hooks';
import { resolutionForZoom, cellWidthKm } from '../api/client';
import FoliageMap from '../map/FoliageMap';
import TimeSlider, { formatDay } from '../components/TimeSlider';
import DetailPanel from '../components/DetailPanel';
import PlaceSearch from '../components/PlaceSearch';
import { STAGES } from '../map/colors';

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
  // Where the map should centre. Carries a nonce so choosing the same place
  // twice still recentres, rather than being ignored as an unchanged prop.
  const [focus, setFocus] = useState(null);

  // Season bounds come from meta, not from a forecast response. Today is
  // usually outside the season, and a static build has no file for a date
  // outside it to clamp against — there would be nothing to fall back from.
  const seasonStart = meta.data?.seasonStart;
  const seasonEnd = meta.data?.seasonEnd;

  useEffect(() => {
    if (!seasonStart || date) return;
    const today = isoToday();
    setDate(today < seasonStart ? seasonStart : today > seasonEnd ? seasonEnd : today);
  }, [seasonStart, seasonEnd, date]);

  const { data, error, isLoading } = useForecast(date, resolution);
  const cells = data?.cells ?? [];

  const horizonDate = useMemo(() => addDays(isoToday(), FORECAST_HORIZON_DAYS), []);
  const beyondHorizon = Boolean(date) && date > horizonDate;

  const counts = useMemo(() => {
    const out = {};
    for (const c of cells) out[c.stage] = (out[c.stage] ?? 0) + 1;
    return out;
  }, [cells]);

  const peakCount = (counts.PEAK ?? 0) + (counts.NEAR_PEAK ?? 0);
  const onSelect = useCallback((h3) => setSelected(h3), []);

  return (
    // On a phone the detail panel takes the whole lower half, so the main
    // panel steps aside rather than stacking two sheets over a hidden map.
    <div className={selected ? 'app app--detail' : 'app'}>
      <FoliageMap
        cells={cells}
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
          onSelect={(place) => {
            const h3 = cells[place.cell]?.h3;
            if (h3) setSelected(h3);
            setFocus({ lat: place.lat, lon: place.lon, nonce: Date.now() });
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
          </section>
        )}

        {/* Reference rather than answer: folded away on a phone. */}
        <div className="panel__more">
        {beyondHorizon && (
          <p className="note note--warn">
            Beyond the 16-day weather forecast. This date is estimated from a
            five-year average, not forecast — treat it as a typical year rather
            than a prediction about this one.
          </p>
        )}

        <footer>
          <p>
            A model, not an official forecast. There is no ground-truth record of
            when foliage actually peaks, so this has not been validated against
            reality. <a href="#/how-it-works">How it works</a>.
          </p>
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
