import { useMeta } from './api/hooks';

const STATE_LABELS = {
  connected: { label: 'Connected', tone: 'ok' },
  unavailable: { label: 'Unavailable', tone: 'bad' },
  starting: { label: 'Starting', tone: 'warn' },
};

function StatusDot({ tone }) {
  return <span className={`dot dot--${tone}`} aria-hidden="true" />;
}

function Row({ label, value, tone }) {
  return (
    <div className="row">
      <dt>{label}</dt>
      <dd>
        {tone && <StatusDot tone={tone} />}
        {value}
      </dd>
    </div>
  );
}

export default function App() {
  const { data, error, isLoading } = useMeta();

  const backendTone = error ? 'bad' : isLoading ? 'warn' : 'ok';
  const db = data?.database;
  const dbInfo = db ? STATE_LABELS[db.state] ?? { label: db.state, tone: 'warn' } : null;

  return (
    <div className="page">
      <main className="card">
        <header className="card__head">
          <h1>Foliage Forecast</h1>
          <p className="sub">US fall colour, forecast on a 3&nbsp;km hexagon grid</p>
        </header>

        <section>
          <h2>System status</h2>
          <dl className="rows">
            <Row
              label="Backend"
              tone={backendTone}
              value={error ? 'Unreachable' : isLoading ? 'Checking…' : 'Healthy'}
            />
            {data && (
              <>
                <Row label="Database" tone={dbInfo.tone} value={dbInfo.label} />
                {db.schemaVersion && <Row label="Schema" value={`v${db.schemaVersion}`} />}
                <Row label="Model" value={data.modelVersion} />
                <Row label="Grid" value={`H3 resolution ${data.gridResolution}`} />
                <Row
                  label="Cells loaded"
                  value={data.cellCount?.toLocaleString() ?? 'None yet'}
                />
              </>
            )}
          </dl>

          {error && (
            <p className="note note--bad">
              {error.message} Start the backend with <code>./gradlew bootRun</code>.
            </p>
          )}
          {db?.state === 'unavailable' && (
            <p className="note note--bad">
              Database unreachable: {db.error ?? 'unknown error'}
            </p>
          )}
        </section>

        <footer className="card__foot">
          <p>
            Phase&nbsp;0 — foundations. The map arrives in Phase&nbsp;1, once the
            hexagon grid is built and masked to forest cover.
          </p>
        </footer>
      </main>
    </div>
  );
}
