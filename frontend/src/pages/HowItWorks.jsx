import { STAGES } from '../map/colors';

// Written for a visitor, not a maintainer.
//
// This page has twice described a model that no longer existed -- most
// recently one that accumulated cool days past a daylight threshold, which was
// replaced wholesale. If the model changes again, this changes with it, or the
// site is lying to people in prose while telling the truth in colour.
const DRIVERS = [
  {
    name: 'How far north',
    role: 'The biggest single thing',
    body:
      'Autumn sweeps from north to south. Nothing else in the model moves a date as far: '
      + 'across New England each degree of latitude — about 111 km — is worth roughly four days.',
  },
  {
    name: 'How high up',
    role: 'Why hexagons, not counties',
    body:
      'It gets colder as you climb, by about 6.5 °C per kilometre, and colder ground turns '
      + 'sooner. In New England that works out at a day for every 49 metres, so a ridge can be '
      + 'ten days ahead of the valley beneath it. Averaging that across a county throws it away, '
      + 'which is why the map is drawn on 3 km hexagons instead.',
  },
  {
    name: 'How near the sea',
    role: 'The coast holds on',
    body:
      'The ocean keeps autumn nights warm for weeks after inland ground has cooled, so coastal '
      + 'forest turns late. It is a bigger effect than it sounds: Maine’s foresters record the '
      + 'Downeast coast peaking on 18 October and the milder-looking south coast on the 15th — '
      + 'the northern coast peaks last in the state, because it juts furthest into the cold Gulf '
      + 'of Maine.',
  },
  {
    name: 'What kind of trees',
    role: 'Aspen goes early',
    body:
      'Aspen and birch turn about a week before maples and drop their leaves quickly once they '
      + 'do. The forest type of every hexagon comes from a national survey, so an aspen stand in '
      + 'northern Minnesota is not given a maple’s timing. Ground with too few trees to forecast '
      + 'is drawn faintly, taking its colour from the nearest woods rather than pretending to '
      + 'know — about a third of the country, most of it farmland and desert.',
  },
];

const NOT_TIMING = [
  {
    name: 'Warm days and cool nights',
    body:
      'The bigger the gap between a day’s high and its overnight low, the more vivid the colour.',
  },
  {
    name: 'A dry autumn',
    body: 'Drought makes the display duller and shorter-lived. Rain so far is compared against a normal year.',
  },
  {
    name: 'A hard freeze',
    body: 'Below −4 °C the leaves come down rather than colouring. The map dims a hexagon once that has happened.',
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
            Treat it as a considered estimate. It is built from where a place sits rather than
            from this year’s weather, so it describes a typical autumn more than a particular
            one — and it is much better checked in New England than anywhere else.
          </p>
        </section>

        <h2>The short version</h2>
        <p>
          For every 3 km hexagon, the map works out <strong>one date</strong>: when the leaves
          there should be at their best. That comes from three things about the place —{' '}
          <strong>how far north it is, how high it sits, and how near the sea</strong>. A season
          is then drawn around that date: building for a few weeks, about ten days at peak, then
          fading.
        </p>
        <p>
          So the map is not simulating an autumn day by day. It is predicting the date, then
          colouring the weeks either side of it.
        </p>

        <h2>What decides the date</h2>
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

        <section className="callout">
          <h2>Weather does not decide when — only how good it looks</h2>
          <p>
            This surprises people, and it is deliberate. An earlier version of this map added up
            cool days until a hexagon crossed a threshold, and a small error in that sum
            compounded all season: New England came out peaking in late September, a fortnight
            early, and no amount of adjusting fixed it.
          </p>
          <p>
            The date is now settled by where a place is, which does not drift. Weather still
            decides how <em>vivid</em> the display is:
          </p>
          <ul className="limits">
            {NOT_TIMING.map((d) => (
              <li key={d.name}>
                <strong>{d.name}.</strong> {d.body}
              </li>
            ))}
          </ul>
        </section>

        <h2>Two maps, and one is better than the other</h2>
        <p>
          A forecast is only as good as what it was checked against, and that varies enormously
          across the country.
        </p>
        <div className="provenance">
          <div>
            <strong>New England</strong>
            <span>6 states</span>
            <em>
              Checked against twelve seasons of Maine Forest Service field reports and five years
              of New Hampshire sightings — real observations of real leaves. Within about two days
              on the Maine coast.
            </em>
          </div>
          <div>
            <strong>The other 42 states</strong>
            <span>Drawn fainter</span>
            <em>
              No comparable record exists, so these are checked against a national prediction map
              — itself a forecast, and one that runs four to six days early where it can be
              compared with Maine’s foresters. Within about four days, and drawn less boldly to
              say so.
            </em>
          </div>
        </div>
        <p>
          Where a hexagon is faint, that is the map telling you it is less sure — either because
          the season there is far enough ahead to be guesswork, or because nobody has ever gone
          and counted the leaves.
        </p>

        <h2>Why the far future is a guess</h2>
        <p>
          Weather forecasts only run <strong>16 days</strong> ahead, and the foliage season runs
          about <strong>75</strong>. That matters less than it used to, because the date no longer
          depends on the weather — but how vivid a hexagon looks still does.
        </p>
        <div className="provenance">
          <div>
            <strong>Observed</strong>
            <span>Season start → today</span>
            <em>What the weather actually did</em>
          </div>
          <div>
            <strong>Forecast</strong>
            <span>Today → +16 days</span>
            <em>An actual weather forecast</em>
          </div>
          <div>
            <strong>Typical year</strong>
            <span>+16 days → season end</span>
            <em>A three-year average: a typical year, not this one</em>
          </div>
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

        <h2>What it gets wrong</h2>
        <ul className="limits">
          <li>
            <strong>The Pacific Northwest runs about a week early.</strong> The model reads
            “nearness to the sea” along an east–west line, which is right on the Atlantic and
            backwards on the Pacific. One number cannot describe both coasts, and this one is
            fitted to the Atlantic.
          </li>
          <li>
            <strong>Only Maine and New Hampshire have been checked against real observations.</strong>{' '}
            Vermont, Massachusetts, Connecticut and Rhode Island are predicted by the pattern
            those two set. Everywhere else is checked against another forecast rather than
            against leaves.
          </li>
          <li>
            <strong>It has never been tested on a season it did not already see.</strong> Every
            accuracy figure here comes from the same years the model was built from, which
            flatters it. The honest test is to predict a year held back, and that has not been
            done.
          </li>
          <li>
            <strong>It describes a typical year, not this one.</strong> Because the date comes
            from geography, an unusually warm or cold autumn will not move it — a real limitation,
            and the price of not having an error that compounds.
          </li>
          <li>
            <strong>It ignores cloud and wind.</strong> Both change how the display looks, and one
            windy night can end a season early.
          </li>
          <li>
            <strong>City centres are mostly missing.</strong> Anywhere with too few trees is drawn
            as bare ground, which includes most of Boston.
          </li>
        </ul>

        <h2>Where the data comes from</h2>
        <ul className="sources">
          <li>
            <strong>When leaves actually peaked</strong> — Maine Forest Service weekly foliage
            reports, 2014–2025; VisitNH regional peak records
          </li>
          <li>
            <strong>Peak dates elsewhere</strong> — SmokyMountains.com county prediction map
          </li>
          <li>
            <strong>Forest type</strong> — USFS BIGMAP forest type group
          </li>
          <li>
            <strong>Tree canopy</strong> — USFS / NLCD Tree Canopy Cover, 30 m raster
          </li>
          <li>
            <strong>Elevation</strong> — USGS 3DEP and AWS Terrain Tiles
          </li>
          <li>
            <strong>Weather</strong> — Open-Meteo, including its historical archive
          </li>
          <li>
            <strong>Boundaries</strong> — US Census TIGERweb
          </li>
          <li>
            <strong>Basemap</strong> — OpenFreeMap, built on OpenStreetMap data
          </li>
        </ul>

        <footer className="page__foot">
          <a href="#/">← Back to the map</a>
        </footer>
      </div>
    </div>
  );
}
