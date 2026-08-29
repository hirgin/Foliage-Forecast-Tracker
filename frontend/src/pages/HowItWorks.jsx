import { STAGES } from '../map/colors';

// Written for a visitor, not a maintainer. These described the previous model
// -- a 7 C chilling threshold and warmth as a brake -- which no longer exists.
const DRIVERS = [
  {
    name: 'Shorter days',
    role: 'The starting gun',
    body:
      'Once daylight drops under about 13 hours, trees begin shutting down for winter. ' +
      'Nothing happens before that, however cold it gets — a cold August does not bring ' +
      'autumn forward. Day length depends only on where you are and the date, so this part ' +
      'is known exactly, months ahead.',
  },
  {
    name: 'Cool weather',
    role: 'Sets the pace',
    body:
      'After that, cool weather does the work. Every day counts for a little more the colder ' +
      'it is, and a place reaches peak once enough cool days have added up. This is why a mild ' +
      'coast turns weeks later than the cold interior at the same latitude — it simply takes ' +
      'longer to get there.',
  },
  {
    name: 'Height above sea level',
    role: 'Why hexagons, not counties',
    body:
      'It gets colder as you climb, by about 6.5 °C per kilometre. A valley floor and a nearby ' +
      'ridge can be 7 °C apart, which is one to two weeks of difference in when they turn. ' +
      'Averaging that across a whole county throws the difference away, so the map scores each ' +
      '3 km hexagon at its own height instead.',
  },
  {
    name: 'Rain',
    role: 'Dulls and shortens',
    body:
      'A dry autumn brings colour on slightly earlier and makes it noticeably duller and ' +
      'shorter-lived. Rainfall so far is compared against what a normal year brings.',
  },
  {
    name: 'Frost',
    role: 'Speeds it up, then ends it',
    body:
      'Frosty nights bring colour on faster. A hard freeze below −4 °C does the opposite: it ' +
      'knocks the leaves down instead of colouring them.',
  },
  {
    name: 'Warm days and cool nights',
    role: 'Makes it brighter',
    body:
      'The bigger the gap between a day’s high and its overnight low, the more vivid the ' +
      'colour. This affects how good the display looks rather than when it happens.',
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
            Nobody keeps an official record of when each place actually peaked, so there is
            nothing to check this against. What we can say is that it is tuned to land inside
            the peak windows published for well-known places, and that it lands within about
            four days of them on average. Treat it as a considered estimate, not a measurement.
          </p>
        </section>

        <h2>Why the far future is a guess</h2>
        <p>
          Weather forecasts only run <strong>16 days</strong> ahead. The foliage season runs
          about <strong>75</strong>. So for most of the year, the date you are curious about is
          far past anything a weather forecast can see.
        </p>
        <p>
          Rather than hide that, each day says where its weather came from — and the picture
          sharpens as the season gets closer:
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
            <em>A five-year average: a typical year, not this one</em>
          </div>
        </div>

        <p>
          Hexagons resting mostly on a typical year are drawn more faintly, so you can see at a
          glance how much the map is guessing. Late in the season that is most of it, which is
          also true of the foliage outlooks you see in the news.
        </p>

        <h2>What decides the colour</h2>
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

        <h2>What it gets wrong</h2>
        <ul className="limits">
          <li>
            <strong>It does not know what kind of trees they are.</strong> Maple, aspen and oak
            turn at different times and in different colours. The map knows how dense the trees
            are but not what they are, so an oak wood and a maple wood side by side score the
            same. This is the biggest single gap, and it shows: oak country in Connecticut comes
            out about six days early.
          </li>
          <li>
            <strong>The far north and deep south are still squeezed together.</strong> The
            season now spreads about six weeks across the country, close to the real thing, but
            the extremes are understated: the far north turns about a week later than it should
            and inland Connecticut about six days early.
          </li>
          <li>
            <strong>Cities are missing.</strong> Anywhere with too few trees to forecast is left
            off the map, which includes most of Boston and every other city centre.
          </li>
          <li>
            <strong>It ignores cloud and wind.</strong> Both change how the display actually
            looks, and one windy night can end a season early.
          </li>
          <li>
            <strong>Past 16 days it is a typical year.</strong> A general expectation, not a
            claim about this particular autumn.
          </li>
        </ul>

        <h2>Where the data comes from</h2>
        <ul className="sources">
          <li>
            <strong>Weather</strong> — Open-Meteo, including its historical archive for past
            weather and long-run averages
          </li>
          <li>
            <strong>Tree canopy</strong> — USFS / NLCD Tree Canopy Cover, 30 m raster
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
