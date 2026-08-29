import { useEffect, useMemo, useRef, useState } from 'react';

const DAY_MS = 86_400_000;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Parsed as UTC: a bare yyyy-mm-dd is midnight UTC, and local parsing shifts it a day. */
function parseDay(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return Date.UTC(y, m - 1, d);
}

function toIso(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`;
}

export function formatDay(iso) {
  const d = new Date(parseDay(iso));
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
}

export default function TimeSlider({ seasonStart, seasonEnd, value, onChange, horizonDate }) {
  const [playing, setPlaying] = useState(false);
  const timer = useRef(null);

  const { start, total } = useMemo(() => {
    const s = parseDay(seasonStart);
    return { start: s, total: Math.round((parseDay(seasonEnd) - s) / DAY_MS) };
  }, [seasonStart, seasonEnd]);

  const index = Math.round((parseDay(value) - start) / DAY_MS);

  // Where the 16-day forecast horizon falls, as a percentage across the track.
  // Everything to its right is climatology, and the UI must not imply
  // otherwise -- see ADR-0005.
  const horizonPct = useMemo(() => {
    if (!horizonDate) return null;
    const pct = ((parseDay(horizonDate) - start) / DAY_MS / total) * 100;
    return pct > 1 && pct < 99 ? pct : null;
  }, [horizonDate, start, total]);

  useEffect(() => {
    if (!playing) return undefined;
    timer.current = setInterval(() => {
      onChange((current) => {
        const next = Math.round((parseDay(current) - start) / DAY_MS) + 1;
        if (next > total) {
          setPlaying(false);
          return current;
        }
        return toIso(start + next * DAY_MS);
      });
    }, 140);
    return () => clearInterval(timer.current);
  }, [playing, start, total, onChange]);

  const monthTicks = useMemo(() => {
    const ticks = [];
    for (let i = 0; i <= total; i++) {
      const d = new Date(start + i * DAY_MS);
      if (d.getUTCDate() === 1) {
        ticks.push({ pct: (i / total) * 100, label: MONTHS[d.getUTCMonth()] });
      }
    }
    return ticks;
  }, [start, total]);

  return (
    <div className="slider">
      <button
        className="slider__play"
        onClick={() => setPlaying((p) => !p)}
        aria-label={playing ? 'Pause' : 'Play through the season'}
      >
        {playing ? '❚❚' : '▶'}
      </button>

      <div className="slider__track">
        <input
          type="range"
          min={0}
          max={total}
          value={index}
          onChange={(e) => onChange(toIso(start + Number(e.target.value) * DAY_MS))}
          aria-label="Date"
        />
        <div className="slider__ticks">
          {monthTicks.map((t) => (
            <span key={t.label} className="tick" style={{ left: `${t.pct}%` }}>
              {t.label}
            </span>
          ))}
          {horizonPct != null && (
            <span
              className="tick tick--horizon"
              style={{ left: `${horizonPct}%` }}
              title="Beyond here the forecast is climatology, not a forecast"
            >
              {/* The dashed marker is drawn by CSS and always shows; only the
                  wording folds away on a narrow screen, where it would sit on
                  top of the month labels. */}
              <span className="tick__text">forecast ends</span>
            </span>
          )}
        </div>
      </div>

      <output className="slider__value">{formatDay(value)}</output>
    </div>
  );
}
