/**
 * Sequential ramp for canopy density.
 *
 * Canopy is an ordered quantity, so the ramp varies mainly in lightness —
 * that keeps the ordering readable in greyscale and for red-green colour
 * blindness, where a hue-only ramp would collapse.
 */
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
