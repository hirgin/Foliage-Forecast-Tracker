import { useCallback, useEffect, useMemo, useState } from 'react';
import { useForecast, useMeta } from './api/hooks';
import FoliageMap from './map/FoliageMap';
import TimeSlider, { formatDay } from './components/TimeSlider';
import DetailPanel from './components/DetailPanel';
import { STAGES } from './map/colors';

const FORECAST_HORIZON_DAYS = 16;

function isoToday() {
  return new Date().toISOString().slice(0, 10);
}

function addDays(iso, n) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
}

export default function App() {
  const meta = useMeta();
  const [date, setDate] = useState(isoToday);
  const [selected, setSelected] = useState(null);

  const { data, error, isLoading } = useForecast(date);
  const cells = data?.cells ?? [];

  // The season bounds come back with the forecast, so today may sit outside
  // them on first load. Clamp once we know.
  useEffect(() => {
    if (!data?.seasonStart) return;
    if (date < data.seasonStart) setDate(data.seasonStart);
    else if (date > data.seasonEnd) setDate(data.seasonEnd);
  }, [data?.seasonStart, data?.seasonEnd]); // eslint-disable-line react-hooks/exhaustive-deps

  const horizonDate = useMemo(() => addDays(isoToday(), FORECAST_HORIZON_DAYS), []);
  const beyondHorizon = date > horizonDate;

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

        {isLoading && !cells.length && <p className="note">Loading forecast…</p>}
        {error && (
          <p className="note note--bad">{error.message} Is the backend running on :8080?</p>
        )}

        {Boolean(cells.length) && (
          <>
            <div className="headline">
              <span className="headline__date">{formatDay(date)}</span>
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
            reality.
          </p>
          {meta.data && (
            <p className="build">
              model {meta.data.modelVersion} · schema v
              {meta.data.database?.schemaVersion ?? '?'}
            </p>
          )}
        </footer>
      </aside>

      {data?.seasonStart && (
        <TimeSlider
          seasonStart={data.seasonStart}
          seasonEnd={data.seasonEnd}
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
