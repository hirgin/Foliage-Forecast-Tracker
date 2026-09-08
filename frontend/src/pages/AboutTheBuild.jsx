const PHASES = [
  { n: 0, name: 'Foundations', body: 'Kotlin/Spring Boot and React/Vite skeletons, health endpoint, migrations that degrade instead of aborting startup.' },
  { n: 1, name: 'The grid', body: 'CONUS tiled into H3 hexagons, masked to real forest cover, each cell carrying its own elevation and canopy density.' },
  { n: 2, name: 'Weather pipeline', body: 'Observed, forecast and climatological weather for every cell, batched, audited, idempotent and resumable.' },
  { n: 3, name: 'The model', body: 'Peak date predicted from where a place sits — how far north, how high, how near the sea — and calibrated against twelve seasons of Maine Forest Service field reports. It replaced one that accumulated cool days toward a threshold, and ran a fortnight early because that error compounded all season.' },
  { n: 4, name: 'Map experience', body: 'Time slider, stage ramp, per-cell explanation, and confidence shown honestly.' },
];

const FINDINGS = [
  {
    title: 'Every accuracy figure was measured against the wrong thing',
    body:
      'Peak dates were fitted to published foliage windows nine to seventeen days wide, and a '
      + 'window that wide is satisfied by landing at its early edge. The model did exactly that '
      + 'at every reference town, scored fifteen of eighteen, and put New England a fortnight '
      + 'early on screen. The metric could not see the error it was supposed to catch. Targets '
      + 'are now single dates from field observations, where a week of error is a week of error.',
  },
  {
    title: 'The colour ramp was half a stage ahead of the data',
    body:
      'Each stage blended toward the next across its whole band, so a hexagon spent the top half '
      + 'of “near peak” drawn in peak’s red and the top of “peak” drawn brown. Only 54% of the '
      + 'ramp read as the stage it actually was. Much of what looked like a timing fault was this, '
      + 'and it was reported as the map peaking early for days before anyone thought to check the '
      + 'colours rather than the model.',
  },
  {
    title: 'A hard freeze made the map more colourful',
    body:
      'Vividness was driven by the day-night temperature spread. A −8 °C night under a 15 °C day ' +
      'is a 23 °C spread, so the model rated a canopy-stripping freeze as the best colour of the ' +
      'season. The spread now caps at 15 °C and the freeze penalty is multiplicative.',
  },
  {
    title: 'Averaging destroyed the chilling signal',
    body:
      'Chilling is a threshold function, and the mean of a nonlinear function is not the function ' +
      'of the mean. Individual years had 2–10 nights below 7 °C in the window; the five-year mean ' +
      'series had 2, because cold snaps land on different dates and average away. Most of the ' +
      'season was silently scoring on daylight alone.',
  },
  {
    title: 'The drought term was dead',
    body:
      'Observed rainfall was summed from May, because the weather series reaches back that far so ' +
      'chilling can accumulate. The normal accumulated only from September. Four months against ' +
      'five weeks made every cell look soaked, so drought stress was always exactly zero.',
  },
  {
    title: 'Progression had the wrong shape',
    body:
      'Progression was proportional to cumulative forcing, which accelerates. Calibrated to peak ' +
      'on the right date, it reached past-peak five days later. A canopy can only turn once, so ' +
      'senescence now saturates.',
  },
  {
    title: 'Midnight sun returned zero hours of daylight',
    body:
      'Both polar branches of the day-length formula were inverted. Invisible in Vermont, ' +
      'catastrophic the moment the grid reached Alaska. Caught by testing against published day ' +
      'lengths rather than for self-consistency.',
  },
];

export default function AboutTheBuild({ nav }) {
  return (
    <div className="page">
      <div className="page__inner">
        <header className="page__head">
          <h1>About the build</h1>
          {nav}
        </header>

        <p className="lede">
          This was built with an AI coding agent, in phases, with every architectural decision
          recorded as it was made. The repository is as much the artifact as the site is.
        </p>

        <h2>Phases</h2>
        <ol className="phases">
          {PHASES.map((p) => (
            <li key={p.n}>
              <strong>
                Phase {p.n} — {p.name}
              </strong>
              <p>{p.body}</p>
            </li>
          ))}
        </ol>

        <h2>Decisions worth defending</h2>
        <div className="drivers">
          <article>
            <h3>Hexagons, not counties</h3>
            <p>
              Counties are political boundaries with no relationship to foliage. One Colorado
              county spans 4,000 ft of elevation — several weeks of difference in peak timing —
              and averaging that into a single colour discards the strongest signal available.
            </p>
          </article>
          <article>
            <h3>No spatial database</h3>
            <p>
              The H3 index <em>is</em> the spatial index. Neighbours and ancestors are arithmetic
              on a 64-bit integer, hexagon outlines are computed in the browser, and zoom
              aggregation is a GROUP BY. There are zero runtime spatial queries.
            </p>
          </article>
          <article>
            <h3>Weather at its native resolution</h3>
            <p>
              Forecasts are ~9 km accurate, so weather is stored at ~9 km and downscaled to 3 km
              cells by elevation. Fetching at 3 km would invent precision and cost six times the
              API calls.
            </p>
          </article>
          <article>
            <h3>An explainable model</h3>
            <p>
              A fitted model would have been far harder to check — the only ground truth is
              volunteer records of individual plants, which measure something subtly different
              from a 3 km stand — and it could not tell you why a hexagon is the colour it is.
              Every constant here is a stated assumption rather than a fitted parameter.
            </p>
          </article>
        </div>

        <h2>What running it found</h2>
        <p className="lede lede--small">
          Every one of these passed code review and unit tests. All were only visible once the
          pipeline ran against real data and the numbers were checked against what they should
          have been.
        </p>
        <p className="lede lede--small">
          This is a record of what went wrong, not a description of what runs now. Several of
          these are faults in a model since deleted — the chilling and drought terms below no
          longer exist. They are kept because how a thing failed is worth more than a tidy
          account of the version that replaced it.
        </p>
        <ol className="findings">
          {FINDINGS.map((f) => (
            <li key={f.title}>
              <strong>{f.title}</strong>
              <p>{f.body}</p>
            </li>
          ))}
        </ol>

        <h2>The honest part</h2>
        <p>
          This forecast cannot be validated. No dataset records when foliage actually peaked, so
          accuracy is unknown and unclaimed. The tests assert what can be known — bounded
          outputs, monotonic response to each driver, and a calibration that puts peak where
          published norms put it — and the documentation states plainly what the model cannot do.
        </p>
        <p>
          Saying so is not a hedge. A map that looks authoritative while resting on a three-year
          average would be the wrong kind of impressive.
        </p>

        <footer className="page__foot">
          <a href="#/">← Back to the map</a>
          <a href="#/how-it-works">How it works →</a>
        </footer>
      </div>
    </div>
  );
}
