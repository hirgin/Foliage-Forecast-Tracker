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

/**
 * Alpha carries confidence, so a climatological October reads as visibly less
 * certain than an observed September without needing a second colour channel.
 * The floor keeps low-confidence cells legible rather than invisible.
 */
export function foliageColor(cell) {
  const [r, g, b] = stageColor(cell.stage);
  const alpha = Math.round(255 * (0.45 + 0.55 * (cell.confidence ?? 1)));
  return [r, g, b, alpha];
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
