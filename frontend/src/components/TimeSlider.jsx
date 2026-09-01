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

/**
 * The next frame of playback, or null once the season has run out.
 *
 * Pure so it can be tested without rendering; see dates.test.js.
 */
export function nextFrame(index, total) {
  return index >= total ? null : index + 1;
}

/**
 * Playback speeds, as multipliers of [BASE_FRAME_MS].
 *
 * Worth having now that a season is 106 days rather than 76: at one frame per
 * 140 ms a full playthrough runs about fifteen seconds, which is a long time
 * to sit through when you are checking one region, and too fast to follow when
 * you are watching a front move down the country.
 *
 * Kept to a short cycle rather than a dropdown. There is one control's worth of
 * room next to the play button, and four steps covers "slow enough to read" to
 * "quick enough to skim" without a menu.
 */
export const SPEEDS = [0.5, 1, 2, 4];

/** One frame at normal speed. */
export const BASE_FRAME_MS = 140;

export function frameMs(speed) {
  return Math.round(BASE_FRAME_MS / speed);
}

/** The next speed in the cycle, wrapping back to the slowest. */
export function nextSpeed(speed) {
  const i = SPEEDS.indexOf(speed);
  return SPEEDS[(i + 1) % SPEEDS.length] ?? 1;
}

/**
 * Where pressing play should start from.
 *
 * Rewinds when the season has already played through. Without this, play at
 * the last day advances to `total + 1`, which is immediately out of range, so
 * playback stopped on its first tick and the button looked broken -- the
 * season could only ever be watched once per page load.
 *
 * A date before the season starts is treated the same way, since it is equally
 * outside the range the slider can step through.
 */
export function playFrom(index, total) {
  return index >= total || index < 0 ? 0 : index;
}

export function formatDay(iso) {
  const d = new Date(parseDay(iso));
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
}

export default function TimeSlider({ seasonStart, seasonEnd, value, onChange, horizonDate }) {
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
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
        const next = nextFrame(Math.round((parseDay(current) - start) / DAY_MS), total);
        if (next === null) {
          setPlaying(false);
          return current;
        }
        return toIso(start + next * DAY_MS);
      });
    }, frameMs(speed));
    return () => clearInterval(timer.current);
  }, [playing, speed, start, total, onChange]);

  const finished = index >= total;

  const toggle = () => {
    if (playing) {
      setPlaying(false);
      return;
    }
    // Rewind before starting, so a second press after the season has run
    // through replays it instead of sitting on the last day.
    const from = playFrom(index, total);
    if (from !== index) onChange(toIso(start + from * DAY_MS));
    setPlaying(true);
  };

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
        onClick={toggle}
        aria-label={
          playing ? 'Pause' : finished ? 'Replay the season' : 'Play through the season'
        }
      >
        {playing ? '❚❚' : finished ? '↻' : '▶'}
      </button>

      {/*
        All four shown rather than cycled through.
        A single button that steps 1 -> 2 -> 4 -> 0.5 puts the slowest speed
        three clicks away, behind the two fast ones, with nothing on screen to
        say it exists -- so the honest reading of pressing it twice is that the
        control only speeds up. Four buttons cost about eighty pixels and make
        every speed one press away and visible without pressing anything.
      */}
      <div className="slider__speeds" role="group" aria-label="Playback speed">
        {SPEEDS.map((s) => (
          <button
            key={s}
            className={s === speed ? 'slider__speed slider__speed--on' : 'slider__speed'}
            onClick={() => setSpeed(s)}
            aria-pressed={s === speed}
            aria-label={`Play at ${s} times speed`}
          >
            {s}&times;
          </button>
        ))}
      </div>

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
