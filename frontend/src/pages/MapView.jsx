import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  useForecast, useMeta, usePrefetchForecast, usePeakDates, usePlaces,
} from '../api/hooks';
import { resolutionForZoom, cellWidthKm, h3ForPlace, fetchBareCells } from '../api/client';
import FoliageMap from '../map/FoliageMap';
import TimeSlider, { formatDay } from '../components/TimeSlider';
import DetailPanel from '../components/DetailPanel';
import PlaceSearch from '../components/PlaceSearch';
import { STAGES, peakDateColor, PEAK_DATE_ALPHA } from '../map/colors';
import { rankPlaces, describeKind } from '../map/bestPlaces';
import { addDays, horizonDate } from '../season';

export default function MapView({ nav }) {
  const meta = useMeta();
  const [date, setDate] = useState(null);
  const [selected, setSelected] = useState(null);
  // Mobile only: the panel collapses to its essentials so the map, which is
  // the whole point of the page, is not buried under a legend. Desktop has
  // room for everything at once and ignores this entirely.
  const [panelOpen, setPanelOpen] = useState(false);
  // Which resolution to draw. A res 6 hexagon is under a pixel with the whole
  // country on screen, so the map falls back to a coarser export when zoomed
  // out and swaps back on the way in.
  const [resolution, setResolution] = useState(6);
  // Which question the map is answering. 'stage' is what a cell is doing on
  // the chosen date; 'peak' is when its peak arrives, which is the question
  // people actually turn up with and previously had to find by scrubbing.
  const [mode, setMode] = useState('stage');
  // Where to go on the chosen day. Behind a toggle because answering it costs
  // the place index and a detailed day, and someone who only wants the map
  // should not pay for a question they did not ask.
  const [showBest, setShowBest] = useState(false);

  // The unforested rest of the grid, so the map has no holes in it. Fetched
  // once and never per date: these cells carry no forecast, which is exactly
  // why they can be held apart from the daily files.
  //
  // Not fetched until something would actually be drawn with it. It is an
  // index of every tiled cell that is not forest -- around 122,000 of them,
  // the largest single file the site serves -- and it is only drawn at res 6,
  // so anyone who looks at the national view and leaves never pays for it.
  const [bareCells, setBareCells] = useState([]);
  useEffect(() => {
    // Every resolution has a bare index now, not just res 6 -- a coarse
    // hexagon whose every cell is farmland is absent from the forested index
    // and would otherwise draw as a hole.
    let live = true;
    fetchBareCells(resolution).then((h3) => { if (live) setBareCells(h3); });
    return () => { live = false; };
  }, [resolution]);
  // Where the map should centre. Carries a nonce so choosing the same place
  // twice still recentres, rather than being ignored as an unchanged prop.
  const [focus, setFocus] = useState(null);

  // Season bounds come from meta, not from a forecast response. Today is
  // usually outside the season, and a static build has no file for a date
  // outside it to clamp against — there would be nothing to fall back from.
  const seasonStart = meta.data?.seasonStart;
  const seasonEnd = meta.data?.seasonEnd;

  // Opens at the start of the season, not at today.
  //
  // Today is the better answer to "where is colour right now" and the worse
  // answer to everything else. The map's subject is a progression across three
  // months, and opening part-way through it hides how much of the season the
  // slider covers -- in early September the two are days apart and look
  // identical, so the map appears to open on a blank green country for no
  // visible reason. Starting at the beginning means pressing play walks the
  // whole autumn.
  useEffect(() => {
    if (!seasonStart || date) return;
    setDate(seasonStart);
  }, [seasonStart, date]);

  const { data, error, isLoading } = useForecast(date, resolution);

  // Every day of the season, so the days just ahead of the playhead can be
  // fetched before they are asked for. See usePrefetchForecast.
  const seasonDays = useMemo(() => {
    if (!seasonStart || !seasonEnd) return [];
    const out = [];
    for (let d = seasonStart; d <= seasonEnd; d = addDays(d, 1)) out.push(d);
    return out;
  }, [seasonStart, seasonEnd]);
  usePrefetchForecast(date, seasonDays, resolution);
  const cells = data?.cells ?? [];

  // Fetched only when asked for, so anyone who never opens this view never
  // pays for the file.
  const peak = usePeakDates(resolution, mode === 'peak');
  const peakCells = useMemo(() => {
    if (mode !== 'peak' || !peak.data) return [];
    const { h3, offsets } = peak.data;
    const out = new Array(h3.length);
    for (let i = 0; i < h3.length; i += 1) out[i] = { h3: h3[i], peak: offsets[i] };
    return out;
  }, [mode, peak.data]);

  const seasonLength = peak.data?.dates?.length ?? 0;
  const peakless = useMemo(() => {
    if (!peak.data) return 0;
    let n = 0;
    for (const v of peak.data.offsets) if (v === 255) n += 1;
    return n;
  }, [peak.data]);
  const peakFill = useCallback((d) => {
    // 255 is "never reaches peak in this season", which is a real answer and
    // must not be coloured as though it were an early one.
    if (d.peak == null || d.peak === 255 || !seasonLength) return [70, 66, 60, 90];
    return [...peakDateColor(d.peak / (seasonLength - 1)), PEAK_DATE_ALPHA];
  }, [seasonLength]);

  // Month boundaries, so the ramp is read against real dates rather than a
  // bare gradient with two numbers on the ends.
  const peakTicks = useMemo(() => {
    const dates = peak.data?.dates;
    if (!dates?.length) return [];
    const out = [];
    dates.forEach((d, i) => {
      if (d.slice(8) === '01' || i === 0) {
        out.push({ at: i / (dates.length - 1), label: formatDay(d).replace(/^1 /, '') });
      }
    });
    return out;
  }, [peak.data]);

  const horizon = useMemo(() => horizonDate(), []);
  const beyondHorizon = Boolean(date) && date > horizon;

  const counts = useMemo(() => {
    const out = {};
    for (const c of cells) out[c.stage] = (out[c.stage] ?? 0) + 1;
    return out;
  }, [cells]);

  // Cells that exist but have not been scored yet. While the backfill works
  // through the country this is most of the map, and leaving it out of the
  // legend made a waiting grid look like a broken one.
  const peakCount = (counts.PEAK ?? 0) + (counts.NEAR_PEAK ?? 0);
  const onSelect = useCallback((h3) => setSelected(h3), []);

  const { data: placeIndex, isLoading: placesLoading } = usePlaces(showBest && mode === 'stage');
  // Always the detailed grid: a place carries an index into that list, and
  // handing this a coarse day would point every place at the wrong hexagon.
  const fine = useForecast(date, 6, showBest && mode === 'stage');
  const best = useMemo(
    () => (showBest ? rankPlaces(placeIndex, fine.data?.cells) : []),
    [showBest, placeIndex, fine.data],
  );

  return (
    // On a phone the detail panel takes the whole lower half, so the main
    // panel steps aside rather than stacking two sheets over a hidden map.
    <div className={selected ? 'app app--detail' : 'app'}>
      <FoliageMap
        cells={mode === 'peak' ? peakCells : cells}
        colorFor={mode === 'peak' ? peakFill : undefined}
        bareCells={bareCells}
        resolution={resolution}
        selected={selected}
        onSelect={onSelect}
        focus={focus}
        onZoom={(zoom) => setResolution(resolutionForZoom(zoom))}
      />

      <aside className={panelOpen ? 'panel panel--open' : 'panel'}>
        <header className="panel__head">
          <div>
            <h1>Foliage Forecast</h1>
            {/* The size follows what is actually being drawn. Zooming out swaps
              to a coarser export and the counts below become counts of those
              larger areas, so a fixed "3 km" would make them look wrong. */}
          <p className="sub">
              {meta.data?.coverage ?? 'United States'} ·{' '}
              {cells.length ? cells.length.toLocaleString() : '—'} hexagons at ~
            {cellWidthKm(resolution)} km
            </p>
          </div>
          <button
            type="button"
            className="panel__toggle"
            onClick={() => setPanelOpen((o) => !o)}
            aria-expanded={panelOpen}
            aria-label={panelOpen ? 'Hide notes about this map' : 'Show notes about this map'}
          >
            <span aria-hidden="true">{panelOpen ? '−' : '+'}</span>
          </button>
        </header>

        {nav}
        <PlaceSearch
          onSelect={async (place) => {
            // Resolved against the detailed cell list rather than whatever is
            // on screen: zoomed out, the drawn cells are a coarser set and the
            // index would point at the wrong hexagon.
            setFocus({ lat: place.lat, lon: place.lon, nonce: Date.now() });
            const h3 = await h3ForPlace(place, cells);
            if (h3) setSelected(h3);
          }}
        />

        <div className="modes" role="group" aria-label="What the map shows">
          <button
            type="button"
            className={mode === 'stage' ? 'modes__pick modes__pick--on' : 'modes__pick'}
            onClick={() => setMode('stage')}
            aria-pressed={mode === 'stage'}
          >
            On a date
          </button>
          <button
            type="button"
            className={mode === 'peak' ? 'modes__pick modes__pick--on' : 'modes__pick'}
            onClick={() => setMode('peak')}
            aria-pressed={mode === 'peak'}
          >
            When peak arrives
          </button>
        </div>

        {(meta.isLoading || (isLoading && !cells.length)) && (
          <p className="note">Loading forecast…</p>
        )}
        {(error || meta.error) && (
          <p className="note note--bad">{(error ?? meta.error).message}</p>
        )}

        {mode === 'stage' && Boolean(cells.length) && (
          <div className="headline">
            <span className="headline__date">{date ? formatDay(date) : '—'}</span>
            <span className="headline__peak">
              {peakCount
                ? `${peakCount.toLocaleString()} of ${cells.length.toLocaleString()} at or near peak`
                : 'No cells near peak yet'}
            </span>
          </div>
        )}

        {mode === 'stage' && Boolean(cells.length) && (
          <section className="bestplaces">
            <button
              type="button"
              className="bestplaces__toggle"
              onClick={() => setShowBest((v) => !v)}
              aria-expanded={showBest}
            >
              <span>At peak on {date ? formatDay(date) : 'this date'}</span>
              <em aria-hidden="true">{showBest ? '−' : '+'}</em>
            </button>

            {showBest && (
              <>
                {(placesLoading || fine.isLoading) && <p className="note">Looking…</p>}
                {best.length > 0 && (
                  <ol className="bestplaces__list">
                    {best.map((p) => (
                      <li key={`${p.name}-${p.state}`}>
                        <button
                          type="button"
                          onClick={() => {
                            setFocus({ lat: p.lat, lon: p.lon, nonce: Date.now() });
                          }}
                        >
                          <strong>{p.name}</strong>
                          <span>{p.state ? `${p.state} · ` : ''}{describeKind(p.kind)}</span>
                        </button>
                      </li>
                    ))}
                  </ol>
                )}
                {!placesLoading && !fine.isLoading && best.length === 0 && (
                  <p className="note">
                    Nothing is at peak on this date. Try a week further into the
                    season.
                  </p>
                )}
                <p className="note">
                  Recognisable places whose own hexagon is at peak, nearest the
                  middle of the band rather than its edge, at most two to a
                  state. Ranked by how well known a place is, which is all the
                  index knows: it cannot tell you whether somewhere is worth the
                  drive, only that the leaves there are out. Tap one to fly to it.
                </p>
              </>
            )}
          </section>
        )}

        {mode === 'peak' && (
          <section className="legend">
            <h2>Peak arrives</h2>
            {peak.isLoading && <p className="note">Loading peak dates…</p>}
            {peak.error && <p className="note note--bad">{peak.error.message}</p>}
            {peak.data && (
              <>
                <div className="peakramp" aria-hidden="true" />
                <div className="peakramp__ticks">
                  {peakTicks.map((t) => (
                    <span key={t.label} style={{ left: `${t.at * 100}%` }}>{t.label}</span>
                  ))}
                </div>
                <p className="note">
                  {peakless.toLocaleString()} hexagons are grey. Every one of them
                  has no forecast at all rather than a peak outside the season, so
                  grey here means a hole in the data and not a forest that stays
                  put. This view does not change with the date, so the slider is
                  put away while it is open.
                </p>
                {/* The fault this view exposes that the stage map hid. Saying it
                    on About the build is not enough: nobody reads that page
                    while looking at this map, and the whole point of drawing
                    peak dates is that people will read dates off them. */}
                <p className="note note--warn">
                  The Pacific Northwest is about a week early here. The model
                  reads nearness to the sea along an east–west line, which is
                  right on the Atlantic and backwards on the Pacific, so
                  Washington and Oregon are drawn peaking with the northern
                  Rockies when they run later.{' '}
                  <a href="#/about-the-build">What else it gets wrong.</a>
                </p>
              </>
            )}
          </section>
        )}

        {/* The legend stays visible at every size. Without it the colours on
            the map mean nothing, and the counts are how you see where the
            season actually is. On a phone it lays out in two columns. */}
        {mode === 'stage' && Boolean(cells.length) && (
          <section className="legend">
            <h2>Stage</h2>
            <div className="legend__rows">
              {STAGES.map((s) => (
                <div className="legend__row" key={s.key}>
                  <span className="swatch" style={{ background: `rgb(${s.rgb.join(',')})` }} />
                  <span className="legend__label">{s.label}</span>
                  <span className="legend__count">{(counts[s.key] ?? 0).toLocaleString()}</span>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* Reference rather than answer: folded away on a phone. */}
        {/* Outside panel__more on purpose. This is the one thing on the page
            that must not be missable: past the horizon the map is drawing a
            typical year, and ADR-0005 forbids presenting that as a forecast.
            Folded into the collapsed section it was invisible on a phone --
            and the slider's horizon tick has its wording dropped at that
            width too, so nothing said it at all. */}
        {beyondHorizon && (
          <p className="note note--warn">
            Beyond the 16-day weather forecast. This date is estimated from a
            three-year average rather than a forecast. Treat it as a typical year, not a
            prediction about this one.
          </p>
        )}

        <div className="panel__more">
        <footer>
          <p>
            A model, not an official forecast. No official record of peak dates
            exists, so it is checked against volunteer observations of real
            leaves instead. <a href="#/how-it-works">How it works</a>.
          </p>
          {/* How far the load has got. Most of the map is grey while the
              nightly backfill works through the country against a metered
              weather API, and a map that is merely unfinished should say so
              rather than look broken. */}
          {meta.data?.statesForecast != null && meta.data.statesForecast < meta.data.stateCount && (
            <p className="build">
              Forecast so far for {meta.data.statesForecast} of {meta.data.stateCount} states
              {meta.data.cellsForecast != null && meta.data.cellCount
                ? ` · ${Math.round((100 * meta.data.cellsForecast) / meta.data.cellCount)}% of the map`
                : ''}
              . The rest is still loading, a few states a night.
            </p>
          )}
          {meta.data && (
            <p className="build">
              model {meta.data.modelVersion}
              {/* Schema version is a backend concept; a static build reports
                  the payload format instead of an empty "schema v?". */}
              {meta.data.database?.schemaVersion
                ? ` · schema v${meta.data.database.schemaVersion}`
                : meta.data.format
                  ? ` · ${meta.data.format}`
                  : ''}
              {meta.data.generatedAt && ` · built ${meta.data.generatedAt.slice(0, 10)}`}
            </p>
          )}
        </footer>
        </div>
      </aside>

      {mode === 'stage' && seasonStart && date && (
        <TimeSlider
          seasonStart={seasonStart}
          seasonEnd={seasonEnd}
          value={date}
          onChange={setDate}
          horizonDate={horizon}
        />
      )}

      {selected && (
        <DetailPanel h3={selected} date={date} onClose={() => setSelected(null)} />
      )}
    </div>
  );
}
