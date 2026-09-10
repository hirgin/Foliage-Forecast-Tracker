import { STAGES } from '../map/colors';

// Written for a visitor who wants to know when to go and see the leaves.
//
// Kept deliberately short. This page previously carried the driver list, the
// weather provenance grid, six limitations and eight data sources, which is a
// specification rather than an explanation -- and the one thing a reader
// actually needs, that the map predicts a date and colours the weeks around
// it, was buried in the middle of it. Everything technical now lives in
// "About the build", which is the page for it.
const DRIVERS = [
  {
    name: 'How far north',
    body: 'Autumn sweeps north to south. This moves the date more than anything else.',
  },
  {
    name: 'How high up',
    body: 'Higher ground is colder and turns sooner. A ridge can be ten days ahead of the valley below it.',
  },
  {
    name: 'How near the sea',
    body: 'The ocean keeps autumn nights warm, so the coast holds its leaves late.',
  },
  {
    name: 'What kind of trees',
    body: 'Aspen and birch turn about a week before maples.',
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

        <p className="lede">
          For every 3 km hexagon, the map works out <strong>one date</strong>, the day the
          leaves there should be at their best, and then draws a season around it: building for
          a few weeks, about ten days at peak, then fading.
        </p>

        <h2>What decides the date</h2>
        <div className="drivers">
          {DRIVERS.map((d) => (
            <article key={d.name}>
              <h3>{d.name}</h3>
              <p>{d.body}</p>
            </article>
          ))}
        </div>

        <h2>Weather changes how it looks, not when</h2>
        <p>
          The date comes from where a place sits, which does not change from year to year.
          Weather decides how good the display is: warm days with cool nights make the brightest
          colour, a dry autumn dulls it, and a hard freeze brings the leaves down early.
        </p>

        <h2>How sure it is</h2>
        <p>
          Best in New England, where it is checked against foresters&rsquo; records of when the
          leaves actually turned, usually within a couple of days. Elsewhere there is no such
          record to check against, so it is a rougher estimate, and those hexagons are{' '}
          <strong>drawn fainter</strong> to say so.
        </p>
        <p>
          Hexagons also fade later in the season, where the map is working from a typical year
          rather than this one. Faint means less certain, wherever you see it.
        </p>

        <h2>Stages</h2>
        <div className="stagerow">
          {STAGES.map((s) => (
            <div key={s.key}>
              <span className="swatch" style={{ background: `rgb(${s.rgb.join(',')})` }} />
              {s.label}
            </div>
          ))}
        </div>

        <section className="callout">
          <h2>It is a model, not an official forecast</h2>
          <p>
            Treat it as a considered estimate of a typical autumn. If you want the accuracy
            figures, what it gets wrong and where the data comes from, that is all in{' '}
            <a href="#/about-the-build">About the build</a>.
          </p>
        </section>

        <footer className="page__foot">
          <a href="#/">← Back to the map</a>
        </footer>
      </div>
    </div>
  );
}
