import { STAGES } from '../map/colors';

const DRIVERS = [
  {
    name: 'Photoperiod',
    role: 'The trigger',
    body:
      'Day length, computed from latitude and date. Below 13 hours each day adds forcing. ' +
      'This is the only driver that needs no weather data at all, so it is known exactly for ' +
      'every day of the season — which is what makes an October estimate meaningful in August.',
  },
  {
    name: 'Chilling',
    role: 'The accelerator',
    body:
      'Nights below 7 °C amplify that day’s forcing. Cold nights are why a sharp early-autumn ' +
      'snap brings colour forward, and because temperature falls with altitude, this is the ' +
      'channel through which elevation does its work.',
  },
  {
    name: 'Warmth',
    role: 'The brake',
    body: 'Days above 20 °C subtract from forcing. A warm September delays the season.',
  },
  {
    name: 'Elevation',
    role: 'Why hexagons',
    body:
      'Weather arrives at ~9 km resolution but scoring happens at ~3 km, with each cell ' +
      'corrected to its own elevation at 6.5 °C per 1000 m. A Vermont valley floor and a ridge ' +
      'differ by roughly 7.5 °C — one to two weeks of difference that a county average erases.',
  },
  {
    name: 'Drought',
    role: 'Shortens and dulls',
    body:
      'Season-to-date rainfall against the climatological normal. A dry season brings colour ' +
      'forward slightly and substantially reduces how vivid it gets.',
  },
  {
    name: 'Frost',
    role: 'Accelerates, then destroys',
    body:
      'Freezing nights accelerate colour. A hard freeze below −4 °C strips leaves rather than ' +
      'colouring them.',
  },
];

export default function HowItWorks({ nav }) {
  return (
    <div className="page">
      <div className="page__inner">
        <header className="page__head">
          <h1>How it works</h1>
          {nav}
        </header>

        <section className="callout callout--warn">
          <h2>This is a model, not an official forecast</h2>
          <p>
            No authoritative dataset records when a given place actually peaked, so nothing here
            has been validated against reality. What has been checked is that the model is
            internally consistent, and that its constants put peak where published norms put it.
            Treat the numbers as a considered estimate, not a measurement.
          </p>
        </section>

        <h2>The forecast horizon problem</h2>
        <p>
          Weather forecasts run <strong>16 days</strong> ahead. The foliage season runs about{' '}
          <strong>75</strong>, and Vermont peaks in early October. So for most of the year, the
          date you care about is far beyond anything a weather model can see.
        </p>
        <p>
          Rather than pretend otherwise, every day is labelled by where its weather came from, and
          the forecast sharpens as the season approaches:
        </p>

        <div className="provenance">
          <div>
            <strong>Observed</strong>
            <span>Season start → today</span>
            <em>Reanalysis of what actually happened</em>
          </div>
          <div>
            <strong>Forecast</strong>
            <span>Today → +16 days</span>
            <em>A real weather forecast</em>
          </div>
          <div>
            <strong>Climatology</strong>
            <span>+16 days → season end</span>
            <em>A five-year average — a typical year, not this one</em>
          </div>
        </div>

        <p>
          Confidence on the map reflects that mix, and cells drawn from climatology are rendered
          more faintly. Accumulated drivers like chilling and drought still integrate from real
          observations even when the target date is climatological — which is exactly how
          published foliage outlooks work.
        </p>

        <h2>What drives the score</h2>
        <div className="drivers">
          {DRIVERS.map((d) => (
            <article key={d.name}>
              <h3>
                {d.name} <em>{d.role}</em>
              </h3>
              <p>{d.body}</p>
            </article>
          ))}
        </div>

        <h2>Stages</h2>
        <div className="stagerow">
          {STAGES.map((s) => (
            <div key={s.key}>
              <span className="swatch" style={{ background: `rgb(${s.rgb.join(',')})` }} />
              {s.label}
            </div>
          ))}
        </div>

        <h2>What it cannot do</h2>
        <ul className="limits">
          <li>
            <strong>It does not know species.</strong> Sugar maple, aspen and oak turn at
            different times in different colours. Only canopy density is modelled, not
            composition, so a maple stand and an oak stand at the same elevation score
            identically. This is the largest single gap.
          </li>
          <li>
            <strong>The spread is too narrow.</strong> Vermont’s real season runs two to three
            weeks from the Northeast Kingdom to the southern valleys; this model produces about
            eleven days. The direction is right and elevation clearly drives it, but the
            magnitude is understated.
          </li>
          <li>
            <strong>It ignores cloud and wind.</strong> Both change how a display is actually
            experienced, and a windstorm can end a season overnight.
          </li>
          <li>
            <strong>Beyond 16 days it is climatology.</strong> A weak claim about a typical year,
            not a statement about this one.
          </li>
        </ul>

        <h2>Where the data comes from</h2>
        <ul className="sources">
          <li>
            <strong>Weather</strong> — Open-Meteo, including the ERA5 reanalysis archive for
            observations and normals
          </li>
          <li>
            <strong>Tree canopy</strong> — USFS / NLCD Tree Canopy Cover, 30 m raster
          </li>
          <li>
            <strong>Boundaries</strong> — US Census TIGERweb
          </li>
          <li>
            <strong>Basemap</strong> — Esri Dark Gray Canvas
          </li>
        </ul>

        <footer className="page__foot">
          <a href="#/">← Back to the map</a>
        </footer>
      </div>
    </div>
  );
}
