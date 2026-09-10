import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { progressionColor, stageLabel } from '../map/colors';
import { formatDay } from './TimeSlider';
import {
  addMonths, monthGrid, monthName, monthRange, openingMonth,
  pickRange, previewRange, within, nightsIn, WEEKDAYS,
} from './calendar';

/**
 * One dropdown for arriving and leaving.
 *
 * It replaces a pair of native date inputs, which were two controls for one
 * decision -- you cannot see a stay in two text boxes, only read it -- and
 * whose width is whatever the platform decides. That second part was a real
 * fault: Safari's date input is far wider than Chromium's and refuses to
 * shrink, so the pair ran off the side of a phone.
 *
 * When a [series] is supplied the days are tinted by what the forecast says
 * that cell is doing, which is the point of picking dates on this site at all:
 * peak is a band of colour you choose your stay against rather than a figure
 * to look up somewhere else first.
 */
export default function DateRange({
  from, to, min, max, series, onChange, label = 'When', compact = false,
}) {
  const [open, setOpen] = useState(false);
  const [view, setView] = useState(() => openingMonth(from, min, max));
  const [draft, setDraft] = useState({ from, to, picking: false });
  const [hovered, setHovered] = useState(null);
  // How far the popover has to slide to stay on screen. A trigger near the
  // right edge would otherwise hang a 258px panel off the side -- the same
  // fault the native date inputs had, and not one worth reintroducing.
  // Measured rather than guessed: a fixed right-alignment fixes the trigger
  // on the right and breaks the one on the left.
  const [nudge, setNudge] = useState(0);
  const boxRef = useRef(null);
  const popRef = useRef(null);

  // Reopening starts from what is currently set rather than from where the
  // last visit was left, so the control never contradicts the trip.
  useEffect(() => {
    if (!open) return;
    setDraft({ from, to, picking: false });
    setView(openingMonth(from, min, max));
    setHovered(null);
  }, [open, from, to, min, max]);

  useLayoutEffect(() => {
    if (!open || !popRef.current) return;
    const r = popRef.current.getBoundingClientRect();
    const edge = 12;
    // clientWidth, not innerWidth: the latter counts the vertical scrollbar,
    // which is not space anything can be drawn in, and measuring against it
    // left the panel hanging over by exactly the scrollbar's width.
    const vw = document.documentElement.clientWidth;
    // A correction relative to where it currently is, rather than an absolute
    // offset from an assumed zero. That assumption was wrong -- the effect
    // that cleared nudge on open runs after this one and undid it every time
    // -- and a delta needs no such reset: once placed, this computes nothing.
    let dx = 0;
    if (r.right > vw - edge) dx = vw - edge - r.right;
    if (r.left + dx < edge) dx = edge - r.left;
    if (dx) setNudge((n) => n + dx);
  }, [open, view]);

  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e) => { if (!boxRef.current?.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const byDate = useMemo(
    () => new Map((series || []).map((d) => [d.date, d])),
    [series],
  );

  const grid = monthGrid(view);
  const { first, last } = monthRange(min, max);
  const shown = previewRange(draft, hovered);

  const choose = (day) => {
    const next = pickRange(draft, day);
    setDraft(next);
    // Committed only when the range closes. A half-made range that escaped
    // into the trip would be a stay of one day nobody asked for.
    if (!next.picking) {
      onChange(next.from, next.to);
      setOpen(false);
    }
  };

  const nights = nightsIn(from, to);
  const summary = from === to
    ? formatDay(from)
    : `${formatDay(from)} – ${formatDay(to)}`;

  return (
    <div className={compact ? 'daterange daterange--compact' : 'daterange'} ref={boxRef}>
      {!compact && <span className="daterange__label">{label}</span>}
      <button
        type="button"
        className="daterange__trigger"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="dialog"
      >
        <span>{summary}</span>
        {nights > 0 && (
          <em>{nights} night{nights === 1 ? '' : 's'}</em>
        )}
      </button>

      {open && (
        <div
          className="cal"
          ref={popRef}
          style={nudge ? { marginLeft: `${nudge}px` } : undefined}
          role="dialog"
          aria-label={`${label}: choose arriving and leaving`}
        >
          <div className="cal__head">
            <button
              type="button"
              onClick={() => setView(addMonths(view, -1))}
              disabled={view <= first}
              aria-label="Previous month"
            >
              ‹
            </button>
            <strong>{monthName(view)}</strong>
            <button
              type="button"
              onClick={() => setView(addMonths(view, 1))}
              disabled={view >= last}
              aria-label="Next month"
            >
              ›
            </button>
          </div>

          <div className="cal__weekdays" aria-hidden="true">
            {WEEKDAYS.map((d, i) => <span key={`${d}-${i}`}>{d}</span>)}
          </div>

          <div className="cal__grid" onMouseLeave={() => setHovered(null)}>
            {grid.weeks.flat().map((day, i) => {
              if (!day) return <span className="cal__pad" key={`pad-${i}`} />;
              const outside = day < min || day > max;
              const reading = byDate.get(day);
              const inRange = within(day, shown.from, shown.to);
              const isStart = day === shown.from;
              const isEnd = day === shown.to;

              // The forecast for that day, behind the selection rather than
              // competing with it: a thin bar under the number, so a chosen
              // day still reads as chosen.
              const tint = reading && typeof reading.progression === 'number'
                ? `rgb(${progressionColor(reading.progression, reading.stage).join(',')})`
                : null;

              const cls = ['cal__day'];
              if (outside) cls.push('cal__day--out');
              if (inRange) cls.push('cal__day--in');
              if (isStart) cls.push('cal__day--start');
              if (isEnd) cls.push('cal__day--end');

              return (
                <button
                  type="button"
                  key={day}
                  className={cls.join(' ')}
                  disabled={outside}
                  onClick={() => choose(day)}
                  onMouseEnter={() => setHovered(day)}
                  aria-label={
                    reading?.stage
                      ? `${formatDay(day)}, ${stageLabel(reading.stage).toLowerCase()}`
                      : formatDay(day)
                  }
                >
                  {Number(day.slice(8, 10))}
                  {tint && <span className="cal__tint" style={{ background: tint }} />}
                </button>
              );
            })}
          </div>

          <p className="cal__hint">
            {draft.picking
              ? 'Now pick the day you leave, or the same day again for a day trip.'
              : 'Pick the day you arrive, then the day you leave.'}
          </p>
        </div>
      )}
    </div>
  );
}
