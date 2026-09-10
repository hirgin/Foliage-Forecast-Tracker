import { useEffect, useMemo, useRef } from 'react';
import { progressionColor, stageLabel } from '../map/colors';
import { formatDay } from './TimeSlider';

/**
 * The trip against the season.
 *
 * The map answers "where is peak today". This answers the question a traveller
 * actually has, which is the same data read the other way: rows are stops,
 * columns are days, and the marked run is where you would be standing.
 *
 * The header carries months above the day numbers. A bare "5" tells you
 * nothing on a strip three months long, and the row of numbers alone made you
 * count columns to work out which 5 it was.
 *
 * Colour comes from the map's own ramp, easing and all, so a hexagon and its
 * row cannot disagree about what a progression looks like.
 */

const COL_W = 13;
const GAP = 2;
const LABEL_W = 168;

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

/**
 * Month boundaries, for the header and the rules that run down the grid.
 *
 * Above the day numbers rather than instead of them. A bare "5" on a strip
 * three months long left you counting columns to work out which 5 it was.
 */
function months(dates) {
  const found = [];
  dates.forEach((d, i) => {
    if (i > 0 && d.slice(5, 7) === dates[i - 1].slice(5, 7)) return;
    found.push({
      at: i,
      label: new Date(`${d}T00:00:00Z`)
        .toLocaleDateString('en-GB', { month: 'short', timeZone: 'UTC' }),
    });
  });
  return found.map((m, i) => ({ ...m, span: (found[i + 1]?.at ?? dates.length) - m.at }));
}

export default function TripStrip({ planned, dates, was }) {
  const scrollRef = useRef(null);
  const firstDate = planned[0]?.from;
  const marks = useMemo(() => months(dates), [dates]);

  // Follow the trip. The season is far wider than the box, so without this the
  // strip opens on September while the trip is in October.
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
  const moved = planned.some((s) => {
    const w = was?.[s.h3];
    return w && !(w.from === s.from && w.to === s.to);
  });

  return (
    <>
      <div className="strip__scroll" ref={scrollRef}>
        <div
          className="strip"
          style={{ gridTemplateColumns: columns, columnGap: `${GAP}px` }}
        >
          {/* Months above, day numbers below: the month is the context the
              numbers were missing, and neither row works alone. */}
          <div className="strip__corner" />
          {marks.map((m) => (
            <div
              className="strip__month"
              key={`${m.label}-${m.at}`}
              style={{ gridColumn: `span ${m.span}` }}
            >
              {m.label}
            </div>
          ))}

          <div className="strip__corner" />
          {dates.map((d, i) => {
            const dom = Number(d.slice(8, 10));
            return (
              <div className="strip__day" key={`h-${d}`}>
                {dom === 1 || dom % 5 === 0 ? dom : ''}
              </div>
            );
          })}

          {planned.map((stop) => (
            <Row
              key={`${stop.h3}-${stop.from}`}
              stop={stop}
              dates={dates}
              was={was?.[stop.h3]}
              marks={marks}
            />
          ))}
        </div>
      </div>

      {/* Two markers mean two things, and the second only appears once you
          move something -- which is exactly when nobody is re-reading a
          caption further up the page. */}
      <div className="strip__key">
        <span className="strip__key__now">Where you are</span>
        {moved && <span className="strip__key__was">Where it was</span>}
      </div>
    </>
  );
}

function Row({ stop, dates, was, marks }) {
  // Indexed once per row rather than scanned per column: a season is ~106 days
  // and a linear find inside the column loop makes the row quadratic.
  const byDate = useMemo(
    () => new Map((stop.series || []).map((d) => [d.date, d])),
    [stop.series],
  );
  const monthStarts = useMemo(() => new Set(marks.map((m) => m.at)), [marks]);
  const moved = Boolean(was) && !(was.from === stop.from && was.to === stop.to);
  const nights = stop.nights ?? 0;

  return (
    <>
      <div className="strip__label">
        <span className="strip__place">{stop.name}</span>
        {/* When the trip is, which is what someone reading their own trip is
            looking for, and the one thing the row never said. */}
        <span className="strip__stay">
          {stop.from === stop.to
            ? formatDay(stop.from)
            : `${formatDay(stop.from)} – ${formatDay(stop.to)}`}
          {nights > 0 && <em>{nights}n</em>}
        </span>
        <span className="strip__meta">
          {stop.window
            ? `peak ${formatDay(stop.window.from)} – ${formatDay(stop.window.to)}`
            : stop.ready
              ? 'no peak this season'
              : 'loading…'}
        </span>
      </div>

      {dates.map((d, i) => {
        const day = byDate.get(d) || null;
        // The whole stay is marked, and its ends are marked as ends, so a run
        // reads as one bar rather than a row of separate visits.
        const here = d >= stop.from && d <= stop.to;
        const before = moved && d >= was.from && d <= was.to;

        const cls = ['strip__cell'];
        if (monthStarts.has(i) && i > 0) cls.push('strip__cell--month');
        if (before) {
          cls.push('strip__cell--was');
          if (d === was.from) cls.push('strip__cell--wasStart');
          if (d === was.to) cls.push('strip__cell--wasEnd');
        }
        if (here) {
          cls.push('strip__cell--here');
          if (d === stop.from) cls.push('strip__cell--start');
          if (d === stop.to) cls.push('strip__cell--end');
        }

        return (
          <div
            key={d}
            className={cls.join(' ')}
            style={cellStyle(day)}
            title={
              day && day.progression != null
                ? `${stop.name}, ${formatDay(d)}: ${stageLabel(day.stage)}`
                : `${stop.name}, ${formatDay(d)}: no forecast`
            }
          />
        );
      })}
    </>
  );
}
