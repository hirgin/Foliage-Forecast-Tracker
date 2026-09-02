/**
 * State labels that sit inside the state they name.
 *
 * At the national view a state label is centred correctly and still
 * unreadable, because the name is wider than the state. Connecticut's label
 * point is -72.729, 41.648 -- the middle of Connecticut -- but "CONNECTICUT"
 * is about 90 px wide where Connecticut is about 17, so most of the word lands
 * in New York and the Atlantic. Measured, after first assuming the anchors
 * were wrong: they are not, and a fix aimed at them would have done nothing.
 *
 * Rhode Island and Delaware settle the design. Both are about 7 px wide at the
 * national view, so their full names would have to be roughly one pixel tall
 * to fit inside them. No amount of placement logic changes that, which is why
 * every state shows its postal code at national zoom and its full name once
 * the state is wide enough to hold one: uniform, complete, inside the borders.
 */

/** Postal codes for the contiguous states. */
export const STATE_ABBR = {
  Alabama: 'AL',
  Arizona: 'AZ',
  Arkansas: 'AR',
  California: 'CA',
  Colorado: 'CO',
  Connecticut: 'CT',
  Delaware: 'DE',
  Florida: 'FL',
  Georgia: 'GA',
  Idaho: 'ID',
  Illinois: 'IL',
  Indiana: 'IN',
  Iowa: 'IA',
  Kansas: 'KS',
  Kentucky: 'KY',
  Louisiana: 'LA',
  Maine: 'ME',
  Maryland: 'MD',
  Massachusetts: 'MA',
  Michigan: 'MI',
  Minnesota: 'MN',
  Mississippi: 'MS',
  Missouri: 'MO',
  Montana: 'MT',
  Nebraska: 'NE',
  Nevada: 'NV',
  'New Hampshire': 'NH',
  'New Jersey': 'NJ',
  'New Mexico': 'NM',
  'New York': 'NY',
  'North Carolina': 'NC',
  'North Dakota': 'ND',
  Ohio: 'OH',
  Oklahoma: 'OK',
  Oregon: 'OR',
  Pennsylvania: 'PA',
  'Rhode Island': 'RI',
  'South Carolina': 'SC',
  'South Dakota': 'SD',
  Tennessee: 'TN',
  Texas: 'TX',
  Utah: 'UT',
  Vermont: 'VT',
  Virginia: 'VA',
  Washington: 'WA',
  'West Virginia': 'WV',
  Wisconsin: 'WI',
  Wyoming: 'WY',
};

/**
 * East-west extent of each state, in degrees of longitude.
 *
 * The input to working out when a name fits, rather than a judgement made by
 * eye about each state in turn. This is bounding-box width, which overstates
 * the room actually available in a state that is not a rectangle -- Maryland
 * spans 4.5 degrees and is thin down most of it -- so [USABLE_WIDTH] discounts
 * it below.
 */
export const STATE_SPAN_DEG = {
  Alabama: 3.6,
  Arizona: 5.8,
  Arkansas: 5.0,
  California: 10.3,
  Colorado: 7.1,
  Connecticut: 1.9,
  Delaware: 0.8,
  Florida: 7.6,
  Georgia: 4.8,
  Idaho: 6.2,
  Illinois: 4.5,
  Indiana: 3.3,
  Iowa: 6.5,
  Kansas: 7.5,
  Kentucky: 7.7,
  Louisiana: 5.2,
  Maine: 4.2,
  Maryland: 4.5,
  Massachusetts: 3.6,
  Michigan: 8.0,
  Minnesota: 7.7,
  Mississippi: 3.6,
  Missouri: 6.7,
  Montana: 12.0,
  Nebraska: 8.8,
  Nevada: 6.0,
  'New Hampshire': 1.9,
  'New Jersey': 1.7,
  'New Mexico': 6.1,
  'New York': 7.9,
  'North Carolina': 8.8,
  'North Dakota': 7.5,
  Ohio: 4.3,
  Oklahoma: 8.6,
  Oregon: 8.1,
  Pennsylvania: 5.8,
  'Rhode Island': 0.8,
  'South Carolina': 4.9,
  'South Dakota': 7.7,
  Tennessee: 8.7,
  Texas: 13.1,
  Utah: 5.1,
  Vermont: 1.9,
  Virginia: 8.5,
  Washington: 7.9,
  'West Virginia': 4.9,
  Wisconsin: 6.1,
  Wyoming: 7.0,
};

/**
 * Below this zoom every state shows its code, however much room it has.
 *
 * A floor rather than a per-state answer, because the national view should
 * read as one map rather than a patchwork of long names and two-letter codes.
 * Texas has room to spell itself out at zoom 3.7; doing so while Rhode Island
 * cannot is exactly the inconsistency this replaced.
 */
export const NATIONAL_CODE_ZOOM = 4.5;

/** Web-mercator tile size, for turning degrees into pixels. */
const TILE_PX = 256;

/** Share of a bounding box a label may occupy. States are not rectangles. */
const USABLE_WIDTH = 0.55;

/** Rough width of one uppercase character as a fraction of font size. */
const CHAR_WIDTH_EM = 0.62;

/** Font size the thresholds are computed against; see STATE_LABEL_SIZE. */
const NOMINAL_FONT_PX = 14;

/** Width of a state's rendered name, in pixels. */
export function nameWidthPx(stateName) {
  return stateName.length * NOMINAL_FONT_PX * CHAR_WIDTH_EM;
}

/** Width of a state on screen at a given zoom, in pixels. */
export function stateWidthPx(stateName, zoom) {
  const span = STATE_SPAN_DEG[stateName];
  if (span === undefined) return Infinity;
  return (span * TILE_PX * Math.pow(2, zoom)) / 360;
}

/**
 * The zoom at which a state's full name first fits inside it.
 *
 * Derived from the state's own width rather than chosen by eye, so adding a
 * state or changing the font is a one-line change rather than a re-tuning
 * exercise. Clamped below by [NATIONAL_CODE_ZOOM] so the national view stays
 * uniform.
 */
export function fullNameZoom(stateName) {
  const span = STATE_SPAN_DEG[stateName];
  if (span === undefined) return NATIONAL_CODE_ZOOM;
  const needed = nameWidthPx(stateName);
  const zoom = Math.log2((needed * 360) / (TILE_PX * span * USABLE_WIDTH));
  // Rounded up to a quarter zoom: the inputs are approximations, and a
  // threshold quoted to six decimal places would imply a precision none of
  // this has.
  return Math.max(NATIONAL_CODE_ZOOM, Math.ceil(zoom * 4) / 4);
}

/** Every state's threshold, computed once. */
export const FULL_NAME_ZOOM = Object.fromEntries(
  Object.keys(STATE_ABBR).map((state) => [state, fullNameZoom(state)]),
);

/** The style's own name lookup, preserved so non-English labels still work. */
export const NAME_EXPRESSION = ['coalesce', ['get', 'name_en'], ['get', 'name']];

/**
 * A `text-field` expression: postal code until the full name fits.
 *
 * One `step` on zoom with a stop per distinct threshold. Not nested steps,
 * which is the obvious way to write it and is rejected -- MapLibre allows a
 * single zoom-based subexpression per expression, so the zoom switch has to be
 * the outermost thing and each stop a complete answer for that zoom band.
 */
export function stateLabelField(nameExpression = NAME_EXPRESSION) {
  const thresholds = [...new Set(Object.values(FULL_NAME_ZOOM))].sort((a, b) => a - b);

  // States still too narrow at this zoom, as match pairs. Anything absent
  // falls through to its full name.
  const codesAt = (zoom) => {
    const pairs = [];
    for (const [state, threshold] of Object.entries(FULL_NAME_ZOOM)) {
      if (zoom < threshold) pairs.push(state, STATE_ABBR[state]);
    }
    return pairs.length ? ['match', nameExpression, ...pairs, nameExpression] : nameExpression;
  };

  const stops = [];
  for (const zoom of thresholds) stops.push(zoom, codesAt(zoom));
  return ['step', ['zoom'], codesAt(-Infinity), ...stops];
}

/**
 * Placement priority within the state label layer.
 *
 * Colliding labels are dropped, not moved, and which one survives is otherwise
 * decided by the order features happen to arrive from the tile -- which is to
 * say, arbitrarily. Massachusetts was being dropped in favour of Rhode Island
 * beside it. Wider states sort first and therefore win, so what survives a
 * genuinely crowded corner is the label a reader is most likely to want.
 */
export function stateLabelSortKey() {
  const pairs = [];
  for (const [state, span] of Object.entries(STATE_SPAN_DEG)) {
    // Negated so wider states sort lower, and lower is placed first.
    pairs.push(state, -span);
  }
  return ['match', NAME_EXPRESSION, ...pairs, 0];
}

/**
 * Nudges for the states packed too tightly to all be drawn.
 *
 * Southern New England is the whole reason this exists. At the national view
 * Massachusetts, Connecticut and Rhode Island have label points about eight
 * pixels apart and each code needs about fifteen, so two of the three were
 * being dropped however they were prioritised -- there is no ordering that
 * fits three labels into the space for one.
 *
 * The offsets fan them apart along the axis each state actually extends:
 * Massachusetts east and north, Connecticut west, Rhode Island south-east.
 * Every one stays over or against its own state, and they are in ems so they
 * scale with the text rather than drifting as the label grows.
 *
 * Deliberately small and deliberately few. An offset is a lie about where a
 * place is, and it is worth telling only where the alternative is saying
 * nothing about that place at all.
 */
export const LABEL_OFFSET_EM = {
  Massachusetts: [0.55, -0.5],
  Connecticut: [-0.7, 0.35],
  'Rhode Island': [0.85, 0.6],
  'New Hampshire': [0.15, -0.45],
  Vermont: [-0.35, -0.4],
  'New Jersey': [0.5, 0.25],
  Delaware: [0.7, 0.15],
  Maryland: [-0.5, 0.35],
};

/** A `text-offset` expression carrying [LABEL_OFFSET_EM]; zero elsewhere. */
export function stateLabelOffset() {
  const pairs = [];
  for (const [state, offset] of Object.entries(LABEL_OFFSET_EM)) {
    pairs.push(state, ['literal', offset]);
  }
  return ['match', NAME_EXPRESSION, ...pairs, ['literal', [0, 0]]];
}

/**
 * What a state is labelled at a given zoom.
 *
 * A plain-JavaScript mirror of the expression, so the behaviour can be
 * asserted without a WebGL context. The expression is what the map draws;
 * this exists for the tests and for reasoning about the thresholds.
 */
export function labelAt(stateName, zoom) {
  const threshold = FULL_NAME_ZOOM[stateName];
  if (threshold === undefined) return stateName;
  return zoom >= threshold ? stateName : STATE_ABBR[stateName];
}
