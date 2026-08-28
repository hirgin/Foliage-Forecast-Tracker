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
  { key: 'PAST_PEAK', label: 'Past peak', rgb: [116, 83, 62] },
];

const BY_KEY = Object.fromEntries(STAGES.map((s) => [s.key, s]));

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
