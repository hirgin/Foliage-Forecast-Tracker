import { useMeta, useCells } from './api/hooks';
import FoliageMap from './map/FoliageMap';
import { CANOPY_STOPS } from './map/colors';

const VERMONT = '50';

function Stat({ label, value }) {
  return (
    <div className="stat">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

export default function App() {
  const meta = useMeta();
  const { data, error, isLoading } = useCells(VERMONT, 0);

  const cells = data?.cells ?? [];
  const forested = cells.filter((c) => c.canopyPct != null && c.canopyPct >= 50).length;
  const elevations = cells.map((c) => c.elevationM).filter((e) => e != null);

  return (
    <div className="app">
      <FoliageMap cells={cells} />

      <aside className="panel">
        <header>
          <h1>Foliage Forecast</h1>
          <p className="sub">Vermont · H3 resolution {data?.resolution ?? 6} · ~3 km hexagons</p>
        </header>

        {isLoading && <p className="note">Loading grid…</p>}
        {error && (
          <p className="note note--bad">
            {error.message} Is the backend running on :8080?
          </p>
        )}

        {data && (
          <>
            <dl className="stats">
              <Stat label="Cells" value={data.count.toLocaleString()} />
              <Stat label="50%+ canopy" value={forested.toLocaleString()} />
              <Stat
                label="Elevation"
                value={
                  elevations.length
                    ? `${Math.min(...elevations)}–${Math.max(...elevations)} m`
                    : '—'
                }
              />
            </dl>

            <section className="legend">
              <h2>Tree canopy</h2>
              {CANOPY_STOPS.map((s) => (
                <div className="legend__row" key={s.min}>
                  <span
                    className="swatch"
                    style={{ background: `rgb(${s.rgb.join(',')})` }}
                  />
                  {s.label}
                </div>
              ))}
              <div className="legend__row">
                <span className="swatch swatch--none" />
                Not sampled
              </div>
            </section>
          </>
        )}

        <footer>
          <p>
            Phase 1 — the grid. Colour shows <strong>canopy density</strong>, not
            foliage: the phenology model arrives in Phase 3.
          </p>
          {meta.data && (
            <p className="build">
              model {meta.data.modelVersion} · schema v{meta.data.database?.schemaVersion ?? '?'}
            </p>
          )}
        </footer>
      </aside>
    </div>
  );
}
