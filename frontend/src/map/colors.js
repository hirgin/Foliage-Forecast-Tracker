/**
 * The foliage ramp.
 *
 * Ordered by season stage rather than by hue family, because the quantity
 * being shown *is* an ordered progression: green through gold and orange to
 * red, then the brown of a canopy that has dropped. Lightness rises to a peak
 * at PEAK and falls away after, so the most important stage is also the most
 * visually prominent — and the ordering survives greyscale.
 */
export const STAGES = [
  { key: 'NO_CHANGE', label: 'No change', rgb: [74, 112, 66] },
  { key: 'PATCHY', label: 'Patchy', rgb: [141, 163, 62] },
  { key: 'PARTIAL', label: 'Partial', rgb: [212, 163, 39] },
  { key: 'NEAR_PEAK', label: 'Near peak', rgb: [224, 124, 42] },
  { key: 'PEAK', label: 'Peak', rgb: [204, 62, 44] },
  // Darker than peak's red so the two separate by brightness and not only by
  // hue -- they were 88.5 against 90.9, which is the same brightness, and hue
  // alone is the first thing red-green colour blindness takes away.
  //
  // But the first attempt at that took the colour out along with the
  // brightness, landing at chroma 37 against the "not forecast" grey's 6.
  // Over a dark basemap at 45-70% alpha those read the same, so a large area
  // past peak looked like ground with no data rather than leaves on the turn.
  // New York was the first state big enough to show it.
  //
  // So: keep the brightness gap from red, put the colour back. A russet at
  // chroma 62 cannot be mistaken for the neutral grey of missing data, and
  // stays 12 luminance below peak.
  { key: 'PAST_PEAK', label: 'Past peak', rgb: [108, 74, 46] },
];

/**
 * A cell that exists but has no forecast yet.
 *
 * Deliberately visible. It used to be [70, 66, 60] at 45% alpha over a
 * near-black basemap, which drew the hexagon and made it indistinguishable
 * from empty ground -- so a grid that was merely waiting on data looked
 * broken. Most of the country is in this state until the nightly backfill
 * finishes, and "not computed yet" is a different claim from "no forest
 * here", which is genuinely blank.
 *
 * Still clearly subordinate to the stage colours: neutral, and no more opaque
 * than it has to be to read as a hexagon.
 */
/**
 * Evergreen forest, which is not a stage of autumn but the absence of one.
 *
 * Kept out of [STAGES] deliberately: that list is a ramp, and the colour
 * interpolator walks it in order, so putting evergreen in it made the map
 * blend evergreen into "no change" as though a spruce were part-way to being
 * a maple.
 *
 * A cool blue-green, chosen to sit apart from both neighbours it could be
 * confused with. It must not read as [NO_FORECAST_RGB] grey, because these
 * hexagons are not missing -- they are known, and known to stay green. And it
 * must not read as NO_CHANGE green, because that is a deciduous wood that has
 * not turned *yet* and will.
 */
export const EVERGREEN_STAGE = { key: 'EVERGREEN', label: 'Evergreen', rgb: [58, 92, 84] };

export const NO_FORECAST_RGB = [92, 90, 86];
export const NO_FORECAST_ALPHA = 120;

/**
 * Ground that is tiled but carries no forest -- farmland, towns, water.
 *
 * A different claim from [NO_FORECAST_RGB], and it has to look like one: that
 * grey means "not computed yet" and will eventually turn a colour, while this
 * never will. So it sits darker and flatter, close enough to the basemap to
 * read as ground rather than as data, but present enough that the grid has no
 * holes in it.
 *
 * Drawn at all because the alternative was worse. Leaving these cells out left
 * the map pitted wherever there is no forest -- a quarter of Ohio, a fifth of
 * Maryland -- and a hole reads as broken data, not as a cornfield.
 *
 * **Bright enough to see, which the first attempt was not.** At [46, 48, 44]
 * and 52% alpha this composited to rgb(31, 31, 28) over the basemap: an
 * honest neutral in the abstract and indistinguishable from a hole on screen,
 * so the map still looked pitted across Iowa and Kansas. Being subordinate to
 * the stage colours is worth nothing if it reads as missing data. It is now
 * near-opaque and clearly a drawn tile -- still plainly not one of the six
 * stage colours, but unmistakably something rather than nothing.
 */
export const NO_FOREST_RGB = [62, 66, 58];
export const NO_FOREST_ALPHA = 236;

// Evergreen is looked up like a stage even though it is not one, so the
// legend and the detail panel can name it without special-casing either.
const BY_KEY = Object.fromEntries(
  [...STAGES, EVERGREEN_STAGE].map((s) => [s.key, s]),
);

export function stageColor(stage) {
  return BY_KEY[stage]?.rgb ?? [70, 66, 60];
}

export function stageLabel(stage) {
  return BY_KEY[stage]?.label ?? 'Unknown';
}

/** Stage boundaries, matching PhenologyModel.stageOf on the backend. */
const BOUNDS = {
  NO_CHANGE: [0, 10],
  PATCHY: [10, 30],
  PARTIAL: [30, 55],
  NEAR_PEAK: [55, 75],
  PEAK: [75, 90],
  PAST_PEAK: [90, 100],
};

const lerp = (a, b, t) => a + (b - a) * t;

/**
 * Which stage a progression falls in — the client-side twin of
 * PhenologyModel.stageOf, and it has to keep matching it.
 *
 * Scored cells carry their stage from the backend and never need this. It is
 * for the cells filled in from their neighbours, whose progression is an
 * average computed here and so has no stage attached to it.
 */
export function stageForProgression(progression) {
  for (const [stage, [lo, hi]] of Object.entries(BOUNDS)) {
    if (progression >= lo && progression < hi) return stage;
  }
  return progression >= 100 ? 'PAST_PEAK' : 'NO_CHANGE';
}

/**
 * Colour by progression, interpolating between stage anchors.
 *
 * Flat-filling each stage hid a real signal. Northern Vermont runs about six
 * progression points ahead of the south — the correct direction, achieved
 * despite sitting lower and therefore warmer — but 79 and 85 are both PEAK, so
 * the whole state rendered as one flat red and the north-to-south march was
 * invisible. Interpolating within the band restores it without changing a
 * single number in the model.
 */
/**
 * Alpha carries confidence, and is deliberately well short of opaque.
 *
 * The basemap underneath holds terrain shading, water and settlement shapes,
 * all of which help place a hexagon in the real world. Painting over it at
 * full strength throws that away. The range here (~45-70%) keeps the ramp
 * legible while letting the ground show through; place names are handled
 * separately by drawing Esri's reference layer above the data.
 */
export function foliageColor(cell) {
  // Evergreen is flat: there is no progression through it, so nothing to
  // interpolate and no confidence worth varying alpha over.
  if (cell.stage === 'EVERGREEN') return [...EVERGREEN_STAGE.rgb, 190];

  // No stage means no forecast for this cell yet, which is not the same as a
  // score of zero and should not be drawn as one.
  if (cell.stage == null) return [...NO_FORECAST_RGB, NO_FORECAST_ALPHA];

  const [r, g, b] = progressionColor(cell.progression, cell.stage);
  const alpha = Math.round(255 * (0.45 + 0.25 * (cell.confidence ?? 1)));
  return [r, g, b, alpha];
}

export function progressionColor(progression, stage) {
  const i = STAGES.findIndex((s) => s.key === stage);
  if (i < 0) return [70, 66, 60];

  const [lo, hi] = BOUNDS[stage];
  // How far through its own stage this cell is.
  const t = hi > lo ? Math.min(1, Math.max(0, (progression - lo) / (hi - lo))) : 0;

  const from = STAGES[i].rgb;
  // Blend toward the next stage, so the ramp is continuous across boundaries
  // rather than stepping. The last stage has nowhere to go, so it deepens.
  const to = STAGES[i + 1]?.rgb ?? from.map((c) => Math.round(c * 0.72));
  return [0, 1, 2].map((k) => Math.round(lerp(from[k], to[k], t)));
}

/** Canopy ramp, retained for the grid-only view. */
export const CANOPY_STOPS = [
  { min: 0, label: 'Under 20%', rgb: [92, 84, 68] },
  { min: 20, label: '20–39%', rgb: [104, 122, 62] },
  { min: 40, label: '40–59%', rgb: [124, 156, 66] },
  { min: 60, label: '60–79%', rgb: [158, 194, 77] },
  { min: 80, label: '80%+', rgb: [198, 230, 106] },
];

export function canopyColor(pct) {
  if (pct == null) return [70, 66, 60, 90];
  let chosen = CANOPY_STOPS[0];
  for (const stop of CANOPY_STOPS) if (pct >= stop.min) chosen = stop;
  return [...chosen.rgb, 205];
}
