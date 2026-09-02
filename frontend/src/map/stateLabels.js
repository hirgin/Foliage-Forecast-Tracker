/**
 * State labels that fit inside the state they name.
 *
 * At the national default view a state label is centred correctly and still
 * unreadable, because the name is wider than the state. Connecticut's label
 * point is -72.729, 41.648 -- the middle of Connecticut -- but "CONNECTICUT"
 * is about 90 px wide where Connecticut is about 15 px, so two thirds of the
 * word lands in New York and the Atlantic. Measured, after first assuming the
 * anchor points were wrong: they are not, and the fix for a bad anchor would
 * have done nothing here.
 *
 * So the crowded states get their postal abbreviation until there is room for
 * the full name. This is the ordinary atlas convention rather than an
 * invention, and it is why printed maps of New England read cleanly at small
 * scale.
 */

/**
 * Zoom at which each state's full name first fits inside its own borders.
 *
 * Per state rather than one global threshold, because "too small" is a
 * property of the state and not of the map. Rhode Island needs far more zoom
 * before "RHODE ISLAND" fits than Maryland needs for "MARYLAND", and a single
 * cutoff would either abbreviate Maryland long after it had room or spill
 * Rhode Island long before it did.
 *
 * Values are the zoom at which the full name stops overflowing, judged
 * against the rendered map. Anything not listed is a state whose name fits at
 * every zoom the label is drawn at, and is left alone.
 */
export const FULL_NAME_ZOOM = {
  'Rhode Island': 8,
  Delaware: 7.5,
  Connecticut: 7,
  'New Jersey': 7,
  'New Hampshire': 6.5,
  Massachusetts: 6.5,
  Vermont: 6,
  Maryland: 6,
  // Not small states, but long names on narrow ones. Illinois and
  // Mississippi are both taller than they are wide, and their names overhang
  // far enough into their neighbours that the label was dropped entirely
  // rather than drawn badly -- which is why they were missing from the
  // national view before anyone noticed the small states were.
  'West Virginia': 5.5,
  Mississippi: 5.5,
  Illinois: 5,
};

/** Postal codes, used only for the states above. */
export const STATE_ABBR = {
  'Rhode Island': 'RI',
  Delaware: 'DE',
  Connecticut: 'CT',
  'New Jersey': 'NJ',
  'New Hampshire': 'NH',
  Massachusetts: 'MA',
  Vermont: 'VT',
  Maryland: 'MD',
  'West Virginia': 'WV',
  Mississippi: 'MS',
  Illinois: 'IL',
};

/** The style's own name lookup, preserved so non-English labels still work. */
export const NAME_EXPRESSION = ['coalesce', ['get', 'name_en'], ['get', 'name']];

/**
 * A `text-field` expression that abbreviates a state until its name fits.
 *
 * Built as nested `step` expressions on zoom -- one per threshold -- because
 * MapLibre evaluates `text-field` per feature per zoom, so the switch happens
 * in the renderer rather than needing the map to be rebuilt or re-filtered as
 * the user zooms.
 *
 * States with no entry in [FULL_NAME_ZOOM] fall through to their full name at
 * every zoom, which is the same expression the basemap style already used.
 */
export function stateLabelField(nameExpression = NAME_EXPRESSION) {
  // One `step` on zoom, with a stop per threshold.
  //
  // Not nested steps, which is the obvious way to write this and is rejected:
  // MapLibre allows only one zoom-based subexpression per expression, so the
  // zoom switch has to be the single outermost thing and every stop has to be
  // a complete answer for that zoom band.
  const thresholds = [...new Set(Object.values(FULL_NAME_ZOOM))].sort((a, b) => a - b);

  // Which states are still too small at a given zoom, as match pairs. An
  // empty result means everything fits and the name passes through untouched.
  const abbreviationsAbove = (zoom) => {
    const pairs = [];
    for (const [state, threshold] of Object.entries(FULL_NAME_ZOOM)) {
      if (zoom < threshold) pairs.push(state, STATE_ABBR[state]);
    }
    return pairs.length ? ['match', nameExpression, ...pairs, nameExpression] : nameExpression;
  };

  const stops = [];
  for (const zoom of thresholds) stops.push(zoom, abbreviationsAbove(zoom));

  // The first argument is the value below the lowest threshold, where every
  // listed state is abbreviated.
  return ['step', ['zoom'], abbreviationsAbove(-Infinity), ...stops];
}

/**
 * What a state would be labelled at a given zoom.
 *
 * A plain-JavaScript mirror of the expression above, so the behaviour can be
 * asserted in a unit test without a WebGL context or a running map. It exists
 * for the tests and for reasoning about the thresholds; the map itself uses
 * the expression, which is the thing that must be correct.
 */
export function labelAt(stateName, zoom) {
  const threshold = FULL_NAME_ZOOM[stateName];
  if (threshold === undefined) return stateName;
  return zoom >= threshold ? stateName : STATE_ABBR[stateName];
}

/**
 * Placement priority within the state label layer.
 *
 * Labels that collide are not moved, they are dropped, and which one survives
 * is otherwise decided by the order features happen to arrive from the tile --
 * which is to say, arbitrarily. At national zoom Massachusetts and Rhode
 * Island are about seven pixels apart and only one of them can be drawn; left
 * to chance it was Rhode Island.
 *
 * Lower sorts first and therefore wins. States whose names already fit get 0,
 * so the map's large, legible labels are placed before the crowded ones
 * compete for what is left; the rest are ordered by how much zoom they need,
 * which is a proxy for how small they are. So Massachusetts beats Rhode
 * Island, and both still appear as soon as there is room for them.
 */
export function stateLabelSortKey() {
  const pairs = [];
  for (const [state, zoom] of Object.entries(FULL_NAME_ZOOM)) pairs.push(state, zoom);
  return ['match', NAME_EXPRESSION, ...pairs, 0];
}
