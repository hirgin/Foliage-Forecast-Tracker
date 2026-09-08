import { useEffect, useRef } from 'react';
import { progressionColor, stageLabel } from '../map/colors';
import { formatDay } from './TimeSlider';

/**
 * The trip against the season.
 *
 * The map answers "where is peak today". This answers the question a traveller
 * actually has, which is the same data read the other way: rows are stops,
 * columns are days, and the ringed cell is where you would be standing. Seeing
 * a trip run diagonally across the season is the whole point -- it makes
 * "leave four days later" a visible move rather than an argument.
 *
 * Colour comes from the map's own ramp, easing and all, so a hexagon and its
 * row cannot disagree about what a progression looks like.
 */

const COL_W = 13;
const GAP = 2;
const LABEL_W = 150;

function cellStyle(day) {
  if (!day || day.progression == null) return { background: 'rgba(120,116,108,.22)' };
  const [r, g, b] = progressionColor(day.progression, day.stage);
  return {
    background: `rgb(${r}, ${g}, ${b})`,
    // Confidence is the only provenance the export carries, and the map
    // already fades a hexagon by it. Fading a column the same way keeps one
    // vocabulary: faint means less certain, wherever you see it.
    opacity: 0.35 + 0.65 * Math.min(1, Math.max(0, day.confidence ?? 1)),
  };
}

export default function TripStrip({ planned, dates }) {
  const scrollRef = useRef(null);
  const firstDate = planned[0]?.date;

  // Follow the trip. The season is ~76 columns and the trip occupies a handful
  // of them, so without this the strip opens on September while the trip is in
  // October.
  useEffect(() => {
    const box = scrollRef.current;
    if (!box || !firstDate) return;
    const i = dates.indexOf(firstDate);
    if (i < 0) return;
    const target = LABEL_W + i * (COL_W + GAP) - box.clientWidth / 3;
    box.scrollTo({ left: Math.max(0, target), behavior: 'smooth' });
  }, [firstDate, dates]);

  if (!planned.length) return null;

  const columns = `${LABEL_W}px repeat(${dates.length}, ${COL_W}px)`;

  return (
    <div className="strip__scroll" ref={scrollRef}>
      <div className="strip" style={{ gridTemplateColumns: columns, gap: `${GAP}px` }}>
        <div className="strip__corner" />
        {dates.map((d) => {
          const dom = Number(d.slice(8, 10));
          return (
            <div className="strip__day" key={d}>
              {dom === 1 || dom % 5 === 0 ? dom : ''}
            </div>
          );
        })}

        {planned.map((stop) => (
          <Row key={`${stop.h3}-${stop.date}`} stop={stop} dates={dates} />
        ))}
      </div>
    </div>
  );
}

function Row({ stop, dates }) {
  // Indexed once per row rather than scanned per column: a season is ~76 days
  // and a linear find inside the column loop makes the row quadratic.
  const byDate = new Map((stop.series || []).map((d) => [d.date, d]));

  return (
    <>
      <div className="strip__label">
        <span className="strip__place">{stop.name}</span>
        <span className="strip__meta">
          {stop.window
            ? `peak ${formatDay(stop.window.from)} – ${formatDay(stop.window.to)}`
            : stop.ready
              ? 'no peak this season'
              : 'loading…'}
        </span>
      </div>

      {dates.map((d) => {
        const day = byDate.get(d) || null;
        const here = d === stop.date;
        return (
          <div
            key={d}
            className={here ? 'strip__cell strip__cell--here' : 'strip__cell'}
            style={cellStyle(day)}
            title={
              day && day.progression != null
                ? `${stop.name} — ${formatDay(d)}: ${stageLabel(day.stage)}`
                : `${stop.name} — ${formatDay(d)}: no forecast`
            }
          />
        );
      })}
    </>
  );
}
