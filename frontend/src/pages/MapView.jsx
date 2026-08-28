import { useCallback, useEffect, useMemo, useState } from 'react';
import { useForecast, useMeta } from '../api/hooks';
import FoliageMap from '../map/FoliageMap';
import TimeSlider, { formatDay } from '../components/TimeSlider';
import DetailPanel from '../components/DetailPanel';
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

  const { data, error, isLoading } = useForecast(date);
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
    <div className="app">
      <FoliageMap cells={cells} selected={selected} onSelect={onSelect} />

      <aside className="panel">
        <header>
          <h1>Foliage Forecast</h1>
          <p className="sub">Vermont · {cells.length || '—'} hexagons at ~3 km</p>
        </header>

        {nav}

        {(meta.isLoading || (isLoading && !cells.length)) && (
          <p className="note">Loading forecast…</p>
        )}
        {(error || meta.error) && (
          <p className="note note--bad">{(error ?? meta.error).message}</p>
        )}

        {Boolean(cells.length) && (
          <>
            <div className="headline">
              <span className="headline__date">{date ? formatDay(date) : '—'}</span>
              <span className="headline__peak">
                {peakCount
                  ? `${peakCount} of ${cells.length} at or near peak`
                  : 'No cells near peak yet'}
              </span>
            </div>

            <section className="legend">
              <h2>Stage</h2>
              {STAGES.map((s) => (
                <div className="legend__row" key={s.key}>
                  <span className="swatch" style={{ background: `rgb(${s.rgb.join(',')})` }} />
                  <span className="legend__label">{s.label}</span>
                  <span className="legend__count">{counts[s.key] ?? 0}</span>
                </div>
              ))}
            </section>
          </>
        )}

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
              model {meta.data.modelVersion} · schema v
              {meta.data.database?.schemaVersion ?? '?'}
            </p>
          )}
        </footer>
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
