import { useTimeline, useExplain } from '../api/hooks';
import { stageColor, stageLabel } from '../map/colors';
import { formatDay } from './TimeSlider';

const W = 258;
const H = 54;

/** Season curve for one cell, with the selected day marked. */
function Sparkline({ days, activeDate }) {
  if (!days?.length) return null;

  const step = W / (days.length - 1);
  const points = days
    .map((d, i) => `${(i * step).toFixed(1)},${(H - (d.progression / 100) * H).toFixed(1)}`)
    .join(' ');

  const activeIndex = days.findIndex((d) => d.date === activeDate);
  const peakIndex = days.findIndex((d) => d.stage === 'PEAK');

  return (
    <svg className="spark" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Season progression">
      {/* Stage bands along the base, so the curve reads against the palette. */}
      {days.map((d, i) => {
        const [r, g, b] = stageColor(d.stage);
        return (
          <rect
            key={d.date}
            x={i * step}
            y={H - 4}
            width={step + 0.6}
            height={4}
            fill={`rgb(${r},${g},${b})`}
          />
        );
      })}
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" />
      {peakIndex >= 0 && (
        <line
          x1={peakIndex * step}
          y1="0"
          x2={peakIndex * step}
          y2={H - 4}
          stroke="currentColor"
          strokeWidth="1"
          strokeDasharray="2 2"
          opacity="0.45"
        />
      )}
      {activeIndex >= 0 && (
        <circle
          cx={activeIndex * step}
          cy={H - (days[activeIndex].progression / 100) * H}
          r="3.2"
          fill="currentColor"
        />
      )}
    </svg>
  );
}

export default function DetailPanel({ h3, date, onClose }) {
  const timeline = useTimeline(h3);
  const explain = useExplain(h3, date);

  const today = timeline.data?.days?.find((d) => d.date === date);
  const [r, g, b] = stageColor(today?.stage);

  return (
    <aside className="detail">
      <header>
        <div>
          <h2>Selected hexagon</h2>
          <code>{h3}</code>
        </div>
        <button onClick={onClose} aria-label="Close">×</button>
      </header>

      {timeline.isLoading && <p className="note">Loading…</p>}
      {timeline.error && <p className="note note--bad">{timeline.error.message}</p>}

      {today && (
        <>
          <div className="detail__stage">
            <span className="swatch" style={{ background: `rgb(${r},${g},${b})` }} />
            <strong>{stageLabel(today.stage)}</strong>
            <span className="muted">{Math.round(today.progression)}% turned</span>
          </div>

          <dl className="detail__stats">
            <div>
              <dt>Intensity</dt>
              <dd>{Math.round(today.intensity)}/100</dd>
            </div>
            <div>
              <dt>Peak</dt>
              <dd>{timeline.data.peakDay ? formatDay(timeline.data.peakDay) : '—'}</dd>
            </div>
            <div>
              <dt>Confidence</dt>
              <dd>{Math.round(today.confidence * 100)}%</dd>
            </div>
          </dl>

          {/* How wooded the hexagon actually is.
              City cells are now on the map whatever their canopy, because
              street trees turn on the same schedule as the woods outside town.
              But downtown Boston is 2% trees, and showing it at peak without
              saying so would promise a display that is not there. */}
          {typeof timeline.data.canopyPct === 'number' && (
            <p className="detail__canopy">
              {timeline.data.canopyPct < 20
                ? `Only ${timeline.data.canopyPct}% of this area is tree cover, so expect ` +
                  'scattered colour rather than a hillside.'
                : `${timeline.data.canopyPct}% tree cover.`}
            </p>
          )}

          <Sparkline days={timeline.data.days} activeDate={date} />
          <p className="spark__caption">
            Season progression · dashed line marks first peak
          </p>
        </>
      )}

      {explain.data?.factors?.length > 0 && (
        <section className="factors">
          <h3>Why{explain.data.atPeakOnly ? ' — at peak' : ''}</h3>
          {explain.data.atPeakOnly && (
            <p className="factors__note">
              Drivers are shown for this cell&rsquo;s peak day
              {explain.data.date ? ` (${formatDay(explain.data.date)})` : ''}.
            </p>
          )}
          {explain.data.factors.map((f) => (
            <div className="factor" key={f.name}>
              <div className="factor__head">
                <span>{f.name}</span>
                <em>{f.effect}</em>
              </div>
              <p>{f.detail}</p>
            </div>
          ))}
        </section>
      )}
    </aside>
  );
}
